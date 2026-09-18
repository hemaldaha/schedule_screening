# AML Screening Engine

A high-performance, batch-oriented Anti-Money Laundering (AML) and sanctions screening microservice built on Java 21 virtual threads, Apache Solr, and a Python fuzzy-matching cluster. Designed to screen 100k–1.5M+ customer records against multiple sanctions lists within a 2–3 hour window.

---

## Authors

**Hemal Dahanayake**
Tech Consultant · KPMG
hemal@kpmg.com

*Initial architecture, design and implementation — 2026*

---

## Table of Contents

- [Architecture](#architecture)
- [Technology Stack](#technology-stack)
- [Project Structure](#project-structure)
- [Pipeline Overview](#pipeline-overview)
- [Screening Modes](#screening-modes)
- [Database Schema](#database-schema)
- [Configuration](#configuration)
- [Running the Application](#running-the-application)
- [Python Scoring Cluster](#python-scoring-cluster)
- [Logging](#logging)
- [REST API](#rest-api)
- [Known Issues & Pre-Production Checklist](#known-issues--pre-production-checklist)
- [Post-MVP Roadmap](#post-mvp-roadmap)

---

## Architecture

```
Oracle DB
    │
    ▼
ScreeningProcessor  (virtual threads — oracle-reader-*)
    │   Reads customers in paginated chunks
    │   Classifies name quality (NameNormalizer)
    │   Executes Solr screening strategy per scenario / per sanction list
    │
    ▼
ScoringBuffer  (ArrayBlockingQueue)
    │
    ▼
ScoringConsumer  (virtual threads — scoring-consumer-*)
    │   Calls Python match-rate cluster (async HTTP)
    │   Applies false-positive suppression patterns
    │   Scores name variants against candidate names
    │
    ▼
AlertBuffer  (ArrayBlockingQueue)
    │
    ▼
AlertConsumer  (virtual threads — alert-consumer-*)
    │   Batch persists AlertEntity + evidence trail
    │
    ▼
PostgreSQL
    ├── aml.sch_screening_cus           (alerts)
    ├── aml.sch_screening_match         (candidate evidence)
    ├── aml.sch_screening_match_variant (name variant scores)
    ├── aml.screening_exceptions        (per-customer audit trail)
    └── aml.screening_runs              (run-level metrics)
```

**Fallback:** `DeadLetterService` handles alerts that fail to reach `AlertBuffer` — retries once, then persists directly to DB and writes to `missed-alerts.log`.

**Lifecycle:** `ScreeningGovernor` manages pipeline state, buffer access, semaphore, shutdown sequencing, and error propagation across all stages.

**Archival:** `ArchivalService` runs monthly, moving `screening_exceptions` rows older than 7 years to `screening_exceptions_archive` (FATF Rec. 11 compliance).

---

## Technology Stack

| Component | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.0.2 |
| Concurrency | Virtual threads (`Thread.ofVirtual()`) |
| Search / Screening | Apache Solr 9.4 (CloudSolrClient, ZooKeeper) |
| Fuzzy Matching | Python 3.12 · Flask · Waitress · RapidFuzz |
| Primary DB | PostgreSQL 14+ |
| Secondary DB | Oracle (customer data source) |
| ORM | Hibernate / Spring Data JPA |
| Build | Maven |

---

## Project Structure

```
com.kpmg.aml.screening
├── ScreeningSchedule21Application
├── controller/
│   ├── ExceptionFilter                  Filter constants for exception REST endpoint
│   └── ScreeningExceptionController     REST API — /api/screening/*
├── dto/
│   ├── constant/SchScreeningStatus      Pipeline lifecycle states
│   ├── DetailedExceptionDto
│   ├── RunSummaryDto
│   └── SummaryExceptionDto
├── engine/
│   ├── ScreeningProcessor               Stage 1+2: Oracle reader + Solr dispatcher
│   ├── ScoringConsumer                  Stage 3: Python scoring
│   ├── AlertManager                     In-memory dedup cache
│   ├── ArchivalService                  Monthly exception archival job
│   ├── ScreeningExceptionWriter         Per-customer exception audit writer
│   ├── pipeline/
│   │   ├── ScreeningGovernor            Lifecycle, buffers, semaphore, signals
│   │   ├── AlertConsumer                Stage 4: Alert + evidence persistence
│   │   ├── AlertBuffer                  Stage 3→4 queue
│   │   ├── ScoringBuffer                Stage 2→3 queue
│   │   ├── ChunkBuffer                  Stage 1→2 wrapper
│   │   └── DeadLetterService            Fallback alert persistence
│   ├── scoring/
│   │   ├── MatchRateClient              HTTP client for Python cluster
│   │   └── EndpointSelector             Round-robin load balancer with health checks
│   └── solr/
│       ├── AdvancedScreeningSolrQueryBuilder   Primary query builder (3 modes × 5 lists)
│       ├── SanctionStrategyBuilder             Builds screening strategies per sanction type
│       ├── SolrQueryExecutor                   CloudSolrClient wrapper (edismax)
│       ├── ScreeningCandidate                  Abstract Solr result DTO
│       ├── ScreeningMode                       NORMAL / RESTRICTED / NIC_ONLY
│       └── dto/                               Concrete candidate types per Solr core
├── entity/
│   ├── AlertEntity                     → aml.sch_screening_cus
│   ├── ScreeningMatchEntity            → aml.sch_screening_match
│   ├── ScreeningMatchVariantEntity     → aml.sch_screening_match_variant
│   ├── ScreeningExceptionEntity        → aml.screening_exceptions
│   ├── ScreeningRunEntity              → aml.screening_runs
│   └── persistence/                   JPA repositories
├── monitor/
│   └── ScreeningMetrics               Atomic pipeline counters + JVM metrics
├── runner/
│   └── ScreeningTrigger               @Profile("test") startup trigger
└── util/
    ├── ConfigLoader                   @ConfigurationProperties binding
    ├── DataSourceConfig               Dual datasource (PostgreSQL primary, Oracle secondary)
    ├── NameNormalizer                 Name quality classifier
    ├── NameRefinerUtil                Solr-safe name normaliser
    ├── SolrSettings                   Solr config POJO
    ├── SriLankanNicUtil               NIC format validation + conversion
    └── UuidV7                         RFC 9562 time-ordered UUID generator
```

---

## Pipeline Overview

### Stage 1 — Oracle Reader
- Reads customer records in configurable chunks (`batchSize`) from Oracle
- Filters customers already alerted via `AlertManager.isExist()`
- Bounded concurrency via `chunkSemaphore` (`maxConcurrentChunks`)
- For each customer, runs `NameNormalizer.classify()` to determine screening mode

### Stage 2 — Solr Screening (bundled with Stage 1)
- Executes composed `SanctionScreeningStrategy` per customer
- Queries up to 5 Solr cores: `fiu_individual_core`, `fiu_org_core`, `un_consolidated_core`, `local_watch_core`, `lxnx_entities_core`
- Query strategy depends on `ScreeningMode` (see below)
- Customers with exception dispositions (SKIPPED, RESTRICTED, NIC_ONLY) are recorded via `ScreeningExceptionWriter`
- Results pushed to `ScoringBuffer`

### Stage 3 — Python Scoring
- Builds `dataArr` of all name variants across all candidates per customer
- Single batch HTTP POST to Python cluster per customer
- Maps scores back to candidates — builds `CandidateResult` with per-variant scores
- Applies false-positive suppression patterns (B, D, E, F) before alert decision
- Customers with any candidate above `scoreThreshold` pushed to `AlertBuffer`

### Stage 4 — Alert Persistence
- Batch drains `AlertBuffer` — flushes by size (`alertBatchSize`) or timeout (`alertBatchTimeoutMs`)
- Persists `AlertEntity` → gets DB-generated `screen_id`
- Persists `ScreeningMatchEntity` + `ScreeningMatchVariantEntity` (full evidence trail)

---

## Screening Modes

`NameNormalizer.classify()` determines the mode for each customer before Solr queries are built.

| Mode | Trigger | Solr Strategy |
|---|---|---|
| `NORMAL` | Name passes all quality checks | Full wildcard + fuzzy + phonetic via `AdvancedScreeningSolrQueryBuilder` |
| `RESTRICTED` | Single-token name | Exact phrase + fuzzy~1 + phonetic; no leading wildcard. FIU-ORG: skipped |
| `NIC_ONLY` | All-initials name + valid Sri Lankan NIC | NIC field query only on FIU and local_watch; all others: not applicable |
| `SKIPPED` | Blank, placeholder, or unscreenable name | No Solr query; exception row written |

Customers with `NORMAL` disposition are not written to `screening_exceptions`. All other dispositions produce a row via `ScreeningExceptionWriter`.

---

## Database Schema

### Tables created for this project

```sql
-- Run-level tracking
CREATE TABLE aml.screening_runs (
    run_id           varchar(20) PRIMARY KEY,   -- yyyyMMdd_HHmmss
    status           varchar(20),
    started_at       timestamp,
    completed_at     timestamp,
    total_count      bigint,
    exception_count  bigint,
    alert_count      bigint,
    skipped_count    bigint,
    restricted_count bigint,
    nic_only_count   bigint,
    infra_failed_count bigint,
    transformed_count  bigint
);

-- Per-customer exception audit trail
CREATE TABLE aml.screening_exceptions (
    id              varchar(36) PRIMARY KEY,    -- UUIDv7
    run_id          varchar(20),
    client_id       varchar(100),
    name            text,
    action          varchar(50),
    reason_code     varchar(50),
    screening_mode  varchar(20),
    -- per-list result columns (CANDIDATE_FOUND / ATTEMPTED / NOT_APPLICABLE / FAILED)
    fiu_result      varchar(30),
    fiu_org_result  varchar(30),
    un_result       varchar(30),
    watch_result    varchar(30),
    lxnx_result     varchar(30),
    created_at      timestamp DEFAULT now()
);

-- Candidate evidence per alert
CREATE TABLE aml.sch_screening_match (
    id               bigserial PRIMARY KEY,
    screen_id        bigint NOT NULL REFERENCES aml.sch_screening_cus(screen_id),
    run_id           varchar(20),
    doc_id           varchar(255),
    list_source      varchar(100),
    best_score       numeric(5,2),
    above_threshold  boolean,
    screened_at      timestamp
);

-- Name variant scores per candidate
CREATE TABLE aml.sch_screening_match_variant (
    id              bigserial PRIMARY KEY,
    match_id        bigint NOT NULL REFERENCES aml.sch_screening_match(id),
    variant_name    varchar(500),
    variant_score   numeric(5,2)
);

-- Archive table (DDL mirrors screening_exceptions)
CREATE TABLE aml.screening_exceptions_archive (LIKE aml.screening_exceptions INCLUDING ALL);
```

---

## Configuration

All configuration lives in `application.yml`.

```yaml
application:
  config:
    screening:
      batch-size: 1000
      max-concurrent-chunks: 4
      scoring-buffer-capacity: 500
      alert-buffer-capacity: 200
      score-threshold: 86.0
      score-thresholds:
        fiu_individual_core: 86.0
        fiu_org_core: 85.0
        local_watch_core: 86.0
        un_consolidated_core: 88.0
        lxnx_entities_core: 94.0
      alert-batch-size: 50
      alert-batch-timeout-ms: 5000
      metrics-interval-ms: 30000
      retention-years: 7
      archival-cron: "0 0 2 1 * *"

    solr:
      zk-hosts: localhost:2181
      collections:
        individual: fiu_individual_core
        org: fiu_org_core
        un: un_consolidated_core
        watch: local_watch_core
        lxnx: lxnx_entities_core

    match-rate:
      endpoints:
        - http://localhost:5000
        - http://localhost:5001
        - http://localhost:5002
      connect-timeout: 3000
      read-timeout: 10000
      health-check-interval: 15000
```

> **Security:** Move DB passwords and credentials to environment variables or a secrets manager before production deployment.

---

## Running the Application

### Prerequisites

- JDK 21+
- PostgreSQL with `aml` schema and all tables created
- Apache Solr cluster with ZooKeeper
- Python scoring cluster running (see below)
- Oracle DB accessible (or PostgreSQL substitute for dev)

### Build

```bash
mvn clean package -DskipTests
```

### Run

```bash
java -jar target/Screening-Schedule-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=test
```

`--spring.profiles.active=test` activates `ScreeningTrigger`, which fires `ScreeningProcessor.process()` on startup.

### Monitor

```bash
tail -f logs/spring.log
tail -f logs/match-results.log
tail -f logs/missed-alerts.log
tail -f logs/metrics.log
tail -f logs/screening-exceptions.json
```

---

## Python Scoring Cluster

The fuzzy name matching service runs as standalone Python processes.

### Setup

```bash
python3 -m venv /path/to/python_cluster/venv
/path/to/python_cluster/venv/bin/pip install flask waitress rapidfuzz
```

### systemd service (repeat for ports 5001, 5002)

```ini
[Unit]
Description=AML Match Rate Service - Port 5000
After=network.target

[Service]
Type=simple
User=user
WorkingDirectory=/path/to/python_cluster
ExecStart=/path/to/python_cluster/venv/bin/python3 match_rate_V2.py 5000
Restart=on-failure
RestartSec=5
```

### Health check

```bash
curl http://localhost:5000/health
# {"status": "UP"}
```

### API contract

**Request:**
```json
{
  "keyword": "customer name",
  "dataArr": [{"key": "docId|variantIndex", "value": "candidate name"}],
  "screening_mode": "NORMAL"
}
```

**Response:**
```json
[{"key": "docId|variantIndex", "match": 85}]
```

---

## Logging

| File | Content | Retention |
|---|---|---|
| `logs/spring.log` | Full application log | Rolling 500 MB / 30 days |
| `logs/match-results.log` | Evidence trail per qualifying customer | Overwritten each run |
| `logs/missed-alerts.log` | Dead-letter alerts — full payload | Accumulates |
| `logs/metrics.log` | Pipeline counters flushed at `metricsIntervalMs` | Rolling |
| `logs/screening-exceptions.json` | Structured JSON exception events (one object per line) | 1095-day rolling / 10 GB cap (FATF Rec. 11) |

---

## REST API

Base path: `/api/screening`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/runs` | All screening runs, ordered by start time descending |
| `GET` | `/runs/{runId}/exceptions` | Paginated exceptions for a run; filter by `ALL / SKIPPED / RESTRICTED / NIC_ONLY / HITS / FAILED` |
| `GET` | `/exceptions` | Exceptions by date range (`startDate`, `endDate`) |
| `GET` | `/exceptions/{id}` | Full detail for a single exception |

---

## Known Issues & Pre-Production Checklist

| # | Item | Status |
|---|---|---|
| 1 | Oracle JDBC URL is placeholder — configure real Oracle connection | Open |
| 2 | DB credentials in plain text in `application.yml` — move to env vars / secrets manager | Open |
| 3 | `ScreeningTrigger` auto-fires on startup under `test` profile — add production cron or REST trigger | Open |
| 4 | `AlertConsumer` catch block on `saveAll` failure does not forward to `DeadLetterService` — alerts can be lost on DB batch failure | Open |
| 5 | `EndpointSelector` has no alerting when all match-rate endpoints are unreachable | Open |
| 6 | Forced shutdown of `ALERT_GENERATION` executor causes 30 s timeout before force-kill (cosmetic, no data loss) | Known / low priority |
| 7 | `progress=75%` at shutdown — `ORACLE_READER` ends as `EXHAUSTED` (not `DONE`) | Known / cosmetic |

---

## Post-MVP Roadmap

1. **Production scheduler** — daily cron trigger with concurrency guard and email notification on failure
2. **Alert auto-assignment** — `aml.assign_alerts()` stored procedure for round-robin allocation to approval groups
3. **REST trigger endpoint** — `POST /api/screening/run` for on-demand manual runs
4. **LXNX nationality filter** — post-scoring `countries_ss` filter to reduce common-name false positives against non-South-Asian LXNX entities
5. **NIC post-scoring boost** — exact NIC match → score 100.0 override, bypassing name similarity
6. **AI-assisted review** — contextual alert review using LLM (DOB, NIC, nationality cross-matching)
7. **Python service enhancement** — return `match_method` alongside score for `ScreeningMatchEntity.match_method` column
8. **Checkpoint / resume** — persist last processed offset; resume from crash point without re-screening
9. **Alert & Exception Management Service** — separate service for reviewer workflow (see `ALERT_SERVICE_SPEC.md`)

---

## Evidence Trail

For each qualifying alert the full screening evidence is persisted:

- **`sch_screening_match`** — one row per Solr candidate evaluated
- **`sch_screening_match_variant`** — one row per name variant scored by the Python service
- **`screening_exceptions`** — one row per exception customer per run with per-list disposition

This satisfies FATF, EBA, and OFAC audit trail requirements — regulators can trace every match decision back to the exact sanction list entry and the specific name variants that triggered it.

---

*AML Screening Engine · KPMG · 2026*
