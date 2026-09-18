# AML Alert & Exception Management Service — Specification

> Status: Pre-implementation — specification only. No code exists yet.
> Author: AML Engineering
> Date: 2026-07-04
> Reference: Screening-Schedule service, `ARCHITECTURE.md` §Service Boundary

---

## 1. Purpose

The AML Alert & Exception Management Service is the compliance workflow layer for the AML screening programme. It provides:

- **Compliance review and triage** of AML screening exceptions produced by the Screening Service
- **Alert assignment and workflow management** — routing exceptions to the correct analyst or team
- **User and team management** — roles, groups, assignment rules
- **Reporting** — run summaries, SLA tracking, SAR escalation reports, and regulatory audit exports

---

## 2. Background

### What the Screening Service produces

The Screening-Schedule service runs a nightly batch pipeline that screens customer records against sanctions lists (FIU Individual, FIU Org, UN Consolidated, Local Watchlist, LexisNexis, Dow Jones). For each customer it produces one of three outcomes:

| Outcome | Where stored | Description |
|---|---|---|
| **Normal match** | `aml.sch_screening_cus` | Customer name returned candidates that scored above threshold after Python match-rate scoring |
| **Exception** | `aml.screening_exceptions` | Customer was skipped, restricted-screened, or NIC-only screened due to name quality issues |
| **Clean** | Not stored | Customer produced no candidates above threshold — no further action |

The Screening Service also writes a run summary row to `aml.screening_runs` for every batch execution.

### Why a separate service is needed

The Screening Service is a high-throughput data pipeline. Compliance review is a human workflow with different:

- **Lifecycle** — screening runs nightly; review takes days or weeks
- **Access model** — pipeline runs as a service account; review requires authenticated human users with RBAC
- **Business logic** — assignment rules, SLA tracking, escalation paths, and dual sign-off are compliance domain logic, not pipeline logic
- **Team** — the compliance operations team owns review workflow; the engineering team owns the pipeline
- **Scaling** — the Screening Service scales horizontally for throughput; the Alert Service scales for concurrent user load

Mixing these concerns in a single service would couple fast-changing compliance workflow rules to the stable, certified screening pipeline.

### REST API consumed from Screening Service

All data access is read-only via the Screening Service REST API. No direct database connection to the Screening Service database is permitted.

| Endpoint | Used for |
|---|---|
| `GET /api/screening/runs` | Ingest new runs and detect new exceptions to import |
| `GET /api/screening/runs/{runId}/exceptions?filter=HITS` | Pull HIT exceptions for a specific run |
| `GET /api/screening/runs/{runId}/exceptions?filter=SKIPPED` | Pull SKIPPED exceptions for data-quality review |
| `GET /api/screening/exceptions/{id}` | Fetch full detail for a single exception when opening an alert |
| `GET /api/screening/exceptions?startDate=&endDate=` | Ad-hoc date-range pulls |

---

## 3. Data Sources

| Source | Access method | Notes |
|---|---|---|
| Screening Service REST API | HTTP — read-only | Primary data source for exception and run data |
| Own PostgreSQL schema (`aml_alerts` or similar) | Direct JDBC / JPA | Owns all alert workflow, user, assignment, and decision data |

The Alert Service does **not** write to any Screening Service database table.

---

## 4. Core Entities (to be designed)

The following entities are required. Schema design is left to the implementing engineer subject to the constraints in §6.

### Alert
Wraps a screening exception from the Screening Service for the compliance review workflow.

| Field (proposed) | Notes |
|---|---|
| `id` | Internal alert ID (UUIDv7 recommended) |
| `exceptionId` | Foreign reference to `screening_exceptions.id` (no FK — cross-service) |
| `runId` | Screening run identifier |
| `clientId` | Customer identifier from source system |
| `status` | `NEW → ASSIGNED → IN_REVIEW → CLOSED` |
| `alertType` | Derived from exception action: `CANDIDATE_FOUND / SKIPPED / RESTRICTED / NIC_ONLY / FAILED` |
| `listSource` | Which sanction list(s) returned `CANDIDATE_FOUND` |
| `assignedTo` | User or group the alert is assigned to |
| `assignedAt` | Timestamp of assignment |
| `createdAt` | When the alert was created in this service |
| `closedAt` | When the final decision was recorded |

### User
A compliance officer, analyst, manager, or administrator.

| Field (proposed) | Notes |
|---|---|
| `id` | UUIDv7 |
| `username` | Unique login identifier |
| `fullName` | Display name |
| `email` | For notifications |
| `role` | `ADMIN / MANAGER / ANALYST / READ_ONLY` |
| `groupId` | Team/group membership |
| `active` | Soft-delete flag |

### UserGroup / Team
A named group of users. Alerts can be assigned to a group rather than an individual.

