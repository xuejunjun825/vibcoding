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
 * provisioning, demo reset), so it is asserted here without a database: 180 rows per built-in role,
 * the row set being exactly the registries, and the four pages that bind no function code staying
 * {@code ALLOW}. {@link RbacSeedEquivalenceTest} checks the derivation against the matrix cell by
 * cell; this test checks the shape the write path depends on.
 */
class RbacSeederTest {

    /** Pages that bind no function code: their MENU layer must be ALLOW for every role. */
    private static final List<String> UNBOUND_PAGES =
            List.of("me.todo", "me.tasks", "req.pool", "defect.list");

    @Test
    void presetHoldsOneHundredSeventyOneRowsPerRole() {
        int functions = PermissionMatrix.functions().size();
        int pages = MenuRegistry.pages().size();
        int actions = ActionRegistry.actions().size();
        assertEquals(61, functions, "FUNCTION layer is the 61-cell matrix row (r39 +1)");
        assertEquals(38, pages, "MENU layer is one row per registered page (r41 +1)");
        assertEquals(81, actions, "ACTION layer is one row per registered action (r41 +6)");

        for (String role : PermissionMatrix.roles()) {
            List<RbacSeeder.Row> rows = RbacSeeder.preset(role);
            assertEquals(180, rows.size(), "role " + role);
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
        // full 180, and a denied cell is a row carrying 'DENY'.
        for (String role : PermissionMatrix.roles()) {
            List<RbacSeeder.Row> rows = RbacSeeder.preset(role);
            assertEquals(180, rows.size(), "DENY rows are written, not skipped (role " + role + ")");
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
        assertEquals(RbacSeeder.actionAccessFor("QA", action).name(),
                single(RbacSeeder.preset("QA"), "ACTION", action.code()).access(),
                "ACTION row goes through actionAccessFor, not a second derivation");
    }

    /**
     * r24: the preset is where the 11 overrides become rows. Asserting through {@code preset("VENDOR_PM")}
     * (not only through the override map) is what proves the seeder actually used them — a preset that
     * still called {@code accessForAny} would pass an override-map assertion and seed the wrong rows.
     */
    @Test
    void vendorPmPresetCarriesTheOverridesAsDenyRows() {
        List<RbacSeeder.Row> rows = RbacSeeder.preset("VENDOR_PM");
        for (String code : List.of("release.action.create", "release.action.update",
                "release.action.approve", "release.action.close",
                "task.action.create", "task.action.assign", "task.action.update",
                "task.action.close", "sprint.action.create", "sprint.action.close",
                "test.action.case_edit",
                // r40：两个变更动作。厂商项目经理不发起、不应用需求变更（11→13）。
                "req.action.change_request", "req.action.change_apply")) {
            assertEquals("DENY", single(rows, "ACTION", code).access(), code + " must seed DENY");
        }
        // The three write actions the role keeps: scheduling is its job, defects are its to handle.
        assertEquals("ALLOW", single(rows, "ACTION", "req.action.schedule").access());
        assertEquals("ALLOW", single(rows, "ACTION", "defect.action.handle").access());
        assertEquals(8, rows.stream()
                        .filter(row -> row.subjectType().equals("ACTION") && row.access().equals("ALLOW"))
                        .count(),
                "8 ACTION rows remain ALLOW (81 − 13 overrides − 60 derivation)");
        // Functional layer is untouched by the override: still the matrix cell.
        assertEquals(PermissionMatrix.accessFor("VENDOR_PM", "release:plan").name(),
                single(rows, "FUNCTION", "release:plan").access(),
                "the override lives on the ACTION layer only");
        assertEquals(180, rows.size(), "the row count never moves: overrides flip a value, not a row");
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
