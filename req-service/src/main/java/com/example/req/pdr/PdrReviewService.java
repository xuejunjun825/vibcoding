package com.example.req.pdr;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.contract.ActionPermissionResolver;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.project.ProjectService;
import com.example.req.requirement.RequirementErrors;
import com.example.req.sys.SysDb;

/**
 * PDR 评审单（r41）。
 *
 * <p>三件事必须一起看，否则每一条单独都像多余的：</p>
 * <ol>
 *   <li><b>评审人快照</b>（{@code reviewer_ids} JSON）：发起时把「谁该评」冻住。项目组之后加人/删人
 *       都不改这张单——否则一张 3 人单在删掉 1 人后突然「全员已评」，结论就假了。</li>
 *   <li><b>意见不唯一</b>（{@code (review_id, reviewer_id)} 故意不做唯一约束）：同一个人可以对一条
 *       需求提 3 条问题（D1）。因此「某人评了没有」= 该人名下 exists 一行，而不是「有几行」。</li>
 *   <li><b>结论按人聚合</b>：MODE_ALL 下把每人的多条意见压成一个立场（任一 REJECT → REJECT，
 *       否则任一 NEED_CHANGE → NEED_CHANGE，否则 PASS），再按人求和。这样多条意见不会放大票数。</li>
 * </ol>
 */
@Service
public class PdrReviewService {

    /** 附件与审计的 bizType（与 {@code AttachmentService} 的白名单取值一致）。 */
    public static final String BIZ_TYPE = "PDR_REVIEW";

    public static final String ST_IN_REVIEW = "IN_REVIEW";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";

    public static final String MODE_ALL = "MODE_ALL";
    public static final String MODE_ANY = "MODE_ANY";

    public static final String DEC_PASS = "PASS";
    public static final String DEC_NEED_CHANGE = "NEED_CHANGE";
    public static final String DEC_REJECT = "REJECT";
    private static final List<String> DECISIONS = List.of(DEC_PASS, DEC_NEED_CHANGE, DEC_REJECT);

    public static final String OP_OPEN = "OPEN";
    public static final String OP_TASKED = "TASKED";
    public static final String OP_RESOLVED = "RESOLVED";

    static final String SRC_PROJECT = "PROJECT";
    static final String SRC_FALLBACK = "FALLBACK";

    private static final String MENU = "pdr.review";
    private static final String ACTION_CONCLUDE = "req.action.pdr_review_conclude";
    private static final String ACTION_START = "req.action.pdr_review_start";

    private static final int NOTE_MAX = 500;
    private static final int COMMENT_MAX = 2000;
    /** 转任务时 title 的缺省取法：意见正文前 N 个字（设计 §4.5）。 */
    private static final int TITLE_CLIP = 40;

    private final SysDb db;
    private final ProjectService projects;
    private final AuditService audit;
    private final NotificationWriter notifications;
    private final ActionPermissionResolver actionPermissions;
    private final PdrTaskClient taskClient;

    public PdrReviewService(SysDb db, ProjectService projects, AuditService audit,
                            NotificationWriter notifications,
                            ActionPermissionResolver actionPermissions, PdrTaskClient taskClient) {
        this.db = db;
        this.projects = projects;
        this.audit = audit;
        this.notifications = notifications;
        this.actionPermissions = actionPermissions;
        this.taskClient = taskClient;
    }

    // ------------------------------------------------------------------ 发起

