# Operations and recovery

## Normal scheduler execution

1. `MigrationScheduler` obtains the local `AtomicBoolean` guard.
2. `ClaimService` generates a unique worker `RUN_ID` and conditionally claims complete customers in DB2.
3. `RunProcessor` partitions customer IDs into 500-customer chunks.
4. Exactly `async-threads` chunks are submitted initially.
5. `ExecutorCompletionService.take()` waits for whichever chunk finishes first.
6. The next chunk is submitted immediately, maintaining a strict maximum number of active chunks.
7. The scheduler waits until all chunks finish, releases its guard, and only then does `fixedDelay` begin.

## Multi-instance race protection

- Every instance can initially read the same candidate customer IDs.
- Claim SQL updates rows only while status is `PENDING` or `RETRY_PENDING`.
- Each worker re-reads only customers carrying its own `RUN_ID` and `CLAIMED_BY`.
- MariaDB target writes use a business unique key and idempotent upsert.
- Manual endpoints call the same claim code as the scheduler.

## Sanity run

```bash
curl -X POST 'http://host/internal/migration/sanity?limit=500'
```

This processes 500 unique available customers with the complete production path.

## Monitor a run

```bash
curl 'http://host/internal/migration/runs/DCE_0_xxx'
```

The response includes customer and row counts per status, activity timestamps, whether rows remain in progress, and whether the heartbeat is stale.

## Retrigger a stopped run

Inspect first. Then:

```bash
curl -X POST 'http://host/internal/migration/runs/DCE_0_xxx/retrigger?includeFailed=true&force=false&processNowLimit=500'
```

- `force=false` rejects a run with a recent heartbeat.
- `includeFailed=false` resets only `IN_PROGRESS` and `RETRY_PENDING`.
- Final `MIGRATED` and `ALREADY_MIGRATED` rows are not reset.
- Rows are processed under a newly generated run ID.

## Reconciliation

Fast row-count sample:

```bash
curl 'http://host/internal/migration/verify?offset=0&limit=500'
```

Exact composite-key sample:

```bash
curl 'http://host/internal/migration/verify/keys?offset=0&limit=100'
```

Offset/limit is intentionally used only for deterministic validation sampling, not for work claiming.
