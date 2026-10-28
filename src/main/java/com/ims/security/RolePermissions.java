package com.ims.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Role to permission matrix. ADMIN inherits everything; MANAGER runs the
 * warehouse; STAFF executes daily operations; VIEWER is read-only.
 */
public final class RolePermissions {

    private static final Map<Role, Set<Permission>> MATRIX = Map.of(
            Role.ADMIN, EnumSet.allOf(Permission.class),

            Role.MANAGER, EnumSet.of(
                    Permission.CATEGORY_READ, Permission.CATEGORY_WRITE,
                    Permission.SUPPLIER_READ, Permission.SUPPLIER_WRITE,
                    Permission.CUSTOMER_READ, Permission.CUSTOMER_WRITE,
                    Permission.WAREHOUSE_READ, Permission.WAREHOUSE_WRITE,
                    Permission.PRODUCT_READ, Permission.PRODUCT_WRITE,
                    Permission.STOCK_READ, Permission.STOCK_WRITE,
                    Permission.STOCK_ADJUST, Permission.STOCK_TRANSFER,
                    Permission.PURCHASE_ORDER_READ, Permission.PURCHASE_ORDER_WRITE,
                    Permission.PURCHASE_ORDER_APPROVE,
                    Permission.SALES_ORDER_READ, Permission.SALES_ORDER_WRITE,
                    Permission.SALES_ORDER_FULFILL,
                    Permission.REPORT_READ,
                    Permission.USER_READ,
                    Permission.WEBHOOK_READ, Permission.WEBHOOK_WRITE),

            Role.STAFF, EnumSet.of(
                    Permission.CATEGORY_READ,
                    Permission.SUPPLIER_READ,
                    Permission.CUSTOMER_READ, Permission.CUSTOMER_WRITE,
                    Permission.WAREHOUSE_READ,
                    Permission.PRODUCT_READ, Permission.PRODUCT_WRITE,
                    Permission.STOCK_READ, Permission.STOCK_WRITE,
                    Permission.STOCK_TRANSFER,
                    Permission.PURCHASE_ORDER_READ, Permission.PURCHASE_ORDER_WRITE,
                    Permission.SALES_ORDER_READ, Permission.SALES_ORDER_WRITE,
                    Permission.SALES_ORDER_FULFILL,
                    Permission.REPORT_READ),

            Role.VIEWER, EnumSet.of(
                    Permission.CATEGORY_READ,
                    Permission.SUPPLIER_READ,
                    Permission.CUSTOMER_READ,
                    Permission.WAREHOUSE_READ,
                    Permission.PRODUCT_READ,
                    Permission.STOCK_READ,
                    Permission.PURCHASE_ORDER_READ,
                    Permission.SALES_ORDER_READ,
                    Permission.REPORT_READ));

    private RolePermissions() {
    }

    public static Set<Permission> permissionsOf(Role role) {
        return MATRIX.getOrDefault(role, Set.of());
    }

    public static boolean has(Role role, Permission permission) {
        return permissionsOf(role).contains(permission);
    }

    public static Map<Role, Set<Permission>> matrix() {
        return Collections.unmodifiableMap(MATRIX);
    }
}
