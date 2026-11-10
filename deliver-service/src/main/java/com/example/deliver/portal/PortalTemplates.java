package com.example.deliver.portal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.platform.common.tenant.TenantContext;

/**
 * The nine workbench templates plus the unknown-role fallback.
 *
 * <p>Template choice is a server decision — the frontend branches on nothing. A vendor comes
 * <b>before</b> the role, because {@code vendor1} is seeded as {@code PMO}: checking the role first
 * would hand a vendor the PMO workbench and expose cross-vendor tiles the row scope then silently
 * empties. {@code vendorId != null} is the discriminator; the role list only breaks ties among
 * tenant-internal roles, in the documented priority order.</p>
 *
 * <p>Every card is a set of {@code AnalyticsMetrics} keys, never SQL and never a percentage
 * computed here: the workbench shows the same numbers as the analytics pages by construction
 * (AC-4). Card keys are globally unique so a card-key config row can never be ambiguous.</p>
 */
public final class PortalTemplates {

    public static final String FALLBACK_CODE = "fallback";
    public static final String VENDOR_CODE = "vendor";
    public static final int MAX_CARDS = 4;

    /** One workbench card: a title, a destination, and the metric keys it renders. */
    public record CardDef(String key, String title, String link, String hint, List<String> metrics) {

        public CardDef {
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
        }
    }

    /** One count tile above the sections. */
    public record TodoDef(String key, String label, String metric, String link) {
    }

    /** One shortcut card. */
    public record ActionDef(String key, String label, String link, String hint) {
    }

    /** A template: 4 metric cards, 0..3 count tiles, 2..3 shortcuts, 0..1 note lines. */
    public record Role(String code, String label, String todoMode, List<CardDef> cards,
                       List<TodoDef> todos, List<ActionDef> actions, List<String> messages,
                       String listTitle) {

        public Role {
            cards = List.copyOf(cards);
            todos = List.copyOf(todos);
            actions = List.copyOf(actions);
            messages = List.copyOf(messages);
        }

        public CardDef card(String key) {
            for (CardDef card : cards) {
                if (card.key().equals(key)) {
                    return card;
                }
            }
            return null;
        }

        public List<String> cardKeys() {
            List<String> keys = new ArrayList<>(cards.size());
            for (CardDef card : cards) {
                keys.add(card.key());
            }
            return keys;
        }
    }

    /** Template priority when a user carries several roles. */
    private static final List<String> ROLE_ORDER = List.of(
            "PLATFORM_ADMIN", "PRODUCT", "PMO", "SUB_BUSINESS_LEADER", "SUB_BUSINESS",
            "BUSINESS", "DEV", "QA", "OPS", "MGMT");

    private static final Map<String, Role> ROLES = new LinkedHashMap<>();

