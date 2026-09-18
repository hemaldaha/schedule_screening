# AML Screening Pipeline — Architecture & Implementation Guide

> Version: 2.2
> Date: 2026-06-27
> Status: Implementation in progress — Steps 1–11 complete (Task 15 done)

---

## 1. What This Document Covers

This document describes the full set of changes required to upgrade the AML screening
pipeline from v1 to v2. It is the single source of truth — all architectural decisions,
code changes, database changes, and implementation steps are here.

---

## 2. What Exists Today (v1)

```
Customer record
      │
      ▼
NameRefinerUtil.refineName()     ← strips non-alpha, uppercases, removes AND/OR/NOT
      │
      ▼
SanctionStrategyBuilder.build()  ← builds one strategy per sanction type
      │
      ▼
ScreeningSolrQueryBuilder        ← one query style for all customers
      │
      ▼
SolrQueryExecutor                ← sends query to Solr
      │
      ▼
sch_screening_match              ← results saved here
```

**Problems with v1:**
- Single-token names (e.g. `"Amana"`) → silently skipped. False-negative risk on UN/FIU lists.
- All-initials names (e.g. `"R M M T"`) with valid NIC → silently skipped. NIC-only query possible but not done.
- No audit trail for skipped/transformed customers. FATF non-compliant.
- No distinction between data-quality skips and infrastructure failures.
- `AUTO_INCREMENT` primary key — write bottleneck under parallel batch loads.

---

## 3. What v2 Adds

```
Customer record
      │
      ▼
NameNormalizer.classify()        ← NEW: classifies name quality, returns reason code + action
      │
      ├─ SKIP_PLACEHOLDER / SKIP_ANOMALY (no NIC)
      │       └─► write SKIPPED exception row → stop
      │
      ├─ SCREEN_TRANSFORMED (dots / CamelCase / invalid chars stripped)
      │       └─► write TRANSFORMED_AND_SCREENED exception row → screen with cleaned name
      │
      ├─ SCREEN_RESTRICTED (single-token name)
      │       └─► write SCREENED_RESTRICTED exception row → screen with restricted query
      │
      └─ NAME_ALL_INITIALS + valid NIC  (rerouted in composeStrategy)
              └─► write SCREENED_RESTRICTED exception row → NIC-only query

SanctionStrategyBuilder          ← UPDATED: determines ScreeningMode per customer
      │
      ▼
AdvancedScreeningSolrQueryBuilder ← NEW: 3 query strategies per list (NORMAL/RESTRICTED/NIC_ONLY)
      │
      ▼
SolrQueryExecutor (unchanged)
      │
      ├─► sch_screening_match              ← unchanged
      └─► aml.screening_exceptions         ← NEW: full audit trail
```

---

## 4. New / Changed Files

### 4.1 New files (drop-ins — no modifications needed)

| File | Package | Purpose |
|---|---|---|
| `AdvancedScreeningSolrQueryBuilder.java` | `engine/solr/` | Replaces `ScreeningSolrQueryBuilder`. Three query strategies per list. |
| `ScreeningMode.java` | `engine/solr/` | Enum: `NORMAL`, `RESTRICTED`, `NIC_ONLY` |
| `NameNormalizer.java` | `util/` | Classifies customer name quality. Returns `Result(reasonCode, action, screenName)` |
| `UuidV7.java` | `util/` | RFC 9562 UUIDv7 generator. Time-ordered, no DB coordination. |

Source location: `qbuilderTest/production/com/kmpg/aml/screening/`

### 4.2 Updated files

| File | Change |
|---|---|
| `SanctionStrategyBuilder.java` | `queryBuilder` field type changed; `composeStrategy()` adds per-customer mode determination; `resolveStrategy()` gains `ScreeningMode` parameter; sets `matchCount` before commit; fixed `return List.of()` → `return new ScreeningResult(List.of(), mode)` |
| `ScreeningProcessor.java` | Inject `ScreeningExceptionRepository` + `ScreeningRunRepository`; inserts RUNNING row at run start; calls `updateRunRecord()` in `whenComplete` callback; private method handles all completion logic |
| `ScreeningMetrics.java` | Added `cumulativeEligible` AtomicLong (never reset mid-run on flush); `getTotalEligible()` exposes run total |
| `ScoringConsumer.java` | Pass `screening_mode` string to `MatchRateClient` when building the scoring request |
| `MatchRateClient.java` | Add `screening_mode` field to the JSON body sent to Python |
| `match_rate_V3.py` | Early-exit for `NIC_ONLY` mode — skip name scoring, return all candidates at score 95 |
| `ScreeningExceptionWriter.java` | `startException()` sets `screeningMode` and `screenedAt` at entity creation; `setListFailed()` signature extended to `(exc, type, reason, details)`; first-write-wins null guard added |
| `ScreeningExceptionEntity.java` | Converted to `@Getter @Setter`; added `screeningMode`, `screenedAt`, `matchCount` fields; `@Setter(AccessLevel.NONE)` on `createdAt` |
| `ScreeningExceptionRepository.java` | Added 5 paginated drill-down methods; 4 aggregation count queries for run completion |
| `ScreeningExceptionController.java` | Full rewrite — base mapping to `/api/screening`; 4 endpoints (see §16) |
| `screening_exceptions_ddl.sql` | Added columns `screening_mode`, `screened_at`, `match_count`, `failure_reason`, `failure_details`; `aml.screening_runs` table added |

### 4.3 New files to create in production

| File | Package | Purpose |
|---|---|---|
| `ScreeningExceptionEntity.java` | `entity/` | JPA entity for `aml.screening_exceptions` |
| `ScreeningExceptionRepository.java` | `entity/persistence/` | Spring Data repository — list, paginated drill-down, and aggregation queries |
| `ScreeningExceptionWriter.java` | `engine/` | Service — builds and saves exception rows |
| `ScreeningRunEntity.java` | `entity/` | JPA entity for `aml.screening_runs` |
| `ScreeningRunRepository.java` | `entity/persistence/` | `findAllByOrderByStartedAtDesc()` |
| `SummaryExceptionDto.java` | `dto/` | Lightweight list-view record for REST responses |
| `DetailedExceptionDto.java` | `dto/` | Full audit-trail view class for REST responses |
| `RunSummaryDto.java` | `dto/` | Per-run summary record for the runs-list endpoint |
| `ExceptionFilter.java` | `controller/` | Enum — 6 filter constants, each holding its repository delegate; zero if/else in controller |
| `ScreeningExceptionController.java` | `controller/` | `@RestController` — 4 GET endpoints (see §16) |

---

## 5. ScreeningMode — Three Modes Explained

```java
public enum ScreeningMode { NORMAL, RESTRICTED, NIC_ONLY }
```

