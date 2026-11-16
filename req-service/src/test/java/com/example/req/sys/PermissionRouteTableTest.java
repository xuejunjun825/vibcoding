package com.example.req.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.example.platform.common.security.PermissionRouteTable;
import com.example.platform.contract.ActionRegistry;
import com.example.platform.contract.MenuRegistry;
import org.junit.jupiter.api.Test;

/**
 * The route table is the shared declaration for both services, so every shape the r18 contract
 * depends on is pinned here: a write endpoint names a page <em>and</em> an action, the two
 * {@code /actions} endpoints take their verb from the body, and the read surfaces stay page-only.
 */
class PermissionRouteTableTest {

    @Test
    void tenantAndOrgSurfacesArePageGuarded() {
        PermissionRouteTable.Requirement list =
                PermissionRouteTable.requirementFor("GET", "/admin/tenants");
        assertNotNull(list, "the tenant list is not public data");
        assertEquals(List.of("admin.orgs"), list.pages());
        assertNull(list.action(), "reads are guarded by the page alone");

        assertEquals(List.of("admin.orgs"),
                PermissionRouteTable.requirementFor("PATCH", "/admin/orgs/7").pages());
        assertEquals(List.of("admin.users"),
                PermissionRouteTable.requirementFor("GET", "/admin/users").pages());
        assertEquals(List.of("admin.vendors"),
                PermissionRouteTable.requirementFor("GET", "/admin/vendors").pages());
    }

    @Test
    void requirementWritesCarryAnAction() {
        PermissionRouteTable.Requirement create =
                PermissionRouteTable.requirementFor("POST", "/requirements");
        assertEquals(List.of("req.pool"), create.pages());
        assertEquals("req.action.submit", create.action());

        assertEquals("req.action.decompose",
                PermissionRouteTable.requirementFor("POST", "/requirements/5/decompose").action());
        assertEquals("req.action.deliver",
                PermissionRouteTable.requirementFor("POST", "/requirements/5/deliver").action());
        assertEquals("defect.action.create",
                PermissionRouteTable.requirementFor("POST", "/defects").action());
    }

    @Test
    void actionEndpointsTakeTheirVerbFromTheBody() {
        PermissionRouteTable.Requirement requirement =
                PermissionRouteTable.requirementFor("POST", "/requirements/12/actions");
        assertNotNull(requirement, "r18 guards the per-action endpoint instead of leaving it open");
        assertTrue(requirement.fromBody());
        assertEquals("req.pool", requirement.pages().get(0));
        assertEquals("req.action.", requirement.bodyPrefix());

        PermissionRouteTable.Requirement defect =
                PermissionRouteTable.requirementFor("POST", "/defects/9/actions");
        assertTrue(defect.fromBody());
        assertEquals("defect.list", defect.pages().get(0));
        assertEquals("defect.action.", defect.bodyPrefix());
    }

    @Test
    void roleAndUserAdministrationUseDistinctActions() {
        assertEquals("role.action.create",
                PermissionRouteTable.requirementFor("POST", "/admin/roles").action());
        assertEquals("role.action.status",
                PermissionRouteTable.requirementFor("PATCH", "/admin/roles/3/status").action());
        assertEquals("role.action.grant",
                PermissionRouteTable.requirementFor("PUT", "/admin/roles/3/permissions").action());
        assertEquals("role.action.update",
                PermissionRouteTable.requirementFor("DELETE", "/admin/roles/3").action());

        assertEquals("user.action.create",
                PermissionRouteTable.requirementFor("POST", "/admin/users").action());
        assertEquals("user.action.disable",
                PermissionRouteTable.requirementFor("PATCH", "/admin/users/7/status").action());
        assertEquals("user.action.reset_password",
                PermissionRouteTable.requirementFor("POST", "/admin/users/7/reset-password").action());
        assertEquals("org.action.move_users",
                PermissionRouteTable.requirementFor("POST", "/admin/orgs/4/move-users").action());
        assertEquals("org.action.delete",
                PermissionRouteTable.requirementFor("DELETE", "/admin/orgs/4").action());
    }

