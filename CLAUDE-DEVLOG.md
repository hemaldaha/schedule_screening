# CLAUDE-DEVLOG.md — Development Notes

> Operational knowledge: incomplete features, known issues, bug history, and configuration reference.

---

## Fields / Features Marked Incomplete or TODO

### `AlertConsumer.persistBatch()`
- **TODO:** The `catch` block on `saveAll` failure logs the error but does not forward the failed batch to `DeadLetterService`. Records lost on a DB batch failure are currently unrecoverable.
  ```java
  // TODO: forward to DeadLetterService
  ```

### `ScreeningMatchEntity`
- **TODO:** `match_method` column is commented out, pending the Python service being enhanced to return the matching algorithm used.
  ```java
  // TODO: add match_method when Python service is enhanced to return it
  ```

### `EndpointSelector.nextHealthy()`
- **TODO:** When all match-rate endpoints are unreachable after 3 retries, a log entry is written but no alerting mechanism is triggered.
  ```java
  // TODO: Alert system admin — all match-rate endpoints unavailable
  // Suggested: send email/SMS notification or trigger monitoring webhook
  ```

### `ScreeningSolrQueryBuilder`
- **Legacy:** Superseded by `AdvancedScreeningSolrQueryBuilder`. Contains a commented-out filter `AND blacklisted_b:true` in `buildUNQuery()`. Kept in the codebase for reference but should be removed once the migration is confirmed stable.

---

## Known Typos and Naming Bugs

| Location | Typo | Correct |
|---|---|---|
| `ScreeningMetrics` scheduler thread name | `"matrics-flush"` | `"metrics-flush"` |
| Test source package | `com.kmpg.aml.screening` | `com.kpmg.aml.screening` |

---

## Bugs Fixed During Integration Testing (2026-07-06 → 2026-07-11)

| # | Bug | File(s) | Fix |
|---|---|---|---|
| 1 | JPQL class reference typo `com.kmpg` → `com.kpmg` in `MiniAlert` query — caused startup failure | `AlertRepository.java` | Corrected package name in `@Query` string |
| 2 | `SanctionTypeEntity` `@JoinColumn` generated column `sanction_id_sanction_id` (double-suffix) — FK lookup failed | `SanctionTypeEntity.java`, `SanctionTypeRepository.java` | Set `name = "sanction_id"` on `@JoinColumn`; renamed DB column via `ALTER TABLE` |
| 3 | `getCountsByRunId()` declared `Object[]` but JPQL returns `List<Object[]>` → `ClassCastException` in `updateRunRecord()` | `ScreeningExceptionRepository.java`, `ScreeningProcessor.java` | Changed return type to `List<Object[]>`; caller uses `.get(0)` |
| 4 | `AlertManager.loadData()` filtered by `[pending, rejected]` — missed `unassigned` alerts, every re-run created duplicate alerts | `AlertManager.java` | Changed filter statuses to `[unassigned, pending]` |
| 5 | `loadAlerts` `CompletableFuture` never awaited before chunk processing — race condition, exceptions silently swallowed | `ScreeningProcessor.java` | Added `loadAlerts.get()` to the pre-load await block |
| 6 | `AlertManager.loadData()` `Collectors.toMap()` threw `IllegalStateException` on customers with multiple active alerts (duplicate key) | `AlertManager.java` | Added merge function `(existing, replacement) -> existing` to both `toMap()` calls |
| 7 | Run record counts (exception_count, alert_count, skipped_count etc.) were near-zero — `updateRunRecord()` fired at Stage 1 EXHAUSTED before Stages 3 & 4 flushed data | `ScreeningGovernor.java`, `ScreeningProcessor.java` | Added `pipelineCompletionHook` (`volatile Runnable`) to `ScreeningGovernor`; hook fires at end of `initiateShutdown()` after all executors terminate; `ScreeningProcessor.process()` registers the hook; added `AtomicReference<Throwable> scenarioError` to capture scenario failures |
| 8 | Only 2 of 5 sanction types present in `sch_screening_san_type` for `sanction_id=2` — FIU, FIU-ORG, consoli never screened; all three exception columns NULL | DB data | Inserted 3 missing rows (`FIU`, `FIU-ORG`, `consoli`) after resetting the sequence with `setval` |
| 9 | Drain chain broken — poison pill never reached `AlertConsumer`; `AlertBuffer.putPoisonPill()` called from `ScreeningGovernor` but method did not exist | `AlertBuffer.java`, `ScoringConsumer.java`, `AlertConsumer.java` | Added `putPoisonPill()` to `AlertBuffer`; `ScoringConsumer` drain pill-branch calls `governor.report(PYTHON_SCORING, DONE)` (not `buffer.signalDone()`); `AlertConsumer` drain pill-branch calls `governor.report(ALERT_GENERATION, DONE)` |
| 10 | `processInChunks()` discarded `Future<?>` returned by `executor.submit()` — scenario `CompletableFuture` completed before chunk threads finished Solr queries, firing EXHAUSTED 13 ms after the first chunk started → 0 customers scored | `ScreeningProcessor.java` | Collect all `Future<?>` into `List<Future<?>> chunkFutures`; `f.get()` each after the while loop before returning |
| 11 | `SanctionStrategyBuilder.composeStrategy()` had outer `if (types.contains("FIU-ORG"))` guard that forced `NORMAL` mode and `isExceptionCustomer=false` for every customer once FIU-ORG was added to the sanction type list — `NameNormalizer` never called, 0 exception rows written | `SanctionStrategyBuilder.java` | Removed the outer `if/else` block entirely; personal name classification via `NameNormalizer.classify()` now always runs first; FIU-ORG list logic already handled correctly inside `resolveStrategy()` |
| 12 | `SolrClientConfig` active bean used a simplified builder that ignored `connection-timeout` and `socket-timeout` from `application.yml` — timeout settings had no effect on Solr HTTP queries; Solr hang would block virtual threads indefinitely | `SolrClientConfig.java` | Activated the `Http2SolrClient.Builder` block with `.withConnectionTimeout()` and `.withIdleTimeout()`, wiring the config values into the HTTP client |
| 13 | `SanctionStrategyBuilder` timeout detection only checked `ex.getCause() instanceof SocketTimeoutException` — `Http2SolrClient` wraps timeouts as `SolrServerException → java.util.concurrent.TimeoutException`, not `SocketTimeoutException`, so all timeouts were classified as `PROVIDER_ERROR` instead of `SYSTEM_TIMEOUT` | `SanctionStrategyBuilder.java` | Added `isTimeoutException(Throwable)` helper that walks the full cause chain checking for both `SocketTimeoutException` and `TimeoutException` |