    /**
     * 发起评审（设计 §5.1 / R2 / R5）。同一需求只允许一张 IN_REVIEW —— 由
     * {@code uk_pdr_in_review} 生成列兜底，这里先查一遍是为了给 409 带上已有 reviewId。
     *
     * <p>评审人来源优先级：显式名单（必须都在项目组）→ 项目组全名单 → 回退「产品经理 + 域负责人」。
     * 回退时响应带 {@code fallback=true}（验收 B5），前端据此提示「评审人来自兜底规则，请核对」。</p>
     */
    @Transactional
    public PdrViews.ReviewView start(long requirementId, PdrViews.StartReview req) {
        DataScope scope = TenantContext.require();
        Req requirement = requireRequirement(scope, requirementId);
        if (!"PDR".equals(requirement.type())) {
            throw PdrErrors.invalidRequirementType(requirement.type());
        }
        findOpen(requirementId).ifPresent(open -> {
            throw PdrErrors.alreadyOpen(requirementId, open.id());
        });

        String mode = normalizeMode(req == null ? null : req.mode());
        String note = trimToNull(req == null ? null : req.note(), NOTE_MAX, "note");
        Timestamp dueAt = parseDueAt(req == null ? null : req.dueAt());

        List<Long> members = requirement.projectId() == null
                ? List.of()
                : projects.memberUserIds(requirement.projectId());
        List<Long> explicit = distinctIds(req == null ? null : req.reviewerIds());
        if (!explicit.isEmpty() && !members.isEmpty()) {
            for (Long reviewerId : explicit) {
                if (!members.contains(reviewerId)) {
                    throw PdrErrors.reviewerNotInProject(reviewerId);
                }
            }
        }

        String source;
        List<Long> reviewers;
        if (!explicit.isEmpty()) {
            reviewers = explicit;
            source = SRC_PROJECT;
        } else if (!members.isEmpty()) {
            reviewers = members;
            source = SRC_PROJECT;
        } else {
            reviewers = fallbackReviewers(requirement);
            if (reviewers.isEmpty()) {
                throw PdrErrors.noReviewer(requirementId);
            }
            source = SRC_FALLBACK;
        }

        long id = db.insert("""
                INSERT INTO pdr_review (tenant_id, requirement_id, project_id, mode, status,
                                        initiator_id, reviewer_ids, reviewer_source, note, due_at,
                                        created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                scope.tenantId(), requirementId, requirement.projectId(), mode, ST_IN_REVIEW,
                scope.userId(), toJsonIds(reviewers), source, note, dueAt,
                scope.userId(), scope.userId());

        audit.record("PDR_REVIEW_START", BIZ_TYPE, id, auditDetail(Map.of(
                "requirementId", requirementId,
                "mode", mode,
                "reviewerSource", source,
                "reviewerCount", reviewers.size())));

        String label = note == null ? "" : "：" + note;
        for (Long reviewerId : reviewers) {
            notifications.send(reviewerId, "PDR_REVIEW_START", "你有新的 PDR 评审",
                    "需求 " + requirement.code() + " 发起了 PDR 评审，请提交评审意见" + label,
                    BIZ_TYPE, id);
        }
        return toView(loadReview(scope, id));
    }

    /** 回退名单：需求的产品经理（reporter）+ 域负责人（biz_domain.owner_user_id），去重去空。 */
    private List<Long> fallbackReviewers(Req requirement) {
        Set<Long> ids = new LinkedHashSet<>();
        if (requirement.reporterId() > 0) {
            ids.add(requirement.reporterId());
        }
        db.<Object>scalars("""
                SELECT owner_user_id FROM biz_domain
                WHERE tenant_id = ? AND id = ? AND owner_user_id IS NOT NULL
                """, TenantContext.require().tenantId(), requirement.domainId())
                .stream().findFirst().map(SysDb::asLong).ifPresent(ids::add);
        return List.copyOf(ids);
    }

    // ------------------------------------------------------------------ 意见

    /**
     * 提交意见（设计 §4.5 / R8）：一次可以多条，{@code seq} 在「本单 × 本人」内递增。
     *
     * <p>提交成功后立即尝试出结论：MODE_ALL 全员到齐才通过，任一 NEED_CHANGE/REJECT 立刻驳回
     * （不必等其余人评完——这是设计 R2 的原话「任一人…则整单 REJECTED」）。</p>
     */
    @Transactional
    public PdrViews.ReviewDetail submitOpinions(long reviewId, PdrViews.SubmitOpinions req) {
        DataScope scope = TenantContext.require();
        ReviewRow review = loadReview(scope, reviewId);
        if (!ST_IN_REVIEW.equals(review.status())) {
            throw PdrErrors.reviewClosed(reviewId, review.status());
        }
        List<PdrViews.OpinionInput> inputs = opinionInputs(req);
        if (inputs.isEmpty()) {
            throw RequirementErrors.validation("至少提交一条评审意见");
        }
        boolean privileged = privileged(scope, review);
        if (!review.reviewerIds().contains(scope.userId()) && !privileged) {
            throw PdrErrors.notReviewer(reviewId, scope.userId());
        }

        int seq = nextSeq(reviewId, scope.userId());
        for (PdrViews.OpinionInput input : inputs) {
            String decision = normalizeDecision(input.decision());
            String category = normalizeCategory(input.category());
            String comment = opinionComment(decision, input.comment());
            long opinionId = db.insert("""
                    INSERT INTO pdr_review_opinion (tenant_id, review_id, reviewer_id, seq, decision,
                                                    category, comment, status, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    scope.tenantId(), reviewId, scope.userId(), seq++, decision, category, comment,
                    OP_OPEN, scope.userId(), scope.userId());
            audit.record("PDR_REVIEW_OPINION", BIZ_TYPE, reviewId, auditDetail(Map.of(
                    "opinionId", opinionId,
                    "decision", decision,
                    "category", category)));
        }

        ReviewRow fresh = loadReview(scope, reviewId);
        evaluate(fresh);
        return detail(loadReview(scope, reviewId));
    }

