#!/usr/bin/env bash
# ============================================================================
# e2e-smoke.sh - full business lifecycle against a LIVE running inventory-api server
# Exercises: master data -> PO -> approve -> receive -> transfer -> SO ->
#            confirm -> partial ship -> final ship -> return -> adjust ->
#            reports -> audit -> ledger reconciliation -> stocktake ->
#            webhooks -> dashboard -> reorder -> import -> barcode -> password
# Usage: ./scripts/e2e-smoke.sh [base_url]
# ============================================================================
set -uo pipefail

BASE="${1:-http://127.0.0.1:8080}"
PASS=0
FAIL=0

check() { # check <name> <expected> <actual>
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "  ok: $1";
  else FAIL=$((FAIL+1)); echo "  FAIL: $1 (expected '$2' got '$3')"; fi
}

# jq-style extraction without jq: pass a python expression over the payload
jget() { python3 -c "
import sys, json
d = json.load(sys.stdin)
print($1)
"; }

post() { curl -s -X POST "$BASE$1" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "${@:2}"; }
put()  { curl -s -X PUT  "$BASE$1" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "${@:2}"; }
get()  { curl -s "$BASE$1" -H "Authorization: Bearer $TOKEN"; }

# Convenience: on-hand qty for (sku, warehouseCode)
onhand() { get "/api/stock/levels?sku=$1" | jget "format([l for l in d['content'] if l['warehouseCode']=='$2'][0]['qtyOnHand'], '.3f')"; }
q3() { get "$1" | jget "format(d$2, '.3f')"; }

echo "== 1. Health & login =="
check "health UP" "UP" "$(curl -s "$BASE/api/health" | jget "d['status']")"
# Password resolution: IMS_ADMIN_PASSWORD env wins; otherwise the generated
# bootstrap file written on first boot (relative to the server's working dir).
PW="${IMS_ADMIN_PASSWORD:-$(grep -o 'password: .*' logs/bootstrap-admin.txt 2>/dev/null | cut -d' ' -f2)}"
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}" | jget "d['token']")
[ -n "$TOKEN" ] && echo "  ok: admin login" || { echo "  FAIL: login"; exit 1; }

echo "== 2. Master data =="
post /api/warehouses -d '{"code":"WH-MAIN","name":"Main Distribution Center","city":"Riyadh","country":"Saudi Arabia"}' > /dev/null
post /api/warehouses -d '{"code":"WH-EAST","name":"Eastern Branch","city":"Dammam","country":"Saudi Arabia"}' > /dev/null
check "2 warehouses" 2 "$(get /api/warehouses | jget "len(d)")"
post /api/categories -d '{"code":"LAPTOP","name":"Laptops"}' > /dev/null
post /api/categories -d '{"code":"PERIPH","name":"Peripherals","parentCode":"LAPTOP"}' > /dev/null
check "2 categories" 2 "$(get /api/categories | jget "len(d)")"
post /api/suppliers -d '{"code":"TECHNO-01","name":"TechnoSource Trading","contactName":"Sara Al-Otaibi","email":"sales@technosource.example","phone":"+966-11-555-0100","city":"Riyadh","paymentTermsDays":45,"rating":4}' > /dev/null
post /api/customers -d '{"code":"RET-0001","name":"Al-Futtaim Electronics","customerType":"WHOLESALE","email":"orders@alfuttaim.example","creditLimit":500000}' > /dev/null
post /api/products -d '{"sku":"LAP-THINK-14","name":"Laptop ThinkPad 14 i7","description":"Business laptop 14in i7 32GB","categoryCode":"LAPTOP","unitOfMeasure":"EA","standardCost":950.00,"listPrice":1299.00,"reorderPoint":10,"reorderQty":50}' > /dev/null
post /api/products -d '{"sku":"MOU-ERG-01","name":"Ergonomic Mouse","description":"Wireless ergonomic mouse","barcode":"5012345678900","categoryCode":"PERIPH","unitOfMeasure":"EA","standardCost":18.50,"listPrice":39.99,"reorderPoint":100,"reorderQty":400}' > /dev/null
check "2 products" 2 "$(get '/api/products?size=50' | jget "len(d['content'])")"

