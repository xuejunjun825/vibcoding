package com.example.req.review;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.StateTables;
import com.example.platform.contract.quality.GateSnapshot;
import com.example.req.integration.DeliverQualityGateClient;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseService;
import com.example.req.requirement.RequirementErrors;
import com.example.req.review.ReleaseReviewViews.ConfirmReview;
import com.example.req.review.ReleaseReviewViews.CreateReview;
import com.example.req.review.ReleaseReviewViews.ReviewDetail;
import com.example.req.review.ReleaseReviewViews.ReviewPage;
import com.example.req.review.ReleaseReviewViews.ReviewRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 发布 Go/No-Go 评审（TECHNICAL §5.3 / §6.9，AC-10）。
 *
 * <p>评审属 req 的发布决策域：它与发布单同库、同状态机（{@code StateTables.REVIEW}：
 * RECORDED --confirm--> CONFIRMED），质量事实只经 {@link GateSnapshot} 这条只读通道进来，
 * 不在这里重算门禁。每次评审都固化当时的最新门禁快照 id（{@code gate_evaluated} 表示当时是否已有快照），
 * 这样几个月后回看仍能说清"当时放行依据是什么"。</p>
 *
 * <p>决策三态：{@code GO} / {@code NO_GO} / {@code CONDITIONAL}。CONDITIONAL 必须带条件，
 * 否则它就是一张没有内容的放行条——服务端硬判 400 {@code WAIVER_FIELDS_REQUIRED}，
 * 而不是靠前端把"保存"按钮置灰。</p>
 *
 * <p>确认只允许 PMO / 平台管理员，重复确认幂等（返回当前状态，不报 409）：发布决策被双击
 * 不该变成一次错误。</p>
 */
@Service
public class ReleaseReviewService {

    public static final String DECISION_GO = "GO";
    public static final String DECISION_NO_GO = "NO_GO";
    public static final String DECISION_CONDITIONAL = "CONDITIONAL";

    private static final Set<String> DECISIONS = Set.of(DECISION_GO, DECISION_NO_GO, DECISION_CONDITIONAL);
    private static final List<String> CONFIRM_ROLES = List.of("PMO", "PLATFORM_ADMIN");
    private static final String BIZ_TYPE = "RELEASE_REVIEW";
    private static final int PARTICIPANTS_MAX = 1000;
    private static final int CONDITIONS_MAX = 1000;
    private static final int DOC_PATH_MAX = 300;

