# Production Readiness Review

This document records the production-readiness posture of inventory-api:
which controls are in place, how they are enforced, and how each one was
verified. It doubles as the acceptance record for going live.

## Review method

The entire code base (main sources, 9 migrations, all controllers, security,
DB plumbing, test infrastructure) is reviewed end-to-end; every route is
catalogued with its permission and response shape; the full test suite is
executed against a real MySQL 8 instance, and a live smoke run exercises
the complete business lifecycle on a fresh database.

## Strengths verified (not assumed)

- **Concurrency correctness at the stock core** - pessimistic row locks,
  invariants re-checked under the lock, append-only ledger enforced by DB
  triggers, and state-transition matrices tested from every state of POs,
  SOs and stocktakes.
- **Honest transaction boundaries** - connections passed explicitly; no
  hidden transaction magic anywhere.
- **Exhaustive test strategy** - business-day simulation with global
  invariant verification, concurrency proofs (no oversell under 20 threads),
  full HTTP E2E, RBAC matrix, webhook delivery against a live receiver.
- **Sane data model** - DECIMAL money, CHECK constraints, gap-tolerant
  document numbering under lock.
- **Problem+json errors** with SQLState translation (duplicates, FKs,
  CHECKs, deadlocks) into typed 4xx responses.

## Security controls

| # | Control | Enforcement |
|---|---|---|
| A1 | No secrets in the repository | Credentials only via `IMS_*` environment variables; `.gitignore` covers `config/` and `logs/` |
| A2 | Request body size limit | `http.maxRequestBodyBytes` (default 1 MB); 413 issued before buffering; remainder drained so clients get a clean response |
| A3 | Rate limiting beyond account lockout | Per-IP token buckets: general + tight login bucket; 429 + `Retry-After`; bounded memory under IP scans |
| A4 | CORS posture | Explicit allowlist `http.cors.allowedOrigins` (default disabled); proper preflight + echo on actual responses with `Vary: Origin` |
| A5 | Session hygiene | Opaque hashed tokens with instant revocation; self-service password change revokes every other session of the user |
| A6 | Attribution | `api_tokens.ip_address` / `audit_logs.ip_address` captured on login and every audit row via request context; proxy-aware (`X-Forwarded-For` behind explicit `http.trustProxyHeaders`) |
| A7 | Security headers on every response (incl. errors/204) | `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Cache-Control: no-store` |
| A8 | Outbound webhook authentication | HMAC-SHA256 signature header over the raw body; receivers verify with the shared secret; delivery id makes at-least-once retries safe |

## Correctness controls

| # | Control | Enforcement |
|---|---|---|
| B1 | Accurate pagination | Real `COUNT` queries with the same filters on every list endpoint |
| B2 | HTTP semantics | `405` + `Allow` header listing valid methods |
| B3 | Bounded background state | Housekeeping scheduler (hourly) purges expired tokens and stale idempotency records |
| B4 | Stocktake safety | Posting runs through the adjustment machinery inside one transaction; counts below the reserved quantity are rejected by the same invariants as every stock operation |
| B5 | Webhook atomicity | Transactional outbox: delivery rows commit with the business change - no lost events, no events for rolled-back changes |

## Operational capabilities

| # | Capability | Detail |
|---|---|---|
| C1 | Metrics | `/api/metrics` Prometheus text: request counters + duration histograms by route pattern, in-flight gauge, HikariCP pool gauges, JVM basics, build info, rate-limit counters |
| C2 | Probe split | `/api/health/live` + `/api/health/ready`; ready reports DOWN during drain so LBs remove the instance first |
| C3 | Graceful shutdown | `app.shutdownDrainSeconds` (default 10); readiness flips before drain; main thread parked on a latch |
| C4 | Request correlation | `X-Request-Id` generated/echoed on every response; MDC-bound for logs; embedded in problem bodies |
| C5 | Deadlock resilience | `TransactionManager` retries deadlocks (SQLState 40001) up to `db.retry.deadlockMaxRetries` with jittered backoff; nested scopes never retried |
| C6 | Access logging | INFO access log: method, path, status, duration, user, ip, requestId |
| C7 | Build introspection | `/api/meta` (public) with version + feature flags; `ims_build_info` metric |
| C8 | Webhook operations | Delivery log with attempts/status codes/errors; FAILED parking with operator retry; dispatch interval, timeout and attempt budget configurable |

