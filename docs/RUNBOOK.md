# Operations Runbook

## Provisioning a new environment

1. **Database instance**: MySQL 8.4+ (utf8mb4). Ensure
   `log_bin_trust_function_creators = 1` when binlog is enabled (the schema
   contains triggers for ledger immutability); otherwise run migrations as a
   role with SUPER.

2. **Databases and roles** (least privilege):

   ```sql
   CREATE DATABASE inventory_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   CREATE DATABASE inventory_db_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

   CREATE USER 'ims_app'@'%' IDENTIFIED BY '<strong password>';
   GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE ON inventory_db.* TO 'ims_app'@'%';

   CREATE USER 'ims_migrate'@'%' IDENTIFIED BY '<strong password>';
   GRANT ALL ON inventory_db.* TO 'ims_migrate'@'%';
   ```

3. **Schema migration** (release step, run once per release):

   ```bash
   IMS_DB_URL='jdbc:mysql://host:3306/inventory_db?useSSL=...' \
   IMS_DB_USERNAME=ims_migrate IMS_DB_PASSWORD=... \
   java -cp target/inventory-api.jar:target/lib/* com.ims.tools.Migrate
   ```

   The runner records each version with a SHA-256 checksum in
   `schema_migrations`; edited-after-apply files abort the run.
   Note: MySQL DDL is non-transactional - if a migration fails midway, fix
   the issue, repair the partial objects, and rerun.

4. **Application start**:

   ```bash
   IMS_DB_URL=... IMS_DB_USERNAME=ims_app IMS_DB_PASSWORD=... \
   IMS_HTTP_PORT=8080 java -cp target/inventory-api.jar:target/lib/* com.ims.Main
   ```

   `app.migrateOnStartup` is false by default (the app role has no DDL
   grants). First boot with an empty `users` table creates `admin` with a
   password from `IMS_BOOTSTRAP_ADMIN_PASSWORD` or a generated one written to
   `logs/bootstrap-admin.txt` - rotate it immediately.

## Daily operations

- **Health**: `GET /api/health` (also checks pending migrations). Wire to
  your load balancer / orchestrator probes.
- **Logs**: console + `logs/ims.log` with daily rolling (14 days, 200 MB cap).
  HTTP 5xx responses are logged at WARN with method and path.
- **Lockouts**: accounts lock for 15 minutes after 5 failed logins
  (configurable). Unlock early by resetting the password.
- **Token hygiene**: tokens live 4 hours (configurable). `POST /auth/logout`
  revokes one token; deactivating a user should be followed by a review of
  `api_tokens` (revoked_at audit) if immediate invalidation is required.
- **Housekeeping**: `TokenService.purgeExpired()` deletes expired token rows;
  schedule as needed.

## Backup and recovery

- Nightly `mysqldump --single-transaction inventory_db` is sufficient
  (InnoDB, consistent snapshot).
- The ledger is append-only; restore = restore the dump. Post-restore,
  run the ledger reconciliation query (see docs/TESTING.md) to confirm
  `stock_items` matches `SUM(stock_movements)`.

## Common incidents

| Symptom | Cause | Action |
|---|---|---|
| 503 health, `database: DOWN` | DB unreachable / pool exhausted | check MySQL and `db.pool.*`; connection timeout surfaces as 500s on request |
| `pendingMigrations > 0` | new release deployed without migration | run the Migrate tool |
| 409 "Deadlock detected ... retry" | concurrent stock ops on same rows | safe to retry; clients should back off briefly |
| 409 "append-only ledger" on manual SQL fix | tried to UPDATE stock_movements | write a compensating adjustment instead |
| Slow reports on huge ledgers | movement queries by date | add date-range filters; consider archiving old movements |

## Capacity notes (defaults)

Pool `maxSize=20`, page size cap 200, request timeout 60 s. The HTTP layer
uses one virtual thread per request, so blocking JDBC does not starve other
requests. For larger deployments raise `db.pool.maxSize` and MySQL
`max_connections` together.

## Observability