echo "== 3. Supplier catalog =="
COST=$(put "/api/suppliers/1/items" -d '{"productSku":"LAP-THINK-14","supplierSku":"TS-LAP14","unitCost":912.50,"preferred":true,"leadTimeDays":21}' | jget "format(d['unitCost'], '.2f')")
check "supplier item cost 912.50" "912.50" "$COST"
put "/api/suppliers/1/items" -d '{"productSku":"MOU-ERG-01","supplierSku":"TS-MOU01","unitCost":17.25,"preferred":true,"leadTimeDays":14}' > /dev/null

echo "== 4. Purchase order lifecycle =="
PO=$(post /api/purchase-orders -d '{"supplierCode":"TECHNO-01","warehouseCode":"WH-MAIN","currency":"USD","notes":"Q4 restock","lines":[{"productSku":"LAP-THINK-14","qty":30,"unitCost":912.50,"taxPct":15},{"productSku":"MOU-ERG-01","qty":200,"unitCost":18.50,"taxPct":15}]}')
PO_ID=$(echo "$PO" | jget "d['id']")
LINE1=$(echo "$PO" | jget "d['lines'][0]['id']")
LINE2=$(echo "$PO" | jget "d['lines'][1]['id']")
# 30*912.50=27375 + 200*18.50=3700 -> 31075 + 15% = 35736.25
check "PO total 35736.25" "35736.25" "$(echo "$PO" | jget "format(d['total'], '.2f')")"
check "PO submitted" "SUBMITTED" "$(post "/api/purchase-orders/$PO_ID/submit" | jget "d['status']")"
check "PO approved" "APPROVED" "$(post "/api/purchase-orders/$PO_ID/approve" | jget "d['status']")"

echo "== 5. Receive goods (two partial receipts) =="
post "/api/purchase-orders/$PO_ID/receipts" -d "{\"lines\":[{\"poLineId\":$LINE1,\"qtyAccepted\":20,\"qtyRejected\":1}],\"note\":\"First delivery\"}" > /dev/null
post "/api/purchase-orders/$PO_ID/receipts" -d "{\"lines\":[{\"poLineId\":$LINE1,\"qtyAccepted\":9},{\"poLineId\":$LINE2,\"qtyAccepted\":200}]}" > /dev/null
check "PO fully received" "RECEIVED" "$(get "/api/purchase-orders/$PO_ID" | jget "d['status']")"

echo "== 6. Stock levels after receiving =="
check "laptop on-hand 29 (30 minus 1 rejected)" "29.000" "$(onhand LAP-THINK WH-MAIN)"
check "laptop reserved 0" "0.000" "$(q3 '/api/stock/levels?sku=LAP-THINK' "['content'][0]['qtyReserved']")"
check "laptop moving-average cost 912.50" "912.5000" "$(get '/api/stock/levels?sku=LAP-THINK' | jget "format(d['content'][0]['avgUnitCost'], '.4f')")"

echo "== 7. Inter-warehouse transfer =="
TRF_ID=$(post /api/transfers -d '{"productSku":"MOU-ERG-01","fromWarehouseCode":"WH-MAIN","toWarehouseCode":"WH-EAST","quantity":50,"note":"East branch replenishment"}' | jget "d['id']")
check "transfer dispatched" "DISPATCHED" "$(post "/api/transfers/$TRF_ID/dispatch" | jget "d['status']")"
check "transfer received" "RECEIVED" "$(post "/api/transfers/$TRF_ID/receive" | jget "d['status']")"
check "main mice 150" "150.000" "$(onhand MOU-ERG WH-MAIN)"
check "east mice 50" "50.000" "$(onhand MOU-ERG WH-EAST)"

