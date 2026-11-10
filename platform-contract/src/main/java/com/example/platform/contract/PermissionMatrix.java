package com.example.platform.contract;

import java.util.ArrayList;
import java.util.Collection;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single definition of "who may do what": 10 roles × 60 function codes.
 *
 * <p>The 26 r2 codes cover requirement/defect/delivery operations; M6 appended 15 quality codes
 * (strategy, plan lifecycle, report, gate, waiver, review, metrics) and M7 the integration code;
 * M8 appends 14 collaboration/code/test-governance codes (wiki, gantt, value stream, branch
 * policy, case review, executor); M9 appends 4 analytics/portal codes (workbench, dashboard,
 * report read, report share). Appends only, so existing offsets never move. Nothing else may add a code: server-side interception and the frontend
 * entry check both read {@link #functions()} / {@link #accessOf}, and the admin page is a
 * projection of this matrix (AC-5 of r2, PRD §6 of r3).</p>
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
            "notification:read",
            // ---- M6 quality domain (append-only; PRD §6 increment matrix) ----
            "strategy:manage",
            "plan:edit",
            "plan:approve",
            "plan:execute",
            "plan:assign",
            "env:manage",
            "report:generate",
            "report:sign",
            "gate:view",
            "waiver:apply",
            "waiver:approve",
            "review:manage",
            "proddefect:report",
            "qualitymetric:view",
            "report:export",
            // ---- M7 integration domain (append-only; PRD §6 increment matrix) ----
            // 集成配置 / 准入项开关 / 同步日志三块管理面共用这一个码：它们是同一个
            // 「接哪四个系统、开哪九项准入」的决策，拆成三个码只会让管理员点三次。
            // 没有它，前端侧栏（nav.ts）与 /admin/integrations 路由的守卫恒 DENY，
            // 集成管理页对包括 PLATFORM_ADMIN 在内的所有人都是 403。
            "integration:manage",
            // ---- M8 collaboration / code / test governance (append-only; PRD §6 increment) ----
            // 大写常量是本轮 PRD/DESIGN 的原文（§5.1），与既有 metric:manage 风格不同但只有一份
            // 字符串：meta.functionCode、路由表、前端守卫读的都是这里，不建别名。
            "WIKI_VIEW",
            "WIKI_EDIT",
            "WIKI_ADMIN",
            "GANTT_VIEW",
            "GANTT_EDIT",
            "FLOW_VIEW",
            "FLOW_CONFIG",
            "CCODE_POLICY_VIEW",
            "CCODE_POLICY_EDIT",
            "CASE_REVIEW_VIEW",
            "CASE_REVIEW_SUBMIT",
            "CASE_REVIEW_DECIDE",
            "TEST_EXECUTOR_VIEW",
            "TEST_EXECUTOR_MANAGE",
            // ---- M9 岗位工作台 / 数据看板 / 自助报表（append-only；PRD §6.10） ----
            // 四个码对应三条新路由：/portal → PORTAL_VIEW（PUT /portal/config → PORTAL_CONFIG）、
            // /dashboard（前端路由，服务端 /analytics）→ PORTAL_VIEW、/reports → REPORT_VIEW
            // （POST /reports/{id}/share 另要 REPORT_SHARE）。与 M8 同风格用大写常量。
            "PORTAL_VIEW",
            "PORTAL_CONFIG",
            "REPORT_VIEW",
            "REPORT_SHARE");

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

        // ---- M6 quality domain (PRD §6 increment matrix; every row also grants PLATFORM_ADMIN
        // through the catch-all above). Grouped by role so one role's quality surface is read in
        // one place; DENY is the default for any role not named here.

        // Business side reads quality metrics only, and only vendor-wide (RESTRICTED).
        restrict(matrix, "BUSINESS", "qualitymetric:view");
        restrict(matrix, "SUB_BUSINESS_LEADER", "qualitymetric:view");

        // Product: owns the Go/No-Go record and signs reports; strategy is read-only for them.
        restrict(matrix, "PRODUCT", "strategy:manage", "review:manage");
        allow(matrix, "PRODUCT", "report:sign", "gate:view", "waiver:apply", "proddefect:report",
                "qualitymetric:view", "report:export");

        // PMO: reads gates and metrics, exports and co-signs reports, approves waivers (PRD §3.6
        // "PMO 或平台管理员审批"), hosts Go/No-Go (PRD §3.5), proposes strategy/env changes.
        restrict(matrix, "PMO", "strategy:manage", "env:manage");
        allow(matrix, "PMO", "gate:view", "qualitymetric:view", "report:export", "report:sign",
                "waiver:approve", "review:manage");

        // QA owns the whole quality loop: plan lifecycle, execution, reports, gates, waivers.
        allow(matrix, "QA", "strategy:manage", "plan:edit", "plan:approve", "plan:execute",
                "plan:assign", "report:generate", "report:sign", "gate:view", "waiver:apply",
                "review:manage", "proddefect:report", "qualitymetric:view", "report:export");
        restrict(matrix, "QA", "env:manage");

        // Dev sees gates and metrics read-only, may file post-release defects.
        restrict(matrix, "DEV", "gate:view", "proddefect:report", "qualitymetric:view",
                "report:export");

        // Ops runs environments, applies waivers for deployment blockers, joins Go/No-Go.
        allow(matrix, "OPS", "env:manage", "waiver:apply", "review:manage", "proddefect:report",
                "qualitymetric:view", "report:export", "gate:view");

        // Management: the only waiver approver, may approve plans and sign reports.
        allow(matrix, "MGMT", "plan:approve", "report:sign", "gate:view", "waiver:approve",
                "review:manage", "qualitymetric:view", "report:export");
        restrict(matrix, "MGMT", "strategy:manage");

        // ---- M8 collaboration / code / test governance (PRD §6 increment matrix) ----
        // One role's M8 surface is read in one place, same rule as M6: PLATFORM_ADMIN already has
        // every code through the catch-all above, every other role starts from DENY.
        // PMO owns the collaboration workspace (wiki structure, gantt re-planning, stage config,
        // branch policy registry) - the domain owner role from DESIGN §2.
        allow(matrix, "PMO", "WIKI_VIEW", "WIKI_EDIT", "WIKI_ADMIN", "GANTT_VIEW", "GANTT_EDIT",
                "FLOW_VIEW", "FLOW_CONFIG", "CCODE_POLICY_VIEW", "CCODE_POLICY_EDIT");
        // Dev reads the plan and works the wiki; re-dating gantt rows is a delivery action
        // (AC-2 审计到人), editing branch policy is not.
        allow(matrix, "DEV", "GANTT_VIEW", "GANTT_EDIT", "WIKI_VIEW", "WIKI_EDIT",
                "CCODE_POLICY_VIEW");
        // QA runs the review loop and keeps the executor ledger (健康是派生值，但台账要人维护).
        allow(matrix, "QA", "CASE_REVIEW_VIEW", "CASE_REVIEW_SUBMIT", "CASE_REVIEW_DECIDE",
                "TEST_EXECUTOR_VIEW", "TEST_EXECUTOR_MANAGE", "WIKI_VIEW");
        // Product reads the same collaboration surfaces it plans against; the flow board is the
        // delivery question a product owner answers in a review, so reading beats guessing.
        allow(matrix, "PRODUCT", "WIKI_VIEW", "GANTT_VIEW", "FLOW_VIEW");
        // Management reads boards; business roles read the plan they report into.
        allow(matrix, "MGMT", "WIKI_VIEW", "GANTT_VIEW", "FLOW_VIEW", "TEST_EXECUTOR_VIEW");
        allow(matrix, "BUSINESS", "WIKI_VIEW", "GANTT_VIEW");
        allow(matrix, "SUB_BUSINESS", "WIKI_VIEW", "GANTT_VIEW");
        allow(matrix, "SUB_BUSINESS_LEADER", "WIKI_VIEW", "GANTT_VIEW");
        allow(matrix, "OPS", "WIKI_VIEW", "GANTT_VIEW");

        // ---- M9 岗位工作台 / 数据看板 / 自助报表（PRD §6.10） ----
        // PORTAL_VIEW / REPORT_VIEW 对十个角色全开：这两个码管的是「能不能进自己的页面」，
        // 不是「能看到谁的数据」——行级范围由 ScopeSql 与报表属主/共享判定收口（共享只共享
        // 定义，数据永远按查看者 Scope 重算，AC-4）。按角色关掉读页面只会让厂商账号看不见
        // 自己的交付，而这正是工作台要回答的问题。
        for (String role : ROLES) {
            allow(matrix, role, "PORTAL_VIEW", "REPORT_VIEW");
        }
        // PORTAL_CONFIG（管理员改四张卡的显隐）不在这里授予：PLATFORM_ADMIN 已由上面的
        // catch-all 拿到，其余九个角色保持 DENY——卡片显隐是模板治理，不是个人偏好。
        // REPORT_SHARE 是「把报表定义共享给别人」的写动作，只有治理/管理侧三档 + 平台管理员。
        allow(matrix, "PMO", "REPORT_SHARE");
        allow(matrix, "PRODUCT", "REPORT_SHARE");
        allow(matrix, "MGMT", "REPORT_SHARE");

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
