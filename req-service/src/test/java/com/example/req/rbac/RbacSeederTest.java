package com.example.req.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.platform.contract.ActionRegistry;
import com.example.platform.contract.MenuRegistry;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.PermissionMatrix.Access;
import com.example.req.seed.RbacSeeder;
import org.junit.jupiter.api.Test;

/**
 * The preset grid is the one place all three write paths agree on (startup bootstrap, tenant
 * provisioning, demo reset), so it is asserted here without a database: 168 rows per built-in role,
 * the row set being exactly the registries, and the four pages that bind no function code staying
 * {@code ALLOW}. {@link RbacSeedEquivalenceTest} checks the derivation against the matrix cell by
 * cell; this test checks the shape the write path depends on.
 */
class RbacSeederTest {

    /** Pages that bind no function code: their MENU layer must be ALLOW for every role. */
    private static final List<String> UNBOUND_PAGES =
            List.of("me.todo", "me.tasks", "req.pool", "defect.list");

    @Test
    void presetHoldsOneHundredSixtyEightRowsPerRole() {
        int functions = PermissionMatrix.functions().size();
        int pages = MenuRegistry.pages().size();
        int actions = ActionRegistry.actions().size();
        assertEquals(60, functions, "FUNCTION layer is the 60-cell matrix row");
        assertEquals(36, pages, "MENU layer is one row per registered page");
        assertEquals(72, actions, "ACTION layer is one row per registered action");

        for (String role : PermissionMatrix.roles()) {
            List<RbacSeeder.Row> rows = RbacSeeder.preset(role);
            assertEquals(168, rows.size(), "role " + role);
            assertEquals(functions, count(rows, "FUNCTION"), "function rows of " + role);
            assertEquals(pages, count(rows, "MENU"), "menu rows of " + role);
            assertEquals(actions, count(rows, "ACTION"), "action rows of " + role);
        }
    }

    @Test
    void presetCoversEveryRegistrySubjectExactlyOnce() {
        for (String role : PermissionMatrix.roles()) {
            List<RbacSeeder.Row> rows = RbacSeeder.preset(role);
            Set<String> keys = new LinkedHashSet<>();
            for (RbacSeeder.Row row : rows) {
                assertTrue(keys.add(row.subjectType() + "|" + row.code()),
                        "duplicate subject " + row.subjectType() + "|" + row.code() + " for " + role);
                assertTrue(Set.of("ALLOW", "RESTRICTED", "DENY").contains(row.access()),
                        "unexpected access " + row.access());
            }
            for (MenuRegistry.MenuPage page : MenuRegistry.pages()) {
                assertTrue(keys.contains("MENU|" + page.code()), "missing page " + page.code());
            }
            for (ActionRegistry.ActionDef action : ActionRegistry.actions()) {
                assertTrue(keys.contains("ACTION|" + action.code()), "missing action " + action.code());
            }
        }
    }

    @Test
    void unboundPagesStayAllowAndDenyIsStoredAsARow() {
        for (String role : PermissionMatrix.roles()) {
            List<RbacSeeder.Row> rows = RbacSeeder.preset(role);
            for (String pageCode : UNBOUND_PAGES) {
                assertEquals("ALLOW", accessOf(rows, "MENU", pageCode),
                        "page " + pageCode + " binds no function code, so it stays visible");
            }
        }
        // PLATFORM_ADMIN is all-ALLOW by design; restricted roles are where DENY appears. What
        // matters is that DENY is never expressed by omitting the row: every role still gets its
        // full 168, and a denied cell is a row carrying 'DENY'.
        for (String role : PermissionMatrix.roles()) {
            List<RbacSeeder.Row> rows = RbacSeeder.preset(role);
            assertEquals(168, rows.size(), "DENY rows are written, not skipped (role " + role + ")");
        }
        assertTrue(RbacSeeder.preset("QA").stream().anyMatch(row -> row.access().equals("DENY")),
                "QA must carry at least one explicit DENY row");
    }

    @Test
    void presetMatchesTheMatrixAndThePageMax() {
        RbacSeeder.Row function = single(RbacSeeder.preset("QA"), "FUNCTION", "plan:manage");
        assertEquals(PermissionMatrix.accessFor("QA", "plan:manage").name(),
                function.access(), "FUNCTION row is the matrix cell");

        MenuRegistry.MenuPage page = MenuRegistry.pages().stream()
                .filter(candidate -> !candidate.functionCodes().isEmpty())
                .findFirst().orElseThrow();
        assertEquals(PermissionMatrix.accessForAny("QA", page.functionCodes()).name(),
                single(RbacSeeder.preset("QA"), "MENU", page.code()).access(),
                "MENU row is the max over the page's function codes");

        ActionRegistry.ActionDef action = ActionRegistry.actions().stream()
                .filter(candidate -> !candidate.functionCodes().isEmpty())
                .findFirst().orElseThrow();
        assertEquals(PermissionMatrix.accessForAny("QA", action.functionCodes()).name(),
                single(RbacSeeder.preset("QA"), "ACTION", action.code()).access(),
                "ACTION row is the max over the action's function codes");
    }

    @Test
    void highRiskActionsAreTheRegistrySet() {
        Set<String> highRisk = new LinkedHashSet<>();
        for (ActionRegistry.ActionDef action : ActionRegistry.actions()) {
            if (action.highRisk()) {
                highRisk.add(action.code());
            }
        }
        assertEquals(ActionRegistry.HIGH_RISK, highRisk,
                "sys_action.high_risk is derived from this set; a mismatch would gate the wrong actions");
    }

    private static long count(List<RbacSeeder.Row> rows, String subjectType) {
        return rows.stream().filter(row -> row.subjectType().equals(subjectType)).count();
    }

    private static RbacSeeder.Row single(List<RbacSeeder.Row> rows, String type, String code) {
        return rows.stream()
                .filter(row -> row.subjectType().equals(type) && row.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " row for " + code));
    }

    private static String accessOf(List<RbacSeeder.Row> rows, String type, String code) {
        return single(rows, type, code).access();
    }
}