echo "== 8. Sales order + reservation =="
SO=$(post /api/sales-orders -d '{"customerCode":"RET-0001","warehouseCode":"WH-MAIN","requestedDate":"2026-11-05","lines":[{"productSku":"LAP-THINK-14","qty":10,"unitPrice":1249.00,"discountPct":5,"taxPct":15},{"productSku":"MOU-ERG-01","qty":30,"unitPrice":39.99,"taxPct":15}]}')
SO_ID=$(echo "$SO" | jget "d['id']")
SOL1=$(echo "$SO" | jget "d['lines'][0]['id']")
SOL2=$(echo "$SO" | jget "d['lines'][1]['id']")
check "SO confirmed" "CONFIRMED" "$(post "/api/sales-orders/$SO_ID/confirm" | jget "d['status']")"
check "laptop reserved 10" "10.000" "$(q3 '/api/stock/levels?sku=LAP-THINK' "['content'][0]['qtyReserved']")"
check "laptop available 19" "19.000" "$(q3 '/api/stock/levels?sku=LAP-THINK' "['content'][0]['qtyAvailable']")"

echo "== 9. Partial then final shipment =="
post "/api/sales-orders/$SO_ID/shipments" -d "{\"lines\":[{\"soLineId\":$SOL1,\"qty\":4},{\"soLineId\":$SOL2,\"qty\":30}],\"carrier\":\"Aramex\",\"trackingNo\":\"ARX-88123\"}" > /dev/null
check "SO partially fulfilled" "PARTIALLY_FULFILLED" "$(get "/api/sales-orders/$SO_ID" | jget "d['status']")"
post "/api/sales-orders/$SO_ID/shipments" -d "{\"lines\":[{\"soLineId\":$SOL1,\"qty\":6}]}" > /dev/null
check "SO fulfilled" "FULFILLED" "$(get "/api/sales-orders/$SO_ID" | jget "d['status']")"
check "laptop on-hand 19" "19.000" "$(onhand LAP-THINK WH-MAIN)"
check "laptop reserved 0 after fulfilment" "0.000" "$(q3 '/api/stock/levels?sku=LAP-THINK' "['content'][0]['qtyReserved']")"

echo "== 10. Customer return (2 mice, restock) =="
post "/api/sales-orders/$SO_ID/returns" -d "{\"lines\":[{\"soLineId\":$SOL2,\"qty\":2,\"restock\":true}],\"reason\":\"CUSTOMER_REMORSE\",\"note\":\"Box opened\"}" > /dev/null
check "main mice on-hand 122 after return" "122.000" "$(onhand MOU-ERG WH-MAIN)"

echo "== 11. Stock adjustment (damage in east) =="
post /api/stock/adjustments -d '{"productSku":"MOU-ERG-01","warehouseCode":"WH-EAST","quantityDelta":-5,"reason":"DAMAGE","note":"Water damage in transit"}' > /dev/null
check "east mice 45 after damage" "45.000" "$(onhand MOU-ERG WH-EAST)"

echo "== 12. Reports =="
check "stock valuation computed" "yes" "$(get /api/reports/stock-valuation | jget "'yes' if d['grandTotal'] > 0 else 'no'")"
check "gross margin positive" "yes" "$(get /api/reports/gross-margin | jget "'yes' if d['totalMargin'] > 0 else 'no'")"
check "movement summary non-empty" "yes" "$(get /api/reports/movement-summary | jget "'yes' if len(d['rows']) > 0 else 'no'")"
check "low stock report works" "yes" "$(get /api/reports/low-stock | jget "'yes' if isinstance(d['rows'], list) else 'no'")"

echo "== 13. Audit trail =="
AUDIT_COUNT=$(get "/api/audit/logs?size=200" | jget "d['totalElements']")
if [ "${AUDIT_COUNT:-0}" -gt 20 ] 2>/dev/null; then
  PASS=$((PASS+1)); echo "  ok: $AUDIT_COUNT audit entries recorded"
else
  FAIL=$((FAIL+1)); echo "  FAIL: audit entries: $AUDIT_COUNT"
fi

