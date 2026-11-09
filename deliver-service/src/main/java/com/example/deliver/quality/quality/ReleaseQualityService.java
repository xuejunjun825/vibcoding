package com.example.deliver.quality.quality;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.deliver.quality.gate.GateEvaluator;
import com.example.deliver.quality.gate.QualityGateService;
import com.example.deliver.quality.gate.QualityGateViews;
import com.example.deliver.quality.metric.QualityMetricService;
import com.example.deliver.support.Rows;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release quality pane (AC-8): gate history, signed reports, open P0/P1, escaped defects and the
 * four counts.
 *
 * <p>The counts are not recomputed here — they come from {@link QualityMetricService}, the same
 * service the board and the report use, so AC-8.5's "recompute and compare" can never disagree
 * with the dashboard. Reports are projected from {@code test_report}/{@code test_report_version}
 * rather than {@code ReportService.page} because this pane needs {@code created_at}, which the
 * report list view does not carry.</p>
 */
@Service
public class ReleaseQualityService {

    public record ReleaseQuality(long releaseId,
                                 String releaseCode,
                                 String releaseName,
                                 List<QualityGateViews.Snapshot> gateHistory,
                                 List<Report> reports,
                                 List<OpenDefect> openP0P1,
                                 List<EscapedDefect> escapeDefects,
                                 Counts counts) {
    }

    public record Report(long id,
                         String code,
                         String status,
                         int currentVersion,
                         String conclusion,
                         BigDecimal passRate,
                         BigDecimal coverage,
                         String signedAt,
                         String generatedAt,
                         String createdAt) {
    }

    public record OpenDefect(Long defectId, String code, String title, String severity, String status) {
    }

    public record EscapedDefect(Long defectId, String code, String title, String severity, String foundAt) {
    }

    /** Ratios stay 0..1 (the UI's formatPercent multiplies), counts stay counts. */
    public record Counts(BigDecimal fpy,
                         BigDecimal escape,
                         Long postRelease,
                         BigDecimal rollback) {
    }

    /** What req-service's admission step 6 reads through {@code /internal/quality/summary}. */
    public record Summary(long releaseId,
                          long escapeCount,
                          long postReleaseDefects,
                          long openP0P1,
                          long rollbackCount,
                          long executedOrders,
                          BigDecimal fpy,
                          List<Long> gateSnapshotIds,
                          List<Long> reportVersionIds) {
    }

    private final ScopedJdbc jdbcRead;
    private final QualityGateService gates;
    private final QualityMetricService metrics;

