package com.example.platform.common.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;

/**
 * The caller as resolved from the database for one request.
 *
 * <p>Roles and authorization domains are read fresh on every request (not from the token) so that
 * revoking a role takes effect within one request rather than when a 12-hour JWT expires. The
 * token only establishes <em>identity</em>; this record establishes <em>permission</em>.</p>
 */
public record UserIdentity(long userId,
                           long tenantId,
                           String username,
                           String name,
                           String tenantCode,
                           String tenantName,
                           List<String> roles,
                           Set<Long> domainIds,
                           Long vendorId,
                           Set<Long> orgSubtreeIds) {

    public UserIdentity {
        roles = roles == null ? List.of() : List.copyOf(roles);
        domainIds = domainIds == null ? Set.of() : Set.copyOf(domainIds);
        orgSubtreeIds = orgSubtreeIds == null ? Set.of() : Set.copyOf(orgSubtreeIds);
    }

    public boolean isAdmin() {
        return roles.contains("PLATFORM_ADMIN");
    }

    /** Roles that anchor visibility to the business organization they belong to. */
    public static final List<String> BIZ_ROLES =
            List.of("BUSINESS", "SUB_BUSINESS_LEADER");

    /** Roles that see only what they submitted themselves. */
    public static final List<String> SELF_ROLES = List.of("SUB_BUSINESS");

    /**
     * Tier precedence: tenant admin → vendor → business org / self → domain → organization.
     *
     * <p>Business-side roles come before the domain check on purpose: every seeded account is
     * authorized for all domains, so a domain-first precedence would silently turn a business
     * user into a tenant-wide reader.</p>
     *
     * <p>Always carries the real {@code userId}: {@code DataScope.system(tenantId)} is for internal
     * calls only, and using it here would stamp {@code reporter_id = 0} on everything a logged-in
     * user creates.</p>
     */
    public DataScope scope() {
        if (isAdmin()) {
            return DataScope.of(tenantId, ScopeTier.TENANT, Set.of(), null, userId, Set.of());
        }
        if (vendorId != null) {
            return DataScope.of(tenantId, ScopeTier.VENDOR, domainIds, vendorId, userId,
                    orgSubtreeIds);
        }
        if (roles.stream().anyMatch(BIZ_ROLES::contains)) {
            return DataScope.of(tenantId, ScopeTier.BIZ, Set.of(), null, userId, orgSubtreeIds);
        }
        if (roles.stream().anyMatch(SELF_ROLES::contains)) {
            return DataScope.of(tenantId, ScopeTier.SELF, Set.of(), null, userId, Set.of());
        }
        if (!domainIds.isEmpty()) {
            return DataScope.of(tenantId, ScopeTier.DOMAIN, domainIds, null, userId, orgSubtreeIds);
        }
        if (!orgSubtreeIds.isEmpty()) {
            return DataScope.of(tenantId, ScopeTier.ORG, Set.of(), null, userId, orgSubtreeIds);
        }
        // No authorization at all: fail closed (an empty domain set resolves to "1 = 0").
        return DataScope.of(tenantId, ScopeTier.DOMAIN, Set.of(), null, userId, Set.of());
    }

    public TenantContext.Principal principal() {
        return new TenantContext.Principal(userId, username, name, tenantCode, tenantName,
                roles, domainIds, vendorId);
    }

    public Set<String> roleSet() {
        return new LinkedHashSet<>(roles);
    }
}