    /**
     * 结论判定：把每人的意见压成一个立场，再按 mode 求和。
     *
     * <p>MODE_ALL：任一人的立场是 REJECT 或 NEED_CHANGE → 立刻 REJECTED（设计 R2 明确「任一」
     * 即整单驳回，不等其他人）；全员到齐且都是 PASS → APPROVED；否则保持 IN_REVIEW。</p>
     *
     * <p>MODE_ANY：任一人 PASS → APPROVED；全员评完但没人 PASS → REJECTED。</p>
     */
    private void evaluate(ReviewRow review) {
        if (!ST_IN_REVIEW.equals(review.status())) {
            return;
        }
        Map<Long, List<String>> byReviewer = decisionsByReviewer(review.id());
        PdrViews.Tally tally = tally(review, byReviewer);
        boolean allIn = byReviewer.size() >= review.reviewerIds().size() && !review.reviewerIds().isEmpty();

        if (MODE_ANY.equals(review.mode())) {
            if (tally.passCount() > 0) {
                close(review, ST_APPROVED, tally, "MODE_ANY：已有评审人通过");
            } else if (allIn) {
                close(review, ST_REJECTED, tally, "MODE_ANY：全部评审人已评但无人通过");
            }
            return;
        }
        if (tally.rejectCount() > 0 || tally.needChangeCount() > 0) {
            close(review, ST_REJECTED, tally, "MODE_ALL：存在需修改或驳回意见");
        } else if (allIn) {
            close(review, ST_APPROVED, tally, "MODE_ALL：全部评审人通过");
        }
    }

    /** 收尾：写 status/conclusion/closed_at + 审计 PDR_REVIEW_PASS|PDR_REVIEW_REJECT + 通知发起人。 */
    private void close(ReviewRow review, String status, PdrViews.Tally tally, String reason) {
        DataScope scope = TenantContext.require();
        String conclusion = ST_APPROVED.equals(status) ? DEC_PASS : DEC_REJECT;
        int rows = db.update("""
                UPDATE pdr_review
                   SET status = ?, conclusion = ?, closed_at = CURRENT_TIMESTAMP(3),
                       updated_by = ?, version = version + 1
                 WHERE tenant_id = ? AND id = ? AND status = ?
                """, status, conclusion, scope.userId(), scope.tenantId(), review.id(), ST_IN_REVIEW);
        if (rows == 0) {
            // 并发下另一笔已经收尾：读回最新状态即可，不重复通知。
            return;
        }
        audit.record(ST_APPROVED.equals(status) ? "PDR_REVIEW_PASS" : "PDR_REVIEW_REJECT",
                BIZ_TYPE, review.id(), auditDetail(Map.of(
                        "conclusion", conclusion,
                        "reason", reason,
                        "passCount", tally.passCount(),
                        "needChangeCount", tally.needChangeCount(),
                        "rejectCount", tally.rejectCount(),
                        "reviewerCount", tally.reviewerCount())));
        if (review.initiatorId() > 0) {
            notifications.send(review.initiatorId(),
                    ST_APPROVED.equals(status) ? "PDR_REVIEW_PASS" : "PDR_REVIEW_REJECT",
                    ST_APPROVED.equals(status) ? "PDR 评审已通过" : "PDR 评审被驳回",
                    "需求 " + review.requirementId() + " 的 PDR 评审结论：" + reason,
                    BIZ_TYPE, review.id());
        }
    }

    /**
     * 显式出结论（{@code POST /pdr-reviews/{id}/conclude}）：与提交后的自动判定同一条 {@link #evaluate}
     * 逻辑，区别只是 MODE_ALL 没评完时给 409 而不是静默等他投完。
     */
    @Transactional
    public PdrViews.ReviewDetail conclude(long reviewId) {
        DataScope scope = TenantContext.require();
        ReviewRow review = loadReview(scope, reviewId);
        if (!ST_IN_REVIEW.equals(review.status())) {
            throw PdrErrors.reviewClosed(reviewId, review.status());
        }
        if (!privileged(scope, review)) {
            throw PdrErrors.notAllowed("conclude");
        }
        Map<Long, List<String>> byReviewer = decisionsByReviewer(reviewId);
        if (byReviewer.isEmpty()) {
            throw PdrErrors.incomplete(List.copyOf(review.reviewerIds()));
        }
        PdrViews.Tally tally = tally(review, byReviewer);
        boolean allIn = byReviewer.size() >= review.reviewerIds().size();
        if (MODE_ALL.equals(review.mode()) && !allIn
                && tally.rejectCount() == 0 && tally.needChangeCount() == 0) {
            throw PdrErrors.incomplete(tally.pendingReviewerIds());
        }
        evaluate(review);
        return detail(loadReview(scope, reviewId));
    }

    /**
     * 关闭评审（{@code POST /pdr-reviews/{id}/close}）：给「评不下去了」一个出口（需求取消、评审人离职）。
     * 结论记 REJECTED —— 需求继续可编辑，只是拿不到「已通过」这张通行证。
     */
    @Transactional
    public PdrViews.ReviewDetail closeReview(long reviewId, String reason) {
        DataScope scope = TenantContext.require();
        ReviewRow review = loadReview(scope, reviewId);
        if (!ST_IN_REVIEW.equals(review.status())) {
            throw PdrErrors.reviewClosed(reviewId, review.status());
        }
        if (!privileged(scope, review)) {
            throw PdrErrors.notAllowed("close");
        }
        PdrViews.Tally tally = tally(review, decisionsByReviewer(reviewId));
        close(review, ST_REJECTED, tally,
                reason == null || reason.isBlank() ? "发起人关闭了评审" : reason.trim());
        return detail(loadReview(scope, reviewId));
    }

