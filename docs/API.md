# API Reference

Base URL: `http://<host>:8080/api`. All responses are JSON.
Authentication: `Authorization: Bearer <token>` from `POST /api/auth/login`.
Errors use a problem+json shape: `{type, title, status, detail, requestId, fieldErrors?}`.

## Cross-cutting headers and semantics

- **X-Request-Id**: every response carries one (echoed from the request
  when supplied); it is also embedded in error bodies and access logs.
- **Security headers**: `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
  `Cache-Control: no-store` on every response.
- **Idempotency-Key** (POST/PUT/PATCH/DELETE): send a unique client key;
  safe retries replay the stored response (marked
  `Idempotency-Replayed: true`) instead of re-executing. Same key with a
  different body returns 409.
- **ETag / If-None-Match**: single-resource GETs return a strong ETag;
  `If-None-Match` short-circuits to `304 Not Modified`.
- **Rate limiting** (per client IP): 429 with `Retry-After` when exceeded;
  login has a much tighter budget.
- **Body cap**: requests larger than 1 MB (configurable) fail with `413`.
- **405** now returns `Allow` listing the valid methods for the path.
- **CORS**: disabled unless `http.cors.allowedOrigins` lists origins.

## Authentication

| Method | Path | Body | Notes |
|---|---|---|---|
| POST | /auth/login | `{username, password}` | Returns `{token, expiresAt, userId, username, role}`. 5 failures lock the account for 15 min. |
| POST | /auth/logout | - | Revokes the presented token. |
| POST | /auth/change-password | `{currentPassword, newPassword}` | Self-service rotation: verifies the current password, applies the policy-checked new one, and revokes every OTHER session of the user (the calling token stays valid). 204 on success. |
| GET | /auth/me | - | Current principal + permission list. |

## Users (ADMIN)

| Method | Path | Notes |
|---|---|---|
| GET | /users?search&role&status&page&size | Paged search |
| POST | /users | `{username, email, password, fullName, role}` |
| GET | /users/{id} | |
| PUT | /users/{id} | `{email, fullName, role}` |
| POST | /users/{id}/reset-password | `{newPassword}` |
| POST | /users/{id}/activate -or- /deactivate | Last active ADMIN cannot be demoted/disabled |

## Categories

| Method | Path | Notes |
|---|---|---|
| GET | /categories?includeInactive | Flat list |
| GET | /categories/tree?includeInactive | Nested tree |
| POST | /categories | `{code, name, description, parentCode?}` - cycles rejected |
| GET/PUT/DELETE | /categories/{id} | Delete rejected when products/children exist |

## Warehouses

| Method | Path | Notes |
|---|---|---|
| GET | /warehouses?includeInactive | |
| POST | /warehouses | `{code, name, address...}` |
| GET/PUT/DELETE | /warehouses/{id} | Delete rejected when referenced |

## Suppliers

| Method | Path | Notes |
|---|---|---|
| GET | /suppliers?search&active&page&size | Paged |
| POST | /suppliers | `{code, name, contactName, email, phone, address..., paymentTermsDays, rating, notes}` |
| GET | /suppliers/{id} / /suppliers/{id}/detail | Detail includes catalog items |
| PUT | /suppliers/{id} | |
| DELETE | /suppliers/{id} | Rejected with purchase history |
| PUT | /suppliers/{id}/items | `{productSku, supplierSku, unitCost, preferred, leadTimeDays}` (upsert) |
| DELETE | /suppliers/{id}/items/{productId} | |

## Customers

CRUD analogous to suppliers; type RETAIL/WHOLESALE/DISTRIBUTOR, creditLimit.

## Products

| Method | Path | Notes |
|---|---|---|
| GET | /products?search&categoryCode&active&sortBy&page&size | |
| POST | /products | `{sku, barcode?, name, description?, categoryCode?, unitOfMeasure?, standardCost?, listPrice?, reorderPoint?, reorderQty?}` - optional numerics default to 0 |
| GET | /products/sku/{sku} -or- /products/{id} | |
| GET | /products/barcode/{barcode} | Scanner-style lookup by barcode |
| GET | /products/{id}/stock | Stock rows across warehouses |
| PUT | /products/{id} | Partial update |
| DELETE | /products/{id} | Rejected when transaction history exists |
| POST | /products/import | Body is raw CSV text (see below) |

## Product CSV import

`POST /api/products/import` takes a raw `text/csv` body (UTF-8, RFC 4180
quoting). The header row is required and must contain at least `sku` and
`name`; optional columns: `barcode, description, categoryCode,
unitOfMeasure, standardCost, listPrice, reorderPoint, reorderQty` (order
does not matter, extra columns are ignored). Valid rows are inserted in one
transaction; invalid rows are skipped and reported. Response:

```json
{"created": 2, "skipped": 3,
 "createdSkus": ["A", "B"],
 "errors": [{"line": 4, "sku": "", "message": "sku must not be blank"}]}
