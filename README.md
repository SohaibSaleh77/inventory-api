# inventory-api

A production-grade inventory management backend built with **Java 21** and
**MySQL 8**: procure-to-pay and order-to-cash with multi-warehouse stock,
reservations, an append-only movement ledger, cycle counting, outbound
webhooks, full audit trail, RBAC, and a JSON REST API.

By [Sohaib Abdo Ahmed Saleh](https://github.com/SohaibSaleh77) —
<https://github.com/SohaibSaleh77/inventory-api>

- **Zero framework runtime**: JDK built-in HTTP server (virtual threads),
  plain JDBC with HikariCP, Jackson for JSON. No Spring, no reflection magic,
  no hidden transaction boundaries - every layer is explicit and reviewable.
- **Correctness first**: pessimistic row locks for stock operations,
  optimistic concurrency (version columns) on documents, DB-level CHECK
  constraints and append-only triggers, ACID document lifecycles.

## Feature Overview

| Module | Capabilities |
|---|---|
| Products | SKUs, barcodes (with scanner-style lookup), categories (tree), units, costs, reorder points, bulk CSV import |
| Warehouses | Multi-warehouse stock, activate/deactivate, delete protection |
| Suppliers | Vendor master data + supplier catalog (per-vendor SKU, cost, lead time) |
| Customers | Retail/wholesale/distributor types, credit limits |
| Purchasing | PO draft→submit→approve→receive→close, partial receipts, rejections, GRN documents, reorder proposals turned into draft POs per supplier |
| Sales | SO draft→confirm (reserves stock)→partial/full fulfilment→close, backorders |
| Returns | Customer returns with restock flag and credit computation |
| Inventory ops | Stock adjustments (damage/loss/found/QC...), two-phase inter-warehouse transfers |
| Stocktaking | Cycle counts: frozen count sheets, blind counting, recounts, variance analysis, posting through adjustment documents, CSV count-sheet export |
| Ledger | Append-only stock_movements with balance-after, movement types, document references |
| Webhooks | Outbound event notifications (stock-low, PO approved, SO confirmed/shipped, stocktake posted), HMAC-SHA256 signed deliveries, transactional outbox, retry with backoff, delivery log, operator retries |
| Reporting | Dashboard KPI snapshot, stock levels, valuation, low stock, reorder proposals, sales by customer, purchases by supplier, movement summary, gross margin (COGS at ship time) |
| Security | PBKDF2 password hashing, self-service password rotation (revokes other sessions), opaque hashed bearer tokens with revocation, brute-force lockout + per-IP rate limiting, body-size caps, security headers, configurable CORS, RBAC (ADMIN/MANAGER/STAFF/VIEWER) |
| Audit | Every mutation recorded with actor, before/after JSON, document references, client IP |
| Ops | Prometheus metrics, liveness/readiness split, X-Request-Id correlation, graceful drain, housekeeping scheduler, Docker/compose/k8s/systemd delivery |

## Quick Start

Fastest path (Docker):

```bash
cp .env.example .env        # set IMS_BOOTSTRAP_ADMIN_PASSWORD
docker compose up -d --build
curl http://localhost:8080/api/health/ready
```

Classic (jar):

```bash
# 1. Configure the database connection (env or ./config/application.properties)
export IMS_DB_URL='jdbc:mysql://127.0.0.1:3306/inventory_db?useSSL=false&allowPublicKeyRetrieval=true'
export IMS_DB_USERNAME=...    # DML role, e.g. ims_app
export IMS_DB_PASSWORD=...

# 2. Apply schema migrations with a DDL-privileged role (once per release)
IMS_MIGRATE_USERNAME=root IMS_MIGRATE_PASSWORD=... \
  java -cp target/inventory-api.jar:target/lib/* com.ims.tools.Migrate

# 3. Start the API
./bin/run-server.sh            # foreground; add --daemon for background

# 4. First boot creates user 'admin' with a generated password in
#    logs/bootstrap-admin.txt (control it with IMS_BOOTSTRAP_ADMIN_PASSWORD)
```

Login and explore:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<from logs/bootstrap-admin.txt>"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

curl -s localhost:8080/api/health
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/dashboard
```

A complete operational smoke test (login, master data, procure-to-pay,
order-to-cash, stocktake, webhooks, dashboard) lives in
`scripts/e2e-smoke.sh`.

## Building and Testing

```bash
./mvnw verify      # unit + integration/e2e tests (needs a test DB)
```

Integration tests need a scratch database:

```bash
export IMS_TEST_DB_PASSWORD=...   # password of a DDL-capable test user
mvn verify -Ddb.url='jdbc:mysql://127.0.0.1:3306/inventory_db_test?useSSL=false&allowPublicKeyRetrieval=true' \
           -Ddb.username=ims_test
```

The test suite covers:

- **Unit**: money/quantity math, validation, password hashing + policy, RBAC
  matrix, routing, SQL exception translation, migration statement splitting,
  rate limiter, ETag matching, metrics rendering, CSV escaping and parsing,
  webhook signatures, event sets and backoff schedule.
- **Service integration** (real MySQL): auth lockout, stock invariants,
  PO/SO lifecycles, receiving, returns, transfers, adjustments.
- **State-transition matrices**: every action attempted from every state of
  purchase orders, sales orders - and stocktakes; the legal set is asserted
  exactly.
- **Concurrency**: 20 threads competing for 100 units (no oversell, exact
  success count), concurrent document numbering (no duplicates), transfer
  dispatch races (exactly one winner).
- **HTTP end-to-end**: the full business lifecycle plus RBAC 401/403 matrix
  against a real running server, and production-hardening suites: rate
  limiting 429s, 413 body caps, 405+Allow, ETag 304s, Idempotency-Key
  replay/conflict, CSV exports, metrics format, request-id propagation.
- **Stocktaking**: count-sheet freezing, blind mode, recounting, variance
  posting through adjustment documents, reservation guard rails, CSV export.
- **Webhooks**: outbox rows written with the business transaction, real
  HTTP delivery with verified HMAC signatures to a live receiver, retry
  and backoff behavior, operator retry, STOCK_LOW crossing semantics.
- **Business-day simulation**: a full trading day (receipts with rejections,
  transfers, partial shipments, returns, damage adjustments, concurrent
  orders, reorder proposals) followed by global invariant verification
  (ledger reconciliation, reservation accounting, no negative stock).

## Project Layout

```
src/main/java/com/ims/
├── Main.java                  # entry point, bootstrap admin
├── core/                      # AppConfig, ApplicationContext (explicit wiring)
├── db/                        # pool, transactions, migrator, doc numbers
├── common/                    # utils + typed exceptions
├── security/                  # RBAC, password hashing, tokens
├── domain/
│   ├── user/ auth/ audit/     # identity & audit
│   ├── category/ supplier/ customer/ warehouse/ product/   # master data
│   ├── stock/                 # stock items, ledger, adjustments
│   ├── stocktake/             # cycle counting & variance posting
│   ├── transfer/              # two-phase warehouse transfers
│   ├── purchase/              # POs, lines, goods receipts, replenishment
│   ├── sales/                 # SOs, reservations, shipments, returns
│   ├── report/                # SQL-computed reports + dashboard
│   └── webhook/               # outbound notifications, outbox, dispatcher
└── http/                      # router, server, controllers, error format
src/main/resources/db/migration/   # V001..V009 versioned schema (Flyway-style)
src/test/java/com/ims/          # unit + integration + e2e + concurrency + simulation
```

## Documentation

- `docs/API.md` - endpoint reference
- `docs/RUNBOOK.md` - operations guide (provisioning, backups, users, health, stocktakes, webhooks)
- `docs/ARCHITECTURE.md` - design decisions and data flow
- `docs/TESTING.md` - what the test suite guarantees
- `docs/DEPLOYMENT.md` - deployment options + full config reference
- `docs/SECURITY.md` - security model
- `docs/PRODUCTION_READINESS.md` - the production readiness review
- `CHANGELOG.md` - release history

## Configuration

All settings live in `application.properties` and can be overridden by
`IMS_*` environment variables or system properties (see AppConfig).
Key settings: `http.host/port`, `db.*` (JDBC + pool), `security.*`
(token TTL, PBKDF2 iterations, lockout policy), `app.webhooks.*`
(dispatch interval, timeouts, retry budget), `app.housekeeping.*`,
`app.migrateOnStartup`.

## Production Notes

- Run migrations with a dedicated DDL role; the app role only needs
  SELECT/INSERT/UPDATE/DELETE/EXECUTE.
- MySQL requires `log_bin_trust_function_creators=1` (or a SUPER migration
  role) because the schema creates triggers for ledger immutability.
- All times are stored as UTC; the API speaks ISO-8601.
- The stock ledger (`stock_movements`) rejects UPDATE/DELETE at the database
  level - corrections are new compensating movements only.
