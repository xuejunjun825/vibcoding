package com.example.req.seed;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等种子：7 域 / 3 厂商 / 10 账号 / 完整需求链路 / 1 条测试计划（≥10 用例）/ 缺陷与指标定义。
 *
 * <p>为什么用 JDBC 批量而不是走状态机：种子要造"已经走到某状态"的历史数据，
 * 逐条走状态机会触发审批、通知、门禁等真实副作用，既慢又会把演示数据搅乱。
 * 这是 lint 白名单里唯一允许直接使用 JdbcTemplate 的业务包（L9）。</p>
 *
 * <p>幂等实现：每张表都先按业务键（code/username 等）查 id，查到就复用；
 * 返回的 counts 一律从库里重新 COUNT，所以第二次调用 counts 与第一次完全一致，
 * 且 skipped=true。</p>
 */
@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    private static final String TENANT_CODE = "t1";
    private static final String ADMIN_PASSWORD = "Admin@12345";
    private static final String USER_PASSWORD = "Passw0rd!";
    /** 协作/代码/测试治理的落库种子与回填校验都写在这个迁移里（V9 是唯一口径）。 */
    private static final String COLLAB_SEED_SOURCE = "db/migration/V9__ccode_cteam_ctest_p0.sql";
    /** 5 行质量指标（FPY/回归/逃逸/72h/回滚）的定义只在 V5 步 7，这里同样按段重放。 */
    private static final String QUALITY_SEED_SOURCE = "db/migration/V5__quality_management.sql";

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final Map<String, Set<String>> columnCache = new ConcurrentHashMap<>();

    public SeedService(JdbcTemplate jdbc, @Value("${platform.seed.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 已经种过：租户存在且至少有一条需求。 */
    public boolean alreadySeeded() {
        Long tenantId = findId("sys_tenant", row("code", TENANT_CODE));
        if (tenantId == null) {
            return false;
        }
        Long reqs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM requirement WHERE tenant_id = ?", Long.class, tenantId);
        return reqs != null && reqs > 0;
    }

    @Transactional
    public SeedResult seed(int scale) {
        if (!enabled) {
            throw new SeedDisabledException();
        }
        boolean skipped = alreadySeeded();
        // 幂等收敛（不是「已种过就整体跳过」）：ensure/link 逐行命中即跳过，所以重跑不会产生重复行，
        // 但能把后来新增/修正的种子行补齐到旧库上——例如 2026-11-09 T11 补的 vendor1 账号，
        // 若沿用整体 skip，旧库永远拿不到它，AC-4 的厂商账号登录就会一直失败。
        doSeed(Math.max(scale, 1));
        return skipped ? SeedResult.skipped(counts()) : SeedResult.seeded(counts());
    }

    public Map<String, Object> counts() {
        Map<String, Object> c = SeedResult.newCounts();
        Long tenantId = findId("sys_tenant", row("code", TENANT_CODE));
        if (tenantId == null) {
            c.put("domains", 0);
            return c;
        }
        c.put("tenantCode", TENANT_CODE);
        c.put("domains", count("biz_domain", tenantId));
        c.put("vendors", count("sys_vendor", tenantId));
        c.put("users", count("sys_user", tenantId));
        c.put("roles", count("sys_role", tenantId));
        c.put("requirements", count("requirement", tenantId));
        c.put("releases", count("release", tenantId));
        c.put("sprints", count("sprint", tenantId));
        c.put("tasks", count("task", tenantId));
        c.put("testModules", count("test_module", tenantId));
        c.put("testCases", count("test_case", tenantId));
        c.put("testPlans", count("test_plan", tenantId));
        c.put("defects", count("defect", tenantId));
        c.put("executions", count("test_execution", tenantId));
        c.put("planCases", jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_plan_case WHERE tenant_id = ?", Long.class, tenantId));
        c.put("metricDefinitions", count("metric_definition", tenantId));
        // 质量域（M6）：计数只加不改，platform-seed 的既有断言不受影响
        c.put("testStrategies", count("test_strategy", tenantId));
        c.put("testEnvs", count("test_env", tenantId));
        c.put("testRounds", count("test_plan_round", tenantId));
        c.put("planAssignments", count("plan_assignment", tenantId));
        c.put("qualityGates", count("quality_gate", tenantId));
        c.put("qualityGateRules", jdbc.queryForObject(
                "SELECT COUNT(*) FROM quality_gate_rule WHERE tenant_id = ?", Long.class, tenantId));
        c.put("loginUsers", SeedResult.loginUsers());
        return c;
    }

    // ------------------------------------------------------------------ 造数

    private void doSeed(int scale) {
        LocalDateTime now = LocalDateTime.now();

        long tenantId = ensure("sys_tenant",
                row("code", TENANT_CODE),
                row("name", "演示租户", "isolation_mode", "ROW", "status", "ACTIVE",
                        "created_at", now, "updated_at", now));

        // 组织：1 个根 + 3 个部门。
        // path 是「祖先 id 链」（/<root>/<dept>/），ScopeSql 与 AuthService 都用它做 LIKE 前缀子树查询；
        // 同时 uk_org_path(tenant_id, path) 唯一 —— 三个部门必须各自带自己的 id，否则三行同写
        // "/1/<root>/" 会 Duplicate entry '…-…/1/…/' for key 'sys_org.uk_org_path'（2026-11-09 T11 实测
        // platform-seed 首次即 500）。自增 id 只有 INSERT 后才知道，故先用按 code 唯一的占位 path 落库，
        // 再按真实 id 回填；对上一轮残留的行同样回填，保证 seed 幂等且能自愈。
        long rootOrg = ensure("sys_org",
                row("tenant_id", tenantId, "code", "HQ"),
                row("parent_id", null, "type", "ROOT", "path", "/1/", "sort_no", 1, "name", "集团总部", "status", "ACTIVE"));
        rewriteOrgPath(rootOrg);
        long supplyOrg = ensureDept(tenantId, rootOrg, "SUPPLY", "供应链部", 2);
        ensureDept(tenantId, rootOrg, "IT", "信息技术部", 3);
        ensureDept(tenantId, rootOrg, "QA", "质量保障部", 4);

        // 7 个业务域
        String[][] domainDefs = {
                {"PUR", "采购管理"}, {"INV", "库存管理"}, {"ORD", "订单管理"},
                {"LOG", "物流管理"}, {"SET", "结算管理"}, {"SUP", "供应商管理"}, {"MDM", "主数据管理"}};
        List<Long> domains = new ArrayList<>();
        int sort = 1;
        for (String[] d : domainDefs) {
            domains.add(ensure("biz_domain",
                    row("tenant_id", tenantId, "code", d[0]),
                    row("name", d[1], "enabled", 1, "sort_no", sort++)));
        }
        long domain1 = domains.get(0);

        // 3 家厂商
        long vendor1 = ensure("sys_vendor",
                row("tenant_id", tenantId, "code", "VEN-A"),
                row("name", "甲方科技", "contact", "vendor-a@example.com", "status", "ACTIVE"));
        ensure("sys_vendor",
                row("tenant_id", tenantId, "code", "VEN-B"),
                row("name", "乙方软件", "contact", "vendor-b@example.com", "status", "ACTIVE"));
        ensure("sys_vendor",
                row("tenant_id", tenantId, "code", "VEN-C"),
                row("name", "丙方信息", "contact", "vendor-c@example.com", "status", "ACTIVE"));

        // 10 个内置角色（PRD §6.1）
        String[][] roleDefs = {
                {"BUSINESS", "业务部门"}, {"SUB_BUSINESS", "二级企业业务"},
                {"SUB_BUSINESS_LEADER", "二级企业负责人"}, {"PRODUCT", "产品经理"},
                {"PMO", "项目·域(PMO)"}, {"DEV", "研发工程师"}, {"QA", "测试工程师"},
                {"OPS", "运维工程师"}, {"MGMT", "管理层"}, {"PLATFORM_ADMIN", "平台管理员"}};
        Map<String, Long> roles = new LinkedHashMap<>();
        for (String[] r : roleDefs) {
            roles.put(r[0], ensure("sys_role",
                    row("tenant_id", tenantId, "code", r[0]),
                    row("name", r[1], "builtin", 1)));
        }

        // 10 个账号：厂商隔离靠账号的 vendor_id（不是独立厂商角色）
        String[][] userDefs = {
                {"admin", "平台管理员", "PLATFORM_ADMIN", null},
                {"pm", "产品经理-张", "PRODUCT", null},
                {"tl1", "项目域负责人-李", "PMO", null},
                {"po1", "二级企业负责人-王", "SUB_BUSINESS_LEADER", null},
                {"dev1", "研发-赵", "DEV", null},
                {"dev2", "研发-钱", "DEV", null},
                {"qa1", "测试-孙", "QA", null},
                {"qa2", "测试-周", "QA", null},
                {"vendor1", "厂商管理员-钱", "PMO", "VEN-A"},
                {"vendor2", "厂商研发-郑", "DEV", "VEN-B"}};
        Map<String, Long> users = new LinkedHashMap<>();
        for (String[] u : userDefs) {
            String raw = "admin".equals(u[0]) ? ADMIN_PASSWORD : USER_PASSWORD;
            Map<String, Object> keys = row("tenant_id", tenantId, "username", u[0]);
            Map<String, Object> extra = row("name", u[1], "password_hash", bcrypt(raw),
                    "org_id", supplyOrg, "status", "ACTIVE",
                    "vendor_id", u[3] == null ? null
                            : findId("sys_vendor", row("tenant_id", tenantId, "code", u[3])));
            long uid = ensure("sys_user", keys, extra);
            users.put(u[0], uid);
            link("sys_user_role", row("tenant_id", tenantId, "user_id", uid, "role_id", roles.get(u[2])));
            for (Long d : domains) {
                link("sys_user_domain", row("tenant_id", tenantId, "user_id", uid, "domain_id", d));
            }
        }

        // 已有账号（例如早期由 T1 检查脚本建的 admin）可能 org_id 为空：ensure 命中即返回、不会改列，
        // 于是这类账号登录后 DataScope 的组织子树为空，建需求会撞 requirement.origin_org_id NOT NULL。
        // 种子在这里把「本租户缺组织归属的账号」补齐到供应链部，让幂等 seed 也能自愈历史数据。
        int healedOrgs = jdbc.update(
                "UPDATE sys_user SET org_id = ? WHERE tenant_id = ? AND org_id IS NULL", supplyOrg, tenantId);
        if (healedOrgs > 0) {
            log.info("seed: 为 {} 个已有账号补齐 org_id={}", healedOrgs, supplyOrg);
        }

        // 字典（前端状态/优先级文案的唯一来源）
        String[][] dicts = {
                {"priority", "P0", "紧急", "1"}, {"priority", "P1", "高", "2"},
                {"priority", "P2", "中", "3"}, {"priority", "P3", "低", "4"},
                {"req_status", "POOLED", "需求池", "1"}, {"req_status", "IN_DEV", "开发中", "2"},
                {"req_status", "PENDING_TEST", "待测试", "3"}, {"req_status", "TEST_PASSED", "测试通过", "4"}};
        for (String[] d : dicts) {
            link("sys_dict", row("tenant_id", tenantId, "type", d[0], "code", d[1], "label", d[2], "sort_no", d[3]));
        }

        // 默认审批流程（已发布）：四类单据都必须有可用流程。审批引擎找不到匹配定义时 start 会
        // 抛 NO_WORKFLOW —— 版本「提交发布审批」直接 400、发布单 submit 报 APPROVAL_NOT_CONFIGURED，
        // 而种子正是 verify 的前置数据，所以这里一次种全。
        //   REQ_SUBMIT  req_type=BUS  PRODUCT 会签（提报；PDR/RD 走 is_default 兜底）
        //   REQ_REVIEW  req_type=ANY  PRODUCT 会签（复核）
        //   RELEASE     req_type=ANY  PRODUCT 会签（版本发布）
        //   REQ_DEPLOY  req_type=ANY  PLATFORM_ADMIN 会签（发布单放行）
        // 键仍是 (tenant,biz_type,req_type,domain_id,version)：已种过的库命中 REQ_SUBMIT/BUS 那一行，
        // 不会多出第二份默认流程。
        String apprProduct = approvalDefinition("产品评审", "PRODUCT");
        String apprAdmin = approvalDefinition("平台管理员审批", "PLATFORM_ADMIN");
        seedWorkflow(tenantId, "REQ_SUBMIT", "BUS", "业务需求默认审批", apprProduct, users.get("admin"), now);
        seedWorkflow(tenantId, "REQ_REVIEW", "ANY", "需求复核默认审批", apprProduct, users.get("admin"), now);
        seedWorkflow(tenantId, "RELEASE", "ANY", "版本发布默认审批", apprProduct, users.get("admin"), now);
        seedWorkflow(tenantId, "REQ_DEPLOY", "ANY", "发布单默认审批", apprAdmin, users.get("admin"), now);

        // 完整链路：BUS → 2 个 PDR → 4 个 RD
        LocalDate today = LocalDate.now();
        long bus = requirement(tenantId, "BUS", null, domain1, supplyOrg, 1, users.get("pm"),
                "供应链采购协同需求", "BUS-0001", "POOLED", "P1", null, today.plusDays(30), now);
        long pdr1 = requirement(tenantId, "PDR", bus, domain1, supplyOrg, 2, users.get("po1"),
                "采购申请单移动审批", "PDR-0001", "SCHEDULED", "P1", 5, today.plusDays(20), now);
        long pdr2 = requirement(tenantId, "PDR", bus, domain1, supplyOrg, 3, users.get("po1"),
                "采购订单批量导入", "PDR-0002", "IN_DEV", "P2", 8, today.plusDays(25), now);
        // 规模夹具必须挂在自己的 PDR 下：挂到 PDR-0001 会让父汇总加载 10 万个子需求，
        // 且子需求人天之和超出 DECIMAL 列范围（T11 实测 insert 500）。
        long pdrBulk = requirement(tenantId, "PDR", bus, domain1, supplyOrg, 1, users.get("po1"),
                "性能基线父需求", "PDR-BULK-0001", "SCHEDULED", "P3", null, today.plusDays(30), now);
        long rd1 = requirement(tenantId, "RD", pdr1, domain1, supplyOrg, 12, users.get("dev1"),
                "采购申请单列表接口", "RD-0001", "IN_DEV", "P1", 3, today.plusDays(10), now);
        long rd2 = requirement(tenantId, "RD", pdr1, domain1, supplyOrg, 12, users.get("dev2"),
                "采购申请单审批接口", "RD-0002", "TODO", "P1", 3, today.plusDays(12), now);
        long rd3 = requirement(tenantId, "RD", pdr2, domain1, supplyOrg, 3, users.get("dev2"),
                "订单导入模板解析", "RD-0003", "PENDING_TEST", "P2", 5, today.plusDays(18), now);
        long rd4 = requirement(tenantId, "RD", pdr2, domain1, supplyOrg, 3, users.get("dev1"),
                "订单导入错误报告", "RD-0004", "DONE", "P2", 3, today.minusDays(2), now);
        jdbc.update("UPDATE requirement SET mr_url = ?, actual_days = ?, closed_at = ? WHERE id = ?",
                "https://git.example.com/pur/merge/42", new BigDecimal("3.0"), now, rd4);

        // 版本 + 需求纳入
        long releaseId = ensure("release",
                row("tenant_id", tenantId, "code", "R2026-11"),
                row("name", "2026 年 11 月版本", "domain_id", domain1, "status", "PLAN",
                        "start_date", today, "end_date", today.plusDays(30),
                        "planned_release_date", today.plusDays(35), "gate_mode", "STRICT",
                        "created_at", now, "updated_at", now, "created_by", users.get("pm"),
                        "updated_by", users.get("pm"), "version", 0));
        for (long r : new long[]{bus, pdr1, pdr2, rd4}) {
            link("release_requirement", row("tenant_id", tenantId, "release_id", releaseId,
                    "requirement_id", r, "added_by", users.get("pm"), "added_at", now));
        }
        jdbc.update("UPDATE requirement SET release_id = ? WHERE id IN (?, ?, ?, ?)",
                releaseId, bus, pdr1, pdr2, rd4);

        // 迭代 + 任务
        long sprintId = ensure("sprint",
                row("tenant_id", tenantId, "release_id", releaseId, "name", "Sprint-1"),
                row("goal", "采购链路打通", "start_date", today, "end_date", today.plusDays(14),
                        "capacity_days", new BigDecimal("40.0"), "status", "PLANNING"));
        ensure("task",
                row("tenant_id", tenantId, "requirement_id", rd1, "title", "列表接口联调"),
                row("sprint_id", sprintId, "assignee_id", users.get("dev1"), "estimate_hours", new BigDecimal("16.0"),
                        "start_date", today, "due_date", today.plusDays(5), "status", "DOING",
                        "sort_no", 1, "domain_id", domain1, "vendor_id", null));

        // 测试模块 3 层 + 12 条用例（每条 1 个版本 + 1 个步骤）
        // test_module 没有 code 列，幂等键用 (tenant_id, path)——uk_module_path 是同一个唯一键。
        long m1 = ensure("test_module",
                row("tenant_id", tenantId, "path", "采购"),
                row("parent_id", null, "name", "采购", "level", 1, "domain_id", domain1, "sort_no", 1));
        long m2 = ensure("test_module",
                row("tenant_id", tenantId, "path", "采购/采购申请"),
                row("parent_id", m1, "name", "采购申请", "level", 2, "domain_id", domain1, "sort_no", 1));
        long m3 = ensure("test_module",
                row("tenant_id", tenantId, "path", "采购/采购申请/申请单列表"),
                row("parent_id", m2, "name", "申请单列表", "level", 3, "domain_id", domain1, "sort_no", 1));

        long planId = ensure("test_plan",
                row("tenant_id", tenantId, "release_id", releaseId, "name", "2026-11 回归计划"),
                row("window_start", today, "window_end", today.plusDays(20),
                        "owner_id", users.get("qa1"), "status", "DRAFT", "gate_mode", "STRICT",
                        "domain_id", domain1, "vendor_id", null));

        for (int i = 1; i <= 12; i++) {
            String code = String.format(Locale.ROOT, "TC-%04d", i);
            long caseId = ensure("test_case",
                    row("tenant_id", tenantId, "code", code),
                    row("module_id", m3, "title", "采购申请单场景 " + i, "type", "FUNC",
                            "priority", i <= 3 ? "P1" : "P2", "precondition", "已登录",
                            "tags", "seed,采购", "automated", i % 3 == 0 ? 1 : 0,
                            "status", "ACTIVE", "current_version", 1,
                            "requirement_id", i <= 6 ? rd1 : rd2, "domain_id", domain1, "vendor_id", null));
            link("test_case_step", row("tenant_id", tenantId, "case_id", caseId, "version_no", 1,
                    "step_no", 1, "action", "打开页面并提交", "expected", "列表出现新记录"));
            link("test_case_version", row("tenant_id", tenantId, "case_id", caseId, "version_no", 1,
                    "snapshot_json", "{\"title\":\"采购申请单场景 " + i + "\",\"version\":1}",
                    "editor_id", users.get("qa1")));
            link("test_plan_case", row("tenant_id", tenantId, "plan_id", planId, "case_id", caseId,
                    "case_version", 1, "requirement_id", i <= 6 ? rd1 : rd2, "result", "NOT_RUN"));
        }

        // 缺陷：一条未关闭 HIGH + 一条已关闭
        long defectOpen = ensure("defect",
                row("tenant_id", tenantId, "code", "BUG-0001"),
                row("requirement_id", rd1, "release_id", releaseId, "title", "申请单列表分页错乱",
                        "description", "第 2 页与第 1 页重复", "repro_steps", "1. 打开列表 2. 翻页",
                        "severity", "P1", "priority", "P1", "status", "IN_PROGRESS",
                        "reporter_id", users.get("qa1"), "handler_id", users.get("dev1"),
                        "domain_id", domain1, "vendor_id", null, "reopen_count", 0,
                        "found_at", now.minusDays(1), "created_at", now, "updated_at", now));
        ensure("defect",
                row("tenant_id", tenantId, "code", "BUG-0002"),
                row("requirement_id", rd3, "release_id", releaseId, "title", "模板下载 404",
                        "description", "模板链接失效", "repro_steps", "点击下载", "severity", "P2",
                        "priority", "P2", "status", "CLOSED", "reporter_id", users.get("qa1"),
                        "handler_id", users.get("dev2"), "domain_id", domain1, "vendor_id", null,
                        "reopen_count", 1, "found_at", now.minusDays(3), "closed_at", now.minusDays(1),
                        "created_at", now, "updated_at", now));
        log.debug("seed: 打开中的高危缺陷 id={}", defectOpen);

        // 质量域种子（M6 / V5）：SIT 环境 → 版本策略 → 计划挂策略与环境 → R1 轮次 → 分配 → 质量门与 5 条规则。
        // 全部走 ensure（命中即返回、不覆盖）：老库上 V5 的回填已经建好策略与 R1，命中后保持原值；
        // 新库上 V5 跑在 seed 之前、那时 plan 还不存在，所以计划挂接与 R1 归集必须在这里再补一次。
        // 准出/准入条件的字面量必须与 V5__quality_management.sql 的默认值逐字一致（迁移是唯一 owner，这里只是补新库）。
        long envId = ensure("test_env",
                row("tenant_id", tenantId, "domain_id", domain1, "name", "SIT 采购测试环境"),
                row("env_type", "TEST", "base_url", "http://sit.pur.example.com",
                        "owner_id", users.get("qa1"), "status", "AVAILABLE", "vendor_id", null,
                        "note", "质量门与执行轮次的默认环境",
                        "created_by", users.get("admin"), "created_at", now, "updated_at", now));
        long strategyId = ensure("test_strategy",
                row("tenant_id", tenantId, "release_id", releaseId, "version_no", 1),
                row("scope_json", "{\"coverage\":\"FULL\",\"regressionRounds\":2}",
                        "risk_json", "{\"items\":[{\"risk\":\"批量导入性能\",\"level\":\"HIGH\"}]}",
                        "env_ids_json", "[" + envId + "]",
                        "round_plan_json", "{\"rounds\":[{\"no\":1,\"type\":\"FEATURE\"},"
                                + "{\"no\":2,\"type\":\"REGRESSION\"}]}",
                        "resource_note", "QA 2 人 + 1 套 SIT", "owner_id", users.get("qa1"),
                        "status", "ACTIVE", "domain_id", domain1, "vendor_id", null,
                        "created_by", users.get("admin"), "created_at", now, "updated_at", now));
        jdbc.update("UPDATE test_plan SET strategy_id = ? WHERE id = ? AND strategy_id IS NULL",
                strategyId, planId);
        jdbc.update("UPDATE test_plan SET env_id = ? WHERE id = ? AND env_id IS NULL", envId, planId);
        jdbc.update("UPDATE test_plan SET exit_criteria_json = ? WHERE id = ? AND exit_criteria_json IS NULL",
                "{\"items\":[{\"key\":\"passRate\",\"op\":\">=\",\"value\":95},"
                        + "{\"key\":\"openP0P1\",\"op\":\"==\",\"value\":0},"
                        + "{\"key\":\"roundClosed\",\"op\":\"==\",\"value\":1}]}", planId);
        jdbc.update("UPDATE test_plan SET entry_criteria_json = ? WHERE id = ? AND entry_criteria_json IS NULL",
                "{\"items\":[{\"key\":\"strategyActive\",\"op\":\"==\",\"value\":1}]}", planId);

        Map<String, Object> roundStats = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, COALESCE(SUM(result = 'PASS'), 0) AS passed,"
                        + " COALESCE(SUM(result = 'FAIL'), 0) AS failed,"
                        + " COALESCE(SUM(result = 'BLOCKED'), 0) AS blocked"
                        + " FROM test_plan_case WHERE tenant_id = ? AND plan_id = ?", tenantId, planId);
        ensure("test_plan_round",
                row("tenant_id", tenantId, "plan_id", planId, "round_no", 1),
                row("round_type", "FEATURE", "name", "R1 功能测试", "status", "CLOSED",
                        "start_at", now.minusDays(2), "closed_at", now.minusDays(1),
                        "exec_total", ((Number) roundStats.get("total")).intValue(),
                        "exec_passed", ((Number) roundStats.get("passed")).intValue(),
                        "exec_failed", ((Number) roundStats.get("failed")).intValue(),
                        "exec_blocked", ((Number) roundStats.get("blocked")).intValue(),
                        "active", null, "note", "种子轮次：存量执行归集",
                        "created_by", users.get("qa1"), "created_at", now, "updated_at", now));
        ensure("plan_assignment",
                row("tenant_id", tenantId, "plan_id", planId, "executor_id", users.get("qa1"),
                        "module_id", m3),
                row("round_id", null, "planned_count", 12, "due_date", today.plusDays(5),
                        "created_by", users.get("qa1"), "created_at", now, "updated_at", now));

        long gateId = ensure("quality_gate",
                row("tenant_id", tenantId, "release_id", releaseId),
                row("report_id", null, "status", "NOT_EVALUATED", "ruleset_version", 1,
                        "domain_id", domain1, "created_by", users.get("pm"),
                        "created_at", now, "updated_at", now));
        // 阈值口径见 TECHNICAL.md §4「门禁规则」；QG-04 是硬红线，enabled 恒为 1（禁用由服务端拒绝，种子不提供关闭开关）。
        String[][] gateRules = {
                {"QG-01", "1", "{\"coveragePercent\":100,\"requireSignedReport\":true}"},
                {"QG-02", "1", "{\"maxAgeDays\":7,\"conclusions\":[\"PASS\",\"CONDITIONAL\"]}"},
                {"QG-03", "1", "{\"minPassRate\":95,\"strictRequiresAll\":true}"},
                {"QG-04", "1", "{\"maxOpenP0P1\":0}"},
                {"QG-05", "0", "{\"sources\":[\"SONAR\",\"ARTIFACT\",\"BUILD\"]}"}};
        for (String[] r : gateRules) {
            ensure("quality_gate_rule",
                    row("tenant_id", tenantId, "gate_id", gateId, "rule_code", r[0]),
                    row("enabled", Integer.parseInt(r[1]), "threshold_json", r[2]));
        }

        // 指标定义（公式与 contract 的 MetricFormulas 同名同口径）
        String[][] metrics = {
                {"TEST_PASS_RATE", "测试通过率", "passed / planned", "测试计划", "已执行通过 / 计划用例数"},
                {"DEFECT_DENSITY", "缺陷密度", "defects / requirements", "缺陷", "缺陷数 / 需求数"},
                {"REOPEN_RATE", "缺陷重开率", "reopened / total", "缺陷", "重开次数 / 缺陷总数"},
                {"MTTR", "平均修复时长", "avg(closed_at - found_at)", "缺陷", "已关闭缺陷的平均处理分钟数"}};
        for (String[] m : metrics) {
            ensure("metric_definition",
                    row("tenant_id", tenantId, "code", m[0]),
                    row("name", m[1], "topic", m[3], "formula", m[2], "datasource", m[3],
                            "caliber_note", m[4], "owner_id", users.get("admin"),
                            "version", 1, "effective_at", now));
        }

        // 协作域（M8）：阶段定义 / FLOW 指标 / Wiki 空间与页面 / 分支策略 / 执行器台账。
        // V9 的 ⑤ 段是这些行的唯一定义，这里在租户造好之后把它幂等重放（见 replayCollabSeed）。
        replayCollabSeed();
        // 质量域同理：V5 步 7 的 5 行指标在空库 Flyway 阶段插不进（sys_tenant 为空），
        // 全新安装的指标字典会少 5 条（D-03）。租户与用户此刻都已存在，owner 子查询可命中。
        replayQualityMetricSeed();

        // 编号序列（与已插入的单据号对齐）：next_val = 该前缀已发出的最大值，
        // 否则 CodeGenerator 从 0 起算，第一个 API 建单就会撞 uk_case_code / uk_req_code
        // （2026-11-09 T11 实测：种子 TC-0001..0012 之后，POST /testcases 报 Duplicate '4-TC-0001'）。
        syncCodeSequences(tenantId);

        // 规模夹具也必须满足 AC-2 树不变量（RD 必须挂在 PDR 下）。旧版本夹具插入过
        // parent_id 为空的 RD-BULK 行，这里每次 seed 都自愈（即使本次 scale 不大），
        // 否则 platform-perf 之后的 platform-seed [09] 会 9/10，门禁结果依赖执行顺序。
        repairBulkRequirementParents(tenantId, pdrBulk, now);

        // 规模数据（性能基线用）：只补 requirement 行，走批量 INSERT
        if (scale > 20) {
            bulkRequirements(tenantId, pdrBulk, domain1, supplyOrg, users.get("admin"), scale, now);
        }
    }

    /**
     * 重放 V9 迁移的 ⑤ 种子段（按租户幂等）。
     *
     * <p>为什么需要这一步：V9 的种子是 {@code INSERT ... SELECT FROM sys_tenant}，而全新空库里
     * Flyway 先于 {@code POST /internal/seed} 执行——那一刻 sys_tenant 还是空表，于是 11 张新表
     * 全是 0 行（实测 branch_policy=0 而 sys_tenant=1），甘特/价值流/Wiki/执行器的夹具全部缺失。
     * 迁移已经 applied，改 V9 既不会重跑（validate-on-migrate=true，改内容反而校验和不匹配），
     * 也不该把同一批行再抄一份到 Java 里变成两处定义。</p>
     *
     * <p>所以：V9 仍是唯一口径，这里只在租户造好之后把同一段 SQL 原样再执行一次。段内每条
     * {@code INSERT ... SELECT} 都自带 {@code NOT EXISTS} 守卫，重放是幂等的（租户多个也只是
     * 各取各的）。标号缺失会直接抛错而不是静默 0 行——那正是本方法要修的故障形态。</p>
     */
    private void replayCollabSeed() {
        String sql;
        try (InputStream in = new ClassPathResource(COLLAB_SEED_SOURCE).getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("读不到协作域种子源 " + COLLAB_SEED_SOURCE, ex);
        }
        // 段标号带括号是为了避开文件头那张目录注释（第 4 行也写着「⑤ 种子 → ⑥ 校验」）；
        // ⑥ 从 ⑤ 之后找，否则永远命中的是那行注释，切片长度成 0。
        int from = sectionStart(sql, "⑤ 种子（按租户，幂等）", 0);
        int to = sectionStart(sql, "⑥ 校验", Math.max(from, 0));
        if (from < 0 || to <= from) {
            throw new IllegalStateException(COLLAB_SEED_SOURCE
                    + " 缺少种子段标号（⑤ 种子 / ⑥ 校验）：迁移被动过，协作域种子会静默 0 行");
        }
        int executed = replaySeedSection(COLLAB_SEED_SOURCE, sql.substring(from, to));
        log.info("协作域种子已按 V9 ⑤ 段重放：{} 条语句（幂等，命中即跳过）", executed);
    }

    /**
     * 重放 V5 迁移「步 7」的质量指标种子（每租户 5 行，幂等）。
     *
     * <p>为什么需要这一步：V5 步 7 与 V9 ⑤ 段是同一个故障形态——种子写成
     * {@code INSERT ... SELECT FROM sys_tenant}，而全新空库 Flyway 先于 {@code POST /internal/seed}
     * 执行，sys_tenant 当时为空，插入 0 行；迁移已 applied，改文件反而校验和不匹配。
     * 于是全新安装的指标字典只有 SeedService 内置的 4 条 + V9 的 5 条 FLOW，缺 5 条质量指标
     * （FPY / REGRESSION_PASS_RATE / DEFECT_ESCAPE_RATE / POST_RELEASE_DEFECTS_72H / ROLLBACK_RATE），
     * 既有的「14 条指标定义」门禁因此掉到 9 条（D-03）。</p>
     *
     * <p>与 V9 一样，V5 仍是唯一定义：这里只把步 7 原样再执行一次，步 7 自带
     * {@code NOT EXISTS} 守卫，重放幂等；标号缺失直接抛错，不允许静默 0 行。</p>
     */
    private void replayQualityMetricSeed() {
        String sql;
        try (InputStream in = new ClassPathResource(QUALITY_SEED_SOURCE).getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("读不到质量域种子源 " + QUALITY_SEED_SOURCE, ex);
        }
        int from = sectionStart(sql, "步 7：5 行质量指标", 0);
        if (from < 0) {
            throw new IllegalStateException(QUALITY_SEED_SOURCE
                    + " 缺少「步 7：5 行质量指标」段标号：迁移被动过，5 条质量指标会静默缺失");
        }
        int executed = replaySeedSection(QUALITY_SEED_SOURCE, sql.substring(from));
        log.info("质量指标已按 V5 步 7 重放：{} 条语句（幂等，命中即跳过）", executed);
    }

    /** 执行一段迁移 SQL：丢弃 -- 注释行、按 ';' 拆句逐条执行，返回执行条数。 */
    private int replaySeedSection(String source, String section) {
        int executed = 0;
        for (String chunk : section.split(";")) {
            StringBuilder statement = new StringBuilder();
            for (String line : chunk.split("\n")) {
                // 段内的 -- 注释只解释口径，不进 SQL；留着会把注释粘到上一条语句尾部。
                if (!line.trim().startsWith("--")) {
                    statement.append(line).append('\n');
                }
            }
            if (statement.toString().isBlank()) {
                continue;
            }
            jdbc.execute(statement.toString());
            executed++;
        }
        if (executed == 0) {
            throw new IllegalStateException(source + " 的种子段解析出 0 条语句：迁移格式已变");
        }
        return executed;
    }

    /** 段标号所在行的行首偏移（从 fromIndex 起找）；找不到返回 -1。 */
    private static int sectionStart(String sql, String marker, int fromIndex) {
        int at = sql.indexOf(marker, fromIndex);
        if (at < 0) {
            return -1;
        }
        int lineStart = sql.lastIndexOf('\n', at);
        return lineStart < 0 ? 0 : lineStart + 1;
    }

    /**
     * 把 code_sequence.next_val 对齐到各表已发出的最大编号。
     *
     * <p>种子直接 INSERT 单据行（绕过 CodeGenerator），所以序列必须由真实数据反推，
     * 而不是写死常量：租户跑过几轮验证后最大号已经变了，写死就会重复发号。</p>
     */
    private void syncCodeSequences(long tenantId) {
        syncSequence(tenantId, "BUS", "requirement", "type = 'BUS'");
        syncSequence(tenantId, "PDR", "requirement", "type = 'PDR'");
        syncSequence(tenantId, "RD", "requirement", "type = 'RD'");
        syncSequence(tenantId, "TC", "test_case", "1 = 1");
        syncSequence(tenantId, "REL", "release", "1 = 1");
        syncSequence(tenantId, "DEFECT", "defect", "1 = 1");
        syncSequence(tenantId, "DEP", "deploy_order", "1 = 1");
    }

    private void syncSequence(long tenantId, String prefix, String table, String where) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(code, '-', -1) AS UNSIGNED)), 0)"
                        + " FROM " + qt(table) + " WHERE tenant_id = ? AND " + where,
                Long.class, tenantId);
        long value = max == null ? 0L : max;
        jdbc.update("INSERT INTO code_sequence (tenant_id, code_type, next_val) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE next_val = GREATEST(next_val, ?)",
                tenantId, prefix, value, value);
    }

    /**
     * 自愈旧版规模夹具留下的无父 RD：RD-BULK-% 必须挂在（规模专用）PDR 下（AC-2），
     * 且人天归零——10 万条 × 2 天会让父汇总超出需求表的 DECIMAL 列范围。
     * 走多表 UPDATE + LEFT JOIN，只改 parent 为空/父不是 PDR/父不对/人天非零的行。
     */
    private void repairBulkRequirementParents(long tenantId, long parentId, LocalDateTime now) {
        int repaired = jdbc.update("UPDATE requirement rd"
                + " LEFT JOIN requirement p ON p.id = rd.parent_id AND p.type = 'PDR'"
                + " SET rd.parent_id = ?, rd.estimate_days = 0, rd.updated_at = ?"
                + " WHERE rd.tenant_id = ? AND rd.type = 'RD' AND rd.code LIKE 'RD-BULK-%'"
                + " AND (p.id IS NULL OR rd.parent_id <> ? OR rd.estimate_days <> 0)",
                parentId, now, tenantId, parentId);
        if (repaired > 0) {
            log.info("seed: 自愈 {} 条 RD-BULK 规模数据的 PDR 父需求 → {}", repaired, parentId);
        }
    }

    private void bulkRequirements(long tenantId, long parentId, long domainId, long orgId, long userId,
                                  int target, LocalDateTime now) {
        Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM requirement WHERE tenant_id = ?", Long.class, tenantId);
        int have = existing == null ? 0 : existing.intValue();
        int need = target - have;
        if (need <= 0) {
            return;
        }
        String sql = "INSERT INTO requirement (tenant_id, code, type, parent_id, domain_id, origin_org_id, reporter_id,"
                + " owner_user_id, owner_vendor_id, assignee_vendor_id, title, description, priority, status,"
                + " estimate_days, actual_days, expected_delivery_date, created_at, updated_at, created_by, updated_by, version)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        List<Object[]> args = new ArrayList<>(need);
        int start = have + 1;
        for (int i = 0; i < need; i++) {
            int n = start + i;
            args.add(new Object[]{
                    tenantId, String.format(Locale.ROOT, "RD-BULK-%06d", n), "RD", parentId, domainId, orgId, userId,
                    userId, null, null, "性能基线需求 " + n, null, "P2",
                    n % 7 == 0 ? "POOLED" : (n % 5 == 0 ? "DONE" : "IN_DEV"),
                    new BigDecimal("0"), null, LocalDate.now().plusDays(30), now, now, userId, userId, 0});
        }
        int batch = 1000;
        for (int from = 0; from < args.size(); from += batch) {
            List<Object[]> slice = args.subList(from, Math.min(from + batch, args.size()));
            jdbc.batchUpdate(sql, slice);
        }
        log.info("seed: 批量补入 {} 条需求（总目标 {}）", need, target);
    }

    private long requirement(long tenantId, String type, Long parentId, long domainId, long orgId,
                             long reporterId, long ownerId, String title, String code, String status,
                             String priority, Integer estimateDays, LocalDate expected, LocalDateTime now) {
        long id = ensure("requirement",
                row("tenant_id", tenantId, "code", code),
                row("type", type, "parent_id", parentId, "domain_id", domainId, "origin_org_id", orgId,
                        "reporter_id", reporterId, "owner_user_id", ownerId, "title", title,
                        "description", title + "（种子数据）", "priority", priority, "status", status,
                        "estimate_days", estimateDays == null ? null : new BigDecimal(estimateDays),
                        "expected_delivery_date", expected, "created_at", now, "updated_at", now,
                        "created_by", reporterId, "updated_by", reporterId, "version", 0));
        link("requirement_domain_rel", row("tenant_id", tenantId, "requirement_id", id, "domain_id", domainId));
        return id;
    }

    private long count(String table, long tenantId) {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM " + qt(table) + " WHERE tenant_id = ?", Long.class, tenantId);
        return v == null ? 0L : v;
    }

    // ------------------------------------------------------------------ JDBC helpers

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /**
     * 反引号包裹表名：{@code release} 是 MySQL 保留字（V1 里就是以 {@code `release`} 建的），
     * 这些通用 helper 拼 SQL 时若不包裹会直接 1064 语法错误。
     */
    private static String qt(String table) {
        return "`" + table.replace("`", "") + "`";
    }

    private Set<String> columnsOf(String table) {
        return columnCache.computeIfAbsent(table.toLowerCase(Locale.ROOT), t -> Set.copyOf(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?",
                String.class, t).stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet())));
    }

    private Long findId(String table, Map<String, Object> keys) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> e : keys.entrySet()) {
            if (e.getValue() == null) {
                where.append(where.length() == 0 ? "" : " AND ").append(e.getKey()).append(" IS NULL");
            } else {
                where.append(where.length() == 0 ? "" : " AND ").append(e.getKey()).append(" = ?");
                args.add(e.getValue());
            }
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM " + qt(table) + " WHERE " + where + " LIMIT 1", Long.class, args.toArray());
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 按业务键幂等插入一行，返回 id。表里不存在的列会被过滤掉（DDL 以 T0 的迁移为准）。 */
    private long ensure(String table, Map<String, Object> keys, Map<String, Object> extra) {
        Long existing = findId(table, keys);
        if (existing != null) {
            return existing;
        }
        Map<String, Object> cols = new LinkedHashMap<>(keys);
        cols.putAll(extra);
        Set<String> allowed = columnsOf(table);
        cols.keySet().retainAll(allowed);
        String names = String.join(", ", cols.keySet());
        String marks = cols.keySet().stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + qt(table) + " (" + names + ") VALUES (" + marks + ")";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            for (Object v : cols.values()) {
                ps.setObject(i++, v);
            }
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalStateException("种子插入后没有拿到自增 id: " + table + " " + keys);
        }
        return key.longValue();
    }

    /** 单节点审批流程定义（起点 n1 → end），与会签/条件节点的 JSON 结构一致。 */
    private static String approvalDefinition(String nodeName, String roleCode) {
        return "{\"schemaVersion\":1,\"start\":\"n1\",\"nodes\":["
                + "{\"key\":\"n1\",\"type\":\"APPROVAL\",\"name\":\"" + nodeName + "\",\"mode\":\"ALL\","
                + "\"approvers\":[{\"type\":\"ROLE\",\"value\":\"" + roleCode + "\"}],\"next\":\"end\"}]}";
    }

    /** 已发布的默认审批流程；按 (tenant,biz_type,req_type,domain,version) 幂等。 */
    private void seedWorkflow(long tenantId, String bizType, String reqType, String name,
                              String definition, Long publishedBy, LocalDateTime now) {
        ensure("wf_definition",
                row("tenant_id", tenantId, "biz_type", bizType, "req_type", reqType, "version", 1),
                row("name", name, "domain_id", 0, "definition_json", definition,
                        "status", "PUBLISHED", "is_default", 1, "published_at", now,
                        "published_by", publishedBy));
    }

    /**
     * 部门组织：占位 path 按 code 唯一（避免三个部门撞 uk_org_path），INSERT/命中后立即回填真实 id 链。
     */
    private long ensureDept(long tenantId, long rootOrg, String code, String name, int sortNo) {
        long orgId = ensure("sys_org",
                row("tenant_id", tenantId, "code", code),
                row("parent_id", rootOrg, "type", "DEPT", "path", "/1/" + rootOrg + "/" + code + "/",
                        "sort_no", sortNo, "name", name, "status", "ACTIVE"));
        rewriteOrgPath(orgId);
        return orgId;
    }

    /**
     * 把组织 path 重写为祖先 id 链：根 {@code /<id>/}，子 {@code <父 path><id>/}。
     * 用 JOIN 版 UPDATE（MySQL 不允许 UPDATE 的子查询再读同一张表），并对已存在的行幂等重算。
     */
    private void rewriteOrgPath(long orgId) {
        jdbc.update("UPDATE sys_org SET path = CONCAT('/', id, '/')"
                + " WHERE id = ? AND parent_id IS NULL", orgId);
        jdbc.update("UPDATE sys_org child JOIN sys_org parent ON parent.id = child.parent_id"
                + " SET child.path = CONCAT(parent.path, child.id, '/')"
                + " WHERE child.id = ?", orgId);
    }

    /** 关联表：没有 id，存在即跳过。 */
    private void link(String table, Map<String, Object> cols) {
        // 存在性只按「表的真实主键/唯一键列」判断。用整行当条件会把内容列也纳入比较：
        // 旧行的 snapshot_json/editor_id/added_by 与新种子不完全相同 → COUNT 为 0 → INSERT 撞唯一键
        // （2026-11-09 T11 实测：release_requirement PRIMARY 5-36、test_case_version uk_case_ver 13-1）。
        Set<String> keys = keyColumnsOf(table);
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> e : cols.entrySet()) {
            if (!keys.isEmpty() && keys.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                appendEquals(where, args, e);
            }
        }
        if (where.length() == 0) {
            for (Map.Entry<String, Object> e : cols.entrySet()) {
                if (identityColumn(e.getKey())) {
                    appendEquals(where, args, e);
                }
            }
        }
        if (where.length() > 0) {
            Long hit = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + qt(table) + " WHERE " + where, Long.class, args.toArray());
            if (hit != null && hit > 0) {
                return;
            }
        }
        Set<String> allowed = columnsOf(table);
        Map<String, Object> use = new LinkedHashMap<>();
        cols.forEach((k, v) -> {
            if (allowed.contains(k.toLowerCase(Locale.ROOT))) {
                use.put(k, v);
            }
        });
        String names = String.join(", ", use.keySet());
        String marks = use.keySet().stream().map(c -> "?").collect(Collectors.joining(", "));
        jdbc.update("INSERT INTO " + qt(table) + " (" + names + ") VALUES (" + marks + ")", use.values().toArray());
    }

    private static void appendEquals(StringBuilder where, List<Object> args, Map.Entry<String, Object> e) {
        if (e.getValue() == null) {
            where.append(where.length() == 0 ? "" : " AND ").append(e.getKey()).append(" IS NULL");
        } else {
            where.append(where.length() == 0 ? "" : " AND ").append(e.getKey()).append(" = ?");
            args.add(e.getValue());
        }
    }

    /** 表的主键/唯一键列（小写）。information_schema 是唯一权威，避免在种子里手抄键顺序。 */
    private Set<String> keyColumnsOf(String table) {
        List<String> names = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND NON_UNIQUE = 0",
                String.class, table.toLowerCase(Locale.ROOT));
        return names.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    /** 关系身份列：不属于 added_by / 时间戳这类「谁在何时写的」审计列（无键表的兜底）。 */
    private static boolean identityColumn(String column) {
        String c = column.toLowerCase(Locale.ROOT);
        return !(c.startsWith("added_") || c.startsWith("created_") || c.startsWith("updated_")
                || c.startsWith("deleted_") || c.equals("created_by") || c.equals("updated_by")
                || c.endsWith("_json") || c.equals("snapshot") || c.equals("editor_id"));
    }

    private String bcrypt(String raw) {
        try {
            Class<?> type = Class.forName("org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder");
            Object encoder = type.getDeclaredConstructor().newInstance();
            return (String) type.getMethod("encode", CharSequence.class).invoke(encoder, raw);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "种子需要一个可用的密码哈希器（BCrypt）。请在 req-service 依赖中加入 "
                            + "spring-security-crypto，或提供 PasswordEncoder Bean。", e);
        }
    }

    /** 种子被关闭时抛给控制器，映射成 404（TECHNICAL §2.4）。 */
    public static class SeedDisabledException extends RuntimeException {
        public SeedDisabledException() {
            super("种子接口未启用：设置 PLATFORM_SEED_ENABLED=true 后重启 req-service");
        }
    }
}
