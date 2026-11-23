package com.example.platform.contract;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * The 37 authorizable pages, in one place (r18; r39 appends the automation page).
 *
 * <p>A page code is the subject a role is granted in {@code sys_role_permission}
 * ({@code subject_type = 'MENU'}) and the thing the frontend route guard, the navigation and the
 * role-permission tree are all keyed by. Before r18 the "page" was implicitly a function code, so
 * two pages sharing a function could never be granted separately and a page with no write action
 * had no code at all.</p>
 *
 * <p>Why code and not a table: the same list feeds the seed ({@code MENU = max(绑定功能码)}), the
 * runtime page admission and the frontend tree through {@code GET /auth/me/permissions}. A
 * database-only registry would let the seed and the guard drift apart with no compile error;
 * {@code scripts/tests/rbac/s1-registry-three-way.mjs} compares this class, {@code web/src/layouts/nav.ts}
 * and the {@code sys_menu} table row by row.</p>
 *
 * <p>{@code functionCodes} are the legacy function codes the page is bound to: the page is visible
 * only when its own MENU grant is not DENY <b>and</b> no bound function is DENY (r18 AC-16, the
 * double condition). An empty list means "no legacy code gates this page".</p>
 *
 * <p>{@code routeName} is the Vue router name, not the URL: several pages share one URL
 * ({@code /requirements} is both 我的任务 and 需求池), and the URL alone cannot tell them apart.</p>
 */
public final class MenuRegistry {

    /**
     * One authorizable page.
     *
     * @param code          stable page code, the grant subject ({@code <module>.<page>})
     * @param module        tree grouping name shown on the left of the role-permission screen
     * @param name          Chinese label, identical to the navigation label
     * @param routeName     Vue router name; empty when the page has no route of its own
     * @param functionCodes legacy function codes bound to the page (empty = none)
     * @param sort          display order inside the module
     */
    public record MenuPage(String code, String module, String name, String routeName,
                           List<String> functionCodes, int sort) {

        public MenuPage {
            functionCodes = List.copyOf(functionCodes);
        }
    }

