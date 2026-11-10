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
    }

    /**
     * r7: the option dictionaries are authenticated-only <b>on purpose</b>. They are what the
     * non-admin pages (requirement pool, release plan, approval centre, test plans, code policy)
     * read for their pickers; guarding them with a function code is exactly the 403 that broke
     * those pages, because a pm/dev has no {@code org:manage}. Pinned here so a future rule cannot
     * silently undo the fix — while {@code /admin/*} keeps its code, because the dictionaries do
     * not replace the management surface.
     */
    @Test
    void dictionaryOptionsStayAuthenticatedOnly() {
        assertNull(PermissionRouteTable.requirementFor("GET", "/dictionaries/domains"),
                "a rule on /dictionaries/domains is the r7 regression: pickers would 403 again");
        assertNull(PermissionRouteTable.requirementFor("GET", "/dictionaries/vendors"),
                "vendor options are read by deliver/quality pages without org:manage");
        assertNull(PermissionRouteTable.requirementFor("GET", "/dictionaries/users"),
                "assignee options are read by every page with a person picker");
        assertEquals(List.of("org:manage"),
                PermissionRouteTable.requirementFor("GET", "/admin/domains").codes(),
                "the admin surface keeps its code; the dictionaries do not widen it");
    }

    @Test
    void permissionMatrixIsAdminOnly() {
        // The matrix is a read-only projection, but it exposes the whole role × function model, so
        // "management page" means guarded like the rest of /admin (AC-17). The web nav uses the
        // same code to hide the entry instead of letting a non-admin click into a 403.
        assertEquals(List.of("org:manage"),
                PermissionRouteTable.requirementFor("GET", "/admin/permissions").codes());
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

    /**
     * M8: each collaboration/code/test surface is pinned above its own broad read rule. A rule
     * appended at the bottom of the table would be shadowed by {@code ^/wiki}, {@code ^/gantt},
     * {@code ^/flow} or the {@code ^/(testcases|test-modules)} fallback, and a write would be
     * charged only the read code — the classic "the button is visible, so the action must be
     * allowed" bug.
     */
    @Test
    void collaborationSurfacesHitTheirOwnCode() {
        // Gantt: one write entry point (PATCH rows), everything else reads the board.
        assertEquals(List.of("GANTT_EDIT"),
                PermissionRouteTable.requirementFor("PATCH", "/gantt/rows/12").codes());
        assertEquals(List.of("GANTT_VIEW"),
                PermissionRouteTable.requirementFor("GET", "/gantt").codes());
        assertEquals(List.of("GANTT_VIEW"),
                PermissionRouteTable.requirementFor("GET", "/gantt/rows/12").codes());

        // Wiki: structure writes are WIKI_ADMIN, page writes WIKI_EDIT, reads WIKI_VIEW.
        assertEquals(List.of("WIKI_ADMIN"),
                PermissionRouteTable.requirementFor("POST", "/wiki/spaces").codes());
        assertEquals(List.of("WIKI_ADMIN"),
                PermissionRouteTable.requirementFor("PUT", "/wiki/spaces/3").codes());
        assertEquals(List.of("WIKI_ADMIN"),
                PermissionRouteTable.requirementFor("DELETE", "/wiki/pages/9/purge").codes());
        assertEquals(List.of("WIKI_EDIT"),
                PermissionRouteTable.requirementFor("POST", "/wiki/pages").codes());
        assertEquals(List.of("WIKI_EDIT"),
                PermissionRouteTable.requirementFor("PUT", "/wiki/pages/9").codes());
        assertEquals(List.of("WIKI_EDIT"),
                PermissionRouteTable.requirementFor("DELETE", "/wiki/pages/9").codes());
        assertEquals(List.of("WIKI_VIEW"),
                PermissionRouteTable.requirementFor("GET", "/wiki/pages/9").codes());
        assertEquals(List.of("WIKI_VIEW"),
                PermissionRouteTable.requirementFor("GET", "/wiki/search").codes());

        // Value stream: configuring the stages changes every metric's caliber.
        assertEquals(List.of("FLOW_CONFIG"),
                PermissionRouteTable.requirementFor("PUT", "/flow/stages").codes());
        assertEquals(List.of("FLOW_VIEW"),
                PermissionRouteTable.requirementFor("GET", "/flow/board").codes());
        assertEquals(List.of("FLOW_VIEW"),
                PermissionRouteTable.requirementFor("GET", "/flow/stages").codes());
        // The dictionary preview of a FLOW_* code must not need metric:manage.
        assertEquals(List.of("FLOW_VIEW"),
                PermissionRouteTable.requirementFor("GET", "/metrics/FLOW_WIP/preview").codes());

        // Branch policy: registering/verifying is an edit, listing is a read.
        assertEquals(List.of("CCODE_POLICY_EDIT"),
                PermissionRouteTable.requirementFor("POST", "/code/policies").codes());
        assertEquals(List.of("CCODE_POLICY_EDIT"),
                PermissionRouteTable.requirementFor("POST", "/code/policies/3/check").codes());
        assertEquals(List.of("CCODE_POLICY_VIEW"),
                PermissionRouteTable.requirementFor("GET", "/code/policies").codes());
        assertEquals(List.of("CCODE_POLICY_VIEW"),
                PermissionRouteTable.requirementFor("GET", "/code/policies/3/checks").codes());

        // Case reviews: both entry points are gated by the read code, and neither falls through to
        // the case:manage fallback (which would let any case editor decide a review).
        assertEquals(List.of("CASE_REVIEW_VIEW"),
                PermissionRouteTable.requirementFor("GET", "/testcase-reviews").codes());
        assertEquals(List.of("CASE_REVIEW_VIEW"),
                PermissionRouteTable.requirementFor("POST", "/testcase-reviews/3/decide").codes());
        assertEquals(List.of("CASE_REVIEW_VIEW"),
                PermissionRouteTable.requirementFor("GET", "/testcases/7/reviews").codes());
        assertEquals(List.of("CASE_REVIEW_VIEW"),
                PermissionRouteTable.requirementFor("POST", "/testcases/7/reviews").codes());
        // The pre-existing case surface is unchanged.
        assertEquals(List.of("case:manage"),
                PermissionRouteTable.requirementFor("GET", "/testcases").codes());

        // Executors: the ledger is managed, the health board is read.
        assertEquals(List.of("TEST_EXECUTOR_MANAGE"),
                PermissionRouteTable.requirementFor("POST", "/test-executors").codes());
        assertEquals(List.of("TEST_EXECUTOR_MANAGE"),
                PermissionRouteTable.requirementFor("PUT", "/test-executors/2").codes());
        assertEquals(List.of("TEST_EXECUTOR_VIEW"),
                PermissionRouteTable.requirementFor("GET", "/test-executors").codes());
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
