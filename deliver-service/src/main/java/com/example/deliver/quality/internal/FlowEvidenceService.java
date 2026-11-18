package com.example.deliver.quality.internal;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.deliver.quality.gate.QualityGateService;
import com.example.deliver.quality.gate.QualityGateViews;
import com.example.deliver.quality.report.ReportViews;
import com.example.deliver.quality.report.SignoffService;
import com.example.deliver.support.Rows;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.contract.quality.GateSnapshot;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * r30 / AC-8：版本发版链路的<b>前三段事实</b>，一次批量喂给 {@code req-service}。
 *
 * <p>为什么这里只给事实、不做判定：测试计划、三签、质量门的归属在 deliver，判定的唯一出口也在
 * deliver（计划生命周期、签署规则、{@code GateSnapshot}）；req 只把它们摆成一条传送带并补上自己
 * 拥有的第④段准入。若在这里顺手算出「发布链路是否可发」，就出现第二套判定，前端两个页面会各说各话。</p>
 *
 * <p>失败语义（AC-8 / AC-9）：单段取不到 → {@code status:"MISSING"} + 人可读 {@code reason}，
 * <b>不抛 404/500</b>，整次调用仍返回 200 和另外两段；这样版本详情在 deliver 抖动时仍然可读，
 * 而提交闸门（deploy 侧）走的是"取不到就不放行"的另一条路径，两者刻意不共用。</p>
 *
 * <p>批量：一次 SQL 取版本下全部计划的<b>最新轮</b>结论（子查询取 MAX(round_no)），
 * 不在 Java 里循环查计划；需求交集走 {@code test_plan_case.requirement_id}——库里没有
 * {@code test_plan_requirement} 这张表（技术方案写作时的口径偏差），覆盖关系只能从计划用例反查。</p>
 */
@Service
public class FlowEvidenceService {

    /** 计划状态的三种归类：待批 / 已批（含批后生命周期）/ 其它（如被驳回）。 */
    private static final Set<String> PLAN_PENDING = Set.of("DRAFT", "PENDING_REVIEW");
    private static final Set<String> PLAN_APPROVED = Set.of("APPROVED", "IN_PROGRESS", "COMPLETED", "CLOSED");

    private static final String SEGMENT_MISSING = "MISSING";
    private static final String SEGMENT_PASS = "PASS";
    private static final String SEGMENT_FAIL = "FAIL";
    private static final String SEGMENT_PENDING = "PENDING";

    /** {@code reason} 的上限：它要能塞进设计稿的 2 行 clamp，太长的堆栈式文案没有价值。 */
    private static final int REASON_MAX = 160;

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final SignoffService signoffs;
    private final QualityGateService gates;

