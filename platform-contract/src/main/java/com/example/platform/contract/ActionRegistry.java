package com.example.platform.contract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The 73 authorizable actions, in one place (r18; r39 appends the automation action).
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
            // r40 需求变更单：两个动作码都在 req.pool 页，但不进 16 条状态迁移矩阵
            // （RequirementActionMatrix.ACTIONS 是「状态迁移」，变更单是内容写入，两码正交）。
            // functionCodes 是 any-of：「发起」只给能拆需求的人（研发受限、产品/域负责人可申请），
            // 「应用」给能交付或能拆需求的人（研发/产品经理都可应用，域负责人不应用）。
            action("req.action.change_request", "req.pool", "发起变更", "req:split"),
            action("req.action.change_apply", "req.pool", "应用变更", "req:deliver", "req:split"),
            // r47 需求池运营：跟单与重复合并。两者都不进状态迁移矩阵（RequirementActionMatrix），
            // 是关系/内容写入，与 req.action.merge（RD 测试通过后进入 MERGED 的状态动作）正交——
            // 这也是为什么重复合并必须另起一个码：`req.action.merge` 已被 r17 的状态矩阵与
            // MR 确认合并路由占用，改它的 functionCodes 会同时改掉研发的 RD 合并能力。
            //
            // follow 借用 notification:read 作为「参与类」功能码：契约 §3.2 要求「任何能看见该需求的
            // 账号都能跟单」，而 11 个角色在矩阵里全部持有 notification:read 的 ALLOW；不新造功能码
            // （契约 §4 明确功能码表 61 不变）。留空 functionCodes 虽然也能全放行，但会被
            // RbacRegistryTest 的「每个动作必须指明授权功能码」冻结核对挡住——那是既有断言，不放宽。
            action("req.action.follow", "req.pool", "我也需要", "notification:read"),
            // 「合并重复需求」的准入 = PLATFORM_ADMIN / PMO / PRODUCT（契约 §3.3）。req:review 的
            // ALLOW 集正好是这四个角色 + SUB_BUSINESS_LEADER，因此这里再对域负责人显式 DENY
            // （RbacSeeder.ACTION_DENY_OVERRIDES），其余角色按 req:review 自然推导为 DENY。
            action("req.action.merge_duplicate", "req.pool", "合并重复需求", "req:review"),

            // ---- PDR 评审 pdr.review（r41）----
            // 「发起」与「汇总结论」是评审的组织权，沿用 req:review（产品经理/域负责人/PMO 已持有，
            // 与设计 §7 的三行完全一致）；「认领更换」沿用 req:assign-vendor（指派厂商的那批人）。
            // 「提交意见」与「意见转任务」的准入主体是「本单评审人 / 发起人」——那是运行时事实，
            // 矩阵表达不了，所以功能码只负责让这批人看见入口：req:review（产品经理/域负责人/PMO）
            // 并上 req:deliver（研发），两者任一 ALLOW 即可见；厂商两种都不持有，评审入口对厂商关闭，
            // 与设计 §7「评审人是项目组成员 + 产品经理」一致。服务层再按意见归属收一次口。
            action("req.action.pdr_review_start", "pdr.review", "发起 PDR 评审", "req:review"),
            action("req.action.pdr_review_opinion", "pdr.review", "提交评审意见",
                    "req:review", "req:deliver"),
            action("req.action.pdr_review_conclude", "pdr.review", "汇总评审结论", "req:review"),
            action("req.action.pdr_review_task", "pdr.review", "评审意见转任务",
                    "req:review", "req:deliver"),
            action("req.action.assign_vendor_change", "pdr.review", "更换承接厂商", "req:assign-vendor"),
            // 提交工时评估：设计 §7 要求「厂商账号（VENDOR_PM / 厂商研发）也能提交」。req:deliver
            // 覆盖内部 PMO/研发，但对 VENDOR_PM 是 DENY；task:manage 是厂商已持有的码（见
            // PermissionMatrix 的 VENDOR_PM 注释：写口收在 ACTION 层）。any-of 并集因此让三类账号
            // 都能看见入口，真正的边界仍在服务层：厂商账号必须 assignee_vendor_id == 我的 vendorId
            // （否则 403 OUT_OF_SCOPE），内部账号沿用 r26 的 req:deliver 判定。
            action("req.action.estimate_submit", "pdr.review", "提交工时评估",
                    "req:deliver", "task:manage"),

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
                    "apiclient:manage"),
            // r39：自动化规则页的写动作。highRisk=false —— HIGH_RISK 三条是 r18 冻结语义，
            // 新增第四条会改变 s1 的高危断言口径（TECHNICAL §10.1）。
            action("automation.action.manage", "automation.rules", "管理规则",
                    "automation:manage"));

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