| Mode | When set | Query behaviour |
|---|---|---|
| `NORMAL` | Standard multi-word name | Full fuzzy + phonetic + wildcard. `NameRefinerUtil` applied. |
| `RESTRICTED` | `NAME_SINGLE_TOKEN` — only one name token | Exact phrase + fuzzy~1 + phonetic. No wildcard. Heavy cores (LXNX, DJ): exact phrase only. |
| `NIC_ONLY` | Name unusable (any reason: blank, placeholder, anomaly, all-initials) + valid Sri Lankan NIC | NIC field query only. No name clause at all. |

Mode is determined **per customer, per record** inside `composeStrategy()` — not once at build time.

---

## 6. NameNormalizer — Classification Pipeline

The classifier runs before any query is built. It returns a `Result` with three fields:
- `reasonCode` — stable string constant (never rename once stored in DB)
- `action` — what the pipeline should do with this customer
- `screenName` — the name to submit to Solr (may differ from original)

### Reason codes and actions

| Reason Code | What it means | Action |
|---|---|---|
| `NAME_BLANK` | Empty or null name | `SKIP_PLACEHOLDER` |
| `NAME_PLACEHOLDER` | Literal filler (e.g. "NOT MENTIONED", "N/A") | `SKIP_PLACEHOLDER` |
| `NAME_TRANSFORMED_DOTS` | Dot-separated initials expanded (e.g. `M.A.Fernando` → `MA Fernando`) | `SCREEN_TRANSFORMED` |
| `NAME_TRANSFORMED_CAMEL` | CamelCase split (e.g. `MohamedAliKhan` → `Mohamed Ali Khan`) | `SCREEN_TRANSFORMED` |
| `NAME_INVALID_CHARS_STRIPPED` | Digits/brackets stripped; enough tokens remain | `SCREEN_TRANSFORMED` |
| `NAME_INVALID_CHARS` | Digits/brackets stripped; insufficient tokens remain | `SKIP_ANOMALY` |
| `NAME_SINGLE_TOKEN` | Only one name token after all processing | `SCREEN_RESTRICTED` |
| `NAME_ALL_INITIALS` | Every token is a single letter | `SKIP_ANOMALY` in classifier; NIC rescue applies (see below) |

### NIC Rescue — independent of name quality

`NameNormalizer` classification output is **one of two independent inputs** to the eligibility
decision — it no longer solely gates the skip/screen outcome. NIC eligibility is checked
separately for every customer whose name is unusable.

A customer is **SKIPPED** only when **both** conditions fail:

```java
boolean nameUsable = norm.action() != Action.SKIP_PLACEHOLDER
                  && norm.action() != Action.SKIP_ANOMALY;

boolean shouldSkip = !nameUsable && !nics.isUsable();
```

When name is unusable but NIC is valid → `NIC_ONLY` mode. This applies uniformly to all
"name unusable" reason codes: `NAME_BLANK`, `NAME_PLACEHOLDER`, `NAME_INVALID_CHARS`,
`NAME_ALL_INITIALS`. No compliance basis for rescuing all-initials but not blank names —
a valid NIC alone can produce a sanctions hit regardless of why the name failed.

**Known gap:** customers with an unusable name and a valid NIC get zero coverage on
`un_consolidated_core`, `lxnx_entities_core`, `dj_entities_core`, `fiu_org_core` — those
cores have no NIC field. Real screening gap, not a deliberate exclusion. Deferred.

---

## 7. AdvancedScreeningSolrQueryBuilder — Query Methods

### Normal queries (unchanged from v1 in intent, rebuilt in v2)
```
buildFIUQuery(name, oldNic, newNic, passport)
buildFIUOrgQuery(name, policyNo)
buildUNQuery(name)
buildLocalWatchListQuery(name, oldNic, newNic)
buildLXNXQuery(name)
buildDJQuery(name)
```

### Restricted queries (single-token name — 5 new methods)
```
buildRestrictedFIUQuery(name, oldNic, newNic, passport)
buildRestrictedUNQuery(name)
buildRestrictedLocalWatchQuery(name, oldNic, newNic)
buildRestrictedLXNXQuery(name)
buildRestrictedDJQuery(name)
```

### NIC-only queries (all-initials + valid NIC — 2 new methods)
```
buildNicOnlyFIUQuery(oldNic, newNic, passportNo)    → queries nic_no_s, passport_no_ss
buildNicOnlyLocalWatchQuery(oldNic, newNic)         → queries nic_s
```

### Why heavy cores (LXNX, DJ) use exact-phrase only in restricted mode

LXNX and Dow Jones each hold ~7 million records. DoubleMetaphone collapses
many distinct names to the same phonetic code at that scale. Even `fuzzy~1`
on a common single token (e.g. `"Ali"`) returns thousands of irrelevant results.
Restricted mode on heavy cores = exact phrase only — precision over recall.

---

## 8. Dispatch Table — Which Method per List × Mode

| List (type string) | NORMAL | RESTRICTED | NIC_ONLY |
|---|---|---|---|
| `"FIU"` | `buildFIUQuery` | `buildRestrictedFIUQuery` | `buildNicOnlyFIUQuery` |
| `"FIU-ORG"` | `buildFIUOrgQuery` | **NOT_APPLICABLE** | **NOT_APPLICABLE** |
| `"consoli"` | `buildUNQuery` | `buildRestrictedUNQuery` | **NOT_APPLICABLE** |
| `"local_watchList"` | `buildLocalWatchListQuery` | `buildRestrictedLocalWatchQuery` | `buildNicOnlyLocalWatchQuery` |
| `"LXNX"` | `buildLXNXQuery` | `buildRestrictedLXNXQuery` | **NOT_APPLICABLE** |
| `"DOW_JONES"` | `buildDJQuery` | `buildRestrictedDJQuery` | **NOT_APPLICABLE** |

**NOT_APPLICABLE** means `Collections.emptyList()` is returned and the list column
in `screening_exceptions` is set to `NOT_APPLICABLE`.

Rationale for NOT_APPLICABLE cases:
- `FIU-ORG` + RESTRICTED: a single-token personal name queried against an organisation
  list produces noise, not compliance value.
- `FIU-ORG` + NIC_ONLY: NIC belongs to a person, not an organisation.
- `consoli`, `LXNX`, `DOW_JONES` + NIC_ONLY: these cores have no NIC field.

**NIC runs in parallel for FIU and local_watchList in NORMAL and RESTRICTED modes.**
`buildFIUQuery`, `buildRestrictedFIUQuery`, `buildLocalWatchListQuery`, and
`buildRestrictedLocalWatchQuery` all include NIC as an OR clause when NIC is present.
NIC_ONLY mode is only needed when the name itself is unusable (no name clause possible).

---

## 9. SanctionStrategyBuilder — Key Changes