    // ------------------------------------------------------------------ 读

    /** 某需求最近的评审单（含意见）；从未发起过时返回 {@code review=null}，前端照常渲染空态。 */
    public PdrViews.ReviewDetail detailForRequirement(long requirementId) {
        DataScope scope = TenantContext.require();
        requireRequirement(scope, requirementId);
        Optional<ReviewRow> latest = db.row("""
                SELECT id, requirement_id, project_id, status, mode, conclusion, initiator_id,
                       reviewer_source, reviewer_ids, note, due_at, created_at, closed_at
                  FROM pdr_review
                 WHERE tenant_id = ? AND requirement_id = ?
                 ORDER BY id DESC LIMIT 1
                """, scope.tenantId(), requirementId).map(PdrReviewService::reviewRow);
        return latest.map(this::detail).orElse(new PdrViews.ReviewDetail(null, List.of()));
    }

    /** 意见分页（评审单维度；{@code reviewerId/category/status} 都可空）。 */
    public PdrViews.OpinionPage opinions(long requirementId, Long reviewerId, String category,
                                         String status, Integer page, Integer size) {
        DataScope scope = TenantContext.require();
        requireRequirement(scope, requirementId);
        int p = SysDb.pageNo(page);
        int s = SysDb.pageSize(size);
        Optional<ReviewRow> latest = db.row("""
                SELECT id FROM pdr_review WHERE tenant_id = ? AND requirement_id = ?
                 ORDER BY id DESC LIMIT 1
                """, scope.tenantId(), requirementId).map(row -> loadReview(scope, SysDb.asLong(row[0])));
        if (latest.isEmpty()) {
            return new PdrViews.OpinionPage(List.of(), 0L, p, s);
        }
        long reviewId = latest.get().id();
        StringBuilder where = new StringBuilder(" WHERE o.tenant_id = ? AND o.review_id = ?");
        List<Object> params = new ArrayList<>(List.of(scope.tenantId(), reviewId));
        if (reviewerId != null && reviewerId > 0) {
            where.append(" AND o.reviewer_id = ?");
            params.add(reviewerId);
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND o.category = ?");
            params.add(normalizeCategory(category));
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND o.status = ?");
            params.add(status.trim().toUpperCase());
        }
        long total = db.count("SELECT COUNT(*) FROM pdr_review_opinion o" + where, params.toArray());
        List<Object> paged = new ArrayList<>(params);
        paged.add(s);
        paged.add((long) (p - 1) * s);
        List<PdrViews.OpinionView> items = db.rows(OPINION_SELECT + where
                        + " ORDER BY o.reviewer_id, o.seq, o.id LIMIT ? OFFSET ?", paged.toArray())
                .stream().map(PdrReviewService::opinionView).toList();
        return new PdrViews.OpinionPage(items, total, p, s);
    }

    /** 需求详情里的 {@code pdrReview} 摘要（供 RequirementService 拼 Detail）。 */
    public Optional<PdrViews.ReviewView> latestForRequirement(long requirementId) {
        DataScope scope = TenantContext.require();
        return db.row("""
                SELECT id, requirement_id, project_id, status, mode, conclusion, initiator_id,
                       reviewer_source, reviewer_ids, note, due_at, created_at, closed_at
                  FROM pdr_review WHERE tenant_id = ? AND requirement_id = ?
                 ORDER BY id DESC LIMIT 1
                """, scope.tenantId(), requirementId).map(PdrReviewService::reviewRow).map(this::toView);
    }

    /**
     * 顺序门（设计 §3.2 / R1）：PDR 必须有一张 APPROVED 的评审单。
     *
     * <p>返回「为什么被拦」而不是 boolean，因为 409 的 detail 要带 {@code reviewId} 让前端跳过去。</p>
     */
    public Optional<Gate> gate(long requirementId) {
        Optional<ReviewRow> latest = latestReview(TenantContext.require(), requirementId);
        if (latest.isPresent() && ST_APPROVED.equals(latest.get().status())) {
            return Optional.empty();
        }
        return Optional.of(new Gate(latest.map(ReviewRow::id).orElse(null),
                latest.map(ReviewRow::status).orElse("NONE")));
    }

    /**
     * 宽松版门（给「父 PDR」用）：从没发起过评审时放行。
     *
     * <p>存量 RD 挂在一张没走过评审的 PDR 下，不该在 r41 上线当天被追溯卡死；但只要父 PDR 开始
     * 评审，就必须以通过结论为准——否则「评了一半就去排期」这条口子会一直开着。</p>
     */
    public Optional<Gate> gateIfStarted(long requirementId) {
        Optional<ReviewRow> latest = latestReview(TenantContext.require(), requirementId);
        if (latest.isEmpty() || ST_APPROVED.equals(latest.get().status())) {
            return Optional.empty();
        }
        return Optional.of(new Gate(latest.get().id(), latest.get().status()));
    }