    public ReleaseQualityService(ScopedJdbc jdbcRead, QualityGateService gates, QualityMetricService metrics) {
        this.jdbcRead = jdbcRead;
        this.gates = gates;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public ReleaseQuality quality(long releaseId) {
        Map<String, Object> release = releaseRow(releaseId);
        if (release == null) {
            throw new ApiException(404, "RELEASE_NOT_FOUND", "发布版本不存在或无权访问");
        }
        QualityMetricService.QualityMetrics numbers =
                metrics.quality(new QualityMetricService.Query(releaseId, null, null, null, null));
        return new ReleaseQuality(
                releaseId,
                Rows.str(release, "code", ""),
                Rows.str(release, "name", ""),
                gateHistory(releaseId),
                reports(releaseId),
                openDefects(releaseId),
                escapedDefects(releaseId),
                new Counts(numbers.fpy(), numbers.escapeRate(), numbers.postReleaseDefects72h(),
                        numbers.rollbackRate()));
    }

    /**
     * The compact projection req-service needs at admission time: counts and ids only, no rows.
     * Every number is the same one {@link #quality(long)} returns.
     */
    @Transactional(readOnly = true)
    public Summary summary(long releaseId) {
        if (releaseRow(releaseId) == null) {
            throw new ApiException(404, "RELEASE_NOT_FOUND", "发布版本不存在或无权访问");
        }
        QualityMetricService.QualityMetrics numbers =
                metrics.quality(new QualityMetricService.Query(releaseId, null, null, null, null));
        long escapeCount = jdbcRead.count("""
                SELECT COUNT(*) AS total FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId AND d.is_escape = 1
                """, Map.of("releaseId", releaseId));
        long openP0P1 = jdbcRead.count("""
                SELECT COUNT(*) AS total FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId
                  AND d.severity IN ('P0', 'P1') AND d.status <> 'CLOSED'
                """, Map.of("releaseId", releaseId));
        Map<String, Object> orders = jdbcRead.one("""
                SELECT COALESCE(SUM(CASE WHEN o.execution_result = 'ROLLED_BACK' THEN 1 ELSE 0 END), 0) AS rolled,
                       COUNT(*) AS exec_cnt
                FROM deploy_order o
                JOIN `release` rel ON rel.id = o.release_id
                WHERE /*scope:rel*/ AND o.release_id = :releaseId
                  AND o.execution_result IN ('SUCCESS', 'FAILED', 'ROLLED_BACK')
                """, Map.of("releaseId", releaseId));
        long rolled = Rows.toLong(Rows.object(orders, "rolled"), 0L);
        long executed = Rows.toLong(Rows.object(orders, "exec_cnt"), 0L);
        return new Summary(releaseId,
                escapeCount,
                numbers.postReleaseDefects72h() == null ? 0L : numbers.postReleaseDefects72h(),
                openP0P1,
                rolled,
                executed,
                numbers.fpy(),
                snapshotIds(releaseId),
                reportVersionIds(releaseId));
    }

    private List<Long> snapshotIds(long releaseId) {
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT s.id FROM quality_gate_snapshot s
                JOIN `release` rel ON rel.id = s.release_id
                WHERE /*scope:rel*/ AND s.release_id = :releaseId
                ORDER BY s.id ASC
                """, Map.of("releaseId", releaseId));
        List<Long> ids = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            ids.add(Rows.toLong(Rows.object(row, "id"), 0L));
        }
        return ids;
    }

    private List<Long> reportVersionIds(long releaseId) {
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT v.id FROM test_report_version v
                JOIN test_report r ON r.id = v.report_id
                WHERE /*scope:r*/ AND r.release_id = :releaseId
                ORDER BY v.id ASC
                """, Map.of("releaseId", releaseId));
        List<Long> ids = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            ids.add(Rows.toLong(Rows.object(row, "id"), 0L));
        }
        return ids;
    }

    private Map<String, Object> releaseRow(long releaseId) {
        return jdbcRead.one("""
                SELECT rel.id, rel.code, rel.name
                FROM `release` rel
                WHERE /*scope:rel*/ AND rel.id = :releaseId
                """, Map.of("releaseId", releaseId));
    }

    private List<QualityGateViews.Snapshot> gateHistory(long releaseId) {        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT s.id
                FROM quality_gate_snapshot s
                JOIN `release` rel ON rel.id = s.release_id
                WHERE /*scope:rel*/ AND s.release_id = :releaseId
                ORDER BY s.id DESC
                LIMIT 20
                """, Map.of("releaseId", releaseId));
        List<QualityGateViews.Snapshot> history = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            history.add(gates.viewOf(Rows.toLong(Rows.object(row, "id"), 0L)));
        }
        return history;
    }

    private List<Report> reports(long releaseId) {
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT r.id, r.code, r.status, r.current_version, r.created_at,
                       v.conclusion, v.pass_rate, v.coverage, v.signed_at, v.generated_at
                FROM test_report r
                LEFT JOIN test_report_version v
                       ON v.report_id = r.id AND v.version_no = r.current_version
                WHERE /*scope:r*/ AND r.release_id = :releaseId
                ORDER BY r.id DESC
                """, Map.of("releaseId", releaseId));
        List<Report> reports = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            reports.add(new Report(
                    Rows.toLong(Rows.object(row, "id"), 0L),
                    Rows.str(row, "code", ""),
                    Rows.str(row, "status", ""),
                    Rows.toInt(Rows.object(row, "current_version"), 0),
                    Rows.str(row, "conclusion"),
                    Rows.toDecimal(Rows.object(row, "pass_rate")),
                    Rows.toDecimal(Rows.object(row, "coverage")),
                    GateEvaluator.iso(Rows.toDateTime(Rows.object(row, "signed_at"))),
                    GateEvaluator.iso(Rows.toDateTime(Rows.object(row, "generated_at"))),
                    GateEvaluator.iso(Rows.toDateTime(Rows.object(row, "created_at")))));
        }
        return reports;
    }

    private List<OpenDefect> openDefects(long releaseId) {
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT d.id, d.code, d.title, d.severity, d.status
                FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId
                  AND d.severity IN ('P0', 'P1') AND d.status <> 'CLOSED'
                ORDER BY d.severity ASC, d.id ASC
                """, Map.of("releaseId", releaseId));
        List<OpenDefect> defects = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            defects.add(new OpenDefect(
                    Rows.toLong(Rows.object(row, "id")),
                    Rows.str(row, "code", ""),
                    Rows.str(row, "title", ""),
                    Rows.str(row, "severity", ""),
                    Rows.str(row, "status", "")));
        }
        return defects;
    }

    private List<EscapedDefect> escapedDefects(long releaseId) {
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT d.id, d.code, d.title, d.severity, d.found_at
                FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId AND d.is_escape = 1
                ORDER BY d.found_at DESC, d.id DESC
                """, Map.of("releaseId", releaseId));
        List<EscapedDefect> defects = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            defects.add(new EscapedDefect(
                    Rows.toLong(Rows.object(row, "id")),
                    Rows.str(row, "code", ""),
                    Rows.str(row, "title", ""),
                    Rows.str(row, "severity", ""),
                    GateEvaluator.iso(Rows.toDateTime(Rows.object(row, "found_at")))));
        }
        return defects;
    }
}