    private static final List<MenuPage> PAGES = List.of(
            // ---- 我的 ----
            new MenuPage("me.todo", "我的", "我的待办", "approvals", List.of(), 10),
            new MenuPage("me.tasks", "我的", "我的任务", "requirements", List.of(), 20),
            new MenuPage("me.workbench", "我的", "工作台", "workbench",
                    List.of("plan:execute", "case:manage"), 30),

            // ---- 需求域 ----
            new MenuPage("req.pool", "需求域", "需求池", "requirements", List.of(), 10),
            new MenuPage("req.create", "需求域", "提报需求", "requirement-create",
                    List.of("req:report:bus", "req:report:dev"), 20),
            new MenuPage("req.release", "需求域", "版本计划", "releases", List.of("release:plan"), 30),
            new MenuPage("req.iteration", "需求域", "迭代", "iterations",
                    List.of("task:manage"), 40),

            // ---- 交付域 ----
            new MenuPage("defect.list", "交付域", "缺陷", "defects", List.of(), 10),
            new MenuPage("test.cases", "交付域", "用例库", "testcases",
                    List.of("case:manage"), 20),
            new MenuPage("test.plans", "交付域", "测试计划", "test-plans",
                    List.of("plan:manage"), 30),
            new MenuPage("test.strategies", "交付域", "测试策略", "test-strategies",
                    List.of("strategy:manage"), 40),
            new MenuPage("test.reports", "交付域", "测试报告", "test-reports",
                    List.of("report:generate", "report:sign"), 50),
            new MenuPage("test.envs", "交付域", "环境台账", "test-envs",
                    List.of("env:manage"), 60),
            new MenuPage("test.executors", "交付域", "执行器", "test-executors",
                    List.of("TEST_EXECUTOR_VIEW"), 70),
            new MenuPage("deploy.orders", "交付域", "发布单", "deploy-orders",
                    List.of("deploy:create"), 80),

            // ---- 协作 ----
            new MenuPage("collab.gantt", "协作", "甘特", "gantt",
                    List.of("GANTT_VIEW"), 10),
            new MenuPage("collab.wiki", "协作", "Wiki", "wiki",
                    List.of("WIKI_VIEW"), 20),
            new MenuPage("collab.value-stream", "协作", "价值流", "value-stream",
                    List.of("FLOW_VIEW"), 30),

            // ---- 分析域 ----
            new MenuPage("qa.trace", "分析域", "追溯矩阵", "traceability",
                    List.of("trace:view"), 10),
            new MenuPage("qa.gates", "分析域", "质量门总览", "quality-gates",
                    List.of("gate:view"), 20),
            new MenuPage("qa.metrics", "分析域", "质量指标", "quality-metrics",
                    List.of("qualitymetric:view"), 30),
            new MenuPage("defect.board", "分析域", "质量看板", "defect-board",
                    List.of("defect:board"), 40),
            new MenuPage("code.policies", "分析域", "分支策略", "code-policies",
                    List.of("CCODE_POLICY_VIEW"), 50),
            new MenuPage("ana.portal", "分析域", "岗位工作台", "portal",
                    List.of("PORTAL_VIEW"), 60),
            new MenuPage("ana.dashboard", "分析域", "数据看板", "analytics-dashboard",
                    List.of("PORTAL_VIEW"), 70),
            new MenuPage("ana.reports", "分析域", "自助报表", "report-list",
                    List.of("REPORT_VIEW"), 80),

            // ---- 设置 ----
            new MenuPage("admin.orgs", "设置", "组织与租户", "admin-orgs",
                    List.of("tenant:manage", "org:manage"), 10),
            new MenuPage("admin.users", "设置", "用户与角色", "admin-users",
                    List.of("org:manage"), 20),
            new MenuPage("admin.vendors", "设置", "厂商与域", "admin-vendors",
                    List.of("org:manage"), 30),
            new MenuPage("admin.permissions", "设置", "权限总览", "admin-permissions",
                    List.of("org:manage"), 40),
            new MenuPage("admin.roles", "设置", "角色与权限", "admin-roles",
                    List.of("org:manage"), 50),
            new MenuPage("admin.workflows", "设置", "流程定义", "admin-workflows",
                    List.of("workflow:manage"), 60),
            new MenuPage("admin.api-clients", "设置", "API 客户端", "admin-api-clients",
                    List.of("apiclient:manage"), 70),
            new MenuPage("admin.integrations", "设置", "集成配置", "admin-integrations",
                    List.of("integration:manage"), 80),
            new MenuPage("admin.metrics", "设置", "指标字典", "admin-metrics",
                    List.of("metric:manage"), 90),
            new MenuPage("admin.audit", "设置", "审计日志", "admin-audit",
                    List.of("audit:view"), 100),
            // r39：自动化规则页。module 沿用既有「设置」（= 前端「平台管理」组），新增模块会让
            // RoleAdminService 的树多出一个只有一页的分组，也会破坏「6 modules」的注册表契约。
            new MenuPage("automation.rules", "设置", "自动化规则", "automation-rules",
                    List.of("automation:manage"), 90));

    private MenuRegistry() {
    }

    /** Every page, in tree order (module order first, then {@code sort}). */
    public static List<MenuPage> pages() {
        return PAGES;
    }

    /** Distinct module names, in the order the tree shows them. */
    public static List<String> modules() {
        return List.copyOf(new LinkedHashSet<>(PAGES.stream().map(MenuPage::module).toList()));
    }

    public static Optional<MenuPage> page(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        for (MenuPage page : PAGES) {
            if (page.code().equals(code)) {
                return Optional.of(page);
            }
        }
        return Optional.empty();
    }

    public static boolean isRegistered(String code) {
        return page(code).isPresent();
    }

    public static List<String> codes() {
        return PAGES.stream().map(MenuPage::code).toList();
    }

    /**
     * Function codes no page is bound to, in {@link PermissionMatrix#functions()} order (r20).
     *
     * <p>These 30 codes are still grantable and still derive their page's actions, but no page
     * carries them, so the role-permission tree never showed them: saving the tree as-is wrote
     * {@code DENY} over the 30 hidden levels. The set is derived here — from the same
     * {@code MenuPage.functionCodes()} the seed and the page gate read — so the read model, the
     * probe scripts and the frontend cannot drift (r20 BUG-1).</p>
     */
    public static List<String> unboundFunctionCodes() {
        List<String> bound = PAGES.stream().flatMap(page -> page.functionCodes().stream()).toList();
        return PermissionMatrix.functions().stream()
                .filter(code -> !bound.contains(code))
                .toList();
    }

    /** Pages of one module, in sort order; empty for an unknown module. */
    public static List<MenuPage> of(String module) {
        return PAGES.stream().filter(page -> page.module().equals(module)).toList();
    }
}