    private final ReleaseReviewRepository reviews;
    private final ReleaseService releaseService;
    private final DeliverQualityGateClient gateClient;
    private final AuditService auditService;
    /** 只用来存参会人/风险/缺陷的原始 JSON；req-service 不注册 ObjectMapper bean，故自带一个。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    public ReleaseReviewService(ReleaseReviewRepository reviews,
                                ReleaseService releaseService,
                                DeliverQualityGateClient gateClient,
                                AuditService auditService) {
        this.reviews = reviews;
        this.releaseService = releaseService;
        this.gateClient = gateClient;
        this.auditService = auditService;
    }

    @Transactional
    public ReviewDetail create(long releaseId, CreateReview cmd) {
        DataScope scope = TenantContext.require();
        ReleaseEntity release = releaseService.requireVisible(releaseId);
        CreateReview request = cmd == null
                ? new CreateReview(null, null, null, null, null, null, null, null, null, null)
                : cmd;

        String decision = normaliseDecision(request.decision());
        String conditions = trimToNull(request.conditions());
        if (conditions != null && conditions.length() > CONDITIONS_MAX) {
            throw new ApiException(400, "VALIDATION_FAILED", "conditions 不能超过 " + CONDITIONS_MAX + " 字",
                    Map.of("field", "conditions", "max", CONDITIONS_MAX));
        }
        if (DECISION_CONDITIONAL.equals(decision) && conditions == null) {
            throw new ApiException(400, "WAIVER_FIELDS_REQUIRED",
                    "CONDITIONAL 结论必须填写放行条件（conditions）",
                    Map.of("field", "conditions"));
        }
        String docPath = trimToNull(request.docPath());
        if (docPath != null && docPath.length() > DOC_PATH_MAX) {
            throw new ApiException(400, "VALIDATION_FAILED", "docPath 不能超过 " + DOC_PATH_MAX + " 字",
                    Map.of("field", "docPath", "max", DOC_PATH_MAX));
        }

        LocalDateTime meetingAt = parseMeetingAt(request.meetingAt());
        String participantsJson = json(request.participants(), "participants");
        if (participantsJson != null && participantsJson.length() > PARTICIPANTS_MAX) {
            throw new ApiException(400, "VALIDATION_FAILED",
                    "参会人列表过长（序列化后最多 " + PARTICIPANTS_MAX + " 字符）",
                    Map.of("field", "participants", "max", PARTICIPANTS_MAX));
        }
        String risksJson = json(request.risks(), "risks");
        String openDefectsJson = json(request.openDefects(), "openDefects");

        // 门禁事实只读：取最新快照，取不到（404/超时）就是"当时没有可依据的门禁"，gateEvaluated=false。
        GateSnapshot snapshot = gateClient.latest(releaseId);

        ReleaseReviewEntity entity = new ReleaseReviewEntity();
        entity.setTenantId(scope.tenantId());
        entity.setReleaseId(release.getId());
        entity.setDeployOrderId(request.deployOrderId() != null
                ? request.deployOrderId()
                : snapshot != null && snapshot.deployOrderId() > 0 ? snapshot.deployOrderId() : null);
        entity.setGateSnapshotId(snapshot == null ? null : snapshot.snapshotId());
        entity.setGateEvaluated(snapshot == null ? 0 : 1);
        entity.setReportId(request.reportId());
        entity.setReportVersionNo(request.reportVersionNo());
        entity.setMeetingAt(meetingAt);
        entity.setParticipantsJson(participantsJson);
        entity.setRisksJson(risksJson);
        entity.setOpenDefectsJson(openDefectsJson);
        entity.setDecision(decision);
        entity.setConditions(conditions);
        entity.setDocPath(docPath);
        entity.setCreatedBy(scope.userId());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setStatus("RECORDED");
        ReleaseReviewEntity saved = reviews.save(entity);

        auditService.record("CREATE", BIZ_TYPE, saved.getId(), Map.of(
                "releaseId", releaseId,
                "decision", decision,
                "gateEvaluated", snapshot != null,
                "gateSnapshotId", String.valueOf(saved.getGateSnapshotId())));
        return detailOf(saved);
    }

    @Transactional(readOnly = true)
    public ReviewPage list(long releaseId, String decision, int page, int size) {
        DataScope scope = TenantContext.require();
        releaseService.requireVisible(releaseId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String decisionFilter = decision == null || decision.isBlank()
                ? null : normaliseDecision(decision);
        List<ReviewRow> rows = new ArrayList<>();
        for (ReleaseReviewEntity entity : reviews.findByTenantIdAndReleaseIdOrderByIdDesc(scope.tenantId(), releaseId)) {
            if (decisionFilter != null && !decisionFilter.equals(entity.getDecision())) {
                continue;
            }
            rows.add(rowOf(entity));
        }
        int from = Math.min((safePage - 1) * safeSize, rows.size());
        int to = Math.min(from + safeSize, rows.size());
        return ReviewPage.of(List.copyOf(rows.subList(from, to)), rows.size(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public ReviewDetail get(long releaseId, long reviewId) {
        ReleaseReviewEntity entity = requireReview(releaseId, reviewId);
        return detailOf(entity);
    }

    @Transactional
    public ReviewDetail confirm(long releaseId, long reviewId, ConfirmReview cmd) {
        DataScope scope = TenantContext.require();
        requireConfirmer();
        ReleaseReviewEntity entity = requireReview(releaseId, reviewId);
        if ("CONFIRMED".equals(entity.getStatus())) {
            return detailOf(entity);
        }
        String target = StateTables.target(StateTables.REVIEW, entity.getStatus(), "confirm");
        if (target == null) {
            throw RequirementErrors.illegalTransition(StateTables.REVIEW, entity.getStatus(), "confirm",
                    List.copyOf(StateTables.actions(StateTables.REVIEW, entity.getStatus())));
        }
        entity.setStatus(target);
        entity.setConfirmedBy(scope.userId());
        entity.setConfirmedAt(LocalDateTime.now());
        ReleaseReviewEntity saved = reviews.save(entity);
        String note = cmd == null ? null : trimToNull(cmd.note());
        auditService.record("CONFIRM", BIZ_TYPE, saved.getId(), Map.of(
                "releaseId", releaseId,
                "decision", String.valueOf(saved.getDecision()),
                "note", note == null ? "" : note));
        return detailOf(saved);
    }

    /**
     * 该版本最近一条评审（按 id 倒序），没有则 {@code null}。
     *
     * <p>供发布单提交时判 NO-GO 使用（AC-10）：提交侧只需要读结论，不应自己再查一遍表，
     * 否则"哪个算最新"会出现第二套定义。</p>
     */
    @Transactional(readOnly = true)
    public ReviewRow latest(long releaseId) {
        DataScope scope = TenantContext.require();
        List<ReleaseReviewEntity> rows =
                reviews.findByTenantIdAndReleaseIdOrderByIdDesc(scope.tenantId(), releaseId);
        return rows.isEmpty() ? null : rowOf(rows.get(0));
    }

