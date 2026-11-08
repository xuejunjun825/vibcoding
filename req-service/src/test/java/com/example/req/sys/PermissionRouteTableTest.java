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
}
