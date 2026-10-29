package com.ims.unit;

import com.ims.security.Permission;
import com.ims.security.Role;
import com.ims.security.RolePermissions;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RBAC matrix invariants. */
class RolePermissionsTest {

    @Test
    void adminHasEverything() {
        assertEquals(Set.of(Permission.values()),
                RolePermissions.permissionsOf(Role.ADMIN));
        for (Permission permission : Permission.values()) {
            assertTrue(RolePermissions.has(Role.ADMIN, permission));
        }
    }

    @Test
    void viewerIsStrictlyReadOnly() {
        Set<Permission> viewer = RolePermissions.permissionsOf(Role.VIEWER);
        for (Permission permission : viewer) {
            assertTrue(permission.name().endsWith("_READ"),
                    "viewer should only hold *_READ permissions, got " + permission);
        }
        assertFalse(viewer.contains(Permission.USER_READ));
        assertTrue(viewer.contains(Permission.STOCK_READ));
        assertTrue(viewer.contains(Permission.REPORT_READ));
    }

    @Test
    void managerCanApproveButStaffCannot() {
        assertTrue(RolePermissions.has(Role.MANAGER, Permission.PURCHASE_ORDER_APPROVE));
        assertFalse(RolePermissions.has(Role.STAFF, Permission.PURCHASE_ORDER_APPROVE));
        assertFalse(RolePermissions.has(Role.VIEWER, Permission.PURCHASE_ORDER_APPROVE));
    }

    @Test
    void onlyAdminManagesUsersAndAudit() {
        assertFalse(RolePermissions.has(Role.MANAGER, Permission.USER_WRITE));
        assertFalse(RolePermissions.has(Role.STAFF, Permission.USER_WRITE));
        assertTrue(RolePermissions.has(Role.ADMIN, Permission.USER_WRITE));
        assertFalse(RolePermissions.has(Role.MANAGER, Permission.AUDIT_READ));
        assertTrue(RolePermissions.has(Role.ADMIN, Permission.AUDIT_READ));
    }

    @Test
    void staffCanTradeButNotAdjustStock() {
        assertTrue(RolePermissions.has(Role.STAFF, Permission.SALES_ORDER_WRITE));
        assertTrue(RolePermissions.has(Role.STAFF, Permission.PURCHASE_ORDER_WRITE));
        assertTrue(RolePermissions.has(Role.STAFF, Permission.STOCK_TRANSFER));
        assertFalse(RolePermissions.has(Role.STAFF, Permission.STOCK_ADJUST));
        assertTrue(RolePermissions.has(Role.MANAGER, Permission.STOCK_ADJUST));
    }

    @Test
    void matrixCoversAllRoles() {
        for (Role role : Role.values()) {
            assertFalse(RolePermissions.permissionsOf(role).isEmpty(),
                    role + " should have at least one permission");
        }
    }

    @Test
    void roleOrdering() {
        assertTrue(Role.ADMIN.atLeast(Role.MANAGER));
        assertTrue(Role.MANAGER.atLeast(Role.STAFF));
        assertFalse(Role.STAFF.atLeast(Role.MANAGER));
    }

    @Test
    void onlyAdminAndManagerManageWebhooks() {
        assertTrue(RolePermissions.has(Role.ADMIN, Permission.WEBHOOK_READ));
        assertTrue(RolePermissions.has(Role.ADMIN, Permission.WEBHOOK_WRITE));
        assertTrue(RolePermissions.has(Role.MANAGER, Permission.WEBHOOK_READ));
        assertTrue(RolePermissions.has(Role.MANAGER, Permission.WEBHOOK_WRITE));
        assertFalse(RolePermissions.has(Role.STAFF, Permission.WEBHOOK_READ));
        assertFalse(RolePermissions.has(Role.STAFF, Permission.WEBHOOK_WRITE));
        assertFalse(RolePermissions.has(Role.VIEWER, Permission.WEBHOOK_READ));
        assertFalse(RolePermissions.has(Role.VIEWER, Permission.WEBHOOK_WRITE));
    }
}
