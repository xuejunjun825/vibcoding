package com.example.platform.common.security;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Path + method → function code, as specified in TECHNICAL §5.3.
 *
 * <p>It exists because the controllers were written (in parallel tasks) without
 * {@code @RequiresPermission}; putting the mapping in the platform layer gives <b>one</b>
 * authorization table instead of scattering annotations across two services that would then
 * drift. A handler-level annotation still wins when present.</p>
 *
 * <p>Only paths that actually perform a guarded action are listed. Everything else is readable by
 * any authenticated caller and narrowed by {@code ScopeSql}/{@code ScopeGuard} instead — closing
 * reads by role would break the "vendor reads the requirement assigned to it" flow.</p>
 */
public final class PermissionRouteTable {

    /** One row: an optional method, a path regex, the functions, and and/or semantics. */
    public record Requirement(List<String> codes, boolean anyOf) {

        public Requirement {
            codes = List.copyOf(codes);
        }
    }

    private record Rule(String method, Pattern path, Requirement requirement) {
    }

    private static final List<Rule> RULES = List.of(
            // exports: always narrowed further by the caller's data scope
            rule(null, "^/.+/export\\.csv$", false, "export:detail"),

            // ---- system administration ----
            rule(null, "^/admin/tenants$", false, "tenant:manage"),
            rule(null, "^/admin/tenants/.+$", false, "tenant:manage"),
            rule(null, "^/admin/(orgs|vendors|domains|users|dicts)(/.*)?$", false, "org:manage"),
            rule(null, "^/admin/workflows(/.*)?$", false, "workflow:manage"),
            rule(null, "^/admin/api-clients(/.*)?$", false, "apiclient:manage"),
            rule(null, "^/admin/metric-definitions(/.*)?$", false, "metric:manage"),
            rule(null, "^/admin/audit(/.*)?$", false, "audit:view"),

            // ---- requirements ----
            rule("POST", "^/requirements$", true, "req:report:bus", "req:report:dev"),
            rule(null, "^/requirements/\\d+/decompose$", false, "req:split"),
            rule(null, "^/requirements/\\d+/assign-vendor$", false, "req:assign-vendor"),
            rule(null, "^/requirements/\\d+/deliver$", false, "req:deliver"),
            rule(null, "^/requirements/\\d+/quick-test$", false, "test:quick"),
            rule("POST", "^/requirements/\\d+/actions$", false, "req:review"),

            // ---- releases / deployment ----
            rule(null, "^/releases(/.*)?$", false, "release:plan"),
            rule(null, "^/deploy-orders/\\d+/force-release$", false, "deploy:force"),
            rule(null, "^/deploy-orders/\\d+/execution$", false, "deploy:execute"),
            rule(null, "^/deploy-orders(/.*)?$", false, "deploy:create"),

            // ---- defects ----
            rule("POST", "^/defects$", false, "defect:report"),
            rule(null, "^/defects/board(/.*)?$", false, "defect:board"),
            rule(null, "^/defects/\\d+/actions$", false, "defect:handle"),

            // ---- delivery surface ----
            rule(null, "^/(tasks|sprints)(/.*)?$", false, "task:manage"),
            rule(null, "^/(testcases|test-modules)(/.*)?$", false, "case:manage"),
            rule(null, "^/test-plans(/.*)?$", false, "plan:manage"),
            rule(null, "^/traceability(/.*)?$", false, "trace:view"),
            rule(null, "^/notifications(/.*)?$", false, "notification:read"));

    private PermissionRouteTable() {
    }

    /** The requirement for this request, or {@code null} when the path is not guarded. */
    public static Requirement requirementFor(String method, String path) {
        if (path == null) {
            return null;
        }
        String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
        for (Rule rule : RULES) {
            if (rule.method() != null && !rule.method().equals(normalizedMethod)) {
                continue;
            }
            if (rule.path().matcher(path).matches()) {
                return rule.requirement();
            }
        }
        return null;
    }

    /** Every guarded path, for the lint/test that asserts nothing is left unguarded by accident. */
    public static List<String> guardedPatterns() {
        return RULES.stream().map(rule -> rule.path().pattern()).toList();
    }

    private static Rule rule(String method, String regex, boolean anyOf, String... codes) {
        return new Rule(method, Pattern.compile(regex), new Requirement(List.of(codes), anyOf));
    }
}