```

Duplicates (in the file or the database), unknown category codes and
malformed numbers become per-row errors, not failures of the whole import.
Hard cap: 10,000 data rows per call.

## Stock

| Method | Path | Notes |
|---|---|---|
| GET | /stock/levels?sku&warehouseId&lowOnly&page&size | On-hand / reserved / available / value |
| GET | /stock/movements?productId&warehouseId&type&refType&refId&from&to | Ledger query |
| GET | /stock/adjustments?productId&warehouseId&reason | |
| POST | /stock/adjustments | `{productSku, warehouseCode, quantityDelta, reason, note?}` - reason must be one of DAMAGE/LOSS/THEFT/EXPIRY/COUNT_CORRECTION/QUALITY_HOLD/FOUND/RETURN_TO_VENDOR/OTHER; MANAGER+ only |

## Transfers (two-phase)

| Method | Path | Notes |
|---|---|---|
| GET | /transfers?status&productId | |
| POST | /transfers | `{productSku, fromWarehouseCode, toWarehouseCode, quantity, note?}` |
| POST | /transfers/{id}/dispatch | Deducts source (available stock only) |
| POST | /transfers/{id}/receive | Adds to destination, carrying average cost |
| POST | /transfers/{id}/cancel | Only while PENDING |

## Purchase Orders

Lifecycle: DRAFT → SUBMITTED → APPROVED → PARTIALLY_RECEIVED → RECEIVED →
CLOSED, with CANCELLED reachable before any receipt.

| Method | Path | Notes |
|---|---|---|
| GET | /purchase-orders?status&supplierId&warehouseId&dateFrom&dateTo | |
| POST | /purchase-orders | `{supplierCode, warehouseCode, orderDate?, expectedDate?, currency?, notes?, lines:[{productSku, qty, unitCost, taxPct?}]}` - totals computed server-side |
| POST | /purchase-orders/from-reorder | `{warehouseCode, supplierCode?}` - creates one DRAFT PO per supplier from the current reorder proposals (all suppliers when supplierCode is omitted); response `{purchaseOrders, totalLines, skippedLines}` |
| GET/PUT | /purchase-orders/{id} | PUT only in DRAFT |
| POST | /purchase-orders/{id}/submit -or- /approve | Approve needs PURCHASE_ORDER_APPROVE (MANAGER+) |
| POST | /purchase-orders/{id}/close | From RECEIVED |
| DELETE | /purchase-orders/{id} | Cancel (before receipts) |
| POST | /purchase-orders/{id}/receipts | `{lines:[{poLineId, qtyAccepted, qtyRejected?}], note?}` - accepts ≤ outstanding per line; updates stock + moving average |
| GET | /purchase-orders/{id}/receipts | GRN history |

## Sales Orders

Lifecycle: DRAFT → CONFIRMED (stock reserved atomically) →
PARTIALLY_FULFILLED → FULFILLED → CLOSED; CANCELLED before any shipment
(releases reservations).

| Method | Path | Notes |
|---|---|---|
| GET | /sales-orders?status&customerId&warehouseId&dateFrom&dateTo | |
| POST | /sales-orders | `{customerCode, warehouseCode, requestedDate?, notes?, lines:[{productSku, qty, unitPrice? (defaults to list price), discountPct?, taxPct?}]}` |
| GET/PUT | /sales-orders/{id} | PUT only in DRAFT |
| POST | /sales-orders/{id}/confirm | All-or-nothing reservation |
| POST | /sales-orders/{id}/close | From FULFILLED |
| DELETE | /sales-orders/{id} | Cancel + release |
| POST | /sales-orders/{id}/shipments | `{lines:[{soLineId, qty}], carrier?, trackingNo?, note?}` - qty ≤ reserved; records COGS unit cost |
| GET | /sales-orders/{id}/shipments | |
| POST | /sales-orders/{id}/returns | `{lines:[{soLineId, qty, restock}], reason, note?}` - reason ∈ DEFECTIVE/WRONG_ITEM/CUSTOMER_REMORSE/DAMAGED_IN_TRANSIT/OTHER; cumulative returns ≤ shipped |
| GET | /sales-orders/{id}/returns | |

## Reports (all roles with REPORT_READ)

| Path | Content |
|---|---|
| /reports/stock-summary | Per product: totals, reorder flags |
| /reports/stock-valuation | Stock value per warehouse + grand total |
| /reports/low-stock | Products at/below reorder point with shortfall |
| /reports/reorder-proposals | Products at/below reorder point with suggested quantity and cheapest preferred supplier; `{rows, skippedNoSupplier, skippedNoQty}` |
| /dashboard | One-call KPI snapshot: master-data counts, stock value, low/out-of-stock lines, in-transit transfers, open documents, active stocktakes, pending webhook deliveries, last 10 ledger movements |
| /reports/sales-by-customer?dateFrom&dateTo | Revenue by customer |
| /reports/purchases-by-supplier?dateFrom&dateTo | Spend by supplier |
| /reports/movement-summary?dateFrom&dateTo | Count + net quantity per movement type |
| /reports/gross-margin?dateFrom&dateTo | Revenue, COGS (at-ship cost), margin per product |

## Stocktakes (cycle counting)

Lifecycle: DRAFT → OPEN (counting) → REVIEW (counts frozen) → POSTED
(variances applied); CANCELLED from any pre-POSTED state. Reads need
STOCK_READ, lifecycle transitions and counting need STOCK_WRITE (STAFF+),
posting needs STOCK_ADJUST (MANAGER+) because it creates stock corrections.

| Method | Path | Notes |
|---|---|---|
| GET | /stocktakes?warehouseId&status&page&size | List with line counters |
| POST | /stocktakes | `{warehouseCode, name, blind?, note?}` - freezes a count sheet: every active product with its current book quantity (0 when never stocked in that warehouse) |
| GET | /stocktakes/{id} | Detail incl. lines; while OPEN on a blind stocktake the expected quantities and variances are hidden from counters |
| POST | /stocktakes/{id}/open | Begin counting |
| POST | /stocktakes/{id}/counts | `{productSku, countedQty}` - upsert a count; a second count of the same line is a recount (recounts counter++) |
| POST | /stocktakes/{id}/close | Freeze counts for variance review |
| POST | /stocktakes/{id}/post | Apply non-zero variances: one adjustment document + one ledger movement per corrected line (reason COUNT_CORRECTION, ref STOCKTAKE); stamps stock_items.last_counted_at for counted lines; uncounted lines are left untouched; rejects when a count would take on-hand below the reserved quantity |
| POST | /stocktakes/{id}/cancel | Abandon (no stock impact) |
| GET | /stocktakes/{id}/export | Count sheet as text/csv (`sku,productName,expectedQty,countedQty,variance,recounts,countedAt`) |

## Webhooks (ADMIN/MANAGER)

Outbound event notifications. Deliveries are appended to an outbox inside
the same transaction as the business change, then POSTed by a background
dispatcher with `X-IMS-Signature: sha256=<hmac>` over the raw body,
`X-IMS-Event` and `X-IMS-Delivery-Id` headers. Receivers should verify the
HMAC with their shared secret and treat the delivery id as an
at-least-once handle. Failed deliveries retry with exponential backoff
(1m doubling, 6h cap, 6 attempts) before parking as FAILED.

Events: `STOCK_LOW` (available crossed at/below the reorder point
downward, reservations included), `PURCHASE_ORDER_APPROVED`,
`SALES_ORDER_CONFIRMED`, `SALES_ORDER_SHIPPED`, `STOCKTAKE_POSTED`, `TEST`.

Payload envelope: `{"eventType", "occurredAt", "data": {...}}`.

| Method | Path | Notes |
|---|---|---|
| GET | /webhooks | All registrations |
| POST | /webhooks | `{name, url, secret (>= 16 chars), events:[...]}` |
| GET/PUT | /webhooks/{id} | PUT: `{name, url, secret, events, isActive}` (pause/reactivate) |
| DELETE | /webhooks/{id} | Delivery history is removed with it |
| POST | /webhooks/{id}/test | Enqueues a TEST event (delivered on the next dispatch tick) |
| GET | /webhooks/{id}/deliveries?status&page&size | Delivery log with attempts, status codes, errors |
| POST | /webhooks/{id}/deliveries/{deliveryId}/retry | Re-queue a FAILED delivery from now |

## Audit (ADMIN)

| Method | Path | Notes |
|---|---|---|
| GET | /audit/logs?action&entityType&entityId&actor&from&to&page&size | Immutable trail of every mutation |

## Health (public)

`GET /health` → `{status: UP, database: UP, pendingMigrations: 0}` (503 when degraded).


## Platform endpoints (public)

| Method | Path | Notes |
|---|---|---|
| GET | /health | Combined status (compat) |
| GET | /health/live | Liveness - process up (for restart probes) |
| GET | /health/ready | Readiness - DB reachable, schema current, not draining (for LB pool) |
| GET | /meta | Build/runtime info: version, java, feature flags |
| GET | /metrics | Prometheus text format: request counters, duration histograms, pool + JVM gauges |

## CSV exports

| Method | Path | Notes |
|---|---|---|
| GET | /stock/levels/export?sku&warehouseId&lowOnly | text/csv download, RFC 4180, capped at 50k rows (`X-Truncated` header) |
| GET | /stock/movements/export?productId&warehouseId&type&refType&refId&from&to | same filters as the movements query |

## Status Codes

200/201/204 success · 400 malformed request · 401 unauthenticated ·
403 insufficient permission · 404 unknown resource · 409 business conflict
(illegal transition, duplicate key, insufficient stock, concurrent modification) ·
422 validation failure (with `fieldErrors`) · 500 internal (details only in logs).