- `GET /api/metrics` (Prometheus text): scrape with a sidecar/external
  Prometheus. Useful series: `http_server_requests_total`,
  `http_server_request_duration_seconds_bucket`,
  `ims_http_requests_active`, `db_pool_connections{state="active"}`,
  `ims_rate_limited_total`.
- Suggested alerts: error rate (`rate(http_server_requests_total{status=~"5.."}[5m]) > 1%`),
  saturation (`ims_http_requests_active > 100`), pool exhaustion
  (`db_pool_threads_pending > 0 for 5m`), crash-looping (liveness failures).
- Counters reset on restart - always use `rate()`/`increase()`, never
  absolute values.

## Rate limiting operations

- Per-IP, per-process. Behind N replicas the effective budget is N x the
  configured limit; treat it as a safety net and enforce exact quotas at
  the edge (ingress annotation / WAF).
- Emergency off-switch: `IMS_HTTP_RATE_LIMIT_ENABLED=false` (restart).
- Suspected lockouts: check `ims_rate_limited_total` by bucket and the
  client IP in access logs (each line carries ip + requestId).

## Graceful shutdown / rolling deploys

SIGTERM sequence: readiness flips DOWN (LB removes the instance) ->
in-flight requests drain up to `app.shutdownDrainSeconds` (default 10;
k8s `terminationGracePeriodSeconds` should exceed it, manifests use 30) ->
pool closes -> exit 143. Verify with: `kill -TERM <pid>` then
`grep -E "draining|Shutdown complete" logs/server.out`.

## Housekeeping

The scheduler purges expired api_tokens and stale idempotency_keys hourly
(`app.housekeeping.intervalMinutes`). Safe to run on every replica
(idempotent deletes); disable on all but one if you prefer single-owner.

## Running a stocktake (cycle count)

1. `POST /api/stocktakes {warehouseCode, name, blind}` - the sheet freezes
   every active product with its current book quantity. Use `blind: true`
   when counters must not see book quantities.
2. `POST /api/stocktakes/{id}/open`, then counters submit
   `POST /api/stocktakes/{id}/counts {productSku, countedQty}` (STAFF can
   do this). Download `GET /api/stocktakes/{id}/export` for paper/counting
   gun workflows.
3. `POST /api/stocktakes/{id}/close` freezes the counts; reviewers see
   per-line variances in `GET /api/stocktakes/{id}`.
4. `POST /api/stocktakes/{id}/post` (MANAGER+): each non-zero variance
   becomes one adjustment document + one ledger movement (reason
   COUNT_CORRECTION). Uncounted lines are skipped - partial counts are a
   normal cycle-count outcome. A count below the reserved quantity aborts
   the posting atomically; resolve reservations (ship or cancel the SOs)
   first.
5. `stock_items.last_counted_at` is stamped for every counted line - use it
   to schedule the next count.

## Webhook operations

- Register endpoints via `POST /api/webhooks` with a random secret of at
  least 16 characters; receivers must verify `X-IMS-Signature`
  (HMAC-SHA256 over the raw body) and dedupe on `X-IMS-Delivery-Id`.
- `POST /api/webhooks/{id}/test` enqueues a TEST event; watch
  `GET /api/webhooks/{id}/deliveries` until it shows SUCCESS with the
  receiver's status code.
- Delivery failures retry on an exponential backoff (1m doubling, 6h cap)
  up to `app.webhooks.maxAttempts` (default 6) and then park as FAILED:
  fix the endpoint, then `POST .../deliveries/{id}/retry` to requeue.
- Pausing a webhook (`isActive: false`) stops new events immediately;
  already-queued deliveries drain or park.
- The dispatcher runs every `app.webhooks.dispatchIntervalSeconds`
  (default 30). On multi-replica deployments, receivers must dedupe by
  delivery id, or disable dispatch on all but one replica
  (`app.webhooks.enabled=false`).

## Backup verification

1. `scripts/backup.sh` (cron): --single-transaction dump, gzip, sha256.
2. Monthly: restore into a scratch DB (`scripts/restore.sh --yes` against
   a throwaway database) and run `SELECT COUNT(*)` sanity checks on
   users/products/stock_movements.
3. Keep the checksum sidecars; they catch bit rot via `sha256sum -c`.
