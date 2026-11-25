package com.example.platform.common.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Path → (page codes, action code) rules, evaluated first-match-wins (r18).
 *
 * <p>r17 gated paths by legacy function codes; r18 gates them by the two authorizable layers the
 * tenant can actually configure: the <b>page</b> (which menu entry the caller may open) and the
 * <b>action</b> (which verb it may perform on that page). A rule lists one or more page codes —
 * admission is any-of, because one URL legitimately serves several pages ({@code /requirements} is
 * both "我的任务" and "需求池") — and at most one action code.</p>
 *
 * <p>Keeping the table (rather than scanning every controller) is deliberate: it is one readable
 * list, the startup validator can compare it against the real handler set, and the gate script can
 * assert "no unprotected write endpoint" statically. The annotation
 * {@link RequiresAction} covers the few endpoints whose verb differs from their URL family.</p>
 *
 * <p>A rule with no action is a <b>page-only</b> rule: the endpoint exists but has no separate verb
 * worth granting (e.g. editing a draft requirement). Those are listed by
 * {@link #pageOnlyPatterns()} so the exemption is auditable rather than silent.</p>
 */
public final class PermissionRouteTable {

    /**
     * What one path requires.
     *
     * @param pages      page codes, admission is any-of
     * @param action     action code required, or {@code null} for a page-only rule
     * @param bodyPrefix when set, the action code is {@code bodyPrefix + <body field>} (see
     *                   {@link RequiresActionFromBody}); the field defaults to {@code action}
     */
    public record Requirement(List<String> pages, String action, String bodyPrefix) {

        public Requirement {
            pages = pages == null ? List.of() : List.copyOf(pages);
        }

        /** True when the action code has to be read from the request body. */
        public boolean fromBody() {
            return bodyPrefix != null && !bodyPrefix.isBlank();
        }

        /** True when the rule has no action at all (page admission only). */
        public boolean pageOnly() {
            return (action == null || action.isBlank()) && !fromBody();
        }
    }

    private record Rule(String method, Pattern path, Requirement requirement) {

        boolean matches(String requestMethod, String requestPath) {
            if (method != null && !method.equalsIgnoreCase(requestMethod)) {
                return false;
            }
            return path.matcher(requestPath).matches();
        }
    }

    /** Admission by page only (any-of the given codes). */
    public static Requirement page(String... pages) {
        return new Requirement(List.of(pages), null, null);
    }

    /** Admission by page plus one fixed action code. */
    public static Requirement action(String pageCode, String actionCode) {
        return new Requirement(List.of(pageCode), actionCode, null);
    }

    /**
     * Admission by page plus an action code taken from the body field {@code action}
     * (e.g. {@code POST /requirements/{id}/actions}).
     */
    public static Requirement bodyAction(String pageCode, String prefix) {
        return new Requirement(List.of(pageCode), null, prefix);
    }

    private static final List<Rule> RULES = build();

    private PermissionRouteTable() {
    }

    /** The requirement for a request, or {@code null} when the path is not guarded here. */
    public static Requirement requirementFor(String method, String path) {
        if (path == null) {
            return null;
        }
        String verb = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
        for (Rule rule : RULES) {
            if (rule.matches(verb, path)) {
                return rule.requirement();
            }
        }
        return null;
    }

    /** Every guarded pattern, as {@code METHOD pattern} — used by the declaration validator. */
    public static List<String> guardedPatterns() {
        List<String> patterns = new ArrayList<>();
        for (Rule rule : RULES) {
            patterns.add((rule.method() == null ? "*" : rule.method()) + " " + rule.path().pattern());
        }
        return List.copyOf(patterns);
    }

    /** The patterns that are protected by page admission only (no action code). */
    public static List<String> pageOnlyPatterns() {
        List<String> patterns = new ArrayList<>();
        for (Rule rule : RULES) {
            if (rule.requirement().pageOnly()) {
                patterns.add((rule.method() == null ? "*" : rule.method()) + " "
                        + rule.path().pattern());
            }
        }
        return List.copyOf(patterns);
    }

    private static Rule rule(String method, String regex, Requirement requirement) {
        return new Rule(method, Pattern.compile(regex), requirement);
    }

    private static List<Rule> build() {
        List<Rule> rules = new ArrayList<>();

        // ---------------------------------------------------------------- exports
        // Export is a capability on the page the caller can already read, so it has to be matched
        // before any per-family page rule; otherwise "/requirements/5/export.csv" would be
        // admitted by the page rule and the export capability would never be checked.
        rules.add(rule("GET", "^/.+/export\\.csv$",
                action("ana.reports", "report.action.export")));

        // ---------------------------------------------------------------- requirements
        rules.add(rule("POST", "^/requirements/\\d+/actions$",
                bodyAction("req.pool", "req.action.")));
        rules.add(rule("POST", "^/requirements/\\d+/decompose$",
                action("req.pool", "req.action.decompose")));
        rules.add(rule("POST", "^/requirements/\\d+/assign-vendor$",
                action("req.pool", "req.action.assign_vendor")));
        rules.add(rule("POST", "^/requirements/\\d+/deliver$",
                action("req.pool", "req.action.deliver")));
        rules.add(rule("POST", "^/requirements/\\d+/quick-test$",
                action("req.pool", "req.action.test_pass")));
        rules.add(rule("POST", "^/requirements/\\d+/mr/confirm-merge$",
                action("req.pool", "req.action.merge")));
        rules.add(rule("POST", "^/requirements/\\d+/integration/mr/refresh$", page("req.pool")));
        rules.add(rule("POST", "^/requirements/\\d+/sonar/refresh$", page("req.pool")));
        rules.add(rule("POST", "^/requirements/\\d+/mr-sync$", page("req.pool")));
        rules.add(rule("POST", "^/requirements$", action("req.pool", "req.action.submit")));
        rules.add(rule("PUT", "^/requirements/\\d+$", page("req.pool")));
        rules.add(rule("PATCH", "^/requirements/\\d+$", page("req.pool")));
        rules.add(rule("DELETE", "^/requirements/\\d+$", page("req.pool")));
        // 需求变更单（r40）：五个端点必须排在 /requirements 兜底之前，否则 POST 会被页规则
        // 放行成「有页面权限就能发起变更」。apply 不以 /requirements 开头，必须独立一条。
        rules.add(rule("POST", "^/requirements/\\d+/changes$",
                action("req.pool", "req.action.change_request")));
        rules.add(rule("POST", "^/requirements/\\d+/changes/preview$",
                action("req.pool", "req.action.change_request")));
        rules.add(rule("GET", "^/requirements/\\d+/changes$", page("req.pool")));
        rules.add(rule("GET", "^/changes/\\d+$", page("req.pool")));
        rules.add(rule("POST", "^/changes/\\d+/apply$",
                action("req.pool", "req.action.change_apply")));
        // PDR 评审（r41）：五个 /requirements 侧端点必须排在下面的兜底之前，否则「有需求池页面
        // 权限」就能发起评审/提交意见（评审人的运行时校验会变成唯一防线）。/pdr-reviews 侧的
        // 端点不以 /requirements 开头，但仍集中写在这里——同一特性的路由放一处才看得全。
        rules.add(rule("POST", "^/requirements/\\d+/pdr-review$",
                action("pdr.review", "req.action.pdr_review_start")));
        rules.add(rule("GET", "^/requirements/\\d+/pdr-review$", page("req.pool", "pdr.review")));
        rules.add(rule("GET", "^/requirements/\\d+/pdr-review/opinions$",
                page("req.pool", "pdr.review")));
        rules.add(rule("POST", "^/pdr-reviews/\\d+/opinions$",
                action("pdr.review", "req.action.pdr_review_opinion")));
        // 意见转任务排在 /opinions 之后无所谓（方法+正则都不同），但保持在 conclude 之前可读。
        rules.add(rule("POST", "^/pdr-reviews/\\d+/opinions/\\d+/to-task$",
                action("pdr.review", "req.action.pdr_review_task")));
        rules.add(rule("POST", "^/pdr-reviews/\\d+/conclude$",
                action("pdr.review", "req.action.pdr_review_conclude")));
        rules.add(rule("POST", "^/pdr-reviews/\\d+/close$",
                action("pdr.review", "req.action.pdr_review_conclude")));
        // 统计口径是分析能力：能看评审页或报表页的人都该能看（厂商行级收窄在服务层）。
        rules.add(rule("GET", "^/pdr-reviews/stats$", page("pdr.review", "ana.reports")));
        rules.add(rule("GET", "^/pdr-reviews/datasets$", page("pdr.review", "ana.reports")));
        rules.add(rule(null, "^/pdr-reviews.*$", page("pdr.review", "req.pool")));
        // 项目组：读名单是评审人选人的前置能力（评审页/需求池可见即可），写名册归「发起评审」的
        // 那批人（产品经理/域负责人/PMO）。设计 §5.1 写的 org:manage 会让产品经理连自己项目的
        // 成员都加不了，评审人选人随即断链——这里的收口见交付说明的偏差条。
        rules.add(rule("GET", "^/projects.*$", page("pdr.review", "req.pool")));
        rules.add(rule(null, "^/projects.*$",
                action("pdr.review", "req.action.pdr_review_start")));
        rules.add(rule(null, "^/requirements.*$", page("req.pool", "me.tasks")));

        // ---------------------------------------------------------------- defects
        rules.add(rule("POST", "^/defects/from-execution/\\d+$",
                action("defect.list", "defect.action.create")));
        rules.add(rule("POST", "^/defects/\\d+/actions$",
                bodyAction("defect.list", "defect.action.")));
        rules.add(rule("POST", "^/defects$", action("defect.list", "defect.action.create")));
        rules.add(rule(null, "^/defects.*$", page("defect.list", "defect.board")));

        // ---------------------------------------------------------------- tasks / sprints
        rules.add(rule("POST", "^/tasks$", action("req.iteration", "task.action.create")));
        rules.add(rule("PATCH", "^/tasks/\\d+$", action("req.iteration", "task.action.update")));
        rules.add(rule("POST", "^/tasks/\\d+/move$", action("req.iteration", "task.action.assign")));
        rules.add(rule("POST", "^/sprints$", action("req.iteration", "sprint.action.create")));
        rules.add(rule("POST", "^/sprints/\\d+/close$",
                action("req.iteration", "sprint.action.close")));
        rules.add(rule(null, "^/(tasks|sprints).*$", page("req.iteration")));

        // ---------------------------------------------------------------- cases / modules
        rules.add(rule("POST", "^/testcases/import$",
                action("test.cases", "test.action.case_edit")));
        rules.add(rule("POST", "^/testcases/\\d+/copy$",
                action("test.cases", "test.action.case_edit")));
        rules.add(rule("POST", "^/testcases/(\\d+/)?reviews$",
                action("test.cases", "test.action.case_review")));
        rules.add(rule("POST", "^/testcase-reviews/\\d+/decide$",
                action("test.cases", "test.action.case_review")));
        rules.add(rule("POST", "^/testcases.*$", action("test.cases", "test.action.case_edit")));
        rules.add(rule("PUT", "^/testcases.*$", action("test.cases", "test.action.case_edit")));
        rules.add(rule("DELETE", "^/testcases.*$", action("test.cases", "test.action.case_edit")));
        rules.add(rule("POST", "^/test-modules.*$", action("test.cases", "test.action.case_edit")));
        rules.add(rule("PUT", "^/test-modules.*$", action("test.cases", "test.action.case_edit")));
        rules.add(rule("DELETE", "^/test-modules.*$", action("test.cases", "test.action.case_edit")));
        rules.add(rule(null, "^/(testcases|test-modules|testcase-reviews).*$", page("test.cases")));

        // ---------------------------------------------------------------- test plans
        rules.add(rule("POST", "^/test-plans/\\d+/approve$",
                action("test.plans", "test.action.plan_approve")));
        rules.add(rule("POST", "^/test-plans/\\d+/reject$",
                action("test.plans", "test.action.plan_approve")));
        rules.add(rule("POST", "^/test-plans/\\d+/submit-review$",
                action("test.plans", "test.action.plan_create")));
        rules.add(rule("POST", "^/test-plans/\\d+/(start|complete|close|archive)$",
                action("test.plans", "test.action.execute")));
        rules.add(rule("PUT", "^/test-plans/\\d+/assignments$",
                action("test.plans", "test.action.plan_create")));
        rules.add(rule("PATCH", "^/test-plans/\\d+$",
                action("test.plans", "test.action.plan_create")));
        rules.add(rule("POST", "^/test-plans/\\d+/cases$",
                action("test.plans", "test.action.plan_create")));
        rules.add(rule("DELETE", "^/test-plans/\\d+/cases/\\d+$",
                action("test.plans", "test.action.plan_create")));
        rules.add(rule("POST", "^/test-plans$", action("test.plans", "test.action.plan_create")));
        rules.add(rule("POST", "^/test-plans/\\d+/execute$",
                action("test.plans", "test.action.execute")));
        rules.add(rule("POST", "^/test-plans/\\d+/rounds.*$", action("test.plans", "test.action.execute")));
        rules.add(rule("PUT", "^/test-plans/\\d+/data-note$",
                action("test.plans", "test.action.execute")));
        rules.add(rule(null, "^/test-plans.*$", page("test.plans")));

        // ---------------------------------------------------------------- strategies / envs
        rules.add(rule(null, "^/test-strategies.*$", page("test.strategies")));
        rules.add(rule(null, "^/test-envs.*$", page("test.envs")));

        // ---------------------------------------------------------------- reports / executors
        rules.add(rule("POST", "^/test-reports/\\d+/versions/\\d+/signoff$",
                action("test.reports", "test.action.report_sign")));
        rules.add(rule("POST", "^/test-reports$",
                action("test.reports", "test.action.report_sign")));
        rules.add(rule(null, "^/test-reports.*$", page("test.reports")));
        rules.add(rule(null, "^/test-executors.*$", page("test.executors")));

        // ---------------------------------------------------------------- quality gates
        rules.add(rule("POST", "^/quality-gates/waivers$",
                action("qa.gates", "test.action.gate_waive")));
        rules.add(rule("POST", "^/quality-gates/waivers/\\d+/(approve|reject|revoke)$",
                action("qa.gates", "test.action.gate_waive")));
        rules.add(rule("PUT", "^/quality-gates/\\d+$",
                action("qa.gates", "test.action.gate_waive")));
        // Evaluating and replaying a gate is the admission decision itself, not a waiver: there is
        // no action code of its own, so the qa.gates page is the whole requirement.
        rules.add(rule("POST", "^/quality-gates/\\d+/evaluate$", page("qa.gates")));
        rules.add(rule("POST", "^/quality-gates/snapshots/\\d+/replay$", page("qa.gates")));
        rules.add(rule(null, "^/quality-gates.*$", page("qa.gates")));

        // ---------------------------------------------------------------- releases
        rules.add(rule("GET", "^/releases/\\d+/quality-gate$", page("qa.gates", "req.release")));
        rules.add(rule("POST", "^/releases$", action("req.release", "release.action.create")));
        rules.add(rule("POST", "^/releases/\\d+/approval$",
                action("req.release", "release.action.approve")));
        // Creating a review round is a write on the release, not a read of its page: guarding it by
        // page code alone let any role that can *see* the release open a round (VENDOR_PM is the
        // first such role). release.action.update is derived from release:plan for the other roles,
        // so their admission is unchanged — only a read-only vendor loses this write.
        rules.add(rule("POST", "^/releases/\\d+/reviews$",
                action("req.release", "release.action.update")));
        rules.add(rule("POST", "^/releases/\\d+/reviews/\\d+/confirm$",
                action("req.release", "release.action.approve")));
        rules.add(rule("PATCH", "^/releases/\\d+/quality-settings$",
                action("req.release", "release.action.update")));
        rules.add(rule("PUT", "^/releases/\\d+/integration-settings$",
                action("req.release", "release.action.update")));
        rules.add(rule("POST", "^/releases/\\d+/requirements$",
                action("req.release", "release.action.update")));
        rules.add(rule("DELETE", "^/releases/\\d+/requirements/\\d+$",
                action("req.release", "release.action.update")));
        rules.add(rule("POST", "^/releases/\\d+/lock$", action("req.release", "release.action.close")));
        rules.add(rule("POST", "^/releases/\\d+/milestones$",
                action("req.release", "release.action.update")));
        rules.add(rule(null, "^/releases.*$", page("req.release")));

        // ---------------------------------------------------------------- deploy orders
        rules.add(rule("POST", "^/deploy-orders/\\d+/force-release$",
                action("deploy.orders", "deploy.action.force_release")));
        rules.add(rule("POST", "^/deploy-orders/\\d+/execution$",
                action("deploy.orders", "deploy.action.execute")));
        rules.add(rule("POST", "^/artifacts/\\d+/promote$",
                action("deploy.orders", "deploy.action.create")));
        rules.add(rule("POST", "^/deploy-orders$", action("deploy.orders", "deploy.action.create")));
        rules.add(rule(null, "^/(deploy-orders|artifacts|builds).*$", page("deploy.orders")));

        // ---------------------------------------------------------------- integration config
        rules.add(rule(null, "^/integration/(configs|admission-items).*$",
                action("admin.integrations", "integration.action.manage")));
        rules.add(rule(null, "^/integration.*$", page("admin.integrations")));

        // ---------------------------------------------------------------- collaboration
        // 只有**改动空间结构**才要 wiki.action.admin：GET /wiki/spaces 是读空间列表（页面级读），
        // GET /wiki/spaces/{id}/tree 是读某空间的页面树。原先把整个 /wiki/spaces.* 归给 admin，
        // 于是只有 WIKI_VIEW 的 QA 连列表都读不到——读接口不该被写能力挡住。
        rules.add(rule("POST", "^/wiki/spaces.*$", action("collab.wiki", "wiki.action.admin")));
        rules.add(rule("PUT", "^/wiki/spaces.*$", action("collab.wiki", "wiki.action.admin")));
        rules.add(rule("PATCH", "^/wiki/spaces.*$", action("collab.wiki", "wiki.action.admin")));
        rules.add(rule("DELETE", "^/wiki/spaces.*$", action("collab.wiki", "wiki.action.admin")));
        rules.add(rule("DELETE", "^/wiki/pages/\\d+/purge$",
                action("collab.wiki", "wiki.action.admin")));
        rules.add(rule("POST", "^/wiki.*$", action("collab.wiki", "wiki.action.edit")));
        rules.add(rule("PUT", "^/wiki.*$", action("collab.wiki", "wiki.action.edit")));
        rules.add(rule("DELETE", "^/wiki.*$", action("collab.wiki", "wiki.action.edit")));
        rules.add(rule(null, "^/wiki.*$", page("collab.wiki")));
        rules.add(rule("PATCH", "^/gantt/rows/.*$", action("collab.gantt", "gantt.action.edit")));
        rules.add(rule(null, "^/gantt.*$", page("collab.gantt")));
        rules.add(rule("PUT", "^/flow/stages$",
                action("collab.value-stream", "flow.action.config")));
        rules.add(rule(null, "^/flow.*$", page("collab.value-stream")));
        rules.add(rule(null, "^/code/policies.*$", page("code.policies")));

        // ---------------------------------------------------------------- analytics / reports
        rules.add(rule("PUT", "^/portal/config$", action("ana.portal", "portal.action.config")));
        rules.add(rule("POST", "^/reports/\\d+/pin$",
                action("ana.reports", "report.action.publish")));
        rules.add(rule("PUT", "^/reports/\\d+/share$", action("ana.reports", "report.action.share")));
        rules.add(rule("POST", "^/reports/\\d+/share$", action("ana.reports", "report.action.share")));
        rules.add(rule("POST", "^/reports/\\d+/run$", action("ana.reports", "report.action.generate")));
        rules.add(rule("POST", "^/reports$", action("ana.reports", "report.action.define")));
        rules.add(rule("PUT", "^/reports/\\d+$", action("ana.reports", "report.action.define")));
        rules.add(rule("DELETE", "^/reports/\\d+$", action("ana.reports", "report.action.define")));
        rules.add(rule("POST", "^/reports/\\d+/copy$", action("ana.reports", "report.action.define")));
        rules.add(rule(null, "^/reports.*$", page("ana.reports")));
        rules.add(rule(null, "^/portal.*$", page("ana.portal")));
        rules.add(rule(null, "^/analytics.*$", page("ana.dashboard")));
        rules.add(rule(null, "^/metrics.*$", page("qa.metrics", "ana.dashboard")));
        rules.add(rule(null, "^/workbench.*$", page("me.workbench")));

        // ---------------------------------------------------------------- approvals / notifications
        rules.add(rule(null, "^/approvals.*$", page("me.todo")));
        rules.add(rule(null, "^/notifications.*$", page("me.todo")));

        // ---------------------------------------------------------------- admin: roles
        rules.add(rule("POST", "^/admin/roles$", action("admin.roles", "role.action.create")));
        rules.add(rule("PATCH", "^/admin/roles/\\d+/status$",
                action("admin.roles", "role.action.status")));
        rules.add(rule("PUT", "^/admin/roles/\\d+/permissions$",
                action("admin.roles", "role.action.grant")));
        rules.add(rule("PUT", "^/admin/roles/\\d+$", action("admin.roles", "role.action.update")));
        rules.add(rule("DELETE", "^/admin/roles/\\d+$", action("admin.roles", "role.action.update")));
        rules.add(rule(null, "^/admin/roles.*$", page("admin.roles")));

        // ---------------------------------------------------------------- admin: users
        rules.add(rule("POST", "^/admin/users$", action("admin.users", "user.action.create")));
        rules.add(rule("PATCH", "^/admin/users/\\d+/status$",
                action("admin.users", "user.action.disable")));
        rules.add(rule("POST", "^/admin/users/\\d+/reset-password$",
                action("admin.users", "user.action.reset_password")));
        rules.add(rule("PUT", "^/admin/users/(\\d+)?$",
                action("admin.users", "user.action.update")));
        rules.add(rule(null, "^/admin/users.*$", page("admin.users")));

        // ---------------------------------------------------------------- admin: orgs / tenants
        rules.add(rule("POST", "^/admin/orgs$", action("admin.orgs", "org.action.create")));
        rules.add(rule("POST", "^/admin/orgs/\\d+/move-users$",
                action("admin.orgs", "org.action.move_users")));
        rules.add(rule("DELETE", "^/admin/orgs/\\d+$", action("admin.orgs", "org.action.delete")));
        rules.add(rule("PUT", "^/admin/orgs/(\\d+)?$",
                action("admin.orgs", "org.action.update")));
        rules.add(rule(null, "^/admin/(orgs|tenants|dicts).*$", page("admin.orgs")));

        // ---------------------------------------------------------------- admin: vendors / domains
        rules.add(rule(null, "^/admin/(vendors|domains).*$",
                action("admin.vendors", "vendor.action.manage")));

        // ---------------------------------------------------------------- admin: read-only surfaces
        rules.add(rule(null, "^/admin/permissions.*$", page("admin.permissions")));
        rules.add(rule(null, "^/admin/workflows.*$",
                action("admin.workflows", "workflow.action.manage")));
        // r30：分级审批绑定与流程定义同属「审批链配置」，页面码与动作码复用 admin.workflows /
        // workflow.action.manage（不新增权限码，rbac=113 不动）。读也要动作码：绑定内容决定全租户
        // 的审批链，非管理员连读都不该放行（AC-6）。缺这一条时声明校验器会以 P0 崩启动（D-1），
        // 而 PermissionInterceptor 对未声明路径直接放行（D-2）。
        rules.add(rule(null, "^/admin/flow-bindings.*$",
                action("admin.workflows", "workflow.action.manage")));
        rules.add(rule(null, "^/admin/api-clients.*$",
                action("admin.api-clients", "apiclient.action.manage")));
        rules.add(rule(null, "^/admin/metric-definitions.*$", page("admin.metrics")));
        rules.add(rule(null, "^/admin/metrics.*$", page("admin.metrics")));
        rules.add(rule(null, "^/admin/audit.*$", page("admin.audit")));

        // ---------------------------------------------------------------- automation (r39)
        // 页面码只有一个（automation.rules），「规则 / 执行日志」是页内双 tab，所以四条路由共用它；
        // 写操作额外要动作码 automation.action.manage（页面判定先于动作，顺序不能倒）。
        rules.add(rule("POST", "^/automation/rules$",
                action("automation.rules", "automation.action.manage")));
        rules.add(rule("PUT", "^/automation/rules/\\d+$",
                action("automation.rules", "automation.action.manage")));
        rules.add(rule("PATCH", "^/automation/rules/\\d+/enabled$",
                action("automation.rules", "automation.action.manage")));
        rules.add(rule(null, "^/automation.*$", page("automation.rules")));

        // ---------------------------------------------------------------- attachments
        rules.add(rule(null, "^/attachments.*$",
                page("req.pool", "defect.list", "test.cases", "collab.wiki")));

        return List.copyOf(rules);
    }
}