### Before (v1)
```java
private final ScreeningSolrQueryBuilder queryBuilder;

private SanctionScreeningStrategy resolveStrategy(String type) { ... }

private SanctionScreeningStrategy composeStrategy(List<String> types) {
    return customer -> types.stream()
        .flatMap(type -> resolveStrategy(type).execute(customer).stream())
        .toList();
}
```

### After (v2)
```java
private final AdvancedScreeningSolrQueryBuilder queryBuilder;   // changed

private SanctionScreeningStrategy resolveStrategy(String type, ScreeningMode mode) { ... }  // mode added

private SanctionScreeningStrategy composeStrategy(List<String> types) {
    return customer -> {
        String nameToScreen;
        ScreeningMode mode;

        if (types.contains("FIU-ORG")) {
            // Org names: bypass NameNormalizer (single-token rules wrongly skip
            // valid short org names like "LTTE", "TRO"). Use NameRefinerUtil only.
            nameToScreen = NameRefinerUtil.refineName(customer.name());
            mode = ScreeningMode.NORMAL;

        } else {
            NameNormalizer.Result norm = NameNormalizer.classify(customer.name());

            boolean nameUsable = norm.action() != NameNormalizer.Action.SKIP_PLACEHOLDER
                              && norm.action() != NameNormalizer.Action.SKIP_ANOMALY;

            if (!nameUsable) {
                // Name unusable for any reason (blank, placeholder, invalid chars,
                // all-initials). NIC eligibility is checked independently.
                NicPair nics = SriLankanNicUtil.resolve(customer.nic());
                if (nics.isUsable()) {
                    mode = ScreeningMode.NIC_ONLY;
                    nameToScreen = "";  // not used in NIC-only queries
                } else {
                    return List.of();   // both checks failed — exception writer handles SKIPPED upstream
                }

            } else if (norm.action() == NameNormalizer.Action.SCREEN_RESTRICTED) {
                // Single-token name (NAME_SINGLE_TOKEN) — restricted query.
                // Do NOT apply NameRefinerUtil: restricted query needs the exact
                // single token as-is for the "name"~1 fuzzy clause.
                mode = ScreeningMode.RESTRICTED;
                nameToScreen = norm.screenName();

            } else {
                mode = ScreeningMode.NORMAL;
                nameToScreen = NameRefinerUtil.refineName(norm.screenName());
            }
        }

        CustomerInfo cleansed = new CustomerInfo(
            customer.clientId(), nameToScreen, customer.nic(),
            customer.passport(), customer.dob(),
            customer.proposalDate(), customer.policyNo()
        );

        return types.stream()
            .flatMap(type -> resolveStrategy(type, mode).execute(cleansed).stream())
            .toList();
    };
}
```

Full updated file: `qbuilderTest/production/com/kmpg/aml/screening/engine/solr/SanctionStrategyBuilder.java`

---

## 10. Exception Writer — Per-Customer Logic

This logic runs in `ScreeningProcessor` (or `AlertManager`) for every customer,
**before** the strategy is executed.

```java
// 1. Classify name and NIC independently
NameNormalizer.Result norm = NameNormalizer.classify(customer.name());
NicPair nics = SriLankanNicUtil.resolve(customer.nic());

// 2. Independent eligibility checks
boolean nameUsable = norm.action() != Action.SKIP_PLACEHOLDER
                  && norm.action() != Action.SKIP_ANOMALY;

boolean shouldSkip = !nameUsable && !nics.isUsable();  // BOTH must fail to skip

// 3. Determine action string
String action = shouldSkip                          ? "SKIPPED"
              : !nameUsable && nics.isUsable()      ? "SCREENED_RESTRICTED"  // NIC-rescued
              : norm.action() == SCREEN_TRANSFORMED ? "TRANSFORMED_AND_SCREENED"
              : norm.action() == SCREEN_RESTRICTED  ? "SCREENED_RESTRICTED"  // single-token
              :                                       null;  // NORMAL — no exception row

// 4. Write exception row (for non-normal customers only)
if (action != null) {
    String excId = UuidV7.generate();
    ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
    exc.setId(excId);
    exc.setRunId(runId);
    exc.setClientId(customer.clientId());
    exc.setOriginalName(customer.name());
    exc.setNormalizedName(!nameUsable ? null : norm.screenName());  // null when name unusable
    exc.setIdNumber(customer.nic());
    exc.setEntityType(deriveEntityType(nics, customer.passport()));
    exc.setDateOfBirth(customer.dob());
    exc.setNationality(nics.isUsable() ? "LKA" : null);
    exc.setReasonCode(norm.reasonCode());
    exc.setAction(action);
    exc.setNote(norm.note());

    // Pre-fill all list columns as SKIPPED (updated after each query)
    exc.setFiuInd(shouldSkip ? "SKIPPED" : null);
    exc.setFiuOrg(shouldSkip ? "SKIPPED" : null);
    exc.setUnConsolidated(shouldSkip ? "SKIPPED" : null);
    exc.setLocalWatch(shouldSkip ? "SKIPPED" : null);
    exc.setLexisNexis(shouldSkip ? "SKIPPED" : null);
    exc.setDowJones(shouldSkip ? "SKIPPED" : null);

    exceptionRepository.save(exc);

    if (shouldSkip) return;   // do not call Solr
}

// 5. Execute screening (with try/catch per list — see §11)
```

### Entity type derivation
```java
String deriveEntityType(NicPair nics, String passport) {
    return (nics.isUsable() || isPassport(passport)) ? "INDIVIDUAL" : "UNKNOWN";
}
```

---

## 11. Infrastructure Error Handling

Every per-list Solr call must be wrapped in try/catch. A failure on one list
must NOT stop screening on the other lists.

```java
for (String type : types) {
    try {
        List<ScreeningCandidate> hits = resolveStrategy(type, mode).apply(cleansed);
        if (exc != null) exceptionWriter.setListResult(exc, type, hits, mode);
        all.addAll(hits);
    } catch (SolrQueryException ex) {
        String reason = ex.getCause() instanceof SocketTimeoutException
                ? "SYSTEM_TIMEOUT" : "PROVIDER_ERROR";
        log.error("[SanctionStrategyBuilder] List query failed type={} clientId={} reason={}",
                type, customer.clientId(), reason, ex);
        if (exc != null) exceptionWriter.setListFailed(exc, type, reason, ex.getMessage());
    }
}
```

`SolrQueryException` is the single unchecked exception thrown by `SolrQueryExecutor` for all
infrastructure failures. The reason is classified at the catch site by inspecting the cause:

### Infrastructure reason codes

| Code | Category | Trigger |
|---|---|---|
| `SYSTEM_TIMEOUT` | Infrastructure | `cause instanceof SocketTimeoutException` — vendor API / socket timeout |
| `PROVIDER_ERROR` | Infrastructure | All other causes — HTTP 5xx, invalid/unparseable Solr response |

