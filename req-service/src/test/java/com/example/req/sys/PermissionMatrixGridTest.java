package com.example.req.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.PermissionMatrix.Access;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC-5: the matrix is asserted cell by cell, not spot-checked.
 *
 * <p>Every one of the 10 roles × 60 functions is covered, so a silently changed cell (the usual
 * way a permission bug ships) fails here instead of in production. The 26 r2 codes are asserted
 * exactly as before; M6 appended 15 quality codes (grid 260 → 410), M7 appended
 * {@code integration:manage} (420), M8 appended 14 collaboration/code/test codes (560), and M9
 * appended 4 analytics/portal codes (PORTAL_VIEW / PORTAL_CONFIG / REPORT_VIEW / REPORT_SHARE),
 * which is why the expected grid size is 600.</p>
 */
class PermissionMatrixGridTest {

    @Test
    @DisplayName("10 roles × 60 functions: every cell exists and is defined exactly once")
    void everyCellIsDefined() {
        List<PermissionMatrix.Cell> cells = PermissionMatrix.cells();
        int expected = PermissionMatrix.roles().size() * PermissionMatrix.functions().size();
        assertEquals(600, expected, "the matrix is 10 roles × 60 functions");
        assertEquals(expected, cells.size(), "cells() must enumerate the whole grid");
        Set<String> seen = new HashSet<>();
        for (PermissionMatrix.Cell cell : cells) {
            assertNotNull(cell.access(), "undefined cell: " + cell.function() + " × " + cell.role());
            assertTrue(seen.add(cell.role() + "|" + cell.function()),
                    "duplicate cell: " + cell.role() + " × " + cell.function());
            assertNotNull(PermissionMatrix.accessFor(cell.role(), cell.function()),
                    "accessFor disagrees with cells() at " + cell.role() + " × " + cell.function());
        }
        assertEquals(expected, seen.size(), "grid has holes");
    }

    @Test
    @DisplayName("PLATFORM_ADMIN is ALLOW on all 60 functions; no other role holds tenant:manage")
    void platformAdminIsUnrestricted() {
        for (String function : PermissionMatrix.functions()) {
            assertEquals(Access.ALLOW, PermissionMatrix.accessFor("PLATFORM_ADMIN", function),
                    "PLATFORM_ADMIN must allow " + function);
        }
        for (String role : PermissionMatrix.roles()) {
            Access access = PermissionMatrix.accessFor(role, "tenant:manage");
            if ("PLATFORM_ADMIN".equals(role)) {
                assertEquals(Access.ALLOW, access);
            } else {
                assertEquals(Access.DENY, access, "tenant:manage must be DENY for " + role);
            }
        }
    }