    @Test
    void deployAndGateWritesUseTheirHighRiskActions() {
        assertEquals("deploy.action.force_release",
                PermissionRouteTable.requirementFor("POST", "/deploy-orders/9/force-release").action());
        assertEquals("deploy.action.execute",
                PermissionRouteTable.requirementFor("POST", "/deploy-orders/9/execution").action());
        assertEquals("deploy.action.create",
                PermissionRouteTable.requirementFor("POST", "/artifacts/3/promote").action());
        assertEquals("test.action.gate_waive",
                PermissionRouteTable.requirementFor("POST", "/quality-gates/waivers/2/approve").action());
        assertEquals("report.action.export",
                PermissionRouteTable.requirementFor("GET", "/releases/5/export.csv").action());
    }

    @Test
    void testPlanLifecycleIsSplitBetweenAuthoringAndRunning() {
        assertEquals("test.action.plan_approve",
                PermissionRouteTable.requirementFor("POST", "/test-plans/5/approve").action());
        assertEquals("test.action.plan_create",
                PermissionRouteTable.requirementFor("POST", "/test-plans/5/submit-review").action());
        assertEquals("test.action.execute",
                PermissionRouteTable.requirementFor("POST", "/test-plans/5/start").action());
        assertEquals("test.action.execute",
                PermissionRouteTable.requirementFor("POST", "/test-plans/5/rounds").action());
    }

    @Test
    void collaborationAndAnalyticsWritesAreGuarded() {
        assertEquals("wiki.action.admin",
                PermissionRouteTable.requirementFor("POST", "/wiki/spaces").action());
        assertEquals("wiki.action.edit",
                PermissionRouteTable.requirementFor("POST", "/wiki/pages").action());
        assertEquals("gantt.action.edit",
                PermissionRouteTable.requirementFor("PATCH", "/gantt/rows/req/5").action());
        assertEquals("flow.action.config",
                PermissionRouteTable.requirementFor("PUT", "/flow/stages").action());
        assertEquals("portal.action.config",
                PermissionRouteTable.requirementFor("PUT", "/portal/config").action());
    }

    @Test
    void internalAndHealthPathsAreNotGuardedByTheTable() {
        assertNull(PermissionRouteTable.requirementFor("GET", "/health"));
        assertNull(PermissionRouteTable.requirementFor("POST", "/internal/seed"));
        assertNull(PermissionRouteTable.requirementFor("GET", "/actuator/health"));
        assertNull(PermissionRouteTable.requirementFor("POST", "/v1/test-executions"));
    }

    @Test
    void everyRuleNamesARegisteredPageAndAConsistentAction() {
        assertFalse(PermissionRouteTable.guardedPatterns().isEmpty());
        for (String guarded : PermissionRouteTable.guardedPatterns()) {
            int space = guarded.indexOf(' ');
            String method = guarded.substring(0, space);
            String pattern = guarded.substring(space + 1);
            String path = samplePath(pattern);
            PermissionRouteTable.Requirement requirement =
                    PermissionRouteTable.requirementFor("*".equals(method) ? "GET" : method, path);
            assertNotNull(requirement, "guarded pattern " + guarded + " must resolve to a rule ("
                    + path + ")");
            for (String page : requirement.pages()) {
                assertTrue(MenuRegistry.isRegistered(page),
                        guarded + " names unregistered page " + page);
            }
            if (requirement.action() != null) {
                assertTrue(ActionRegistry.isRegistered(requirement.action()),
                        guarded + " names unregistered action " + requirement.action());
                assertTrue(requirement.pages()
                                .contains(ActionRegistry.require(requirement.action()).menuCode()),
                        guarded + " action " + requirement.action() + " belongs to another page");
            }
        }
    }

    /** Collapse a rule regex into one path it matches, so the sweep exercises every rule. */
    private static String samplePath(String pattern) {
        String path = pattern.replace("^", "").replace("$", "");
        path = path.replace("\\d+", "1").replace("\\d", "1");
        path = path.replace("\\.", ".");
        path = path.replaceFirst("\\(([^|()]*)\\|[^()]*\\)", "$1");
        path = path.replace(".*", "").replace(".+", "5");
        return path;
    }
}
