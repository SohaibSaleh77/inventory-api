package com.ims.security;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Fine-grained capabilities. Controllers check permissions, not roles, so the
 * role model can evolve without touching call sites.
 */
public enum Permission {
    // master data
    CATEGORY_READ, CATEGORY_WRITE,
    SUPPLIER_READ, SUPPLIER_WRITE,
    CUSTOMER_READ, CUSTOMER_WRITE,
    WAREHOUSE_READ, WAREHOUSE_WRITE,
    PRODUCT_READ, PRODUCT_WRITE,
    // inventory
    STOCK_READ, STOCK_WRITE,
    STOCK_ADJUST, STOCK_TRANSFER,
    // trade documents
    PURCHASE_ORDER_READ, PURCHASE_ORDER_WRITE, PURCHASE_ORDER_APPROVE,
    SALES_ORDER_READ, SALES_ORDER_WRITE, SALES_ORDER_FULFILL,
    // analytics & admin
    REPORT_READ,
    AUDIT_READ,
    USER_READ, USER_WRITE,
    // integrations
    WEBHOOK_READ, WEBHOOK_WRITE
}