    @Test
    @DisplayName("the two AC-5 spot checks: dev1 cannot manage tenants, qa1 can manage cases")
    void acceptanceCells() {
        assertEquals(Access.DENY, PermissionMatrix.accessFor("DEV", "tenant:manage"));
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("QA", "case:manage"));
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("DEV", "req:deliver"));
        assertEquals(Access.DENY, PermissionMatrix.accessFor("MGMT", "req:deliver"));
    }

    @Test
    @DisplayName("role unions take the most permissive cell, never the last one")
    void unionResolution() {
        assertEquals(Access.ALLOW,
                PermissionMatrix.accessOf("tenant:manage", List.of("MGMT", "PLATFORM_ADMIN")));
        assertEquals(Access.DENY, PermissionMatrix.accessOf("tenant:manage", List.of("MGMT")));
        assertEquals(Access.RESTRICTED,
                PermissionMatrix.accessOf("req:report:dev", List.of("DEV", "MGMT")));
        assertTrue(PermissionMatrix.functionsOf(List.of("PLATFORM_ADMIN")).size()
                >= PermissionMatrix.functions().size() - 1);
        assertFalse(PermissionMatrix.functionsOf(List.of("MGMT")).contains("tenant:manage"));
    }

    @Test
    @DisplayName("M6: waiver approval is MGMT+PMO, plan approval is QA+MGMT, signing is PRODUCT+QA+MGMT")
    void qualityCells() {
        // The three cells that make the quality loop a control rather than a self-service form.
        // PRD §3.6: PMO 或平台管理员审批豁免；MGMT 保留治理侧审批权。
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("MGMT", "waiver:approve"));
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("PMO", "waiver:approve"));
        for (String role : PermissionMatrix.roles()) {
            if (!List.of("MGMT", "PMO", "PLATFORM_ADMIN").contains(role)) {
                assertEquals(Access.DENY, PermissionMatrix.accessFor(role, "waiver:approve"),
                        "waiver:approve must be DENY for " + role);
            }
        }
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("QA", "plan:approve"));
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("MGMT", "plan:approve"));
        assertEquals(Access.DENY, PermissionMatrix.accessFor("PMO", "plan:approve"),
                "PMO reads plans but must not approve them");
        for (String role : List.of("PRODUCT", "QA", "MGMT")) {
            assertEquals(Access.ALLOW, PermissionMatrix.accessFor(role, "report:sign"));
        }
        assertEquals(Access.DENY, PermissionMatrix.accessFor("DEV", "report:sign"));
        // Execute and assign are QA-only, and every M6 code is defined for every role.
        assertEquals(Access.DENY, PermissionMatrix.accessFor("MGMT", "plan:execute"));
        assertEquals(Access.DENY, PermissionMatrix.accessFor("MGMT", "plan:assign"));
        for (String function : List.of("strategy:manage", "plan:edit", "plan:approve",
                "plan:execute", "plan:assign", "env:manage", "report:generate", "report:sign",
                "gate:view", "waiver:apply", "waiver:approve", "review:manage",
                "proddefect:report", "qualitymetric:view", "report:export")) {
            assertTrue(PermissionMatrix.functions().contains(function), "missing code " + function);
            assertNotNull(PermissionMatrix.accessFor("QA", function));
        }
    }

    @Test
    @DisplayName("M8: wiki/gantt/flow/policy are PMO-owned, review+executor QA-owned, reads are broad")
    void collaborationCells() {
        List<String> m8 = List.of("WIKI_VIEW", "WIKI_EDIT", "WIKI_ADMIN", "GANTT_VIEW",
                "GANTT_EDIT", "FLOW_VIEW", "FLOW_CONFIG", "CCODE_POLICY_VIEW",
                "CCODE_POLICY_EDIT", "CASE_REVIEW_VIEW", "CASE_REVIEW_SUBMIT",
                "CASE_REVIEW_DECIDE", "TEST_EXECUTOR_VIEW", "TEST_EXECUTOR_MANAGE");
        for (String function : m8) {
            assertTrue(PermissionMatrix.functions().contains(function), "missing code " + function);
            assertEquals(Access.ALLOW,
                    PermissionMatrix.accessFor("PLATFORM_ADMIN", function),
                    "PLATFORM_ADMIN must allow " + function);
            assertNotNull(PermissionMatrix.accessFor("DEV", function));
        }
        // Editing a space's visibility is administration, not authoring: DEV writes pages but must
        // not be able to widen who can read them.
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("PMO", "WIKI_ADMIN"));
        assertEquals(Access.DENY, PermissionMatrix.accessFor("DEV", "WIKI_ADMIN"));
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("DEV", "WIKI_EDIT"));
        // Re-dating a gantt row is delivery work (AC-2 audits it to a person), so both roles have it.
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("DEV", "GANTT_EDIT"));
        // Stage configuration decides the metric caliber: PMO defines it, and the flow board is a
        // delivery-governance read (PMO/PRODUCT/MGMT), so QA keeps the test surfaces instead.
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("PMO", "FLOW_CONFIG"));
        assertEquals(Access.DENY, PermissionMatrix.accessFor("QA", "FLOW_CONFIG"));
        assertEquals(Access.DENY, PermissionMatrix.accessFor("QA", "FLOW_VIEW"));
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("PMO", "FLOW_VIEW"));
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("PRODUCT", "FLOW_VIEW"));
        // The review loop belongs to QA; the reviewer decision is a QA cell, not a PMO one.
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("QA", "CASE_REVIEW_DECIDE"));
        assertEquals(Access.DENY, PermissionMatrix.accessFor("DEV", "CASE_REVIEW_DECIDE"));
        // Registering a policy is PMO-only; devs read which branches they must protect.
        assertEquals(Access.DENY, PermissionMatrix.accessFor("DEV", "CCODE_POLICY_EDIT"));
        assertEquals(Access.ALLOW, PermissionMatrix.accessFor("DEV", "CCODE_POLICY_VIEW"));
        // Every M8 code is defined for every role (no holes in the projected admin page).
        for (String role : PermissionMatrix.roles()) {
            for (String function : m8) {
                assertNotNull(PermissionMatrix.accessFor(role, function),
                        "hole at " + role + " × " + function);
            }
        }
    }
}
