# CLAUDE.md — Screening Schedule Project Reference

> AML (Anti-Money Laundering) screening pipeline application.
> Spring Boot 4.0.2 · Java 21 virtual threads · PostgreSQL + Oracle · Apache Solr 9.4

@CLAUDE-SIGNATURES.md
@CLAUDE-DEVLOG.md

---

## Build

| Field | Value |
|---|---|
| Group | `com.kpmg.aml` |
| Artifact | `Screening-Schedule` |
| Version | `0.0.1-SNAPSHOT` |
| Java | 21 |
| Parent | `spring-boot-starter-parent:4.0.2` |

**Key dependencies:** Spring Web, Spring Data JPA, Spring Mail, PostgreSQL, ojdbc11 23.3.0, solr-solrj 9.4.0, Lombok, ModelMapper 3.2.0, Commons Lang3/Collections4/IO, Jakarta XML Binding, logstash-logback-encoder 8.0.

---

## Package Structure

```
com.kpmg.aml.screening
├── ScreeningSchedule21Application
├── controller/
│   ├── ExceptionFilter                        (enum — filter constants for exception REST endpoint)
│   └── ScreeningExceptionController           (@RestController — /api/screening/*)
├── dto/
│   ├── constant/
│   │   └── SchScreeningStatus                 (enum)
│   ├── DetailedExceptionDto                   (record)
│   ├── RunSummaryDto                          (record)
│   └── SummaryExceptionDto                    (record)
├── entity/
│   ├── AlertEntity                            (@Entity → aml.sch_screening_cus)
│   ├── AlertStatus                            (enum)
│   ├── ApprovalGroupEntity                    (@Entity → aml.sch_screening_app_grp)
│   ├── SanctionTypeEntity                     (@Entity → aml.sch_screening_san_type)
│   ├── ScreeningExceptionEntity               (@Entity → aml.screening_exceptions)
│   ├── ScreeningMatchEntity                   (@Entity → aml.sch_screening_match)
│   ├── ScreeningMatchVariantEntity            (@Entity → aml.sch_screening_match_variant)
│   ├── ScreeningRunEntity                     (@Entity → aml.screening_runs)
│   ├── ScreeningScheduleEntity                (@Entity → aml.sch_screening_schedule)
│   ├── dto/
│   │   ├── ActiveScreeningView                (JPA projection interface)
│   │   ├── CustomerInfo                       (record)
│   │   ├── MiniAlert                          (record)
│   │   └── SanctionTypeView                   (JPA projection interface)
│   ├── persistence/
│   │   ├── AlertEntityRepository              (JpaRepository)
│   │   ├── AlertRepository                    (custom JPQL)
│   │   ├── SanctionTypeRepository             (native query)
│   │   ├── ScreeningExceptionRepository       (JpaRepository + custom queries)
│   │   ├── ScreeningRunRepository             (JpaRepository)
│   │   └── ScreeningScheduleRepository        (native query)
│   └── repo/
│       └── ScreeningMatchRepository           (JpaRepository — no custom queries)
├── engine/
│   ├── AlertManager                           (@Component)
│   ├── ArchivalService                        (@Component — monthly scheduled DB archival job)
│   ├── ExceptionContext                       (record)
│   ├── PipelineSignal                         (enum)
│   ├── PipelineStageReporter                  (interface)
│   ├── ScoringConsumer                        (@Service — Stage 3)
│   ├── ScreeningExceptionWriter               (@Service)
│   ├── ScreeningProcessor                     (@Service — Stage 1 orchestrator)
│   ├── Stage                                  (enum)
│   ├── pipeline/
│   │   ├── AlertBuffer                        (blocking queue wrapper — Stage 3→4)
│   │   ├── AlertConsumer                      (@Service — Stage 4)
│   │   ├── ChunkBuffer                        (blocking queue wrapper — Stage 1→2)
│   │   ├── DeadLetterService                  (@Service)
│   │   ├── ScoringBuffer                      (blocking queue wrapper — Stage 2→3)
│   │   └── ScreeningGovernor                  (@Service — pipeline lifecycle coordinator)
│   ├── scoring/
│   │   ├── EndpointSelector                   (singleton — round-robin with health checks)
│   │   ├── MatchRateClient                    (@Component — HTTP client for Python service)
│   │   └── MatchRateException                 (RuntimeException)
│   └── solr/
│       ├── AdvancedScreeningSolrQueryBuilder   (class — primary query builder)
│       ├── SanctionScreeningStrategy          (@FunctionalInterface)
│       ├── SanctionStrategyBuilder            (@Component)
│       ├── ScreeningCandidate                 (abstract class)
│       ├── ScreeningMode                      (enum: NORMAL / RESTRICTED / NIC_ONLY)
│       ├── ScreeningResult                    (record)
│       ├── ScreeningSolrQueryBuilder          (class — legacy, superseded by Advanced*)
│       ├── SolrClientConfig                   (@Configuration @Lazy)
│       ├── SolrQueryException                 (RuntimeException)
│       ├── SolrQueryExecutor                  (@Component)
│       └── dto/
│           ├── ConsolidatedCandidate          (UN consolidated / local watchlist results)
│           ├── FiuIndividualCandidate         (FIU individual results)
│           ├── FiuOrgCandidate                (FIU org results)
│           └── LxNxCandidate                  (LexisNexis results)
├── monitor/
│   └── ScreeningMetrics                       (@Component — JVM + pipeline counters)
├── runner/
│   └── ScreeningTrigger                       (@Component @Profile("test"))
└── util/
    ├── ConfigLoader                           (@ConfigurationProperties prefix=application.config)
    ├── DataSourceConfig                       (@Configuration — dual datasource)
    ├── NameNormalizer                         (static utility — name quality classifier)
    ├── NameRefinerUtil                        (static utility — Solr-safe name normaliser)
    ├── SolrSettings                           (POJO nested in ConfigLoader)
    ├── SriLankanNicUtil                       (static utility — old/new NIC format conversion)
    └── UuidV7                                 (static utility — RFC 9562 time-ordered UUIDs)
```

