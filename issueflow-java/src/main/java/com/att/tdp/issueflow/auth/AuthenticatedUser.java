package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.common.domain.Role;

import java.time.Instant;

/**
 * Principal stored in the SecurityContext after a JWT is accepted by JwtAuthFilter.
 * Includes jti + token expiry so the logout handler can denylist the current token.
 */
public record AuthenticatedUser(
        Long userId,
        String username,
        Role role,
        String jti,
        Instant tokenExpiresAt
) {}