    /**
     * 批量版门（列表页用）：一次查完一页需求，避免逐行 N+1。
     *
     * <p>{@code strict=true} 是「PDR 自己」的口径——从未发起过也算被拦（status 报 {@code NONE}）；
     * {@code strict=false} 是「父 PDR」的口径——没发起过就放行，与 {@link #gateIfStarted} 一致。</p>
     */
    public java.util.Map<Long, Gate> gatesOf(java.util.List<Long> requirementIds, boolean strict) {
        java.util.Map<Long, Gate> result = new java.util.LinkedHashMap<>();
        if (requirementIds == null || requirementIds.isEmpty()) {
            return result;
        }
        DataScope scope = TenantContext.require();
        for (Long id : requirementIds) {
            Optional<ReviewRow> latest = latestReview(scope, id);
            if (latest.isEmpty()) {
                if (strict) {
                    result.put(id, new Gate(null, "NONE"));
                }
                continue;
            }
            if (!ST_APPROVED.equals(latest.get().status())) {
                result.put(id, new Gate(latest.get().id(), latest.get().status()));
            }
        }
        return result;
    }

    /** 单个需求的严格门（PDR 自己）：从未发起过也算被拦。 */
    public boolean passed(long requirementId) {
        return gate(requirementId).isEmpty();
    }

    private Optional<ReviewRow> latestReview(DataScope scope, long requirementId) {
        return db.row("""
                SELECT id, requirement_id, project_id, status, mode, conclusion, initiator_id,
                       reviewer_source, reviewer_ids, note, due_at, created_at, closed_at
                  FROM pdr_review WHERE tenant_id = ? AND requirement_id = ?
                 ORDER BY id DESC LIMIT 1
                """, scope.tenantId(), requirementId).map(PdrReviewService::reviewRow);
    }

    /** 门禁结果：{@code reviewId} 可能为空（从未发起过）。 */
    public record Gate(Long reviewId, String status) {
    }

    // ------------------------------------------------------------------ 转任务

    /**
     * 意见转任务（设计 §4.5 / R8）。
     *
     * <p>顺序刻意是「先建远程任务、再写本地回写」：远程失败时本地一行都没动，天然满足「同事务」；
     * 反过来先写本地再远程，远程超时就会留下一条指不到任务的 TASKED 意见。</p>
     *
     * <p>评审已关闭仍然允许转（D4 的最后一条）——意见还在，只是不再接受新意见。</p>
     */
    @Transactional
    public PdrViews.ToTaskResult toTask(long reviewId, long opinionId, PdrViews.ToTask req) {
        DataScope scope = TenantContext.require();
        ReviewRow review = loadReview(scope, reviewId);
        OpinionRow opinion = loadOpinion(scope, reviewId, opinionId);
        if (!review.reviewerIds().contains(scope.userId())
                && !privileged(scope, review)) {
            throw PdrErrors.notAllowed("opinion_to_task");
        }
        if (opinion.taskId() != null) {
            throw PdrErrors.alreadyTasked(opinionId, opinion.taskId());
        }

        String title = trimToNull(req == null ? null : req.title(), 200, "title");
        if (title == null) {
            title = defaultTitle(opinion);
        }
        String description = req == null ? null : trimToNull(req.description(), 2000, "description");
        if (description == null) {
            description = "来源：PDR 评审 #" + reviewId + " · " + PdrCategories.label(opinion.category())
                    + " · " + nullToEmpty(opinion.comment());
        }
        LocalDate dueDate = parseDate(req == null ? null : req.dueDate(), "dueDate");
        BigDecimal hours = req == null ? null : req.estimateHours();
        if (hours != null && hours.signum() < 0) {
            throw RequirementErrors.validation("estimateHours 不能为负");
        }

        PdrTaskClient.TaskCreated created = taskClient.create(review.requirementId(), title,
                description, req == null ? null : req.assigneeId(), hours, dueDate);

        int rows = db.update("""
                UPDATE pdr_review_opinion
                   SET status = ?, task_id = ?, updated_by = ?, version = version + 1
                 WHERE tenant_id = ? AND id = ? AND task_id IS NULL
                """, OP_TASKED, created.taskId(), scope.userId(), scope.tenantId(), opinionId);
        if (rows == 0) {
            // 并发：另一笔已经回写过。远程任务多建了一条，明确报冲突让用户去关掉多余的那条。
            throw PdrErrors.alreadyTasked(opinionId, loadOpinion(scope, reviewId, opinionId).taskId());
        }
        audit.record("PDR_OPINION_TO_TASK", BIZ_TYPE, reviewId, auditDetail(Map.of(
                "opinionId", opinionId,
                "taskId", created.taskId(),
                "category", opinion.category())));
        return new PdrViews.ToTaskResult(created.taskId(), created.taskCode(),
                loadOpinionView(scope, opinionId));
    }