---

## Pipeline Overview

The screening pipeline runs 4 stages connected by blocking queues. All execution uses Java 21 virtual threads.

```
Oracle DB ──► [Stage 1: ScreeningProcessor]
                    │  (chunk batches via chunkSemaphore)
                    ▼
             ScoringBuffer (ArrayBlockingQueue)
                    │
              [Stage 2: SanctionStrategyBuilder inside ScreeningProcessor]
                    │  (Solr queries per customer, per sanction list)
                    ▼
             ScoringBuffer (consumed by ScoringConsumer)
                    │
              [Stage 3: ScoringConsumer]
                    │  (calls Python match-rate service via MatchRateClient)
                    ▼
             AlertBuffer (ArrayBlockingQueue)
                    │
              [Stage 4: AlertConsumer]
                    │  (batch-persists AlertEntity + ScreeningMatchEntity evidence)
                    ▼
             PostgreSQL (aml.sch_screening_cus + aml.sch_screening_match)
```

`ScreeningGovernor` manages executor registration, the chunk semaphore, all three buffers, and ordered shutdown triggered by poison-pill drain propagation.

---

## Screening Modes

Determined per customer by `NameNormalizer.classify()` inside `SanctionStrategyBuilder.composeStrategy()`.

| Mode | Trigger | Query strategy |
|---|---|---|
| `NORMAL` | Name passes all quality checks | Full wildcard + fuzzy + phonetic via `AdvancedScreeningSolrQueryBuilder` |
| `RESTRICTED` | Single-token name (`NAME_SINGLE_TOKEN`) | Exact phrase + fuzzy~1 + phonetic; no wildcard. FIU-ORG: NOT_APPLICABLE |
| `NIC_ONLY` | All-initials name + valid Sri Lankan NIC present | NIC field query only on FIU and local_watchList; FIU-ORG / consoli / LXNX: NOT_APPLICABLE |

