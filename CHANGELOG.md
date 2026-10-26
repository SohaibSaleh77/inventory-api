# Changelog

## 1 - 2026-10-27

Initial release of inventory-api: a production-grade inventory management
backend (Java 21, zero-framework, MySQL 8).

### Core business
- Procure-to-pay: PO draft→submit→approve→receive→close with partial
  receipts, rejections and GRN documents
- Order-to-cash: SO draft→confirm (stock reservations)→partial/full
  fulfilment→close, backorders, customer returns with restock
- Multi-warehouse stock with pessimistic row locks and optimistic
  concurrency (version columns) on documents
- Two-phase inter-warehouse transfers; reason-coded stock adjustments
- Append-only movement ledger (DB triggers reject UPDATE/DELETE) with
  balance-after on every row; corrections are compensating movements
- Stocktaking (cycle counting): frozen count sheets, blind counting mode,
  recounts, variance analysis, posting through adjustment documents with
  last_counted_at bookkeeping, CSV count-sheet export
- Reorder automation: proposal report (per-product suggested quantities,
  cheapest preferred supplier) and one-endpoint generation of draft POs
  per supplier
- Dashboard KPI endpoint: master-data counts, stock value, low/out-of-stock
  lines, open documents, in-transit transfers, active stocktakes, recent
  ledger movements
- Master data: products (SKUs, barcodes, categories tree, units, costs,
  reorder points) with bulk CSV import, warehouses, supplier catalogs,
  customers with credit limits

### Integrations
- Outbound webhooks with a transactional outbox: delivery rows commit with
  the business change, a background dispatcher delivers over HTTP with
  HMAC-SHA256 signatures (X-IMS-Signature), event and delivery-id headers,
  exponential backoff (1m doubling, 6h cap), attempt budget, FAILED parking
  with operator retry
- Events: STOCK_LOW (downward reorder-point crossing, including
  reservations), PURCHASE_ORDER_APPROVED, SALES_ORDER_CONFIRMED,
  SALES_ORDER_SHIPPED, STOCKTAKE_POSTED, TEST
- Scanner-style product lookup by barcode (GET /api/products/barcode/{code})

### Security
- PBKDF2 password hashing with policy enforcement
- Self-service password change: current-password proof, revocation of every
  other session, audited
- Opaque hashed bearer tokens with revocation; brute-force lockout;
  per-IP rate limiting (general + tight login buckets) with 429 +
  Retry-After
- Request body size cap (default 1 MB, 413); security headers on every
  response; CORS via explicit origin allowlist
- RBAC: ADMIN / MANAGER / STAFF / VIEWER permission matrix
- Full audit trail: actor, before/after JSON, document references, client IP

### HTTP semantics
- X-Request-Id generated/echoed on every response, bound to logs (MDC) and
  error bodies
- Idempotency-Key for POST/PUT/PATCH/DELETE with stored-response replay,
  body-hash conflict detection (409), 24 h TTL
- ETag + If-None-Match (304) on single-resource GETs; 405 with Allow
- RFC 4180 CSV exports (stock levels, movements, count sheets) streamed
  with a 50k row cap and X-Truncated
- Graceful shutdown: readiness flips DOWN, in-flight requests drain, then
  the pool closes

### Observability & ops
- GET /api/metrics: Prometheus text (request counters + duration histograms
  by route pattern, in-flight, pool + JVM gauges, build info, rate-limit
  counters)
- /api/health split into /health/live and /health/ready; GET /api/meta
  build + feature flags
- Housekeeping scheduler purges expired tokens and stale idempotency records
- Deadlock (40001) auto-retry at the transaction boundary with backoff
- Docker (multi-stage, non-root, healthcheck) + compose (MySQL 8 +
  one-shot migrate + app); GitHub Actions CI and release workflows;
  Kubernetes manifests (kustomize) with probes, HPA, securityContext;
  systemd unit; run-server.sh; k6 load test; backup/restore scripts;
  CycloneDX SBOM; Makefile

### Tests
- 260 total: 105 unit + 155 integration/e2e, including state-transition
  matrices for POs, SOs and stocktakes, concurrency races (no oversell,
  unique document numbers, exactly-one transfer winner), webhook delivery
  against a real HTTP receiver with verified signatures, password rotation
  semantics, CSV import error reporting, and a full business-day simulation
  with global invariant verification