**First-write-wins:** if two lists fail in the same customer run, `failure_reason` and
`failure_details` are only written once (on the first failure). Subsequent failures update
the list column to `FAILED` but do not overwrite the failure context. See §21.

### Log level rules (all exceptions)

| Situation | Level |
|---|---|
| `TRANSFORMED_AND_SCREENED` | `INFO` |
| `SCREENED_RESTRICTED` | `INFO` |
| `SKIPPED` — data quality (blank, placeholder, initials) | `WARN` |
| `SKIPPED` — infrastructure (`SYSTEM_TIMEOUT`, `PROVIDER_ERROR`) | `ERROR` |

---

## 12. Database Changes

### 12.1 New table — `aml.screening_exceptions`

Full DDL is in: `qbuilderTest/production/screening_exceptions_ddl.sql`

Summary of columns:

| Column | Type | Notes |
|---|---|---|
| `id` | `VARCHAR(36)` | UUIDv7 — app-generated, time-ordered |
| `run_id` | `VARCHAR(20)` | `yyyyMMdd_HHmmss` — groups a run's exceptions |
| `client_id` | `VARCHAR(50)` | Source system customer ID |
| `original_name` | `VARCHAR(1000)` | Raw name, never modified |
| `normalized_name` | `VARCHAR(1000)` | Name sent to Solr; null for SKIPPED |
| `id_number` | `VARCHAR(50)` | Raw NIC / ID |
| `entity_type` | `VARCHAR(20)` | `INDIVIDUAL` / `CORPORATE` / `UNKNOWN` |
| `date_of_birth` | `DATE` | Secondary FP identifier |
| `nationality` | `CHAR(3)` | ISO Alpha-3; `LKA` if valid SL NIC |
| `reason_code` | `VARCHAR(50)` | `NameNormalizer` CODE_* constant (never rename) |
| `action` | `VARCHAR(30)` | `SKIPPED` / `TRANSFORMED_AND_SCREENED` / `SCREENED_RESTRICTED` |
| `note` | `TEXT` | Human-readable classification note for compliance |
| `fiu_ind` | `VARCHAR(20)` | `CANDIDATE_FOUND` / `ATTEMPTED` / `SKIPPED` / `NOT_APPLICABLE` / `FAILED` |
| `fiu_org` | `VARCHAR(20)` | same values |
| `un_consolidated` | `VARCHAR(20)` | same values |
| `local_watch` | `VARCHAR(20)` | same values |
| `lexis_nexis` | `VARCHAR(20)` | same values |
| `dow_jones` | `VARCHAR(20)` | same values |
| `screening_mode` | `VARCHAR(20)` | `NORMAL` / `RESTRICTED` / `NIC_ONLY`; null for SKIPPED rows |
| `screened_at` | `TIMESTAMP` | When screening was performed; null for SKIPPED rows |
| `match_count` | `INT` | Solr candidates before Python scoring; null for SKIPPED rows |
| `failure_reason` | `VARCHAR(50)` | `SYSTEM_TIMEOUT` or `PROVIDER_ERROR` — null unless a list column = FAILED |
| `failure_details` | `TEXT` | Raw exception message from `SolrQueryException` — null unless FAILED |
| `created_at` | `TIMESTAMP` | DB default `CURRENT_TIMESTAMP` |

Indexes created:
- `idx_se_run_id` — queries by run
- `idx_se_client_id` — queries by client
- `idx_se_action` — compliance filtering
- `idx_se_reason_code` — data quality reporting
- `idx_se_created_at DESC` — chronological reports
- `idx_se_screening_mode` — NIC_ONLY drill-down
- `idx_se_any_hit` — partial index, only rows with at least one `CANDIDATE_FOUND` column

### 12.2 New table — `aml.screening_runs` (2026-06-27)

One row per batch screening session. Inserted as `RUNNING` when the run starts;
updated to `COMPLETED` or `FAILED` when all scenarios finish.

| Column | Type | Notes |
|---|---|---|
| `run_id` | `VARCHAR(20) PK` | `yyyyMMdd_HHmmss` — Java-generated in `ScreeningProcessor` |
| `started_at` | `TIMESTAMP NOT NULL` | Set at run start |
| `completed_at` | `TIMESTAMP` | Null while RUNNING |
| `duration_seconds` | `INT` | `completedAt - startedAt` in seconds |
| `status` | `VARCHAR(20)` | `RUNNING` / `COMPLETED` / `FAILED` |
| `error_message` | `TEXT` | Populated only on FAILED; the `Throwable` message from `whenComplete` |
| `triggered_by` | `VARCHAR(50)` | Reserved — `SCHEDULER` or manual trigger name |
| `screening_version` | `VARCHAR(50)` | Reserved — application version tag |
| `total_customers` | `INT` | Eligible customers processed (`ScreeningMetrics.getTotalEligible()`) |
| `exception_count` | `INT` | Rows written to `screening_exceptions` |
| `alert_count` | `INT` | Customers with ≥1 HIT across any list |
| `skipped_count` | `INT` | `action = 'SKIPPED'` |
| `restricted_count` | `INT` | `action = 'SCREENED_RESTRICTED'` |
| `transformed_count` | `INT` | `action = 'TRANSFORMED_AND_SCREENED'` |
| `nic_only_count` | `INT` | `screening_mode = 'NIC_ONLY'` |
| `infra_failed_count` | `INT` | `failureReason IS NOT NULL` |

Index: `idx_sr_started_at` — ordered runs list.

### 12.4 Added — infrastructure failure context + screening audit columns (2026-06-24 / 2026-06-27)

```sql
ALTER TABLE aml.screening_exceptions
    ADD COLUMN IF NOT EXISTS failure_reason  VARCHAR(50),
    ADD COLUMN IF NOT EXISTS failure_details TEXT,
    ADD COLUMN IF NOT EXISTS screening_mode  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS screened_at     TIMESTAMP,
    ADD COLUMN IF NOT EXISTS match_count     INT;
```

`failure_reason` / `failure_details` — nullable; populated only when at least one list column equals `FAILED`.
First-write-wins: the first failing list captures the context; subsequent list failures do not overwrite.

`screening_mode` / `screened_at` — set in `ScreeningExceptionWriter.startException()` at entity creation;
null for SKIPPED rows (where no screening took place).

`match_count` — set in `SanctionStrategyBuilder.composeStrategy()` before `commit()`; null for SKIPPED rows.

### 12.5 Deferred — compliance review columns

To be added after initial go-live (Task 9 in TODO.md). See also §12.5:

