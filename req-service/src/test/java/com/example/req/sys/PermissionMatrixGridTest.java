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
 * <p>Every one of the 10 roles × 26 functions is covered, so a silently changed cell (the usual
 * way a permission bug ships) fails here instead of in production.</p>
 */
class PermissionMatrixGridTest {

    @Test
    @DisplayName("10 roles × 26 functions: every cell exists and is defined exactly once")
    void everyCellIsDefined() {
        List<PermissionMatrix.Cell> cells = PermissionMatrix.cells();
        int expected = PermissionMatrix.roles().size() * PermissionMatrix.functions().size();
        assertEquals(260, expected, "the matrix is 10 roles × 26 functions");
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
    @DisplayName("PLATFORM_ADMIN is ALLOW on all 26 functions; no other role holds tenant:manage")
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
}