| Field (proposed) | Notes |
|---|---|
| `id` | UUIDv7 |
| `name` | e.g. `"FIU Compliance Team"` |
| `description` | |
| `managerId` | User who manages the group |

### AssignmentRule
Defines how alerts are auto-routed to teams or users.

| Field (proposed) | Notes |
|---|---|
| `id` | UUIDv7 |
| `priority` | Rule evaluation order (lower = higher priority) |
| `alertType` | Match condition: alert type (CANDIDATE_FOUND / SKIPPED / etc.) |
| `listSource` | Match condition: which sanction list |
| `entityType` | Match condition: `INDIVIDUAL / CORPORATE` |
| `assignToGroupId` | Target group for auto-assignment |
| `assignToUserId` | Target user for auto-assignment (alternative to group) |
| `active` | Enable/disable without deleting |

### ReviewDecision
The outcome recorded by a compliance officer after reviewing an alert.

| Field (proposed) | Notes |
|---|---|
| `id` | UUIDv7 |
| `alertId` | The alert being decided |
| `outcome` | `NO_ACTION / FALSE_POSITIVE / ESCALATE_TO_SAR / REQUEST_MORE_INFO` |
| `notes` | Free-text review notes (required for SAR escalation) |
| `reviewedBy` | User who recorded the decision |
| `reviewedAt` | Timestamp |
| `approvedBy` | Second sign-off user (required for SAR escalation, if dual-approval configured) |
| `approvedAt` | Timestamp of second sign-off |

### Report
Metadata for a generated compliance report.

| Field (proposed) | Notes |
|---|---|
| `id` | UUIDv7 |
| `reportType` | `RUN_SUMMARY / EXCEPTION_BREAKDOWN / SLA_COMPLIANCE / SAR_ESCALATION / AUDIT_EXPORT` |
| `generatedBy` | User who requested it |
| `generatedAt` | Timestamp |
| `parameters` | JSON — date range, filters applied |
| `filePath` | Where the generated file is stored (if file-based output) |

---

## 5. Functional Requirements

### 5.1 Alert Ingestion

- On a configurable schedule (or triggered manually), poll `GET /api/screening/runs` to detect new completed runs.
- For each new run, pull exceptions via `GET /api/screening/runs/{runId}/exceptions?filter=ALL`.
- For each exception, create one `Alert` record in the local database.
- Deduplication: if an `Alert` already exists for a given `exceptionId`, skip it — do not create duplicates.
- Initial alert status: `NEW`.
- Ingestion should be idempotent — safe to re-run after failure.

**Alert status lifecycle:**

```
NEW ──► ASSIGNED ──► IN_REVIEW ──► CLOSED
         │                            ▲
         └────────────────────────────┘  (re-assign or re-open)
```

### 5.2 Alert Assignment

**Manual assignment:**
- A manager can assign any `NEW` or unassigned alert to an individual user or group.
- A manager can reassign an `ASSIGNED` alert.

**Auto-assignment:**
- On ingestion, evaluate `AssignmentRule` records in priority order.
- Apply the first matching rule (match on `alertType`, `listSource`, `entityType`).
- If no rule matches, alert remains `NEW` and appears in the unassigned queue.

**Assignment rule criteria:**

| Criterion | Values |
|---|---|
| Exception type | `SKIPPED / RESTRICTED / NIC_ONLY / HITS / FAILED` |
| Sanction list that returned CANDIDATE_FOUND | `FIU / FIU-ORG / UN / LOCAL_WATCH / LEXISNEXIS / DOW_JONES` |
| Entity type | `INDIVIDUAL / CORPORATE / UNKNOWN` |

**SLA:**
- `SKIPPED` records (data-quality skips) must be reviewed within **5 business days** of the screening run date.
- `CANDIDATE_FOUND` records must be reviewed within **2 business days**.

### 5.3 Compliance Review Workflow

1. Compliance officer opens an assigned alert — status transitions to `IN_REVIEW`.
2. Officer reviews:
   - Customer data (`originalName`, `normalizedName`, `idNumber`, `dateOfBirth`, `nationality`)
   - Per-list screening results (`fiuInd`, `unConsolidated`, etc.)
   - Full detail fetched from `GET /api/screening/exceptions/{id}`
3. Officer records a `ReviewDecision`:
   - **`NO_ACTION`** — screened, no match confirmed; close alert.
   - **`FALSE_POSITIVE`** — Solr / Python returned a match but it is not the same person; close alert; add to suppression list if required.
   - **`ESCALATE_TO_SAR`** — genuine sanctions match; initiate Suspicious Activity Report process.
   - **`REQUEST_MORE_INFO`** — insufficient data; alert remains open; request sent to source system team.
