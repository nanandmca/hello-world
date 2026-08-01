# Personalisation bulk migration — operational reference

DB2 is the staging/source database. MariaDB is the target. The design uses **one staging table only** and stores worker ownership directly in `PERSONALISATION_MIGRATION_STAGE`.

## Scheduler and async completion

- Each scheduler execution claims up to `max-customers-per-run` unique customers under one worker `RUN_ID`.
- Customer IDs are split into chunks, default 500.
- `RunProcessor` uses `ExecutorCompletionService` and keeps exactly `async-threads` chunks in flight.
- When one chunk completes, the next chunk is submitted immediately; the scheduler interval is not used between chunks.
- The scheduler method blocks until every chunk in the run completes.
- `fixedDelay` starts only after the whole run returns.
- An `AtomicBoolean` prevents same-JVM scheduler overlap. Conditional DB2 claiming prevents cross-instance overlap.

## Main endpoints

All endpoints are under `/internal/migration`; secure them with the organisation's operator role/network controls.

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/sanity?limit=500` | Claim and process the first 500 available unique customers using the full production flow. |
| POST | `/process?limit=10000` | Manually claim and process up to the requested limit. Maximum 100,000. |
| GET | `/runs?limit=50` | Recent `RUN_ID`/batch summaries, activity time and status row counts. |
| GET | `/runs/{runId}` | Customer and row counts grouped by status for one batch. |
| POST | `/runs/{runId}/retrigger?includeFailed=true&processNowLimit=500` | Reset non-final rows for the run and optionally process immediately under a new run ID. Final rows are not reset. |
| GET | `/pending` | Count distinct pending/retry customers. |
| GET | `/verify?offset=0&limit=500` | Compare source and target row counts for an offset/limit customer sample. |
| GET | `/verify/keys?offset=0&limit=100` | Exact composite-key comparison, reporting missing and extra keys. Maximum 500 customers. |

## Retigger semantics

Retrigger resets only rows belonging to the selected run with these statuses:

- default: `IN_PROGRESS`, `RETRY_PENDING`
- when `includeFailed=true`: also `FAILED`

Rows in `MIGRATED` and `ALREADY_MIGRATED` are never reset. Reset rows become `RETRY_PENDING`, ownership is cleared, and a subsequent scheduler/manual process claims them under a new run ID.

## Reconciliation

Row-count verification is fast but does not prove that the same business keys exist. Use exact key verification for smaller samples. In production, also add field-level hashes for critical value columns if target transformations are not expected.

## Production notes

- Replace sample table and column names.
- Confirm DB2 `OFFSET ... FETCH NEXT` and timestamp arithmetic syntax for the installed DB2 edition/version.
- Keep `skip-existing-customers=false` unless existence proves complete customer migration.
- Use target unique key `(CUSTOMER_SYS_GEN_ID, PERSONALISATION_TYPE, PERSONALISATION_KEY)` and idempotent upsert.
- Start with 2 threads per instance, 500-customer chunks, and a smaller manual sanity run before enabling 100,000-customer claims.
- Authentication/authorization is intentionally left for integration with the organisation's standard Spring Security setup.
