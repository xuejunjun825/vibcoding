package com.example.platform.contract;

import java.util.ArrayList;
import java.util.Collection;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single definition of "who may do what": 10 roles × 26 function codes.
 *
 * <p>Why in code rather than in a table: AC-5 is verified cell by cell, and a database-stored
 * matrix can drift between environments without any code change. Keeping it compiled means
 * the gateway, both services and {@code GET /admin/permissions} cannot disagree, and a missing
 * cell is a compile-time visible hole rather than a runtime "403 because the row was not seeded".</p>
 *
 * <p>{@link Access#RESTRICTED} is <b>not</b> a denial: the caller passes the function check and
 * the range is then narrowed by {@link DataScope} (e.g. vendor QA sees only their vendor's
 * defects). Union of roles takes the highest access: ALLOW &gt; RESTRICTED &gt; DENY.</p>
 */
public final class PermissionMatrix {

    /** Access level of one role in one function. */
    public enum Access {
        ALLOW,
        RESTRICTED,
        DENY;

        public static Access max(Access left, Access right) {
            return left.ordinal() <= right.ordinal() ? left : right;
        }
    }

    /** One cell of the matrix, as returned by {@code GET /admin/permissions}. */
    public record Cell(String function, String role, Access access) {
    }

    /** Function codes, in the stable order the admin page and the scripts read them. */
    private static final List<String> FUNCTIONS = List.of(
            "tenant:manage",
            "org:manage",
            "req:report:bus",
            "req:report:dev",
            "req:review",
            "req:split",
            "release:plan",
            "req:assign-vendor",
            "req:deliver",
            "test:quick",
            "deploy:create",
            "deploy:execute",
            "deploy:force",
            "defect:report",
            "defect:handle",
            "defect:board",
            "task:manage",
            "case:manage",
            "plan:manage",
            "trace:view",
            "metric:manage",
            "workflow:manage",
            "apiclient:manage",
            "export:detail",
            "audit:view",
            "notification:read");

    /**
     * Role codes, matching PRD §6.1 and {@code sys_role.code} seeded by SeedService.
     *
     * <p>The ten roles are the business-side org roles, the delivery roles and the platform
     * administrator. Vendor isolation is carried by the account's {@code vendor_id}, not by a
     * separate role — a vendor engineer is a {@code DEV} whose data scope is the vendor.</p>
     */
    private static final List<String> ROLES = List.of(
            "BUSINESS",
            "SUB_BUSINESS",
            "SUB_BUSINESS_LEADER",
            "PRODUCT",
            "PMO",
            "DEV",
            "QA",
            "OPS",
            "MGMT",
            "PLATFORM_ADMIN");

    private static final Map<String, Map<String, Access>> MATRIX = build();

    private PermissionMatrix() {
    }

    public static List<String> functions() {
        return FUNCTIONS;
    }

    public static List<String> roles() {
        return ROLES;
    }

    /** Access of a role in a function; unknown function/role is DENY (fail-closed). */
    public static Access accessFor(String role, String function) {
        Map<String, Access> row = MATRIX.get(role);
        if (row == null) {
            return Access.DENY;
        }
        return row.getOrDefault(function, Access.DENY);
    }

    /**
     * Effective access of a caller: the union (highest) of every role it holds.
     *
     * <p>An empty role set is DENY, never ALLOW — a token without roles must not pass.</p>
     */
    public static Access accessOf(String function, Collection<String> roles) {
        if (function == null || roles == null || roles.isEmpty()) {
            return Access.DENY;
        }
        Access result = Access.DENY;
        for (String role : roles) {
            result = Access.max(result, accessFor(role, function));
            if (result == Access.ALLOW) {
                return Access.ALLOW;
            }
        }
        return result;
    }

    /** Every function the caller may at least see (ALLOW or RESTRICTED), in stable order. */
    public static Set<String> functionsOf(Collection<String> roles) {
        Set<String> granted = new LinkedHashSet<>();
        if (roles == null) {
            return granted;
        }
        for (String function : FUNCTIONS) {
            if (accessOf(function, roles) != Access.DENY) {
                granted.add(function);
            }
        }
        return granted;
    }

    /**
     * Every cell, in function-major order (function, then role).
     *
     * <p>{@code GET /admin/permissions} serialises exactly this list; the verify script counts
     * the distinct functions and roles from it, so a missing cell shows up as a smaller roster
     * instead of silently never being probed.</p>
     */
    public static List<Cell> cells() {
        List<Cell> cells = new ArrayList<>(FUNCTIONS.size() * ROLES.size());
        for (String function : FUNCTIONS) {
            for (String role : ROLES) {
                cells.add(new Cell(function, role, accessFor(role, function)));
            }
        }
        return cells;
    }

    private static Map<String, Map<String, Access>> build() {
        Map<String, Map<String, Access>> matrix = new LinkedHashMap<>();
        for (String role : ROLES) {
            Map<String, Access> row = new LinkedHashMap<>();
            for (String function : FUNCTIONS) {
                row.put(function, Access.DENY);
            }
            matrix.put(role, row);
        }

        // Platform administrator: everything is allowed.
        allow(matrix, "PLATFORM_ADMIN", FUNCTIONS.toArray(String[]::new));

        // Business analyst / product manager: reports business requirements and reviews them.
        allow(matrix, "PRODUCT", "req:report:bus", "req:review");
        restrict(matrix, "PRODUCT", "req:report:dev", "req:split", "release:plan",
                "req:assign-vendor", "defect:board", "task:manage", "trace:view",
                "export:detail", "workflow:manage", "deploy:create", "case:manage",
                "plan:manage", "notification:read");

        // Business department: reports business requirements, reads the boards its org can see.
        allow(matrix, "BUSINESS", "req:report:bus", "notification:read");
        restrict(matrix, "BUSINESS", "trace:view", "defect:board", "export:detail");

        // Second-level enterprise business: reports and sees only what it submitted.
        allow(matrix, "SUB_BUSINESS", "req:report:bus", "notification:read");
        restrict(matrix, "SUB_BUSINESS", "trace:view", "defect:board", "export:detail");

        // Second-level enterprise lead: reviews and exports for its enterprise.
        allow(matrix, "SUB_BUSINESS_LEADER", "req:report:bus", "req:review", "notification:read");
        restrict(matrix, "SUB_BUSINESS_LEADER", "trace:view", "defect:board", "export:detail",
                "req:split", "release:plan");

        // Project / domain PMO: owns admission force-release, vendor assignment and delivery.
        // Vendor isolation is the account's vendor_id, not a separate role — a vendor PMO is this
        // row plus a vendor-scoped data scope.
        allow(matrix, "PMO", "req:report:bus", "req:report:dev", "req:review", "req:split",
                "release:plan", "req:assign-vendor", "req:deliver", "deploy:create",
                "deploy:force", "defect:report", "defect:handle", "defect:board",
                "task:manage", "metric:manage", "workflow:manage", "export:detail",
                "notification:read");
        restrict(matrix, "PMO", "test:quick", "deploy:execute", "case:manage", "plan:manage",
                "trace:view", "audit:view");

        // R&D engineer: delivers development requirements and handles defects.
        allow(matrix, "DEV", "req:deliver", "defect:report", "defect:handle", "notification:read");
        restrict(matrix, "DEV", "req:report:dev", "req:split", "release:plan", "test:quick",
                "defect:board", "task:manage", "case:manage", "trace:view", "export:detail",
                "deploy:create", "deploy:execute");

        // QA engineer: runs cases/plans, reports and verifies defects.
        allow(matrix, "QA", "test:quick", "case:manage", "plan:manage", "defect:report",
                "defect:handle", "defect:board", "notification:read");
        restrict(matrix, "QA", "trace:view", "export:detail", "task:manage", "release:plan",
                "req:report:dev", "deploy:create");

        // Operations: executes releases, keeps admission rights read-only.
        allow(matrix, "OPS", "deploy:execute", "defect:report", "notification:read");
        restrict(matrix, "OPS", "deploy:create", "defect:board", "trace:view", "export:detail",
                "req:report:dev", "test:quick", "release:plan");

        // Management: reads every board, changes nothing operational.
        allow(matrix, "MGMT", "defect:board", "trace:view", "metric:manage", "audit:view",
                "export:detail", "notification:read");
        restrict(matrix, "MGMT", "release:plan", "deploy:force");

        Map<String, Map<String, Access>> frozen = new LinkedHashMap<>();
        matrix.forEach((role, row) -> frozen.put(role, Map.copyOf(row)));
        return Map.copyOf(frozen);
    }

    private static void allow(Map<String, Map<String, Access>> matrix, String role, String... functions) {
        grant(matrix, role, Access.ALLOW, functions);
    }

    private static void restrict(Map<String, Map<String, Access>> matrix, String role, String... functions) {
        grant(matrix, role, Access.RESTRICTED, functions);
    }

    private static void grant(Map<String, Map<String, Access>> matrix, String role, Access access,
                              String... functions) {
        Map<String, Access> row = matrix.get(role);
        if (row == null) {
            throw new IllegalStateException("unknown role: " + role);
        }
        for (String function : functions) {
            if (!FUNCTIONS.contains(function)) {
                throw new IllegalStateException("unknown function code: " + function);
            }
            row.put(function, access);
        }
    }

    /** Convenience for callers that want the whole row (admin page / diagnostics). */
    public static Map<String, Access> rowOf(String role) {
        Map<String, Access> row = MATRIX.get(role);
        return row == null ? Map.of() : new LinkedHashMap<>(row);
    }

    /** Effective access of every function for a caller, in stable order (admin page / diagnostics). */
    public static Map<String, Access> effective(Collection<String> roles) {
        Map<String, Access> effective = new LinkedHashMap<>();
        for (String function : FUNCTIONS) {
            effective.put(function, accessOf(function, roles));
        }
        return effective;
    }
}
