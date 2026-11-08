package com.example.platform.common.scope;

import java.util.Map;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import org.springframework.stereotype.Component;

/**
 * Visibility/writability check for rows loaded by id.
 *
 * <p>A detail endpoint that filters by id alone is the classic isolation hole: the list is scoped,
 * the detail is not. Every such read goes through {@link #requireVisible(ScopeAware)} so the
 * decision (and the 403 + audit) exists once. Cross-tenant rows are reported as 404 — telling a
 * caller "this exists but belongs to another tenant" is itself a leak.</p>
 */
@Component
public class ScopeGuard {

    public <T extends ScopeAware> T requireVisible(T row) {
        check(row, false);
        return row;
    }

    public <T extends ScopeAware> T requireWritable(T row) {
        check(row, true);
        return row;
    }

    private <T extends ScopeAware> void check(T row, boolean write) {
        if (row == null) {
            throw new ApiException(404, "NOT_FOUND", "资源不存在或不可见", Map.of());
        }
        DataScope scope = TenantContext.require();
        if (row.tenantId() != scope.tenantId()) {
            throw notFound();
        }
        boolean domainTier = scope.tier() != ScopeTier.TENANT
                && scope.tier() != ScopeTier.BIZ
                && scope.tier() != ScopeTier.SELF;
        if (row.domainId() != null && domainTier && !scope.canSeeDomain(row.domainId())) {
            throw outOfScope(write);
        }
        if (row.vendorId() != null && !scope.canSeeVendor(row.vendorId())) {
            throw outOfScope(write);
        }
        if (scope.tier() == ScopeTier.ORG || scope.tier() == ScopeTier.BIZ) {
            if (row.originOrgId() != null && !scope.orgSubtreeIds().contains(row.originOrgId())) {
                throw outOfScope(write);
            }
            if (scope.orgSubtreeIds().isEmpty()) {
                // fail-closed: an org-scoped caller without a subtree sees nothing
                throw outOfScope(write);
            }
        }
        if (scope.tier() == ScopeTier.SELF) {
            Long owner = row.ownerUserId();
            if (owner == null || owner != scope.userId()) {
                throw outOfScope(write);
            }
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "资源不存在或不可见", Map.of());
    }

    private static ApiException outOfScope(boolean write) {
        return new ApiException(403, "OUT_OF_SCOPE",
                write ? "无权修改该资源" : "无权访问该资源", Map.of());
    }
}