## API capabilities

| # | Capability | Detail |
|---|---|---|
| D1 | Idempotent retries | `Idempotency-Key` on POST/PUT/PATCH/DELETE: stored responses keyed by (user, key) with body hash conflict detection (409), 24 h TTL, DB-backed (replica-safe), replay marker header |
| D2 | Client-side caching | Strong `ETag` on single-resource GETs; `If-None-Match` → 304 |
| D3 | Bulk data egress | CSV exports for stock levels, movements and count sheets (RFC 4180, streamed, capped 50k rows, `X-Truncated`) |
| D4 | Bulk data ingress | Product CSV import (10k row cap) with per-row error reporting and all-or-nothing transactions per valid row batch |
| D5 | Replenishment loop | Reorder proposal report + one-endpoint draft-PO-per-supplier generation |
| D6 | Batch-free query paths | Chunked `IN` batch lookups on movements/adjustments/receipts lists |

## Schema discipline

Indexes cover every reporting access path (`shipments.shipped_at`,
`shipment_lines.product_id`, `audit_logs.actor_username`); the idempotency
store is unique-keyed with a TTL index; stocktake lines are unique per
(stocktake, product); the webhook outbox is due-indexed
(`(status, next_attempt_at)`); the reorder proposal view ranks supplier
catalog entries with `ROW_NUMBER()` so the cheapest preferred supplier is
always chosen deterministically.

## Delivery

Dockerfile (multi-stage, non-root, healthchecked), docker-compose (MySQL 8 +
one-shot migrate + app), GitHub Actions CI (full `mvn verify` against a real
MySQL 8 service container + coverage artifacts), release workflow (image to
ghcr + jar artifacts), dependabot, Kubernetes manifests (probes, resources,
HPA, securityContext, graceful termination), systemd unit + env template, k6
load-test scenario, verified backup/restore scripts with retention,
CycloneDX SBOM at package time, Maven wrapper, Makefile, portable
run-server.sh (start/stop/status), and an e2e smoke script covering the
complete business lifecycle.

## Verification evidence

- `mvn verify` on MySQL 8: full suite green (unit + integration/e2e/
  concurrency/simulation).
- Coverage includes: RateLimiter, ETag matching, metrics rendering, CSV
  escaping and parsing, webhook signatures/backoff, housekeeping purge,
  idempotency replay/conflict, 413/405/429 semantics, request-id
  propagation, security headers, CSV endpoints, stocktake state matrices,
  webhook delivery to a live HTTP receiver, password rotation semantics.
- Live boot on a fresh database: full smoke run including the business
  lifecycle, ledger reconciliation, stocktaking, webhooks and dashboard.
- Graceful shutdown observed: SIGTERM → readiness DOWN → drain → clean exit.

## Remaining known limitations (documented, deliberate)

- **Rate limiting is per-process** - behind N replicas the budget is the
  sum; treat it as a safety net, enforce exact quotas at the edge.
- **Metrics counter reset on restart** - use an external Prometheus; add
  `rate()` over counters rather than absolute values.
- **CSV export cap 50k rows** - larger exports belong in a future async
  job/file delivery mechanism.
- **Idempotency TTL is 24 h** - extremely long offline retries (>24 h) will
  re-execute; acceptable by industry convention (Stripe: 24 h).
- **Single-instance housekeeping and webhook dispatch** - the schedulers are
  per-process; on multi-replica deployments the purge jobs run redundantly
  (harmless: they are idempotent deletes), and webhook deliveries are
  claimed per row without SKIP LOCKED, so at-least-once (not exactly-once)
  semantics apply across replicas - receivers must treat the delivery id as
  the dedupe key. Both can be disabled via config on all but one replica.
- **Webhook URL reachability is the operator's responsibility** - endpoints
  are validated for scheme/host but not restricted to public ranges;
  webhook management requires ADMIN/MANAGER, and the signing secret lets
  receivers reject forged calls.
- **Opaque tokens, no refresh flow** - a deliberate trade-off for instant
  revocation; long sessions re-login when the 4 h TTL lapses.
