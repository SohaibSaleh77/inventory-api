package com.ims.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** System roles. The permission matrix is defined in {@link RolePermissions}. */
public enum Role {
    ADMIN,
    MANAGER,
    STAFF,
    VIEWER;

    public boolean atLeast(Role minimum) {
        return this.ordinal() <= minimum.ordinal();
    }
}