```sql
ALTER TABLE aml.screening_exceptions
    ADD COLUMN reviewed       BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN reviewed_by    VARCHAR(100),
    ADD COLUMN reviewed_at    TIMESTAMP,
    ADD COLUMN review_notes   TEXT;

CREATE INDEX idx_se_reviewed ON aml.screening_exceptions (reviewed)
    WHERE reviewed = FALSE;
```

Compliance SLA: all `SKIPPED` records reviewed within 5 business days.

### 12.6 Deferred — retention archival

7-year retention required (FATF Rec. 11). Never hard-delete rows. Archival table:

```sql
CREATE TABLE aml.screening_exceptions_archive
    (LIKE aml.screening_exceptions INCLUDING ALL);

-- Periodic archive job (cron / scheduled task)
WITH moved AS (
    DELETE FROM aml.screening_exceptions
    WHERE created_at < NOW() - INTERVAL '7 years'
    RETURNING *
)
INSERT INTO aml.screening_exceptions_archive SELECT * FROM moved;
```

---

## 13. ScreeningExceptionEntity — JPA Mapping

```java
@Getter
@Setter
@Entity
@Table(name = "screening_exceptions", schema = "aml")
public class ScreeningExceptionEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;                      // UuidV7.generate() — never @GeneratedValue

    @Column(name = "run_id", nullable = false, length = 20)
    private String runId;

    @Column(name = "client_id", nullable = false, length = 50)
    private String clientId;

    @Column(name = "original_name", nullable = false, length = 1000)
    private String originalName;

    @Column(name = "normalized_name", length = 1000)
    private String normalizedName;

    @Column(name = "id_number", length = 50)
    private String idNumber;

    @Column(name = "entity_type", nullable = false, length = 20)
    private String entityType;              // INDIVIDUAL / CORPORATE / UNKNOWN

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "nationality", length = 3)
    private String nationality;             // ISO Alpha-3

    @Column(name = "reason_code", nullable = false, length = 50)
    private String reasonCode;

    @Column(name = "action", nullable = false, length = 30)
    private String action;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "fiu_ind", length = 20)
    private String fiuInd;

    @Column(name = "fiu_org", length = 20)
    private String fiuOrg;

    @Column(name = "un_consolidated", length = 20)
    private String unConsolidated;

    @Column(name = "local_watch", length = 20)
    private String localWatch;

    @Column(name = "lexis_nexis", length = 20)
    private String lexisNexis;

    @Column(name = "dow_jones", length = 20)
    private String dowJones;

    @Column(name = "screening_mode", length = 20)
    private String screeningMode;           // NORMAL / RESTRICTED / NIC_ONLY; null for SKIPPED

    @Column(name = "screened_at")
    private LocalDateTime screenedAt;       // null for SKIPPED rows

    @Column(name = "match_count")
    private Integer matchCount;             // Solr candidates before Python scoring; null if SKIPPED

    @Column(name = "failure_reason", length = 50)
    private String failureReason;           // SYSTEM_TIMEOUT | PROVIDER_ERROR — null if no FAILED list

    @Column(name = "failure_details", columnDefinition = "text")
    private String failureDetails;          // raw SolrQueryException message — null if no FAILED list

    @Setter(AccessLevel.NONE)              // DB-managed via DEFAULT CURRENT_TIMESTAMP
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private LocalDateTime createdAt;
}
```

---

## 14. ScreeningExceptionRepository

```java
public interface ScreeningExceptionRepository
        extends JpaRepository<ScreeningExceptionEntity, String> {

    // ── List (non-paginated, used internally) ────────────────────────────────
    List<ScreeningExceptionEntity> findByRunId(String runId);
    List<ScreeningExceptionEntity> findByClientId(String clientId);
    List<ScreeningExceptionEntity> findByAction(String action);

    // ── Paginated drill-down (GET /api/screening/runs/{runId}/exceptions) ────
    Page<ScreeningExceptionEntity> findByRunId(String runId, Pageable pageable);
    Page<ScreeningExceptionEntity> findByRunIdAndAction(String runId, String action, Pageable pageable);
    Page<ScreeningExceptionEntity> findByRunIdAndScreeningMode(String runId, String screeningMode, Pageable pageable);
    Page<ScreeningExceptionEntity> findByRunIdAndFailureReasonIsNotNull(String runId, Pageable pageable);

    @Query("SELECT e FROM ScreeningExceptionEntity e WHERE e.runId = :runId AND " +
           "(e.fiuInd='CANDIDATE_FOUND' OR e.fiuOrg='CANDIDATE_FOUND' OR e.unConsolidated='CANDIDATE_FOUND' " +
           "OR e.localWatch='CANDIDATE_FOUND' OR e.lexisNexis='CANDIDATE_FOUND' OR e.dowJones='CANDIDATE_FOUND')")
    Page<ScreeningExceptionEntity> findHitsByRunId(@Param("runId") String runId, Pageable pageable);

    // ── Ad-hoc date range (GET /api/screening/exceptions?startDate=&endDate=) ─
    Page<ScreeningExceptionEntity> findByCreatedAtBetween(
            LocalDateTime start, LocalDateTime end, Pageable pageable);

    // ── Run completion aggregations (used by ScreeningProcessor) ─────────────
    // Returns Object[4]: [totalCount, skippedCount, restrictedCount, transformedCount]
    @Query("SELECT COUNT(e), " +
           "SUM(CASE WHEN e.action='SKIPPED' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN e.action='SCREENED_RESTRICTED' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN e.action='TRANSFORMED_AND_SCREENED' THEN 1 ELSE 0 END) " +
           "FROM ScreeningExceptionEntity e WHERE e.runId = :runId")
    Object[] getCountsByRunId(@Param("runId") String runId);

    @Query("SELECT COUNT(e) FROM ScreeningExceptionEntity e WHERE e.runId = :runId AND " +
           "(e.fiuInd='CANDIDATE_FOUND' OR e.fiuOrg='CANDIDATE_FOUND' OR e.unConsolidated='CANDIDATE_FOUND' " +
           "OR e.localWatch='CANDIDATE_FOUND' OR e.lexisNexis='CANDIDATE_FOUND' OR e.dowJones='CANDIDATE_FOUND')")
    long countHitsByRunId(@Param("runId") String runId);

    @Query("SELECT COUNT(e) FROM ScreeningExceptionEntity e " +
           "WHERE e.runId = :runId AND e.screeningMode = 'NIC_ONLY'")
    long countNicOnlyByRunId(@Param("runId") String runId);

    @Query("SELECT COUNT(e) FROM ScreeningExceptionEntity e " +
           "WHERE e.runId = :runId AND e.failureReason IS NOT NULL")
    long countInfraFailedByRunId(@Param("runId") String runId);
}
```

---

## 15. NIC_ONLY Scoring — Python Scorer Fix

### Problem

