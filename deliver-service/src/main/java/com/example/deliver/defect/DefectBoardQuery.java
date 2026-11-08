package com.example.deliver.defect;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.defect.DefectViews.BoardMetrics;
import com.example.deliver.defect.DefectViews.DefectFilter;
import com.example.deliver.defect.DefectViews.PageView;
import com.example.deliver.reqread.ReqReadModel;
import com.example.deliver.support.Rows;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.contract.MetricFormulas;
import org.springframework.stereotype.Component;

/**
 * The defect board: four cards, their drill-down lists and the CSV export — all built from
 * <b>one</b> WHERE clause.
 *
 * <p>That single clause is the whole point (AC-13): a card says 7, clicking it lists 7, and the
 * export contains 7, because the card and the list are the same query with a different SELECT.
 * Two query builders would drift the first time a filter is added to only one of them.</p>
 */
@Component
public class DefectBoardQuery {

    private static final String FROM = " FROM defect d WHERE /*scope:d*/";

    private final ScopedJdbc jdbc;
    private final ReqReadModel readModel;

    public DefectBoardQuery(ScopedJdbc jdbc, ReqReadModel readModel) {
        this.jdbc = jdbc;
        this.readModel = readModel;
    }

    public BoardMetrics metrics(DefectFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params);

        long total = jdbc.count("SELECT COUNT(*)" + where, params);
        long openHigh = jdbc.count("SELECT COUNT(*)" + where
                + " AND d.severity IN ('P0','P1') AND d.status <> 'CLOSED'", params);
        long reopened = jdbc.count("SELECT COUNT(*)" + where + " AND d.reopen_count > 0", params);
        long closed = jdbc.count("SELECT COUNT(*)" + where + " AND d.status = 'CLOSED'", params);

        List<Map<String, Object>> avgRows = jdbc.list(
                "SELECT AVG(TIMESTAMPDIFF(SECOND, d.found_at, d.closed_at)) AS avg_seconds"
                        + where + " AND d.closed_at IS NOT NULL", params);
        BigDecimal avgSeconds = avgRows.isEmpty() ? null
                : Rows.toDecimal(Rows.object(avgRows.get(0), "avg_seconds"));

        // Denominator from the requirement read model, not from a second defect query: density
        // is "defects per requirement", so the requirement half must come from the same scope.
        long requirements = readModel.countVisible(filter.domainId(), filter.vendorId());

        return new BoardMetrics(openHigh, total, reopened, closed, requirements,
                MetricFormulas.defectDensity(total, requirements),
                MetricFormulas.reopenRate(reopened, total),
                MetricFormulas.mttrFromSeconds(avgSeconds));
    }

    /** Drill-down for one card. {@code total} equals the card's number by construction. */
    public PageView<Map<String, Object>> items(DefectFilter filter, String metric, int page, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params) + metricClause(metric);
        long total = jdbc.count("SELECT COUNT(*)" + where, params);

        int safeSize = size <= 0 ? 20 : Math.min(size, 200);
        int safePage = page <= 0 ? 1 : page;
        params.put("limit", safeSize);
        params.put("offset", (long) (safePage - 1) * safeSize);

        List<Map<String, Object>> rows = jdbc.list("""
                SELECT d.id AS id, d.code AS code, d.title AS title, d.severity AS severity,
                       d.priority AS priority, d.status AS status, d.reopen_count AS reopen_count,
                       d.domain_id AS domain_id, d.vendor_id AS vendor_id,
                       d.requirement_id AS requirement_id, d.handler_id AS handler_id,
                       d.found_at AS found_at, d.closed_at AS closed_at
                """ + where + " ORDER BY d.found_at DESC, d.id DESC LIMIT :limit OFFSET :offset", params);
        return new PageView<>(rows, total, safePage, safeSize);
    }

    /** Same rows as {@link #items} with no page window — used by the CSV export. */
    public List<Map<String, Object>> export(DefectFilter filter, String metric) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params) + metricClause(metric);
        return jdbc.list("""
                SELECT d.code AS code, d.title AS title, d.severity AS severity,
                       d.priority AS priority, d.status AS status, d.reopen_count AS reopen_count,
                       d.found_at AS found_at, d.closed_at AS closed_at
                """ + where + " ORDER BY d.found_at DESC, d.id DESC", params);
    }

    /**
     * The one filter→predicate translation. Every branch adds a named parameter, so the caller
     * can reuse the same map for count/list/export without rebuilding SQL text.
     */
    private String where(DefectFilter filter, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(FROM);
        DefectFilter f = filter == null ? DefectFilter.empty() : filter;
        if (f.domainId() != null) {
            sql.append(" AND d.domain_id = :domainId");
            params.put("domainId", f.domainId());
        }
        if (f.vendorId() != null) {
            sql.append(" AND d.vendor_id = :vendorId");
            params.put("vendorId", f.vendorId());
        }
        if (f.requirementId() != null) {
            sql.append(" AND d.requirement_id = :requirementId");
            params.put("requirementId", f.requirementId());
        }
        if (f.releaseId() != null) {
            sql.append(" AND d.release_id = :releaseId");
            params.put("releaseId", f.releaseId());
        }
        if (f.handlerId() != null) {
            sql.append(" AND d.handler_id = :handlerId");
            params.put("handlerId", f.handlerId());
        }
        if (f.status() != null && !f.status().isBlank()) {
            sql.append(" AND d.status = :status");
            params.put("status", f.status());
        }
        if (f.severity() != null && !f.severity().isBlank()) {
            sql.append(" AND d.severity = :severity");
            params.put("severity", f.severity());
        }
        if (f.keyword() != null && !f.keyword().isBlank()) {
            sql.append(" AND (d.title LIKE :keyword OR d.code LIKE :keyword)");
            params.put("keyword", "%" + f.keyword().trim() + "%");
        }
        if (f.from() != null) {
            sql.append(" AND d.found_at >= :foundFrom");
            params.put("foundFrom", f.from().atStartOfDay());
        }
        if (f.to() != null) {
            sql.append(" AND d.found_at < :foundTo");
            params.put("foundTo", f.to().plusDays(1).atStartOfDay());
        }
        return sql.toString();
    }

    /** Card → its own population. Unknown metric means "no extra predicate", never an error. */
    private String metricClause(String metric) {
        if (metric == null) {
            return "";
        }
        return switch (metric) {
            case "openHigh" -> " AND d.severity IN ('P0','P1') AND d.status <> 'CLOSED'";
            case "reopened" -> " AND d.reopen_count > 0";
            case "closed", "mttr" -> " AND d.status = 'CLOSED'";
            case "open" -> " AND d.status <> 'CLOSED'";
            default -> "";
        };
    }

    /** Distinct severities/statuses the board may be filtered by; keeps the UI honest. */
    public Map<String, Object> options() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("severities", List.of("P0", "P1", "P2", "P3"));
        options.put("statuses", new ArrayList<>(List.of("NEW", "IN_PROGRESS", "PENDING_VERIFY", "CLOSED")));
        options.put("metrics", List.of("openHigh", "density", "reopenRate", "mttrMinutes"));
        return options;
    }
}
