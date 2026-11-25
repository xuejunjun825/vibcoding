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

    /**
     * 与 {@code RequirementDomainValidator.outOfScope} 同口径的域可见性：TENANT 档或 {@code domainIds} 空集
     * 都表示「该维度不限制」（见类头注释的契约），只有非空集合才逐域判定。
     *
     * <p>为什么不能直接用 {@link #canSeeDomain(long)}：那个实现把空集当作「一个域都看不见」，
     * 与契约相反。ORG 档用户（域由组织派生、{@code domainIds} 为空）在列表侧可见、详情侧却 403，
     * 就是这两套语义并存导致的（r46 的 PDR 详情回归）。新代码一律用本方法；{@code canSeeDomain}
     * 保留给已经按「空集=不可见」写死断言的既有调用点。</p>
     */
    public boolean canSeeDomainOrUnrestricted(long rowDomainId) {
        if (tier == ScopeTier.TENANT) {
            return true;
        }
        if (domainIds == null || domainIds.isEmpty()) {
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