For `NIC_ONLY` mode customers (all-initials names like `"R M M T"`), the scorer receives
the initials as `keyword` and scores them against candidate names using fuzzy/Levenshtein.
A genuine sanctions hit (correct person, NIC confirmed on the list) scores near-zero on
name similarity and gets discarded below threshold. Real hit thrown away.

### Why per-candidate flagging is not needed

A NIC-only Solr query (`nic_no_s:(oldNic OR newNic)`) **only returns documents that
contain that exact NIC value**. Every candidate returned from a NIC_ONLY query is already
a NIC-confirmed match — `nic_matched=true` on every record would be redundant. The mode
itself is the signal.

### Fix — minimal, no breaking changes to payload shape

**Java — `ScoringConsumer.java`:**

Pass the current `ScreeningMode` as a string when calling the scorer:
```java
// existing call
matchRateClient.getMatchRates(customer.name(), dataArr)

// updated call
matchRateClient.getMatchRates(customer.name(), dataArr, screeningMode.name())
```

**Java — `MatchRateClient.java`:**

Add `screening_mode` to the request body alongside the existing `keyword` and `dataArr`:
```java
Map.of(
    "keyword",       keyword,
    "dataArr",       dataArr,
    "screening_mode", screeningMode   // e.g. "NIC_ONLY", "RESTRICTED", "NORMAL"
)
```

**Python — `match_rate_V3.py`:**

Add one early-exit at the top of `view_match_rate()`, before any name scoring runs:
```python
screening_mode = (request_data or {}).get("screening_mode", "NORMAL")

if screening_mode == "NIC_ONLY":
    # All candidates returned by a NIC-only Solr query are NIC-confirmed matches.
    # Name scoring is meaningless here (keyword is initials). Return fixed high score.
    return jsonify([{"key": r.get("key"), "match": 95} for r in records])
```

Score of 95 (not 100) leaves headroom for compliance review — a NIC match is high
confidence but not absolute certainty.

### What does NOT change

| Component | Reason |
|---|---|
| `buildNicOnlyFIUQuery` / `buildNicOnlyLocalWatchQuery` | NIC must be primary for all-initials — correct as-is |
| `buildRestrictedFIUQuery` / `buildRestrictedLocalWatchQuery` | Already multi-signal (name + NIC combined) |
| Python scoring logic for NORMAL / RESTRICTED | Unchanged — name scoring path runs as before |
| Payload field names (`keyword`, `dataArr`) | No breaking change |

---

## 16. REST Endpoints — `ScreeningExceptionController`

**Package:** `com.kpmg.aml.screening.controller`
**Base mapping:** `/api/screening`

Four endpoints. `ExceptionFilter` enum (§16.1) keeps all routing logic out of the controller.

---

### Endpoint 1 — Screening runs list

```
GET /api/screening/runs
```

Returns `List<RunSummaryDto>` — all batch runs, most-recent first.

- No parameters.
- Counts are 0 for a `RUNNING` row (not yet populated).
- Repository: `ScreeningRunRepository.findAllByOrderByStartedAtDesc()`

---

### Endpoint 2 — Exceptions drill-down for a run

```
GET /api/screening/runs/{runId}/exceptions[?filter=ALL][&page=0&size=20&sort=createdAt,desc]
```

Returns `Page<SummaryExceptionDto>` — exceptions for the given run, filtered by `ExceptionFilter`.

| `filter` value | Repository method | What it returns |
|---|---|---|
| `ALL` (default) | `findByRunId(runId, pageable)` | All exceptions for the run |
| `SKIPPED` | `findByRunIdAndAction(runId, "SKIPPED", pageable)` | Skipped customers only |
| `RESTRICTED` | `findByRunIdAndScreeningMode(runId, "RESTRICTED", pageable)` | Restricted-mode screened |
| `NIC_ONLY` | `findByRunIdAndScreeningMode(runId, "NIC_ONLY", pageable)` | NIC-only screened |
| `HITS` | `findHitsByRunId(runId, pageable)` | Any sanction list returned HIT |
| `FAILED` | `findByRunIdAndFailureReasonIsNotNull(runId, pageable)` | Infrastructure failures |

- Spring binds the `filter` query-param string to `ExceptionFilter` automatically; an unrecognised value → 400.
- Default page: 0, size: 20, sort: `createdAt DESC`.

---

### Endpoint 3 — Ad-hoc date-range scan

```
GET /api/screening/exceptions?startDate={yyyy-MM-dd}&endDate={yyyy-MM-dd}[&page=0&size=20&sort=createdAt,desc]
```

Returns `Page<SummaryExceptionDto>` — exceptions created within the given dates (inclusive).

- Both params **required**. Missing or unparseable → 400 (Spring auto).
- `endDate` before `startDate` → 400 (explicit guard).
- `startDate` → `startDate.atStartOfDay()` (00:00:00.000000000).
- `endDate` → `endDate.atTime(LocalTime.MAX)` (23:59:59.999999999) — inclusive.
- Repository: `findByCreatedAtBetween(LocalDateTime, LocalDateTime, Pageable)`

---

### Endpoint 4 — Full detail by ID

```
GET /api/screening/exceptions/{id}
```

Returns `DetailedExceptionDto` — full audit trail for one exception row.

- `id` is the UUIDv7 primary key.
- 404 if no row found.
- Repository: `findById(String id)` (inherited from `JpaRepository`)

---

### 16.1 ExceptionFilter enum

**File:** `controller/ExceptionFilter.java`

Each constant holds a `FilterFunction` lambda that delegates directly to the correct
repository method. The controller body for endpoint 2 is a single expression:

```java
return ResponseEntity.ok(
    filter.execute(repository, runId, pageable)
          .map(SummaryExceptionDto::from));
```

```java
public enum ExceptionFilter {
    ALL        ((repo, runId, p) -> repo.findByRunId(runId, p)),
    SKIPPED    ((repo, runId, p) -> repo.findByRunIdAndAction(runId, "SKIPPED", p)),
    RESTRICTED ((repo, runId, p) -> repo.findByRunIdAndScreeningMode(runId, "RESTRICTED", p)),
    NIC_ONLY   ((repo, runId, p) -> repo.findByRunIdAndScreeningMode(runId, "NIC_ONLY", p)),
    HITS       ((repo, runId, p) -> repo.findHitsByRunId(runId, p)),
    FAILED     ((repo, runId, p) -> repo.findByRunIdAndFailureReasonIsNotNull(runId, p));

    public Page<ScreeningExceptionEntity> execute(
            ScreeningExceptionRepository repo, String runId, Pageable pageable) { ... }

    @FunctionalInterface
    public interface FilterFunction {
        Page<ScreeningExceptionEntity> apply(
                ScreeningExceptionRepository repo, String runId, Pageable pageable);
    }
}
```

---

### Response shapes — see §21 (DTOs)

---

