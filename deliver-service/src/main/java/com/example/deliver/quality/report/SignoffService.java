package com.example.deliver.quality.report;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Three-level report signoff (AC-4.5/4.6/4.8).
 *
 * <p>The order is the contract: {@code TEST_LEAD → PRODUCT → PMO}, one row per version and role
 * ({@code uk_signoff}). Every rule lives here and nowhere else — the controller only maps the
 * payload, the UI only renders {@link ReportViews.SignoffProgress}.</p>
 *
 * <ul>
 *   <li><b>Order</b> — step = approved + 1; signing a later rung first is 409 {@code SIGNOFF_ORDER_VIOLATION}
 *       with the expected role in {@code details}.</li>
 *   <li><b>Idempotency</b> — the same signer on a step that is already approved gets 200 with the
 *       current progress and no new row (the unique key is the backstop for two clicks racing).</li>
 *   <li><b>Reject</b> — the version and the report become {@code REJECTED}; a reason is mandatory; the
 *       next generation supersedes the rejected version.</li>
 *   <li><b>Conclusion</b> — a signer may only lower it (PASS→CONDITIONAL→FAIL); raising is 409
 *       {@code CONCLUSION_UPGRADE_FORBIDDEN} with {@code details.{from,to}}.</li>
 * </ul>
 *
 * <p>The snapshot is never rewritten by a signature: {@code snapshot_json} and {@code hash} stay the
 * bytes the version was generated with, so an exported copy signed later is still verifiable. The
 * signatures are read live from {@code report_signoff} when the document is exported.</p>
 */
@Service
public class SignoffService {

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final ReportService reports;
    private final AuditService audit;
    private final NotificationWriter notifications;

