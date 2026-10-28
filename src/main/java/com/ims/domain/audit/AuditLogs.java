package com.ims.domain.audit;

/** Canonical audit action names - stable identifiers used in queries. */
public final class AuditLogs {

    // users & auth
    public static final String LOGIN_SUCCEEDED = "LOGIN_SUCCEEDED";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_ACTIVATED = "USER_ACTIVATED";
    public static final String USER_DEACTIVATED = "USER_DEACTIVATED";
    public static final String USER_PASSWORD_RESET = "USER_PASSWORD_RESET";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";

    // master data
    public static final String CATEGORY_CREATED = "CATEGORY_CREATED";
    public static final String CATEGORY_UPDATED = "CATEGORY_UPDATED";
    public static final String CATEGORY_DELETED = "CATEGORY_DELETED";
    public static final String SUPPLIER_CREATED = "SUPPLIER_CREATED";
    public static final String SUPPLIER_UPDATED = "SUPPLIER_UPDATED";
    public static final String SUPPLIER_DEACTIVATED = "SUPPLIER_DEACTIVATED";
    public static final String SUPPLIER_ACTIVATED = "SUPPLIER_ACTIVATED";
    public static final String SUPPLIER_DELETED = "SUPPLIER_DELETED";
    public static final String SUPPLIER_ITEM_SAVED = "SUPPLIER_ITEM_SAVED";
    public static final String SUPPLIER_ITEM_REMOVED = "SUPPLIER_ITEM_REMOVED";
    public static final String CUSTOMER_CREATED = "CUSTOMER_CREATED";
    public static final String CUSTOMER_UPDATED = "CUSTOMER_UPDATED";
    public static final String CUSTOMER_DEACTIVATED = "CUSTOMER_DEACTIVATED";
    public static final String CUSTOMER_ACTIVATED = "CUSTOMER_ACTIVATED";
    public static final String CUSTOMER_DELETED = "CUSTOMER_DELETED";
    public static final String WAREHOUSE_CREATED = "WAREHOUSE_CREATED";
    public static final String WAREHOUSE_UPDATED = "WAREHOUSE_UPDATED";
    public static final String WAREHOUSE_DEACTIVATED = "WAREHOUSE_DEACTIVATED";
    public static final String WAREHOUSE_ACTIVATED = "WAREHOUSE_ACTIVATED";
    public static final String WAREHOUSE_DELETED = "WAREHOUSE_DELETED";
    public static final String PRODUCT_CREATED = "PRODUCT_CREATED";
    public static final String PRODUCT_UPDATED = "PRODUCT_UPDATED";
    public static final String PRODUCT_ACTIVATED = "PRODUCT_ACTIVATED";
    public static final String PRODUCT_DEACTIVATED = "PRODUCT_DEACTIVATED";
    public static final String PRODUCT_DELETED = "PRODUCT_DELETED";

    // inventory
    public static final String STOCK_ADJUSTED = "STOCK_ADJUSTED";
    public static final String TRANSFER_CREATED = "TRANSFER_CREATED";
    public static final String TRANSFER_DISPATCHED = "TRANSFER_DISPATCHED";
    public static final String TRANSFER_RECEIVED = "TRANSFER_RECEIVED";
    public static final String TRANSFER_CANCELLED = "TRANSFER_CANCELLED";
    public static final String STOCKTAKE_CREATED = "STOCKTAKE_CREATED";
    public static final String STOCKTAKE_OPENED = "STOCKTAKE_OPENED";
    public static final String STOCKTAKE_COUNTED = "STOCKTAKE_COUNTED";
    public static final String STOCKTAKE_CLOSED = "STOCKTAKE_CLOSED";
    public static final String STOCKTAKE_POSTED = "STOCKTAKE_POSTED";
    public static final String STOCKTAKE_CANCELLED = "STOCKTAKE_CANCELLED";

    // purchasing
    public static final String PO_CREATED = "PO_CREATED";
    public static final String PO_UPDATED = "PO_UPDATED";
    public static final String PO_SUBMITTED = "PO_SUBMITTED";
    public static final String PO_APPROVED = "PO_APPROVED";
    public static final String PO_CANCELLED = "PO_CANCELLED";
    public static final String PO_CLOSED = "PO_CLOSED";
    public static final String GOODS_RECEIVED = "GOODS_RECEIVED";

    // sales
    public static final String SO_CREATED = "SO_CREATED";
    public static final String SO_UPDATED = "SO_UPDATED";
    public static final String SO_CONFIRMED = "SO_CONFIRMED";
    public static final String SO_CANCELLED = "SO_CANCELLED";
    public static final String SO_CLOSED = "SO_CLOSED";
    public static final String GOODS_SHIPPED = "GOODS_SHIPPED";
    public static final String SALES_RETURN_RECEIVED = "SALES_RETURN_RECEIVED";

    // integrations
    public static final String WEBHOOK_CREATED = "WEBHOOK_CREATED";
    public static final String WEBHOOK_UPDATED = "WEBHOOK_UPDATED";
    public static final String WEBHOOK_DELETED = "WEBHOOK_DELETED";
    public static final String WEBHOOK_TEST_EVENT = "WEBHOOK_TEST_EVENT";
    public static final String WEBHOOK_DELIVERY_RETRIED = "WEBHOOK_DELIVERY_RETRIED";

    // data management
    public static final String PRODUCTS_IMPORTED = "PRODUCTS_IMPORTED";

    private AuditLogs() {
    }
}
