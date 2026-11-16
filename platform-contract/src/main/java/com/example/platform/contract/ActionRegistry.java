package com.example.platform.contract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The 72 authorizable actions, in one place (r18).
 *
 * <p>Before r18 an action was not a grant subject at all: the frontend decided whether to show a
 * button by a function code, so "may write wiki pages" and "may administer wiki spaces" shared one
 * switch, and three obviously dangerous operations (force release, disable user, grant permissions)
 * had no way to be granted separately from the harmless ones next to them.</p>
 *
 * <p>An action is always owned by exactly one page ({@code menuCode}) and lists the legacy function
 * codes it depends on. The page link is what makes the three-layer model coherent: an action whose
 * page is DENY can never take effect, no matter how it is granted (the page is the first of the
 * three decision steps, and the role-permission screen shows such a grant as "配置保留、不生效").</p>
 *
 * <p>{@code functionCodes} carries the <b>any-of</b> meaning the route table already had: an action
 * is available when at least one of its codes is not DENY, and the seed derives its ACCESS cell with
 * {@link PermissionMatrix.Access#max} over those codes. That is what makes the upgrade
 * behaviour-preserving: the union over roles is unchanged, only the storage moved.</p>
 */
public final class ActionRegistry {

    /**
     * One authorizable action.
     *
     * @param code          stable action code ({@code <module>.action.<verb>}), the grant subject
     * @param menuCode      the page that owns the action; must be a registered page code
     * @param name          Chinese label, identical to the button label
     * @param functionCodes legacy function codes the action depends on (any-of; empty = none)
     * @param highRisk      grants of this action need an explicit second confirmation (r18 AC-12)
     * @param sort          display order inside the page's action table
     */
    public record ActionDef(String code, String menuCode, String name, List<String> functionCodes,
                            boolean highRisk, int sort) {

        public ActionDef {
            functionCodes = List.copyOf(functionCodes);
        }
    }

    /**
     * The three actions r18 freezes behind a second confirmation (AC-12). They are also the three
     * that must never be granted by the ordinary "tick the page" gesture: force release ships
     * without a quality gate, disable user can lock a tenant out, grant permissions escalates.
     */
    public static final Set<String> HIGH_RISK = Set.of(
            "deploy.action.force_release",
            "user.action.disable",
            "role.action.grant");

    /** Running display order per page, filled while {@link #ACTIONS} is being built. */
    private static final java.util.Map<String, Integer> SORTS = new java.util.HashMap<>();

    private static final List<ActionDef> ACTIONS = List.of(
            // ---- 需求池 req.pool ----
            action("req.action.submit", "req.pool", "提交审批",
                    "req:report:bus", "req:report:dev"),
            action("req.action.withdraw", "req.pool", "撤回",
                    "req:report:bus", "req:report:dev"),
            action("req.action.approve", "req.pool", "评审通过", "req:review"),
            action("req.action.reject", "req.pool", "评审不通过", "req:review"),
            action("req.action.review_start", "req.pool", "发起评审", "req:review"),
            action("req.action.review_pass", "req.pool", "评审通过（会签）", "req:review"),
            action("req.action.review_reject", "req.pool", "评审驳回（会签）", "req:review"),
            action("req.action.accept", "req.pool", "验收", "req:review"),
            action("req.action.close", "req.pool", "关闭需求", "req:review"),
            action("req.action.schedule", "req.pool", "排期", "release:plan"),
            // r22 校正：「开始开发」原来沿用 req:split，而 PRODUCT 对 req:split 是 RESTRICTED、
            // 对 req:deliver 是 DENY。判定改由本动作的 ACTION 格承担后，沿用旧码会把「不能
            // 开始开发」静默变成「可以」——那是扩权，所以这里必须写 req:deliver（产品经理/研发
            // 可开始开发，PRODUCT 仍被别的行挡住）。
            action("req.action.start_dev", "req.pool", "开始开发", "req:deliver"),
            action("req.action.decompose", "req.pool", "分解需求", "req:split"),
            action("req.action.assign_vendor", "req.pool", "指派厂商", "req:assign-vendor"),
            action("req.action.deliver", "req.pool", "提交交付", "req:deliver"),
            action("req.action.merge", "req.pool", "合并", "req:deliver"),
            action("req.action.finish", "req.pool", "完成", "req:deliver"),
            // r22 校正：QA 对 req:deliver 是 DENY，沿用旧码会让提测人自己提不了测；「提测」
            // 的语义归属是 test:quick，与下面的 test_pass 同一档位。
            action("req.action.submit_test", "req.pool", "提测", "test:quick"),
            action("req.action.test_pass", "req.pool", "测试通过", "test:quick"),

            // ---- 迭代 req.iteration ----
            action("task.action.create", "req.iteration", "新建任务", "task:manage"),
            action("task.action.assign", "req.iteration", "指派任务", "task:manage"),
            action("task.action.update", "req.iteration", "编辑任务", "task:manage"),
            action("task.action.close", "req.iteration", "关闭任务", "task:manage"),
            action("sprint.action.create", "req.iteration", "新建迭代", "task:manage"),
            action("sprint.action.close", "req.iteration", "关闭迭代", "task:manage"),

            // ---- 用例库 test.cases ----
            action("test.action.case_edit", "test.cases", "维护用例", "case:manage"),
            action("test.action.case_review", "test.cases", "用例评审结论", "CASE_REVIEW_DECIDE"),

            // ---- 测试计划 test.plans ----
            action("test.action.plan_create", "test.plans", "新建测试计划", "plan:edit"),
            action("test.action.plan_approve", "test.plans", "审批测试计划", "plan:approve"),
            action("test.action.execute", "test.plans", "执行测试", "plan:execute"),

            // ---- 测试报告 test.reports ----
            action("test.action.report_sign", "test.reports", "签发测试报告", "report:sign"),

            // ---- 质量门 qa.gates ----
            action("test.action.gate_waive", "qa.gates", "审批质量门豁免", "waiver:approve"),

            // ---- 缺陷 defect.list ----
            action("defect.action.create", "defect.list", "新建缺陷", "defect:report"),
            action("defect.action.assign", "defect.list", "指派缺陷", "defect:handle"),
            action("defect.action.handle", "defect.list", "开始处理", "defect:handle"),
            action("defect.action.resolve", "defect.list", "修复完成", "defect:handle"),
            action("defect.action.verify", "defect.list", "验证通过", "defect:handle"),
            action("defect.action.reopen", "defect.list", "重新打开", "defect:handle"),
            action("defect.action.close", "defect.list", "关闭缺陷", "defect:handle"),

            // ---- 版本计划 req.release ----
            action("release.action.create", "req.release", "新建版本", "release:plan"),
            action("release.action.update", "req.release", "编辑版本", "release:plan"),
            action("release.action.approve", "req.release", "审批版本", "release:plan"),
            action("release.action.close", "req.release", "关闭版本", "release:plan"),

            // ---- 发布单 deploy.orders ----
            action("deploy.action.create", "deploy.orders", "新建发布单", "deploy:create"),
            action("deploy.action.execute", "deploy.orders", "执行发布", "deploy:execute"),
            highRisk("deploy.action.force_release", "deploy.orders", "强发（跳过质量门）",
                    "deploy:force"),

            // ---- 协作 ----
            action("wiki.action.edit", "collab.wiki", "编辑 Wiki", "WIKI_EDIT"),
            action("wiki.action.admin", "collab.wiki", "管理空间", "WIKI_ADMIN"),
            action("gantt.action.edit", "collab.gantt", "调整排期", "GANTT_EDIT"),
            action("flow.action.config", "collab.value-stream", "配置阶段", "FLOW_CONFIG"),

            // ---- 自助报表 ana.reports ----
            action("report.action.generate", "ana.reports", "生成报表", "report:generate"),
            action("report.action.define", "ana.reports", "定义报表", "report:generate"),
            action("report.action.sign", "ana.reports", "签发报表", "report:sign"),
            action("report.action.export", "ana.reports", "导出报表", "report:export"),
            action("report.action.share", "ana.reports", "共享报表", "REPORT_SHARE"),
            action("report.action.publish", "ana.reports", "发布报表", "REPORT_SHARE"),
            action("portal.action.config", "ana.portal", "配置工作台", "PORTAL_CONFIG"),

            // ---- 设置 ----
            action("org.action.create", "admin.orgs", "新建组织", "org:manage"),
            action("org.action.update", "admin.orgs", "编辑组织", "org:manage"),
            action("org.action.delete", "admin.orgs", "删除组织", "org:manage"),
            action("org.action.move_users", "admin.orgs", "迁移人员", "org:manage"),
            action("user.action.create", "admin.users", "新建用户", "org:manage"),
            action("user.action.update", "admin.users", "编辑用户", "org:manage"),
            action("user.action.reset_password", "admin.users", "重置密码", "org:manage"),
            highRisk("user.action.disable", "admin.users", "停用用户", "org:manage"),
            action("role.action.create", "admin.roles", "新建角色", "org:manage"),
            action("role.action.update", "admin.roles", "编辑/删除角色", "org:manage"),
            action("role.action.status", "admin.roles", "启停角色", "org:manage"),
            highRisk("role.action.grant", "admin.roles", "配置角色授权", "org:manage"),
            action("vendor.action.manage", "admin.vendors", "维护厂商与域", "org:manage"),
            action("workflow.action.manage", "admin.workflows", "维护流程定义",
                    "workflow:manage"),
            action("integration.action.manage", "admin.integrations", "维护集成配置",
                    "integration:manage"),
            action("apiclient.action.manage", "admin.api-clients", "维护 API 客户端",
                    "apiclient:manage"));

    private ActionRegistry() {
    }

    /** Every action, in page/sort order. */
    public static List<ActionDef> actions() {
        return ACTIONS;
    }

    public static Optional<ActionDef> action(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        for (ActionDef action : ACTIONS) {
            if (action.code().equals(code)) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }

    /**
     * Wire verbs that name a <b>state transition</b> but no grantable capability of their own.
     *
     * <p>The defect loop is driven by {@link StateTables} verbs ({@code start}, {@code submit_verify},
     * {@code verify_pass}, {@code verify_reject}, {@code close_duplicate}), while the authorizable
     * actions of that page are the capabilities those transitions need ({@code handle},
     * {@code resolve}, {@code verify}, {@code close}). Without this map the platform administrator
     * — who is ALLOW on every registered action — is denied on {@code POST /defects/{id}/actions}
     * because {@code defect.action.start} is not a code at all, and the deny is indistinguishable
     * from a real "not granted". Canonicalising first keeps one grant subject per capability:
     * granting "处理缺陷" covers both starting work and receiving a verification rejection.
     *
     * <p>Verbs with no alias stay unknown and are denied (fail-closed), so a new state verb cannot
     * silently inherit a capability by accident.
     */
    private static final java.util.Map<String, String> TRANSITION_ALIASES = java.util.Map.of(
            "defect.action.start", "defect.action.handle",
            "defect.action.submit_verify", "defect.action.resolve",
            "defect.action.verify_pass", "defect.action.verify",
            "defect.action.verify_reject", "defect.action.handle",
            "defect.action.close_duplicate", "defect.action.close");

    /** Canonical grant subject for a wire action code; the code itself when no alias applies. */
    public static String canonical(String code) {
        return code == null ? null : TRANSITION_ALIASES.getOrDefault(code, code);
    }

    public static boolean isRegistered(String code) {
        return action(code).isPresent();
    }

    /** Fail-fast accessor for the interceptor and the declaration validator. */
    public static ActionDef require(String code) {
        return action(code).orElseThrow(() -> new IllegalArgumentException(
                "unregistered action code: " + code));
    }

    /** Actions of one page, in sort order; empty for an unknown page. */
    public static List<ActionDef> of(String menuCode) {
        List<ActionDef> result = new ArrayList<>();
        for (ActionDef action : ACTIONS) {
            if (action.menuCode().equals(menuCode)) {
                result.add(action);
            }
        }
        return List.copyOf(result);
    }

    public static List<String> codes() {
        return ACTIONS.stream().map(ActionDef::code).toList();
    }

    /** Page codes that own at least one action — a superset check for the registry test. */
    public static Set<String> menus() {
        return Set.copyOf(new LinkedHashSet<>(ACTIONS.stream().map(ActionDef::menuCode).toList()));
    }

    private static ActionDef action(String code, String menuCode, String name,
                                    String... functionCodes) {
        return new ActionDef(code, menuCode, name, List.of(functionCodes), false,
                sortOf(menuCode));
    }

    private static ActionDef highRisk(String code, String menuCode, String name,
                                      String... functionCodes) {
        return new ActionDef(code, menuCode, name, List.of(functionCodes), true,
                sortOf(menuCode));
    }

    /** Sort = position of the action inside its page's already-ordered list (10, 20, 30...). */
    private static int sortOf(String menuCode) {
        int next = SORTS.merge(menuCode, 10, Integer::sum);
        return next - 10;
    }
}
