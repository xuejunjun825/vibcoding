package com.example.deliver.quality.aggregate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.contract.MetricFormulas;
import org.springframework.stereotype.Component;

/**
 * The only place that turns plan cases into plan/release numbers.
 *
 * <p>Report, gate, board and plan detail all call this component. The pass rate is
 * {@code passed / planned} (NOT_RUN counts against it) and coverage is {@code executed /
 * planned}; both use {@link MetricFormulas}, so a second division elsewhere is a defect, not a
 * style choice.</p>
 */
@Component
public class QualityAggregator {

    private static final String PLAN_ROLLUP = """
            SELECT p.id AS plan_id, p.name AS name, p.status AS status,
                   COALESCE(p.revision, 0) AS revision,
                   COUNT(pc.id) AS total,
                   COALESCE(SUM(CASE WHEN pc.result <> 'NOT_RUN' THEN 1 ELSE 0 END), 0) AS executed,
                   COALESCE(SUM(CASE WHEN pc.result = 'PASS' THEN 1 ELSE 0 END), 0) AS passed,
                   COALESCE(SUM(CASE WHEN pc.result = 'FAIL' THEN 1 ELSE 0 END), 0) AS failed,
                   COALESCE(SUM(CASE WHEN pc.result = 'BLOCKED' THEN 1 ELSE 0 END), 0) AS blocked,
                   COALESCE(SUM(CASE WHEN pc.result = 'NOT_RUN' THEN 1 ELSE 0 END), 0) AS not_run
            FROM test_plan p
            LEFT JOIN test_plan_case pc ON pc.plan_id = p.id
            WHERE /*scope:p*/ AND %s
            GROUP BY p.id, p.name, p.status, p.revision
            ORDER BY p.id ASC
            """;

    private final ScopedJdbc jdbc;

    public QualityAggregator(ScopedJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** Release rollup: sum of its plans, computed once from plan cases. */
    public QualityAggregate aggregateRelease(long releaseId) {
        List<PlanAggregate> plans = read("p.release_id = :releaseId", Map.of("releaseId", releaseId));
        long total = 0;
        long executed = 0;
        long passed = 0;
        long failed = 0;
        long blocked = 0;
        long notRun = 0;
        Map<Long, Integer> revisions = new LinkedHashMap<>();
        for (PlanAggregate plan : plans) {
            total += plan.total();
            executed += plan.executed();
            passed += plan.passed();
            failed += plan.failed();
            blocked += plan.blocked();
            notRun += plan.total() - plan.executed();
            revisions.put(plan.planId(), plan.revision());
        }
        return new QualityAggregate(releaseId, total, executed, passed, failed, blocked, notRun,
                MetricFormulas.passRate(passed, total),
                MetricFormulas.coverage(executed, total), plans, revisions);
    }

    /** One plan's rollup (empty plan returns all zeroes, not null). */
    public PlanAggregate aggregatePlan(long planId) {
        List<PlanAggregate> plans = read("p.id = :planId", Map.of("planId", planId));
        if (plans.isEmpty()) {
            return new PlanAggregate(planId, null, null, 0, 0, 0, 0, 0, 0,
                    MetricFormulas.passRate(0, 0), MetricFormulas.coverage(0, 0));
        }
        return plans.get(0);
    }

    private List<PlanAggregate> read(String predicate, Map<String, Object> params) {
        List<Map<String, Object>> rows = jdbc.list(PLAN_ROLLUP.formatted(predicate), params);
        List<PlanAggregate> plans = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            long total = Rows.toLong(Rows.object(row, "total"), 0L);
            long executed = Rows.toLong(Rows.object(row, "executed"), 0L);
            long passed = Rows.toLong(Rows.object(row, "passed"), 0L);
            long failed = Rows.toLong(Rows.object(row, "failed"), 0L);
            long blocked = Rows.toLong(Rows.object(row, "blocked"), 0L);
            plans.add(new PlanAggregate(
                    Rows.toLong(Rows.object(row, "plan_id"), 0L),
                    Rows.str(row, "name"),
                    Rows.str(row, "status"),
                    Rows.toInt(Rows.object(row, "revision"), 0),
                    total, executed, passed, failed, blocked,
                    MetricFormulas.passRate(passed, total),
                    MetricFormulas.coverage(executed, total)));
        }
        return plans;
    }

    /** Convenience: release pass rate as a percentage with two decimals (95.00, not 0.95). */
    public BigDecimal passRatePercent(QualityAggregate aggregate) {
        return aggregate.passRate().multiply(BigDecimal.valueOf(100));
    }
}
