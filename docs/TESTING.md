# Testing Guide

## Layout

| Package | Scope | Needs DB |
|---|---|---|
| `com.ims.unit` | pure logic (money, quantities, validators, hashing, RBAC, router, SQL-error translation, migration splitter, JSON) | no |
| `com.ims.integration` | service-level business rules on a live MySQL | yes |
| `com.ims.concurrency` | oversell prevention, doc numbering, transfer races | yes |
| `com.ims.e2e` | full lifecycle + RBAC matrix through a real HTTP server | yes |
| `com.ims.simulation` | business-day simulation + global invariants | yes |

Run:

```bash
mvn test                          # unit only
export IMS_TEST_DB_PASSWORD=...   # for the DDL-capable test user
mvn verify                        # everything (surefire + failsafe)
```

## Isolation

`IntegrationTestEnv` is a per-JVM singleton: it migrates the test schema
once, builds the full `ApplicationContext` against the test database, and
offers `truncateAll()` / `resetDocuments()`. Each IT class truncates in
`@BeforeAll`; stateful classes reset per test method.

## What each suite guarantees

- **AuthServiceIT**: lockout after 5 failures persists (failed attempts are
  committed in their own transaction), disabled accounts cannot log in,
  logout kills the token immediately.
- **StockServiceIT**: inbound/outbound math, moving average, reserved vs
  available pools, negative-stock rejection, ledger reconciliation.
- **Purchase/SalesOrderServiceIT**: totals computed server-side (discount +
  tax), atomic all-or-nothing confirmation, partial receipts/shipments,
  over-receive/over-ship rejection, cancel semantics, returns bounded by
  shipped quantities.
- **State matrices**: for every (status, action) pair the outcome is exactly
  as specified - illegal actions leave state untouched.
- **ReservationConcurrencyIT**: 20 threads × 10 units against 100 on hand →
  exactly 10 confirmations succeed, reserved == 100, no violations. Document
  numbering under 12 concurrent creators yields 96 unique numbers. Two
  dispatches racing for one stock pool → exactly one wins.
- **FullLifecycleHttpIT / RbacHttpIT**: the whole business flow over real
  HTTP; every role × representative endpoint yields 401/403 exactly where
  the RBAC matrix says.
- **BusinessDaySimulationIT**: a realistic trading day (see the source for
  the script) followed by `InvariantVerifier`:

```
qty_on_hand == SUM(movements)              per (product, warehouse)
qty_reserved == SUM(active reservations)   per (product, warehouse)
0 <= qty_reserved <= qty_on_hand           everywhere
every movement references its document
SO lines: fulfilled + reserved <= ordered; PO lines: received <= ordered
```

## Reconciliation query (production-usable)

```sql
SELECT si.product_id, si.warehouse_id, si.qty_on_hand,
       COALESCE(SUM(m.quantity), 0) AS ledger
  FROM stock_items si
  LEFT JOIN stock_movements m
         ON m.product_id = si.product_id AND m.warehouse_id = si.warehouse_id
 GROUP BY si.id, si.product_id, si.warehouse_id, si.qty_on_hand
HAVING si.qty_on_hand <> ledger;
```

Empty result = ledger reconciles. (The same check runs via the API in
`scripts/e2e-smoke.sh` step 14.)

## Live acceptance

`scripts/e2e-smoke.sh` runs a check-by-check business lifecycle against a running
server and verifies the ledger from the outside, as an API client. It
expects a fresh database (or unique codes).

## Platform suites

- **Unit**: RateLimiterTest (refill/burst/isolation/eviction/toggle),
  EtagSupportTest (RFC 9110 If-None-Match), MetricsTest (Prometheus
  format, monotone buckets), CsvParseTest + CsvTest (RFC 4180 parsing and
  quoting + formula-injection containment), WebhookTest (HMAC signatures,
  event sets, backoff schedule).
- **IT/E2E**: HttpHardeningIT (security headers, request ids, 405+Allow,
  413, ETag 304, meta, health split, metrics), IdempotencyHttpIT (replay,
  conflict, isolation), RateLimitHttpIT (parallel burst -> 429 +
  Retry-After), CsvHttpIT (content types, quoting, auth), HousekeepingIT
  (purge correctness), DatabaseMigratorIT.
- **Live smoke** (`scripts/e2e-smoke.sh`): the full business lifecycle
  plus every hardening feature against a running server.

## Stocktaking, webhooks and replenishment suites

- **StocktakeServiceIT**: count-sheet freezing (every active product, book
  quantity snapshot), blind mode hiding expected quantities until close,
  recount counting, variance posting through adjustment documents +
  ledger movements + last_counted_at stamps, reservation guard rails
  (a count under the reserved quantity rejects the posting atomically),
  and the complete state-transition matrix (every action from every state).
- **WebhookServiceIT**: a real local HTTP receiver captures deliveries;
  assertions cover HMAC signature correctness over the exact body, event
  and delivery-id headers, outbox rows written inside the business
  transaction, retry/backoff behavior with a bounded attempt budget,
  operator retry semantics, inactive/deleted webhook handling, and
  STOCK_LOW firing exactly once on a downward reorder-point crossing
  (reservations included).
- **ReorderServiceIT**: proposal rows (suggested quantity = reorder_qty
  when set, else the deficit; cheapest preferred supplier), draft-PO
  generation per supplier with correct line costs, single-supplier mode,
  and skip-counting for products without a supplier catalog entry.
- **HTTP E2E** (StocktakeHttpIT, WebhooksHttpIT, FeaturesHttpIT): the
  complete stocktake lifecycle over HTTP incl. CSV export and 405+Allow;
  webhook CRUD, ETag 304 on detail, test events and the delivery log;
  barcode lookup, CSV import error reporting, self-service password
  rotation (other sessions revoked, audit recorded) and the
  reorder-to-PO shortcut. RbacHttpIT asserts the 401/403 matrix for every
  new endpoint family.