echo "== 14. Movement ledger reconciliation (every stock row) =="
RECON=$(python3 - "$BASE" "$TOKEN" <<'PYEOF'
import json, sys, urllib.request
base, token = sys.argv[1], sys.argv[2]
def api(path):
    req = urllib.request.Request(base + path, headers={"Authorization": "Bearer " + token})
    return json.load(urllib.request.urlopen(req))
levels = api("/api/stock/levels?size=200")["content"]
bad = 0
for level in levels:
    ms = api(f"/api/stock/movements?productId={level['productId']}&warehouseId={level['warehouseId']}&size=1000")["content"]
    ledger = sum(float(m["quantity"]) for m in ms)
    if abs(ledger - float(level["qtyOnHand"])) > 0.0005:
        print(f"  FAIL: {level['sku']} in {level['warehouseCode']}: ledger {ledger} vs on hand {level['qtyOnHand']}")
        bad += 1
print(f"ok: ledger reconciles for all {len(levels)} stock rows" if bad == 0 else f"FAIL: {bad} mismatches")
sys.exit(1 if bad else 0)
PYEOF
)
echo "  $RECON"
if echo "$RECON" | grep -q "^ok"; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi

echo "== 15. Production hardening: meta, health split, request ids =="
check "meta endpoint public" "v1" "$(curl -s "$BASE/api/meta" | jget "d['apiVersion']")"
check "liveness probe" "UP" "$(curl -s "$BASE/api/health/live" | jget "d['status']")"
check "readiness probe" "UP" "$(curl -s "$BASE/api/health/ready" | jget "d['status']")"
RID=$(curl -s -D - -o /dev/null "$BASE/api/health" | grep -i '^x-request-id:' | tr -d '\r' | awk '{print $2}')
if [ -n "$RID" ]; then PASS=$((PASS+1)); echo "  ok: request id assigned ($RID)"; else FAIL=$((FAIL+1)); echo "  FAIL: no X-Request-Id"; fi
SEC=$(curl -s -D - -o /dev/null -H "Authorization: Bearer $TOKEN" "$BASE/api/products?size=1" | grep -ci 'x-content-type-options')
if [ "$SEC" -ge 1 ]; then PASS=$((PASS+1)); echo "  ok: security headers present"; else FAIL=$((FAIL+1)); echo "  FAIL: security headers missing"; fi
check "405 with Allow header" "405" "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/health")"

echo "== 16. Idempotency + ETag =="
IDEM_KEY="smoke-$(date +%s)"
R1=$(curl -s -X POST "$BASE/api/warehouses" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "Idempotency-Key: $IDEM_KEY" -d '{"code":"WH-IDEM","name":"Idem Check"}')
R2=$(curl -s -X POST "$BASE/api/warehouses" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "Idempotency-Key: $IDEM_KEY" -d '{"code":"WH-IDEM","name":"Idem Check"}')
ID1=$(echo "$R1" | jget "d['id']"); ID2=$(echo "$R2" | jget "d['id']")
check "idempotent replay returns same id" "$ID1" "$ID2"
WH_IDEM_COUNT=$(get /api/warehouses | jget "len([w for w in d if w['code']=='WH-IDEM'])")
check "no duplicate created" "1" "$WH_IDEM_COUNT"
ETAG=$(curl -s -D - -o /dev/null -H "Authorization: Bearer $TOKEN" "$BASE/api/warehouses/$ID1" | grep -i '^etag:' | tr -d '\r' | awk '{print $2}')
if [ -n "$ETAG" ]; then PASS=$((PASS+1)); echo "  ok: ETag present"; else FAIL=$((FAIL+1)); echo "  FAIL: no ETag"; fi
check "If-None-Match returns 304" "304" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" -H "If-None-Match: $ETAG" "$BASE/api/warehouses/$ID1")"

echo "== 17. Metrics + CSV exports =="
METRICS_STATUS=$(curl -s -o /tmp/inventory-api-metrics.txt -w '%{http_code}' "$BASE/api/metrics")
METRICS_SIZE=$(wc -c < /tmp/inventory-api-metrics.txt)
if [ "$METRICS_STATUS" = "200" ] && grep -q 'http_server_requests_total' /tmp/inventory-api-metrics.txt; then
  PASS=$((PASS+1)); echo "  ok: prometheus metrics (${METRICS_SIZE} bytes)"
