package com.example.req.seed;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopeSql;
import com.example.platform.common.scope.SqlPredicate;
import com.example.platform.common.security.UserIdentity;
import com.example.platform.contract.DataScope;
import com.example.req.sys.SysDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示数据重置：把租户的业务实例数据清空，重建一条「12 需求小链路」。
 *
 * <p>为什么是一个内部端点而不是 SQL 脚本：数据集要复用种子已经建好的账号、域、厂商、测试模块，
 * 插入顺序（版本 → 需求 → 关联 → 用例 → 计划 → 计划-用例 → 缺陷 → 任务 → 通知）与删除顺序
 * （{@link DemoFixture#DELETE_ORDER}）必须一致地维护在一处；脚本方式会把同一套逻辑复制一份，
 * 外键拓扑一变必漏一处。</p>
 *
 * <p>四个设计约束（PRD AC-13 / AC-14）：</p>
 * <ol>
 *   <li><b>单事务</b>：{@link #reset()} 上 {@code @Transactional}，任何一步失败（含不变量违反）都整体
 *       回滚——半重置的演示数据比没重置更难排查。</li>
 *   <li><b>只删行</b>：绝不 {@code TRUNCATE}/{@code DROP}，逐表 {@code DELETE ... WHERE tenant_id = ?}；
 *       账号/角色/域/厂商/组织/指标定义/字典/集成配置一律保留；{@code test_module}/{@code wf_definition}/
 *       {@code test_env} 的<b>种子行</b>也保留，只清门禁按 RUN_TAG 命名的探针行
 *       （{@link DemoFixture#GATE_RUN_TAG_RESIDUE}）；{@code test_strategy} 是 release 的派生数据，
 *       无种子可按，随 release 一并清空。</li>
 *   <li><b>不变量自检用真实的数据范围引擎</b>：{@code po1=8 / vendor1=3 / vendor2=0} 不是写死的结论，
 *       而是用 {@link UserIdentity#scope()}（唯一的分档来源）+ {@link ScopeSql#forTable} 现算出来的——
 *       数据集改坏了，重置自己会红。</li>
 *   <li><b>幂等</b>：所有插入用固定 code（{@code DEMO-*}），先删后插，连跑两次计数完全一致。</li>
 * </ol>
 */
@Service
public class DemoResetService {

    private static final Logger log = LoggerFactory.getLogger(DemoResetService.class);

    /** 夹具痕迹前缀：历次验收脚本造的临时数据（s1/s5/s7 协作段、neg-sm 负向段）。 */
    private static final List<String> FIXTURE_PREFIXES = List.of("s1-", "s5-", "s7-", "neg-sm-");

    /**
     * 报表探针名片段：历次验收脚本在报表中心建的临时报表（T8 编辑器探针、r11 证据、r13 交叉表）。
     *
     * <p>为什么报表名要单独一组而不是复用 {@link #FIXTURE_PREFIXES}：报表名是人写的中文短句
     * （「T8 编辑器探针」），不像需求 code 有统一前缀；按 {@code s1-}/{@code s7-} 探报表名永远是 0，
     * 断言会变成恒真的假安全网。这组只在 {@code reports=0} 之后做诊断（证明 251 行探针确实清空）。</p>
     */
    private static final List<String> PROBE_REPORT_PATTERNS =
            List.of("%T8 编辑器探针%", "r11-ev%", "r13-%");

    /** AC-13：人天超过 30 的残留（s1-gantt 会把需求改成 45）等于没清干净。 */
    private static final double ACTUAL_DAYS_LIMIT = 30;

    /** 甘特图上算「已收口」的状态：这些行即使计划结束日已过，也不该记成逾期。 */
    private static final List<String> TERMINAL_STATUSES =
            List.of("DONE", "CLOSED", "ACCEPTED_CLOSED", "CANCELLED", "MERGED");

    private final SysDb db;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final boolean enabled;
    private final String tenantCode;

    public DemoResetService(SysDb db,
                            NamedParameterJdbcTemplate namedJdbc,
                            @Value("${platform.seed.enabled:false}") boolean enabled,
                            @Value("${platform.tenant:" + DemoFixture.TENANT_CODE + "}") String tenantCode) {
        this.db = db;
        this.namedJdbc = namedJdbc;
        this.enabled = enabled;
        this.tenantCode = tenantCode == null || tenantCode.isBlank()
                ? DemoFixture.TENANT_CODE : tenantCode.trim();
    }

    /** 与 {@code SeedController} 同一条开关：演示能力不暴露在业务面。 */
    public boolean isEnabled() {
        return enabled;
    }

    public String tenantCode() {
        return tenantCode;
    }

    public String resetAt() {
        return OffsetDateTime.now().toString();
    }

    /**
     * 清空并重建演示数据，返回计数表（表名 → 行数 + 不变量实测值）。
     *
     * @throws ApiException 409 {@code DEMO_RESET_INVARIANT}——不变量不满足，事务整体回滚
     * @throws ApiException 409 {@code DEMO_RESET_TENANT}——租户或种子数据缺失（先跑 platform-seed）
     */
    @Transactional
    public Map<String, Long> reset() {
        long tenantId = tenantId();
        Map<String, Long> refs = references(tenantId);

        long deleted = deleteBusinessData(tenantId);
        log.info("demo-reset: tenant={} 清理 {} 行业务实例数据", tenantCode, deleted);

        // keepFilter 保住的两行执行器只保住了「行」：last_report_at/report_count 被 s7 与手工上报
        // 改过之后会跨重置存活，NEVER/DISABLED 这两条种子事实就没了。重置要回到可演示夹具，
        // 字段也得回到种子基线（与 DELETE_ORDER 同属 DemoFixture 一处拥有）。
        db.update(DemoFixture.RESTORE_KEPT_EXECUTOR_BASELINE, tenantId);

        insertFixture(tenantId, refs);

        Map<String, Long> counts = counts();
        Map<String, Long> invariants = invariants(tenantId);
        counts.putAll(invariants);
        List<String> violations = violations(tenantId, counts);
        if (!violations.isEmpty()) {
            throw new ApiException(409, "DEMO_RESET_INVARIANT",
                    "演示数据不变量不满足（已整体回滚）：" + String.join("；", violations), Map.of());
        }
        log.info("demo-reset: tenant={} 重建完成 counts={}", tenantCode, counts);
        return counts;
    }

    /**
     * 当前计数（不重置）：脚本渲染计数表、后续阶段核对演示态都用它。
     *
     * <p>r15 的 6 个新键（{@code reports/portalCards/portalPins/executors/apiClients/probeReports}）
     * 都是<b>纯行数</b>，不掺派生指标——{@link #reset()} 末尾会把 {@link #invariants} 混进同一个 map，
     * 那是历史形状，不要在这里再加派生量。{@code probeReports} 是 {@code reports} 的子集，只作为
     * 「探针确实清空」的诊断信号，不得据此推导别的期望。</p>
     */
    public Map<String, Long> counts() {
        long tenantId = tenantId();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("requirements", db.count(
                "SELECT COUNT(*) FROM requirement WHERE tenant_id = ?", tenantId));
        counts.put("releases", db.count(
                "SELECT COUNT(*) FROM `release` WHERE tenant_id = ?", tenantId));
        counts.put("milestones", db.count(
                "SELECT COUNT(*) FROM milestone WHERE tenant_id = ?", tenantId));
        counts.put("sprints", db.count("SELECT COUNT(*) FROM sprint WHERE tenant_id = ?", tenantId));
        counts.put("tasks", db.count("SELECT COUNT(*) FROM task WHERE tenant_id = ?", tenantId));
        counts.put("testCases", db.count(
                "SELECT COUNT(*) FROM test_case WHERE tenant_id = ? AND deleted_at IS NULL", tenantId));
        counts.put("testPlans", db.count("SELECT COUNT(*) FROM test_plan WHERE tenant_id = ?", tenantId));
        counts.put("planCases", db.count(
                "SELECT COUNT(*) FROM test_plan_case WHERE tenant_id = ?", tenantId));
        counts.put("defects", db.count("SELECT COUNT(*) FROM defect WHERE tenant_id = ?", tenantId));
        counts.put("notifications", db.count(
                "SELECT COUNT(*) FROM notification WHERE tenant_id = ?", tenantId));
        // r15：漏删的四张表纳入计数（定义数据残留曾让 /reports、/portal、/admin 带着上次门禁的痕迹）。
        counts.put("reports", db.count(
                "SELECT COUNT(*) FROM report_definition WHERE tenant_id = ?", tenantId));
        counts.put("portalCards", db.count(
                "SELECT COUNT(*) FROM portal_card_config WHERE tenant_id = ?", tenantId));
        counts.put("portalPins", db.count(
                "SELECT COUNT(*) FROM portal_card_config WHERE tenant_id = ? AND user_id <> 0", tenantId));
        counts.put("executors", db.count(
                "SELECT COUNT(*) FROM test_executor WHERE tenant_id = ?", tenantId));
        counts.put("apiClients", db.count(
                "SELECT COUNT(*) FROM api_client WHERE tenant_id = ?", tenantId));
        counts.put("probeReports", probeReports(tenantId));
        // r15-fix（QA D-01/D-02）：上一轮门禁的「运行时行」残留。前两个是纯行数，
        // gateResidue 是跨表合计（按 DemoFixture.GATE_RUN_TAG_RESIDUE 一处谓词统计）。
        counts.put("testStrategies", db.count(
                "SELECT COUNT(*) FROM test_strategy WHERE tenant_id = ?", tenantId));
        counts.put("testEnvs", db.count(
                "SELECT COUNT(*) FROM test_env WHERE tenant_id = ?", tenantId));
        counts.put("gateResidue", gateResidue(tenantId));
        return counts;
    }

    /**
     * 门禁 RUN_TAG 残留行数（三张种子表合计）：谓词来自 {@link DemoFixture#GATE_RUN_TAG_RESIDUE}，
     * 与 {@link DemoFixture#DELETE_ORDER} 里 {@code purgeRunTagResidue} 用的是同一份，删除范围与
     * 断言口径因此不可能各说一套。纯计数，0 是唯一合法值。
     */
    private long gateResidue(long tenantId) {
        long residue = 0;
        for (String table : DemoFixture.RUN_TAG_RESIDUE_TABLES) {
            residue += db.count("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ? AND ("
                    + DemoFixture.GATE_RUN_TAG_RESIDUE + ")", tenantId);
        }
        return residue;
    }

    /** 报表探针残留：按 {@link #PROBE_REPORT_PATTERNS} 逐片段探测，只做「确实清空」的诊断。 */
    private long probeReports(long tenantId) {
        long probe = 0;
        for (String pattern : PROBE_REPORT_PATTERNS) {
            probe += db.count("SELECT COUNT(*) FROM report_definition"
                    + " WHERE tenant_id = ? AND name LIKE ?", tenantId, pattern);
        }
        return probe;
    }

    // ------------------------------------------------------------------ 租户与引用

    private long tenantId() {
        return db.row("SELECT id FROM sys_tenant WHERE code = ?", tenantCode)
                .map(row -> SysDb.asLong(row[0]))
                .orElseThrow(() -> new ApiException(409, "DEMO_RESET_TENANT",
                        "租户 " + tenantCode + " 不存在：先执行 make platform-seed", Map.of()));
    }

    /**
     * 把数据集需要的引用解析成 id：账号、域、厂商、组织、测试模块。
     *
     * <p>缺任何一个都说明种子没跑过（或跑了一半），此时直接 409 比插入一堆 NULL 好——
     * {@code requirement.origin_org_id} 这类列是 NOT NULL，猜一个默认值只会把问题推到页面。</p>
     */
    private Map<String, Long> references(long tenantId) {
        Map<String, Long> refs = new LinkedHashMap<>();
        for (Object[] row : db.rows(
                "SELECT id, username FROM sys_user WHERE tenant_id = ?", tenantId)) {
            refs.put("user:" + SysDb.asString(row[1]), SysDb.asLong(row[0]));
        }
        for (Object[] row : db.rows(
                "SELECT id, code FROM biz_domain WHERE tenant_id = ?", tenantId)) {
            refs.put("domain:" + SysDb.asString(row[1]), SysDb.asLong(row[0]));
        }
        for (Object[] row : db.rows(
                "SELECT id, code FROM sys_vendor WHERE tenant_id = ?", tenantId)) {
            refs.put("vendor:" + SysDb.asString(row[1]), SysDb.asLong(row[0]));
        }
        for (Object[] row : db.rows(
                "SELECT id, code FROM sys_org WHERE tenant_id = ? AND code IN ('SUPPLY', 'IT')", tenantId)) {
            String code = SysDb.asString(row[1]);
            refs.put("SUPPLY".equals(code) ? "supplyOrg" : "itOrg", SysDb.asLong(row[0]));
        }
        db.row("SELECT id FROM test_module WHERE tenant_id = ? ORDER BY id LIMIT 1", tenantId)
                .map(row -> SysDb.asLong(row[0]))
                .ifPresent(id -> refs.put("module", id));
        for (String required : List.of("user:po1", "user:pm", "domain:ORD", "vendor:VEN-A",
                "supplyOrg", "itOrg", "module")) {
            if (!refs.containsKey(required)) {
                throw new ApiException(409, "DEMO_RESET_TENANT",
                        "种子数据缺失（" + required + "）：先执行 make platform-seed 再重置演示数据",
                        Map.of());
            }
        }
        return refs;
    }

    // ------------------------------------------------------------------ 删除

    /**
     * 按 {@link DemoFixture#DELETE_ORDER} 逐表删除本租户的业务实例数据。
     *
     * <p>表名与保留谓词都来自代码常量（不是请求参数），参数只有 {@code tenant_id}，所以拼接安全。
     * {@code keepFilter} 存在时按 {@code AND NOT (<keepFilter>)} 保留命中行（例如 V9 迁移种下的
     * 两个执行器）——「删什么/留什么」仍只有 {@code DemoFixture} 一处拥有，脚本不再做第二套判断。
     * 这些表在迁移里没有 FOREIGN KEY 约束，删除顺序必须自身完备——顺序写错的后果不是报错，
     * 而是静默留下孤儿行，因此顺序收敛在 {@code DemoFixture} 一处。</p>
     */
    private long deleteBusinessData(long tenantId) {
        long deleted = 0;
        for (DemoFixture.TableReset reset : DemoFixture.DELETE_ORDER) {
            String sql = "DELETE FROM " + reset.table() + " WHERE tenant_id = ?";
            if (reset.keepFilter() != null && !reset.keepFilter().isBlank()) {
                sql += " AND NOT (" + reset.keepFilter() + ")";
            }
            deleted += db.update(sql, tenantId);
        }
        return deleted;
    }

    // ------------------------------------------------------------------ 插入

    private void insertFixture(long tenantId, Map<String, Long> refs) {
        LocalDate today = LocalDate.now();
        long pm = refs.get("user:pm");

        // 1) 版本 + 里程碑
        long releaseId = db.insert("INSERT INTO `release` (tenant_id, code, name, domain_id, status,"
                        + " start_date, end_date, planned_release_date, gate_mode, created_by, updated_by)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, DemoFixture.RELEASE_CODE, "演示版本 2026-11", refs.get("domain:ORD"), "PLAN",
                Date.valueOf(today.minusDays(12)), Date.valueOf(today.plusDays(14)),
                Date.valueOf(today.plusDays(14)), "STRICT", pm, pm);
        db.insert("INSERT INTO milestone (tenant_id, release_id, name, planned_date, status,"
                        + " created_by, updated_by) VALUES (?,?,?,?,?,?,?)",
                tenantId, releaseId, "演示提测里程碑", Date.valueOf(today.plusDays(10)), "PLANNED",
                pm, pm);

        // 2) 迭代
        long sprintId = db.insert("INSERT INTO sprint (tenant_id, release_id, name, goal, start_date,"
                        + " end_date, capacity_days, status, created_by, updated_by)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                tenantId, releaseId, DemoFixture.SPRINT_NAME, "演示迭代：打通需求→用例→缺陷的可见性",
                Date.valueOf(today.minusDays(12)), Date.valueOf(today.plusDays(4)), 40.0, "PLANNING",
                pm, pm);

        // 3) 需求（父需求用 code 表达，父行总在子行之前）
        List<Object[]> requirementRows = DemoFixture.requirementRows(tenantId, refs);
        Map<String, Long> requirementIds = new LinkedHashMap<>();
        Map<String, Long> requirementDomains = new LinkedHashMap<>();
        int requirementIndex = 0;
        for (Object[] row : requirementRows) {
            String code = (String) row[DemoFixture.R_CODE];
            String parentCode = (String) row[DemoFixture.R_PARENT_CODE];
            Long parentId = parentCode == null || parentCode.isEmpty()
                    ? null : requirementIds.get(parentCode);
            Long domainId = (Long) row[DemoFixture.R_DOMAIN_ID];
            Long reporterId = (Long) row[DemoFixture.R_REPORTER_ID];
            String status = (String) row[DemoFixture.R_STATUS];
            double estimate = ((Integer) row[DemoFixture.R_ESTIMATE]).doubleValue();
            boolean inRelease = Boolean.TRUE.equals(row[DemoFixture.R_IN_RELEASE]);
            long id = db.insert("INSERT INTO requirement (tenant_id, code, type, parent_id, domain_id,"
                            + " origin_org_id, reporter_id, owner_user_id, owner_vendor_id, title,"
                            + " priority, status, estimate_days, actual_days, release_id,"
                            + " plan_start_date, plan_end_date, created_by, updated_by)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenantId, code, row[DemoFixture.R_TYPE], parentId, domainId,
                    row[DemoFixture.R_ORG_ID], reporterId, row[DemoFixture.R_OWNER_ID],
                    row[DemoFixture.R_VENDOR_ID], row[DemoFixture.R_TITLE], row[DemoFixture.R_PRIORITY],
                    status, estimate, "DONE".equals(status) ? estimate : null,
                    inRelease ? releaseId : null,
                    Date.valueOf((LocalDate) row[DemoFixture.R_PLAN_START]),
                    Date.valueOf((LocalDate) row[DemoFixture.R_PLAN_END]), reporterId, reporterId);
            requirementIds.put(code, id);
            requirementDomains.put(code, domainId);
            db.update("INSERT INTO requirement_domain_rel (tenant_id, requirement_id, domain_id)"
                    + " VALUES (?,?,?)", tenantId, id, domainId);
            if (inRelease) {
                db.update("INSERT INTO release_requirement (tenant_id, release_id, requirement_id,"
                        + " added_by) VALUES (?,?,?,?)", tenantId, releaseId, id, pm);
            }
            requirementIndex++;
        }
        log.info("demo-reset: 插入需求 {} 条", requirementIndex);

        // 4) 任务（甘特「任务」视图的数据来源，全部挂演示迭代）
        int taskIndex = 0;
        for (Object[] row : DemoFixture.taskRows(refs)) {
            String requirementCode = (String) row[0];
            Long requirementId = requirementIds.get(requirementCode);
            db.insert("INSERT INTO task (tenant_id, requirement_id, sprint_id, title, assignee_id,"
                            + " estimate_hours, start_date, due_date, status, sort_no, domain_id,"
                            + " created_by, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenantId, requirementId, sprintId, row[1], row[2], row[3],
                    Date.valueOf((LocalDate) row[4]), Date.valueOf((LocalDate) row[5]), row[6],
                    taskIndex++, requirementDomains.get(requirementCode), pm, pm);
        }

        // 5) 用例（挂既有测试模块；模块属定义数据，重置不删）
        List<Long> caseIds = new ArrayList<>();
        List<Long> caseRequirements = new ArrayList<>();
        for (Object[] row : DemoFixture.caseRows(refs)) {
            Long requirementId = requirementIds.get((String) row[1]);
            long caseId = db.insert("INSERT INTO test_case (tenant_id, code, module_id, title, type,"
                            + " priority, automated, status, current_version, requirement_id,"
                            + " domain_id, created_by, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenantId, row[0], row[6], row[2], row[3], row[4], 0, "ACTIVE", 1,
                    requirementId, row[5], pm, pm);
            caseIds.add(caseId);
            caseRequirements.add(requirementId);
        }

        // 6) 测试计划 + 计划-用例（≥10 条，质量门禁/轮次才有可执行用例）
        long planId = db.insert("INSERT INTO test_plan (tenant_id, release_id, name, window_start,"
                        + " window_end, owner_id, status, gate_mode, domain_id, created_by, updated_by)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, releaseId, DemoFixture.PLAN_NAME,
                Timestamp.valueOf(LocalDateTime.now().minusDays(12).withHour(9).withMinute(0)),
                Timestamp.valueOf(LocalDateTime.now().plusDays(14).withHour(18).withMinute(0)),
                refs.get("user:qa1"), "DRAFT", "STRICT", refs.get("domain:ORD"), pm, pm);
        int planCaseIndex = 0;
        for (int i = 0; i < caseIds.size(); i++) {
            if (planCaseIndex++ >= DemoFixture.PLAN_CASE_COUNT) {
                break;
            }
            db.update("INSERT INTO test_plan_case (tenant_id, plan_id, case_id, case_version,"
                            + " requirement_id, executor_id, result) VALUES (?,?,?,?,?,?,?)",
                    tenantId, planId, caseIds.get(i), 1, caseRequirements.get(i),
                    refs.get("user:qa1"), "NOT_RUN");
        }

        // 7) 缺陷（承接厂商 VEN-A：vendor1 登录能看到这 3 条）
        for (Object[] row : DemoFixture.defectRows(refs)) {
            Long requirementId = requirementIds.get((String) row[1]);
            String status = (String) row[5];
            db.insert("INSERT INTO defect (tenant_id, code, requirement_id, release_id, title,"
                            + " severity, priority, status, reporter_id, handler_id, domain_id,"
                            + " vendor_id, closed_at, created_by, updated_by)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenantId, row[0], requirementId, releaseId, row[2], row[3], row[4], status,
                    pm, row[7], row[6], refs.get("vendor:VEN-A"),
                    "CLOSED".equals(status) ? Timestamp.valueOf(LocalDateTime.now()) : null,
                    pm, pm);
        }

        // 8) 通知（恰好 4 条 = DemoFixture.NOTIFICATION_COUNT；修复前是 99+）
        for (Object[] row : DemoFixture.notificationRows(refs)) {
            db.insert("INSERT INTO notification (tenant_id, user_id, type, title, content, biz_type)"
                    + " VALUES (?,?,?,?,?,?)",
                    tenantId, row[0], row[1], row[2], row[3], row[4]);
        }
    }

    // ------------------------------------------------------------------ 不变量

    /** 4 条口径不变量（AC-13）+ 夹具痕迹清零。数字来自 {@link DemoFixture}，不是随手写死。 */
    private Map<String, Long> invariants(long tenantId) {
        Map<String, Long> invariants = new LinkedHashMap<>();
        invariants.put("po1Visible", visibleRequirements(tenantId, "po1"));
        invariants.put("vendorAVisible", visibleRequirements(tenantId, "vendor1"));
        invariants.put("vendorBVisible", visibleRequirements(tenantId, "vendor2"));
        invariants.put("fixtureTraces", fixtureTraces(tenantId));
        invariants.put("actualDaysOver30", db.count(
                "SELECT COUNT(*) FROM requirement WHERE tenant_id = ? AND actual_days > ?",
                tenantId, ACTUAL_DAYS_LIMIT));
        invariants.put("overdueGanttRows", overdueGanttRows(tenantId));
        return invariants;
    }

    private List<String> violations(long tenantId, Map<String, Long> counts) {
        List<String> violations = new ArrayList<>();
        expect(violations, "需求总数", counts.get("requirements"), DemoFixture.REQUIREMENT_COUNT);
        expect(violations, "版本数", counts.get("releases"), DemoFixture.RELEASE_COUNT);
        expect(violations, "迭代数", counts.get("sprints"), DemoFixture.SPRINT_COUNT);
        expect(violations, "任务数", counts.get("tasks"), DemoFixture.TASK_COUNT);
        expect(violations, "用例数", counts.get("testCases"), DemoFixture.CASE_COUNT);
        expect(violations, "测试计划数", counts.get("testPlans"), DemoFixture.PLAN_COUNT);
        expect(violations, "计划用例数", counts.get("planCases"), DemoFixture.PLAN_CASE_COUNT);
        expect(violations, "缺陷数", counts.get("defects"), DemoFixture.DEFECT_COUNT);
        expect(violations, "po1 可见需求", counts.get("po1Visible"), DemoFixture.PO1_VISIBLE);
        expect(violations, "vendor1（VEN-A）可见需求", counts.get("vendorAVisible"),
                DemoFixture.VENDOR_A_VISIBLE);
        expect(violations, "vendor2（VEN-B）可见需求", counts.get("vendorBVisible"),
                DemoFixture.VENDOR_B_VISIBLE);
        expect(violations, "夹具痕迹（s1-/s5-/s7-/neg-sm-）", counts.get("fixtureTraces"), 0);
        expect(violations, "人天 > 30 的残留", counts.get("actualDaysOver30"), 0);
        expect(violations, "甘特逾期行", counts.get("overdueGanttRows"), 0);
        expect(violations, "通知", counts.get("notifications"), DemoFixture.NOTIFICATION_COUNT);
        // r15：四张原先漏删的表必须真的清空（执行器保留 V9 种子的 2 行）。
        expect(violations, "报表定义", counts.get("reports"), 0);
        expect(violations, "门户卡片配置", counts.get("portalCards"), 0);
        expect(violations, "门户个人 pin", counts.get("portalPins"), 0);
        expect(violations, "测试执行器", counts.get("executors"), 2);
        expect(violations, "开放客户端", counts.get("apiClients"), 0);
        expect(violations, "报表探针残留", counts.get("probeReports"), 0);
        // r15-fix（QA D-01/D-02）：派生实例与门禁残留都必须归零，否则演示页会带着上一轮的痕迹。
        // testEnvs 不进断言：种子环境行是唯一合法基线，但人工建的环境不该让整次重置 409，
        // 它作为纯计数交给脚本按「残留 0 + 种子行在」核对。
        expect(violations, "测试策略（派生自 release）", counts.get("testStrategies"),
                DemoFixture.TEST_STRATEGY_COUNT);
        expect(violations, "门禁 RUN_TAG 残留（wf_definition/test_module/test_env）",
                counts.get("gateResidue"), DemoFixture.GATE_RESIDUE_COUNT);
        return violations;
    }

    private static void expect(List<String> violations, String label, Long actual, long expected) {
        if (actual == null || actual != expected) {
            violations.add(label + "应为 " + expected + "，实际 " + actual);
        }
    }

    private long fixtureTraces(long tenantId) {
        long traces = 0;
        for (String prefix : FIXTURE_PREFIXES) {
            String like = prefix + "%";
            traces += db.count("SELECT COUNT(*) FROM requirement WHERE tenant_id = ?"
                    + " AND (code LIKE ? OR title LIKE ?)", tenantId, like, like);
        }
        return traces;
    }

    /**
     * 甘特「逾期」的口径与 {@code GanttWarnings} 对齐：计划结束日早于今天且未收口。
     * 终态集合取各类型的收口状态，避免把「已完成的 8 条里的第 8 条」误判成逾期。
     */
    private long overdueGanttRows(long tenantId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM requirement WHERE tenant_id = ?"
                + " AND plan_end_date IS NOT NULL AND plan_end_date < ? AND status NOT IN (");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(Date.valueOf(LocalDate.now()));
        for (int i = 0; i < TERMINAL_STATUSES.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
            params.add(TERMINAL_STATUSES.get(i));
        }
        sql.append(')');
        return db.count(sql.toString(), params.toArray());
    }

    // ------------------------------------------------------------------ 可见性（真实范围引擎）

    /**
     * 用生产同一条链路算「这个账号能看到几条需求」：{@link UserIdentity#scope()} 决定分档，
     * {@link ScopeSql#forTable} 生成谓词。
     *
     * <p>不写死 8/3/0 的原因：写死只能证明插入语句跑过了，不能证明隔离口径正确；而隔离口径
     * 正是这次演示要给人看的东西。角色/域/组织子树从库里现读，与登录时的解析方式一致。</p>
     */
    private long visibleRequirements(long tenantId, String username) {
        DataScope scope = scopeOf(tenantId, username);
        SqlPredicate predicate = ScopeSql.forTable("requirement", "r", scope);
        Long value = namedJdbc.queryForObject(
                "SELECT COUNT(*) FROM requirement r WHERE " + predicate.sql(),
                predicate.params(), Long.class);
        return value == null ? 0L : value;
    }

    private DataScope scopeOf(long tenantId, String username) {
        Object[] user = db.row("SELECT id, name, vendor_id, org_id FROM sys_user"
                        + " WHERE tenant_id = ? AND username = ?", tenantId, username)
                .orElseThrow(() -> new ApiException(409, "DEMO_RESET_TENANT",
                        "账号 " + username + " 不存在：先执行 make platform-seed", Map.of()));
        long userId = SysDb.asLong(user[0]);
        Long vendorId = SysDb.asNullableLong(user[2]);
        List<String> roles = db.scalars("SELECT r.code FROM sys_user_role ur"
                        + " JOIN sys_role r ON r.id = ur.role_id"
                        + " WHERE ur.tenant_id = ? AND ur.user_id = ? ORDER BY r.code", tenantId, userId)
                .stream().map(SysDb::asString).toList();
        Set<Long> domainIds = new LinkedHashSet<>();
        for (Object value : db.scalars("SELECT domain_id FROM sys_user_domain"
                + " WHERE tenant_id = ? AND user_id = ? ORDER BY domain_id", tenantId, userId)) {
            domainIds.add(SysDb.asLong(value));
        }
        Set<Long> orgSubtree = orgSubtree(tenantId, SysDb.asNullableLong(user[3]));
        return new UserIdentity(userId, tenantId, username, SysDb.asString(user[1]), tenantCode, "演示租户",
                roles, domainIds, vendorId, orgSubtree).scope();
    }

    /** 组织子树：与 {@code AuthService} 同一条 LIKE 前缀查询（{@code path} 是祖先 id 链）。 */
    private Set<Long> orgSubtree(long tenantId, Long orgId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (orgId == null) {
            return ids;
        }
        String path = db.row("SELECT path FROM sys_org WHERE tenant_id = ? AND id = ?", tenantId, orgId)
                .map(row -> SysDb.asString(row[0])).orElse(null);
        if (path == null) {
            return ids;
        }
        for (Object value : db.scalars("SELECT id FROM sys_org WHERE tenant_id = ? AND path LIKE ?",
                tenantId, path + "%")) {
            ids.add(SysDb.asLong(value));
        }
        return ids;
    }
}
