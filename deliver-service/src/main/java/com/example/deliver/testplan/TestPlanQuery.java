package com.example.deliver.testplan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.deliver.quality.aggregate.PlanAggregate;
import com.example.deliver.quality.aggregate.QualityAggregator;
import com.example.deliver.support.Rows;
import com.example.deliver.testcase.TestCaseViews;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.contract.MetricFormulas;
import org.springframework.stereotype.Component;

/**
 * Read side of plans: list, plan cases and the summary card.
 *
 * <p>The summary reads counts only ({@code SUM(result = 'PASS')} …) and hands them to
 * {@link MetricFormulas}. Keeping arithmetic out of SQL is what makes the card, the gate and the
 * release admission impossible to disagree: there is one formula, used with different counts.</p>
 */
@Component
public class TestPlanQuery {

    private static final String PLAN_SELECT = """
            SELECT p.id AS id, p.name AS name, p.release_id AS release_id, p.status AS status,
                   p.gate_mode AS gate_mode, p.window_start AS window_start, p.window_end AS window_end,
                   u.name AS owner_name, COALESCE(rel.gate_mode, 'STRICT') AS release_gate_mode,
                   (SELECT COUNT(*) FROM test_plan_case pc WHERE pc.plan_id = p.id) AS planned,
                   (SELECT COUNT(*) FROM test_plan_case pc
                     WHERE pc.plan_id = p.id AND pc.result <> 'NOT_RUN') AS executed
            FROM test_plan p
            LEFT JOIN `release` rel ON rel.id = p.release_id AND rel.tenant_id = p.tenant_id
            LEFT JOIN sys_user u ON u.id = p.owner_id AND u.tenant_id = p.tenant_id
            WHERE /*scope:p*/
            """;

    private final ScopedJdbc jdbc;
    private final QualityAggregator aggregator;

    public TestPlanQuery(ScopedJdbc jdbc, QualityAggregator aggregator) {
        this.jdbc = jdbc;
        this.aggregator = aggregator;
    }

    public PageResponse<TestPlanViews.PlanHeader> page(Long releaseId, String status, String keyword,
                                                       int page, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder(PLAN_SELECT);
        if (releaseId != null) {
            sql.append(" AND p.release_id = :releaseId");
            params.put("releaseId", releaseId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND p.status = :status");
            params.put("status", status.trim().toUpperCase(Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND p.name LIKE :keyword");
            params.put("keyword", "%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY p.id DESC");
        PageResponse<Map<String, Object>> rows = jdbc.page(sql.toString(), params, page, size);
        List<TestPlanViews.PlanHeader> items = new ArrayList<>();
        for (Map<String, Object> row : rows.items()) {
            items.add(map(row));
        }
        return new PageResponse<>(items, rows.total(), rows.page(), rows.size());
    }

    public TestPlanViews.PlanHeader header(long planId) {
        List<Map<String, Object>> rows = jdbc.list(PLAN_SELECT + " AND p.id = :planId",
                Map.of("planId", planId));
        return rows.isEmpty() ? null : map(rows.get(0));
    }

    public List<TestCaseViews.PlanCaseRow> cases(long planId) {
        String sql = """
                SELECT pc.case_id AS case_id, pc.case_version AS case_version, pc.result AS result,
                       pc.last_executed_at AS last_executed_at, pc.note AS note,
                       c.code AS case_code, c.title AS title
                FROM test_plan_case pc
                JOIN test_case c ON c.id = pc.case_id AND c.tenant_id = pc.tenant_id
                WHERE /*scope:c*/ AND pc.plan_id = :planId
                ORDER BY pc.id ASC
                """;
        List<TestCaseViews.PlanCaseRow> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.list(sql, Map.of("planId", planId))) {
            out.add(new TestCaseViews.PlanCaseRow(
                    Rows.toLong(Rows.object(row, "case_id"), 0L),
                    Rows.str(row, "case_code"),
                    Rows.toInt(Rows.object(row, "case_version"), 1),
                    Rows.str(row, "title"),
                    Rows.str(row, "result", "NOT_RUN"),
                    Rows.toDateTime(Rows.object(row, "last_executed_at")) == null ? null
                            : Rows.toDateTime(Rows.object(row, "last_executed_at")).toString(),
                    Rows.str(row, "note")));
        }
        return out;
    }

    public TestPlanViews.PlanSummary summary(long planId) {
        // 计划进度、报告、门禁同源：计数只允许经 QualityAggregator，禁止在本类重写一遍 SUM。
        PlanAggregate p = aggregator.aggregatePlan(planId);
        long planned = p.total();
        long executed = p.executed();
        long passed = p.passed();
        long failed = p.failed();
        long blocked = p.blocked();
        long skip = p.skipped();
        long notRun = p.notRun();
        return new TestPlanViews.PlanSummary(planned, executed, passed, failed, blocked, skip, notRun,
                MetricFormulas.passRate(passed, planned),
                MetricFormulas.executedPassRate(passed, executed),
                MetricFormulas.coverage(executed, planned),
                notRun == 0 && planned > 0);
    }

    private TestPlanViews.PlanHeader map(Map<String, Object> row) {
        long id = Rows.toLong(Rows.object(row, "id"), 0L);
        String gateMode = Rows.str(row, "gate_mode");
        return new TestPlanViews.PlanHeader(
                id,
                "TP-%04d".formatted(id),
                Rows.str(row, "name"),
                Rows.toLong(Rows.object(row, "release_id")),
                Rows.str(row, "status"),
                gateMode == null || gateMode.isBlank() ? Rows.str(row, "release_gate_mode", "STRICT") : gateMode,
                text(Rows.object(row, "window_start")),
                text(Rows.object(row, "window_end")),
                Rows.str(row, "owner_name"),
                Rows.toLong(Rows.object(row, "planned"), 0L),
                Rows.toLong(Rows.object(row, "executed"), 0L));
    }

    private static String text(Object value) {
        java.time.LocalDateTime dateTime = Rows.toDateTime(value);
        return dateTime == null ? null : dateTime.toString();
    }

    static BigDecimal rate(long numerator, long denominator) {
        return MetricFormulas.passRate(numerator, denominator);
    }
}
