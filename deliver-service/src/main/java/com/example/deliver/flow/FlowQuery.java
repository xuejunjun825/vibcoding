package com.example.deliver.flow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.scope.ScopedJdbc;
import org.springframework.stereotype.Component;

/**
 * The only place the flow reads {@code audit_log} (AC-9).
 *
 * <p>Dwell time is <em>derived</em>, never stored: each requirement's audit trail is cut into
 * intervals at every state change, an interval belongs to the stage its entry event maps to, and
 * the interval ends when the next event arrives — whatever that next event is. That is why a
 * stage's "leave statuses" need no special case: {@code POOLED} both ends 评审 and starts 排期
 * because the event itself closes the previous interval.</p>
 *
 * <p>Requirements with no audit trail are not "0 days", they are <b>unknown</b>: they never enter
 * an interval, never count as WIP, never join an average, and are reported once as
 * {@code noDataCount} so the page can say 「排除 N 条无审计」.</p>
 */
@Component
public class FlowQuery {

    private final ScopedJdbc jdbc;
    private final FlowStageService stages;

    public FlowQuery(ScopedJdbc jdbc, FlowStageService stages) {
        this.jdbc = jdbc;
        this.stages = stages;
    }

    // ------------------------------------------------------------------ window

    /** Fills the default 90-day window and rejects an inverted one. */
    public FlowViews.Query window(FlowViews.Query q) {
        LocalDate to = q.to() != null ? q.to() : LocalDate.now();
        LocalDate from = q.from() != null ? q.from()
                : to.minusDays(FlowViews.DEFAULT_WINDOW_DAYS - 1L);
        if (from.isAfter(to)) {
            throw new ApiException(400, "VALIDATION_FAILED", "from 不能晚于 to",
                    Map.of("from", from.toString(), "to", to.toString()));
        }
        return new FlowViews.Query(q.domainId(), q.vendorId(), from, to, q.reqType());
    }

