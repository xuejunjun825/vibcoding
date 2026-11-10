package com.example.deliver.support;

import java.util.Set;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;

/**
 * Shared-report visibility: the five sharing tiers of a report <b>definition</b>.
 *
 * <p>This is not {@link Visibility}. {@code Visibility} answers "which data rows may this request
 * see" and is rendered by {@code ScopedJdbc}; this answers "may this request see this report
 * entry at all". Sharing a report shares the definition only — every viewer still runs the
 * definition under their own row scope, so a shared report can never leak another vendor's
 * numbers (AC-4).</p>
 *
 * <p><b>An administrator gets no bypass here.</b> The owner sees their own report; anyone else
 * needs a matching tier. That is what makes a private report of another user a 404 for the admin
 * too (AC-8) — admin privilege is about card-config governance, not about reading someone's
 * private query. The {@code admin} flag is carried for callers that need to describe the viewer,
 * and {@link #visible} deliberately never reads it.</p>
 *
 * <p>The package stays free of {@code analytics} dependencies: the role ids are resolved by the
 * caller ({@code AnalyticsDirectory}) and handed in, keeping the dependency direction
 * {@code controller → portal/reports → analytics → support} acyclic.</p>
 */
public final class ShareVisibility {

    public static final String PRIVATE = "PRIVATE";
    public static final String ROLE = "ROLE";
    public static final String DOMAIN = "DOMAIN";
    public static final String VENDOR = "VENDOR";
    public static final String PUBLIC = "PUBLIC";

    private ShareVisibility() {
    }

    /** The asking identity, flattened once per request so the predicate stays a pure function. */
    public record Viewer(long userId, long tenantId, Set<Long> roleIds, Set<Long> domainIds,
                         Long vendorId, boolean admin) {

        public Viewer {
            roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
            domainIds = domainIds == null ? Set.of() : Set.copyOf(domainIds);
        }
    }

    /** Build the viewer from the request identity plus the role ids the directory resolved. */
    public static Viewer viewer(Set<Long> roleIds) {
        TenantContext.Principal principal = TenantContext.principal();
        DataScope scope = TenantContext.require();
        long userId = principal != null ? principal.userId() : scope.userId();
        return new Viewer(userId, scope.tenantId(),
                roleIds, scope.domainIds(), scope.vendorId(), isAdmin());
    }

    public static boolean isAdmin() {
        return TenantContext.roles().contains("PLATFORM_ADMIN");
    }

    /**
     * Whether {@code viewer} may see a report owned by {@code ownerId} with the given tier.
     *
     * <p>Owner always sees their own report. {@code scopeRef} is the tier's subject: role id for
     * ROLE, domain id for DOMAIN, vendor id for VENDOR; PRIVATE and PUBLIC carry none. An unknown
     * tier is invisible (fail-closed) — a typo in a stored row must not widen access.</p>
     */
    public static boolean visible(String scope, Long scopeRef, long ownerId, Viewer viewer) {
        if (viewer == null) {
            return false;
        }
        if (ownerId == viewer.userId()) {
            return true;
        }
        String tier = scope == null || scope.isBlank() ? PRIVATE : scope.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (tier) {
            case PRIVATE -> false;
            case ROLE -> scopeRef != null && viewer.roleIds().contains(scopeRef);
            case DOMAIN -> scopeRef != null && viewer.domainIds().contains(scopeRef);
            case VENDOR -> scopeRef != null && scopeRef.equals(viewer.vendorId());
            case PUBLIC -> true;
            default -> false;
        };
    }
}