else
  FAIL=$((FAIL+1)); echo "  FAIL: metrics endpoint (status=$METRICS_STATUS, ${METRICS_SIZE} bytes)"; head -3 /tmp/inventory-api-metrics.txt; fi
CSV_HEAD=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/stock/levels/export" | head -1)
check "levels csv export" "sku" "$(echo "$CSV_HEAD" | cut -d, -f1)"
MOV_HEAD=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/stock/movements/export" | head -1)
check "movements csv export" "movementNo" "$(echo "$MOV_HEAD" | cut -d, -f1)"
check "oversized body rejected 413" "413" "$(python3 -c "print('{\"sku\":\"' + 'x'*1100000 + '\"}')" > /tmp/big.json; curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/products" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data-binary @/tmp/big.json)"

echo "== 18. Stocktake (cycle count) =="
STK=$(post /api/stocktakes -d '{"warehouseCode":"WH-EAST","name":"East annual count","blind":false}')
STK_ID=$(echo "$STK" | jget "d['id']")
check "stocktake DRAFT with all products" "2" "$(echo "$STK" | jget "d['lineCount']")"
check "stocktake opens" "OPEN" "$(post "/api/stocktakes/$STK_ID/open" | jget "d['status']")"
post "/api/stocktakes/$STK_ID/counts" -d '{"productSku":"MOU-ERG-01","countedQty":43}' > /dev/null
post "/api/stocktakes/$STK_ID/counts" -d '{"productSku":"LAP-THINK-14","countedQty":0}' > /dev/null
check "stocktake closes" "REVIEW" "$(post "/api/stocktakes/$STK_ID/close" | jget "d['status']")"
check "one variance detected (laptop expected 0, counted 0)" "1" "$(get "/api/stocktakes/$STK_ID" | jget "d['varianceLines']")"
check "posting corrects one line" "1" "$(post "/api/stocktakes/$STK_ID/post" | jget "d['correctedLines']")"
check "stocktake POSTED" "POSTED" "$(get "/api/stocktakes/$STK_ID" | jget "d['status']")"
check "east mice corrected to 43" "43.000" "$(onhand MOU-ERG WH-EAST)"
check "movements reference the stocktake" "1" "$(get "/api/stock/movements?refType=STOCKTAKE&refId=$STK_ID&size=10" | jget "d['totalElements']")"
STK_CSV=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/stocktakes/$STK_ID/export" | head -1)
check "stocktake csv export" "sku" "$(echo "$STK_CSV" | cut -d, -f1)"

echo "== 19. Webhook delivery (local sink) =="
SINK_PORT=18099
rm -f /tmp/inventory-api-webhook-sink.json
python3 - "$SINK_PORT" <<'PYEOF' &
import http.server, json, sys, threading
class Sink(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        event = {"event": self.headers.get("X-IMS-Event"),
                 "deliveryId": self.headers.get("X-IMS-Delivery-Id"),
                 "signature": self.headers.get("X-IMS-Signature"),
                 "body": body.decode()}
        with open("/tmp/inventory-api-webhook-sink.json", "a") as f:
            f.write(json.dumps(event) + "\n")
        self.send_response(204); self.end_headers()
    def log_message(self, *args): pass
server = http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), Sink)
threading.Timer(120, server.shutdown).start()
server.serve_forever()
PYEOF
SINK_PID=$!
sleep 1
HOOK=$(post /api/webhooks -d '{"name":"smoke-sink","url":"http://127.0.0.1:'"$SINK_PORT"'/sink","secret":"smoke-secret-0123456789","events":["TEST"]}')
HOOK_ID=$(echo "$HOOK" | jget "d['id']")
check "webhook registered" "true" "$(echo "$HOOK" | jget "str(d['isActive']).lower()")"
check "test event accepted" "TEST" "$(post "/api/webhooks/$HOOK_ID/test" | jget "d['eventType']")"
sleep 4   # dispatcher drains every few seconds (test interval override: see below)
# deliver now via the API? the dispatcher is background-only; poll instead
for i in $(seq 1 20); do
  STATUS=$(get "/api/webhooks/$HOOK_ID/deliveries" | jget "d['content'][0]['status'] if d['content'] else 'PENDING'")
  [ "$STATUS" = "SUCCESS" ] && break
  sleep 2