    static {
        ROLES.put("admin", new Role("admin", "平台管理工作台", PortalViews.TODO_FULL,
                List.of(card("admin.members", "成员规模", "/admin/users", "按角色统计的在册成员", "org.memberCount", "workflow.enabled"),
                        card("admin.req", "需求总览", "/requirements", "全租户需求总量与完成率", "req.total", "req.completionRate"),
                        card("admin.approval", "审批效率", "/approvals", "审批单量与通过率", "approval.total", "approval.passRate"),
                        card("admin.quality", "质量健康", "/quality", "测试通过率与缺陷密度", "quality.passRate", "quality.defectDensity")),
                List.of(todo("admin.pending", "待处理审批", "approval.total", "/approvals"),
                        todo("admin.mine", "我的待办", "req.mineOpen", "/requirements")),
                List.of(action("admin.config", "门户配置", "/admin/portal", "配置各角色可见卡片"),
                        action("admin.perms", "权限矩阵", "/admin/permissions", "角色×功能码准入"),
                        action("admin.reports", "全部报表", "/reports", "查看与治理报表")),
                List.of("门户卡片按角色配置，变更即时生效。"),
                "全部报表"));

        ROLES.put("product", new Role("product", "产品工作台", PortalViews.TODO_FULL,
                List.of(card("product.pool", "需求池", "/requirements", "需求总量与入池率", "req.total", "req.inPoolRate"),
                        card("product.flow", "流转压力", "/requirements?state=pending", "待测试与逾期需求", "req.pendingTest", "req.overdueCount"),
                        card("product.cycle", "交付周期", "/analytics", "平均与 P90 交付周期", "cycle.avg", "cycle.p90"),
                        card("product.quality", "质量", "/quality", "测试通过率与缺陷密度", "quality.passRate", "quality.defectDensity")),
                List.of(todo("product.test", "待测试", "req.pendingTest", "/requirements?state=pending"),
                        todo("product.overdue", "逾期需求", "req.overdueCount", "/requirements?overdue=1")),
                List.of(action("product.new", "新建报表", "/reports/new", "从数据集拖拽生成"),
                        action("product.req", "需求列表", "/requirements", "全部需求"),
                        action("product.metrics", "指标口径", "/metrics", "查看计算公式")),
                List.of("报表只有可见性档位，不改变数据行级范围。"),
                "我的报表"));

        ROLES.put("pmo", new Role("pmo", "项目管理工作台", PortalViews.TODO_FULL,
                List.of(card("pmo.delivery", "按期交付", "/analytics", "完成数与按期率", "delivery.onTime", "vendor.onTimeRate"),
                        card("pmo.vendor", "厂商表现", "/vendors", "完成需求与准时率", "vendor.accepted", "vendor.onTimeRate"),
                        card("pmo.approval", "审批负荷", "/approvals", "审批单量与平均耗时", "approval.total", "approval.avgHours"),
                        card("pmo.cycle", "周期趋势", "/analytics", "平均与 P90 交付周期", "cycle.avg", "cycle.p90")),
                List.of(todo("pmo.approval", "待审批", "approval.total", "/approvals"),
                        todo("pmo.overdue", "逾期需求", "req.overdueCount", "/requirements?overdue=1")),
                List.of(action("pmo.reports", "报表中心", "/reports", "组合与厂商报表"),
                        action("pmo.gate", "质量门禁", "/quality", "门禁与豁免"),
                        action("pmo.approvals", "审批中心", "/approvals", "待办审批")),
                List.of("厂商模板优先于角色模板：厂商账号即使持有 PMO 角色也进入厂商工作台。"),
                "我的报表"));

        ROLES.put("sub-business", new Role("sub-business", "子业务工作台", PortalViews.TODO_FULL,
                List.of(card("sub.req", "需求进展", "/requirements", "需求总量与完成率", "req.total", "req.completionRate"),
                        card("sub.cycle", "交付周期", "/analytics", "平均交付周期", "cycle.avg"),
                        card("sub.approval", "审批", "/approvals", "审批单量与通过率", "approval.total", "approval.passRate"),
                        card("sub.quality", "质量", "/quality", "测试通过率", "quality.passRate")),
                List.of(todo("sub.test", "待测试", "req.pendingTest", "/requirements?state=pending"),
                        todo("sub.approval", "待审批", "approval.total", "/approvals")),
                List.of(action("sub.reports", "报表中心", "/reports", "本业务报表"),
                        action("sub.req", "需求列表", "/requirements", "全部需求")),
                List.of("可见范围由域与厂商行级隔离决定。"),
                "我的报表"));

        ROLES.put("business", new Role("business", "业务工作台", PortalViews.TODO_FULL,
                List.of(card("business.req", "需求规模", "/requirements", "总量与新增", "req.total", "req.newCount"),
                        card("business.pool", "入池率", "/requirements", "进入交付池的比例", "req.inPoolRate"),
                        card("business.vendor", "厂商交付", "/vendors", "完成需求与准时率", "vendor.accepted", "vendor.onTimeRate"),
                        card("business.quality", "质量", "/quality", "测试通过率", "quality.passRate")),
                List.of(todo("business.new", "新增需求", "req.newCount", "/requirements?sort=new")),
                List.of(action("business.reports", "报表中心", "/reports", "业务报表"),
                        action("business.req", "需求列表", "/requirements", "全部需求")),
                List.of("指标口径统一在指标字典中维护。"),
                "我的报表"));

        ROLES.put("dev", new Role("dev", "研发工作台", PortalViews.TODO_FULL,
                List.of(card("dev.mine", "我的待办", "/requirements?mine=1", "待处理与待测试", "req.mineOpen", "req.pendingTest"),
                        card("dev.effort", "人天投入", "/analytics", "预估与实际人天", "effort.estimate", "effort.actual"),
                        card("dev.cycle", "交付周期", "/analytics", "平均交付周期", "cycle.avg"),
                        card("dev.quality", "质量", "/quality", "缺陷数与通过率", "quality.defects", "quality.passRate")),
                List.of(todo("dev.mine", "我的待处理", "req.mineOpen", "/requirements?mine=1")),
                List.of(action("dev.tasks", "我的任务", "/requirements?mine=1", "分配给我的需求"),
                        action("dev.reports", "报表中心", "/reports", "我的报表")),
                List.of("个人卡片只读报表合计，不重复计算。"),
                "我的报表"));

        ROLES.put("qa", new Role("qa", "测试工作台", PortalViews.TODO_FULL,
                List.of(card("qa.pending", "待测试", "/requirements?state=pending", "等待测试的需求数", "req.pendingTest"),
                        card("qa.pass", "通过率", "/quality", "测试通过率", "quality.passRate"),
                        card("qa.density", "缺陷", "/quality", "缺陷数与缺陷密度", "quality.defects", "quality.defectDensity"),
                        card("qa.release", "发布", "/quality", "发布成功率", "quality.releaseSuccess")),
                List.of(todo("qa.test", "待测试", "req.pendingTest", "/requirements?state=pending"),
                        todo("qa.defect", "缺陷数", "quality.defects", "/quality")),
                List.of(action("qa.quality", "质量中心", "/quality", "计划与门禁"),
                        action("qa.reports", "报表中心", "/reports", "质量报表")),
                List.of("缺陷密度 = 缺陷数 ÷ 需求数，口径见指标字典。"),
                "我的报表"));

        ROLES.put("ops", new Role("ops", "运维工作台", PortalViews.TODO_FULL,
                List.of(card("ops.release", "发布成功率", "/quality", "发布成功比例", "quality.releaseSuccess"),
                        card("ops.delivery", "交付健康", "/analytics", "按期完成与准时率", "delivery.onTime", "vendor.onTimeRate"),
                        card("ops.approval", "审批耗时", "/approvals", "审批单量与 P90 耗时", "approval.total", "approval.p90Hours"),
                        card("ops.cycle", "交付周期", "/analytics", "平均交付周期", "cycle.avg")),
                List.of(todo("ops.approval", "待审批", "approval.total", "/approvals")),
                List.of(action("ops.quality", "质量中心", "/quality", "发布与门禁"),
                        action("ops.reports", "报表中心", "/reports", "运维报表")),
                List.of("发布数据来自质量域，不在门户二次计算。"),
                "我的报表"));

        ROLES.put("mgmt", new Role("mgmt", "管理驾驶舱", PortalViews.TODO_READONLY,
                List.of(card("mgmt.req", "需求总量", "/analytics", "总量与完成率", "req.total", "req.completionRate"),
                        card("mgmt.cycle", "交付周期", "/analytics", "平均与 P90 周期", "cycle.avg", "cycle.p90"),
                        card("mgmt.delivery", "交付", "/analytics", "按期完成与厂商完成数", "delivery.onTime", "vendor.accepted"),
                        card("mgmt.quality", "质量", "/quality", "通过率与缺陷密度", "quality.passRate", "quality.defectDensity")),
                List.of(),
                List.of(action("mgmt.reports", "报表中心", "/reports", "经营报表")),
                List.of("管理视图为只读，不提供待办计数。"),
                "我的报表"));

        ROLES.put(VENDOR_CODE, new Role(VENDOR_CODE, "厂商工作台", PortalViews.TODO_FULL,
                List.of(card("vendor.accepted", "完成需求", "/requirements", "本厂商完成需求数", "vendor.accepted"),
                        card("vendor.ontime", "准时率", "/analytics", "本厂商按期交付比例", "vendor.onTimeRate"),
                        card("vendor.cycle", "交付周期", "/analytics", "平均交付周期", "cycle.avg"),
                        card("vendor.quality", "质量", "/quality", "测试通过率", "quality.passRate")),
                List.of(todo("vendor.mine", "我的待处理", "req.mineOpen", "/requirements?mine=1")),
                List.of(action("vendor.req", "我的需求", "/requirements?mine=1", "分配给我的需求"),
                        action("vendor.reports", "报表中心", "/reports", "本厂商报表")),
                List.of("厂商工作台只显示本厂商数据，模板先于角色判定。"),
                "我的报表"));

        ROLES.put(FALLBACK_CODE, new Role(FALLBACK_CODE, "通用工作台", PortalViews.TODO_FALLBACK,
                List.of(card("fallback.req", "需求总量", "/requirements", "可见范围内的需求数", "req.total"),
                        card("fallback.test", "待测试", "/requirements?state=pending", "等待测试的需求数", "req.pendingTest"),
                        card("fallback.cycle", "交付周期", "/analytics", "平均交付周期", "cycle.avg"),
                        card("fallback.quality", "质量", "/quality", "测试通过率", "quality.passRate")),
                List.of(todo("fallback.test", "待测试", "req.pendingTest", "/requirements?state=pending")),
                List.of(action("fallback.req", "需求列表", "/requirements", "全部需求"),
                        action("fallback.reports", "报表中心", "/reports", "我的报表")),
                List.of("当前角色没有专属模板，已加载通用工作台。"),
                "我的报表"));
    }

