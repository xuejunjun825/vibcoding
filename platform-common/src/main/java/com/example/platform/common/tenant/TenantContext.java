package com.example.platform.common.tenant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.platform.contract.DataScope;
import com.example.platform.contract.PermissionMatrix;

/**
 * Per-request data scope holder.
 *
 * <p>Read side is fail-closed: {@link #require()} throws when no scope was established, so a
 * query that forgot to go through the auth filter can never silently read the whole database.
 * The wrapper around the {@link ThreadLocal} exists so that business code only ever sees
 * {@link DataScope} — the cleanup rule lives in exactly one place (the filter's {@code finally}).</p>
 *
 * <p>The principal (username / roles / tenant code) is kept next to the scope because audit and
 * the permission interceptor need it, but it is <b>not</b> trusted for authorization decisions on
 * its own: roles are re-read from the database on every request by {@code UserScopeResolver}.</p>
 */
public final class TenantContext {

    /** Request identity as resolved from the database (roles are current, never token-cached). */
    public record Principal(long userId,
                            String username,
                            String name,
                            String tenantCode,
                            String tenantName,
                            List<String> roles,
                            Set<Long> domainIds,
                            Long vendorId) {

        public Principal {
            roles = roles == null ? List.of() : List.copyOf(roles);
            domainIds = domainIds == null ? Set.of() : Set.copyOf(domainIds);
        }

        public Set<String> roleSet() {
            return new LinkedHashSet<>(roles);
        }
    }

    private static final ThreadLocal<DataScope> SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<Principal> PRINCIPAL = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(DataScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        SCOPE.set(scope);
    }

    public static void setPrincipal(Principal principal) {
        if (principal != null) {
            PRINCIPAL.set(principal);
        }
    }

    /** Current scope; throws instead of returning {@code null} (fail-closed). */
    public static DataScope require() {
        DataScope scope = SCOPE.get();
        if (scope == null) {
            throw new IllegalStateException(
                    "TenantContext is not set: the request did not pass through AuthFilter");
        }
        return scope;
    }

    public static boolean isSet() {
        return SCOPE.get() != null;
    }

    public static DataScope scopeOrNull() {
        return SCOPE.get();
    }

    public static Principal principal() {
        return PRINCIPAL.get();
    }

    public static List<String> roles() {
        Principal principal = PRINCIPAL.get();
        return principal == null ? List.of() : principal.roles();
    }

    public static String username() {
        Principal principal = PRINCIPAL.get();
        return principal == null ? null : principal.username();
    }

    public static String tenantCode() {
        Principal principal = PRINCIPAL.get();
        return principal == null ? null : principal.tenantCode();
    }

    public static String tenantName() {
        Principal principal = PRINCIPAL.get();
        return principal == null ? null : principal.tenantName();
    }

    /** Effective access of the current caller, union of every role it holds. */
    public static PermissionMatrix.Access access(String function) {
        return PermissionMatrix.accessOf(function, roles());
    }

    public static boolean can(String function) {
        return access(function) != PermissionMatrix.Access.DENY;
    }

    public static void clear() {
        SCOPE.remove();
        PRINCIPAL.remove();
    }
}
