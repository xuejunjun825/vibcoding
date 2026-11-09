package com.example.req.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.example.platform.common.security.PermissionRouteTable;
import org.junit.jupiter.api.Test;

/**
 * The path → function mapping is the single decision point for both services (no controller
 * declares its own annotation), so each row the acceptance criteria touch is pinned here.
 */
class PermissionRouteTableTest {

    @Test
    void tenantManagementIsAdminOnly() {
        PermissionRouteTable.Requirement list = PermissionRouteTable.requirementFor("GET", "/admin/tenants");
        assertNotNull(list, "GET /admin/tenants must be guarded — the tenant list is not public data");
        assertEquals(List.of("tenant:manage"), list.codes());
        PermissionRouteTable.Requirement create = PermissionRouteTable.requirementFor("POST", "/admin/tenants");
        assertEquals(List.of("tenant:manage"), create.codes());
        assertFalse(create.anyOf());
    }

    @Test
    void orgSurfaceIsOneFunction() {
        assertEquals(List.of("org:manage"),
                PermissionRouteTable.requirementFor("PATCH", "/admin/users/7/status").codes());
        assertEquals(List.of("org:manage"),
                PermissionRouteTable.requirementFor("GET", "/admin/vendors").codes());
    }

    @Test
    void requirementReportingAcceptsEitherReporterFunction() {
        PermissionRouteTable.Requirement create = PermissionRouteTable.requirementFor("POST", "/requirements");
        assertTrue(create.anyOf(), "a BUS reporter and a PDR reporter both pass");
        assertEquals(List.of("req:report:bus", "req:report:dev"), create.codes());
        assertEquals(List.of("req:split"),
                PermissionRouteTable.requirementFor("POST", "/requirements/12/decompose").codes());
        assertEquals(List.of("test:quick"),
                PermissionRouteTable.requirementFor("POST", "/requirements/12/quick-test").codes());
    }

    @Test
    void deliverySurface() {
        assertEquals(List.of("deploy:force"),
                PermissionRouteTable.requirementFor("POST", "/deploy-orders/3/force-release").codes());
        assertEquals(List.of("deploy:execute"),
                PermissionRouteTable.requirementFor("POST", "/deploy-orders/3/execution").codes());
        assertEquals(List.of("defect:handle"),
                PermissionRouteTable.requirementFor("POST", "/defects/5/actions").codes());
        assertEquals(List.of("case:manage"),
                PermissionRouteTable.requirementFor("GET", "/testcases").codes());
        assertEquals(List.of("audit:view"),
                PermissionRouteTable.requirementFor("GET", "/admin/audit").codes());
    }

    @Test
    void readPathsAreLeftToDataScope() {
        assertNull(PermissionRouteTable.requirementFor("GET", "/requirements"),
                "listing is filtered by scope, not denied by function");
        assertNull(PermissionRouteTable.requirementFor("GET", "/auth/me"));
        assertNull(PermissionRouteTable.requirementFor("GET", "/admin/permissions"));
    }

    @Test
    void everyExportIsGuardedAsDetail() {
        assertEquals(List.of("export:detail"),
                PermissionRouteTable.requirementFor("GET", "/requirements/export.csv").codes());
        assertEquals(List.of("export:detail"),
                PermissionRouteTable.requirementFor("GET", "/defects/export.csv").codes());
    }

