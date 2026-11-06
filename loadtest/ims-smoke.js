// =============================================================================
// ims-smoke.js - k6 smoke/load profile for the IMS API.
//
// Seeds master data + ~5000 units of stock once in setup(), then exercises:
//   - 80% read traffic : /api/products, /api/stock/levels, /api/reports/stock-summary
//   - 20% order flow   : POST /api/sales-orders (1 line, qty 1) + confirm
//
// How to run (k6 >= 0.45):
//   k6 run \
//     -e BASE_URL=http://127.0.0.1:8080 \
//     -e ADMIN_USER=admin \
//     -e ADMIN_PASS='<bootstrap admin password>' \
//     loadtest/ims-smoke.js
//
// (-e sets k6 env vars; plain environment variables work the same way:
//    BASE_URL=... ADMIN_PASS=... k6 run loadtest/ims-smoke.js)
//
// Optional env vars:
//   UNITS     stock units seeded in setup()          (default 5000)
//
// Thresholds (k6 exits non-zero on breach):
//   http_req_failed     rate < 0.02
//   http_req_duration   p(95) < 600ms
// =============================================================================

import http from 'k6/http';
import { check } from 'k6';
import { sleep } from 'k6';
import exec from 'k6/execution';

var BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
var ADMIN_USER = __ENV.ADMIN_USER || 'admin';
var ADMIN_PASS = __ENV.ADMIN_PASS || '';
var UNITS = parseInt(__ENV.UNITS || '5000', 10);

var WAREHOUSE = 'LT-WH';
var CATEGORY = 'LT-CAT';
var SUPPLIER = 'LT-SUP';
var CUSTOMER = 'LT-CUST';
var SKU = 'LT-SKU-001';

export var options = {
  scenarios: {
    ramping_vus: {
      executor: 'ramping-vus',
      startVUs: 2,
      gracefulRampDown: '10s',
      stages: [
        { duration: '30s', target: 10 }, // ramp 2 -> 10
        { duration: '60s', target: 10 }, // hold 10
        { duration: '15s', target: 0 },  // ramp down
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<600'],
  },
};

function authHeaders(token) {
  return { headers: { Authorization: 'Bearer ' + token } };
}

// POST that must succeed; aborts the whole run otherwise (use in setup()).
function mustPost(token, path, payload) {
  var res = http.post(BASE_URL + path, payload, authHeaders(token));
  if (res.status !== 200 && res.status !== 201) {
    throw new Error('POST ' + path + ' -> ' + res.status + ' ' + String(res.body).substring(0, 300));
  }
  return res;
}

// Idempotent POST: 201 = created now, 409 = already exists from a previous run.
function createOrIgnore(token, path, payload) {
  var res = http.post(BASE_URL + path, payload, authHeaders(token));
  if (res.status !== 201 && res.status !== 409) {
    throw new Error('POST ' + path + ' -> ' + res.status + ' ' + String(res.body).substring(0, 300));
  }
  return res;
}

function findCustomerId(token, code) {
  var res = http.get(BASE_URL + '/api/customers?search=' + code, authHeaders(token));
  if (res.status === 200) {
    var page = res.json();
    var content = page.content || page;
    for (var i = 0; i < content.length; i++) {
      if (content[i].code === code) {
        return content[i].id;
      }
    }
  }
  return -1; // unknown id; order flow addresses the customer by code anyway
}

// ---------------------------------------------------------------------------
// setup(): login, seed master data, receive a big PO so sales flows have stock
// ---------------------------------------------------------------------------
export function setup() {
  if (!ADMIN_PASS) {
    throw new Error('ADMIN_PASS is required: k6 run -e ADMIN_PASS=<bootstrap admin password> ...');
  }

  var login = http.post(BASE_URL + '/api/auth/login', {
    username: ADMIN_USER,
    password: ADMIN_PASS,
  });
  if (login.status !== 200) {
    throw new Error('admin login failed -> ' + login.status + ' ' + String(login.body).substring(0, 300));
  }
  var token = login.json().token;

  // Master data (tolerates re-runs against a non-reset database).
  createOrIgnore(token, '/api/warehouses', {
    code: WAREHOUSE, name: 'Loadtest Warehouse', city: 'Riyadh', country: 'Saudi Arabia',
  });
  createOrIgnore(token, '/api/categories', { code: CATEGORY, name: 'Loadtest' });
  createOrIgnore(token, '/api/suppliers', {
    code: SUPPLIER, name: 'Loadtest Supplier', email: 'loadtest@example.invalid', paymentTermsDays: 30,
  });
  createOrIgnore(token, '/api/customers', {
    code: CUSTOMER, name: 'Loadtest Customer', customerType: 'RETAIL', creditLimit: 10000000,
  });
  createOrIgnore(token, '/api/products', {
    sku: SKU, name: 'Loadtest Widget', unitOfMeasure: 'EA',
    standardCost: 10, listPrice: 25, reorderPoint: 0, reorderQty: 0,
  });
  var customerId = findCustomerId(token, CUSTOMER);

  // Purchase + receive ~UNITS units into the loadtest warehouse.
  var po = mustPost(token, '/api/purchase-orders', {
    supplierCode: SUPPLIER,
    warehouseCode: WAREHOUSE,
    currency: 'USD',
    lines: [{ productSku: SKU, qty: UNITS, unitCost: 10 }],
  }).json();
  mustPost(token, '/api/purchase-orders/' + po.id + '/submit', '');
  mustPost(token, '/api/purchase-orders/' + po.id + '/approve', '');
  var receipt = mustPost(token, '/api/purchase-orders/' + po.id + '/receipts', {
    lines: [{ poLineId: po.lines[0].id, qtyAccepted: UNITS }],
  }).json();

  console.log('setup: PO ' + po.id + ' -> ' + receipt.status +
    '; stocked ' + UNITS + ' x ' + SKU + ' in ' + WAREHOUSE);

  return {
    token: token,
    productSku: SKU,
    warehouseCode: WAREHOUSE,
    customerCode: CUSTOMER,
    customerId: customerId,
  };
}

// ---------------------------------------------------------------------------
// default(): 80% reads, 20% order flow, all authenticated
// ---------------------------------------------------------------------------
export default function (data) {
  var params = authHeaders(data.token);

  if (Math.random() < 0.2) {
    // ---- write path: create + confirm a 1-unit sales order ----------------
    var soRes = http.post(BASE_URL + '/api/sales-orders', {
      customerCode: data.customerCode,
      warehouseCode: data.warehouseCode,
      notes: 'k6 iteration ' + exec.scenario.iterationInTest,
      lines: [{ productSku: data.productSku, qty: 1 }],
    }, params);

    if (check(soRes, {
      'POST /api/sales-orders -> 201': function (r) { return r.status === 201; },
    })) {
      var so = soRes.json();
      var confirmRes = http.post(BASE_URL + '/api/sales-orders/' + so.id + '/confirm', '', params);
      check(confirmRes, {
        'POST /confirm -> 200': function (r) { return r.status === 200; },
      });
    }
  } else {
    // ---- read path: random pick of three endpoints -------------------------
    var pick = Math.floor(Math.random() * 3);
    var readRes;
    if (pick === 0) {
      readRes = http.get(BASE_URL + '/api/products?page=1&size=25', params);
    } else if (pick === 1) {
      readRes = http.get(BASE_URL + '/api/stock/levels', params);
    } else {
      readRes = http.get(BASE_URL + '/api/reports/stock-summary', params);
    }
    check(readRes, {
      'GET read endpoint -> 200': function (r) { return r.status === 200; },
    });
  }

  sleep(1);
};