    public FlowEvidenceService(ScopedJdbc jdbcRead,
                               NamedParameterJdbcTemplate jdbc,
                               SignoffService signoffs,
                               QualityGateService gates) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.signoffs = signoffs;
        this.gates = gates;
    }

    /**
     * 前三段事实 + 生成时间。
     *
     * @param requirementIds 版本下的需求集合；非空时计划按覆盖关系求交（交集为空退化为全部计划，
     *                       与 {@code AdmissionPolicy} 的既有口径一致：什么都没覆盖 ≠ 没有计划）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> evidence(long releaseId, List<Long> requirementIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("testPlan", testPlan(releaseId, requirementIds));
        out.put("signoff", signoff(releaseId));
        out.put("qualityGate", qualityGate(releaseId));
        out.put("generatedAt", LocalDateTime.now().toString());
        return out;
    }

    // ------------------------------------------------------------------ ① 测试计划

    private Map<String, Object> testPlan(long releaseId, List<Long> requirementIds) {
        try {
            List<Long> ids = requirementIds == null ? List.of() : requirementIds;
            // 空集合不能直接展开成 IN ()：MySQL 解析期就报语法错（CASE 里的死分支也救不了）。
            // 需求为空时用哨兵占位，covers 由 :reqCount = 0 直接判 1。
            List<Long> probeIds = ids.isEmpty() ? List.of(-1L) : ids;
            List<Map<String, Object>> rows = jdbcRead.list("""
                    SELECT p.id AS plan_id, p.name AS plan_name, p.status AS plan_status,
                           r.conclusion AS round_conclusion, r.round_no AS round_no,
                           CASE WHEN :reqCount = 0 THEN 1
                                WHEN EXISTS (SELECT 1 FROM test_plan_case c
                                             WHERE c.plan_id = p.id
                                               AND c.requirement_id IN (:reqIds)) THEN 1
                                ELSE 0 END AS covers
                    FROM test_plan p
                    LEFT JOIN test_plan_round r ON r.plan_id = p.id
                         AND r.round_no = (SELECT MAX(r2.round_no) FROM test_plan_round r2
                                           WHERE r2.plan_id = p.id)
                    WHERE /*scope:p*/ AND p.release_id = :releaseId
                    ORDER BY p.id ASC
                    """, Map.of("releaseId", releaseId, "reqIds", probeIds, "reqCount", ids.size()));
            List<Map<String, Object>> scoped = intersect(rows, ids.isEmpty());
            return testPlanSegment(scoped);
        } catch (RuntimeException ex) {
            return missing("测试计划不可读：" + message(ex));
        }
    }

    /**
     * 需求交集：有覆盖行就只留覆盖行；一条都没有（需求没被任何计划覆盖）时退化为全部计划，
     * 让"计划存在但没写用例"仍然显示成待办，而不是伪装成"没有计划"。
     */
    private static List<Map<String, Object>> intersect(List<Map<String, Object>> rows, boolean all) {
        if (all || rows.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> covered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (Rows.toInt(Rows.object(row, "covers"), 0) == 1) {
                covered.add(row);
            }
        }
        return covered.isEmpty() ? rows : covered;
    }

    private static Map<String, Object> testPlanSegment(List<Map<String, Object>> rows) {
        int total = rows.size();
        int approved = 0;
        int pending = 0;
        List<Map<String, Object>> plans = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String status = Rows.str(row, "plan_status");
            if (PLAN_APPROVED.contains(status)) {
                approved++;
            } else if (PLAN_PENDING.contains(status)) {
                pending++;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            // 计划表没有 code 列（V1 只有 name）：业务标识就用计划名，前端只展示这一列。
            line.put("code", Rows.str(row, "plan_name"));
            line.put("status", status);
            line.put("conclusion", Rows.str(row, "round_conclusion"));
            plans.add(line);
        }
        boolean allApproved = total > 0 && approved == total;
        String status;
        String reason;
        if (total == 0) {
            status = SEGMENT_MISSING;
            reason = "该版本下还没有测试计划";
        } else if (allApproved) {
            status = SEGMENT_PASS;
            reason = null;
        } else if (pending > 0) {
            status = SEGMENT_PENDING;
            reason = "还有 " + pending + " 张测试计划待审批（共 " + total + " 张）";
        } else {
            status = SEGMENT_FAIL;
            reason = "有 " + (total - approved) + " 张测试计划未通过审批（共 " + total + " 张）";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("reason", reason);
        out.put("total", total);
        out.put("approved", approved);
        out.put("pending", pending);
        out.put("allApproved", allApproved);
        out.put("plans", plans);
        return out;
    }

    // ------------------------------------------------------------------ ② 报告三签

    private Map<String, Object> signoff(long releaseId) {
        try {
            Map<String, Object> report = jdbcRead.one("""
                    SELECT r.id AS report_id, r.current_version AS version_no
                    FROM test_report r
                    WHERE /*scope:r*/ AND r.scope = 'RELEASE' AND r.release_id = :releaseId
                    ORDER BY r.created_at DESC, r.id DESC
                    LIMIT 1
                    """, Map.of("releaseId", releaseId));
            if (report == null) {
                return missingSignoff("该版本还没有 RELEASE 范围的测试报告");
            }
            long reportId = Rows.toLong(Rows.object(report, "report_id"), 0L);
            int versionNo = Rows.toInt(Rows.object(report, "version_no"), 1);
            ReportViews.SignoffProgress progress = signoffs.progress(reportId, versionNo);
            return signoff(progress);
        } catch (RuntimeException ex) {
            return missingSignoff("报告签署进度不可读：" + message(ex));
        }
    }

    private static Map<String, Object> signoff(ReportViews.SignoffProgress progress) {
        List<Map<String, Object>> levels = new ArrayList<>();
        if (progress.steps() != null) {
            for (ReportViews.SignoffStep step : progress.steps()) {
                Map<String, Object> level = new LinkedHashMap<>();
                level.put("orderNo", step.orderNo());
                level.put("signRole", step.signRole());
                level.put("roleName", step.roleName());
                level.put("status", step.status());
                level.put("signerName", step.signerName());
                level.put("signedAt", step.signedAt());
                levels.add(level);
            }
        }
        String status;
        String reason;
        if (progress.signed()) {
            status = SEGMENT_PASS;
            reason = null;
        } else if ("FAIL".equals(progress.conclusion())) {
            status = SEGMENT_FAIL;
            reason = "报告结论为 FAIL，签署不通过";
        } else {
            status = SEGMENT_PENDING;
            String who = progress.pendingRoleLabel() == null || progress.pendingRoleLabel().isBlank()
                    ? progress.pendingRole() : progress.pendingRoleLabel();
            reason = "待「" + (who == null || who.isBlank() ? "下一级" : who) + "」签署（"
                    + progress.approved() + "/" + progress.total() + "）";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("reason", reason);
        out.put("reportId", progress.reportId());
        out.put("versionNo", progress.versionNo());
        out.put("conclusion", progress.conclusion());
        out.put("levels", levels);
        out.put("approved", progress.approved());
        out.put("total", progress.total());
        out.put("pendingRole", progress.pendingRole());
        return out;
    }

    /** 缺段形状与 {@code FlowChainViews.SignoffSegment} 的 JSON 键一致（数值位留给前端渲染「—」）。 */
    private static Map<String, Object> missingSignoff(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", SEGMENT_MISSING);
        out.put("reason", reason);
        out.put("reportId", null);
        out.put("versionNo", null);
        out.put("conclusion", null);
        out.put("levels", List.of());
        out.put("approved", 0);
        out.put("total", 0);
        out.put("pendingRole", null);
        return out;
    }

    // ------------------------------------------------------------------ ③ 质量门

    private Map<String, Object> qualityGate(long releaseId) {
        try {
            List<Long> ids = jdbc.queryForList("""
                    SELECT id FROM quality_gate_snapshot
                    WHERE tenant_id = :tenantId AND release_id = :releaseId
                    ORDER BY id DESC LIMIT 1
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId())
                    .addValue("releaseId", releaseId), Long.class);
            if (ids.isEmpty() || ids.get(0) == null) {
                return missingGate("该版本还没有质量门快照（门未评估）");
            }
            QualityGateViews.Snapshot snapshot = gates.viewOf(ids.get(0));
            return qualityGate(snapshot);
        } catch (RuntimeException ex) {
            return missingGate("质量门快照不可读：" + message(ex));
        }
    }

    private static Map<String, Object> qualityGate(QualityGateViews.Snapshot snapshot) {
        List<Map<String, Object>> failedRules = new ArrayList<>();
        if (snapshot.rules() != null) {
            for (GateSnapshot.GateRuleResult rule : snapshot.rules()) {
                if (rule.passed()) {
                    continue;
                }
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("code", rule.code());
                line.put("status", rule.status());
                line.put("required", rule.required());
                line.put("actual", rule.actual());
                line.put("threshold", rule.threshold());
                line.put("fixHint", rule.fixHint());
                failedRules.add(line);
            }
        }
        List<Map<String, Object>> waivers = snapshot.waivers() == null
                ? List.of() : new ArrayList<>(snapshot.waivers());
        String result = snapshot.result();
        String status;
        String reason = null;
        if (GateSnapshot.PASS.equals(result) || GateSnapshot.PASS_WITH_WAIVER.equals(result)) {
            status = SEGMENT_PASS;
        } else if (GateSnapshot.FAIL.equals(result)) {
            status = SEGMENT_FAIL;
            reason = failedRules.isEmpty()
                    ? "质量门结论 FAIL" : failedRules.size() + " 条门规则未通过";
        } else {
            status = SEGMENT_PENDING;
            reason = "质量门结论未定（" + result + "）";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("reason", reason);
        out.put("result", result);
        out.put("snapshotId", snapshot.snapshotId());
        out.put("gateId", snapshot.gateId());
        out.put("reportId", snapshot.reportId());
        out.put("computedAt", snapshot.computedAt());
        out.put("failedRules", failedRules);
        out.put("waivers", waivers);
        return out;
    }

    private static Map<String, Object> missingGate(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", SEGMENT_MISSING);
        out.put("reason", reason);
        out.put("result", null);
        out.put("snapshotId", null);
        out.put("gateId", null);
        out.put("reportId", null);
        out.put("computedAt", null);
        out.put("failedRules", List.of());
        out.put("waivers", List.of());
        return out;
    }

    // ------------------------------------------------------------------ 缺段/工具

    private static Map<String, Object> missing(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", SEGMENT_MISSING);
        out.put("reason", truncate(reason));
        out.put("total", 0);
        out.put("approved", 0);
        out.put("pending", 0);
        out.put("allApproved", false);
        out.put("plans", List.of());
        return out;
    }

    private static String message(RuntimeException ex) {
        String text = ex.getMessage();
        return ex.getClass().getSimpleName() + (text == null || text.isBlank() ? "" : "：" + text);
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= REASON_MAX ? reason : reason.substring(0, REASON_MAX - 1) + "…";
    }

    private static long tenantId() {
        return com.example.platform.common.tenant.TenantContext.require().tenantId();
    }
}