    private String defaultTitle(OpinionRow opinion) {
        String source = opinion.comment() == null || opinion.comment().isBlank()
                ? PdrCategories.label(opinion.category())
                : opinion.comment().trim();
        String flat = source.replaceAll("\\s+", " ");
        return flat.length() <= TITLE_CLIP ? flat : flat.substring(0, TITLE_CLIP);
    }

    // ------------------------------------------------------------------ 内部

    private record Req(long id, String code, String type, String status, long domainId,
                       Long vendorId, Long ownerVendorId, long reporterId, Long projectId) {
    }

    private record ReviewRow(long id, long requirementId, Long projectId, String status, String mode,
                             String conclusion, long initiatorId, String reviewerSource,
                             List<Long> reviewerIds, String note, Timestamp dueAt,
                             Timestamp createdAt, Timestamp closedAt) {
    }

    private record OpinionRow(long id, long reviewId, long reviewerId, int seq, String decision,
                              String category, String comment, String status, Long taskId,
                              Timestamp createdAt) {
    }

    private static final String OPINION_SELECT = """
            SELECT o.id, o.review_id, o.reviewer_id, u.name, o.seq, o.decision, o.category,
                   o.comment, o.status, o.task_id, o.created_at
              FROM pdr_review_opinion o
              LEFT JOIN sys_user u ON u.id = o.reviewer_id AND u.tenant_id = o.tenant_id
            """;

    private Req requireRequirement(DataScope scope, long requirementId) {
        Req row = db.row("""
                SELECT id, code, type, status, domain_id, assignee_vendor_id, owner_vendor_id,
                       reporter_id, project_id
                  FROM requirement WHERE tenant_id = ? AND id = ?
                """, scope.tenantId(), requirementId)
                .map(cells -> new Req(SysDb.asLong(cells[0]), SysDb.asString(cells[1]),
                        SysDb.asString(cells[2]), SysDb.asString(cells[3]),
                        SysDb.asLong(cells[4]), SysDb.asNullableLong(cells[5]),
                        SysDb.asNullableLong(cells[6]), SysDb.asLong(cells[7]),
                        SysDb.asNullableLong(cells[8])))
                .orElseThrow(() -> RequirementErrors.notFound("requirement", requirementId));
        boolean vendorVisible = scope.canSeeVendor(row.vendorId() != null
                ? row.vendorId() : row.ownerVendorId());
        if (!scope.canSeeDomainOrUnrestricted(row.domainId()) || !vendorVisible) {
            throw RequirementErrors.outOfScope("requirement", requirementId);
        }
        return row;
    }

    private ReviewRow loadReview(DataScope scope, long reviewId) {
        ReviewRow row = db.row("""
                SELECT id, requirement_id, project_id, status, mode, conclusion, initiator_id,
                       reviewer_source, reviewer_ids, note, due_at, created_at, closed_at
                  FROM pdr_review WHERE tenant_id = ? AND id = ?
                """, scope.tenantId(), reviewId)
                .map(PdrReviewService::reviewRow)
                .orElseThrow(() -> PdrErrors.reviewNotFound(reviewId));
        requireRequirement(scope, row.requirementId());
        return row;
    }

    private static ReviewRow reviewRow(Object[] cells) {
        return new ReviewRow(SysDb.asLong(cells[0]), SysDb.asLong(cells[1]),
                SysDb.asNullableLong(cells[2]), SysDb.asString(cells[3]), SysDb.asString(cells[4]),
                SysDb.asString(cells[5]), SysDb.asLong(cells[6]), SysDb.asString(cells[7]),
                parseIds(SysDb.asString(cells[8])), SysDb.asString(cells[9]),
                timestamp(cells[10]), timestamp(cells[11]), timestamp(cells[12]));
    }

    private OpinionRow loadOpinion(DataScope scope, long reviewId, long opinionId) {
        return db.row("""
                SELECT id, review_id, reviewer_id, seq, decision, category, comment, status,
                       task_id, created_at
                  FROM pdr_review_opinion
                 WHERE tenant_id = ? AND review_id = ? AND id = ?
                """, scope.tenantId(), reviewId, opinionId)
                .map(PdrReviewService::opinionRow)
                .orElseThrow(() -> PdrErrors.opinionNotFound(opinionId));
    }

    private static OpinionRow opinionRow(Object[] cells) {
        return new OpinionRow(SysDb.asLong(cells[0]), SysDb.asLong(cells[1]), SysDb.asLong(cells[2]),
                SysDb.asInt(cells[3]), SysDb.asString(cells[4]), SysDb.asString(cells[5]),
                SysDb.asString(cells[6]), SysDb.asString(cells[7]), SysDb.asNullableLong(cells[8]),
                timestamp(cells[9]));
    }

    private PdrViews.OpinionView loadOpinionView(DataScope scope, long opinionId) {
        return db.row(OPINION_SELECT + " WHERE o.tenant_id = ? AND o.id = ?",
                        scope.tenantId(), opinionId)
                .map(PdrReviewService::opinionView)
                .orElseThrow(() -> PdrErrors.opinionNotFound(opinionId));
    }

