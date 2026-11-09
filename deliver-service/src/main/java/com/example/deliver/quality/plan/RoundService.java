package com.example.deliver.quality.plan;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.deliver.quality.aggregate.ExitCriteriaEvaluator;
import com.example.deliver.support.Rows;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.MetricFormulas;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test rounds (AC-2): a round pins the case ids and versions it covers, so results of round N stay
 * readable after the plan's case set changes.
 *
 * <p>Concurrency: {@code UNIQUE(plan_id, active)} makes "at most one RUNNING round" a database
 * fact. The insert catches {@link DataIntegrityViolationException} and turns it into a 409 instead
 * of letting a duplicate key reach the client as a 500.</p>
 */
@Service
public class RoundService {

    private static final Set<String> SCOPE_MODES = Set.of("CURRENT", "PREVIOUS_FAILED", "EXPLICIT");

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;

    public RoundService(ScopedJdbc jdbcRead,
                        NamedParameterJdbcTemplate jdbc,
                        ObjectMapper objectMapper,
                        AuditService audit) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    /** Called when a plan enters IN_PROGRESS: a plan without any round gets R1 over its full scope. */
    @Transactional
    public void ensureFirstRound(long planId) {
        long total = count("""
                SELECT COUNT(*) FROM test_plan_round r
                WHERE /*scope:r*/ AND r.plan_id = :planId
                """, Map.of("planId", planId));
        if (total > 0) {
            return;
        }
        open(planId, new PlanViews.OpenRound("FEATURE", "R1", "CURRENT", null, null,
                "计划启动自动开轮"));
    }