Customers with `NORMAL` action are not written to `screening_exceptions`. All other dispositions (SKIPPED, SCREENED_RESTRICTED, TRANSFORMED_AND_SCREENED, NIC_ONLY) produce a row via `ScreeningExceptionWriter`.

---

## Classes, Interfaces, Records, and Enums

### Main

| Type | Name | Responsibility |
|---|---|---|
| `@SpringBootApplication` | `ScreeningSchedule21Application` | Bootstrap; declares `ModelMapper` bean (STRICT, skipNull, ignoreAmbiguity). |

### `controller`

| Type | Name | Responsibility |
|---|---|---|
| `enum` | `ExceptionFilter` | Filter constants (`ALL, SKIPPED, RESTRICTED, NIC_ONLY, HITS, FAILED`) for the `/runs/{runId}/exceptions` endpoint; each constant holds its repository delegate. |
| `@RestController` | `ScreeningExceptionController` | REST API for screening runs and the `aml.screening_exceptions` audit table. See endpoints below. |

### `dto.constant`

| Type | Name | Values / Responsibility |
|---|---|---|
| `enum` | `SchScreeningStatus` | Pipeline lifecycle states: `START, END, RUNNING, ERROR`. |

### `dto`

| Type | Name | Responsibility |
|---|---|---|
| `record` | `RunSummaryDto` | One entry per screening run returned by `GET /api/screening/runs`; includes all volume counts. Static `from(ScreeningRunEntity)`. |
| `record` | `SummaryExceptionDto` | Lightweight view (`id, clientId, action, reasonCode, createdAt`) returned by list endpoints. Static `from(ScreeningExceptionEntity)`. |
| `record` | `DetailedExceptionDto` | Full audit-trail detail returned by `GET /api/screening/exceptions/{id}`. Static `from(ScreeningExceptionEntity)`. |

### `entity`

| Type | Name | DB Table | Responsibility |
|---|---|---|---|
| `@Entity` | `AlertEntity` | `aml.sch_screening_cus` | Persists a single customer screening alert with status, assignee, timestamps, match count, and soft-delete. |
| `enum` | `AlertStatus` | — | `unassigned, pending, approved, rejected`. |
| `@Entity` | `ApprovalGroupEntity` | `aml.sch_screening_app_grp` | Approval group linked to a schedule; getters only (immutable). |
| `@Entity` | `SanctionTypeEntity` | `aml.sch_screening_san_type` | Sanction list type (e.g. FIU, UN) linked to a schedule; getters only. |
| `@Entity` | `ScreeningExceptionEntity` | `aml.screening_exceptions` | One row per exception customer per run: name quality reason, action taken, per-list results (CANDIDATE_FOUND / ATTEMPTED / NOT_APPLICABLE / FAILED), infra failure context. PK is a UUIDv7 string. |
| `@Entity` | `ScreeningMatchEntity` | `aml.sch_screening_match` | Evidence row per Solr candidate linked to an `AlertEntity`: docId, list source, best score, above-threshold flag. |
| `@Entity` | `ScreeningMatchVariantEntity` | `aml.sch_screening_match_variant` | Per-name-variant score row linked to a `ScreeningMatchEntity`. |
| `@Entity` | `ScreeningRunEntity` | `aml.screening_runs` | Run-level tracking: status (RUNNING / COMPLETED / FAILED), timing, volume counts (total, exceptions, alerts, skipped, restricted, nic-only, infra-failed, transformed). PK is `yyyyMMdd_HHmmss` string. |
| `@Entity` | `ScreeningScheduleEntity` | `aml.sch_screening_schedule` | Screening scenario definition: name, SQL query, frequency, active flag; getters only. |

### `entity.dto`

