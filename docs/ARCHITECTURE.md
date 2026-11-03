# Architecture

## Design goals

1. **Correctness under concurrency** - stock operations must never oversell,
   never go negative, and always leave a reconcilable ledger.
2. **Operability** - every mutation audited, uniform errors, health probes,
   versioned schema, least-privilege DB roles.
3. **Reviewability** - no framework magic: the object graph is wired in one
   explicit place; transaction boundaries are visible at call sites; SQL is
   hand-written and indexed deliberately.

## Layering

```
HTTP (HttpApiServer, Router, Controllers)
  │  parse + auth (AuthContext) + permission check
  ▼
Services (business rules, transactions, audit)
  │  inTransaction(conn -> ...)   ← explicit connection passing
  ▼
DAOs (hand-written JDBC, prepared statements)
  │
  ▼
MySQL 8 (InnoDB, FKs, CHECKs, triggers, views)
```

- `ApplicationContext` constructs every component once (constructor wiring).
- `TransactionManager` binds the connection to the current thread
  (REQUIRED semantics). DAO methods take the `Connection` explicitly, so a
  multi-statement operation outside a transaction is impossible to write by
  accident.
- Controllers are thin: parse → `actor.require(permission)` → service → JSON.

## Why these choices

| Choice | Rationale |
|---|---|
| JDK HttpServer + virtual threads | no dependency surface, scales for blocking I/O, trivially embedded in tests |
| Plain JDBC + HikariCP | full control of locking (`SELECT ... FOR UPDATE`) and batch sizes; no N+1 surprises |
| Opaque hashed tokens (not JWT) | instant revocation (logout disables the row), no key distribution, auditable |
| PBKDF2 (not bcrypt) | NIST-aligned, in-JDK, constant-time comparison |
| Server-side totals | clients never dictate prices/totals |
| Moving average cost | simple, auditable valuation; COGS snapshot at ship time in `shipment_lines` |
| Optimistic version columns | documents (PO/SO) conflict softly; stock rows use pessimistic locks (hot rows) |
| Append-only ledger + DB triggers | stock history cannot be silently rewritten, even by SQL-savvy insiders |

## Stock operation core

All quantity changes flow through `StockService.applyStockChange`:

1. `stock_items` row is fetched (created if missing) with `FOR UPDATE`.
2. Invariants re-checked under the lock (available ≥ out; reserved ≥ 0;
   reserved ≤ on-hand).
3. Inbound at cost → moving average recomputed.
4. Row updated and one `stock_movements` row appended with
   `balance_after`, document reference (`ref_type/ref_id/ref_no`), actor.
5. Caller's transaction commits both together - or neither.

Reservations: `sales_orders.confirm` reserves per line (row-locked);
fulfilment consumes from the reserved pool; cancel releases.

## Document lifecycles

- PO: DRAFT → SUBMITTED → APPROVED → (PARTIALLY_)RECEIVED → CLOSED (+CANCELLED).
- SO: DRAFT → CONFIRMED → (PARTIALLY_)FULFILLED → CLOSED (+CANCELLED).
- Transfer: PENDING → DISPATCHED → RECEIVED (cancel only while PENDING).
- Stocktake: DRAFT → OPEN → REVIEW → POSTED (+CANCELLED from any
  pre-POSTED state).

Transitions are guarded in service code (typed 409s) and mirrored by
conditional UPDATEs (`WHERE status = ?`), so even a race cannot corrupt state.
The state-matrix integration tests attempt every action from every state.

## Stocktaking (cycle counting)

A stocktake freezes the book quantities of one warehouse into a count sheet
(`stocktake_lines.expected_qty`), collects blind counts, and posts variances
through the adjustment machinery - each corrected line becomes one
`stock_adjustments` document plus one ledger movement (reason
COUNT_CORRECTION, reference STOCKTAKE), inside a single transaction that
also stamps `stock_items.last_counted_at` and transitions the header to
POSTED. Uncounted lines are deliberately skipped: cycle counts sample the
warehouse. A count that would take on-hand below the reserved quantity is
rejected by the same invariants that guard every other stock operation.

## Outbound webhooks (transactional outbox)

`WebhookService.enqueueEvent` appends `webhook_deliveries` rows inside the
caller's business transaction, so an outbox row exists if and only if the
change committed - there is no window where stock moved but the event is
lost, and no window where an event exists for a rolled-back change. The
`WebhookDispatcher` (single daemon thread) drains due rows over HTTP with
HMAC-SHA256 signatures, per-delivery transactions, exponential backoff and
a terminal FAILED state with operator retry. `StockService` is the single
choke point for STOCK_LOW evaluation: it hands pre/post available quantities
to the webhook service, which fires only on a downward crossing of the
reorder point (reservations included) to avoid event storms.

## Schema highlights (9 migrations, 29 tables + 4 views + 2 triggers)

- Money `DECIMAL(14,2)`, quantities `DECIMAL(12,3)`, costs `DECIMAL(14,4)`.
- `CHECK (qty_on_hand >= 0)`, `CHECK (qty_reserved <= qty_on_hand)`.
- `stock_movements` UPDATE/DELETE rejected by trigger (append-only).
- Views `v_stock_levels`, `v_product_stock_summary`, `v_low_stock`,
  `v_reorder_proposals` power reporting; heavy aggregation stays in SQL.
- `doc_sequences` with `SELECT ... FOR UPDATE` hands out gap-tolerant,
  collision-free document numbers under concurrency.

## Error model

`ApiException` subclasses map to HTTP codes (401/403/404/409/422/500) and
render as problem+json with optional `fieldErrors`. `SQLExceptionMapper`
translates SQLState (duplicate key, FK, CHECK, deadlock, custom 45000) into
the same typed exceptions, so DB-level protections surface as clean 409s.

## Testing strategy

See docs/TESTING.md. The short version: unit tests for pure logic; ITs on a
real MySQL for services; exhaustive state matrices; concurrency proofs; full
HTTP E2E; a business-day simulation ending in global invariant verification.