    @Transactional(readOnly = true)
    public List<PlanViews.RoundView> list(long planId) {
        requirePlanRow(planId);
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT r.*, u.name AS closed_by_name
                FROM test_plan_round r
                LEFT JOIN sys_user u ON u.id = r.closed_by
                WHERE /*scope:r*/ AND r.plan_id = :planId
                ORDER BY r.round_no ASC
                """, Map.of("planId", planId));
        List<PlanViews.RoundView> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(toView(row));
        }
        return out;
    }

    @Transactional
    public PlanViews.RoundView open(long planId, PlanViews.OpenRound cmd) {
        DataScope scope = TenantContext.require();
        Map<String, Object> plan = lockPlan(planId);
        String status = Rows.str(plan, "status");
        if (!"IN_PROGRESS".equals(status)) {
            throw new ApiException(409, "ILLEGAL_PLAN_TRANSITION", "只有进行中的计划可以开轮次",
                    Map.of("status", status, "allowedNext", PlanLifecycle.allowedNext(status)));
        }
        Map<String, Object> active = jdbcRead.one("""
                SELECT r.id FROM test_plan_round r
                WHERE /*scope:r*/ AND r.plan_id = :planId AND r.active = 1
                """, Map.of("planId", planId));
        if (active != null) {
            throw new ApiException(409, "ROUND_IN_PROGRESS", "同一计划只能有一个进行中的轮次",
                    Map.of("roundId", Rows.toLong(Rows.object(active, "id"))));
        }
        String mode = cmd == null || cmd.scopeMode() == null || cmd.scopeMode().isBlank()
                ? "CURRENT"
                : cmd.scopeMode().trim().toUpperCase();
        if (!SCOPE_MODES.contains(mode)) {
            throw new ApiException(400, "VALIDATION_FAILED", "作用域只能是 CURRENT/PREVIOUS_FAILED/EXPLICIT",
                    Map.of("field", "scopeMode", "value", mode));
        }
        List<Map<String, Object>> scopeCases = switch (mode) {
            case "CURRENT" -> currentScope(planId);
            case "PREVIOUS_FAILED" -> previousFailedScope(planId);
            default -> explicitScope(planId, cmd == null ? null : cmd.caseIds());
        };
        if (scopeCases.isEmpty()) {
            throw new ApiException(409, "ROUND_EMPTY_SCOPE", "本轮次作用域没有用例",
                    Map.of("scopeMode", mode));
        }
        long roundNo = maxRoundNo(planId) + 1;
        String name = cmd == null || cmd.name() == null || cmd.name().isBlank()
                ? "R" + roundNo
                : cmd.name().trim();
        String roundType = cmd == null || cmd.roundType() == null || cmd.roundType().isBlank()
                ? "FEATURE"
                : cmd.roundType().trim().toUpperCase();
        String executorJson = "[]";
        if (cmd != null && cmd.executorIds() != null) {
            executorJson = jsonOf(cmd.executorIds());
        }
        try {
            jdbc.update("""
                    INSERT INTO test_plan_round (tenant_id, plan_id, round_no, round_type, name,
                        case_scope_json, executor_ids_json, start_at, status, active, note,
                        created_at, updated_at)
                    VALUES (:tenantId, :planId, :roundNo, :roundType, :name, :caseScope,
                        :executors, NOW(3), 'RUNNING', 1, :note, NOW(3), NOW(3))
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", scope.tenantId())
                    .addValue("planId", planId)
                    .addValue("roundNo", roundNo)
                    .addValue("roundType", roundType)
                    .addValue("name", name)
                    .addValue("caseScope", jsonOf(scopeCases))
                    .addValue("executors", executorJson)
                    .addValue("note", cmd == null ? null : cmd.note()));
        } catch (DataIntegrityViolationException ex) {
            // UNIQUE(plan_id, active) — another request opened a round at the same time
            throw new ApiException(409, "ROUND_IN_PROGRESS", "同一计划只能有一个进行中的轮次",
                    Map.of("planId", planId));
        }
        long roundId = lastInsertId(scope.tenantId(), planId, roundNo);
        audit.record("ROUND_OPEN", "TEST_PLAN_ROUND", roundId,
                Map.of("planId", planId, "roundNo", roundNo, "scopeMode", mode,
                        "caseTotal", scopeCases.size()));
        return detail(planId, roundId).round();
    }

    @Transactional
    public PlanViews.RoundDetail close(long planId, long roundId, PlanViews.CloseRound cmd) {
        DataScope scope = TenantContext.require();
        lockRound(planId, roundId);
        Map<String, Object> round = requireRound(planId, roundId);
        if ("CLOSED".equals(Rows.str(round, "status"))) {
            throw new ApiException(409, "ROUND_CLOSED", "轮次已关闭，不能再关闭",
                    Map.of("roundId", roundId));
        }
        List<Map<String, Object>> scopeCases = parseScope(Rows.str(round, "case_scope_json"));
        Map<Long, Map<String, Object>> latest = latestExecutions(planId, roundId);
        List<Long> unexecuted = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int blocked = 0;
        for (Map<String, Object> entry : scopeCases) {
            long caseId = Rows.toLong(Rows.object(entry, "caseId"), 0L);
            Map<String, Object> execution = latest.get(caseId);
            String result = execution == null ? "NOT_RUN" : Rows.str(execution, "result", "NOT_RUN");
            switch (result) {
                case "PASS" -> passed++;
                case "FAIL" -> failed++;
                case "BLOCKED" -> blocked++;
                default -> unexecuted.add(caseId);
            }
        }
        int total = scopeCases.size();
        int executed = passed + failed + blocked;
        String suggested = failed > 0 ? "FAIL" : (blocked > 0 ? "CONDITIONAL" : "PASS");
        String requested = cmd == null || cmd.conclusion() == null || cmd.conclusion().isBlank()
                ? suggested
                : PlanLifecycle.normaliseConclusion(cmd.conclusion());
        // A cancelled round is an abandoned round: it freezes what was executed and needs no
        // further runs, so the "everything executed" gate does not apply to it.
        if (!unexecuted.isEmpty() && !"CANCELLED".equals(requested)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("unexecutedCaseIds", unexecuted);
            detail.put("unexecuted", unexecuted.size());
            throw new ApiException(409, "ROUND_HAS_UNEXECUTED", "轮次内仍有用例未执行，不能关闭", detail);
        }
        if (!"CANCELLED".equals(requested)) {
            // PASS > CONDITIONAL > FAIL: a late close may lower the conclusion, never raise it
            PlanLifecycle.requireNotUpgraded(requested, suggested);
        }
        jdbc.update("""
                UPDATE test_plan_round
                   SET status = 'CLOSED', active = NULL, end_at = NOW(3), conclusion = :conclusion,
                       closed_by = :userId, closed_at = NOW(3), note = COALESCE(:note, note),
                       exec_total = :total, exec_passed = :passed, exec_failed = :failed,
                       exec_blocked = :blocked, updated_at = NOW(3)
                 WHERE id = :roundId AND tenant_id = :tenantId
                """, new MapSqlParameterSource()
                .addValue("conclusion", requested)
                .addValue("userId", scope.userId())
                .addValue("note", cmd == null ? null : cmd.note())
                .addValue("total", total)
                .addValue("passed", passed)
                .addValue("failed", failed)
                .addValue("blocked", blocked)
                .addValue("roundId", roundId)
                .addValue("tenantId", scope.tenantId()));
        audit.record("ROUND_CLOSE", "TEST_PLAN_ROUND", roundId,
                Map.of("planId", planId, "conclusion", requested, "suggested", suggested,
                        "passed", passed, "failed", failed, "blocked", blocked));
        return detail(planId, roundId);
    }

    @Transactional(readOnly = true)
    public PlanViews.RoundDetail detail(long planId, long roundId) {
        Map<String, Object> round = requireRound(planId, roundId);
        PlanViews.RoundView view = toView(round);
        List<Map<String, Object>> scopeCases = parseScope(Rows.str(round, "case_scope_json"));
        Map<Long, Map<String, Object>> latest = latestExecutions(planId, roundId);
        List<PlanViews.RoundCaseRow> cases = new ArrayList<>(scopeCases.size());
        for (Map<String, Object> entry : scopeCases) {
            long caseId = Rows.toLong(Rows.object(entry, "caseId"), 0L);
            Map<String, Object> execution = latest.get(caseId);
            Map<String, Object> meta = caseMeta(caseId);
            cases.add(new PlanViews.RoundCaseRow(
                    caseId,
                    meta == null ? null : Rows.str(meta, "code"),
                    meta == null ? null : Rows.str(meta, "title"),
                    execution == null ? "NOT_RUN" : Rows.str(execution, "result", "NOT_RUN"),
                    execution == null ? null : dateText(Rows.object(execution, "executed_at")),
                    execution == null ? null : Rows.lng(execution, "executor_id"),
                    meta == null ? null : Rows.str(meta, "note")));
        }
        PlanViews.Summary counts = new PlanViews.Summary(
                view.caseTotal(), view.executed(), view.passed(), view.failed(), view.blocked(),
                view.caseTotal() - view.executed(), view.passRate(), view.coverage());
        return new PlanViews.RoundDetail(view, cases, counts);
    }

    // ------------------------------------------------------------------ scope resolution

    private List<Map<String, Object>> currentScope(long planId) {
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT pc.case_id AS case_id, pc.case_version AS case_version
                FROM test_plan_case pc JOIN test_plan p ON p.id = pc.plan_id
                WHERE /*scope:p*/ AND pc.plan_id = :planId
                ORDER BY pc.case_id ASC
                """, Map.of("planId", planId));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("caseId", Rows.toLong(Rows.object(row, "case_id"), 0L));
            entry.put("caseVersion", Rows.toInt(Rows.object(row, "case_version"), 1));
            out.add(entry);
        }
        return out;
    }

    private List<Map<String, Object>> previousFailedScope(long planId) {
        Map<String, Object> previous = jdbcRead.one("""
                SELECT r.id FROM test_plan_round r
                WHERE /*scope:r*/ AND r.plan_id = :planId AND r.status = 'CLOSED'
                ORDER BY r.round_no DESC LIMIT 1
                """, Map.of("planId", planId));
        if (previous == null) {
            return currentScope(planId);
        }
        long roundId = Rows.toLong(Rows.object(previous, "id"), 0L);
        List<Map<String, Object>> rows = jdbcRead.list("""
                WITH latest AS (
                    SELECT e.case_id, e.case_version, e.result,
                           ROW_NUMBER() OVER (PARTITION BY e.case_id
                                              ORDER BY e.executed_at DESC, e.id DESC) AS rn
                    FROM test_execution e
                    WHERE e.plan_id = :planId AND e.round_id = :roundId
                )
                SELECT l.case_id AS case_id, l.case_version AS case_version
                FROM latest l JOIN test_plan p ON p.id = :planId
                WHERE /*scope:p*/ AND l.rn = 1 AND l.result IN ('FAIL', 'BLOCKED')
                ORDER BY l.case_id ASC
                """, Map.of("planId", planId, "roundId", roundId));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("caseId", Rows.toLong(Rows.object(row, "case_id"), 0L));
            entry.put("caseVersion", Rows.toInt(Rows.object(row, "case_version"), 1));
            out.add(entry);
        }
        return out;
    }

    private List<Map<String, Object>> explicitScope(long planId, List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT pc.case_id AS case_id, pc.case_version AS case_version
                FROM test_plan_case pc JOIN test_plan p ON p.id = pc.plan_id
                WHERE /*scope:p*/ AND pc.plan_id = :planId
                """, Map.of("planId", planId));
        Map<Long, Integer> versions = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            versions.put(Rows.toLong(Rows.object(row, "case_id"), 0L),
                    Rows.toInt(Rows.object(row, "case_version"), 1));
        }
        List<Long> unknown = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>(caseIds.size());
        for (Long caseId : caseIds) {
            if (caseId == null || !versions.containsKey(caseId)) {
                unknown.add(caseId);
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("caseId", caseId);
            entry.put("caseVersion", versions.get(caseId));
            out.add(entry);
        }
        if (!unknown.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", "CASE_NOT_IN_PLAN");
            detail.put("caseIds", unknown);
            throw new ApiException(400, "ASSIGNMENT_MISMATCH", "作用域用例必须属于本计划", detail);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Map<Long, Map<String, Object>> latestExecutions(long planId, long roundId) {
        List<Map<String, Object>> rows = jdbcRead.list("""
                WITH latest AS (
                    SELECT e.case_id, e.case_version, e.result, e.executor_id, e.executed_at,
                           ROW_NUMBER() OVER (PARTITION BY e.case_id
                                              ORDER BY e.executed_at DESC, e.id DESC) AS rn
                    FROM test_execution e
                    WHERE e.plan_id = :planId AND e.round_id = :roundId
                )
                SELECT l.case_id AS case_id, l.result AS result, l.executor_id AS executor_id,
                       l.executed_at AS executed_at
                FROM latest l JOIN test_plan p ON p.id = :planId
                WHERE /*scope:p*/ AND l.rn = 1
                """, Map.of("planId", planId, "roundId", roundId));
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            out.put(Rows.toLong(Rows.object(row, "case_id"), 0L), row);
        }
        return out;
    }

    private Map<String, Object> caseMeta(long caseId) {
        return jdbcRead.one("""
                SELECT c.code AS code, c.title AS title, pc.note AS note
                FROM test_case c
                LEFT JOIN test_plan_case pc ON pc.case_id = c.id
                WHERE /*scope:c*/ AND c.id = :caseId
                LIMIT 1
                """, Map.of("caseId", caseId));
    }

    private PlanViews.RoundView toView(Map<String, Object> row) {
        long planId = Rows.toLong(Rows.object(row, "plan_id"), 0L);
        long roundId = Rows.toLong(Rows.object(row, "id"), 0L);
        long caseTotal = Rows.toLong(Rows.object(row, "exec_total"), 0L);
        long executed = Rows.toLong(Rows.object(row, "exec_passed"), 0L)
                + Rows.toLong(Rows.object(row, "exec_failed"), 0L)
                + Rows.toLong(Rows.object(row, "exec_blocked"), 0L);
        long passed = Rows.toLong(Rows.object(row, "exec_passed"), 0L);
        long failed = Rows.toLong(Rows.object(row, "exec_failed"), 0L);
        long blocked = Rows.toLong(Rows.object(row, "exec_blocked"), 0L);
        boolean closed = "CLOSED".equals(Rows.str(row, "status"));
        if (!closed) {
            // a running round reports live numbers, a closed one keeps its frozen snapshot
            caseTotal = parseScope(Rows.str(row, "case_scope_json")).size();
            Map<String, Object> live = jdbcRead.one("""
                    WITH latest AS (
                        SELECT e.result,
                               ROW_NUMBER() OVER (PARTITION BY e.case_id
                                                  ORDER BY e.executed_at DESC, e.id DESC) AS rn
                        FROM test_execution e
                        WHERE e.plan_id = :planId AND e.round_id = :roundId
                    )
                    SELECT COUNT(*) AS executed,
                           COALESCE(SUM(CASE WHEN l.result = 'PASS' THEN 1 ELSE 0 END), 0) AS passed,
                           COALESCE(SUM(CASE WHEN l.result = 'FAIL' THEN 1 ELSE 0 END), 0) AS failed,
                           COALESCE(SUM(CASE WHEN l.result = 'BLOCKED' THEN 1 ELSE 0 END), 0) AS blocked
                    FROM latest l JOIN test_plan p ON p.id = :planId
                    WHERE /*scope:p*/ AND l.rn = 1
                    """, Map.of("planId", planId, "roundId", roundId));
            if (live != null) {
                executed = Rows.toLong(Rows.object(live, "executed"), 0L);
                passed = Rows.toLong(Rows.object(live, "passed"), 0L);
                failed = Rows.toLong(Rows.object(live, "failed"), 0L);
                blocked = Rows.toLong(Rows.object(live, "blocked"), 0L);
            }
            if (caseTotal == 0) {
                caseTotal = parseScope(Rows.str(row, "case_scope_json")).size();
            }
        }
        return new PlanViews.RoundView(
                roundId,
                planId,
                Rows.toInt(Rows.object(row, "round_no"), 0),
                Rows.str(row, "round_type"),
                Rows.str(row, "name"),
                Rows.str(row, "status"),
                Rows.str(row, "conclusion"),
                dateText(Rows.object(row, "start_at")),
                dateText(Rows.object(row, "end_at")),
                (int) caseTotal,
                (int) executed,
                (int) passed,
                (int) failed,
                (int) blocked,
                MetricFormulas.passRate(passed, caseTotal),
                MetricFormulas.coverage(executed, caseTotal),
                Rows.toInt(Rows.object(row, "active"), 0) == 1,
                parseLongs(Rows.str(row, "executor_ids_json")),
                Rows.str(row, "note"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseScope(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> parseLongs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = objectMapper.readValue(json, List.class);
            List<Long> out = new ArrayList<>(raw.size());
            for (Object value : raw) {
                if (value instanceof Number number) {
                    out.add(number.longValue());
                }
            }
            return out;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private long maxRoundNo(long planId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT COALESCE(MAX(r.round_no), 0) AS max_no FROM test_plan_round r
                WHERE /*scope:r*/ AND r.plan_id = :planId
                """, Map.of("planId", planId));
        return row == null ? 0 : Rows.toLong(Rows.object(row, "max_no"), 0L);
    }

    private long lastInsertId(long tenantId, long planId, long roundNo) {
        Map<String, Object> row = jdbc.queryForObject("""
                SELECT id FROM test_plan_round
                WHERE tenant_id = :tenantId AND plan_id = :planId AND round_no = :roundNo
                """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("planId", planId).addValue("roundNo", roundNo),
                (rs, index) -> Map.<String, Object>of("id", rs.getLong("id")));
        return row == null ? 0 : Rows.toLong(Rows.object(row, "id"), 0L);
    }

    private Map<String, Object> lockPlan(long planId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT p.id, p.status FROM test_plan p
                WHERE /*scope:p*/ AND p.id = :planId FOR UPDATE
                """, Map.of("planId", planId));
        if (row == null) {
            throw new ApiException(404, "PLAN_NOT_FOUND", "测试计划不存在或不在你的数据范围内",
                    Map.of("planId", planId));
        }
        return row;
    }

    private void lockRound(long planId, long roundId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT r.id FROM test_plan_round r
                WHERE /*scope:r*/ AND r.plan_id = :planId AND r.id = :roundId FOR UPDATE
                """, Map.of("planId", planId, "roundId", roundId));
        if (row == null) {
            throw new ApiException(404, "ROUND_NOT_FOUND", "轮次不存在或不属于该计划",
                    Map.of("planId", planId, "roundId", roundId));
        }
    }

    private Map<String, Object> requireRound(long planId, long roundId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT r.*
                FROM test_plan_round r
                WHERE /*scope:r*/ AND r.plan_id = :planId AND r.id = :roundId
                """, Map.of("planId", planId, "roundId", roundId));
        if (row == null) {
            throw new ApiException(404, "ROUND_NOT_FOUND", "轮次不存在或不属于该计划",
                    Map.of("planId", planId, "roundId", roundId));
        }
        return row;
    }

    private void requirePlanRow(long planId) {
        long found = count("""
                SELECT COUNT(*) FROM test_plan p WHERE /*scope:p*/ AND p.id = :planId
                """, Map.of("planId", planId));
        if (found == 0) {
            throw new ApiException(404, "PLAN_NOT_FOUND", "测试计划不存在或不在你的数据范围内",
                    Map.of("planId", planId));
        }
    }

    private long count(String sql, Map<String, Object> params) {
        return jdbcRead.count(sql, params);
    }

    private String jsonOf(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(400, "VALIDATION_FAILED", "JSON 不合法",
                    Map.of("error", ex.getOriginalMessage()));
        }
    }

    private static String dateText(Object value) {
        LocalDateTime dateTime = Rows.toDateTime(value);
        return dateTime == null ? null : dateTime.toString();
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
