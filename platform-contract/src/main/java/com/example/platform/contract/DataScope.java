package com.example.platform.contract;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Request-scoped data visibility: tenant + tier + the ids that tier allows.
 *
 * <p>This is the <b>only</b> structure that describes isolation. {@code ScopeSql} turns it
 * into SQL, {@code ScopeGuard} uses it for id-based reads and writes, and the gateway only
 * checks that the tenant header agrees with the token. Nothing else may re-implement
 * "only my domains / only my vendor".</p>
 *
 * <p>Instances are immutable; collections are defensively copied and never {@code null}
 * (an empty set means "no restriction of that kind", which is why the tier still matters).</p>
 */
public record DataScope(long tenantId,
                        ScopeTier tier,
                        Set<Long> domainIds,
                        Long vendorId,
                        long userId,
                        Set<Long> orgSubtreeIds) {

    public DataScope {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (tier == null) {
            throw new IllegalArgumentException("tier is required");
        }
        domainIds = copy(domainIds);
        orgSubtreeIds = copy(orgSubtreeIds);
    }

    /** Administrator / internal-call scope: the whole tenant, no dimension restriction. */
    public static DataScope system(long tenantId) {
        return new DataScope(tenantId, ScopeTier.TENANT, Set.of(), null, 0L, Set.of());
    }

    /** Scope built by the auth layer from the caller's roles and authorization domains. */
    public static DataScope of(long tenantId, ScopeTier tier, Set<Long> domainIds, Long vendorId,
                               long userId, Set<Long> orgSubtreeIds) {
        return new DataScope(tenantId, tier, domainIds, vendorId, userId, orgSubtreeIds);
    }

    /**
     * Whether a row carrying {@code rowVendorId} is visible.
     *
     * <p>Only the vendor dimension is decided here; domains and organization subtrees need
     * row columns and are rendered by {@code ScopeSql}. A {@code null} row vendor means the
     * row is not vendor-owned (internal requirement), so it stays visible.</p>
     */
    public boolean canSeeVendor(Long rowVendorId) {
        if (tier != ScopeTier.VENDOR) {
            return true;
        }
        if (rowVendorId == null) {
            return true;
        }
        return vendorId != null && vendorId.equals(rowVendorId);
    }

    /** True when the caller may see every vendor (not a vendor-restricted scope). */
    public boolean seesAllVendors() {
        return tier != ScopeTier.VENDOR;
    }

    /** True when the caller may see the given business domain. */
    public boolean canSeeDomain(long rowDomainId) {
        if (tier == ScopeTier.TENANT) {
            return true;
        }
        return domainIds.contains(rowDomainId);
    }

    private static Set<Long> copy(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<Long> copy = new LinkedHashSet<>(values);
        copy.remove(null);
        return Collections.unmodifiableSet(copy);
    }
}