### Known Cosmetic Issues (not fixed)

- **`progress=75%` at shutdown** — `ORACLE_READER` ends in `EXHAUSTED` (not `DONE`), so only 3/4 stages count toward the percentage.
- **Forced shutdown of Alert Generation executor** — `alert-consumer-0` calls `governor.report(ALERT_GENERATION, DONE)` which triggers `initiateShutdown()` which calls `awaitTermination(30s)` on the same executor — self-deadlock, times out after 30 s then force-kills. No data is lost. Fix would be to run `initiateShutdown()` on a new virtual thread from `evaluateShutdown()`.

---

## Configuration Notes (`application.yml`)

- **PostgreSQL** (primary): `jdbc:postgresql://localhost:5432/schedule-screening`
- **Oracle** (secondary): currently points to the same PostgreSQL URL — **placeholder only, real Oracle URL not configured**.
- **Solr ZooKeeper**: `localhost:2181`
- **Solr collections** (`application.config.solr.collections.*`): `individual` → `fiu_individual_core`, `org` → `fiu_org_core`, `watch` → `local_watch_core`, `un` → `un_consolidated_core`, `lxnx` → `lxnx_entities_core`
- **Python scoring service** (`application.config.match-rate.endpoints`): `http://localhost:5000` — active and integrated in Stage 3.
- **Hardcoded credentials** in `application.yml`: password visible in plain text — move to environment variables or secrets manager before production.
- **Virtual threads**: `spring.threads.virtual.enabled=true`
- **Key screening config** (`application.config.screening.*`): `batchSize`, `maxConcurrentChunks`, `chunkBufferCapacity`, `scoringBufferCapacity`, `alertBufferCapacity`, `scoreThreshold`, `alertBatchSize`, `alertBatchTimeoutMs`, `metricsIntervalMs`, `retentionYears` (default 7), `archivalCron` (default `"0 0 2 1 * *"` — 02:00 on the 1st of every month)
- **Match-rate config** (`application.config.match-rate.*`): `endpoints` (list), `connectTimeout`, `readTimeout`, `healthCheckInterval`
- **Log files** (`logs/`): `spring.log` (main), `match-results.log` (Solr match output), `missed-alerts.log` (dead-letter alerts), `metrics.log` (pipeline counters), `screening-exceptions.json` (structured JSON exception events — one object per line, UTC timestamps, 1095-day rolling retention / 10 GB cap — FATF Rec. 11)
- **Archive table**: `aml.screening_exceptions_archive` — rows moved from `aml.screening_exceptions` by `ArchivalService` after `retentionYears`. Never deleted. DDL: `LIKE aml.screening_exceptions INCLUDING ALL` in `new_tables.sql`.
