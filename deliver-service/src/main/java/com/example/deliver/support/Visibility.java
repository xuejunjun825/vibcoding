package com.example.deliver.support;

import java.util.Objects;

import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;

/**
 * Row-level visibility for the delivery half of the platform.
 *
 * <p>Lists get their filtering from {@code ScopeSql}; this class covers the other shape of
 * access — "load by id, then decide" — which a list predicate cannot express. Both answers
 * come from the same {@link DataScope}, so a detail page can never show a row the list would
 * have hidden.</p>
 */
public final class Visibility {

    private Visibility() {
    }

    /** Whether the scope may read a row with these ownership columns. */
    public static boolean canSee(DataScope scope, long tenantId, Long domainId, Long vendorId) {
        if (scope == null || scope.tenantId() != tenantId) {
            return false;
        }
        ScopeTier tier = scope.tier() == null ? ScopeTier.TENANT : scope.tier();
        return switch (tier) {
            case TENANT -> true;
            case DOMAIN -> domainId != null && scope.domainIds().contains(domainId);
            case VENDOR -> vendorId != null && Objects.equals(scope.vendorId(), vendorId);
            // Business-side tiers read summaries only; DefectService.detail() decides that shape.
            // The tenant boundary above is still enforced, so they never leave their tenant.
            case BIZ, SELF -> true;
            case ORG -> true;
        };
    }

    /**
     * Whether the scope may change the row. Same predicate as {@link #canSee} today, kept as a
     * separate entry point because write rules are allowed to tighten without every caller
     * changing.
     */
    public static boolean canWrite(DataScope scope, long tenantId, Long domainId, Long vendorId) {
        return canSee(scope, tenantId, domainId, vendorId);
    }
}
