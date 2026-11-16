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

    /**
     * The caller's three grant layers (r18).
     *
     * @param epoch      snapshot version; the client keeps the highest it has seen and drops older
     *                   responses, so a slow refresh cannot repaint an outdated menu
     * @param menus      page codes that are not DENY — the navigation's first condition
     * @param actions    action codes that are not DENY — the buttons' only condition
     * @param restricted codes granted at RESTRICTED (allowed, but narrowed by the data scope)
     */
    public record MyPermissions(long epoch, List<String> functionCodes, List<String> menus,
                                List<String> actions, LayerSets restricted) {
    }

    public record LayerSets(List<String> functionCodes, List<String> menus, List<String> actions) {
    }

    private AuthViews() {
    }
}
