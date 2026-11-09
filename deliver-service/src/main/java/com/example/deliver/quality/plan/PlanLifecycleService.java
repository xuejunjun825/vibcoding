package com.example.deliver.quality.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.deliver.quality.aggregate.ExitCriteriaEvaluator;
import com.example.deliver.quality.aggregate.PlanAggregate;
import com.example.deliver.quality.aggregate.QualityAggregate;
import com.example.deliver.quality.aggregate.QualityAggregator;
import com.example.deliver.support.Rows;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.StateTables;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan lifecycle (AC-1) and exit criteria (AC-3).
 *
 * <p>Every transition locks the plan row, re-reads the status and asks {@link StateTables#PLAN}
 * whether the action is legal — the controller never decides, and the frontend stepper renders
 * {@code allowedNext} instead of carrying a second copy. Content changes bump {@code revision}
 * and write {@code plan_change_log}; a plan that is CLOSED or ARCHIVED refuses every write with
 * a 409 whose detail says {@code reason=CLOSED}.</p>
 */
@Service
public class PlanLifecycleService {

    private static final Set<String> APPROVER_ROLES = Set.of("PMO", "MGMT", "PLATFORM_ADMIN");

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final QualityAggregator aggregator;
    private final ExitCriteriaEvaluator exitCriteria;
    private final RoundService rounds;

    public PlanLifecycleService(ScopedJdbc jdbcRead,
                                NamedParameterJdbcTemplate jdbc,
                                ObjectMapper objectMapper,
                                AuditService audit,
                                QualityAggregator aggregator,
                                ExitCriteriaEvaluator exitCriteria,
                                RoundService rounds) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.aggregator = aggregator;
        this.exitCriteria = exitCriteria;
        this.rounds = rounds;
    }

    // ------------------------------------------------------------------ detail

    @Transactional(readOnly = true)
    public PlanViews.PlanDetail detail(long planId) {
        Map<String, Object> row = requirePlan(planId);
        return detailOf(row);
    }

    @Transactional(readOnly = true)
    public List<String> allowedNext(long planId) {
        return PlanLifecycle.allowedNext(Rows.str(requirePlan(planId), "status"));
    }

    // ------------------------------------------------------------------ lifecycle action

    @Transactional
    public PlanViews.PlanDetail action(long planId, String rawAction, PlanViews.ActionBody body) {
        DataScope scope = TenantContext.require();
        String action = PlanLifecycle.normaliseAction(rawAction);
        Map<String, Object> locked = lockPlan(planId);
        String status = Rows.str(locked, "status");
        // "writable" guards content edits; archival is a legal terminal step from CLOSED
        // (StateTables declares CLOSED -> archive -> ARCHIVED), so it must not be rejected here.
        if (!"archive".equals(action)) {
            PlanLifecycle.requireWritable(status, "生命周期动作");
        }
        String target = PlanLifecycle.requireTarget(status, action);
        long releaseId = Rows.toLong(Rows.object(locked, "release_id"), 0L);

        switch (action) {
            case "submit-review" -> requireSubmittable(planId);
            case "approve" -> requireApprover(Rows.toLong(Rows.object(locked, "owner_id")));
            case "reject" -> requireComment(body);
            case "start" -> {
                // status is written below, then the first round is opened against it
            }
            case "complete" -> requireCompletable(planId, releaseId,
                    Rows.str(locked, "exit_criteria_json"));
            case "close" -> requireNoActiveRound(planId);
            default -> {
                // archive needs nothing beyond the state table
            }
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("planId", planId)
                .addValue("tenantId", scope.tenantId())
                .addValue("userId", scope.userId())
                .addValue("status", target);
        StringBuilder sql = new StringBuilder("UPDATE test_plan SET status = :status, "
                + "updated_at = NOW(3), updated_by = :userId");
        if ("approve".equals(action)) {
            sql.append(", approved_by = :userId, approved_at = NOW(3), review_comment = :comment");
            params.addValue("comment", body == null ? null : body.comment());
        }
        if ("reject".equals(action)) {
            sql.append(", approved_by = NULL, approved_at = NULL, review_comment = :comment");
            params.addValue("comment", body == null ? null : body.comment());
        }
        if ("close".equals(action)) {
            sql.append(", closed_at = NOW(3), close_reason = :reason");
            params.addValue("reason", body == null ? null : body.reason());
        }
        sql.append(" WHERE id = :planId AND tenant_id = :tenantId");
        jdbc.update(sql.toString(), params);

        if ("start".equals(action)) {
            rounds.ensureFirstRound(planId);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("planId", planId);
        detail.put("action", action);
        detail.put("from", status);
        detail.put("to", target);
        audit.record("PLAN_" + action.toUpperCase(Locale.ROOT).replace('-', '_'),
                "TEST_PLAN", planId, detail);
        return detail(planId);
    }

    // ------------------------------------------------------------------ change (PATCH)

    @Transactional
    public PlanViews.PlanDetail update(long planId, PlanViews.UpdatePlan cmd) {
        DataScope scope = TenantContext.require();
        Map<String, Object> locked = lockPlan(planId);
        String status = Rows.str(locked, "status");
        PlanLifecycle.requireWritable(status, "计划变更");

        boolean contentChange = cmd != null && (cmd.name() != null || cmd.windowStart() != null
                || cmd.windowEnd() != null || cmd.envId() != null || cmd.entryCriteria() != null
                || cmd.exitCriteria() != null);
        if (!contentChange) {
            return detail(planId);
        }
        boolean approved = !"DRAFT".equals(status) && !"PENDING_REVIEW".equals(status);
        if (approved && (cmd.reason() == null || cmd.reason().isBlank())) {
            throw new ApiException(400, "CHANGE_REASON_REQUIRED",
                    "计划已提交评审，变更必须填写原因", Map.of("status", status));
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("planId", planId)
                .addValue("tenantId", scope.tenantId())
                .addValue("userId", scope.userId());
        StringBuilder sql = new StringBuilder("UPDATE test_plan SET revision = revision + 1, "
                + "updated_at = NOW(3), updated_by = :userId");
        if (cmd.name() != null && !cmd.name().isBlank()) {
            sql.append(", name = :name");
            params.addValue("name", cmd.name().trim());
        }
        if (cmd.windowStart() != null) {
            sql.append(", window_start = :windowStart");
            params.addValue("windowStart", cmd.windowStart());
        }
        if (cmd.windowEnd() != null) {
            sql.append(", window_end = :windowEnd");
            params.addValue("windowEnd", cmd.windowEnd());
        }
        if (cmd.envId() != null) {
            requireEnv(cmd.envId(), scope.tenantId());
            sql.append(", env_id = :envId");
            params.addValue("envId", cmd.envId());
        }
        if (cmd.entryCriteria() != null) {
            sql.append(", entry_criteria_json = :entryCriteria");
            params.addValue("entryCriteria", jsonOf(cmd.entryCriteria()));
        }
        if (cmd.exitCriteria() != null) {
            sql.append(", exit_criteria_json = :exitCriteria");
            params.addValue("exitCriteria", jsonOf(cmd.exitCriteria()));
        }
        sql.append(" WHERE id = :planId AND tenant_id = :tenantId");
        jdbc.update(sql.toString(), params);

        writeChangeLog(planId, scope, "UPDATE", cmd.reason(), cmd.approverId(),
                locked, Map.of("patch", cmd.toString()));
        return detail(planId);
    }

    // ------------------------------------------------------------------ progress / change logs

    @Transactional(readOnly = true)
    public PlanViews.Progress progress(long planId, Long roundId) {
        requirePlan(planId);
        PlanAggregate plan = aggregator.aggregatePlan(planId);
        PlanViews.Summary totals = new PlanViews.Summary(plan.total(), plan.executed(),
                plan.passed(), plan.failed(), plan.blocked(),
                plan.total() - plan.executed(), plan.passRate(), plan.coverage());

        List<PlanViews.ByPerson> byPerson = roundId == null
                ? personProgress(planId, null)
                : personProgress(planId, roundId);
        List<Map<String, Object>> moduleRows = jdbcRead.list("""
                SELECT c.module_id AS module_id, MAX(m.name) AS module_name,
                       COUNT(*) AS planned,
                       COALESCE(SUM(CASE WHEN pc.result <> 'NOT_RUN' THEN 1 ELSE 0 END), 0) AS executed,
                       COALESCE(SUM(CASE WHEN pc.result = 'PASS' THEN 1 ELSE 0 END), 0) AS passed,
                       COALESCE(SUM(CASE WHEN pc.result = 'FAIL' THEN 1 ELSE 0 END), 0) AS failed,
                       COALESCE(SUM(CASE WHEN pc.result = 'BLOCKED' THEN 1 ELSE 0 END), 0) AS blocked
                FROM test_plan_case pc
                JOIN test_plan p ON p.id = pc.plan_id
                LEFT JOIN test_case c ON c.id = pc.case_id
                LEFT JOIN test_module m ON m.id = c.module_id
                WHERE /*scope:p*/ AND pc.plan_id = :planId
                GROUP BY c.module_id
                ORDER BY c.module_id ASC
                """, Map.of("planId", planId));
        List<PlanViews.ByModule> byModule = new ArrayList<>(moduleRows.size());
        for (Map<String, Object> row : moduleRows) {
            byModule.add(new PlanViews.ByModule(
                    Rows.lng(row, "module_id"),
                    Rows.str(row, "module_name"),
                    Rows.toInt(Rows.object(row, "planned"), 0),
                    Rows.toInt(Rows.object(row, "executed"), 0),
                    Rows.toInt(Rows.object(row, "passed"), 0),
                    Rows.toInt(Rows.object(row, "failed"), 0),
                    Rows.toInt(Rows.object(row, "blocked"), 0)));
        }
        List<Map<String, Object>> overdueRows = jdbcRead.list("""
                SELECT pc.case_id AS case_id, c.code AS case_code, c.title AS title,
                       a.executor_id AS executor_id, u.name AS executor_name,
                       a.due_date AS due_date, pc.result AS result
                FROM plan_assignment a
                JOIN test_plan p ON p.id = a.plan_id
                JOIN test_plan_case pc ON pc.plan_id = a.plan_id
                LEFT JOIN test_case c ON c.id = pc.case_id
                LEFT JOIN sys_user u ON u.id = a.executor_id
                WHERE /*scope:p*/ AND a.plan_id = :planId AND a.due_date < CURDATE()
                  AND pc.result = 'NOT_RUN'
                  AND (a.module_id IS NULL OR a.module_id = c.module_id)
                ORDER BY a.due_date ASC, pc.case_id ASC
                LIMIT 200
                """, Map.of("planId", planId));
        List<PlanViews.Overdue> overdue = new ArrayList<>(overdueRows.size());
        for (Map<String, Object> row : overdueRows) {
            overdue.add(new PlanViews.Overdue(
                    Rows.toLong(Rows.object(row, "case_id"), 0L),
                    Rows.str(row, "case_code"),
                    Rows.str(row, "title"),
                    Rows.toLong(Rows.object(row, "executor_id"), 0L),
                    Rows.str(row, "executor_name"),
                    dateText(Rows.object(row, "due_date")),
                    Rows.str(row, "result", "NOT_RUN")));
        }
        return new PlanViews.Progress(byPerson, byModule, overdue, totals);
    }

    private List<PlanViews.ByPerson> personProgress(long planId, Long roundId) {
        List<Map<String, Object>> rows = roundId == null
                ? jdbcRead.list("""
                        SELECT pc.executor_id AS executor_id, MAX(u.name) AS executor_name,
                               COUNT(*) AS planned,
                               COALESCE(SUM(CASE WHEN pc.result <> 'NOT_RUN' THEN 1 ELSE 0 END), 0) AS executed,
                               COALESCE(SUM(CASE WHEN pc.result = 'PASS' THEN 1 ELSE 0 END), 0) AS passed,
                               COALESCE(SUM(CASE WHEN pc.result = 'FAIL' THEN 1 ELSE 0 END), 0) AS failed,
                               COALESCE(SUM(CASE WHEN pc.result = 'BLOCKED' THEN 1 ELSE 0 END), 0) AS blocked
                        FROM test_plan_case pc
                        JOIN test_plan p ON p.id = pc.plan_id
                        LEFT JOIN sys_user u ON u.id = pc.executor_id
                        WHERE /*scope:p*/ AND pc.plan_id = :planId
                        GROUP BY pc.executor_id
                        ORDER BY pc.executor_id ASC
                        """, Map.of("planId", planId))
                : jdbcRead.list("""
                        WITH latest AS (
                            SELECT e.case_id, e.executor_id, e.result,
                                   ROW_NUMBER() OVER (PARTITION BY e.case_id
                                                      ORDER BY e.executed_at DESC, e.id DESC) AS rn
                            FROM test_execution e
                            WHERE e.plan_id = :planId AND e.round_id = :roundId
                        )
                        SELECT l.executor_id AS executor_id, MAX(u.name) AS executor_name,
                               COUNT(*) AS planned,
                               COUNT(*) AS executed,
                               COALESCE(SUM(CASE WHEN l.result = 'PASS' THEN 1 ELSE 0 END), 0) AS passed,
                               COALESCE(SUM(CASE WHEN l.result = 'FAIL' THEN 1 ELSE 0 END), 0) AS failed,
                               COALESCE(SUM(CASE WHEN l.result = 'BLOCKED' THEN 1 ELSE 0 END), 0) AS blocked
                        FROM latest l
                        JOIN test_plan p ON p.id = :planId
                        LEFT JOIN sys_user u ON u.id = l.executor_id
                        WHERE /*scope:p*/ AND l.rn = 1
                        GROUP BY l.executor_id
                        ORDER BY l.executor_id ASC
                        """, Map.of("planId", planId, "roundId", roundId));
        List<PlanViews.ByPerson> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            long planned = Rows.toLong(Rows.object(row, "planned"), 0L);
            long passed = Rows.toLong(Rows.object(row, "passed"), 0L);
            out.add(new PlanViews.ByPerson(
                    Rows.toLong(Rows.object(row, "executor_id"), 0L),
                    Rows.str(row, "executor_name"),
                    (int) planned,
                    Rows.toInt(Rows.object(row, "executed"), 0),
                    (int) passed,
                    Rows.toInt(Rows.object(row, "failed"), 0),
                    Rows.toInt(Rows.object(row, "blocked"), 0),
                    com.example.platform.contract.MetricFormulas.passRate(passed, planned)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanViews.ChangeLogRow> changeLogs(long planId, int page, int size) {
        requirePlan(planId);
        PageResponse<Map<String, Object>> rows = jdbcRead.page("""
                SELECT cl.id, cl.revision, cl.change_type, cl.reason, cl.operator_id,
                       cl.approver_id, cl.created_at, cl.before_json, cl.after_json,
                       u.name AS operator_name, ap.name AS approver_name
                FROM plan_change_log cl
                JOIN test_plan p ON p.id = cl.plan_id
                LEFT JOIN sys_user u ON u.id = cl.operator_id
                LEFT JOIN sys_user ap ON ap.id = cl.approver_id
                WHERE /*scope:p*/ AND cl.plan_id = :planId
                ORDER BY cl.revision DESC, cl.id DESC
                """, Map.of("planId", planId), page, size);
        List<PlanViews.ChangeLogRow> items = new ArrayList<>(rows.items().size());
        for (Map<String, Object> row : rows.items()) {
            items.add(new PlanViews.ChangeLogRow(
                    Rows.toLong(Rows.object(row, "id"), 0L),
                    Rows.toInt(Rows.object(row, "revision"), 0),
                    Rows.str(row, "change_type"),
                    Rows.str(row, "reason"),
                    Rows.lng(row, "operator_id"),
                    Rows.str(row, "operator_name"),
                    Rows.lng(row, "approver_id"),
                    Rows.str(row, "approver_name"),
                    dateTimeText(Rows.object(row, "created_at")),
                    Rows.str(row, "before_json"),
                    Rows.str(row, "after_json")));
        }
        return new PageResponse<>(items, rows.total(), rows.page(), rows.size());
    }

    // ------------------------------------------------------------------ helpers

    private PlanViews.PlanDetail detailOf(Map<String, Object> row) {
        long planId = Rows.toLong(Rows.object(row, "id"), 0L);
        String status = Rows.str(row, "status", "DRAFT");
        String exitJson = Rows.str(row, "exit_criteria_json");
        String entryJson = Rows.str(row, "entry_criteria_json");
        List<PlanViews.CriterionItem> exitItems = items(exitJson);
        List<PlanViews.CriterionItem> entryItems = entryJson == null || entryJson.isBlank()
                ? items(ExitCriteriaEvaluator.DEFAULT_ENTRY_CRITERIA)
                : items(entryJson);

        Long activeRoundId = null;
        Integer activeRoundNo = null;
        String activeConclusion = null;
        Map<String, Object> active = jdbcRead.one("""
                SELECT r.id AS id, r.round_no AS round_no, r.conclusion AS conclusion
                FROM test_plan_round r
                WHERE /*scope:r*/ AND r.plan_id = :planId AND r.active = 1
                """, Map.of("planId", planId));
        if (active != null) {
            activeRoundId = Rows.toLong(Rows.object(active, "id"));
            activeRoundNo = Rows.toInt(Rows.object(active, "round_no"), 0);
            activeConclusion = Rows.str(active, "conclusion");
        }
        long roundTotal = jdbcRead.count("""
                SELECT COUNT(*) FROM test_plan_round r
                WHERE /*scope:r*/ AND r.plan_id = :planId
                """, Map.of("planId", planId));

        Map<String, Object> assignment = jdbcRead.one("""
                SELECT COUNT(*) AS row_count, COALESCE(SUM(a.planned_count), 0) AS planned
                FROM plan_assignment a
                WHERE /*scope:a*/ AND a.plan_id = :planId AND a.round_id IS NULL
                """, Map.of("planId", planId));
        long caseTotal = jdbcRead.count("""
                SELECT COUNT(*) FROM test_plan_case pc JOIN test_plan p ON p.id = pc.plan_id
                WHERE /*scope:p*/ AND pc.plan_id = :planId
                """, Map.of("planId", planId));
        long unassigned = jdbcRead.count("""
                SELECT COUNT(*) FROM test_plan_case pc JOIN test_plan p ON p.id = pc.plan_id
                WHERE /*scope:p*/ AND pc.plan_id = :planId AND pc.executor_id IS NULL
                """, Map.of("planId", planId));
        long plannedTotal = assignment == null ? 0
                : Rows.toLong(Rows.object(assignment, "planned"), 0L);
        int assignmentRows = assignment == null ? 0
                : Rows.toInt(Rows.object(assignment, "row_count"), 0);

        PlanAggregate plan = aggregator.aggregatePlan(planId);
        PlanViews.Summary summary = new PlanViews.Summary(plan.total(), plan.executed(),
                plan.passed(), plan.failed(), plan.blocked(), plan.total() - plan.executed(),
                plan.passRate(), plan.coverage());

        Long strategyId = Rows.lng(row, "strategy_id");
        PlanViews.StrategyRef strategy = strategyId == null ? null
                : new PlanViews.StrategyRef(strategyId,
                        Rows.toInt(Rows.object(row, "strategy_version"), 1),
                        Rows.str(row, "strategy_status"));
        Long envId = Rows.lng(row, "env_id");
        PlanViews.EnvRef env = envId == null ? null
                : new PlanViews.EnvRef(envId, Rows.str(row, "env_name"),
                        Rows.str(row, "env_status"));

        return new PlanViews.PlanDetail(
                planId,
                "TP-%04d".formatted(planId),
                Rows.str(row, "name"),
                Rows.toLong(Rows.object(row, "release_id"), 0L),
                status,
                PlanLifecycle.allowedNext(status),
                Rows.toInt(Rows.object(row, "revision"), 0),
                Rows.str(row, "gate_mode", "STRICT"),
                dateTimeText(Rows.object(row, "window_start")),
                dateTimeText(Rows.object(row, "window_end")),
                Rows.lng(row, "owner_id"),
                Rows.str(row, "owner_name"),
                strategy,
                env,
                new PlanViews.Criteria(entryJson, exitJson, entryItems, exitItems),
                new PlanViews.RoundsSummary((int) roundTotal, activeRoundId, activeRoundNo,
                        activeConclusion),
                new PlanViews.AssignmentSummary(assignmentRows, plannedTotal, caseTotal,
                        caseTotal - plannedTotal, unassigned),
                summary);
    }

    private List<PlanViews.CriterionItem> items(String json) {
        List<PlanViews.CriterionItem> out = new ArrayList<>();
        for (ExitCriteriaEvaluator.Criterion criterion : exitCriteria.parse(json)) {
            out.add(new PlanViews.CriterionItem(criterion.key(), criterion.op(),
                    criterion.value()));
        }
        return out;
    }

    private void requireSubmittable(long planId) {
        long cases = jdbcRead.count("""
                SELECT COUNT(*) FROM test_plan_case pc JOIN test_plan p ON p.id = pc.plan_id
                WHERE /*scope:p*/ AND pc.plan_id = :planId
                """, Map.of("planId", planId));
        if (cases == 0) {
            throw new ApiException(409, "PLAN_INCOMPLETE", "计划至少需要一条用例才能提交评审",
                    Map.of("reason", "NO_CASES"));
        }
    }

    private void requireApprover(Long ownerId) {
        long me = TenantContext.require().userId();
        if (ownerId != null && ownerId == me) {
            return;
        }
        if (TenantContext.roles().stream().anyMatch(APPROVER_ROLES::contains)) {
            return;
        }
        throw new ApiException(403, "PLAN_APPROVE_FORBIDDEN",
                "只有计划负责人或 PMO/管理层可以批准计划", Map.of("planOwnerId", ownerId));
    }

    private void requireComment(PlanViews.ActionBody body) {
        if (body == null || body.comment() == null || body.comment().isBlank()) {
            throw new ApiException(400, "CHANGE_REASON_REQUIRED", "驳回必须填写意见",
                    Map.of("field", "comment"));
        }
    }

    private void requireNoActiveRound(long planId) {
        Map<String, Object> active = jdbcRead.one("""
                SELECT r.id FROM test_plan_round r
                WHERE /*scope:r*/ AND r.plan_id = :planId AND r.active = 1
                """, Map.of("planId", planId));
        if (active != null) {
            throw new ApiException(409, "ROUND_IN_PROGRESS", "请先关闭进行中的轮次",
                    Map.of("roundId", Rows.toLong(Rows.object(active, "id"))));
        }
    }

    private void requireCompletable(long planId, long releaseId, String exitJson) {
        requireNoActiveRound(planId);
        PlanAggregate plan = aggregator.aggregatePlan(planId);
        QualityAggregate aggregate = new QualityAggregate(releaseId, plan.total(), plan.executed(),
                plan.passed(), plan.failed(), plan.blocked(), plan.total() - plan.executed(),
                plan.passRate(), plan.coverage(), List.of(plan), Map.of(planId, plan.revision()));
        Map<String, BigDecimal> extra = new LinkedHashMap<>();
        extra.put("openP0P1", BigDecimal.valueOf(openP0P1(releaseId)));
        extra.put("roundClosed", BigDecimal.valueOf(hasClosedRound(planId) ? 1 : 0));
        List<ExitCriteriaEvaluator.UnmetCriterion> unmet =
                exitCriteria.evaluate(exitJson, aggregate, extra);
        if (!unmet.isEmpty()) {
            List<Map<String, Object>> items = new ArrayList<>(unmet.size());
            for (ExitCriteriaEvaluator.UnmetCriterion item : unmet) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("key", item.key());
                view.put("label", item.label());
                view.put("actual", item.actual());
                view.put("expected", item.expected());
                view.put("fixHint", item.fixHint());
                items.add(view);
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("unmet", items);
            detail.put("passRate", aggregate.passRate());
            throw new ApiException(409, "PLAN_EXIT_CRITERIA_UNMET",
                    "准出条件未满足，不能完成计划", detail);
        }
    }

    private boolean hasClosedRound(long planId) {
        long total = jdbcRead.count("""
                SELECT COUNT(*) FROM test_plan_round r
                WHERE /*scope:r*/ AND r.plan_id = :planId
                """, Map.of("planId", planId));
        long closed = jdbcRead.count("""
                SELECT COUNT(*) FROM test_plan_round r
                WHERE /*scope:r*/ AND r.plan_id = :planId AND r.status = 'CLOSED'
                """, Map.of("planId", planId));
        return total > 0 && closed == total;
    }

    private long openP0P1(long releaseId) {
        return jdbcRead.count("""
                SELECT COUNT(*) FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId
                  AND d.severity IN ('P0', 'P1') AND d.status <> 'CLOSED'
                """, Map.of("releaseId", releaseId));
    }

    private Map<String, Object> requirePlan(long planId) {
        Map<String, Object> row = jdbcRead.one(PLAN_SELECT, Map.of("planId", planId));
        if (row == null) {
            throw new ApiException(404, "PLAN_NOT_FOUND", "测试计划不存在或不在你的数据范围内",
                    Map.of("planId", planId));
        }
        return row;
    }

    private Map<String, Object> lockPlan(long planId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT p.id, p.release_id, p.status, p.owner_id, p.exit_criteria_json,
                       p.entry_criteria_json, p.name, p.revision
                FROM test_plan p
                WHERE /*scope:p*/ AND p.id = :planId
                FOR UPDATE
                """, Map.of("planId", planId));
        if (row == null) {
            throw new ApiException(404, "PLAN_NOT_FOUND", "测试计划不存在或不在你的数据范围内",
                    Map.of("planId", planId));
        }
        return row;
    }

    private void requireEnv(long envId, long tenantId) {
        Long found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_env WHERE tenant_id = :tenantId AND id = :envId",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("envId", envId),
                Long.class);
        if (found == null || found == 0) {
            throw new ApiException(400, "VALIDATION_FAILED", "测试环境不存在",
                    Map.of("field", "envId", "envId", envId));
        }
    }

    private void writeChangeLog(long planId, DataScope scope, String changeType, String reason,
                                Long approverId, Map<String, Object> before,
                                Map<String, Object> after) {
        jdbc.update("""
                INSERT INTO plan_change_log (tenant_id, plan_id, revision, change_type,
                    before_json, after_json, reason, approver_id, approved_at, operator_id,
                    created_at)
                VALUES (:tenantId, :planId,
                    (SELECT revision FROM test_plan WHERE id = :planId AND tenant_id = :tenantId),
                    :changeType, :beforeJson, :afterJson, :reason, :approverId, NOW(3), :operatorId,
                    NOW(3))
                """, new MapSqlParameterSource()
                .addValue("tenantId", scope.tenantId())
                .addValue("planId", planId)
                .addValue("changeType", changeType)
                .addValue("beforeJson", jsonOf(before))
                .addValue("afterJson", jsonOf(after))
                .addValue("reason", reason == null || reason.isBlank() ? "内容更新" : reason.trim())
                .addValue("approverId", approverId)
                .addValue("operatorId", scope.userId()));
    }

    private String jsonOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(400, "VALIDATION_FAILED", "条件 JSON 不合法",
                    Map.of("error", ex.getOriginalMessage()));
        }
    }

    private static String dateTimeText(Object value) {
        LocalDateTime dateTime = Rows.toDateTime(value);
        return dateTime == null ? null : dateTime.toString();
    }

    private static String dateText(Object value) {
        LocalDate date = Rows.toDate(value);
        return date == null ? null : date.toString();
    }

    private static final String PLAN_SELECT = """
            SELECT p.id AS id, p.release_id AS release_id, p.name AS name, p.status AS status,
                   p.window_start AS window_start, p.window_end AS window_end,
                   p.owner_id AS owner_id, COALESCE(p.gate_mode, r.gate_mode) AS gate_mode,
                   p.strategy_id AS strategy_id, p.env_id AS env_id,
                   p.entry_criteria_json AS entry_criteria_json,
                   p.exit_criteria_json AS exit_criteria_json,
                   COALESCE(p.revision, 0) AS revision,
                   u.name AS owner_name,
                   s.version_no AS strategy_version, s.status AS strategy_status,
                   e.name AS env_name, e.status AS env_status
            FROM test_plan p
            JOIN `release` r ON r.id = p.release_id
            LEFT JOIN sys_user u ON u.id = p.owner_id
            LEFT JOIN test_strategy s ON s.id = p.strategy_id
            LEFT JOIN test_env e ON e.id = p.env_id
            WHERE /*scope:p*/ AND p.id = :planId
            """;
}
