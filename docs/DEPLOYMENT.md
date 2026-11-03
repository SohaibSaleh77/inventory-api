# Deployment Guide

Ways to run IMS in production, from a single VM to Kubernetes, plus the
complete configuration reference.

## Quick decision table

| Environment | Recommendation | Section |
|---|---|---|
| Workstation / demo | docker compose | [Docker Compose](#docker-compose) |
| Single VM | systemd unit + local MySQL | [Bare metal](#bare-metal-systemd) |
| Cluster | k8s manifests (kustomize) | [Kubernetes](#kubernetes) |
| CI | GitHub Actions workflow included | `.github/workflows/ci.yml` |

## Prerequisites

- Java 21 runtime (JRE is enough; the jar is thin, dependencies in `lib/`)
- MySQL 8.0.x with `log_bin_trust_function_creators=1` (or grant the
  migration role SUPER) - the schema creates triggers for ledger immutability
- Network egress only to the database; the API serves HTTP on one port

## Configuration reference

Order (highest wins): system properties `-Dkey=value` → environment
`IMS_KEY` → `./config/application.properties` → classpath defaults.

| Key | Default | Meaning |
|---|---|---|
| http.host | 127.0.0.1 | bind address (containers: 0.0.0.0) |
| http.port | 8080 | listen port |
| http.maxRequestBodyBytes | 1048576 | request body cap (413 beyond) |
| http.rateLimit.enabled | true | per-IP rate limiting |
| http.rateLimit.requestsPerSecond | 50 | general refill rate |
| http.rateLimit.burst | 100 | general burst capacity |
| http.rateLimit.loginRequestsPerMinute | 10 | login-only bucket |
| http.trustProxyHeaders | false | honour X-Forwarded-For / X-Real-IP (only behind a trusted proxy!) |
| http.cors.allowedOrigins | (empty) | comma list; empty = no CORS headers |
| http.metrics.enabled | true | expose /api/metrics |
| db.url | jdbc:mysql://127.0.0.1:3306/inventory_db?useSSL=false... | JDBC URL |
| db.username / db.password | ims_app / (empty) | DML app role; never DDL |
| db.pool.maxSize / minIdle | 20 / 4 | HikariCP sizing |
| db.pool.connectionTimeoutMs | 8000 | fail fast when pool exhausted |
| db.retry.deadlockMaxRetries | 2 | auto-retry deadlock victims |
| security.token.ttlMinutes | 240 | bearer token lifetime |
| security.password.iterations | 210000 | PBKDF2 cost |
| security.login.maxFailedAttempts | 5 | account lockout threshold |
| security.login.lockoutMinutes | 15 | lockout duration |
| app.idempotency.enabled | true | Idempotency-Key support |
| app.idempotency.ttlHours | 24 | stored-response retention |
| app.shutdownDrainSeconds | 10 | graceful drain window |
| app.housekeeping.enabled | true | background purge scheduler |
| app.housekeeping.intervalMinutes | 60 | purge cadence |
| app.webhooks.enabled | true | webhook dispatcher on/off |
| app.webhooks.dispatchIntervalSeconds | 30 | outbox drain cadence |
| app.webhooks.timeoutSeconds | 10 | per-delivery HTTP timeout |
| app.webhooks.maxAttempts | 6 | attempts before a delivery parks FAILED |
| app.migrateOnStartup | false | DDL on boot (keep false in prod; use Migrate tool) |

Secrets (`db.password`, `IMS_BOOTSTRAP_ADMIN_PASSWORD`) must come from the
environment / a secret manager - never from committed files.

## Docker Compose

```bash
cp .env.example .env         # set IMS_BOOTSTRAP_ADMIN_PASSWORD
docker compose up -d --build # db -> migrate (one-shot) -> app
docker compose logs -f app   # wait for "ready"
curl http://localhost:8080/api/health/ready
```

The stack: MySQL 8 (volume `db-data`, trigger-friendly flag, healthcheck),
a one-shot `migrate` service running `com.ims.tools.Migrate`, and the app
(readiness healthchecked). The DB port is intentionally not published.

## Bare metal (systemd)

```bash
# 1. layout
sudo mkdir -p /opt/inventory-api /var/log/ims
sudo chown inventory:inventory /opt/inventory-api /var/log/ims     # user 'inventory' (uid 10001)
mvn -DskipTests package                      # or download release artifacts
sudo cp -r target/inventory-api.jar target/lib /opt/inventory-api/

# 2. config + secrets
sudo cp systemd/inventory-api.env.example /etc/inventory-api/inventory-api.env   # edit all values

# 3. migrate (DDL role) then enable
sudo systemctl daemon-reload
sudo systemctl enable --now ims
```

`TimeoutStopSec=30` matches the drain window: SIGTERM → readiness DOWN →
in-flight requests finish → exit. See `systemd/inventory-api.service`.

## Kubernetes

```bash
kubectl apply -k k8s/
```

Included: namespace, configmap, secret (example - externalize/SEAL in
prod), 2-replica deployment with liveness (`/api/health/live`) and readiness
(`/api/health/ready`) probes, `terminationGracePeriodSeconds: 30` (drain),
non-root securityContext, resource requests/limits, ClusterIP service, HPA
(70% CPU, 2-6 pods). Set the image to your ghcr.io build
(`docker-release.yml` builds and pushes on tags).

Multi-replica notes:
- All state is in MySQL - replicas are stateless and safe to scale.
- Per-process rate limiting: N replicas = N x budget (documented limitation;
  enforce precise quotas at the ingress).
- Housekeeping runs on every replica; the purge jobs are idempotent deletes,
  or disable `app.housekeeping.enabled` on all but one.

## Database provisioning

```sql
CREATE DATABASE inventory_db CHARACTER SET utf8mb4;
CREATE USER 'ims_app'@'%' IDENTIFIED BY '<strong>';
GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE ON inventory_db.* TO 'ims_app'@'%';
-- migrations run once per release with a DDL role:
CREATE USER 'ims_migrate'@'%' IDENTIFIED BY '<strong>';
GRANT ALL PRIVILEGES ON inventory_db.* TO 'ims_migrate'@'%';
```

```bash
IMS_DB_USERNAME=ims_migrate IMS_DB_PASSWORD=... \
  java -cp target/inventory-api.jar:target/lib/* com.ims.tools.Migrate
```

The migrator records SHA-256 checksums; editing an applied migration fails
fast - always add a new `Vnnn__*.sql`.

## Upgrades and rollback

1. Run migrations with the DDL role (`Migrate` is additive-only; V008 adds
   indexes + a new table - online-safe).
2. Deploy the new build (rolling replace; readiness gates traffic).
3. Rollback: redeploy the previous image. Schema rollbacks are intentionally
   not supported (forward-fix with a new migration instead).

## Backups

```bash
scripts/backup.sh    # --single-transaction dump + gzip + sha256 + retention
scripts/restore.sh   # requires explicit confirmation; refuses non-empty targets without --force
```

Schedule via cron; verify restores quarterly (see RUNBOOK).

## Load testing

```bash
k6 run loadtest/ims-smoke.js          # reads + order flow mix
BASE_URL=... ADMIN_USER=... ADMIN_PASS=... k6 run loadtest/ims-smoke.js
```
