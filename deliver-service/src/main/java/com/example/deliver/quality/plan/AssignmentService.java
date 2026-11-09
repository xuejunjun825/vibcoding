package com.example.deliver.quality.plan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Execution assignment (AC-9): a plan (or a round) is split by executor and module. The sum of
 * planned counts must equal the number of cases in scope — the difference is computed here, on
 * the server, because "the frontend adds it up" is how 400s become rounding arguments.
 */
@Service
public class AssignmentService {

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public AssignmentService(ScopedJdbc jdbcRead, NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PlanViews.AssignmentView> list(long planId, Long roundId) {
        requirePlan(planId);
        String scopeFilter = roundId == null ? "a.round_id IS NULL" : "a.round_id = :roundId";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("planId", planId);
        if (roundId != null) {
            params.put("roundId", roundId);
        }
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT a.id, a.plan_id, a.round_id, a.executor_id, a.module_id, a.planned_count,
                       a.due_date, u.name AS executor_name, m.name AS module_name
                FROM plan_assignment a
                JOIN test_plan p ON p.id = a.plan_id
                LEFT JOIN sys_user u ON u.id = a.executor_id
                LEFT JOIN test_module m ON m.id = a.module_id
                WHERE /*scope:p*/ AND a.plan_id = :planId
                  AND %s
                ORDER BY a.executor_id ASC, a.module_id ASC
                """.formatted(scopeFilter), params);
        List<PlanViews.AssignmentView> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            long executorId = Rows.toLong(Rows.object(row, "executor_id"), 0L);
            Long moduleId = Rows.lng(row, "module_id");
            long planned = Rows.toLong(Rows.object(row, "planned_count"), 0L);
            long executed = executed(planId, roundId, executorId, moduleId);
            LocalDate dueDate = Rows.toDate(Rows.object(row, "due_date"));
            boolean overdue = dueDate != null && dueDate.isBefore(LocalDate.now()) && executed < planned;
            out.add(new PlanViews.AssignmentView(
                    Rows.toLong(Rows.object(row, "id"), 0L),
                    planId,
                    Rows.lng(row, "round_id"),
                    executorId,
                    Rows.str(row, "executor_name"),
                    moduleId,
                    Rows.str(row, "module_name"),
                    (int) planned,
                    dueDate == null ? null : dueDate.toString(),
                    (int) executed,
                    overdue));
        }
        return out;
    }

    @Transactional
    public List<PlanViews.AssignmentView> replace(long planId, Long roundId,
                                                  PlanViews.AssignmentBody body) {
        DataScope scope = TenantContext.require();
        Map<String, Object> plan = requirePlan(planId);
        String status = Rows.str(plan, "status");
        PlanLifecycle.requireWritable(status, "执行分配");
        if ("DRAFT".equals(status)) {
            throw new ApiException(409, "ILLEGAL_PLAN_TRANSITION", "计划提交评审后才能分配执行",
                    Map.of("status", status, "allowedNext", PlanLifecycle.allowedNext(status)));
        }
        Long effectiveRound = roundId != null ? roundId : (body == null ? null : body.roundId());
        if (effectiveRound != null) {
            long found = jdbcRead.count("""
                    SELECT COUNT(*) FROM test_plan_round r
                    WHERE /*scope:r*/ AND r.plan_id = :planId AND r.id = :roundId
                    """, Map.of("planId", planId, "roundId", effectiveRound));
            if (found == 0) {
                throw new ApiException(404, "ROUND_NOT_FOUND", "轮次不存在或不属于该计划",
                        Map.of("planId", planId, "roundId", effectiveRound));
            }
        }
        List<PlanViews.AssignmentRow> rows = body == null || body.rows() == null
                ? List.of()
                : body.rows();
        long plannedTotal = 0;
        for (PlanViews.AssignmentRow row : rows) {
            if (row.executorId() == null || row.plannedCount() < 0) {
                throw new ApiException(400, "VALIDATION_FAILED", "执行人与计划数必须合法",
                        Map.of("field", "rows", "executorId", row.executorId()));
            }
            plannedTotal += row.plannedCount();
        }
        long caseTotal = caseTotal(planId, effectiveRound);
        long diff = caseTotal - plannedTotal;
        if (diff != 0) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("diff", diff);
            detail.put("caseTotal", caseTotal);
            detail.put("plannedTotal", plannedTotal);
            detail.put("unassigned", unassignedCases(planId, rows));
            throw new ApiException(400, "ASSIGNMENT_MISMATCH",
                    "分配合计必须等于用例总数（差额 " + diff + "）", detail);
        }

        if (effectiveRound == null) {
            jdbc.update("DELETE FROM plan_assignment WHERE tenant_id = :tenantId "
                    + "AND plan_id = :planId AND round_id IS NULL",
                    new MapSqlParameterSource().addValue("tenantId", scope.tenantId())
                            .addValue("planId", planId));
        } else {
            jdbc.update("DELETE FROM plan_assignment WHERE tenant_id = :tenantId "
                    + "AND plan_id = :planId AND round_id = :roundId",
                    new MapSqlParameterSource().addValue("tenantId", scope.tenantId())
                            .addValue("planId", planId).addValue("roundId", effectiveRound));
        }
        for (PlanViews.AssignmentRow row : rows) {
            jdbc.update("""
                    INSERT INTO plan_assignment (tenant_id, plan_id, round_id, executor_id,
                        module_id, planned_count, due_date, created_at, updated_at)
                    VALUES (:tenantId, :planId, :roundId, :executorId, :moduleId, :plannedCount,
                        :dueDate, NOW(3), NOW(3))
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", scope.tenantId())
                    .addValue("planId", planId)
                    .addValue("roundId", effectiveRound)
                    .addValue("executorId", row.executorId())
                    .addValue("moduleId", row.moduleId())
                    .addValue("plannedCount", row.plannedCount())
                    .addValue("dueDate", row.dueDate()));
        }
        syncPlanCaseExecutors(planId, effectiveRound, rows);
        audit.record("ASSIGNMENT_REPLACE", "TEST_PLAN", planId,
                Map.of("roundId", effectiveRound == null ? 0L : effectiveRound,
                        "rows", rows.size(), "plannedTotal", plannedTotal));
        return list(planId, effectiveRound);
    }

    /**
     * Mirrors the allocation onto {@code test_plan_case.executor_id} so the executor sees the case
     * in "my work" without a second join. A module-specific row wins over a catch-all row.
     */
    private void syncPlanCaseExecutors(long planId, Long roundId,
                                       List<PlanViews.AssignmentRow> rows) {
        if (roundId != null) {
            return;
        }
        Map<Long, Long> byModule = new LinkedHashMap<>();
        Long catchAll = null;
        for (PlanViews.AssignmentRow row : rows) {
            if (row.moduleId() == null) {
                catchAll = row.executorId();
            } else {
                byModule.put(row.moduleId(), row.executorId());
            }
        }
        if (catchAll != null) {
            jdbc.update("UPDATE test_plan_case SET executor_id = :executorId "
                            + "WHERE tenant_id = :tenantId AND plan_id = :planId",
                    new MapSqlParameterSource().addValue("executorId", catchAll)
                            .addValue("tenantId", TenantContext.require().tenantId())
                            .addValue("planId", planId));
        }
        for (Map.Entry<Long, Long> entry : byModule.entrySet()) {
            jdbc.update("""
                    UPDATE test_plan_case pc
                    JOIN test_case c ON c.id = pc.case_id
                       SET pc.executor_id = :executorId
                     WHERE pc.tenant_id = :tenantId AND pc.plan_id = :planId
                       AND c.module_id = :moduleId
                    """, new MapSqlParameterSource().addValue("executorId", entry.getValue())
                    .addValue("tenantId", TenantContext.require().tenantId())
                    .addValue("planId", planId)
                    .addValue("moduleId", entry.getKey()));
        }
    }

    private long executed(long planId, Long roundId, long executorId, Long moduleId) {
        String moduleFilter = moduleId == null ? "" : " AND c.module_id = :moduleId";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("planId", planId);
        params.put("executorId", executorId);
        if (moduleId != null) {
            params.put("moduleId", moduleId);
        }
        if (roundId == null) {
            return jdbcRead.count("""
                    SELECT COUNT(*) FROM test_plan_case pc
                    JOIN test_plan p ON p.id = pc.plan_id
                    LEFT JOIN test_case c ON c.id = pc.case_id
                    WHERE /*scope:p*/ AND pc.plan_id = :planId
                      AND pc.executor_id = :executorId AND pc.result <> 'NOT_RUN'%s
                    """.formatted(moduleFilter), params);
        }
        params.put("roundId", roundId);
        return jdbcRead.count("""
                WITH latest AS (
                    SELECT e.case_id, e.executor_id, e.result,
                           ROW_NUMBER() OVER (PARTITION BY e.case_id
                                              ORDER BY e.executed_at DESC, e.id DESC) AS rn
                    FROM test_execution e
                    WHERE e.plan_id = :planId AND e.round_id = :roundId
                )
                SELECT COUNT(*) FROM latest l
                JOIN test_plan p ON p.id = :planId
                LEFT JOIN test_case c ON c.id = l.case_id
                WHERE /*scope:p*/ AND l.rn = 1 AND l.executor_id = :executorId
                  AND l.result <> 'NOT_RUN'%s
                """.formatted(moduleFilter), params);
    }

    private long caseTotal(long planId, Long roundId) {
        if (roundId == null) {
            return jdbcRead.count("""
                    SELECT COUNT(*) FROM test_plan_case pc JOIN test_plan p ON p.id = pc.plan_id
                    WHERE /*scope:p*/ AND pc.plan_id = :planId
                    """, Map.of("planId", planId));
        }
        Map<String, Object> round = jdbcRead.one("""
                SELECT r.case_scope_json FROM test_plan_round r
                WHERE /*scope:r*/ AND r.plan_id = :planId AND r.id = :roundId
                """, Map.of("planId", planId, "roundId", roundId));
        if (round == null) {
            return 0;
        }
        String json = Rows.str(round, "case_scope_json");
        if (json == null || json.isBlank()) {
            return 0;
        }
        return json.split("\\{").length - 1L;
    }

    private List<Long> unassignedCases(long planId, List<PlanViews.AssignmentRow> rows) {
        boolean catchAll = rows.stream().anyMatch(row -> row.moduleId() == null);
        if (catchAll) {
            return List.of();
        }
        List<Long> modules = new ArrayList<>();
        for (PlanViews.AssignmentRow row : rows) {
            if (row.moduleId() != null) {
                modules.add(row.moduleId());
            }
        }
        if (modules.isEmpty()) {
            return jdbcRead.list("""
                    SELECT pc.case_id FROM test_plan_case pc JOIN test_plan p ON p.id = pc.plan_id
                    WHERE /*scope:p*/ AND pc.plan_id = :planId ORDER BY pc.case_id LIMIT 100
                    """, Map.of("planId", planId)).stream()
                    .map(row -> Rows.toLong(Rows.object(row, "case_id"), 0L)).toList();
        }
        Map<String, Object> params = Map.of("planId", planId, "modules", modules);
        return jdbcRead.list("""
                SELECT pc.case_id FROM test_plan_case pc
                JOIN test_plan p ON p.id = pc.plan_id
                LEFT JOIN test_case c ON c.id = pc.case_id
                WHERE /*scope:p*/ AND pc.plan_id = :planId
                  AND (c.module_id IS NULL OR c.module_id NOT IN (:modules))
                ORDER BY pc.case_id LIMIT 100
                """, params).stream()
                .map(row -> Rows.toLong(Rows.object(row, "case_id"), 0L)).toList();
    }

    private Map<String, Object> requirePlan(long planId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT p.id, p.status, p.release_id FROM test_plan p
                WHERE /*scope:p*/ AND p.id = :planId
                """, Map.of("planId", planId));
        if (row == null) {
            throw new ApiException(404, "PLAN_NOT_FOUND", "测试计划不存在或不在你的数据范围内",
                    Map.of("planId", planId));
        }
        return row;
    }
}
