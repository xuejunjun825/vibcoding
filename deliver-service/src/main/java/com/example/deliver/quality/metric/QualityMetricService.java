package com.example.deliver.quality.metric;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.reqread.ReleaseWindowReadModel;
import com.example.deliver.support.Rows;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.MetricFormulas;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The five quality metrics (AC-8.4/8.5), one implementation for board, release page and report.
 *
 * <p>Two rules keep the numbers identical everywhere:</p>
 * <ul>
 *   <li><b>No local division.</b> Every rate goes through {@link MetricFormulas}; a zero
 *       denominator yields {@code null}, which the UI renders as "—" rather than a fake 0%.</li>
 *   <li><b>Same window as defect escape.</b> "Post-release 72h defects" uses the same
 *       {@link ReleaseWindowReadModel} window (first successful deploy's {@code ended_at} plus the
 *       release's {@code observation_hours}) that flags a defect as escaped, so the dashboard and
 *       the defect record cannot disagree about the same defect.</li>
 * </ul>
 */
@Service
public class QualityMetricService {

    /** Query filter; every field is optional, an absent release means "aggregate the scope". */
    public record Query(Long releaseId, Long domainId, Long vendorId, LocalDate from, LocalDate to) {
    }

    public record MetricValue(String code,
                              String name,
                              BigDecimal value,
                              String unit,
                              Integer version,
                              String formula,
                              String caliberNote,
                              String drillQuery) {
    }

    public record QualityMetrics(List<MetricValue> metrics,
                                 BigDecimal fpy,
                                 BigDecimal regressionPassRate,
                                 BigDecimal escapeRate,
                                 Long postReleaseDefects72h,
                                 BigDecimal rollbackRate) {
    }

    private record Definition(String name, String formula, String caliberNote, int version) {
    }

    private static final Map<String, Definition> DEFAULT_DEFINITIONS = Map.of(
            MetricFormulas.FPY, new Definition("首轮通过率", "R1 passed / R1 executed",
                    "第一轮已执行用例中通过的比例（按计划用例加权）", 1),
            MetricFormulas.REGRESSION_PASS_RATE, new Definition("回归通过率", "R2+ passed / R2+ executed",
                    "第二轮及以后回归轮次中通过的比例", 1),
            MetricFormulas.DEFECT_ESCAPE_RATE, new Definition("缺陷逃逸率", "escaped / release defects",
                    "逃逸缺陷数 / 该版本关联缺陷总数", 1),
            MetricFormulas.POST_RELEASE_DEFECTS_72H, new Definition("发布后观察窗缺陷数", "defects in window",
                    "发布成功时刻起 observation_hours（默认 72h）内创建的关联缺陷数", 1),
            MetricFormulas.ROLLBACK_RATE, new Definition("回滚率", "rolled back / executed orders",
                    "回滚发布单 / 已执行发布单", 1));

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final ReleaseWindowReadModel windows;
    private final ObjectMapper json;

    public QualityMetricService(ScopedJdbc jdbcRead,
                                NamedParameterJdbcTemplate jdbc,
                                ReleaseWindowReadModel windows,
                                ObjectMapper qualityObjectMapper) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.windows = windows;
        this.json = qualityObjectMapper;
    }

    @Transactional(readOnly = true)
    public QualityMetrics quality(Query query) {
        Query q = query == null ? new Query(null, null, null, null, null) : query;
        Map<String, Definition> definitions = definitions();

        long[] firstRound = roundCounts(q, 1, 1);
        long[] regression = roundCounts(q, 2, Integer.MAX_VALUE);
        long[] defects = defectCounts(q);
        long postRelease = postReleaseDefects(q);
        long[] orders = orderCounts(q);

        BigDecimal fpy = MetricFormulas.fpy(firstRound[0], firstRound[1]);
        BigDecimal regressionRate = MetricFormulas.regressionPassRate(regression[0], regression[1]);
        BigDecimal escapeRate = MetricFormulas.escapeRate(defects[0], defects[1]);
        BigDecimal rollbackRate = MetricFormulas.rollbackRate(orders[0], orders[1]);

        List<MetricValue> metrics = new ArrayList<>(5);
        metrics.add(value(MetricFormulas.FPY, definitions, fpy, "%", drill(q, "/test-plans", "round=1")));
        metrics.add(value(MetricFormulas.REGRESSION_PASS_RATE, definitions, regressionRate, "%",
                drill(q, "/test-plans", "round=2")));
        metrics.add(value(MetricFormulas.DEFECT_ESCAPE_RATE, definitions, escapeRate, "%",
                drill(q, "/defects", "isEscape=1")));
        metrics.add(value(MetricFormulas.POST_RELEASE_DEFECTS_72H, definitions,
                BigDecimal.valueOf(postRelease), "个", drill(q, "/defects", "window=post-release")));
        metrics.add(value(MetricFormulas.ROLLBACK_RATE, definitions, rollbackRate, "%",
                drill(q, "/deploy-orders", "result=ROLLED_BACK")));

        return new QualityMetrics(metrics, fpy, regressionRate, escapeRate, postRelease, rollbackRate);
    }

    // ------------------------------------------------------------------ counts

    /** {@code [passed, executed]} for one round band (1 = first round, 2… = regression). */
    private long[] roundCounts(Query q, int minRound, int maxRound) {
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(SUM(r.exec_passed), 0) AS passed,
                       COALESCE(SUM(r.exec_passed + r.exec_failed + r.exec_blocked), 0) AS exec_cnt
                FROM test_plan_round r
                JOIN test_plan p ON p.id = r.plan_id
                WHERE /*scope:p*/ AND r.round_no >= :minRound AND r.round_no <= :maxRound
                  AND r.status <> 'CANCELLED'
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("minRound", minRound);
        params.put("maxRound", maxRound);
        if (q.releaseId() != null) {
            sql.append(" AND p.release_id = :releaseId");
            params.put("releaseId", q.releaseId());
        }
        if (q.domainId() != null) {
            sql.append(" AND p.domain_id = :domainId");
            params.put("domainId", q.domainId());
        }
        if (q.vendorId() != null) {
            sql.append(" AND p.vendor_id = :vendorId");
            params.put("vendorId", q.vendorId());
        }
        if (q.from() != null) {
            sql.append(" AND r.start_at >= :from");
            params.put("from", q.from().atStartOfDay());
        }
        if (q.to() != null) {
            sql.append(" AND r.start_at < :to");
            params.put("to", q.to().plusDays(1).atStartOfDay());
        }
        Map<String, Object> row = jdbcRead.one(sql.toString(), params);
        return new long[]{Rows.toLong(Rows.object(row, "passed"), 0L),
                Rows.toLong(Rows.object(row, "exec_cnt"), 0L)};
    }

    /** {@code [escaped, total]} for defects in scope. */
    private long[] defectCounts(Query q) {
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(SUM(CASE WHEN d.is_escape = 1 THEN 1 ELSE 0 END), 0) AS escape_cnt,
                       COUNT(*) AS total
                FROM defect d
                WHERE /*scope:d*/
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (q.releaseId() != null) {
            sql.append(" AND d.release_id = :releaseId");
            params.put("releaseId", q.releaseId());
        }
        if (q.domainId() != null) {
            sql.append(" AND d.domain_id = :domainId");
            params.put("domainId", q.domainId());
        }
        if (q.vendorId() != null) {
            sql.append(" AND d.vendor_id = :vendorId");
            params.put("vendorId", q.vendorId());
        }
        if (q.from() != null) {
            sql.append(" AND d.found_at >= :from");
            params.put("from", q.from().atStartOfDay());
        }
        if (q.to() != null) {
            sql.append(" AND d.found_at < :to");
            params.put("to", q.to().plusDays(1).atStartOfDay());
        }
        Map<String, Object> row = jdbcRead.one(sql.toString(), params);
        return new long[]{Rows.toLong(Rows.object(row, "escape_cnt"), 0L),
                Rows.toLong(Rows.object(row, "total"), 0L)};
    }

    /** Defects created inside the release's observation window (single release) or any window (board). */
    private long postReleaseDefects(Query q) {
        if (q.releaseId() != null) {
            ReleaseWindowReadModel.Window window = windows.window(q.releaseId());
            if (!window.hasSuccessRelease()) {
                return 0L;
            }
            LocalDateTime start = LocalDateTime.ofInstant(window.successEndedAt(),
                    java.time.ZoneId.systemDefault());
            LocalDateTime end = start.plusHours(window.observationHours());
            StringBuilder sql = new StringBuilder("""
                    SELECT COUNT(*) AS total
                    FROM defect d
                    WHERE /*scope:d*/ AND d.release_id = :releaseId
                      AND d.found_at >= :windowStart AND d.found_at <= :windowEnd
                    """);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("releaseId", q.releaseId());
            params.put("windowStart", start);
            params.put("windowEnd", end);
            if (q.domainId() != null) {
                sql.append(" AND d.domain_id = :domainId");
                params.put("domainId", q.domainId());
            }
            if (q.vendorId() != null) {
                sql.append(" AND d.vendor_id = :vendorId");
                params.put("vendorId", q.vendorId());
            }
            return jdbcRead.count(sql.toString(), params);
        }
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) AS total
                FROM defect d
                JOIN `release` rel ON rel.id = d.release_id
                WHERE /*scope:d*/ AND EXISTS (
                        SELECT 1 FROM deploy_order o
                        WHERE o.release_id = d.release_id
                          AND o.execution_result = 'SUCCESS'
                          AND o.ended_at IS NOT NULL
                          AND d.found_at >= o.ended_at
                          AND d.found_at <= DATE_ADD(o.ended_at, INTERVAL rel.observation_hours HOUR))
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (q.domainId() != null) {
            sql.append(" AND d.domain_id = :domainId");
            params.put("domainId", q.domainId());
        }
        if (q.vendorId() != null) {
            sql.append(" AND d.vendor_id = :vendorId");
            params.put("vendorId", q.vendorId());
        }
        if (q.from() != null) {
            sql.append(" AND d.found_at >= :from");
            params.put("from", q.from().atStartOfDay());
        }
        if (q.to() != null) {
            sql.append(" AND d.found_at < :to");
            params.put("to", q.to().plusDays(1).atStartOfDay());
        }
        return jdbcRead.count(sql.toString(), params);
    }

    /** {@code [rolledBack, executed]} deploy orders in scope (terminal results only). */
    private long[] orderCounts(Query q) {
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(SUM(CASE WHEN o.execution_result = 'ROLLED_BACK' THEN 1 ELSE 0 END), 0) AS rolled,
                       COUNT(*) AS exec_cnt
                FROM deploy_order o
                JOIN `release` rel ON rel.id = o.release_id
                WHERE /*scope:rel*/ AND o.execution_result IN ('SUCCESS', 'FAILED', 'ROLLED_BACK')
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (q.releaseId() != null) {
            sql.append(" AND o.release_id = :releaseId");
            params.put("releaseId", q.releaseId());
        }
        if (q.domainId() != null) {
            sql.append(" AND rel.domain_id = :domainId");
            params.put("domainId", q.domainId());
        }
        if (q.from() != null) {
            sql.append(" AND o.ended_at >= :from");
            params.put("from", q.from().atStartOfDay());
        }
        if (q.to() != null) {
            sql.append(" AND o.ended_at < :to");
            params.put("to", q.to().plusDays(1).atStartOfDay());
        }
        Map<String, Object> row = jdbcRead.one(sql.toString(), params);
        return new long[]{Rows.toLong(Rows.object(row, "rolled"), 0L),
                Rows.toLong(Rows.object(row, "exec_cnt"), 0L)};
    }

    // ------------------------------------------------------------------ definitions

    /** Latest definition row per code from {@code metric_definition}, falling back to the built-ins. */
    private Map<String, Definition> definitions() {
        Map<String, Definition> definitions = new LinkedHashMap<>(DEFAULT_DEFINITIONS);
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT code, name, formula, caliber_note, version
                    FROM metric_definition
                    WHERE tenant_id = :tenantId AND topic = 'quality'
                    ORDER BY code ASC, version DESC
                    """, Map.of("tenantId", TenantContext.require().tenantId()));
            for (Map<String, Object> row : rows) {
                String code = Rows.str(row, "code");
                if (code == null || !DEFAULT_DEFINITIONS.containsKey(code)) {
                    continue;
                }
                // ORDER BY version DESC: the first row per code is the effective definition.
                definitions.put(code, new Definition(
                        Rows.str(row, "name", DEFAULT_DEFINITIONS.get(code).name()),
                        Rows.str(row, "formula", DEFAULT_DEFINITIONS.get(code).formula()),
                        Rows.str(row, "caliber_note", DEFAULT_DEFINITIONS.get(code).caliberNote()),
                        Rows.toInt(Rows.object(row, "version"), 1)));
            }
        } catch (Exception ex) {
            // Metric labels are decoration; a metadata read failure must not blank the numbers.
            return definitions;
        }
        return definitions;
    }

    private static MetricValue value(String code, Map<String, Definition> definitions, BigDecimal value,
                                     String unit, String drillQuery) {
        Definition definition = definitions.getOrDefault(code, DEFAULT_DEFINITIONS.get(code));
        return new MetricValue(code, definition.name(), value, unit, definition.version(),
                definition.formula(), definition.caliberNote(), drillQuery);
    }

    private static String drill(Query q, String path, String extra) {
        StringBuilder text = new StringBuilder(path).append('?').append(extra);
        if (q.releaseId() != null) {
            text.append("&releaseId=").append(q.releaseId());
        }
        if (q.domainId() != null) {
            text.append("&domainId=").append(q.domainId());
        }
        if (q.vendorId() != null) {
            text.append("&vendorId=").append(q.vendorId());
        }
        if (q.from() != null) {
            text.append("&from=").append(q.from());
        }
        if (q.to() != null) {
            text.append("&to=").append(q.to());
        }
        return text.toString();
    }

    /** Kept for callers that only need the ratio form of the metric JSON (internal summary). */
    static String toJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