    private static PdrViews.OpinionView opinionView(Object[] cells) {
        String name = SysDb.asString(cells[3]);
        String category = SysDb.asString(cells[6]);
        return new PdrViews.OpinionView(SysDb.asLong(cells[0]), SysDb.asLong(cells[1]),
                SysDb.asLong(cells[2]), name == null ? "" : name, SysDb.asInt(cells[4]),
                SysDb.asString(cells[5]), category, PdrCategories.label(category),
                SysDb.asString(cells[7]), SysDb.asString(cells[8]), SysDb.asNullableLong(cells[9]),
                SysDb.asIso(cells[10]));
    }

    private PdrViews.ReviewDetail detail(ReviewRow review) {
        return new PdrViews.ReviewDetail(toView(review), opinionsOf(review.id()));
    }

    private List<PdrViews.OpinionView> opinionsOf(long reviewId) {
        return db.rows(OPINION_SELECT + """
                 WHERE o.tenant_id = ? AND o.review_id = ?
                 ORDER BY o.reviewer_id, o.seq, o.id
                """, TenantContext.require().tenantId(), reviewId)
                .stream().map(PdrReviewService::opinionView).toList();
    }

    private PdrViews.ReviewView toView(ReviewRow review) {
        return new PdrViews.ReviewView(review.id(), review.requirementId(), review.projectId(),
                review.status(), review.mode(), review.conclusion(), review.initiatorId(),
                review.reviewerSource(), SRC_FALLBACK.equals(review.reviewerSource()),
                review.reviewerIds(), review.note(), SysDb.asIso(review.dueAt()),
                SysDb.asIso(review.createdAt()), SysDb.asIso(review.closedAt()));
    }

    private Optional<ReviewRow> findOpen(long requirementId) {
        DataScope scope = TenantContext.require();
        return db.row("""
                SELECT id, requirement_id, project_id, status, mode, conclusion, initiator_id,
                       reviewer_source, reviewer_ids, note, due_at, created_at, closed_at
                  FROM pdr_review
                 WHERE tenant_id = ? AND requirement_id = ? AND status = ?
                 ORDER BY id DESC LIMIT 1
                """, scope.tenantId(), requirementId, ST_IN_REVIEW)
                .map(PdrReviewService::reviewRow);
    }

    /** 每人的立场集合（按 reviewer_id 分组）。 */
    private Map<Long, List<String>> decisionsByReviewer(long reviewId) {
        Map<Long, List<String>> map = new LinkedHashMap<>();
        for (Object[] cells : db.rows("""
                SELECT reviewer_id, decision FROM pdr_review_opinion
                 WHERE tenant_id = ? AND review_id = ? ORDER BY reviewer_id, id
                """, TenantContext.require().tenantId(), reviewId)) {
            map.computeIfAbsent(SysDb.asLong(cells[0]), key -> new ArrayList<>())
                    .add(SysDb.asString(cells[1]));
        }
        return map;
    }

    /** 按人聚合：一个评审人不论提了几条，只贡献一个立场。 */
    private PdrViews.Tally tally(ReviewRow review, Map<Long, List<String>> byReviewer) {
        int pass = 0;
        int needChange = 0;
        int reject = 0;
        List<Long> pending = new ArrayList<>();
        for (Long reviewerId : review.reviewerIds()) {
            List<String> decisions = byReviewer.get(reviewerId);
            if (decisions == null || decisions.isEmpty()) {
                pending.add(reviewerId);
                continue;
            }
            if (decisions.contains(DEC_REJECT)) {
                reject++;
            } else if (decisions.contains(DEC_NEED_CHANGE)) {
                needChange++;
            } else {
                pass++;
            }
        }
        return new PdrViews.Tally(review.reviewerIds().size(), byReviewer.size(), pass, needChange,
                reject, List.copyOf(pending));
    }

    private int nextSeq(long reviewId, long reviewerId) {
        return (int) db.count("""
                SELECT COALESCE(MAX(seq), 0) FROM pdr_review_opinion
                 WHERE tenant_id = ? AND review_id = ? AND reviewer_id = ?
                """, TenantContext.require().tenantId(), reviewId, reviewerId) + 1;
    }

    private List<PdrViews.OpinionInput> opinionInputs(PdrViews.SubmitOpinions req) {
        if (req == null) {
            return List.of();
        }
        if (req.opinions() != null && !req.opinions().isEmpty()) {
            return req.opinions();
        }
        if (req.decision() == null && req.category() == null && req.comment() == null) {
            return List.of();
        }
        return List.of(new PdrViews.OpinionInput(req.decision(), req.category(), req.comment()));
    }