    public SignoffService(ScopedJdbc jdbcRead,
                          NamedParameterJdbcTemplate jdbc,
                          ReportService reports,
                          AuditService audit,
                          NotificationWriter notifications) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.reports = reports;
        this.audit = audit;
        this.notifications = notifications;
    }

    @Transactional
    public ReportViews.SignoffProgress sign(long reportId, int versionNo, ReportViews.SignCommand cmd) {
        DataScope scope = TenantContext.require();
        String decision = normaliseDecision(cmd == null ? null : cmd.decision());
        Map<String, Object> report = reports.requireReport(reportId);
        Map<String, Object> version = reports.requireVersion(reportId, versionNo);
        String versionStatus = Rows.str(version, "status", "SIGNING");
        int currentVersion = reports.currentVersion(report);

        if (versionNo < currentVersion) {
            throw new ApiException(409, "REPORT_VERSION_SUPERSEDED",
                    "该版本已被新版本取代，只能查看", Map.of("versionNo", versionNo,
                            "currentVersion", currentVersion, "status", versionStatus));
        }
        if ("SIGNED".equals(versionStatus)) {
            throw new ApiException(409, "REPORT_ALREADY_SIGNED", "该版本已完成签署，不能再改",
                    Map.of("versionNo", versionNo));
        }
        if ("REJECTED".equals(versionStatus)) {
            throw new ApiException(409, "REPORT_REJECTED", "该版本已被驳回，需重新生成报告",
                    Map.of("versionNo", versionNo));
        }
        if ("SUPERSEDED".equals(versionStatus)) {
            throw new ApiException(409, "REPORT_VERSION_SUPERSEDED", "该版本已被取代，只能查看",
                    Map.of("versionNo", versionNo));
        }

        List<Map<String, Object>> rows = signoffRows(reportId, versionNo);
        Map<String, Map<String, Object>> byRole = byRole(rows);
        int approved = approvedCount(byRole);
        int step = approved + 1;
        if (step > ReportService.SIGN_LEVELS.size()) {
            throw new ApiException(409, "REPORT_ALREADY_SIGNED", "该版本已完成签署",
                    Map.of("versionNo", versionNo));
        }
        ReportService.SignoffLevel expected = ReportService.SIGN_LEVELS.get(step - 1);

        // Re-signing the rung that is already approved is a no-op, not a second row.
        Map<String, Object> existing = byRole.get(expected.role());
        if (existing != null && "APPROVE".equals(Rows.str(existing, "decision"))) {
            return reports.progress(reportId, versionNo, versionStatus);
        }

        // Re-signing is a no-op, not an out-of-order attempt: check the caller's own row first, so a
        // second click on the same version answers 200 with the live progress (AC-4.5).
        if (signerAlreadySigned(rows, scope.userId())) {
            return reports.progress(reportId, versionNo, versionStatus,
                    Rows.str(version, "conclusion"));
        }

        boolean planOwner = isPlanOwner(report, scope);
        if (!actsAs(expected.role(), planOwner)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("expectedRole", expected.role());
            detail.put("expectedRoleLabel", expected.label());
            detail.put("expectedOrderNo", expected.orderNo());
            detail.put("approved", approved);
            detail.put("roles", TenantContext.roles());
            throw new ApiException(409, "SIGNOFF_ORDER_VIOLATION",
                    "会签必须按顺序进行：当前应由「" + expected.label() + "」签署", detail);
        }

        if ("REJECT".equals(decision)) {
            if (cmd.comment() == null || cmd.comment().isBlank()) {
                throw new ApiException(400, "SIGNOFF_COMMENT_REQUIRED", "驳回必须填写原因",
                        Map.of("field", "comment"));
            }
            insertSignoff(scope, reportId, versionNo, expected, "REJECT", cmd.comment());
            jdbc.update("""
                    UPDATE test_report_version
                       SET status = 'REJECTED'
                     WHERE report_id = :reportId AND version_no = :versionNo AND tenant_id = :tenantId
                    """, new MapSqlParameterSource()
                    .addValue("reportId", reportId)
                    .addValue("versionNo", versionNo)
                    .addValue("tenantId", scope.tenantId()));
            jdbc.update("""
                    UPDATE test_report
                       SET status = 'REJECTED', updated_at = NOW(3), updated_by = :userId
                     WHERE id = :reportId AND tenant_id = :tenantId
                    """, new MapSqlParameterSource()
                    .addValue("userId", scope.userId())
                    .addValue("reportId", reportId)
                    .addValue("tenantId", scope.tenantId()));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("versionNo", versionNo);
            detail.put("role", expected.role());
            detail.put("orderNo", expected.orderNo());
            detail.put("decision", "REJECT");
            detail.put("comment", cmd.comment());
            audit.record("report.signoff.reject", "test_report", reportId, detail);
            notifyCreator(report, "测试报告被驳回", "报告 v" + versionNo + " 被「"
                    + expected.label() + "」驳回：" + cmd.comment(), reportId);
            return reports.progress(reportId, versionNo, "REJECTED");
        }

        String from = Rows.str(version, "conclusion");
        String to = lowerConclusion(from, cmd.conclusion());
        if (!to.equals(from)) {
            jdbc.update("""
                    UPDATE test_report_version
                       SET conclusion = :conclusion
                     WHERE report_id = :reportId AND version_no = :versionNo AND tenant_id = :tenantId
                    """, new MapSqlParameterSource()
                    .addValue("conclusion", to)
                    .addValue("reportId", reportId)
                    .addValue("versionNo", versionNo)
                    .addValue("tenantId", scope.tenantId()));
        }

        try {
            insertSignoff(scope, reportId, versionNo, expected, "APPROVE", cmd.comment());
        } catch (DuplicateKeyException race) {
            // Two clicks landed together: the unique key kept one row, report the live progress.
            return reports.progress(reportId, versionNo, versionStatus);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("versionNo", versionNo);
        detail.put("role", expected.role());
        detail.put("orderNo", expected.orderNo());
        detail.put("decision", "APPROVE");
        detail.put("conclusionFrom", from);
        detail.put("conclusionTo", to);
        putIfPresent(detail, "comment", cmd.comment());
        audit.record("report.signoff.approve", "test_report", reportId, detail);

        int nowApproved = approved + 1;
        if (nowApproved >= ReportService.SIGN_LEVELS.size()) {
            LocalDateTime signedAt = LocalDateTime.now();
            jdbc.update("""
                    UPDATE test_report_version
                       SET status = 'SIGNED', signed_at = :signedAt
                     WHERE report_id = :reportId AND version_no = :versionNo AND tenant_id = :tenantId
                    """, new MapSqlParameterSource()
                    .addValue("signedAt", signedAt)
                    .addValue("reportId", reportId)
                    .addValue("versionNo", versionNo)
                    .addValue("tenantId", scope.tenantId()));
            // Older signed versions of the same report become read-only history (AC-4.6).
            jdbc.update("""
                    UPDATE test_report_version
                       SET status = 'SUPERSEDED'
                     WHERE report_id = :reportId AND version_no < :versionNo
                       AND tenant_id = :tenantId AND status = 'SIGNED'
                    """, new MapSqlParameterSource()
                    .addValue("reportId", reportId)
                    .addValue("versionNo", versionNo)
                    .addValue("tenantId", scope.tenantId()));
            jdbc.update("""
                    UPDATE test_report
                       SET status = 'SIGNED', current_version = :versionNo,
                           updated_at = NOW(3), updated_by = :userId
                     WHERE id = :reportId AND tenant_id = :tenantId
                    """, new MapSqlParameterSource()
                    .addValue("versionNo", versionNo)
                    .addValue("userId", scope.userId())
                    .addValue("reportId", reportId)
                    .addValue("tenantId", scope.tenantId()));
            notifyCreator(report, "测试报告已生效", "报告 v" + versionNo + " 已完成三级会签", reportId);
            return reports.progress(reportId, versionNo, "SIGNED");
        }

        jdbc.update("""
                UPDATE test_report
                   SET status = 'SIGNING', current_version = :versionNo,
                       updated_at = NOW(3), updated_by = :userId
                 WHERE id = :reportId AND tenant_id = :tenantId
                """, new MapSqlParameterSource()
                .addValue("versionNo", versionNo)
                .addValue("userId", scope.userId())
                .addValue("reportId", reportId)
                .addValue("tenantId", scope.tenantId()));
        ReportService.SignoffLevel next = ReportService.SIGN_LEVELS.get(nowApproved);
        notifyCreator(report, "测试报告待签署",
                "报告 v" + versionNo + " 已由「" + expected.label() + "」签署，等待「"
                        + next.label() + "」签署", reportId);
        return reports.progress(reportId, versionNo, "SIGNING");
    }

    /** Live progress of one version, readable without the write path. */
    @Transactional(readOnly = true)
    public ReportViews.SignoffProgress progress(long reportId, int versionNo) {
        Map<String, Object> version = reports.requireVersion(reportId, versionNo);
        return reports.progress(reportId, versionNo, Rows.str(version, "status", "SIGNING"));
    }

    // ================================================================== internals

    private List<Map<String, Object>> signoffRows(long reportId, int versionNo) {
        return jdbcRead.list("""
                SELECT s.sign_role AS sign_role, s.signer_id AS signer_id, s.decision AS decision,
                       s.`comment` AS comment, s.signed_at AS signed_at, s.order_no AS order_no
                FROM report_signoff s
                JOIN test_report r ON r.id = s.report_id AND r.tenant_id = s.tenant_id
                WHERE /*scope:r*/ AND s.report_id = :reportId AND s.version_no = :versionNo
                ORDER BY s.order_no ASC
                """, Map.of("reportId", reportId, "versionNo", versionNo));
    }

    private static Map<String, Map<String, Object>> byRole(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            out.put(Rows.str(row, "sign_role"), row);
        }
        return out;
    }

    private static int approvedCount(Map<String, Map<String, Object>> byRole) {
        int approved = 0;
        for (ReportService.SignoffLevel level : ReportService.SIGN_LEVELS) {
            Map<String, Object> row = byRole.get(level.role());
            if (row != null && "APPROVE".equals(Rows.str(row, "decision"))) {
                approved++;
            }
        }
        return approved;
    }

    private static boolean signerAlreadySigned(List<Map<String, Object>> rows, long userId) {
        for (Map<String, Object> row : rows) {
            Long signer = Rows.toLong(Rows.object(row, "signer_id"));
            if (signer != null && signer == userId && "APPROVE".equals(Rows.str(row, "decision"))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the caller may act for {@code role} on this report — the rule lives in one place. */
    private static boolean actsAs(String role, boolean planOwner) {
        return ReportService.canActAs(role, planOwner);
    }

    private static boolean isPlanOwner(Map<String, Object> report, DataScope scope) {
        Long owner = Rows.toLong(Rows.object(report, "plan_owner_id"));
        return owner != null && owner == scope.userId();
    }

    private void insertSignoff(DataScope scope, long reportId, int versionNo,
                               ReportService.SignoffLevel level, String decision, String comment) {
        jdbc.update("""
                INSERT INTO report_signoff
                    (tenant_id, report_id, version_no, sign_role, signer_id, decision,
                     `comment`, signed_at, order_no)
                VALUES
                    (:tenantId, :reportId, :versionNo, :signRole, :signerId, :decision,
                     :comment, :signedAt, :orderNo)
                """, new MapSqlParameterSource()
                .addValue("tenantId", scope.tenantId())
                .addValue("reportId", reportId)
                .addValue("versionNo", versionNo)
                .addValue("signRole", level.role())
                .addValue("signerId", scope.userId())
                .addValue("decision", decision)
                .addValue("comment", comment)
                .addValue("signedAt", LocalDateTime.now())
                .addValue("orderNo", level.orderNo()));
    }

    private static String normaliseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "decision 必须为 APPROVE 或 REJECT",
                    Map.of("field", "decision"));
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(value) && !"REJECT".equals(value)) {
            throw new ApiException(400, "VALIDATION_FAILED", "decision 必须为 APPROVE 或 REJECT",
                    Map.of("field", "decision", "value", raw));
        }
        return value;
    }

    /** Signers may only lower a conclusion: PASS 3 > CONDITIONAL 2 > FAIL 1. */
    private static String lowerConclusion(String current, String requested) {
        String from = current == null ? "PASS" : current.trim().toUpperCase(Locale.ROOT);
        if (requested == null || requested.isBlank()) {
            return from;
        }
        String to = requested.trim().toUpperCase(Locale.ROOT);
        int fromRank = ReportService.rank(from);
        int toRank = ReportService.rank(to);
        if (toRank < 0) {
            throw new ApiException(400, "VALIDATION_FAILED", "结论必须为 PASS / CONDITIONAL / FAIL",
                    Map.of("field", "conclusion", "value", requested));
        }
        if (toRank > fromRank) {
            throw new ApiException(409, "CONCLUSION_UPGRADE_FORBIDDEN", "会签只能把结论下调，不能上调",
                    Map.of("from", from, "to", to));
        }
        return to;
    }

    private void notifyCreator(Map<String, Object> report, String title, String content, long reportId) {
        Long creator = Rows.toLong(Rows.object(report, "created_by"));
        if (creator == null) {
            return;
        }
        List<Long> targets = new ArrayList<>();
        targets.add(creator);
        for (Long target : targets) {
            try {
                notifications.send(target, "REPORT_SIGNOFF", title, content, "test_report", reportId);
            } catch (RuntimeException ignored) {
                // Notification failure must not roll back a signature.
            }
        }
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
