package com.ims.domain.user.dto;

import com.ims.domain.user.User;
import com.ims.security.Role;

import java.time.Instant;

/** DTO records for the user module. Responses never include the password hash. */
public final class UserDtos {

    private UserDtos() {
    }

    public record CreateRequest(String username, String email, String password,
                                String fullName, String role) {
    }

    public record UpdateRequest(String email, String fullName, String role) {
    }

    public record ResetPasswordRequest(String newPassword) {
    }

    public record ChangeOwnPasswordRequest(String currentPassword, String newPassword) {
    }

    public record Response(Long id, String username, String email, String fullName,
                           Role role, String status, int failedLoginAttempts,
                           Instant lockedUntil, Instant passwordChangedAt,
                           Instant lastLoginAt, int version,
                           Instant createdAt, Instant updatedAt) {

        public static Response from(User user) {
            return new Response(user.getId(), user.getUsername(), user.getEmail(),
                    user.getFullName(), user.getRole(), user.getStatus(),
                    user.getFailedLoginAttempts(), user.getLockedUntil(),
                    user.getPasswordChangedAt(), user.getLastLoginAt(),
                    user.getVersion(), user.getCreatedAt(), user.getUpdatedAt());
        }
    }

    public record Summary(Long id, String username, String fullName, Role role, String status) {

        public static Summary from(User user) {
            return new Summary(user.getId(), user.getUsername(), user.getFullName(),
                    user.getRole(), user.getStatus());
        }
    }
}
