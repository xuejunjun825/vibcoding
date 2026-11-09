package com.example.deliver.review;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Case reviews (AC-14): a review is pinned to the case version it judged.
 *
 * <p>Three rules carry the AC and all three live here, not in the controller:</p>
 * <ul>
 *   <li><b>Pinned version</b> — the submission stores {@code test_case.current_version} read under
 *       {@code FOR UPDATE}, so two concurrent saves cannot review a version that no longer exists.</li>
 *   <li><b>Version bump invalidates</b> — {@link #invalidate(long, int, String)} runs inside the
 *       same transaction as {@code TestCaseService.update}; a window where the case is v2 and its
 *       v1 conclusion still reads "valid" is exactly the state the AC forbids.</li>
 *   <li><b>No workflow ticket</b> — a review is a record plus two audit rows, never a
 *       {@code wf_definition.biz_type}; approval centres must not grow a fifth todo stream.</li>
 * </ul>
 *
 * <p>The validation guards are package-private static so the rules are unit-testable without a
 * database (see {@code CaseReviewRulesTest}).</p>
 */
@Service
public class CaseReviewService {

    public static final int MIN_COMMENT_LENGTH = 10;
    private static final int MAX_COMMENT_LENGTH = 2000;

    private static final List<String> CONCLUSIONS = List.of("PASS", "NEED_CHANGE", "REJECT");
    private static final List<String> STATUSES = List.of("SUBMITTED", "DONE", "INVALIDATED");
    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_INVALIDATED = "INVALIDATED";

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public CaseReviewService(ScopedJdbc jdbcRead, NamedParameterJdbcTemplate jdbc,
                             AuditService audit) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Wire shape; field names are the frontend contract (web/src/api/deliver.ts). */
    public record ReviewRow(long id,
                            long caseId,
                            String caseCode,
                            String caseTitle,
                            int caseVersion,
                            Long reviewerId,
                            String reviewerName,
                            String status,
                            String conclusion,
                            String comment,
                            String invalidReason,
                            LocalDateTime createdAt,
                            LocalDateTime decidedAt) {
    }

    public record SubmitCmd(Integer caseVersion,
                            Long reviewerId,
                            String conclusion,
                            String comment) {
    }

    public record DecideCmd(String conclusion, String comment) {
    }

    // ------------------------------------------------------------------ write

    /**
     * Open a review, pinning {@code test_case.current_version}.
     *
     * <p>{@code reviewerId} defaults to the caller: a reviewer submitting their own review is the
     * common case, and defaulting avoids a 500 when a form omits the field.</p>
     */
    @Transactional
    public ReviewRow submit(long caseId, SubmitCmd cmd) {
        DataScope scope = TenantContext.require();
        requireFunction("CASE_REVIEW_SUBMIT", "发起用例评审");
        SubmitCmd command = cmd == null ? new SubmitCmd(null, null, null, null) : cmd;

        Map<String, Object> row = jdbcRead.one("""
                SELECT c.id AS id, c.code AS code, c.title AS title,
                       c.current_version AS current_version
                FROM test_case c
                WHERE c.id = :caseId AND c.deleted_at IS NULL AND /*scope:c*/
                FOR UPDATE
                """, Map.of("caseId", caseId));
        if (row == null) {
            throw new ApiException(404, "TEST_CASE_NOT_FOUND",
                    "用例不存在或不在你的数据范围内", Map.of("caseId", caseId));
        }
        long tenantId = scope.tenantId();
        int currentVersion = Rows.toInt(Rows.object(row, "current_version"), 1);
        requireFreshVersion(command.caseVersion() == null ? currentVersion : command.caseVersion(),
                currentVersion);

        String conclusion = command.conclusion() == null || command.conclusion().isBlank()
                ? null : normaliseConclusion(command.conclusion());
        String comment = cleanComment(command.comment());
        requireComment(conclusion, comment);
        long reviewerId = command.reviewerId() == null ? scope.userId() : command.reviewerId();

        List<Map<String, Object>> active = jdbc.queryForList("""
                SELECT id FROM test_case_review
                WHERE tenant_id = :tenantId AND case_id = :caseId AND case_version = :version
                  AND status IN ('SUBMITTED', 'DONE')
                LIMIT 1
                FOR UPDATE
                """, Map.of("tenantId", tenantId, "caseId", caseId, "version", currentVersion));
        requireNoActiveReview(active.isEmpty() ? null : Rows.lng(active.get(0), "id"));

        jdbc.update("""
                INSERT INTO test_case_review (tenant_id, case_id, case_version, conclusion, comment,
                                              reviewer_id, status, created_at)
                VALUES (:tenantId, :caseId, :version, :conclusion, :comment, :reviewerId,
                        'SUBMITTED', :now)
                """, Map.of("tenantId", tenantId, "caseId", caseId, "version", currentVersion,
                "conclusion", conclusion == null ? "" : conclusion,
                "comment", comment == null ? "" : comment,
                "reviewerId", reviewerId, "now", LocalDateTime.now()));
        long reviewId = lastInsertId();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("caseId", caseId);
        detail.put("caseVersion", currentVersion);
        detail.put("reviewerId", reviewerId);
        detail.put("conclusion", conclusion);
        audit.record("CASE_REVIEW_SUBMIT", "TEST_CASE_REVIEW", reviewId, detail);

        return requireReview(reviewId, tenantId);
    }

    /**
     * Conclude a review. Only the assigned reviewer may do it, and only while the judged version is
     * still current — a v1 conclusion must never be presented as a v2 approval.
     */
    @Transactional
    public ReviewRow decide(long reviewId, DecideCmd cmd) {
        DataScope scope = TenantContext.require();
        requireFunction("CASE_REVIEW_DECIDE", "下用例评审结论");
        if (cmd == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "请求体不能为空", Map.of());
        }
        long tenantId = scope.tenantId();
        Map<String, Object> review = lockReview(reviewId, tenantId);
        if (review == null) {
            throw new ApiException(404, "CASE_REVIEW_NOT_FOUND",
                    "评审不存在或不在你的数据范围内", Map.of("reviewId", reviewId));
        }
        String status = Rows.str(review, "status", STATUS_SUBMITTED);
        requireOpenReview(status, Rows.str(review, "invalid_reason"));

        Long reviewerId = Rows.lng(review, "reviewer_id");
        requireReviewer(reviewerId == null ? 0L : reviewerId, scope.userId());

        long caseId = Rows.toLong(Rows.object(review, "case_id"), 0L);
        int reviewVersion = Rows.toInt(Rows.object(review, "case_version"), 1);
        List<Map<String, Object>> caseRows = jdbc.queryForList("""
                SELECT current_version FROM test_case
                WHERE id = :caseId AND tenant_id = :tenantId AND deleted_at IS NULL
                """, Map.of("caseId", caseId, "tenantId", tenantId));
        if (caseRows.isEmpty()) {
            throw new ApiException(404, "TEST_CASE_NOT_FOUND", "用例不存在",
                    Map.of("caseId", caseId));
        }
        requireFreshVersion(reviewVersion,
                Rows.toInt(Rows.object(caseRows.get(0), "current_version"), 1));

        String conclusion = normaliseConclusion(cmd.conclusion());
        String comment = cleanComment(cmd.comment());
        requireComment(conclusion, comment);

        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                UPDATE test_case_review
                SET status = 'DONE', conclusion = :conclusion, comment = :comment,
                    decided_at = :now, decided_by = :decidedBy
                WHERE id = :reviewId AND tenant_id = :tenantId AND status = 'SUBMITTED'
                """, Map.of("conclusion", conclusion, "comment", comment == null ? "" : comment,
                "now", now, "decidedBy", scope.userId(), "reviewId", reviewId,
                "tenantId", tenantId));
        jdbc.update("""
                UPDATE test_case_review SET reviewer_id = COALESCE(reviewer_id, :reviewerId)
                WHERE id = :reviewId AND tenant_id = :tenantId
                """, Map.of("reviewerId", scope.userId(), "reviewId", reviewId,
                "tenantId", tenantId));

        audit.record("CASE_REVIEW_DECIDE", "TEST_CASE_REVIEW", reviewId,
                Map.of("caseId", caseId, "caseVersion", reviewVersion,
                        "conclusion", conclusion));
        return requireReview(reviewId, tenantId);
    }

    /**
     * Mark every review of {@code caseVersion} stale. Called from the case-version bump transaction
     * ({@link Propagation#MANDATORY}), so "case saved" and "old reviews expired" commit together.
     *
     * @return rows invalidated (0 is legal — most versions have no review)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int invalidate(long caseId, int caseVersion, String reason) {
        DataScope scope = TenantContext.require();
        long tenantId = scope.tenantId();
        String invalidReason = reason == null || reason.isBlank() ? "NEW_VERSION" : reason;
        int changed = jdbc.update("""
                UPDATE test_case_review
                SET status = 'INVALIDATED', invalid_reason = :reason
                WHERE tenant_id = :tenantId AND case_id = :caseId AND case_version = :version
                  AND status IN ('SUBMITTED', 'DONE')
                """, Map.of("reason", invalidReason, "tenantId", tenantId, "caseId", caseId,
                "version", caseVersion));
        if (changed > 0) {
            audit.record("CASE_REVIEW_INVALIDATED", "TEST_CASE_REVIEW", caseId,
                    Map.of("caseId", caseId, "caseVersion", caseVersion, "count", changed,
                            "reason", invalidReason));
        }
        return changed;
    }

    // ------------------------------------------------------------------ read

    /** All reviews of one case, newest version first (expired ones stay visible, greyed). */
    @Transactional(readOnly = true)
    public List<ReviewRow> listByCase(long caseId) {
        DataScope scope = TenantContext.require();
        long tenantId = scope.tenantId();
        Map<String, Object> testCase = jdbcRead.one("""
                SELECT c.id AS id FROM test_case c
                WHERE c.id = :caseId AND c.deleted_at IS NULL AND /*scope:c*/
                """, Map.of("caseId", caseId));
        if (testCase == null) {
            throw new ApiException(404, "TEST_CASE_NOT_FOUND",
                    "用例不存在或不在你的数据范围内", Map.of("caseId", caseId));
        }
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT r.id AS id, r.case_id AS case_id, c.code AS case_code, c.title AS case_title,
                       r.case_version AS case_version, r.reviewer_id AS reviewer_id,
                       u.name AS reviewer_name, r.status AS status, r.conclusion AS conclusion,
                       r.comment AS comment, r.invalid_reason AS invalid_reason,
                       r.created_at AS created_at, r.decided_at AS decided_at
                FROM test_case_review r
                JOIN test_case c ON c.id = r.case_id AND c.tenant_id = r.tenant_id
                LEFT JOIN sys_user u ON u.id = r.reviewer_id AND u.tenant_id = r.tenant_id
                WHERE r.case_id = :caseId AND /*scope:r*/ AND /*scope:c*/
                ORDER BY r.case_version DESC, r.id DESC
                """, Map.of("caseId", caseId));
        return rows.stream().map(CaseReviewService::toView).toList();
    }

    /** Read-only cross-case list for the approval centre (no todo, no state change). */
    @Transactional(readOnly = true)
    public PageResponse<ReviewRow> page(String status, int page, int size) {
        TenantContext.require();
        String normalised = null;
        if (status != null && !status.isBlank()) {
            normalised = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(normalised)) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "status 必须为 SUBMITTED/DONE/INVALIDATED",
                        Map.of("field", "status", "value", status));
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", normalised);
        String sql = """
                SELECT r.id AS id, r.case_id AS case_id, c.code AS case_code, c.title AS case_title,
                       r.case_version AS case_version, r.reviewer_id AS reviewer_id,
                       u.name AS reviewer_name, r.status AS status, r.conclusion AS conclusion,
                       r.comment AS comment, r.invalid_reason AS invalid_reason,
                       r.created_at AS created_at, r.decided_at AS decided_at
                FROM test_case_review r
                JOIN test_case c ON c.id = r.case_id AND c.tenant_id = r.tenant_id
                LEFT JOIN sys_user u ON u.id = r.reviewer_id AND u.tenant_id = r.tenant_id
                WHERE /*scope:r*/ AND /*scope:c*/
                """ + (normalised == null ? "" : " AND r.status = :status")
                + " ORDER BY r.id DESC";
        PageResponse<Map<String, Object>> rows = jdbcRead.page(sql, params, page, size);
        List<ReviewRow> items = rows.items().stream().map(CaseReviewService::toView).toList();
        return new PageResponse<>(items, rows.total(), rows.page(), rows.size());
    }

    // ------------------------------------------------------------------ rules (unit-tested)

    /** {@code NEED_CHANGE}/{@code REJECT} need at least {@value #MIN_COMMENT_LENGTH} characters. */
    static void requireComment(String conclusion, String comment) {
        if (!"NEED_CHANGE".equals(conclusion) && !"REJECT".equals(conclusion)) {
            if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
                throw new ApiException(400, "CASE_REVIEW_COMMENT_TOO_LONG",
                        "评审意见不能超过 2000 字", Map.of("max", MAX_COMMENT_LENGTH));
            }
            return;
        }
        if (comment == null || comment.trim().length() < MIN_COMMENT_LENGTH) {
            throw new ApiException(400, "CASE_REVIEW_COMMENT_REQUIRED",
                    "结论为 NEED_CHANGE/REJECT 时必填意见（至少 " + MIN_COMMENT_LENGTH + " 字）",
                    Map.of("minLength", MIN_COMMENT_LENGTH,
                            "length", comment == null ? 0 : comment.trim().length()));
        }
    }

    /** The version being judged must still be the case's current version. */
    static void requireFreshVersion(int reviewedVersion, int currentVersion) {
        if (reviewedVersion != currentVersion) {
            throw new ApiException(409, "CASE_REVIEW_VERSION_STALE",
                    "用例已升级，请评审当前版本", Map.of("reviewedVersion", reviewedVersion,
                            "currentVersion", currentVersion));
        }
    }

    /** One active review per (case, version): MySQL cannot express a partial unique key. */
    static void requireNoActiveReview(Long existingId) {
        if (existingId != null) {
            throw new ApiException(409, "CASE_REVIEW_DUPLICATE",
                    "该用例版本已有一条有效评审", Map.of("reviewId", existingId));
        }
    }

    /** Only the assigned reviewer concludes. */
    static void requireReviewer(long reviewerId, long currentUserId) {
        if (reviewerId == 0L || reviewerId != currentUserId) {
            throw new ApiException(403, "CASE_REVIEW_NOT_REVIEWER",
                    "只有被指派的评审人可以下结论",
                    Map.of("reviewerId", reviewerId, "userId", currentUserId));
        }
    }

    /** A review that already reached a terminal state cannot be decided again. */
    static void requireOpenReview(String status, String invalidReason) {
        if (STATUS_INVALIDATED.equals(status)) {
            throw new ApiException(409, "CASE_REVIEW_INVALIDATED",
                    "该评审已过期（用例已升级），不能再下结论",
                    Map.of("invalidReason", invalidReason == null ? "NEW_VERSION" : invalidReason));
        }
        if (STATUS_DONE.equals(status)) {
            throw new ApiException(409, "CASE_REVIEW_DECIDED",
                    "该评审已有结论，不能重复下结论", Map.of("status", status));
        }
    }

    static String normaliseConclusion(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "结论不能为空",
                    Map.of("field", "conclusion", "allowed", CONCLUSIONS));
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!CONCLUSIONS.contains(value)) {
            throw new ApiException(400, "VALIDATION_FAILED", "结论必须为 PASS/NEED_CHANGE/REJECT",
                    Map.of("field", "conclusion", "value", raw, "allowed", CONCLUSIONS));
        }
        return value;
    }

    // ------------------------------------------------------------------ helpers

    private static String cleanComment(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void requireFunction(String code, String what) {
        if (!TenantContext.can(code)) {
            throw new ApiException(403, "FORBIDDEN", "无权" + what, Map.of("function", code));
        }
    }

    private Map<String, Object> lockReview(long reviewId, long tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, case_id, case_version, reviewer_id, status, invalid_reason, conclusion,
                       comment, created_at, decided_at
                FROM test_case_review
                WHERE id = :reviewId AND tenant_id = :tenantId
                FOR UPDATE
                """, Map.of("reviewId", reviewId, "tenantId", tenantId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ReviewRow requireReview(long reviewId, long tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id AS id, r.case_id AS case_id, c.code AS case_code, c.title AS case_title,
                       r.case_version AS case_version, r.reviewer_id AS reviewer_id,
                       u.name AS reviewer_name, r.status AS status, r.conclusion AS conclusion,
                       r.comment AS comment, r.invalid_reason AS invalid_reason,
                       r.created_at AS created_at, r.decided_at AS decided_at
                FROM test_case_review r
                JOIN test_case c ON c.id = r.case_id AND c.tenant_id = r.tenant_id
                LEFT JOIN sys_user u ON u.id = r.reviewer_id AND u.tenant_id = r.tenant_id
                WHERE r.id = :reviewId AND r.tenant_id = :tenantId
                """, Map.of("reviewId", reviewId, "tenantId", tenantId));
        if (rows.isEmpty()) {
            throw new ApiException(404, "CASE_REVIEW_NOT_FOUND", "评审不存在",
                    Map.of("reviewId", reviewId));
        }
        return toView(rows.get(0));
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
        return id == null ? 0L : id;
    }

    private static ReviewRow toView(Map<String, Object> row) {
        return new ReviewRow(
                Rows.toLong(Rows.object(row, "id"), 0L),
                Rows.toLong(Rows.object(row, "case_id"), 0L),
                Rows.str(row, "case_code"),
                Rows.str(row, "case_title"),
                Rows.toInt(Rows.object(row, "case_version"), 1),
                Rows.lng(row, "reviewer_id"),
                Rows.str(row, "reviewer_name"),
                Rows.str(row, "status", STATUS_SUBMITTED),
                Rows.str(row, "conclusion"),
                Rows.str(row, "comment"),
                Rows.str(row, "invalid_reason"),
                Rows.toDateTime(Rows.object(row, "created_at")),
                Rows.toDateTime(Rows.object(row, "decided_at")));
    }
}