## 17. Implementation Order

| Step | TODO # | Task | Status | Depends on |
|---|---|---|---|---|
| 1  | Task 4  | Copy drop-in files to production (§4.1) | ✅ Done — 2026-06-16 | — |
| 2  | Task 5  | Replace `SanctionStrategyBuilder` (§9) | ✅ Done — 2026-06-16 | Step 1 |
| 3  | Task 7  | Run `screening_exceptions` DDL (§12.1) | ✅ Done — 2026-06-16 | — |
| 4  | Task 6  | Create `ScreeningExceptionEntity` (§13) | ✅ Done — 2026-06-18 | — |
| 5  | Task 6  | Create `ScreeningExceptionRepository` (§14) | ✅ Done — 2026-06-18 | Step 4 |
| 6  | Task 8  | Wire exception writer into pipeline (§10, §20) | ✅ Done — 2026-06-22 | Steps 2, 3, 4, 5 |
| 7  | Task 12 | Wire infrastructure error handling (§11) | ✅ Done — 2026-06-23 | Step 6 |
| 8  | Task 13 | NIC_ONLY scorer fix — Python early-exit (§15) | ✅ Done — 2026-06-23 | — |
| 9  | Task 14 | NIC_ONLY scorer fix — Java `ScoringConsumer` + `MatchRateClient` (§15) | ✅ Done — 2026-06-23 | Step 8 |
| 10 | Task 3  | Build REST endpoints (§16) | ✅ Done — 2026-06-24 | Step 5 |
| 11 | Task 15 | Screening runs list + drill-down endpoints; `aml.screening_runs` table; `ExceptionFilter` enum | ✅ Done — 2026-06-27 | Steps 5, 6 |
| 12 | Task 2  | Load real data into `dj_entities_core` | Pending — data dependency | — |
| 13 | Task 1  | Compliance review of 8 MATCH flags | Pending — unblocked | Step 6 |
| 14 | Task 9  | Add review columns to `screening_exceptions` (§12.5) | Deferred | Step 6 |
| 15 | Task 10 | Implement 7-year archival job (§12.6) | Deferred | Step 6 |
| 16 | Task 11 | Structured JSON application logging | Deferred | Step 6 |

---

## 18. Compliance Rationale

| Authority | Requirement |
|---|---|
| FATF 2025 | Proportionate, risk-based screening. Wholesale exclusion of customer classes requires documented rationale. |
| FATF Rec. 10 | Customer Due Diligence documentation. |
| FATF Rec. 11 | Record keeping — minimum 5 years; recommend 7. |
| OFAC FAQ 1591 | Single-name SDN entries are valid match targets. Secondary identifiers (DOB, NIC, nationality) required for confirmation. |
| Wolfsberg Group | Suppression of "weak aliases" requires risk-approved, auditable rationale. |
| MAS 2022 | Exact-only matching is insufficient. Institutions penalised for inadequate dismissal criteria. |
| CBSL AML/CFT | Local regulatory obligation for exception audit trails. |

---

## 19. Solr Cores Reference

| Core | Cluster | NIC field | Key fields |
|---|---|---|---|
| `fiu_individual_core` | lite :8983 | `nic_no_s` | `name_txt`, `name_phonetic`, `passport_no_ss` |
| `fiu_org_core` | lite :8983 | — | `name_txt`, `name_phonetic`, `registration_no_s` |
| `un_consolidated_core` | lite :8983 | — | `name_txt`, `alias_txt`, `name_phonetic` |
| `local_watch_core` | lite :8983 | `nic_s` | `name_txt`, `alias_txt`, `name_phonetic` |
| `lxnx_entities_core` | heavy :9004 | — | `search_names_txt`, `name_phonetic` |
| `dj_entities_core` | heavy :9004 | — | `search_names_txt` — awaiting real data |

---

## 20. ScreeningExceptionWriter

### Responsibility

Single-responsibility `@Service` that encapsulates all exception entity building,
classification, and persistence. Injected into `ScreeningProcessor`. No exception
writing logic lives in `ScreeningProcessor` directly.

**Package:** `com.kpmg.aml.screening.engine`
**File:** `engine/ScreeningExceptionWriter.java`

---

### Methods

#### `ExceptionContext classify(CustomerInfo customer, String runId)`

Called **before** `strategy.execute()` for every eligible customer.

1. Calls `NameNormalizer.classify(customer.name())`
2. NIC check (independent of name quality): calls `SriLankanNicUtil.resolve(customer.nic())`.
   NIC participates in two distinct roles:
   - **Name usable (NORMAL / RESTRICTED):** mode is already determined by name classification.
     NIC is resolved and passed into the query builder methods (buildFIUQuery,
     buildRestrictedFIUQuery, etc.), which include it as a parallel OR clause on FIU and
     local_watchList queries (§7). classify() does not change the mode here — NIC
     participation is handled inside the query builder itself.
   - **Name unusable (SKIP_PLACEHOLDER or SKIP_ANOMALY — any reason):** NIC becomes the
     primary rescue check. Valid NIC → NIC_ONLY mode (name clause dropped entirely).
     No valid NIC → customer is SKIPPED (no Solr call).
   This applies uniformly to all "name unusable" reason codes: NAME_BLANK, NAME_PLACEHOLDER,
   NAME_INVALID_CHARS, and NAME_ALL_INITIALS — not only NAME_ALL_INITIALS.
3. Builds and saves `ScreeningExceptionEntity` for exception customers (all non-NORMAL)
4. Returns `ExceptionContext`

NORMAL customers → `ExceptionContext(shouldScreen=true, excId=null, mode=NORMAL)` — no DB write.

#### `void recordListResult(String excId, String listType, List<ScreeningCandidate> candidates)`

Called **after** each list query for exception customers.
Sets list column: `CANDIDATE_FOUND` if candidates non-empty, `ATTEMPTED` if empty.

#### `void recordListNotApplicable(String excId, String listType)`

Sets list column to `NOT_APPLICABLE` for invalid list + mode combinations
(e.g. `FIU_ORG` in RESTRICTED, `LXNX` / `DJ` / `UN` in NIC_ONLY).

#### `void setListFailed(ScreeningExceptionEntity exc, String type, String reason, String details)`

Called from the catch block in `SanctionStrategyBuilder.composeStrategy()` when a
`SolrQueryException` is thrown. Sets the list column to `FAILED` and records the
failure context on the exception row.

**First-write-wins:** `failure_reason` and `failure_details` are only written if they are
currently `null` — the first failing list in a run captures the context; subsequent list
failures do not overwrite it.

```java
public void setListFailed(ScreeningExceptionEntity exc, String type,
                           String reason, String details) {
    applyListColumn(exc, type, "FAILED");
    if (exc.getFailureReason() == null) {   // first-write-wins
        exc.setFailureReason(reason);
        exc.setFailureDetails(details);
    }
    log.error("[ExceptionWriter] FAILED list={} excId={} reason={}", type, exc.getId(), reason);
}
```