    /**
     * AC-5 of r3: the quality rules only work because they sit above the release / plan fallbacks.
     * Each assertion below would return the fallback code (plan:manage / release:plan) if a rule
     * were appended at the bottom of the table instead of inserted before them.
     */
    @Test
    void qualityActionsHitTheirOwnCodeBeforeTheFallbacks() {
        assertEquals(List.of("plan:approve"),
                PermissionRouteTable.requirementFor("POST", "/test-plans/12/approve").codes());
        assertEquals(List.of("plan:edit"),
                PermissionRouteTable.requirementFor("POST", "/test-plans/12/submit-review").codes());
        assertEquals(List.of("plan:edit"),
                PermissionRouteTable.requirementFor("POST", "/test-plans/12/archive").codes());
        assertEquals(List.of("plan:assign"),
                PermissionRouteTable.requirementFor("PUT", "/test-plans/12/assignments").codes());
        assertEquals(List.of("plan:execute"),
                PermissionRouteTable.requirementFor("POST", "/test-plans/12/execute").codes());
        // The plan's own fallback still guards everything else, including reads.
        assertEquals(List.of("plan:manage"),
                PermissionRouteTable.requirementFor("GET", "/test-plans").codes());
        assertEquals(List.of("plan:manage"),
                PermissionRouteTable.requirementFor("GET", "/test-plans/12").codes());

        assertEquals(List.of("report:sign"),
                PermissionRouteTable.requirementFor("POST", "/test-reports/3/versions/2/signoff").codes());
        assertEquals(List.of("report:export"),
                PermissionRouteTable.requirementFor("GET", "/test-reports/3/export").codes());
        assertEquals(List.of("report:generate"),
                PermissionRouteTable.requirementFor("POST", "/test-reports").codes());

        assertEquals(List.of("waiver:apply"),
                PermissionRouteTable.requirementFor("POST", "/quality-gates/waivers").codes());
        assertEquals(List.of("waiver:approve"),
                PermissionRouteTable.requirementFor("POST", "/quality-gates/waivers/9/approve").codes());
        assertEquals(List.of("waiver:approve"),
                PermissionRouteTable.requirementFor("POST", "/quality-gates/waivers/9/revoke").codes());
        assertEquals(List.of("gate:view"),
                PermissionRouteTable.requirementFor("GET", "/quality-gates/9").codes());
        assertEquals(List.of("plan:manage"),
                PermissionRouteTable.requirementFor("PUT", "/quality-gates").codes());
        assertEquals(List.of("plan:manage"),
                PermissionRouteTable.requirementFor("PUT", "/releases/4/quality-gate").codes());

        // Release-scoped quality routes must win over the blanket ^/releases fallback.
        assertEquals(List.of("gate:view"),
                PermissionRouteTable.requirementFor("GET", "/releases/4/quality-gate").codes());
        assertEquals(List.of("qualitymetric:view"),
                PermissionRouteTable.requirementFor("GET", "/releases/4/quality").codes());
        assertEquals(List.of("review:manage"),
                PermissionRouteTable.requirementFor("POST", "/releases/4/reviews").codes());
        assertEquals(List.of("review:manage"),
                PermissionRouteTable.requirementFor("PUT", "/releases/4/quality-settings").codes());
        assertEquals(List.of("release:plan"),
                PermissionRouteTable.requirementFor("GET", "/releases/4").codes());

        assertEquals(List.of("qualitymetric:view"),
                PermissionRouteTable.requirementFor("GET", "/metrics/quality").codes());
        assertEquals(List.of("strategy:manage"),
                PermissionRouteTable.requirementFor("GET", "/test-strategies").codes());
        assertEquals(List.of("env:manage"),
                PermissionRouteTable.requirementFor("POST", "/test-envs").codes());
        assertNull(PermissionRouteTable.requirementFor("GET", "/test-envs"),
                "environment reads stay open (scope-filtered), only mutations are guarded");
        assertNull(PermissionRouteTable.requirementFor("GET", "/test-reports/3"),
                "report reads stay open (scope-filtered), only generation is guarded");
    }

    /**
     * M7: the integration admin surface is one function, and the two artifact writes are release
     * actions. Before these rows existed the paths fell through to {@code null} (= any authenticated
     * account could rewrite the Jenkins base URL or promote an artifact to the production repo).
     */
    @Test
    void integrationSurface() {
        assertEquals(List.of("integration:manage"),
                PermissionRouteTable.requirementFor("POST", "/integration/configs").codes());
        assertEquals(List.of("integration:manage"),
                PermissionRouteTable.requirementFor("PATCH", "/integration/configs/7").codes());
        assertEquals(List.of("integration:manage"),
                PermissionRouteTable.requirementFor("POST", "/integration/configs/7/test").codes());
        assertEquals(List.of("integration:manage"),
                PermissionRouteTable.requirementFor("GET", "/integration/logs").codes());
        assertEquals(List.of("integration:manage"),
                PermissionRouteTable.requirementFor("PUT", "/integration/admission-items").codes());
        assertEquals(List.of("deploy:create"),
                PermissionRouteTable.requirementFor("POST", "/artifacts/9/promote").codes());
        // Reads the deploy page needs stay open; the webhook has no JWT at all and is verified by
        // its signature, so it must not appear in this table.
        assertNull(PermissionRouteTable.requirementFor("GET", "/integration/artifacts/search"));
        assertNull(PermissionRouteTable.requirementFor("POST", "/integration/webhooks/gitlab/3"));
    }

    /** First match wins: an approve path must never be reachable through the edit rule. */
    @Test
    void approveIsNotShadowedByTheLifecycleRule() {
        PermissionRouteTable.Requirement approve =
                PermissionRouteTable.requirementFor("POST", "/test-plans/7/approve");
        assertNotNull(approve);
        assertFalse(approve.codes().contains("plan:edit"),
                "the approve rule must precede the submit-review|reject|… rule");
    }
}