    private boolean privileged(DataScope scope, ReviewRow review) {
        if (review.initiatorId() == scope.userId()) {
            return true;
        }
        // 产品经理/域负责人：靠动作码判定（DataScope 不带角色，只能问权限服务）。
        return actionPermissions.decide(MENU, ACTION_CONCLUDE) != PermissionMatrix.Access.DENY;
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_ALL;
        }
        String value = mode.trim().toUpperCase();
        if (!MODE_ALL.equals(value) && !MODE_ANY.equals(value)) {
            throw new ApiException(400, "INVALID_MODE", "mode 只能是 MODE_ALL / MODE_ANY",
                    Map.of("mode", mode, "allowed", List.of(MODE_ALL, MODE_ANY)));
        }
        return value;
    }

    private static String normalizeDecision(String decision) {
        if (decision == null || decision.isBlank()) {
            throw PdrErrors.invalidDecision(decision);
        }
        String value = decision.trim().toUpperCase();
        if (!DECISIONS.contains(value)) {
            throw PdrErrors.invalidDecision(decision);
        }
        return value;
    }

    private static String normalizeCategory(String category) {
        String value = category == null ? null : category.trim().toUpperCase();
        if (!PdrCategories.knows(value)) {
            throw PdrErrors.invalidCategory(category);
        }
        return value;
    }

    /** 非 PASS 必须写清理由；PASS 允许空评论（设计只要求「非通过必填」）。 */
    private static String opinionComment(String decision, String comment) {
        String value = trimToNull(comment, COMMENT_MAX, "comment");
        if (value == null && !DEC_PASS.equals(decision)) {
            throw RequirementErrors.validation("决策为 " + decision + " 时必须填写意见内容");
        }
        return value;
    }

    private static String trimToNull(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw RequirementErrors.validation(field + " 不能超过 " + max + " 个字符");
        }
        return trimmed;
    }

    private static Timestamp parseDueAt(String dueAt) {
        if (dueAt == null || dueAt.isBlank()) {
            return null;
        }
        String value = dueAt.trim();
        try {
            return Timestamp.valueOf(LocalDateTime.parse(value));
        } catch (DateTimeParseException ignored) {
            // 允许只给日期：截止到当天结束。
        }
        try {
            return Timestamp.valueOf(LocalDate.parse(value).atTime(23, 59, 59));
        } catch (DateTimeParseException ex) {
            throw RequirementErrors.validation("dueAt 需要 ISO 日期或日期时间，例如 2026-12-01 或 2026-12-01T18:00:00");
        }
    }

    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw RequirementErrors.validation(field + " 需要 ISO 日期，例如 2026-12-01");
        }
    }

    private static List<Long> distinctIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<Long> set = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                set.add(id);
            }
        }
        return List.copyOf(set);
    }

    private static String toJsonIds(List<Long> ids) {
        return "[" + String.join(",", ids.stream().map(String::valueOf).toList()) + "]";
    }

    /**
     * 解析 {@code reviewer_ids}。故意容忍空格与空串：这一列是 JSON 但只存数字数组，
     * 为它拉一个 ObjectMapper 会让「读一行评审单」也变成一次序列化器注入。
     */
    static List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : json.replace("[", "").replace("]", "").split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                try {
                    ids.add(Long.parseLong(value));
                } catch (NumberFormatException ignored) {
                    // 脏数据跳过，评审单仍可读（否则一行坏 JSON 让整张单 500）。
                }
            }
        }
        return List.copyOf(ids);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 审计 detail 里不放 null（{@code ApiException} 同款约定：null 值不参与展示）。 */
    private static Map<String, Object> auditDetail(Map<String, Object> detail) {
        Map<String, Object> clean = new LinkedHashMap<>();
        detail.forEach((key, value) -> {
            if (value != null) {
                clean.put(key, value);
            }
        });
        return clean;
    }

    /**
     * 把 native query 取回的时间列统一成 Timestamp：MySQL 驱动按列类型返回 Timestamp，
     * 但 Hibernate 7 对 DATETIME 也可能给 LocalDateTime——两种都接住，别让一行时间列把详情打成 500。
     */
    static Timestamp timestamp(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts;
        }
        if (value instanceof LocalDateTime ldt) {
            return Timestamp.valueOf(ldt);
        }
        if (value instanceof java.time.Instant instant) {
            return Timestamp.from(instant);
        }
        return null;
    }

    /** 评审时长（小时）：给统计服务复用，避免两处口径漂移。 */
    static BigDecimal hoursBetween(Timestamp from, Timestamp to) {
        if (from == null || to == null) {
            return null;
        }
        long millis = Duration.between(from.toInstant(), to.toInstant()).toMillis();
        if (millis < 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(millis).divide(BigDecimal.valueOf(3_600_000L), 2, java.math.RoundingMode.HALF_UP);
    }

    /** 统计服务要用的公开常量（动作码判定「谁可以转任务」）。 */
    public static String concludeAction() {
        return ACTION_CONCLUDE;
    }

    public static String startAction() {
        return ACTION_START;
    }

    /** 排序工具：统计分桶按数量降序、同数按 key 升序，保证同一份数据每次输出顺序一致。 */
    static Comparator<PdrViews.Bucket> bucketOrder() {
        return Comparator.comparingLong(PdrViews.Bucket::count).reversed()
                .thenComparing(PdrViews.Bucket::key);
    }
}