| Type | Name | Responsibility |
|---|---|---|
| `interface` | `ActiveScreeningView` | JPA projection: `getSanctionId, getFrequency, getScenarioName, getQuery, getApprovalGrpId`. |
| `record` | `CustomerInfo` | Immutable customer data row from Oracle: `clientId, name, nic, passport, dob, proposalDate, policyNo`. |
| `record` | `MiniAlert` | Lightweight alert projection for duplicate suppression: `screenId, customerid, policyNo`. |
| `interface` | `SanctionTypeView` | JPA projection: `getSanctionId, getSanctionType`. |

### `entity.persistence`

| Type | Name | Responsibility |
|---|---|---|
| `interface` | `AlertEntityRepository` | `JpaRepository<AlertEntity, Long>` — no custom queries. |
| `interface` | `AlertRepository` | Custom JPQL: `findCustomersToOmit(List<AlertStatus>)` returns `List<MiniAlert>` of non-deleted alerts matching given statuses. |
| `interface` | `SanctionTypeRepository` | Native query `findActiveSanctionTypes()` returns `List<SanctionTypeView>` where `deleted=false`. |
| `interface` | `ScreeningExceptionRepository` | Full query suite for `aml.screening_exceptions`: paginated lookups by runId, action, mode; aggregate counts for run completion. |
| `interface` | `ScreeningRunRepository` | `JpaRepository<ScreeningRunEntity, String>` + `findAllByOrderByStartedAtDesc()`. |
| `interface` | `ScreeningScheduleRepository` | Native query `findActiveSchedules()` returns `List<ActiveScreeningView>` where `is_active=true AND deleted=false`. |

### `entity.repo`

| Type | Name | Responsibility |
|---|---|---|
| `interface` | `ScreeningMatchRepository` | `JpaRepository<ScreeningMatchEntity, Long>` — no custom queries. |

### `engine`

| Type | Name | Responsibility |
|---|---|---|
| `@Component` | `AlertManager` | In-memory cache of existing (pending/unassigned) alerts; prevents re-screening already-alerted customers. Loaded via `@PostConstruct`. |
| `@Component` | `ArchivalService` | Monthly `@Scheduled` job that moves rows from `aml.screening_exceptions` to `aml.screening_exceptions_archive` once they exceed `retentionYears` (default 7). Uses a single atomic PostgreSQL CTE (`DELETE ... RETURNING * → INSERT`). Cron configurable via `application.config.screening.archival-cron`. |
| `record` | `ExceptionContext` | Per-customer classification result from `ScreeningExceptionWriter`: `shouldScreen`, `excId`, `mode`. |
| `enum` | `PipelineSignal` | Stage communication signals: `RUNNING, EXHAUSTED, DRAINING, DONE, ERROR`. |
| `interface` | `PipelineStageReporter` | Contract for reporting stage transitions: `report(Stage stage, PipelineSignal signal)`. Implemented by `ScreeningGovernor`. |
| `@Service` | `ScoringConsumer` | Stage 3 — drains `ScoringBuffer`, calls the Python match-rate service via `MatchRateClient`, builds `CandidateResult` list, pushes above-threshold payloads to `AlertBuffer`. Halts pipeline via `ERROR` signal if the scoring service is unavailable. |
| `@Service` | `ScreeningExceptionWriter` | Builds and persists `ScreeningExceptionEntity` rows for SKIPPED, SCREENED_RESTRICTED, and TRANSFORMED_AND_SCREENED customers. Also emits a structured JSON event per exception to `logs/screening-exceptions.json` via the dedicated `SCREENING_EXCEPTIONS` logger (WARN for data-quality skips, INFO for screened exceptions, ERROR for infra failures). Thread-safe; no shared mutable state. |
| `@Service` | `ScreeningProcessor` | Stage 1 orchestrator — loads customers from Oracle in chunks (semaphore-gated), filters via `AlertManager`, dispatches Solr screening per customer, pushes hits to `ScoringBuffer`. Manages `ScreeningRunEntity` lifecycle. |
| `enum` | `Stage` | Pipeline stages with order: `ORACLE_READER(1), SOLR_SCREENING(2), PYTHON_SCORING(3), ALERT_GENERATION(4)`. |