    private PortalTemplates() {
    }

    /** The template for this request: admin first, then vendor, then the highest-priority role. */
    public static Role resolve(TenantContext.Principal principal) {
        if (principal == null || principal.roles() == null) {
            return ROLES.get(FALLBACK_CODE);
        }
        List<String> roles = principal.roles();
        if (roles.contains("PLATFORM_ADMIN")) {
            return ROLES.get("admin");
        }
        if (principal.vendorId() != null) {
            return ROLES.get(VENDOR_CODE);
        }
        for (String role : ROLE_ORDER) {
            if (roles.contains(role)) {
                return byRole(role);
            }
        }
        return ROLES.get(FALLBACK_CODE);
    }

    /** Template by canonical code (case-insensitive); unknown returns the fallback. */
    public static Role of(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return ROLES.get(FALLBACK_CODE);
        }
        Role role = ROLES.get(roleCode.trim().toLowerCase(Locale.ROOT));
        return role == null ? ROLES.get(FALLBACK_CODE) : role;
    }

    /** The template that corresponds to an upper-case {@code sys_role.code}, if any. */
    public static Role byRole(String roleCode) {
        if (roleCode == null) {
            return null;
        }
        return switch (roleCode.trim().toUpperCase(Locale.ROOT)) {
            case "PLATFORM_ADMIN" -> ROLES.get("admin");
            case "PRODUCT" -> ROLES.get("product");
            case "PMO" -> ROLES.get("pmo");
            case "SUB_BUSINESS_LEADER", "SUB_BUSINESS" -> ROLES.get("sub-business");
            case "BUSINESS" -> ROLES.get("business");
            case "DEV" -> ROLES.get("dev");
            case "QA" -> ROLES.get("qa");
            case "OPS" -> ROLES.get("ops");
            case "MGMT" -> ROLES.get("mgmt");
            default -> null;
        };
    }

    /** Template code is only "known" when it is a real template (fallback included). */
    public static boolean known(String roleCode) {
        return roleCode != null && ROLES.containsKey(roleCode.trim().toLowerCase(Locale.ROOT));
    }

    public static List<String> roleCodes() {
        return List.copyOf(ROLES.keySet());
    }

    private static CardDef card(String key, String title, String link, String hint, String... metrics) {
        return new CardDef(key, title, link, hint, List.of(metrics));
    }

    private static TodoDef todo(String key, String label, String metric, String link) {
        return new TodoDef(key, label, metric, link);
    }

    private static ActionDef action(String key, String label, String link, String hint) {
        return new ActionDef(key, label, link, hint);
    }
}