    private ReleaseReviewEntity requireReview(long releaseId, long reviewId) {
        DataScope scope = TenantContext.require();
        releaseService.requireVisible(releaseId);
        ReleaseReviewEntity entity = reviews.findByIdAndTenantId(reviewId, scope.tenantId())
                .orElseThrow(() -> RequirementErrors.notFound("评审", reviewId));
        if (entity.getReleaseId() == null || entity.getReleaseId() != releaseId) {
            throw RequirementErrors.notFound("评审", reviewId);
        }
        return entity;
    }

    private static void requireConfirmer() {
        List<String> roles = TenantContext.roles();
        for (String role : CONFIRM_ROLES) {
            if (roles.contains(role)) {
                return;
            }
        }
        throw new ApiException(403, "SCOPE_DENIED", "只有 PMO 或平台管理员可以确认发布结论",
                Map.of("allowedRoles", CONFIRM_ROLES, "field", "confirm"));
    }

    private static String normaliseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "decision 必填（GO / NO_GO / CONDITIONAL）",
                    Map.of("field", "decision", "allowed", List.copyOf(DECISIONS)));
        }
        String value = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!DECISIONS.contains(value)) {
            throw new ApiException(400, "VALIDATION_FAILED", "decision 只能是 GO / NO_GO / CONDITIONAL",
                    Map.of("field", "decision", "allowed", List.copyOf(DECISIONS), "value", raw));
        }
        return value;
    }

    private static LocalDateTime parseMeetingAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDateTime.now();
        }
        String value = raw.trim();
        try {
            return OffsetDateTime.parse(value)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试下一种形状
        }
        try {
            return Instant.parse(value)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地日期时间
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ApiException(400, "VALIDATION_FAILED",
                    "meetingAt 不是合法的 ISO-8601 时间：" + value,
                    Map.of("field", "meetingAt", "value", value));
        }
    }

    private String json(Object value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(400, "VALIDATION_FAILED",
                    field + " 不是合法的 JSON 值", Map.of("field", field));
        }
    }

    private Object readJson(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return "participants".equals(field) ? List.of() : null;
        }
        try {
            return JSON.readValue(raw, Object.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ReviewRow rowOf(ReleaseReviewEntity entity) {
        return new ReviewRow(
                entity.getId(),
                entity.getReleaseId(),
                entity.getDeployOrderId(),
                entity.getGateSnapshotId(),
                entity.getReportId(),
                entity.getReportVersionNo(),
                entity.getMeetingAt(),
                entity.getDecision(),
                entity.getConditions(),
                entity.getGateEvaluated() != null && entity.getGateEvaluated() != 0,
                entity.getStatus(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getConfirmedBy(),
                entity.getConfirmedAt());
    }

    private ReviewDetail detailOf(ReleaseReviewEntity entity) {
        GateSnapshot snapshot = null;
        if (entity.getGateSnapshotId() != null) {
            GateSnapshot latest = gateClient.latest(entity.getReleaseId());
            if (latest != null && latest.snapshotId() == entity.getGateSnapshotId()) {
                snapshot = latest;
            }
        }
        ReviewRow row = rowOf(entity);
        return new ReviewDetail(
                row.id(),
                row.releaseId(),
                row.deployOrderId(),
                row.gateSnapshotId(),
                row.reportId(),
                row.reportVersionNo(),
                row.meetingAt(),
                row.decision(),
                row.conditions(),
                row.gateEvaluated(),
                row.status(),
                row.createdBy(),
                row.createdAt(),
                row.confirmedBy(),
                row.confirmedAt(),
                castList(readJson(entity.getParticipantsJson(), "participants")),
                readJson(entity.getRisksJson(), "risks"),
                readJson(entity.getOpenDefectsJson(), "openDefects"),
                entity.getDocPath(),
                snapshot);
    }

    private static List<Object> castList(Object value) {
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        return List.of();
    }
}
