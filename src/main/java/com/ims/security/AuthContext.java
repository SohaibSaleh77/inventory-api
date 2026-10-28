package com.ims.security;

/**
 * The authenticated principal bound to the current request thread.
 *
 * @param userId     users.id
 * @param username   login name
 * @param role       effective role
 * @param tokenId    api_tokens.id of the presented token (for logout/revocation)
 */
public record AuthContext(long userId, String username, Role role, long tokenId) {

    public boolean has(Permission permission) {
        return RolePermissions.has(role, permission);
    }

    public void require(Permission permission) {
        if (!has(permission)) {
            throw new com.ims.common.exception.ForbiddenException(
                    "Role " + role + " lacks permission " + permission);
        }
    }
}
