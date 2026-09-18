# Screening-Schedule — HNB Client Variant

> **Branch:** `client/hnb`
> **Do not merge this branch into `main`.** It has diverged too far to merge directly — see [Relationship to `main`](#relationship-to-main) below.

## What this branch is

This is the AML screening pipeline as deployed for the **HNB** client. It forked from an early v1
baseline (`src.zip`, 2026-04-05) before the core `Screening-Schedule` codebase evolved into what is
now `main` (v2). A junior engineer extended this fork independently with HNB-specific features while
`main` gained its own set of compliance and reliability improvements — the two codebases have not been
reconciled since.

Stack: Java 21 / Spring Boot / PostgreSQL / Apache Solr / Python (RapidFuzz scoring service).

## Relationship to `main`

`main` is the actively developed core pipeline (v2) — treat it as the base all clients should inherit
from. This branch is a client-specific fork that predates several of `main`'s systems and should
eventually be rebuilt on top of `main` rather than maintained in parallel.

Two comparison reports document the full divergence in detail:

- `HNB-COMPARISON.md` — full feature/regression comparison, HNB (current) vs `main` (v2)
- `potential_v1_hnb_comparison.md` — what changed between the v1 fork point and current HNB

**Do not open a mergeable PR from this branch into `main`.** The package structure, query builder, and
several core classes differ enough that a real git merge would produce a broken hybrid. Porting
individual features forward should happen as new, deliberate commits on `main` or on a
`feature/hnb-ops-port` branch — cherry-picking specific pieces, not merging wholesale. GitHub's compare
view (`main...client/hnb`) or a **draft** PR can be used for review/reference only.

## What HNB added that `main` doesn't have

These are genuinely new operational features, considered good candidates to port into `main`:

- Email notifications on run start/completion/failure (`ScheduleMailService`, `MailProperties`)
- Manual run REST endpoint (`GET /aml/screening/schedule` via `ManualRun`)
- `AtomicBoolean` concurrency guard preventing overlapping runs (`ScreeningTrigger`)
- Production cron schedule (`0 0 17 * * ?`, IST) — `main`'s trigger is currently test-only
- Dynamic score threshold loaded from `kyc.kyc_config` at runtime (needs a race-condition fix before
  adoption — see `HNB-COMPARISON.md` A4)
- Post-scoring NIC exact-match score override with `alertBasedOn` audit field (`ScoringConsumer`) —
  complements, does not replace, `main`'s NIC_ONLY Solr-level retrieval path
- Stale run-log resolution on startup, recovering from crashed runs
- Per-scenario run logging (`ScheduleRunLog`, `ScheduleRunLogEntity`)
- Post-run alert auto-assignment via `aml.assign_alerts()` stored procedure — needs review before
  adoption; assignment logic living in the DB layer vs Java service is an open question

## Known regressions vs `main` — do not port these as-is

`main` has compliance-relevant systems this branch never had (it forked before they existed):

- No `NameNormalizer` / `ScreeningMode` classification (NORMAL / RESTRICTED / NIC_ONLY / SKIPPED)
- Legacy `ScreeningSolrQueryBuilder` only — no phonetic matching, no field weighting, no length-adaptive
  query logic
- No screening exception audit trail (`ScreeningExceptionEntity` + writer + REST API)
- No `ScreeningMetrics` (pipeline/JVM metrics)
- No `ArchivalService` (FATF Recommendation 11 record retention)
- Silent Solr failure swallowing — no typed `SolrQueryException`, no per-list failure differentiation

## Known bugs introduced in this branch since the v1 fork point

- `SolrQueryExecutor`: `mm` parameter regressed from `"75%"` to `"1"` (comment still says 75% — likely
  accidental)
- `ScreeningSolrQueryBuilder.buildUNQuery()`: `alias_txt` OR clause commented out — UN alias-only
  matches are no longer retrieved
- `ConsolidatedCandidate`: alias variant extraction removed — aliases aren't passed to scoring even
  when retrieved
- `AlertConsumer`: initial alert status set to `pending` instead of `unassigned` (causes duplicate
  alerts on re-run for customers with existing unassigned alerts)
- `process()`: `loadAlerts.get()` not awaited — re-introduces a chunk-processing race condition
- Failure-path notification email body copy says "completed successfully"

See `potential_v1_hnb_comparison.md` for the full list with line-level detail. These should be fixed on
this branch before any further feature porting happens.

## Client-specific requirement (context for the exact-match / phonetic gap)

HNB requires certain predefined names to match **exactly**, with no phonetic or alias fuzziness, per
their own internal compliance logic. The current implementation achieves this by using the legacy query
builder entirely (dropping phonetic/alias matching for *all* names, not just the predefined list) — this
is broader than what was actually requested and is flagged as a compliance risk in `HNB-COMPARISON.md`
(C2). The recommended fix is a scoped `STRICT_EXACT` mode inside `main`'s
`AdvancedScreeningSolrQueryBuilder`, applied only to the client's predefined name list, rather than
disabling fuzzy/phonetic matching pipeline-wide.

## Package name

The v1 baseline this branch forked from used `com.kmpg.aml.screening` (typo — missing second `p` in
`kpmg`). This has been corrected to `com.kpmg.aml.screening` on this branch, matching `main`.

---
*This README describes the branch as of 2026-09-19. Update it as reconciliation with `main` progresses.*