### `engine.pipeline`

| Type | Name | Responsibility |
|---|---|---|
| `class` | `AlertBuffer` | `ArrayBlockingQueue<AlertPayload>` wrapper; signals `PYTHON_SCORING → DRAINING/DONE` to `ScreeningGovernor`. Contains nested records `AlertPayload` and `CandidateResult`. |
| `@Service` | `AlertConsumer` | Stage 4 — drains `AlertBuffer` with size-based and time-based batch flushing; persists `AlertEntity` + `ScreeningMatchEntity` evidence trail via `saveAll`. |
| `class` | `ChunkBuffer` | `ArrayBlockingQueue<List<CustomerInfo>>` wrapper; signals `ORACLE_READER → EXHAUSTED`. (Declared but not used in current chunk dispatch path — chunks are dispatched directly via virtual thread executor in `ScreeningProcessor`.) |
| `@Service` | `DeadLetterService` | Handles `AlertPayload` records that could not be enqueued to `AlertBuffer` due to `InterruptedException` in `ScoringConsumer`. Retries once with 2 s delay, then falls back to direct `AlertEntityRepository.save()` and writes to `missed-alerts.log`. |
| `class` | `ScoringBuffer` | `ArrayBlockingQueue<ScoringPayload>` wrapper; signals `SOLR_SCREENING → DRAINING/DONE` to `ScreeningGovernor`. Contains nested record `ScoringPayload`. |
| `@Service` | `ScreeningGovernor` | Implements `PipelineStageReporter`. Manages: executor registry per stage, chunk semaphore (`maxConcurrentChunks`), the three pipeline buffers, stage signal tracking, ordered graceful shutdown (30 s per stage, then forced), and emergency halt on `ERROR` signal. |

### `engine.scoring`

| Type | Name | Responsibility |
|---|---|---|
| `singleton` | `EndpointSelector` | Round-robin selector across Python scoring endpoints with health tracking. On all-down: waits 2.5 s, re-pings up to 3 times, then throws `EndpointUnavailableException`. |
| `@Component` | `MatchRateClient` | Java 11 `HttpClient`-based async HTTP client for the Python `/match-rate` endpoint. Sends `{keyword, dataArr, screening_mode}` JSON; deserialises `[{key, match}]` score array. Periodic health checks via `EndpointSelector.resetHealth()`. |
| `class` | `MatchRateException` | `RuntimeException` for match-rate HTTP failures. |

### `engine.solr`

| Type | Name | Responsibility |
|---|---|---|
| `class` | `AdvancedScreeningSolrQueryBuilder` | Primary query builder. Supports NORMAL, RESTRICTED, and NIC_ONLY query variants for all five sanction lists. Uses adaptive wildcard+fuzzy (length-based), AND-anchored multi-token clauses, phonetic (DoubleMetaphone copyField), and edit-distance deduplication. |
| `@FunctionalInterface` | `SanctionScreeningStrategy` | `ScreeningResult execute(CustomerInfo customer)` — one composed strategy per scenario. |
| `@Component` | `SanctionStrategyBuilder` | Builds `Map<Long, SanctionScreeningStrategy>` per run. Integrates `NameNormalizer`, `AdvancedScreeningSolrQueryBuilder`, and `ScreeningExceptionWriter`. Requires `setRunId()` to be called before `build()`. |
| `abstract class` | `ScreeningCandidate` | Base for Solr result DTOs: `docId`, `coreName`; abstract `getNameVariants()` and `getIdDocuments()`; protected `addIfPresent` / `addMultiIfPresent` helpers. |
| `enum` | `ScreeningMode` | `NORMAL`, `RESTRICTED`, `NIC_ONLY` — passed through `ScreeningResult` into `ScoringBuffer` so `ScoringConsumer` can forward the mode to the Python service. |
| `record` | `ScreeningResult` | `List<ScreeningCandidate> candidates, ScreeningMode mode` — returned by `SanctionScreeningStrategy.execute()`. |
| `class` | `ScreeningSolrQueryBuilder` | Legacy query builder — superseded by `AdvancedScreeningSolrQueryBuilder`. Kept in codebase for reference. |
| `@Configuration @Lazy` | `SolrClientConfig` | Declares `CloudSolrClient` bean with ZooKeeper, Http2 transport, and connection pool settings. |
| `class` | `SolrQueryException` | `RuntimeException` thrown by `SolrQueryExecutor` on infrastructure failures. Caught per-list in `SanctionStrategyBuilder` so one failed list does not abort the others. |
| `@Component` | `SolrQueryExecutor` | Executes Solr queries (`MAX_ROWS=50`, edismax, phrase boost, `mm=75%`) against named cores; returns typed `List<ScreeningCandidate>`. Uses `lxnx`-specific `executeLXNX()` with tighter `mm` and `qf` for the heavy 7M+ record cluster. |

