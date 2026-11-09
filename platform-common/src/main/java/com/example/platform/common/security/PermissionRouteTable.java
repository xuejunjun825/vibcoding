package com.example.platform.common.security;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Path + method → function code, as specified in TECHNICAL §5.3 (r2) and §5.4 (M6 quality).
 *
 * <p>It exists because the controllers were written (in parallel tasks) without
 * {@code @RequiresPermission}; putting the mapping in the platform layer gives <b>one</b>
 * authorization table instead of scattering annotations across two services that would then
 * drift. A handler-level annotation still wins when present.</p>
 *
 * <p>Only paths that actually perform a guarded action are listed. Everything else is readable by
 * any authenticated caller and narrowed by {@code ScopeSql}/{@code ScopeGuard} instead — closing
 * reads by role would break the "vendor reads the requirement assigned to it" flow.</p>
 *
 * <p><b>Order is semantic, not cosmetic:</b> the table is first-match-wins, so every M6 rule sits
 * above the {@code ^/releases}, {@code ^/deploy-orders} and {@code ^/test-plans} fallbacks, and
 * the waiver routes sit above {@code ^/quality-gates(/.*)?$}. Adding a rule at the bottom is
 * therefore a silent no-op — {@code PermissionRouteTableTest} asserts each quality action by name.</p>
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

            // ---- M6 quality domain ----
            // MUST stay above the release / test-plan fallbacks below: first match wins, so an
            // /test-plans/{id}/approve that fell through to plan:manage would let a PMO approve a
            // plan, and /releases/{id}/quality would be swallowed by release:plan.
            rule("POST", "^/test-plans/\\d+/approve$", false, "plan:approve"),
            rule("POST", "^/test-plans/\\d+/(submit-review|reject|start|complete|close|archive)$",
                    false, "plan:edit"),
            rule("POST", "^/test-plans/\\d+/assignments(/.*)?$", false, "plan:assign"),
            rule("PUT", "^/test-plans/\\d+/assignments(/.*)?$", false, "plan:assign"),
            rule("POST", "^/test-plans/\\d+/execute$", false, "plan:execute"),
            // Opening/closing a round is execution work, not plan administration (AC-2): without
            // these two rules the requests fall through to plan:manage and a plan:execute-only
            // QA account gets a 403 on the very action listed in the matrix.
            rule("POST", "^/test-plans/\\d+/rounds$", false, "plan:execute"),
            rule("POST", "^/test-plans/\\d+/rounds/\\d+/close$", false, "plan:execute"),
            rule(null, "^/test-strategies(/.*)?$", false, "strategy:manage"),
            rule("POST", "^/test-envs(/.*)?$", false, "env:manage"),
            rule("PUT", "^/test-envs(/.*)?$", false, "env:manage"),
            rule("DELETE", "^/test-envs(/.*)?$", false, "env:manage"),
            rule("POST", "^/test-reports/\\d+/versions/\\d+/signoff$", false, "report:sign"),
            rule("GET", "^/test-reports/\\d+/export$", false, "report:export"),
            rule("POST", "^/test-reports(/.*)?$", false, "report:generate"),
            rule("PUT", "^/(quality-gates|releases/\\d+/quality-gate)$", false, "plan:manage"),
            rule("POST", "^/quality-gates/waivers$", false, "waiver:apply"),
            rule("POST", "^/quality-gates/waivers/\\d+/(approve|reject|revoke)$", false,
                    "waiver:approve"),
            rule("GET", "^/releases/\\d+/quality-gate$", false, "gate:view"),
            rule(null, "^/quality-gates(/.*)?$", false, "gate:view"),
            rule(null, "^/metrics/quality$", false, "qualitymetric:view"),
            rule(null, "^/releases/\\d+/quality$", false, "qualitymetric:view"),
            rule(null, "^/releases/\\d+/reviews(/.*)?$", false, "review:manage"),
            rule(null, "^/releases/\\d+/quality-settings$", false, "review:manage"),

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