    /** Per-stage WIP: requirements whose latest mapped interval is still open. */
    public Map<String, Long> wip(FlowViews.Query query) {
        FlowViews.Query q = window(query);
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = withClause(q, stages.enabled(), params, null)
                + """
                SELECT stage AS code, COUNT(*) AS wip
                FROM iv
                WHERE stage IS NOT NULL AND left_at IS NULL
                GROUP BY stage
                """;
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.list(sql, params)) {
            out.put(Rows.str(row, "code", ""), Rows.toLong(Rows.object(row, "wip"), 0L));
        }
        return out;
    }

    /** Dwell seconds per stage, for intervals that <b>ended inside the window</b>. */
    public Map<String, List<Long>> dwellSamples(FlowViews.Query query) {
        FlowViews.Query q = window(query);
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = withClause(q, stages.enabled(), params, null)
                + """
                SELECT stage AS code, TIMESTAMPDIFF(SECOND, entered_at, left_at) AS sec
                FROM iv
                WHERE stage IS NOT NULL AND left_at IS NOT NULL
                  AND left_at >= :fromTs AND left_at < :toTs
                """;
        params.put("fromTs", q.from().atStartOfDay());
        params.put("toTs", q.to().plusDays(1).atStartOfDay());
        Map<String, List<Long>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.list(sql, params)) {
            out.computeIfAbsent(Rows.str(row, "code", ""), key -> new ArrayList<>())
                    .add(Rows.toLong(Rows.object(row, "sec"), 0L));
        }
        return out;
    }

    /** Stage aggregates; the one implementation of avg/P90 is {@link FlowMetricFormulas}. */
    public List<FlowViews.StageAgg> stages(FlowViews.Query query) {
        FlowViews.Query q = window(query);
        Map<String, Long> wip = wip(q);
        Map<String, List<Long>> samples = dwellSamples(q);
        List<FlowViews.StageAgg> out = new ArrayList<>();
        for (FlowViews.StageView stage : stages.enabled()) {
            List<Long> seconds = samples.getOrDefault(stage.code(), List.of());
            out.add(new FlowViews.StageAgg(stage.code(),
                    wip.getOrDefault(stage.code(), 0L),
                    seconds.size(),
                    FlowMetricFormulas.avgDwell(seconds),
                    FlowMetricFormulas.p90Dwell(seconds)));
        }
        return out;
    }

    /** Visible requirements in scope that carry no audit trail at all. */
    public long noDataCount(FlowViews.Query query) {
        FlowViews.Query q = window(query);
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = withClause(q, stages.enabled(), params, null)
                + """
                SELECT COUNT(*) AS total
                FROM sel
                WHERE sel.req_id NOT IN (SELECT req_id FROM ev)
                """;
        params.put("fromTs", q.from().atStartOfDay());
        params.put("toTs", q.to().plusDays(1).atStartOfDay());
        return jdbc.count(sql, params);
    }

    /**
     * Flow efficiency = (development + testing dwell) / (first event → delivery), for requirements
     * delivered inside the window. A missing development or testing interval excludes the
     * requirement instead of counting it as 0% (that would drag the KPI down with a data gap).
     */
    public FlowViews.Efficiency efficiency(FlowViews.Query query) {
        FlowViews.Query q = window(query);
        List<FlowViews.StageView> configured = stages.enabled();
        String dev = FlowStageService.codeForRole(configured, "DEV");
        String test = FlowStageService.codeForRole(configured, "TEST");
        String delivery = FlowStageService.codeForRole(configured, "DELIVERY");
        if (dev == null || test == null || delivery == null) {
            return new FlowViews.Efficiency(null, 0, 0);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("devCode", dev);
        params.put("testCode", test);
        params.put("deliveryCode", delivery);
        String sql = withClause(q, configured, params, null)
                + """
                , per_req AS (
                    SELECT req_id,
                           MIN(entered_at) AS first_at,
                           MIN(CASE WHEN stage = :deliveryCode THEN entered_at END) AS delivered_at,
                           SUM(CASE WHEN stage = :devCode AND left_at IS NOT NULL
                                    THEN 1 ELSE 0 END) AS dev_n,
                           SUM(CASE WHEN stage = :testCode AND left_at IS NOT NULL
                                    THEN 1 ELSE 0 END) AS test_n,
                           SUM(CASE WHEN stage IN (:devCode, :testCode) AND left_at IS NOT NULL
                                    THEN TIMESTAMPDIFF(SECOND, entered_at, left_at)
                                    ELSE 0 END) AS active_sec
                    FROM iv
                    GROUP BY req_id
                )
                SELECT COUNT(*) AS delivered,
                       SUM(CASE WHEN dev_n > 0 AND test_n > 0 AND delivered_at > first_at
                                THEN 1 ELSE 0 END) AS included,
                       SUM(CASE WHEN dev_n > 0 AND test_n > 0 AND delivered_at > first_at
                                THEN TIMESTAMPDIFF(SECOND, first_at, delivered_at)
                                ELSE 0 END) AS total_sec,
                       SUM(CASE WHEN dev_n > 0 AND test_n > 0 AND delivered_at > first_at
                                THEN active_sec ELSE 0 END) AS active_sec
                FROM per_req
                WHERE delivered_at IS NOT NULL
                  AND delivered_at >= :fromTs AND delivered_at < :toTs
                """;
        params.put("fromTs", q.from().atStartOfDay());
        params.put("toTs", q.to().plusDays(1).atStartOfDay());
        Map<String, Object> row = jdbc.one(sql, params);
        long delivered = row == null ? 0 : Rows.toLong(Rows.object(row, "delivered"), 0L);
        long included = row == null ? 0 : Rows.toLong(Rows.object(row, "included"), 0L);
        long totalSeconds = row == null ? 0 : Rows.toLong(Rows.object(row, "total_sec"), 0L);
        long activeSeconds = row == null ? 0 : Rows.toLong(Rows.object(row, "active_sec"), 0L);
        Double value = FlowMetricFormulas.efficiency(activeSeconds, totalSeconds);
        long excluded = delivered - (value == null ? 0 : included);
        return new FlowViews.Efficiency(value, value == null ? 0 : included, excluded);
    }

    // ------------------------------------------------------------------ drill and timeline

    /** Requirements that sat in one stage during the window (drill-down from a column). */
    public PageResponse<FlowViews.DrillRow> drill(FlowViews.DrillQuery query) {
        if (query == null || query.stageCode() == null || query.stageCode().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "stageCode 必填", null);
        }
        FlowViews.Query q = window(new FlowViews.Query(query.domainId(), query.vendorId(),
                query.from(), query.to(), query.reqType()));
        int page = Math.max(1, query.page());
        int size = Math.min(200, Math.max(1, query.size()));

        Map<String, Object> base = new LinkedHashMap<>();
        String prefix = withClause(q, stages.enabled(), base, null);
        String hit = """
                , hit AS (
                    SELECT req_id, entered_at, left_at,
                           ROW_NUMBER() OVER (PARTITION BY req_id ORDER BY entered_at DESC) AS rn
                    FROM iv
                    WHERE stage = :stageCode
                      AND entered_at < :toTs
                      AND (left_at IS NULL OR left_at >= :fromTs)
                )
                """;
        base.put("stageCode", query.stageCode());
        base.put("fromTs", q.from().atStartOfDay());
        base.put("toTs", q.to().plusDays(1).atStartOfDay());

        long total = jdbc.count(prefix + hit + "SELECT COUNT(*) AS total FROM hit WHERE rn = 1", base);

        Map<String, Object> params = new LinkedHashMap<>(base);
        params.put("limit", size);
        params.put("offset", (long) (page - 1) * size);
        String rowsSql = prefix + hit
                + """
                SELECT r.id AS id, r.code AS code, r.title AS title, r.type AS type,
                       r.status AS status, r.owner_user_id AS owner_id,
                       h.entered_at AS entered_at,
                       ROUND(TIMESTAMPDIFF(SECOND, h.entered_at, h.left_at) / 86400, 1) AS dwell_days
                FROM hit h
                JOIN requirement r ON r.id = h.req_id
                WHERE h.rn = 1
                  AND /*scope:r*/
                ORDER BY h.entered_at DESC
                LIMIT :limit OFFSET :offset
                """;
        List<FlowViews.DrillRow> items = new ArrayList<>();
        for (Map<String, Object> row : jdbc.list(rowsSql, params)) {
            Object dwell = Rows.object(row, "dwell_days");
            items.add(new FlowViews.DrillRow(
                    Rows.toLong(Rows.object(row, "id"), 0L),
                    Rows.str(row, "code", ""),
                    Rows.str(row, "title", ""),
                    Rows.str(row, "type"),
                    Rows.str(row, "status"),
                    Rows.object(row, "owner_id") == null ? null
                            : Rows.toLong(Rows.object(row, "owner_id"), 0L),
                    Rows.toDateTime(Rows.object(row, "entered_at")),
                    dwell == null ? null : Rows.toDecimal(dwell).doubleValue()));
        }
        return new PageResponse<>(items, total, page, size);
    }

    /** One requirement's stage timeline; {@code noData} when there is no audit trail at all. */
    public List<FlowViews.TimelineRow> timeline(long requirementId, LocalDate from, LocalDate to) {
        Map<String, Object> visible = jdbc.one(
                "SELECT r.id AS id FROM requirement r WHERE r.id = :reqId AND /*scope:r*/",
                Map.of("reqId", requirementId));
        if (visible == null) {
            throw new ApiException(404, "REQUIREMENT_NOT_FOUND", "需求不存在或无权查看: " + requirementId,
                    Map.of("requirementId", requirementId));
        }
        FlowViews.Query q = window(new FlowViews.Query(null, null, from, to, null));
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = withClause(q, stages.enabled(), params, requirementId)
                + """
                SELECT stage AS code, entered_at, left_at, source_event,
                       ROUND(TIMESTAMPDIFF(SECOND, entered_at, left_at) / 86400, 1) AS dwell_days
                FROM iv
                WHERE stage IS NOT NULL
                  AND entered_at < :toTs
                  AND (left_at IS NULL OR left_at >= :fromTs)
                ORDER BY entered_at, stage
                """;
        params.put("fromTs", q.from().atStartOfDay());
        params.put("toTs", q.to().plusDays(1).atStartOfDay());
        List<Map<String, Object>> rows = jdbc.list(sql, params);
        List<FlowViews.TimelineRow> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object dwell = Rows.object(row, "dwell_days");
            out.add(new FlowViews.TimelineRow(
                    Rows.str(row, "code"),
                    Rows.toDateTime(Rows.object(row, "entered_at")),
                    Rows.toDateTime(Rows.object(row, "left_at")),
                    dwell == null ? null : Rows.toDecimal(dwell).doubleValue(),
                    Rows.str(row, "source_event"),
                    false));
        }
        if (out.isEmpty()) {
            out.add(new FlowViews.TimelineRow(null, null, null, null, null, true));
        }
        return out;
    }

    // ------------------------------------------------------------------ SQL scaffolding

    /**
     * {@code sel → ev → mapped → seq → starts → iv}. One statement per read; the CTE names stay
     * private to this class so no caller can re-derive a second definition of "an interval".
     */
    private String withClause(FlowViews.Query q, List<FlowViews.StageView> stages,
                              Map<String, Object> params, Long requirementId) {
        // Bound here, not by each caller: every caller's CTE chain reads :toTs (the audit cutoff)
        // and most also read :fromTs, so leaving it to callers is how a 500 "No value supplied for
        // the SQL parameter 'toTs'" reaches production. Extra keys are ignored by the template.
        params.put("fromTs", q.from().atStartOfDay());
        params.put("toTs", q.to().plusDays(1).atStartOfDay());
        StringBuilder sql = new StringBuilder("""
                WITH sel AS (
                    SELECT r.id AS req_id
                    FROM requirement r
                    WHERE 1 = 1
                """);
        if (q.domainId() != null) {
            sql.append("      AND r.domain_id = :domainId\n");
            params.put("domainId", q.domainId());
        }
        if (q.vendorId() != null) {
            sql.append("      AND (r.owner_vendor_id = :vendorId OR r.assignee_vendor_id = :vendorId)\n");
            params.put("vendorId", q.vendorId());
        }
        if (q.reqType() != null && !q.reqType().isBlank()) {
            sql.append("      AND r.type = :reqType\n");
            params.put("reqType", q.reqType());
        }
        sql.append("      AND /*scope:r*/\n");
        sql.append("""
                ),
                ev AS (
                    SELECT a.id AS eid, a.biz_id AS req_id, a.created_at AS at,
                           CASE WHEN a.action = 'CREATE' THEN 'REQUIREMENT_CREATED'
                                ELSE JSON_UNQUOTE(JSON_EXTRACT(a.detail_json, '$.to'))
                           END AS st
                    FROM audit_log a
                    WHERE a.biz_type = 'REQUIREMENT'
                      AND a.action IN ('CREATE', 'STATE_CHANGE')
                      AND a.created_at < :toTs
                """);
        if (requirementId != null) {
            sql.append("      AND a.biz_id = :reqId\n");
            params.put("reqId", requirementId);
        } else {
            sql.append("      AND a.biz_id IN (SELECT req_id FROM sel)\n");
        }
        sql.append("      AND /*scope:a*/\n");
        sql.append("""
                ),
                mapped AS (
                    SELECT e.req_id, e.eid, e.at, e.st,
                """);
        sql.append(stageCase(stages, params));
        sql.append("""
                    FROM ev e
                ),
                seq AS (
                    SELECT req_id, eid, at, st, stage,
                           LAG(stage) OVER (PARTITION BY req_id ORDER BY at, eid) AS prev
                    FROM mapped
                ),
                starts AS (
                    SELECT req_id, eid, at, st, stage
                    FROM seq
                    WHERE prev IS NULL OR NOT (stage <=> prev)
                ),
                iv AS (
                    SELECT req_id, stage, at AS entered_at, st AS source_event,
                           LEAD(at) OVER (PARTITION BY req_id ORDER BY at, eid) AS left_at
                    FROM starts
                )
                """);
        return sql.toString();
    }

    /** One {@code WHEN ... THEN 'CODE'} per configured stage, all values bound as parameters. */
    private String stageCase(List<FlowViews.StageView> stages, Map<String, Object> params) {
        StringBuilder out = new StringBuilder("        CASE\n");
        int index = 0;
        for (FlowViews.StageView stage : stages) {
            FlowStageService.Mapping mapping = FlowStageService.mapping(stage);
            List<String> values = new ArrayList<>(mapping.enterStatuses());
            for (String event : mapping.enterEvents()) {
                if (!values.contains(event)) {
                    values.add(event);
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            List<String> refs = new ArrayList<>(values.size());
            int position = 0;
            for (String value : values) {
                String name = "st" + index + "_" + (position++);
                params.put(name, value);
                refs.add(":" + name);
            }
            out.append("            WHEN st IN (").append(String.join(", ", refs))
                    .append(") THEN '").append(stage.code().replace("'", "''")).append("'\n");
            index++;
        }
        if (index == 0) {
            out.append("            WHEN 1 = 0 THEN NULL\n");
        }
        out.append("            ELSE NULL\n        END AS stage\n");
        return out.toString();
    }

}
