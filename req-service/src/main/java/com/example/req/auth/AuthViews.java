package com.example.req.auth;

import java.util.List;

/** Wire shapes for login and {@code /auth/me}; mirrors web/src/api/types.ts. */
public final class AuthViews {

    public record LoginRequest(String tenantCode, String username, String password) {
    }

    public record SessionUser(long id, String name, List<String> roles, List<Long> domainIds,
                              Long vendorId, Long tenantId, String tenantName, String username) {
    }

    /** @param access ALLOW or RESTRICTED — DENY entries are omitted, the frontend hides them. */
    public record PermissionEntry(String code, String access) {
    }

    public record LoginResponse(String token, long expiresIn, SessionUser user,
                                List<PermissionEntry> permissions, String defaultRoute,
                                long tenantId, String tenantCode) {
    }

    public record ScopeInfo(String type, List<Long> domainIds, Long vendorId, List<Long> orgIds,
                            String label) {
    }

    public record MeResponse(SessionUser user, List<PermissionEntry> permissions, ScopeInfo scope) {
    }

    private AuthViews() {
    }
}