---

### ExceptionContext

```java
record ExceptionContext(
    boolean       shouldScreen,  // false = SKIPPED, ScreeningProcessor skips strategy.execute()
    String        excId,         // UUIDv7 — null for NORMAL (no exception row written)
    ScreeningMode mode           // NORMAL / RESTRICTED / NIC_ONLY / SKIPPED
) {}
```

---

### Integration in ScreeningProcessor

`runId` (`yyyyMMdd_HHmmss`) generated once at the top of `process()` and passed
into every `classify()` call.

```java
// before strategy.execute():
ExceptionContext ctx = exceptionWriter.classify(customer, runId);
if (!ctx.shouldScreen()) continue;   // SKIPPED — no Solr call

// existing call (unchanged):
List<ScreeningCandidate> candidates = strategy.execute(customer);

// after strategy.execute(), exception customers only:
if (ctx.excId() != null) {
    exceptionWriter.recordListResult(ctx.excId(), listType, candidates);
}
```

---

### NOT_APPLICABLE rules

| List | RESTRICTED (name-based) | NIC_ONLY |
|---|---|---|
| FIU_IND | queried | queried |
| FIU_ORG | NOT_APPLICABLE | NOT_APPLICABLE |
| UN_CONSOLIDATED | queried | NOT_APPLICABLE |
| LOCAL_WATCH | queried | queried |
| LEXIS_NEXIS | queried | NOT_APPLICABLE |
| DOW_JONES | queried | NOT_APPLICABLE |

---

## 21. REST DTOs

**Package:** `com.kpmg.aml.screening.dto`

---

### RunSummaryDto

Per-run summary returned by `GET /api/screening/runs`. Implemented as a Java **record**.

```java
public record RunSummaryDto(
    String        runId,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    String        status,           // RUNNING / COMPLETED / FAILED
    int           totalCustomers,
    int           exceptionCount,
    int           alertCount,       // customers with ≥1 HIT
    int           skippedCount,
    int           restrictedCount,
    int           transformedCount,
    int           nicOnlyCount,
    int           infraFailedCount
) {
    public static RunSummaryDto from(ScreeningRunEntity e) { ... }  // null-safe for RUNNING rows
}
```

---

### SummaryExceptionDto

Lightweight view returned by the date-range list endpoint.
Implemented as a Java **record**.

```java
public record SummaryExceptionDto(
    String        id,
    String        clientId,
    String        action,
    String        reasonCode,
    LocalDateTime createdAt
) {
    public static SummaryExceptionDto from(ScreeningExceptionEntity e) { ... }
}
```

---

### DetailedExceptionDto

Full compliance audit-trail view returned by the single-row detail endpoint.
Implemented as a **class** with `@Getter` (Lombok) — no manual getters required.
Static `from()` factory; no-arg constructor for framework compatibility.

`failureReason` and `failureDetails` are `null` unless at least one list column equals `FAILED`.

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | UUIDv7 primary key |
| `runId` | `String` | Screening run identifier |
| `clientId` | `String` | Source customer ID |
| `originalName` | `String` | Raw name, unchanged |
| `normalizedName` | `String` | Name sent to Solr; null for SKIPPED |
| `idNumber` | `String` | Raw NIC / ID |
| `entityType` | `String` | `INDIVIDUAL` / `CORPORATE` / `UNKNOWN` |
| `dateOfBirth` | `LocalDate` | Secondary FP identifier |
| `nationality` | `String` | ISO Alpha-3; `LKA` if valid SL NIC |
| `action` | `String` | `SKIPPED` / `TRANSFORMED_AND_SCREENED` / `SCREENED_RESTRICTED` |
| `reasonCode` | `String` | `NameNormalizer` code (e.g. `NAME_SINGLE_TOKEN`) |
| `note` | `String` | Human-readable classification explanation |
| `fiuInd` | `String` | Per-list result |
| `fiuOrg` | `String` | Per-list result |
| `unConsolidated` | `String` | Per-list result |
| `localWatch` | `String` | Per-list result |
| `lexisNexis` | `String` | Per-list result |
| `dowJones` | `String` | Per-list result |
| `failureReason` | `String` | `SYSTEM_TIMEOUT` / `PROVIDER_ERROR` — null if no FAILED list |
| `failureDetails` | `String` | Raw exception message — null if no FAILED list |
| `createdAt` | `LocalDateTime` | When the exception row was written |

---

## Service Boundary — AML Alert & Exception Management Service

### Role of This Service

The Screening-Schedule service is a **data producer only**. Its responsibilities are:

- Running the batch screening pipeline against sanction lists
- Capturing exception customers in `aml.screening_exceptions`
- Tracking run-level metadata in `aml.screening_runs`
- Persisting alert matches in `aml.sch_screening_cus` and `aml.sch_screening_match`
- Exposing **read-only** REST endpoints for downstream consumers

This service does **not** own:
- Compliance review workflow or decisions
- Alert assignment, triage, or escalation
- User or team management
- SAR (Suspicious Activity Report) submission
- Review audit trails

Those responsibilities belong exclusively to the **AML Alert & Exception Management Service**.

### API Contract

The Alert & Exception Management Service consumes this service through its published REST API only. No direct database access between services is permitted.

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/screening/runs` | List all screening runs (most-recent first) |
| `GET` | `/api/screening/runs/{runId}/exceptions?filter=` | Paginated exceptions for a run; filter: `ALL \| SKIPPED \| RESTRICTED \| NIC_ONLY \| HITS \| FAILED` |
| `GET` | `/api/screening/exceptions/{id}` | Full detail for a single exception row |
| `GET` | `/api/screening/exceptions?startDate=&endDate=` | Ad-hoc date-range exception scan |

### Data Contracts

The following entities are the stable interface between services:

- **`ScreeningExceptionEntity`** — one row per exception customer per run; contains name quality classification, per-list screening results, and infrastructure failure context.
- **`ScreeningRunEntity`** — one row per batch run; contains timing, status, and volume counts.

DTOs returned by the REST API (`RunSummaryDto`, `SummaryExceptionDto`, `DetailedExceptionDto`) are the versioned contract. Any breaking change to these DTOs requires coordination with the Alert & Exception Management Service team.

### Deployment Independence

- The two services are deployed and scaled independently.
- The Screening Service has no runtime dependency on the Alert & Exception Management Service.
- The Alert & Exception Management Service may be unavailable without affecting screening batch execution.

### Full Specification

See `ALERT_SERVICE_SPEC.md` in this repository for the complete requirements, entity model, functional requirements, and open questions for the Alert & Exception Management Service.