done
check "webhook delivered SUCCESS" "SUCCESS" "$STATUS"
kill $SINK_PID 2>/dev/null; wait $SINK_PID 2>/dev/null
SIG_OK=$(python3 - <<'PYEOF'
import json, hmac, hashlib
try:
    events = [json.loads(line) for line in open("/tmp/inventory-api-webhook-sink.json")]
    assert events, "no events captured"
    e = events[0]
    expected = "sha256=" + hmac.new(b"smoke-secret-0123456789", e["body"].encode(),
                                    hashlib.sha256).hexdigest()
    ok = e["signature"] == expected and e["event"] == "TEST" and e["deliveryId"]
    print("valid" if ok else "invalid")
except Exception as ex:
    print("error: %s" % ex)
PYEOF
)
check "delivery HMAC signature valid" "valid" "$SIG_OK"
check "webhook delivery log kept" "204" "$(get "/api/webhooks/$HOOK_ID/deliveries" | jget "d['content'][0]['lastStatusCode']")"
check "webhook deleted" "204" "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/webhooks/$HOOK_ID" -H "Authorization: Bearer $TOKEN")"

echo "== 20. Dashboard, reorder, barcode, import, password =="
check "dashboard KPIs computed" "yes" "$(get /api/dashboard | jget "'yes' if d['activeWarehouses'] >= 2 and len(d['recentMovements']) > 0 else 'no'")"
check "reorder proposals listed" "yes" "$(get /api/reports/reorder-proposals | jget "'yes' if isinstance(d['rows'], list) else 'no'")"
# shrink mice below the company-wide reorder point (100): 122 main + 43 east
post /api/stock/adjustments -d '{"productSku":"MOU-ERG-01","warehouseCode":"WH-MAIN","quantityDelta":-80,"reason":"LOSS","note":"Shrinkage cleanup"}' > /dev/null
check "mice below reorder point after shrinkage" "yes" "$(get /api/reports/reorder-proposals | jget "'yes' if any(r['sku']=='MOU-ERG-01' for r in d['rows']) else 'no'")"
REORDER=$(post /api/purchase-orders/from-reorder -d '{"warehouseCode":"WH-MAIN"}')
DRAFT_POS=$(echo "$REORDER" | jget "len(d['purchaseOrders'])")
check "reorder generates one draft PO (mice below reorder point)" "1" "$DRAFT_POS"
check "reorder draft PO has the mouse line" "MOU-ERG-01" "$(echo "$REORDER" | jget "d['purchaseOrders'][0]['lines'][0]['productSku']")"
check "reorder draft uses catalog cost" "17.25" "$(echo "$REORDER" | jget "format(d['purchaseOrders'][0]['lines'][0]['unitCost'], '.2f')")"
check "reorder draft orders the reorder quantity" "400.000" "$(echo "$REORDER" | jget "format(d['purchaseOrders'][0]['lines'][0]['qtyOrdered'], '.3f')")"
check "barcode lookup" "MOU-ERG-01" "$(get "/api/products/barcode/5012345678900" | jget "d['sku']")"
IMP=$(printf 'sku,name,unitOfMeasure,standardCost,listPrice\r\nSMK-IMP-1,Smoke Imported Product,EA,5.00,9.00\r\n' | curl -s -X POST "$BASE/api/products/import" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: text/csv' --data-binary @-)
check "csv import creates product" "1" "$(echo "$IMP" | jget "d['created']")"
check "csv import reports no errors" "0" "$(echo "$IMP" | jget "len(d['errors'])")"
check "imported product searchable" "yes" "$(get '/api/products?search=Smoke%20Imported' | jget "'yes' if d['totalElements'] == 1 else 'no'")"
PW_CHECK=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/change-password" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"currentPassword":"totally-wrong","newPassword":"AlsoWrong!123"}')
check "wrong current password rejected" "422" "$PW_CHECK"

echo
echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