### `engine.solr.dto`

| Type | Name | Responsibility |
|---|---|---|
| `class` | `ConsolidatedCandidate` | Solr result for UN consolidated and local watchlist cores; extracts `name_txt`, `alias_txt` name variants and ID documents. |
| `class` | `FiuIndividualCandidate` | Solr result for `fiu_individual_core`; extracts name variants from `name_txt` and NIC/passport from `nic_no_s` / `passport_no_ss`. |
| `class` | `FiuOrgCandidate` | Solr result for `fiu_org_core`; extracts org name variants and registration/NIC documents. |
| `class` | `LxNxCandidate` | Solr result for `lxnx_entities_core`; extracts name variants from `search_names_txt` and IDs from `id_docs_ss`. |

### `monitor`

| Type | Name | Responsibility |
|---|---|---|
| `@Component` | `ScreeningMetrics` | Atomic counters for all pipeline stages (read, eligible, screened, candidates, scored, above-threshold, scoring latency, alerts persisted, buffer depth). JVM metrics (heap, threads, GC delta). Flushes to a dedicated `METRICS` logger at a configurable interval. |

### `runner`

| Type | Name | Responsibility |
|---|---|---|
| `@Component @Profile("test")` | `ScreeningTrigger` | `ApplicationRunner` that calls `ScreeningProcessor.process()` on startup; active only under `test` profile. Injects `ScreeningProcessor` with `@Lazy` to avoid circular init ordering. |

### `util`

| Type | Name | Responsibility |
|---|---|---|
| `@ConfigurationProperties` | `ConfigLoader` | Binds `application.config.*` to nested POJOs: `SolrSettings`, `Screening`, `MatchRateSettings`; singleton. |
| `@Configuration` | `DataSourceConfig` | Declares primary PostgreSQL `DataSource` (`@Primary`), secondary Oracle `DataSource` (`oracleDataSource`), and `oracleJdbcTemplate`. |
| `final class` | `NameNormalizer` | Stateless name quality classifier. Classification pipeline (first match wins): blank → placeholder → dot-expand → CamelCase-split → invalid-char strip → single-token → all-initials → screenable. Returns `Result(screenName, action, reasonCode, note)`. |
| `class` | `NameRefinerUtil` | Static utility: normalises a cleaned name for Solr (strips control chars, Solr boolean operators AND/OR/NOT, uppercases). Applied after `NameNormalizer` for NORMAL-mode names. |
| `@Getter @Setter` | `SolrSettings` | POJO holding Solr connection settings: `zkHosts, connectionTimeout, socketTimeout, defaultCollection, collections` (map with keys `individual, org, un, watch, lxnx`). |
| `class` | `SriLankanNicUtil` | Static utility: validates, converts, and pairs old (10-char) ↔ new (12-char) Sri Lankan NIC formats. Returns `NicPair(oldNic, newNic)` and `isUsable()`. |
| `final class` | `UuidV7` | Generates RFC 9562 UUIDv7 strings (time-ordered, no external dependency). Used as primary key for `ScreeningExceptionEntity`. |

---

