package com.example.deliver.quality.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.deliver.quality.aggregate.PlanAggregate;
import com.example.deliver.quality.aggregate.QualityAggregator;
import com.example.deliver.quality.aggregate.QualityDigest;
import com.example.deliver.quality.plan.PlanLifecycle;
import com.example.deliver.support.Rows;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.code.CodeGenerator;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.MetricFormulas;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test report generation and versioning (AC-4.1/4.2/4.3/4.6/4.7).
 *
 * <p>A report is a header row plus immutable versions. Each version freezes the eight sections in
 * {@code snapshot_json} — the numbers are never recomputed on read, so an old report keeps the
 * numbers it was signed against. What is frozen is the data: plan/case results, defect rollups,
 * environment references and the signoff levels. What stays live is only the signoff rows, because
 * those are written after the version exists.</p>
 *
 * <p>Two identifiers are deliberately distinct: {@code result_digest} (from {@link QualityDigest},
 * the case results) says "did the tested data change", while {@code hash} (SHA-256 of the snapshot
 * JSON) says "did this document change". QG-02 reads the first, the signoff dialog shows the
 * second.</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    public static final String SCOPE_PLAN = "PLAN";
    public static final String SCOPE_RELEASE = "RELEASE";

    /** Signoff order: the plan owner tests, the product accepts, the PMO authorises. */
    public static final List<SignoffLevel> SIGN_LEVELS = List.of(
            new SignoffLevel(1, "TEST_LEAD", "测试负责人"),
            new SignoffLevel(2, "PRODUCT", "产品"),
            new SignoffLevel(3, "PMO", "PMO"));

    /** Frozen detail ids per section. Beyond this the version keeps counts only ({@code truncated}). */
    static final int DETAIL_LIMIT = 20_000;

    /** Rows kept inside the snapshot for the residual-defect section (rendered as a list, not paged). */
    private static final int RESIDUAL_ROW_LIMIT = 200;

    private static final String REPORT_SELECT = """
            SELECT r.id AS id, r.code AS code, r.scope AS scope, r.plan_id AS plan_id,
                   r.release_id AS release_id, r.status AS status, r.current_version AS current_version,
                   r.data_cutoff AS data_cutoff, r.domain_id AS domain_id, r.vendor_id AS vendor_id,
                   r.created_by AS created_by, r.created_at AS created_at, r.updated_at AS updated_at,
                   u.name AS created_by_name,
                   p.name AS plan_name, p.owner_id AS plan_owner_id, p.status AS plan_status,
                   p.revision AS plan_revision, p.exit_criteria_json AS exit_criteria_json,
                   rel.name AS release_name, rel.code AS release_code, rel.status AS release_status,
                   rel.report_validity_days AS validity_days
            FROM test_report r
            LEFT JOIN test_plan p ON p.id = r.plan_id AND p.tenant_id = r.tenant_id
            LEFT JOIN `release` rel ON rel.id = r.release_id AND rel.tenant_id = r.tenant_id
            LEFT JOIN sys_user u ON u.id = r.created_by AND u.tenant_id = r.tenant_id
            """;

    private static final String VERSION_SELECT = """
            SELECT v.id AS id, v.report_id AS report_id, v.version_no AS version_no,
                   v.snapshot_json AS snapshot_json, v.result_digest AS result_digest,
                   v.conclusion AS conclusion, v.pass_rate AS pass_rate, v.coverage AS coverage,
                   v.generated_by AS generated_by, v.generated_at AS generated_at,
                   v.signed_at AS signed_at, v.status AS status, v.hash AS hash,
                   u.name AS generated_by_name
            FROM test_report_version v
            JOIN test_report r ON r.id = v.report_id AND r.tenant_id = v.tenant_id
            LEFT JOIN sys_user u ON u.id = v.generated_by AND u.tenant_id = v.tenant_id
            """;

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final CodeGenerator codes;
    private final NotificationWriter notifications;
    private final QualityDigest digests;
    private final QualityAggregator aggregator;

    public ReportService(ScopedJdbc jdbcRead,
                         NamedParameterJdbcTemplate jdbc,
                         ObjectMapper objectMapper,
                         AuditService audit,
                         CodeGenerator codes,
                         NotificationWriter notifications,
                         QualityDigest digests,
                         QualityAggregator aggregator) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.codes = codes;
        this.notifications = notifications;
        this.digests = digests;
        this.aggregator = aggregator;
    }

    /** One rung of the signoff ladder. */
    public record SignoffLevel(int orderNo, String role, String label) {
    }

    // ================================================================== generate

    @Transactional
    public ReportViews.ReportDetail generate(ReportViews.Generate cmd) {
        DataScope scope = TenantContext.require();
        if (cmd == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "请求体不能为空", Map.of("field", "body"));
        }
        String type = normaliseScope(cmd.scope());

        long releaseId;
        Long planId = null;
        List<Map<String, Object>> allPlans;
        List<Map<String, Object>> included;
        List<ReportViews.ExcludedPlan> excluded = new ArrayList<>();

        if (SCOPE_PLAN.equals(type)) {
            if (cmd.planId() == null) {
                throw new ApiException(400, "VALIDATION_FAILED", "scope=PLAN 时必须提供 planId",
                        Map.of("field", "planId"));
            }
            planId = cmd.planId();
            Map<String, Object> plan = requirePlan(planId);
            String status = Rows.str(plan, "status");
            if (!PlanLifecycle.reportableStatuses().contains(status)) {
                throw new ApiException(409, "PLAN_NOT_APPROVED",
                        "计划尚未批准，不能生成报告",
                        Map.of("planId", planId, "status", status,
                                "allowedStatuses", List.copyOf(PlanLifecycle.reportableStatuses())));
            }
            Long release = Rows.toLong(Rows.object(plan, "release_id"));
            if (release == null) {
                throw new ApiException(400, "PLAN_NO_RELEASE", "计划未关联版本，不能生成报告",
                        Map.of("planId", planId));
            }
            releaseId = release;
            allPlans = List.of(plan);
            included = List.of(plan);
        } else {
            if (cmd.releaseId() == null) {
                throw new ApiException(400, "VALIDATION_FAILED", "scope=RELEASE 时必须提供 releaseId",
                        Map.of("field", "releaseId"));
            }
            releaseId = cmd.releaseId();
            requireRelease(releaseId);
            allPlans = jdbcRead.list("""
                    SELECT p.id AS id, p.name AS name, p.status AS status, p.revision AS revision,
                           p.owner_id AS owner_id, p.exit_criteria_json AS exit_criteria_json
                    FROM test_plan p
                    WHERE /*scope:p*/ AND p.release_id = :releaseId
                    ORDER BY p.id ASC
                    """, Map.of("releaseId", releaseId));
            included = new ArrayList<>();
            for (Map<String, Object> plan : allPlans) {
                String status = Rows.str(plan, "status");
                if (PlanLifecycle.reportableStatuses().contains(status)) {
                    included.add(plan);
                } else {
                    excluded.add(new ReportViews.ExcludedPlan(
                            Rows.toLong(Rows.object(plan, "id"), 0L),
                            Rows.str(plan, "name"),
                            status));
                }
            }
            if (included.isEmpty()) {
                throw new ApiException(409, "REPORT_NO_PLAN",
                        "该版本下没有已批准的计划，无法生成报告",
                        Map.of("releaseId", releaseId, "excludedPlans", excluded));
            }
        }

        List<Long> planIds = new ArrayList<>(included.size());
        for (Map<String, Object> plan : included) {
            planIds.add(Rows.toLong(Rows.object(plan, "id"), 0L));
        }

        // One report per (scope, plan|release): regenerating appends a version (AC-4.3).
        Long existing = findReportForUpdate(type, planId, releaseId, scope.tenantId());
        long reportId;
        int versionNo;
        if (existing == null) {
            Map<String, Object> release = requireRelease(releaseId);
            String code = codes.next("RPT", scope.tenantId());
            reportId = insertReport(scope, code, type, planId, releaseId, release);
            versionNo = 1;
        } else {
            reportId = existing;
            versionNo = nextVersionNo(reportId);
        }

        LocalDateTime cutoff = now();
        Map<String, Object> release = requireRelease(releaseId);
        Snapshot snapshot = buildSnapshot(type, releaseId, planId, release, included, excluded, planIds, cutoff);

        String snapshotJson = json(snapshot.sections());
        String resultDigest = digests.digest(snapshot.stamps());
        String hash = QualityDigest.sha256(snapshotJson);
        String conclusion = chooseConclusion(snapshot.suggestedConclusion(), cmd.conclusion());

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", scope.tenantId())
                .addValue("reportId", reportId)
                .addValue("versionNo", versionNo)
                .addValue("snapshotJson", snapshotJson)
                .addValue("resultDigest", resultDigest)
                .addValue("conclusion", conclusion)
                .addValue("passRate", snapshot.passRate())
                .addValue("coverage", snapshot.coverage())
                .addValue("generatedBy", scope.userId())
                .addValue("generatedAt", cutoff)
                .addValue("status", "SIGNING")
                .addValue("hash", hash);
        jdbc.update("""
                INSERT INTO test_report_version
                    (tenant_id, report_id, version_no, snapshot_json, result_digest, conclusion,
                     pass_rate, coverage, generated_by, generated_at, status, hash)
                VALUES
                    (:tenantId, :reportId, :versionNo, :snapshotJson, :resultDigest, :conclusion,
                     :passRate, :coverage, :generatedBy, :generatedAt, :status, :hash)
                """, params);

        jdbc.update("""
                UPDATE test_report
                   SET status = 'SIGNING', current_version = :versionNo, data_cutoff = :cutoff,
                       updated_at = NOW(3), updated_by = :userId
                 WHERE id = :reportId AND tenant_id = :tenantId
                """, new MapSqlParameterSource()
                .addValue("versionNo", versionNo)
                .addValue("cutoff", cutoff)
                .addValue("userId", scope.userId())
                .addValue("reportId", reportId)
                .addValue("tenantId", scope.tenantId()));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reportId", reportId);
        detail.put("versionNo", versionNo);
        detail.put("scope", type);
        detail.put("releaseId", releaseId);
        detail.put("planIds", planIds);
        detail.put("conclusion", conclusion);
        detail.put("suggested", snapshot.suggestedConclusion());
        detail.put("resultDigest", resultDigest);
        detail.put("hash", hash);
        detail.put("dataCutoff", cutoff.toString());
        audit.record("report.generate", "test_report", reportId, detail);

        notifyPendingSigner(scope.tenantId(), reportId, versionNo, Rows.toLong(Rows.object(included.get(0), "owner_id")));
        return detail(reportId);
    }

    // ================================================================== read

    @Transactional(readOnly = true)
    public ReportViews.ReportDetail detail(long reportId) {
        Map<String, Object> row = requireReport(reportId);
        int currentVersion = Rows.toInt(Rows.object(row, "current_version"), 1);
        // The version read already resolves rates, expiry and signoff progress: one source of numbers.
        ReportViews.ReportVersionDetail current = version(reportId, currentVersion);
        Map<String, Object> sections = section(
                current.snapshot() == null ? Map.of() : current.snapshot(), "sections");
        Map<String, Object> scopeSection = section(sections, "scope");
        ReportViews.ReportRow summary = reportRow(row, current);
        return new ReportViews.ReportDetail(
                summary.id(),
                summary.code(),
                summary,
                current,
                versions(reportId),
                current.signoff(),
                excludedPlans(sections),
                planList(scopeSection.get("plans")),
                planList(scopeSection.get("uncoveredRequirements")),
                lastRejectComment(reportId));
    }

    /** Report centre row built from a {@code report} query row plus its current version. */
    private ReportViews.ReportRow reportRow(Map<String, Object> row,
                                            ReportViews.ReportVersionDetail current) {
        return new ReportViews.ReportRow(
                Rows.toLong(Rows.object(row, "id"), 0L),
                Rows.str(row, "code"),
                Rows.str(row, "scope"),
                Rows.toLong(Rows.object(row, "plan_id")),
                Rows.toLong(Rows.object(row, "release_id")),
                Rows.str(row, "plan_name"),
                Rows.str(row, "release_name"),
                Rows.str(row, "status", "SIGNING"),
                current.versionNo(),
                current.conclusion(),
                current.passRate(),
                current.coverage(),
                current.signoff().approved(),
                current.signoff().total(),
                current.signoff().pendingRole(),
                current.signoff().pendingRoleLabel(),
                text(Rows.object(row, "data_cutoff")),
                current.generatedAt(),
                current.signedAt(),
                current.expiresAt(),
                current.validityDays(),
                Rows.toLong(Rows.object(row, "created_by")),
                Rows.str(row, "created_by_name"));
    }

    private static List<Map<String, Object>> planList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> node = new LinkedHashMap<>();
                    map.forEach((k, v) -> node.put(String.valueOf(k), v));
                    out.add(node);
                }
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ReportViews.VersionRow> versions(long reportId) {
        requireReport(reportId);
        List<Map<String, Object>> rows = jdbcRead.list(VERSION_SELECT + """
                WHERE /*scope:r*/ AND v.report_id = :reportId
                ORDER BY v.version_no ASC
                """, Map.of("reportId", reportId));
        Map<Integer, Integer> signed = signedCounts(reportId);
        List<ReportViews.VersionRow> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            int versionNo = Rows.toInt(Rows.object(row, "version_no"), 1);
            out.add(new ReportViews.VersionRow(
                    reportId,
                    versionNo,
                    Rows.str(row, "status"),
                    Rows.str(row, "conclusion"),
                    Rows.toDecimal(Rows.object(row, "pass_rate")),
                    Rows.toDecimal(Rows.object(row, "coverage")),
                    Rows.str(row, "result_digest"),
                    Rows.str(row, "hash"),
                    Rows.str(row, "generated_by_name"),
                    text(Rows.object(row, "generated_at")),
                    text(Rows.object(row, "signed_at")),
                    signed.getOrDefault(versionNo, 0),
                    SIGN_LEVELS.size()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ReportViews.ReportVersionDetail version(long reportId, int versionNo) {
        Map<String, Object> report = requireReport(reportId);
        Map<String, Object> row = requireVersion(reportId, versionNo);
        String status = Rows.str(row, "status");
        Integer validity = validityDays(report);
        LocalDateTime signedAt = dateTime(Rows.object(row, "signed_at"));
        Map<String, Object> snapshot = snapshot(row);
        String note = stringOrNull(snapshot.get("note"));
        return new ReportViews.ReportVersionDetail(
                reportId,
                Rows.str(report, "code"),
                Rows.str(report, "scope"),
                Rows.toLong(Rows.object(report, "plan_id")),
                Rows.toLong(Rows.object(report, "release_id")),
                Rows.str(report, "plan_name"),
                Rows.str(report, "release_name"),
                versionNo,
                status,
                Rows.str(row, "conclusion"),
                Rows.toDecimal(Rows.object(row, "pass_rate")),
                Rows.toDecimal(Rows.object(row, "coverage")),
                Rows.str(row, "result_digest"),
                Rows.str(row, "hash"),
                Rows.str(row, "generated_by_name"),
                text(Rows.object(row, "generated_at")),
                text(signedAt),
                expiresAt(signedAt, validity),
                validity,
                "SIGNED".equals(status),
                note,
                progress(reportId, versionNo, status, Rows.str(row, "conclusion")),
                wrapSnapshot(snapshot, row));
    }

    /**
     * The API face of a frozen snapshot: the reader's header values stay flat, the 8 frozen sections
     * travel under {@code sections} in their fixed order so the reader can page through them.
     */
    private static Map<String, Object> wrapSnapshot(Map<String, Object> snapshot,
                                                    Map<String, Object> versionRow) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conclusion", Rows.str(versionRow, "conclusion"));
        out.put("passRate", Rows.toDecimal(Rows.object(versionRow, "pass_rate")));
        out.put("coverage", Rows.toDecimal(Rows.object(versionRow, "coverage")));
        out.put("planRevision", planRevision(snapshot));
        out.put("resultDigest", Rows.str(versionRow, "result_digest"));
        out.put("generatedAt", snapshot.get("generatedAt"));
        out.put("schemaVersion", snapshot.get("schemaVersion"));
        out.put("sections", snapshot);
        return out;
    }

    /** Revision of the plan a report froze; a release report freezes several, the lowest one wins. */
    private static Integer planRevision(Map<String, Object> snapshot) {
        Object plans = section(snapshot, "scope").get("plans");
        if (!(plans instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Integer lowest = null;
        for (Object item : list) {
            if (item instanceof Map<?, ?> plan) {
                Object revision = plan.get("revision");
                if (revision instanceof Number number) {
                    int value = number.intValue();
                    lowest = lowest == null ? value : Math.min(lowest, value);
                }
            }
        }
        return lowest;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportViews.ReportRow> page(Long releaseId, Long planId, String scope,
                                                    String status, String keyword,
                                                    int page, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("""
                SELECT r.id AS id, r.code AS code, r.scope AS scope, r.plan_id AS plan_id,
                       r.release_id AS release_id, r.status AS status,
                       r.current_version AS current_version, r.data_cutoff AS data_cutoff,
                       r.created_by AS created_by, r.created_at AS created_at,
                       p.name AS plan_name, rel.name AS release_name, u.name AS created_by_name,
                       rel.report_validity_days AS validity_days,
                       v.conclusion AS conclusion, v.pass_rate AS pass_rate,
                       v.coverage AS coverage, v.signed_at AS signed_at,
                       v.generated_at AS generated_at
                FROM test_report r
                LEFT JOIN test_report_version v
                       ON v.report_id = r.id AND v.version_no = r.current_version
                LEFT JOIN test_plan p ON p.id = r.plan_id AND p.tenant_id = r.tenant_id
                LEFT JOIN `release` rel ON rel.id = r.release_id AND rel.tenant_id = r.tenant_id
                LEFT JOIN sys_user u ON u.id = r.created_by AND u.tenant_id = r.tenant_id
                WHERE /*scope:r*/
                """);
        if (releaseId != null) {
            sql.append(" AND r.release_id = :releaseId");
            params.put("releaseId", releaseId);
        }
        if (planId != null) {
            sql.append(" AND r.plan_id = :planId");
            params.put("planId", planId);
        }
        if (scope != null && !scope.isBlank()) {
            sql.append(" AND r.scope = :scope");
            params.put("scope", normaliseScope(scope));
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND r.status = :status");
            params.put("status", status.trim().toUpperCase(Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND r.code LIKE :keyword");
            params.put("keyword", "%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY r.id DESC");

        PageResponse<Map<String, Object>> raw = jdbcRead.page(sql.toString(), params, page, size);
        List<ReportViews.ReportRow> items = new ArrayList<>(raw.items().size());
        for (Map<String, Object> row : raw.items()) {
            long id = Rows.toLong(Rows.object(row, "id"), 0L);
            int version = Rows.toInt(Rows.object(row, "current_version"), 1);
            Map<Integer, Integer> signed = signedCounts(id);
            int signedCount = signed.getOrDefault(version, 0);
            ReportViews.SignoffProgress progress = progress(id, version,
                    Rows.str(row, "status", "SIGNING"), Rows.str(row, "conclusion"));
            Integer validity = validityDays(row);
            LocalDateTime signedAt = dateTime(Rows.object(row, "signed_at"));
            items.add(new ReportViews.ReportRow(
                    id,
                    Rows.str(row, "code"),
                    Rows.str(row, "scope"),
                    Rows.toLong(Rows.object(row, "plan_id")),
                    Rows.toLong(Rows.object(row, "release_id")),
                    Rows.str(row, "plan_name"),
                    Rows.str(row, "release_name"),
                    Rows.str(row, "status"),
                    version,
                    Rows.str(row, "conclusion"),
                    Rows.toDecimal(Rows.object(row, "pass_rate")),
                    Rows.toDecimal(Rows.object(row, "coverage")),
                    signedCount,
                    SIGN_LEVELS.size(),
                    progress.pendingRole(),
                    progress.pendingRoleLabel(),
                    text(Rows.object(row, "data_cutoff")),
                    text(Rows.object(row, "generated_at")),
                    text(signedAt),
                    expiresAt(signedAt, validity),
                    validity,
                    Rows.toLong(Rows.object(row, "created_by")),
                    Rows.str(row, "created_by_name")));
        }
        return new PageResponse<>(items, raw.total(), raw.page(), raw.size());
    }

    // ================================================================== frozen detail drawers

    @Transactional(readOnly = true)
    public PageResponse<ReportViews.CaseDetailRow> caseItems(long reportId, int versionNo,
                                                             String result, int page, int size) {
        Map<String, Object> row = requireVersion(reportId, versionNo);
        Map<String, Object> execution = section(snapshot(row), "execution");
        List<Long> caseIds = longList(execution.get("caseIds"));
        Map<String, String> results = stringMap(execution.get("results"));

        List<Long> filtered = new ArrayList<>();
        for (Long caseId : caseIds) {
            if (result == null || result.isBlank()) {
                filtered.add(caseId);
                continue;
            }
            String wanted = result.trim().toUpperCase(Locale.ROOT);
            for (String key : results.keySet()) {
                if (key.startsWith(caseId + ":")) {
                    if (wanted.equals(results.get(key))) {
                        filtered.add(caseId);
                    }
                    break;
                }
            }
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size));
        int from = Math.min(filtered.size(), (safePage - 1) * safeSize);
        int to = Math.min(filtered.size(), from + safeSize);
        List<Long> slice = filtered.subList(from, to);
        if (slice.isEmpty()) {
            return new PageResponse<>(List.of(), filtered.size(), safePage, safeSize);
        }

        Map<Long, Map<String, Object>> live = new LinkedHashMap<>();
        for (Map<String, Object> item : jdbcRead.list("""
                SELECT pc.id AS plan_case_id, pc.case_id AS case_id, pc.case_version AS case_version,
                       pc.executor_id AS executor_id, pc.last_executed_at AS last_executed_at,
                       c.code AS case_code, c.title AS title, tm.name AS module_name,
                       u.name AS executor_name
                FROM test_plan_case pc
                JOIN test_plan p ON p.id = pc.plan_id AND p.tenant_id = pc.tenant_id
                LEFT JOIN test_case c ON c.id = pc.case_id AND c.tenant_id = pc.tenant_id
                LEFT JOIN test_module tm ON tm.id = c.module_id AND tm.tenant_id = c.tenant_id
                LEFT JOIN sys_user u ON u.id = pc.executor_id AND u.tenant_id = pc.tenant_id
                WHERE /*scope:p*/ AND pc.case_id IN (:caseIds)
                ORDER BY pc.case_id ASC, pc.id ASC
                """, Map.of("caseIds", slice))) {
            live.putIfAbsent(Rows.toLong(Rows.object(item, "case_id"), 0L), item);
        }

        List<ReportViews.CaseDetailRow> items = new ArrayList<>(slice.size());
        for (Long caseId : slice) {
            Map<String, Object> item = live.get(caseId);
            String frozenResult = results.getOrDefault(caseId + ":" + caseVersionOf(results, caseId), "NOT_RUN");
            items.add(new ReportViews.CaseDetailRow(
                    item == null ? 0L : Rows.toLong(Rows.object(item, "plan_case_id"), 0L),
                    caseId,
                    item == null ? null : Rows.str(item, "case_code"),
                    item == null ? 0 : Rows.toInt(Rows.object(item, "case_version"), 1),
                    item == null ? null : Rows.str(item, "title"),
                    item == null ? null : Rows.str(item, "module_name"),
                    item == null ? null : Rows.str(item, "executor_name"),
                    frozenResult,
                    item == null ? null : text(Rows.object(item, "last_executed_at"))));
        }
        return new PageResponse<>(items, filtered.size(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportViews.DefectDetailRow> defectItems(long reportId, int versionNo,
                                                                 String severity, int page, int size) {
        Map<String, Object> row = requireVersion(reportId, versionNo);
        Map<String, Object> defects = section(snapshot(row), "defects");
        List<Long> ids = longList(defects.get("defectIds"));
        List<Long> filtered = new ArrayList<>();
        if (ids.isEmpty()) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size));
        int from = Math.min(ids.size(), (safePage - 1) * safeSize);
        int to = Math.min(ids.size(), from + safeSize);
        List<Long> slice = ids.subList(from, to);
        if (slice.isEmpty()) {
            return new PageResponse<>(List.of(), ids.size(), safePage, safeSize);
        }
        StringBuilder sql = new StringBuilder("""
                SELECT d.id AS id, d.code AS code, d.title AS title, d.severity AS severity,
                       d.status AS status, d.source AS source, d.is_escape AS is_escape,
                       d.found_at AS found_at
                FROM defect d
                WHERE /*scope:d*/ AND d.id IN (:ids)
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ids", slice);
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND d.severity = :severity");
            params.put("severity", severity.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" ORDER BY FIELD(d.severity, 'P0', 'P1', 'P2', 'P3'), d.id ASC");
        List<ReportViews.DefectDetailRow> items = new ArrayList<>();
        for (Map<String, Object> item : jdbcRead.list(sql.toString(), params)) {
            items.add(new ReportViews.DefectDetailRow(
                    Rows.toLong(Rows.object(item, "id"), 0L),
                    Rows.str(item, "code"),
                    Rows.str(item, "title"),
                    Rows.str(item, "severity"),
                    Rows.str(item, "status"),
                    Rows.str(item, "source"),
                    Rows.toInt(Rows.object(item, "is_escape"), 0) == 1,
                    text(Rows.object(item, "found_at"))));
        }
        return new PageResponse<>(items, ids.size(), safePage, safeSize);
    }

    // ================================================================== diff

    @Transactional(readOnly = true)
    public ReportViews.ReportDiff diff(long reportId, int fromVersion, int toVersion) {
        Map<String, Object> fromRow = requireVersion(reportId, fromVersion);
        Map<String, Object> toRow = requireVersion(reportId, toVersion);
        Map<String, Object> from = snapshot(fromRow);
        Map<String, Object> to = snapshot(toRow);
        Map<String, String> fromResults = stringMap(section(from, "execution").get("results"));
        Map<String, String> toResults = stringMap(section(to, "execution").get("results"));

        Set<Long> addedFailIds = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : toResults.entrySet()) {
            if (!"FAIL".equals(entry.getValue())) {
                continue;
            }
            if (!"FAIL".equals(fromResults.get(entry.getKey()))) {
                addedFailIds.add(caseIdOfKey(entry.getKey()));
            }
        }
        List<String> addedFails = caseCodes(addedFailIds);

        Set<Long> fromDefects = new LinkedHashSet<>(longList(section(from, "defects").get("defectIds")));
        Set<Long> toDefects = new LinkedHashSet<>(longList(section(to, "defects").get("defectIds")));
        Set<Long> newDefectIds = new LinkedHashSet<>(toDefects);
        newDefectIds.removeAll(fromDefects);
        List<String> newDefects = defectCodes(newDefectIds);

        Set<Long> closedIds = new LinkedHashSet<>(fromDefects);
        closedIds.removeAll(toDefects);
        List<String> closedDefects = defectCodes(closedIds);

        return new ReportViews.ReportDiff(
                fromVersion,
                toVersion,
                difference(Rows.toDecimal(Rows.object(toRow, "pass_rate")), Rows.toDecimal(Rows.object(fromRow, "pass_rate"))),
                difference(Rows.toDecimal(Rows.object(toRow, "coverage")), Rows.toDecimal(Rows.object(fromRow, "coverage"))),
                Rows.str(toRow, "conclusion"),
                addedFails,
                closedDefects,
                newDefects,
                Map.of("conclusion", nullSafe(Rows.str(fromRow, "conclusion")),
                        "passRate", nullSafe(Rows.toDecimal(Rows.object(fromRow, "pass_rate"))),
                        "coverage", nullSafe(Rows.toDecimal(Rows.object(fromRow, "coverage"))),
                        "generatedAt", nullSafe(text(Rows.object(fromRow, "generated_at")))),
                Map.of("conclusion", nullSafe(Rows.str(toRow, "conclusion")),
                        "passRate", nullSafe(Rows.toDecimal(Rows.object(toRow, "pass_rate"))),
                        "coverage", nullSafe(Rows.toDecimal(Rows.object(toRow, "coverage"))),
                        "generatedAt", nullSafe(text(Rows.object(toRow, "generated_at")))));
    }

    // ================================================================== snapshot building

    private record Snapshot(Map<String, Object> sections,
                            List<QualityDigest.CaseStamp> stamps,
                            BigDecimal passRate,
                            BigDecimal coverage,
                            String suggestedConclusion) {
    }

    private Snapshot buildSnapshot(String type, long releaseId, Long planId,
                                   Map<String, Object> release,
                                   List<Map<String, Object>> included,
                                   List<ReportViews.ExcludedPlan> excluded,
                                   List<Long> planIds,
                                   LocalDateTime cutoff) {
        Map<Long, Map<String, Object>> planStats = planStats(planIds);
        Map<String, Object> scopeSection = scopeSection(type, release, planId, included, excluded,
                planStats, cutoff);

        long planned = 0;
        long executed = 0;
        long passed = 0;
        long failed = 0;
        long blocked = 0;
        long skipped = 0;
        long notRun = 0;
        for (Map<String, Object> stat : planStats.values()) {
            planned += Rows.toLong(Rows.object(stat, "total"), 0L);
            executed += Rows.toLong(Rows.object(stat, "executed"), 0L);
            passed += Rows.toLong(Rows.object(stat, "passed"), 0L);
            failed += Rows.toLong(Rows.object(stat, "failed"), 0L);
            blocked += Rows.toLong(Rows.object(stat, "blocked"), 0L);
            skipped += Rows.toLong(Rows.object(stat, "skipped"), 0L);
            notRun += Rows.toLong(Rows.object(stat, "not_run"), 0L);
        }

        Map<String, Object> executionSection = executionSection(included, planIds,
                planned, executed, passed, failed, blocked, skipped, notRun);

        BigDecimal passRate = MetricFormulas.passRate(passed, planned);
        BigDecimal coverage = MetricFormulas.coverage(executed, planned);
        Map<String, Object> ratesSection = new LinkedHashMap<>();
        put(ratesSection, "passRate", passRate);
        put(ratesSection, "coverage", coverage);
        put(ratesSection, "executedPassRate", MetricFormulas.executedPassRate(passed, executed));
        ratesSection.put("planned", planned);
        ratesSection.put("executed", executed);
        ratesSection.put("passed", passed);
        ratesSection.put("failed", failed);
        ratesSection.put("blocked", blocked);
        ratesSection.put("skipped", skipped);
        ratesSection.put("notRun", notRun);
        ratesSection.put("passRateFormula", "passed / planned");
        ratesSection.put("coverageFormula", "executed / planned");

        Map<String, Object> defectsSection = defectsSection(type, releaseId, planId, planIds, cutoff);
        long openP0P1 = Rows.toLong(Rows.object(defectsSection, "openP0P1"), 0L);
        long openDefects = Rows.toLong(Rows.object(defectsSection, "open"), 0L);
        Map<String, Object> residualSection = residualSection(type, releaseId, planId, planIds,
                openP0P1, openDefects, cutoff);
        Map<String, Object> envSection = envSection(planIds);

        Map<String, Object> riskSection = riskSection(type, included, planStats, passRate, coverage,
                planned, notRun, failed, openP0P1, openDefects, residualSection, excluded);

        Map<String, Object> signoffSection = new LinkedHashMap<>();
        signoffSection.put("total", SIGN_LEVELS.size());
        signoffSection.put("progress", 0);
        signoffSection.put("status", "SIGNING");
        List<Map<String, Object>> levels = new ArrayList<>();
        for (SignoffLevel level : SIGN_LEVELS) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("orderNo", level.orderNo());
            node.put("role", level.role());
            node.put("roleLabel", level.label());
            node.put("decision", null);
            levels.add(node);
        }
        signoffSection.put("levels", levels);

        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("schemaVersion", 1);
        sections.put("generatedAt", cutoff.toString());
        sections.put("scope", scopeSection);
        sections.put("execution", executionSection);
        sections.put("rates", ratesSection);
        sections.put("defects", defectsSection);
        sections.put("residual", residualSection);
        sections.put("env", envSection);
        sections.put("riskConclusion", riskSection);
        sections.put("signoff", signoffSection);

        List<QualityDigest.CaseStamp> stamps = stamps(planIds);
        String suggested = String.valueOf(riskSection.get("suggested"));
        return new Snapshot(sections, stamps, passRate, coverage, suggested);
    }

    private Map<String, Object> scopeSection(String type, Map<String, Object> release, Long planId,
                                             List<Map<String, Object>> included,
                                             List<ReportViews.ExcludedPlan> excluded,
                                             Map<Long, Map<String, Object>> planStats,
                                             LocalDateTime cutoff) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", type);
        section.put("releaseId", Rows.toLong(Rows.object(release, "id"), 0L));
        section.put("releaseCode", Rows.str(release, "code"));
        section.put("releaseName", Rows.str(release, "name"));
        section.put("releaseStatus", Rows.str(release, "status"));
        put(section, "planId", planId);
        section.put("dataCutoff", cutoff.toString());

        List<Map<String, Object>> plans = new ArrayList<>(included.size());
        for (Map<String, Object> plan : included) {
            long id = Rows.toLong(Rows.object(plan, "id"), 0L);
            Map<String, Object> stat = planStats.getOrDefault(id, Map.of());
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("planId", id);
            node.put("name", Rows.str(plan, "name"));
            node.put("status", Rows.str(plan, "status"));
            node.put("revision", Rows.toInt(Rows.object(plan, "revision"), 0));
            node.put("total", Rows.toLong(Rows.object(stat, "total"), 0L));
            node.put("executed", Rows.toLong(Rows.object(stat, "executed"), 0L));
            node.put("passed", Rows.toLong(Rows.object(stat, "passed"), 0L));
            node.put("failed", Rows.toLong(Rows.object(stat, "failed"), 0L));
            node.put("blocked", Rows.toLong(Rows.object(stat, "blocked"), 0L));
            node.put("skipped", Rows.toLong(Rows.object(stat, "skipped"), 0L));
            node.put("notRun", Rows.toLong(Rows.object(stat, "not_run"), 0L));
            put(node, "passRate", MetricFormulas.passRate(
                    Rows.toLong(Rows.object(stat, "passed"), 0L),
                    Rows.toLong(Rows.object(stat, "total"), 0L)));
            plans.add(node);
        }
        section.put("plans", plans);
        section.put("excludedPlans", excluded);
        section.put("planCount", plans.size());
        section.put("excludedCount", excluded.size());
        return section;
    }

    /**
     * 报告的计划级数字只能来自 {@link QualityAggregator}——报告/门禁/计划进度共用同一个
     * 通过率分母（计划用例总数、按用例加权）。这里曾经自带 result 聚合 SQL，
     * 与聚合器口径一致时看不出问题，一旦分母调整就会各算一套；{@code skipped} 由聚合器
     * 已有的 executed/passed/failed/blocked 派生（PlanAggregate 不单独存 skipped）。
     */
    private Map<Long, Map<String, Object>> planStats(List<Long> planIds) {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        for (Long planId : planIds) {
            PlanAggregate plan = aggregator.aggregatePlan(planId);
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("plan_id", planId);
            stat.put("total", plan.total());
            stat.put("executed", plan.executed());
            stat.put("passed", plan.passed());
            stat.put("failed", plan.failed());
            stat.put("blocked", plan.blocked());
            // SKIP / NOT_RUN 的派生只在 PlanAggregate 一处实现，报告与计划进度共用。
            stat.put("skipped", plan.skipped());
            stat.put("not_run", plan.notRun());
            out.put(planId, stat);
        }
        return out;
    }

    private Map<String, Object> executionSection(List<Map<String, Object>> included, List<Long> planIds,
                                                 long planned, long executed, long passed, long failed,
                                                 long blocked, long skipped, long notRun) {
        Map<String, long[]> total = new LinkedHashMap<>();
        Map<Long, String> moduleNames = new LinkedHashMap<>();
        Map<Long, String> executorNames = new LinkedHashMap<>();
        Map<Long, Map<String, long[]>> byModule = new LinkedHashMap<>();
        Map<Long, Map<String, long[]>> byExecutor = new LinkedHashMap<>();

        for (Map<String, Object> row : jdbcRead.list("""
                SELECT pc.result AS result, COUNT(*) AS n,
                       tc.module_id AS module_id, tm.name AS module_name,
                       pc.executor_id AS executor_id, u.name AS executor_name
                FROM test_plan_case pc
                JOIN test_plan p ON p.id = pc.plan_id AND p.tenant_id = pc.tenant_id
                LEFT JOIN test_case tc ON tc.id = pc.case_id AND tc.tenant_id = pc.tenant_id
                LEFT JOIN test_module tm ON tm.id = tc.module_id AND tm.tenant_id = tc.tenant_id
                LEFT JOIN sys_user u ON u.id = pc.executor_id AND u.tenant_id = pc.tenant_id
                WHERE /*scope:p*/ AND pc.plan_id IN (:planIds)
                GROUP BY pc.result, tc.module_id, tm.name, pc.executor_id, u.name
                """, Map.of("planIds", planIds))) {
            String result = Rows.str(row, "result", "NOT_RUN");
            long count = Rows.toLong(Rows.object(row, "n"), 0L);
            accumulate(total, result, count);
            Long moduleId = Rows.toLong(Rows.object(row, "module_id"));
            if (moduleId != null) {
                moduleNames.putIfAbsent(moduleId, Rows.str(row, "module_name"));
                accumulate(byModule.computeIfAbsent(moduleId, key -> new LinkedHashMap<>()), result, count);
            }
            Long executorId = Rows.toLong(Rows.object(row, "executor_id"));
            if (executorId != null) {
                executorNames.putIfAbsent(executorId, Rows.str(row, "executor_name"));
                accumulate(byExecutor.computeIfAbsent(executorId, key -> new LinkedHashMap<>()), result, count);
            }
        }

        Map<String, Object> section = new LinkedHashMap<>();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("planned", planned);
        totals.put("executed", executed);
        totals.put("passed", passed);
        totals.put("failed", failed);
        totals.put("blocked", blocked);
        totals.put("skipped", skipped);
        totals.put("notRun", notRun);
        section.put("totals", totals);
        section.put("byResult", counts(total));

        List<Map<String, Object>> modules = new ArrayList<>();
        for (Map.Entry<Long, Map<String, long[]>> entry : byModule.entrySet()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("moduleId", entry.getKey());
            node.put("moduleName", moduleNames.get(entry.getKey()));
            node.putAll(counts(entry.getValue()));
            modules.add(node);
        }
        section.put("byModule", modules);

        List<Map<String, Object>> executors = new ArrayList<>();
        for (Map.Entry<Long, Map<String, long[]>> entry : byExecutor.entrySet()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("executorId", entry.getKey());
            node.put("executorName", executorNames.get(entry.getKey()));
            node.putAll(counts(entry.getValue()));
            executors.add(node);
        }
        section.put("byExecutor", executors);

        List<Long> caseIds = ids("""
                SELECT pc.case_id AS id
                FROM test_plan_case pc
                JOIN test_plan p ON p.id = pc.plan_id AND p.tenant_id = pc.tenant_id
                WHERE /*scope:p*/ AND pc.plan_id IN (:planIds)
                ORDER BY pc.case_id ASC
                """, planIds);
        List<Long> executionIds = ids("""
                SELECT e.id AS id
                FROM test_execution e
                JOIN test_plan p ON p.id = e.plan_id AND p.tenant_id = e.tenant_id
                WHERE /*scope:p*/ AND e.plan_id IN (:planIds)
                ORDER BY e.id ASC
                """, planIds);
        Map<String, String> results = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcRead.list("""
                SELECT pc.case_id AS case_id, pc.case_version AS case_version, pc.result AS result
                FROM test_plan_case pc
                JOIN test_plan p ON p.id = pc.plan_id AND p.tenant_id = pc.tenant_id
                WHERE /*scope:p*/ AND pc.plan_id IN (:planIds)
                ORDER BY pc.case_id ASC, pc.id ASC
                """, Map.of("planIds", planIds))) {
            results.putIfAbsent(
                    Rows.toLong(Rows.object(row, "case_id"), 0L) + ":"
                            + Rows.toInt(Rows.object(row, "case_version"), 1),
                    Rows.str(row, "result", "NOT_RUN"));
        }
        boolean truncated = caseIds.size() >= DETAIL_LIMIT || executionIds.size() >= DETAIL_LIMIT
                || results.size() >= DETAIL_LIMIT;
        section.put("caseIds", trim(caseIds));
        section.put("executionIds", trim(executionIds));
        section.put("results", trimResults(results));
        section.put("truncated", truncated);
        section.put("planIds", planIds);
        section.put("planSummaries", summaries(included));
        return section;
    }

    private static List<Map<String, Object>> summaries(List<Map<String, Object>> plans) {
        List<Map<String, Object>> out = new ArrayList<>(plans.size());
        for (Map<String, Object> plan : plans) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("planId", Rows.toLong(Rows.object(plan, "id"), 0L));
            node.put("name", Rows.str(plan, "name"));
            node.put("status", Rows.str(plan, "status"));
            node.put("revision", Rows.toInt(Rows.object(plan, "revision"), 0));
            out.add(node);
        }
        return out;
    }

    private Map<String, Object> defectsSection(String type, long releaseId, Long planId,
                                               List<Long> planIds, LocalDateTime cutoff) {
        String where = defectWhere(type);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseId", releaseId);
        if (SCOPE_PLAN.equals(type)) {
            params.put("planIds", planIds);
        }
        Map<String, Object> head = jdbcRead.one("""
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(d.status <> 'CLOSED'), 0) AS open_count,
                       COALESCE(SUM(d.severity IN ('P0', 'P1') AND d.status <> 'CLOSED'), 0) AS open_p0p1,
                       COALESCE(SUM(d.severity = 'P0'), 0) AS p0,
                       COALESCE(SUM(d.severity = 'P1'), 0) AS p1,
                       COALESCE(SUM(d.is_escape = 1), 0) AS `escaped`
                FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId
                """ + where, params);

        Map<String, Object> section = new LinkedHashMap<>();
        long total = Rows.toLong(Rows.object(head, "total"), 0L);
        section.put("total", total);
        section.put("open", Rows.toLong(Rows.object(head, "open_count"), 0L));
        section.put("openP0P1", Rows.toLong(Rows.object(head, "open_p0p1"), 0L));
        section.put("p0", Rows.toLong(Rows.object(head, "p0"), 0L));
        section.put("p1", Rows.toLong(Rows.object(head, "p1"), 0L));
        section.put("escaped", Rows.toLong(Rows.object(head, "escaped"), 0L));
        put(section, "escapeRate", MetricFormulas.escapeRate(
                Rows.toLong(Rows.object(head, "escaped"), 0L), total));
        section.put("bySeverity", group("""
                SELECT d.severity AS k, COUNT(*) AS n
                FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId
                """ + where + " GROUP BY d.severity ORDER BY d.severity", params));
        section.put("byStatus", group("""
                SELECT d.status AS k, COUNT(*) AS n
                FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId
                """ + where + " GROUP BY d.status ORDER BY d.status", params));
        section.put("bySource", group("""
                SELECT COALESCE(d.source, 'MANUAL') AS k, COUNT(*) AS n
                FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId
                """ + where + " GROUP BY COALESCE(d.source, 'MANUAL') ORDER BY 2 DESC", params));
        section.put("byDay", group("""
                SELECT DATE(d.found_at) AS k, COUNT(*) AS n
                FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId
                """ + where + " GROUP BY DATE(d.found_at) ORDER BY DATE(d.found_at) ASC", params));
        Map<String, Object> idParams = new LinkedHashMap<>(params);
        idParams.put("limit", DETAIL_LIMIT + 1);
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> row : jdbcRead.list("""
                SELECT d.id AS id
                FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId
                """ + where + " ORDER BY d.id ASC LIMIT :limit", idParams)) {
            ids.add(Rows.toLong(Rows.object(row, "id"), 0L));
        }
        boolean truncated = ids.size() > DETAIL_LIMIT;
        if (truncated) {
            ids = ids.subList(0, DETAIL_LIMIT);
        }
        section.put("defectIds", ids);
        section.put("truncated", truncated);
        section.put("dataCutoff", cutoff.toString());
        put(section, "planId", planId);
        return section;
    }

    private String defectWhere(String type) {
        if (!SCOPE_PLAN.equals(type)) {
            return "";
        }
        // PLAN scope: a defect belongs to the plan when it was raised against one of its cases or
        // against a requirement the plan covers. Both EXISTS read the already-scoped plan ids.
        return " AND (EXISTS (SELECT 1 FROM test_plan_case pc2"
                + " WHERE pc2.plan_id IN (:planIds) AND pc2.case_id = d.test_case_id)"
                + " OR EXISTS (SELECT 1 FROM test_plan_case pc3"
                + " WHERE pc3.plan_id IN (:planIds) AND pc3.requirement_id = d.requirement_id))";
    }

    private Map<String, Object> residualSection(String type, long releaseId, Long planId,
                                                List<Long> planIds, long openP0P1, long openDefects,
                                                LocalDateTime cutoff) {
        String where = defectWhere(type);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseId", releaseId);
        if (SCOPE_PLAN.equals(type)) {
            params.put("planIds", planIds);
        }
        params.put("limit", RESIDUAL_ROW_LIMIT);

        List<Map<String, Object>> open = new ArrayList<>();
        List<Long> openIds = new ArrayList<>();
        for (Map<String, Object> row : jdbcRead.list("""
                SELECT d.id AS id, d.code AS code, d.title AS title, d.severity AS severity,
                       d.status AS status, d.source AS source, d.is_escape AS is_escape
                FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId AND d.status <> 'CLOSED'
                """ + where + """
                 ORDER BY FIELD(d.severity, 'P0', 'P1', 'P2', 'P3'), d.id ASC
                 LIMIT :limit
                """, params)) {
            long id = Rows.toLong(Rows.object(row, "id"), 0L);
            openIds.add(id);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", id);
            node.put("code", Rows.str(row, "code"));
            node.put("title", Rows.str(row, "title"));
            node.put("severity", Rows.str(row, "severity"));
            node.put("status", Rows.str(row, "status"));
            node.put("source", Rows.str(row, "source"));
            node.put("escaped", Rows.toInt(Rows.object(row, "is_escape"), 0) == 1);
            open.add(node);
        }

        List<Map<String, Object>> waivers = new ArrayList<>();
        for (Map<String, Object> row : jdbcRead.list("""
                SELECT w.id AS id, w.rule_code AS rule_code, w.status AS status, w.reason AS reason,
                       w.impact_scope AS impact_scope, w.expiry_at AS expiry_at,
                       w.release_id AS release_id
                FROM quality_gate_waiver w
                JOIN `release` rel ON rel.id = w.release_id AND rel.tenant_id = w.tenant_id
                WHERE /*scope:rel*/ AND w.release_id = :releaseId
                  AND w.tenant_id = :tenantId
                  AND w.status IN ('PENDING', 'APPROVED')
                  AND w.expiry_at > :cutoff
                ORDER BY w.id ASC
                """, Map.of("tenantId", TenantContext.require().tenantId(),
                        "releaseId", releaseId, "cutoff", cutoff))) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", Rows.toLong(Rows.object(row, "id"), 0L));
            node.put("ruleCode", Rows.str(row, "rule_code"));
            node.put("status", Rows.str(row, "status"));
            node.put("reason", Rows.str(row, "reason"));
            node.put("impactScope", Rows.str(row, "impact_scope"));
            node.put("expiryAt", text(Rows.object(row, "expiry_at")));
            waivers.add(node);
        }

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("openDefectCount", openDefects);
        section.put("openP0P1", openP0P1);
        section.put("openDefects", open);
        section.put("openDefectIds", openIds);
        section.put("waiverCount", waivers.size());
        section.put("waivers", waivers);
        section.put("truncated", open.size() >= RESIDUAL_ROW_LIMIT);
        put(section, "planId", planId);
        return section;
    }

    private Map<String, Object> envSection(List<Long> planIds) {
        List<Map<String, Object>> envs = new ArrayList<>();
        if (!planIds.isEmpty()) {
            for (Map<String, Object> row : jdbcRead.list("""
                    SELECT e.id AS id, e.name AS name, e.env_type AS env_type,
                           e.base_url AS base_url, e.status AS status
                    FROM test_env e
                    WHERE /*scope:e*/ AND e.id IN (
                        SELECT p.env_id FROM test_plan p
                         WHERE p.id IN (:planIds) AND p.env_id IS NOT NULL
                        UNION
                        SELECT x.env_id FROM test_execution x
                         WHERE x.plan_id IN (:planIds) AND x.env_id IS NOT NULL)
                    ORDER BY e.id ASC
                    """, Map.of("planIds", planIds))) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("envId", Rows.toLong(Rows.object(row, "id"), 0L));
                node.put("name", Rows.str(row, "name"));
                node.put("envType", Rows.str(row, "env_type"));
                node.put("baseUrl", Rows.str(row, "base_url"));
                node.put("status", Rows.str(row, "status"));
                envs.add(node);
            }
        }
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("envs", envs);
        section.put("envCount", envs.size());
        return section;
    }

    private Map<String, Object> riskSection(String type, List<Map<String, Object>> included,
                                            Map<Long, Map<String, Object>> planStats,
                                            BigDecimal passRate, BigDecimal coverage,
                                            long planned, long notRun, long failed,
                                            long openP0P1, long openDefects,
                                            Map<String, Object> residual,
                                            List<ReportViews.ExcludedPlan> excluded) {
        List<Map<String, Object>> risks = new ArrayList<>();
        if (openP0P1 > 0) {
            risks.add(risk("OPEN_P0_P1", "HIGH", openP0P1 + " 个未关闭 P0/P1 缺陷"));
        }
        if (failed > 0) {
            risks.add(risk("FAILED_CASES", "HIGH", failed + " 条用例结果为 FAIL"));
        }
        if (notRun > 0) {
            risks.add(risk("NOT_RUN_CASES", "MEDIUM", notRun + " 条用例尚未执行"));
        }
        if (openDefects > 0) {
            risks.add(risk("RESIDUAL_DEFECTS", "MEDIUM", openDefects + " 个缺陷仍未关闭"));
        }
        long waiverCount = Rows.toLong(Rows.object(residual, "waiverCount"), 0L);
        if (waiverCount > 0) {
            risks.add(risk("ACTIVE_WAIVERS", "MEDIUM", waiverCount + " 条质量门豁免在有效期内"));
        }
        if (!excluded.isEmpty()) {
            risks.add(risk("EXCLUDED_PLANS", "MEDIUM",
                    excluded.size() + " 个计划未批准，未纳入本次报告"));
        }
        BigDecimal threshold = threshold(included);
        // 量纲对齐：MetricFormulas 的比率是 0..1（1.00 = 100%），而阈值/展示口径是百分数（95.00）。
        // 直接比较会把 100% 当成 1% 判成「低于阈值」→ 结论恒 CONDITIONAL，门禁 QG-02 永远不过。
        BigDecimal passPercent = passRate == null ? null : passRate.movePointRight(2);
        BigDecimal coveragePercent = coverage == null ? null : coverage.movePointRight(2);
        if (passPercent != null && threshold != null && passPercent.compareTo(threshold) < 0) {
            risks.add(risk("PASS_RATE_BELOW_THRESHOLD", "HIGH",
                    "通过率 " + passPercent.toPlainString() + "% 低于阈值 " + threshold.toPlainString() + "%"));
        }
        if (coveragePercent != null && planned > 0 && coveragePercent.compareTo(new BigDecimal("100.00")) < 0) {
            risks.add(risk("COVERAGE_GAP", "LOW",
                    "执行覆盖率 " + coveragePercent.toPlainString() + "%，仍有用例未执行"));
        }

        RequiredCases required = requiredCases(included);
        BigDecimal requiredPercent = required.rate() == null ? null : required.rate().movePointRight(2);
        if (required.enabled() && required.total() > 0 && requiredPercent != null) {
            if (requiredPercent.compareTo(required.requiredPassRate()) < 0) {
                risks.add(risk("REQUIRED_CASES_NOT_PASSED", "HIGH",
                        "必需用例通过率 " + requiredPercent.toPlainString() + "% 低于 "
                                + required.requiredPassRate().toPlainString() + "%"));
            }
        }

        boolean fail = openP0P1 > 0
                || (required.enabled() && required.total() > 0 && requiredPercent != null
                        && requiredPercent.compareTo(required.requiredPassRate()) < 0);
        boolean conditional = !fail && (openDefects > 0 || waiverCount > 0
                || (passPercent != null && threshold != null && passPercent.compareTo(threshold) < 0));
        String suggested = fail ? "FAIL" : conditional ? "CONDITIONAL" : "PASS";

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("suggested", suggested);
        section.put("conclusion", suggested);
        section.put("risks", risks);
        put(section, "passRateThreshold", threshold);
        put(section, "passRate", passRate);
        put(section, "coverage", coverage);
        section.put("openP0P1", openP0P1);
        section.put("openDefects", openDefects);
        section.put("waiverCount", waiverCount);
        Map<String, Object> requiredNode = new LinkedHashMap<>();
        requiredNode.put("enabled", required.enabled());
        requiredNode.put("total", required.total());
        requiredNode.put("passed", required.passed());
        put(requiredNode, "rate", required.rate());
        requiredNode.put("requiredPassRate", required.requiredPassRate());
        section.put("requiredCases", requiredNode);
        section.put("scope", type);
        return section;
    }

    private record RequiredCases(boolean enabled, int total, int passed,
                                 BigDecimal rate, BigDecimal requiredPassRate) {
    }

    private RequiredCases requiredCases(List<Map<String, Object>> plans) {
        Set<Long> caseIds = new LinkedHashSet<>();
        BigDecimal requiredPassRate = new BigDecimal("100.00");
        boolean enabled = false;
        for (Map<String, Object> plan : plans) {
            Map<String, Object> json = planExitCriteria(plan);
            Object raw = json.get("requiredCases");
            if (!(raw instanceof Map<?, ?> required)) {
                continue;
            }
            if (truthy(required.get("enabled"))) {
                enabled = true;
                for (Long id : longList(required.get("caseIds"))) {
                    caseIds.add(id);
                }
                BigDecimal rate = decimal(required.get("requiredPassRate"));
                if (rate != null) {
                    requiredPassRate = rate;
                }
            }
        }
        if (!enabled || caseIds.isEmpty()) {
            return new RequiredCases(enabled, caseIds.size(), 0, null, requiredPassRate);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("caseIds", new ArrayList<>(caseIds));
        int passed = 0;
        for (Map<String, Object> row : jdbcRead.list("""
                SELECT pc.case_id AS case_id, pc.result AS result
                FROM test_plan_case pc
                JOIN test_plan p ON p.id = pc.plan_id AND p.tenant_id = pc.tenant_id
                WHERE /*scope:p*/ AND pc.case_id IN (:caseIds)
                ORDER BY pc.id ASC
                """, params)) {
            if ("PASS".equals(Rows.str(row, "result"))) {
                passed++;
            }
        }
        BigDecimal rate = MetricFormulas.passRate(passed, caseIds.size());
        return new RequiredCases(true, caseIds.size(), passed, rate, requiredPassRate);
    }

    private BigDecimal threshold(List<Map<String, Object>> plans) {
        BigDecimal min = null;
        for (Map<String, Object> plan : plans) {
            Map<String, Object> json = planExitCriteria(plan);
            Object raw = json.get("passRate");
            BigDecimal value = raw instanceof Map<?, ?> map ? decimal(map.get("threshold")) : null;
            if (value != null && (min == null || value.compareTo(min) < 0)) {
                min = value;
            }
        }
        return min == null ? new BigDecimal("95.00") : min;
    }

    private Map<String, Object> planExitCriteria(Map<String, Object> plan) {
        String json = Rows.str(plan, "exit_criteria_json");
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            log.debug("ignoring unparseable exit_criteria_json on plan {}",
                    Rows.toLong(Rows.object(plan, "id"), 0L), ex);
            return Map.of();
        }
    }

    private List<QualityDigest.CaseStamp> stamps(List<Long> planIds) {
        List<QualityDigest.CaseStamp> stamps = new ArrayList<>();
        if (planIds.isEmpty()) {
            return stamps;
        }
        for (Map<String, Object> row : jdbcRead.list("""
                SELECT pc.case_id AS case_id, pc.case_version AS case_version, pc.result AS result
                FROM test_plan_case pc
                JOIN test_plan p ON p.id = pc.plan_id AND p.tenant_id = pc.tenant_id
                WHERE /*scope:p*/ AND pc.plan_id IN (:planIds)
                ORDER BY pc.case_id ASC, pc.id ASC
                """, Map.of("planIds", planIds))) {
            stamps.add(new QualityDigest.CaseStamp(
                    Rows.toLong(Rows.object(row, "case_id"), 0L),
                    Rows.toInt(Rows.object(row, "case_version"), 1),
                    Rows.str(row, "result", "NOT_RUN")));
        }
        return stamps;
    }

    // ================================================================== signoff support (used by SignoffService)

    /** Report header row, scoped; 403 when it exists but sits outside the caller's data scope. */
    @Transactional(readOnly = true)
    public Map<String, Object> requireReport(long reportId) {
        Map<String, Object> row = jdbcRead.one(REPORT_SELECT
                + " WHERE /*scope:r*/ AND r.id = :reportId", Map.of("reportId", reportId));
        if (row != null) {
            return row;
        }
        Map<String, Object> raw = jdbc.queryForList(
                "SELECT domain_id, vendor_id FROM test_report WHERE id = :reportId AND tenant_id = :tenantId",
                Map.of("reportId", reportId, "tenantId", TenantContext.require().tenantId()))
                .stream().findFirst().orElse(null);
        if (raw != null) {
            throw new ApiException(403, "SCOPE_DENIED", "该报告不在你的数据范围内",
                    Map.of("reportId", reportId));
        }
        throw new ApiException(404, "REPORT_NOT_FOUND", "报告不存在", Map.of("reportId", reportId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> requireVersion(long reportId, int versionNo) {
        Map<String, Object> row = jdbcRead.one(VERSION_SELECT
                        + " WHERE /*scope:r*/ AND v.report_id = :reportId AND v.version_no = :versionNo",
                Map.of("reportId", reportId, "versionNo", versionNo));
        if (row == null) {
            throw new ApiException(404, "REPORT_VERSION_NOT_FOUND", "报告版本不存在",
                    Map.of("reportId", reportId, "versionNo", versionNo));
        }
        return row;
    }

    /** Live signoff progress of one version; rows missing from {@code report_signoff} count as pending. */
    @Transactional(readOnly = true)
    public ReportViews.SignoffProgress progress(long reportId, int versionNo, String status) {
        return progress(reportId, versionNo, status, null);
    }

    public ReportViews.SignoffProgress progress(long reportId, int versionNo, String status,
                                                String conclusion) {
        Map<String, Map<String, Object>> byRole = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcRead.list("""
                SELECT s.sign_role AS sign_role, s.signer_id AS signer_id, s.decision AS decision,
                       s.`comment` AS comment, s.signed_at AS signed_at, s.order_no AS order_no,
                       u.name AS signer_name
                FROM report_signoff s
                JOIN test_report r ON r.id = s.report_id AND r.tenant_id = s.tenant_id
                LEFT JOIN sys_user u ON u.id = s.signer_id AND u.tenant_id = s.tenant_id
                WHERE /*scope:r*/ AND s.report_id = :reportId AND s.version_no = :versionNo
                ORDER BY s.order_no ASC
                """, Map.of("reportId", reportId, "versionNo", versionNo))) {
            byRole.put(Rows.str(row, "sign_role"), row);
        }
        int approved = 0;
        for (SignoffLevel level : SIGN_LEVELS) {
            Map<String, Object> row = byRole.get(level.role());
            if (row != null && "APPROVE".equals(Rows.str(row, "decision"))) {
                approved++;
            }
        }
        boolean rejected = byRole.values().stream()
                .anyMatch(row -> "REJECT".equals(Rows.str(row, "decision")));
        boolean signed = "SIGNED".equals(status) || approved == SIGN_LEVELS.size();
        int step = Math.min(SIGN_LEVELS.size(), approved + 1);
        SignoffLevel pending = signed || rejected ? null : SIGN_LEVELS.get(step - 1);

        List<ReportViews.SignoffStep> steps = new ArrayList<>(SIGN_LEVELS.size());
        for (SignoffLevel level : SIGN_LEVELS) {
            Map<String, Object> row = byRole.get(level.role());
            String decision = row == null ? null : Rows.str(row, "decision");
            boolean isCurrent = pending != null && pending.orderNo() == level.orderNo();
            steps.add(new ReportViews.SignoffStep(
                    level.orderNo(),
                    level.role(),
                    level.label(),
                    stepStatus(decision),
                    row == null ? null : Rows.toLong(Rows.object(row, "signer_id")),
                    row == null ? null : Rows.str(row, "signer_name"),
                    row == null ? null : text(Rows.object(row, "signed_at")),
                    row == null ? null : Rows.str(row, "comment"),
                    isCurrent,
                    isCurrent && canActAs(level.role(), planOwner(reportId))));
        }
        return new ReportViews.SignoffProgress(
                reportId,
                versionNo,
                rejected ? "REJECTED" : (signed ? "SIGNED" : "SIGNING"),
                conclusion,
                signed ? SIGN_LEVELS.size() : step,
                SIGN_LEVELS.size(),
                approved,
                signed,
                pending == null ? null : pending.role(),
                pending == null ? null : pending.label(),
                steps);
    }

    /** Role decision as the rail renders it: no row is PENDING, APPROVE is SIGNED, REJECT is REJECTED. */
    private static String stepStatus(String decision) {
        if ("APPROVE".equals(decision)) {
            return "SIGNED";
        }
        if ("REJECT".equals(decision)) {
            return "REJECTED";
        }
        return "PENDING";
    }

    /**
     * Whether the caller may sign for {@code role} — the single copy of the signoff role rule, shared
     * with {@link SignoffService} so the rail's button and the service's guard cannot disagree.
     */
    public static boolean canActAs(String role, boolean planOwner) {
        List<String> roles = TenantContext.roles();
        boolean admin = roles.contains("PLATFORM_ADMIN");
        return switch (role) {
            case "TEST_LEAD" -> planOwner || admin || roles.contains("QA")
                    || roles.contains("PMO") || roles.contains("MGMT");
            case "PRODUCT" -> admin || roles.contains("PRODUCT");
            case "PMO" -> admin || roles.contains("PMO") || roles.contains("MGMT");
            default -> false;
        };
    }

    /** Whether the caller owns the plan behind this report (the TEST_LEAD rung's standing claim). */
    private boolean planOwner(long reportId) {
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT p.owner_id AS owner_id
                FROM test_report r
                JOIN test_plan p ON p.id = r.plan_id AND p.tenant_id = r.tenant_id
                WHERE /*scope:r*/ AND r.id = :reportId
                """, Map.of("reportId", reportId));
        if (rows.isEmpty()) {
            return false;
        }
        Long owner = Rows.toLong(Rows.object(rows.get(0), "owner_id"));
        return owner != null && owner == TenantContext.require().userId();
    }

    /** {@code snapshot_json} parsed back into the 8-section map. */
    public Map<String, Object> snapshot(Map<String, Object> versionRow) {
        String json = Rows.str(versionRow, "snapshot_json");
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("report snapshot is not valid JSON: report="
                    + Rows.toLong(Rows.object(versionRow, "report_id"), 0L)
                    + " version=" + Rows.toInt(Rows.object(versionRow, "version_no"), 0), ex);
        }
    }

    /** {@code signed_at + release.report_validity_days} at 23:59:59 (AC-4.7). */
    public static String expiresAt(LocalDateTime signedAt, Integer validityDays) {
        if (signedAt == null || validityDays == null) {
            return null;
        }
        return signedAt.toLocalDate().plusDays(validityDays).atTime(23, 59, 59)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    public int currentVersion(Map<String, Object> reportRow) {
        return Rows.toInt(Rows.object(reportRow, "current_version"), 1);
    }

    public Integer validityDays(Map<String, Object> reportRow) {
        Long value = Rows.toLong(Rows.object(reportRow, "validity_days"));
        return value == null ? null : value.intValue();
    }

    // ================================================================== internals

    private Map<String, Object> requirePlan(long planId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT p.id AS id, p.name AS name, p.status AS status, p.revision AS revision,
                       p.owner_id AS owner_id, p.release_id AS release_id,
                       p.exit_criteria_json AS exit_criteria_json
                FROM test_plan p
                WHERE /*scope:p*/ AND p.id = :planId
                """, Map.of("planId", planId));
        if (row == null) {
            throw new ApiException(404, "PLAN_NOT_FOUND", "测试计划不存在", Map.of("planId", planId));
        }
        return row;
    }

    private Map<String, Object> requireRelease(long releaseId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT rel.id AS id, rel.code AS code, rel.name AS name, rel.status AS status,
                       rel.domain_id AS domain_id,
                       rel.report_validity_days AS report_validity_days
                FROM `release` rel
                WHERE /*scope:rel*/ AND rel.id = :releaseId
                """, Map.of("releaseId", releaseId));
        if (row == null) {
            throw new ApiException(404, "RELEASE_NOT_FOUND", "版本不存在", Map.of("releaseId", releaseId));
        }
        return row;
    }

    private Long findReportForUpdate(String type, Long planId, long releaseId, long tenantId) {
        String sql = "SELECT id FROM test_report WHERE tenant_id = :tenantId AND scope = :scope AND "
                + (SCOPE_PLAN.equals(type) ? "plan_id = :refId" : "release_id = :refId")
                + " ORDER BY id ASC LIMIT 1 FOR UPDATE";
        List<Long> ids = jdbc.queryForList(sql,
                Map.of("tenantId", tenantId, "scope", type,
                        "refId", SCOPE_PLAN.equals(type) ? planId : releaseId),
                Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long insertReport(DataScope scope, String code, String type, Long planId, long releaseId,
                              Map<String, Object> release) {
        Long domainId = Rows.toLong(Rows.object(release, "domain_id"));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", scope.tenantId())
                .addValue("code", code)
                .addValue("scope", type)
                .addValue("planId", planId)
                .addValue("releaseId", releaseId)
                .addValue("domainId", domainId == null ? 0L : domainId)
                .addValue("vendorId", reportVendor(type, planId, releaseId))
                .addValue("userId", scope.userId());
        jdbc.update("""
                INSERT INTO test_report
                    (tenant_id, code, scope, plan_id, release_id, status, current_version,
                     data_cutoff, domain_id, vendor_id, created_by, updated_by)
                VALUES
                    (:tenantId, :code, :scope, :planId, :releaseId, 'SIGNING', 1,
                     NOW(3), :domainId, :vendorId, :userId, :userId)
                """, params);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
        if (id == null) {
            throw new IllegalStateException("report insert did not return an id");
        }
        return id;
    }

    /**
     * The report carries the vendor of the plans it covers: that is what the row-level scope filter
     * reads, so a vendor user must not see another vendor's report. A release spanning several
     * vendors (or none) is a platform-level report and keeps a null vendor — {@code release} has no
     * vendor column of its own, so the answer has to come from the plans.
     */
    private Long reportVendor(String type, Long planId, long releaseId) {
        long tenantId = TenantContext.require().tenantId();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("refId", SCOPE_PLAN.equals(type) ? planId : releaseId);
        List<Long> ids = jdbc.queryForList(SCOPE_PLAN.equals(type)
                ? "SELECT vendor_id FROM test_plan WHERE tenant_id = :tenantId AND id = :refId"
                : "SELECT DISTINCT vendor_id FROM test_plan WHERE tenant_id = :tenantId"
                        + " AND release_id = :refId AND vendor_id IS NOT NULL", params, Long.class);
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        return distinct.size() == 1 ? distinct.get(0) : null;
    }

    private int nextVersionNo(long reportId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM test_report_version"
                        + " WHERE report_id = :reportId FOR UPDATE",
                Map.of("reportId", reportId), Long.class);
        return (max == null ? 0 : max.intValue()) + 1;
    }

    private Map<Integer, Integer> signedCounts(long reportId) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcRead.list("""
                SELECT s.version_no AS version_no, COUNT(*) AS n
                FROM report_signoff s
                JOIN test_report r ON r.id = s.report_id AND r.tenant_id = s.tenant_id
                WHERE /*scope:r*/ AND s.report_id = :reportId AND s.decision = 'APPROVE'
                GROUP BY s.version_no
                """, Map.of("reportId", reportId))) {
            out.put(Rows.toInt(Rows.object(row, "version_no"), 1),
                    Rows.toInt(Rows.object(row, "n"), 0));
        }
        return out;
    }

    private String currentConclusion(long reportId, int versionNo) {
        List<String> rows = jdbc.queryForList(
                "SELECT conclusion FROM test_report_version WHERE report_id = :reportId"
                        + " AND version_no = :versionNo",
                Map.of("reportId", reportId, "versionNo", versionNo), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal currentRate(long reportId, int versionNo, String column) {
        List<BigDecimal> rows = jdbc.queryForList(
                "SELECT " + column + " FROM test_report_version WHERE report_id = :reportId"
                        + " AND version_no = :versionNo",
                Map.of("reportId", reportId, "versionNo", versionNo), BigDecimal.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LocalDateTime currentSignedAt(long reportId, int versionNo) {
        for (Map<String, Object> row : jdbcRead.list("""
                SELECT v.signed_at AS signed_at
                FROM test_report_version v
                JOIN test_report r ON r.id = v.report_id AND r.tenant_id = v.tenant_id
                WHERE /*scope:r*/ AND v.report_id = :reportId AND v.version_no = :versionNo
                """, Map.of("reportId", reportId, "versionNo", versionNo))) {
            return dateTime(Rows.object(row, "signed_at"));
        }
        return null;
    }

    private String lastRejectComment(long reportId) {
        for (Map<String, Object> row : jdbcRead.list("""
                SELECT s.`comment` AS comment
                FROM report_signoff s
                JOIN test_report r ON r.id = s.report_id AND r.tenant_id = s.tenant_id
                WHERE /*scope:r*/ AND s.report_id = :reportId AND s.decision = 'REJECT'
                ORDER BY s.id DESC
                LIMIT 1
                """, Map.of("reportId", reportId))) {
            return Rows.str(row, "comment");
        }
        return null;
    }

    private List<ReportViews.ExcludedPlan> excludedPlans(long reportId, int versionNo) {
        for (Map<String, Object> version : jdbcRead.list(VERSION_SELECT
                + " WHERE /*scope:r*/ AND v.report_id = :reportId AND v.version_no = :versionNo",
                Map.of("reportId", reportId, "versionNo", versionNo))) {
            return excludedPlans(section(snapshot(version), "scope"));
        }
        return List.of();
    }

    private static List<ReportViews.ExcludedPlan> excludedPlans(Map<String, Object> scopeSection) {
        Object raw = scopeSection.get("excludedPlans");
        List<ReportViews.ExcludedPlan> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(new ReportViews.ExcludedPlan(
                        toLong(map.get("planId")),
                        stringOrNull(map.get("name")),
                        stringOrNull(map.get("status"))));
            }
        }
        return out;
    }

    private static long toLong(Object value) {
        Long parsed = Rows.toLong(value);
        return parsed == null ? 0L : parsed;
    }

    private void notifyPendingSigner(long tenantId, long reportId, int versionNo, Long ownerId) {
        if (ownerId == null) {
            return;
        }
        try {
            notifications.send(ownerId, "REPORT_SIGN_PENDING", "测试报告待签署",
                    "报告 v" + versionNo + " 已生成，等待你作为测试负责人签署",
                    "test_report", reportId);
        } catch (RuntimeException ex) {
            // A missing notification must never lose the report itself.
            log.warn("report signoff notification failed: report={} version={}", reportId, versionNo, ex);
        }
    }

    // ------------------------------------------------------------------ small helpers

    private static String normaliseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "scope 必须为 PLAN 或 RELEASE",
                    Map.of("field", "scope"));
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!SCOPE_PLAN.equals(value) && !SCOPE_RELEASE.equals(value)) {
            throw new ApiException(400, "VALIDATION_FAILED", "scope 必须为 PLAN 或 RELEASE",
                    Map.of("field", "scope", "value", raw));
        }
        return value;
    }

    private static String chooseConclusion(String suggested, String requested) {
        if (requested == null || requested.isBlank()) {
            return suggested;
        }
        String value = requested.trim().toUpperCase(Locale.ROOT);
        if (rank(value) < 0) {
            throw new ApiException(400, "VALIDATION_FAILED", "结论必须为 PASS / CONDITIONAL / FAIL",
                    Map.of("field", "conclusion", "value", requested));
        }
        if (rank(value) > rank(suggested)) {
            throw new ApiException(409, "CONCLUSION_UPGRADE_FORBIDDEN",
                    "结论只能下调，不能上调", Map.of("from", suggested, "to", value));
        }
        return value;
    }

    /** Higher is better: PASS 3 > CONDITIONAL 2 > FAIL 1. Unknown = -1. */
    public static int rank(String conclusion) {
        return switch (conclusion == null ? "" : conclusion.trim().toUpperCase(Locale.ROOT)) {
            case "PASS" -> 3;
            case "CONDITIONAL" -> 2;
            case "FAIL" -> 1;
            default -> -1;
        };
    }

    /** {@code SELECT k, COUNT(*) n} rolled up into {@code [{key, count}]}. */
    private List<Map<String, Object>> group(String sql, Map<String, Object> params) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbcRead.list(sql, params)) {
            Map<String, Object> node = new LinkedHashMap<>();
            Object key = Rows.object(row, "k");
            node.put("key", key == null ? null : key.toString());
            node.put("count", Rows.toLong(Rows.object(row, "n"), 0L));
            out.add(node);
        }
        return out;
    }

    private static Map<String, Object> risk(String code, String level, String text) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("code", code);
        node.put("level", level);
        node.put("text", text);
        return node;
    }

    private static void accumulate(Map<String, long[]> target, String result, long count) {
        long[] slot = target.computeIfAbsent(result, key -> new long[1]);
        slot[0] += count;
    }

    private static Map<String, Object> counts(Map<String, long[]> source) {
        long planned = 0;
        long executed = 0;
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("PASS", "FAIL", "BLOCKED", "SKIP", "NOT_RUN")) {
            long value = source.containsKey(key) ? source.get(key)[0] : 0L;
            out.put(key, value);
            planned += value;
            if (!"NOT_RUN".equals(key)) {
                executed += value;
            }
        }
        out.put("planned", planned);
        out.put("executed", executed);
        return out;
    }

    private List<Long> ids(String sql, List<Long> planIds) {
        List<Long> out = new ArrayList<>();
        if (planIds.isEmpty()) {
            return out;
        }
        for (Map<String, Object> row : jdbcRead.list(sql, Map.of("planIds", planIds))) {
            out.add(Rows.toLong(Rows.object(row, "id"), 0L));
        }
        return out;
    }

    private static List<Long> trim(List<Long> ids) {
        return ids.size() <= DETAIL_LIMIT ? ids : new ArrayList<>(ids.subList(0, DETAIL_LIMIT));
    }

    private static Map<String, String> trimResults(Map<String, String> results) {
        if (results.size() <= DETAIL_LIMIT) {
            return results;
        }
        Map<String, String> out = new LinkedHashMap<>();
        int kept = 0;
        for (Map.Entry<String, String> entry : results.entrySet()) {
            if (kept++ >= DETAIL_LIMIT) {
                break;
            }
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private List<String> caseCodes(Set<Long> ids) {
        List<String> out = new ArrayList<>();
        if (ids.isEmpty()) {
            return out;
        }
        List<Long> slice = new ArrayList<>(ids).subList(0, Math.min(200, ids.size()));
        for (Map<String, Object> row : jdbcRead.list("""
                SELECT c.id AS id, c.code AS code
                FROM test_case c
                WHERE /*scope:c*/ AND c.id IN (:ids)
                """, Map.of("ids", slice))) {
            out.add(Rows.str(row, "code"));
        }
        return out;
    }

    private List<String> defectCodes(Set<Long> ids) {
        List<String> out = new ArrayList<>();
        if (ids.isEmpty()) {
            return out;
        }
        List<Long> slice = new ArrayList<>(ids).subList(0, Math.min(200, ids.size()));
        for (Map<String, Object> row : jdbcRead.list("""
                SELECT d.id AS id, d.code AS code
                FROM defect d
                WHERE /*scope:d*/ AND d.id IN (:ids)
                """, Map.of("ids", slice))) {
            out.add(Rows.str(row, "code"));
        }
        return out;
    }

    private static long caseIdOfKey(String key) {
        int idx = key.indexOf(':');
        String id = idx < 0 ? key : key.substring(0, idx);
        Long parsed = Rows.toLong(id);
        return parsed == null ? 0L : parsed;
    }

    private static String caseVersionOf(Map<String, String> results, long caseId) {
        for (String key : results.keySet()) {
            if (key.startsWith(caseId + ":")) {
                return key.substring(key.indexOf(':') + 1);
            }
        }
        return "1";
    }

    private static Map<String, Object> section(Map<String, Object> snapshot, String name) {
        Object value = snapshot.get(name);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), item));
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static List<Long> longList(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Long value = Rows.toLong(item);
                if (value != null) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value == null ? null : value.toString()));
        return out;
    }

    private static BigDecimal decimal(Object raw) {
        try {
            return Rows.toDecimal(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean truthy(Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        return raw != null && Boolean.parseBoolean(raw.toString());
    }

    private static String nullSafe(Object value) {
        return value == null ? null : value.toString();
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static BigDecimal difference(BigDecimal to, BigDecimal from) {
        if (to == null || from == null) {
            return null;
        }
        return to.subtract(from);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }

    private static LocalDateTime dateTime(Object value) {
        return Rows.toDateTime(value);
    }

    private static String text(Object value) {
        LocalDateTime dateTime = Rows.toDateTime(value);
        return dateTime == null ? null : dateTime.toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialise report snapshot", ex);
        }
    }
}
