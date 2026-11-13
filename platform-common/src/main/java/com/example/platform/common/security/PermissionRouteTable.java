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
            // 权限矩阵是只读投影，但它把「角色 × 功能码」整张授权模型摊开：对非管理员开放等于
            // 告诉任意已认证账号每个角色能做什么。管理页与后端用同一个码（前端 nav 也按它隐藏入口）。
            rule(null, "^/admin/permissions$", false, "org:manage"),
            rule(null, "^/admin/workflows(/.*)?$", false, "workflow:manage"),
            rule(null, "^/admin/api-clients(/.*)?$", false, "apiclient:manage"),
            rule(null, "^/admin/metric-definitions(/.*)?$", false, "metric:manage"),
            rule(null, "^/admin/audit(/.*)?$", false, "audit:view"),

            // ---- M7 integration domain ----
            // 配置 / 准入项开关 / 同步日志是管理面，读写同一个码：未匹配的路径返回 null，
            // 等于任何已认证账号都能改集成配置（换成自己控制的 Jenkins 地址）并读出
            // 别处的凭据末四位与同步摘要。webhook 走 /integration/webhooks/**，由网关匿名
            // 放行、由 WebhookSignatureVerifier 自证身份，故意不进这张表。
            rule(null, "^/integration/(configs|logs|admission-items)(/.*)?$", false,
                    "integration:manage"),
            // 制品检索是交互读（发布单页选制品，界面按 deploy:create 决定是否显示入口），
            // 只有晋级是写：它把制品推到正式仓库，属于发布动作而不是集成管理，
            // 与前端 ArtifactSummaryCard.canPromote（deploy:create）保持同一个码。
            rule("POST", "^/artifacts/\\d+/promote$", false, "deploy:create"),

            // ---- M8 collaboration / code / test governance ----
            // Same rule as M6: the broad read rule sits last within its own family, and every write
            // or action rule sits above it, because first-match-wins would otherwise charge a wiki
            // page write only WIKI_VIEW (a read code) and a gantt re-date only GANTT_VIEW.
            // /gantt/rows/{id} is the one write entry point (AC-2 keeps it unique), so it gets its
            // own rule before the board read.
            rule("PATCH", "^/gantt/rows/.*$", false, "GANTT_EDIT"),
            rule(null, "^/gantt(/.*)?$", false, "GANTT_VIEW"),
            // Space structure (create / rename / members / visibility) is administration: without
            // this rule it would be swallowed by the wiki write rule below and any WIKI_EDIT caller
            // could lift a space's visibility.
            rule("POST", "^/wiki/spaces(/.*)?$", false, "WIKI_ADMIN"),
            rule("PUT", "^/wiki/spaces(/.*)?$", false, "WIKI_ADMIN"),
            rule("DELETE", "^/wiki/spaces(/.*)?$", false, "WIKI_ADMIN"),
            // Purging a page is physical deletion of an admin-visible trash row: the author who may
            // soft-delete a page must not be able to skip the 30-day window through it.
            rule("DELETE", "^/wiki/pages/.*/purge$", false, "WIKI_ADMIN"),
            rule("POST", "^/wiki/.*$", false, "WIKI_EDIT"),
            rule("PUT", "^/wiki/.*$", false, "WIKI_EDIT"),
            rule("DELETE", "^/wiki/.*$", false, "WIKI_EDIT"),
            rule(null, "^/wiki(/.*)?$", false, "WIKI_VIEW"),
            // Stage definitions decide the flow metrics' caliber, so re-configuring them is
            // FLOW_CONFIG while reading the board is FLOW_VIEW.
            rule("POST", "^/flow/stages(/.*)?$", false, "FLOW_CONFIG"),
            rule("PUT", "^/flow/stages(/.*)?$", false, "FLOW_CONFIG"),
            rule("DELETE", "^/flow/stages(/.*)?$", false, "FLOW_CONFIG"),
            rule(null, "^/flow(/.*)?$", false, "FLOW_VIEW"),
            // The metric dictionary preview of a FLOW_* code must stay readable by anyone who can
            // read the board: it delegates to the same computation (no second formula), so it must
            // not require metric:manage - that would hide the number from the people judging it.
            rule(null, "^/metrics/FLOW_[A-Z_]+/preview$", false, "FLOW_VIEW"),
            // Verifying a policy calls GitLab; registering/editing one changes what the team must
            // do, so both are the edit code, while listing is view-only.
            rule(null, "^/code/policies/.*/check$", false, "CCODE_POLICY_EDIT"),
            rule("POST", "^/code/policies(/.*)?$", false, "CCODE_POLICY_EDIT"),
            rule("PUT", "^/code/policies(/.*)?$", false, "CCODE_POLICY_EDIT"),
            rule("DELETE", "^/code/policies(/.*)?$", false, "CCODE_POLICY_EDIT"),
            rule(null, "^/code/policies(/.*)?$", false, "CCODE_POLICY_VIEW"),
            // Case reviews are read-gated and the service decides SUBMIT vs DECIDE (the reviewer
            // is the assignee, not the role); both paths exist so the gate also covers the nested
            // form used by the case library drawer. Must stay above ^/(testcases|...) below.
            rule(null, "^/testcase-reviews(/.*)?$", false, "CASE_REVIEW_VIEW"),
            rule(null, "^/testcases/\\d+/reviews(/.*)?$", false, "CASE_REVIEW_VIEW"),
            // The executor ledger is test infrastructure: reading it is not managing it.
            rule("POST", "^/test-executors(/.*)?$", false, "TEST_EXECUTOR_MANAGE"),
            rule("PUT", "^/test-executors(/.*)?$", false, "TEST_EXECUTOR_MANAGE"),
            rule("DELETE", "^/test-executors(/.*)?$", false, "TEST_EXECUTOR_MANAGE"),
            rule(null, "^/test-executors(/.*)?$", false, "TEST_EXECUTOR_VIEW"),

            // ---- M8 CCode（MR 评论 / 代码质量卡） ----
            // 这两条读必须显式登记：没有规则时 /requirements/{id}/code-quality 是「任何已认证
            // 账号可读」，而 /releases/{id}/code-quality 会掉到下面的 ^/releases → release:plan
            // ——「勾了发布权限才能看质量卡」不是想要的口径。卡本身只读本地快照，零外呼。
            rule(null, "^/requirements/\\d+/code-quality$", false, "CCODE_POLICY_VIEW"),
            rule(null, "^/releases/\\d+/code-quality$", false, "CCODE_POLICY_VIEW"),
            // 行级评论跟随需求可见性（跨域 404 由服务层给），但读它要落在协作链路的角色上：
            // 代码负责人/PMO 用 CCODE_POLICY_VIEW，评审人（QA）只有 CASE_REVIEW_VIEW，
            // 所以是 anyOf —— 单用 CCODE_POLICY_VIEW 会把评审人挡在自己要看的评论外面。
            rule(null, "^/requirements/\\d+/integration/mr(-comments)?$", true,
                    "CCODE_POLICY_VIEW", "CASE_REVIEW_VIEW"),
            // 刷新 MR 卡是这一段唯一主动外呼 GitLab 的动作（会写 mr_sync），所以是编辑码：
            // 读走上面的读规则，点「刷新」才需要 CCODE_POLICY_EDIT。
            rule("POST", "^/requirements/\\d+/integration/mr/refresh$", false,
                    "CCODE_POLICY_EDIT"),

            // ---- requirements ----
            rule("POST", "^/requirements$", true, "req:report:bus", "req:report:dev"),
            rule(null, "^/requirements/\\d+/decompose$", false, "req:split"),
            rule(null, "^/requirements/\\d+/assign-vendor$", false, "req:assign-vendor"),
            rule(null, "^/requirements/\\d+/deliver$", false, "req:deliver"),
            rule(null, "^/requirements/\\d+/quick-test$", false, "test:quick"),
            // 动作端点（POST /requirements/{id}/actions）**故意**不在此登记：这一个端点承载需求
            // 状态机的十余个流转动作，每个动作的判据不同（业务方提交自己的、产品经理评审、开发
            // 开工、测试通过……），而本表只能表达「一个端点一个码」。保留任何码都必然把另一侧
            // 挡在门外——历史上这里是 req:review，于是只有 req:report:bus 的业务角色提交自己
            // 的需求也 403（r17 修复）。故此处降为「已认证可达」，逐动作授权下移到服务层，
            // 由 platform-contract 的 RequirementActionMatrix 统一判定（allowedActions 过滤与
            // transition 执行共用同一个谓词）。不要为这个路径再加回端点级规则。

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
            // AC-15：测试数据说明由「计划 owner + 本域测试角色」编辑（非 owner 403、计划完成
            // 409 PLAN_DATA_LOCKED 都在服务层判）。路由层不能再用 plan:manage 一刀切：那会让
            // 只有 plan:edit/plan:execute 的计划 owner 连读自己的说明都被 403，owner 判定永远
            // 走不到。三个码任一即可，属于测试计划自己的动作码。
            rule(null, "^/test-plans/\\d+/data-note$", true,
                    "plan:edit", "plan:execute", "plan:manage"),
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

            // ---- M9 岗位工作台 / 数据看板 / 自助报表（deliver-service，网关 /api/deliver/**） ----
            // 位次是硬约束：本表首匹配生效，这一段必须排在下面的 ^/releases、^/deploy-orders、
            // ^/test-plans 兜底之前。放错位置时 /reports/* 会被 ^/releases 之前的 release:plan
            // 之类规则吃掉（历史上 /reports 落到 release:plan，非发布角色一律 403 且报错说
            // 「缺 release:plan」，与本页毫无关系）。
            // 权限粒度：PORTAL_CONFIG 只管写配置这一条 PUT，读配置（GET /portal/config）走
            // PORTAL_VIEW；REPORT_SHARE 只管共享这一条 POST，报表本身的读写都走 REPORT_VIEW
            // （导出 /reports/{id}/export 也在其中——导出的是查看者自己有权看的定义）。
            rule("PUT", "^/portal/config$", false, "PORTAL_CONFIG"),
            rule(null, "^/portal(/.*)?$", false, "PORTAL_VIEW"),
            rule(null, "^/analytics(/.*)?$", false, "PORTAL_VIEW"),
            rule("POST", "^/reports/\\d+/share$", false, "REPORT_SHARE"),
            rule(null, "^/reports(/.*)?$", false, "REPORT_VIEW"),

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
