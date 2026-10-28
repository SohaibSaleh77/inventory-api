package com.ims.domain.auth.dto;

import com.ims.security.Role;

import java.time.Instant;

/** DTO records for the auth module. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, Instant expiresAt,
                                long userId, String username, String fullName,
                                Role role) {
    }

    public record MeResponse(long id, String username, String email, String fullName,
                             Role role, java.util.Set<String> permissions,
                             Instant lastLoginAt) {
    }
}