## Dependencies Between Classes

```
ScreeningTrigger
  └─► ScreeningProcessor
        ├─► ScreeningScheduleRepository  → ScreeningScheduleEntity / ActiveScreeningView
        ├─► SanctionTypeRepository       → SanctionTypeView
        ├─► JdbcTemplate (oracle)        → CustomerInfo (DataClassRowMapper)
        ├─► SanctionStrategyBuilder
        │     ├─► AdvancedScreeningSolrQueryBuilder
        │     │     └── SriLankanNicUtil (static)
        │     ├─► SolrQueryExecutor
        │     │     ├─► CloudSolrClient  ← SolrClientConfig ← ConfigLoader → SolrSettings
        │     │     └─► FiuIndividualCandidate / FiuOrgCandidate /
        │     │          ConsolidatedCandidate / LxNxCandidate
        │     │              └── (extends) ScreeningCandidate
        │     └─► ScreeningExceptionWriter
        │           └─► ScreeningExceptionRepository → ScreeningExceptionEntity
        ├─► AlertManager
        │     └─► AlertRepository → MiniAlert
        ├─► ScreeningGovernor
        │     ├── ChunkBuffer
        │     ├── ScoringBuffer
        │     └── AlertBuffer
        ├─► ScreeningRunRepository → ScreeningRunEntity
        ├─► ScreeningExceptionRepository
        └─► ScreeningMetrics

ScoringConsumer (@PostConstruct auto-started)
  ├─► ScreeningGovernor.getScoringBuffer() → ScoringBuffer
  ├─► MatchRateClient
  │     └─► EndpointSelector (singleton)
  ├─► DeadLetterService
  │     ├─► AlertConsumer.buildAlertEntity()
  │     └─► AlertEntityRepository
  └─► ScreeningMetrics

AlertConsumer (@PostConstruct auto-started)
  ├─► ScreeningGovernor.getAlertBuffer() → AlertBuffer
  ├─► AlertEntityRepository → AlertEntity
  ├─► ScreeningMatchRepository → ScreeningMatchEntity / ScreeningMatchVariantEntity
  └─► ScreeningMetrics

ScreeningExceptionController
  ├─► ScreeningExceptionRepository
  └─► ScreeningRunRepository

AlertEntity
  ├─► AlertStatus (enum)
  ├─► ApprovalGroupEntity
  └─► ScreeningScheduleEntity

ScreeningMatchEntity ──► AlertEntity
ScreeningMatchVariantEntity ──► ScreeningMatchEntity
ScreeningExceptionEntity (standalone — no FK to AlertEntity)
SanctionTypeEntity  ──► ScreeningScheduleEntity
ApprovalGroupEntity ──► ScreeningScheduleEntity
```

---

## Project Documentation

Key reference documents in the project root. Read these before making architectural or workflow decisions.

| File | Purpose |
|---|---|
| `ARCHITECTURE.md` | Full v1→v2 architecture decisions, query builder dispatch tables, screening mode rationale, NameNormalizer classification pipeline, service boundary definition. Single source of truth for all architectural decisions made during development. |
| `TODO.md` | Task tracking — completed tasks with implementation notes, and pending/deferred items. Tasks 10 (structured JSON logging) and 11 (retention and archival policy) completed 2026-07-04. Task 9 (compliance review columns) has been moved to the Alert & Exception Management Service — see `ALERT_SERVICE_SPEC.md`. |
| `ALERT_SERVICE_SPEC.md` | Full specification for the **AML Alert & Exception Management Service** — a new service to be built separately. Covers purpose, background, data sources, core entities, functional requirements (ingestion, assignment, review workflow, user management, reporting), non-functional requirements, open questions, and out-of-scope items. This Screening Service is a read-only data producer for that service — see API contract in `ARCHITECTURE.md §Service Boundary`. |
| `TYPO-FIXES.md` | Log of typo and naming bug fixes applied during development. |
| `ASSESSMENT-MVP.md` | MVP assessment notes. |