4. Officer adds review notes (mandatory for `ESCALATE_TO_SAR`).
5. Alert status transitions to `CLOSED` on `NO_ACTION` or `FALSE_POSITIVE`.
6. `ESCALATE_TO_SAR` alerts require manager approval (second sign-off) before closure — see §5.4.

### 5.4 User Management

**Roles:**

| Role | Capabilities |
|---|---|
| `ADMIN` | Full access — user management, rule configuration, all reports |
| `MANAGER` | Alert assignment, approval of SAR escalations, team reports |
| `ANALYST` | Review and close alerts assigned to them or their group |
| `READ_ONLY` | View alerts and reports; no write operations |

**Groups / Teams:**
- Users belong to one or more groups.
- Alerts can be assigned to a group; any member of the group can claim and action it.

**Manager approval workflow (SAR escalation):**
- When an analyst records `ESCALATE_TO_SAR`, the alert moves to `PENDING_APPROVAL`.
- A manager reviews the decision and either approves (closes with SAR status) or rejects (returns to `IN_REVIEW`).
- Dual sign-off is configurable — can be disabled for `MANAGER` role users acting as both analyst and approver.

### 5.5 Reporting

All reports are scoped by date range and optionally by team.

| Report | Description |
|---|---|
| **Run Summary** | All screening runs in the period: date, status, total customers, exception counts, alert counts |
| **Exception Breakdown** | Exceptions by type (SKIPPED / RESTRICTED / NIC_ONLY / HITS / FAILED) and by list source |
| **SLA Compliance** | Alerts reviewed within SLA vs. breached; by team and by analyst |
| **SAR Escalation** | All alerts escalated to SAR in the period; approval chain; outcome |
| **Audit Trail Export** | Full record per exception — original data, screening results, review decision, reviewer, timestamps. Format suitable for FATF / CBSL regulatory submission. |

---

## 6. Non-Functional Requirements

| Requirement | Detail |
|---|---|
| **Framework** | Spring Boot 3.x, Java 21, virtual threads (`spring.threads.virtual.enabled=true`) |
| **Database** | PostgreSQL — own schema (`aml_alerts` or similar); no shared tables with Screening Service |
| **Authentication** | JWT-based; tokens issued by an internal identity provider or SSO |
| **Authorisation** | Role-based access control (RBAC) enforced at the API layer — `@PreAuthorize` or equivalent |
| **Audit trail** | Every create, update, and decision event must be logged with `userId`, `timestamp`, and the before/after state. Immutable — no updates to decision records |
| **Data retention** | 7 years minimum (FATF Recommendation 11). No hard-delete of alert or decision records |
| **Availability** | The Alert Service may be unavailable without affecting Screening Service batch execution |
| **API versioning** | REST API versioned from the start (`/api/v1/...`) to allow breaking changes without client disruption |

---

## 7. REST API (to be designed)

The following areas require REST endpoints. Exact paths, request/response shapes, and pagination behaviour are left to the implementing engineer, subject to the constraints in §6.

- **Alert management** — list (with filtering by status, type, list source, team, date range), detail, assignment, status transitions
- **Review submission** — record a `ReviewDecision`; manager approval of SAR escalations
- **User management** — CRUD for users and groups; role assignment
- **Assignment rule management** — CRUD for `AssignmentRule`; priority ordering
- **Reporting** — generate and retrieve reports by type and date range

---

## 8. Open Questions (to be resolved with business)

| # | Question | Impact |
|---|---|---|
| 1 | How are users provisioned — internal LDAP / SSO / manual registration? | Authentication and user management implementation |
| 2 | Is SAR submission automated (API to FIU / CBSL) or manual export? | Whether a SAR integration module is in scope |
| 3 | Which reporting output formats are required — PDF, Excel, API JSON? | Report generation library selection |
| 4 | Are there multi-institution requirements (one instance per bank, or multi-tenant)? | Schema design and data isolation strategy |
| 5 | What is the alert retention policy after closure — archive or keep in active tables? | Schema and archival job design |
| 6 | Is dual sign-off on SAR escalations mandatory for all roles, or configurable? | Approval workflow complexity |
| 7 | What is the notification mechanism for new alert assignments — email, in-app, SMS? | Notification service scope |
| 8 | Are suppression lists (confirmed false-positives) in scope for this service? | Would affect screening pipeline integration |

---

## 9. Out of Scope for This Service

The following are owned by the Screening-Schedule service and must not be duplicated or modified by the Alert & Exception Management Service:

- Running the screening batch pipeline
- Solr query construction and execution
- Exception row capture (`aml.screening_exceptions`)
- Raw screening result storage (`aml.sch_screening_cus`, `aml.sch_screening_match`)
- Screening run management (`aml.screening_runs`)
- Name normalisation logic (`NameNormalizer`, `NameRefinerUtil`, `SriLankanNicUtil`)
- Match-rate scoring integration (Python service)
