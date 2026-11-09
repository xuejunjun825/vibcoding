package com.example.req.integration.gitlab;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.req.integration.adapter.AdapterRegistry;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.store.MrSyncEntity;
import com.example.req.integration.store.MrSyncRepository;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementService;
import com.example.req.requirement.Rows;
import tools.jackson.databind.ObjectMapper;

/**
 * MR 事实的 upsert 与会话（TECHNICAL §5.7 / §9.6）。
 *
 * <p>三条不变式，全部在这里收口，webhook 与手工同步共用同一段代码：</p>
 * <ol>
 *   <li><b>唯一键</b>：{@code uk_mr(tenant_id, gitlab_project, mr_iid)} 一行；同一 MR 只
 *       有一份事实，重复投递不会长出新行。</li>
 *   <li><b>单调</b>：{@code last_event_at} 只增不减。相等 → {@code DUPLICATE}，更旧 →
 *       {@code OUT_OF_ORDER}，两者都不写库，也就不可能把 MERGED 打回 OPENED。</li>
 *   <li><b>绑定</b>：MR 绑定到需求后不再改绑（webhook 靠它找需求）；同一 MR 被另一个
 *       需求认领时 409 {@code MR_ALREADY_LINKED}，而不是悄悄易主。</li>
 * </ol>
 *
 * <p>webhook 里的版本级兜底（见 {@link #resolveCandidateRequirement}）：MR 事件只带
 * {@code project + iid}，若该项目下已有需求绑过 MR，就沿「项目 → 已绑定需求 → 其版本」
 * 找到开启了 {@code mrAutoAdvance} 的版本，把该版本里 {@code TEST_PASSED} 且尚未绑 MR
 * 的 RD 认领这条 MR。没有这条兜底，MR 先合并、需求后建（或需求换了 MR 链接）的场景会
 * 永远停在 TEST_PASSED。</p>
 */
@Service
public class MrSyncService {

    private static final Logger log = LoggerFactory.getLogger(MrSyncService.class);

    public static final String CODE_MR_URL_INVALID = "MR_URL_INVALID";
    public static final String CODE_MR_ALREADY_LINKED = "MR_ALREADY_LINKED";

    public static final String ACTION_SYNCED = "MR_SYNCED";

    private static final String BIZ_TYPE = "MR";

    private final MrSyncRepository repository;
    private final GitLabAdapter adapter;
    private final AdapterRegistry adapterRegistry;
    private final RequirementService requirementService;
    private final MrAdvanceService advanceService;
    private final AuditService auditService;
    private final MrCommentService commentService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public MrSyncService(MrSyncRepository repository,
                         GitLabAdapter adapter,
                         AdapterRegistry adapterRegistry,
                         RequirementService requirementService,
                         MrAdvanceService advanceService,
                         AuditService auditService,
                         MrCommentService commentService,
                         ObjectMapper objectMapper,
                         JdbcTemplate jdbc) {
        this.repository = repository;
        this.adapter = adapter;
        this.adapterRegistry = adapterRegistry;
        this.requirementService = requirementService;
        this.advanceService = advanceService;
        this.auditService = auditService;
        this.commentService = commentService;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    /**
     * 手工同步：解析用户填的 MR URL，拉一次 GitLab，落库并回卡片。
     * URL 解析失败 → 400 {@code MR_URL_INVALID}；集成未启用 → 409 {@code INTEGRATION_DISABLED}。
     */
    @Transactional
    public MrViews.MrCard sync(long requirementId, String mrUrl) {
        RequirementEntity requirement = requirementService.requireWritable(requirementId);
        if (mrUrl == null || mrUrl.isBlank()) {
            throw new ApiException(400, CODE_MR_URL_INVALID, "请填写 MR 链接");
        }
        MrUrlParser.MrRef ref = MrUrlParser.parse(mrUrl).orElseThrow(() -> new ApiException(
                400, CODE_MR_URL_INVALID,
                "无法从链接里识别 GitLab merge request（需形如 …/group/project/-/merge_requests/12）",
                Map.of("mrUrl", mrUrl)));

        IntegrationConfigEntity config = adapterRegistry.require(IntegrationSystem.GITLAB);
        MrViews.MrDetails details = adapter.fetchMergeRequestDetails(
                config, ref.project(), ref.iid(), requirementId);
        MrViews.MrPayload payload = details.payload();

        MrSyncEntity row = repository
                .findByTenantIdAndGitlabProjectAndMrIid(requirement.getTenantId(), ref.project(), ref.iid())
                .orElse(null);
        if (row != null && row.getRequirementId() != null
                && !row.getRequirementId().equals(requirementId)) {
            throw new ApiException(409, CODE_MR_ALREADY_LINKED,
                    "该 MR 已绑定其它需求", Map.of("mrSyncId", row.getId(),
                            "requirementId", row.getRequirementId()));
        }
        MrViews.ApplyOutcome outcome = apply(requirement.getTenantId(), config.getId(), requirementId, payload);
        auditService.record(ACTION_SYNCED, "REQUIREMENT", requirementId, Map.of(
                "gitlabProject", ref.project(), "mrIid", ref.iid(),
                "outcome", outcome.name(),
                "degraded", payload.degraded()));

        MrSyncEntity saved = repository
                .findByTenantIdAndGitlabProjectAndMrIid(requirement.getTenantId(), ref.project(), ref.iid())
                .orElse(null);
        if (saved == null) {
            return MrViews.MrCard.linkOnly(mrUrl);
        }
        persistReview(requirement.getTenantId(), saved, details.review());
        return toCard(saved, requirement.getTenantId());
    }

    /**
     * 「刷新」：需求详情里唯一会外呼的按钮（AC-12 / TECHNICAL §2.6）。
     *
     * <p>三件事按顺序做，任何一步失败都不留半张假卡：</p>
     * <ol>
     *   <li>MR 详情：只有 GitLab 的时间戳更新才 merge（{@code last_event_at} 单调，旧的
     *       载荷不会把卡片打回上一次状态）；</li>
     *   <li>行级评论：按 {@code (tenant, mr_sync_id, gitlab_note_id)} 幂等 upsert，
     *       返回「新增 / 更新」两条真实计数；</li>
     *   <li>评审事实：merged/closed 之后只更新 resolved/评论数，不再覆盖评审人与评审态
     *       ——合并后有人撤销 approve 不该把历史评审记录洗掉。</li>
     * </ol>
     *
     * <p>外呼失败（超时/鉴权/不可达）返回 409 + 具体错误码，<b>不</b>把卡片写成绿色：
     * 「这次没拉到」与「拉到了且是绿的」必须可区分。</p>
     */
    @Transactional
    public MrViews.RefreshResult refresh(long requirementId) {
        RequirementEntity requirement = requirementService.requireWritable(requirementId);
        long tenantId = requirement.getTenantId();
        MrSyncEntity row = repository
                .findFirstByTenantIdAndRequirementIdOrderByLastEventAtDesc(tenantId, requirementId)
                .orElseThrow(() -> new ApiException(404, "MR_NOT_FOUND",
                        "该需求还没有关联 MR，请先填写 MR 链接并同步"));
        IntegrationConfigEntity config = adapterRegistry.require(IntegrationSystem.GITLAB);

        MrViews.MrDetails details = adapter.fetchMergeRequestDetails(
                config, row.getGitlabProject(), row.getMrIid(), requirementId);
        MrViews.MrPayload payload = details.payload();
        if (payload == null || payload.degraded()) {
            String code = payload == null ? "GITLAB_UNREACHABLE" : payload.errorCode();
            throw new ApiException(409, code == null ? "GITLAB_UNREACHABLE" : code,
                    "本次刷新未能从 GitLab 取到最新数据，卡片保持上一次的结论",
                    Map.of("requirementId", requirementId, "mrSyncId", row.getId()));
        }

        boolean newer = payload.lastEventAt() != null
                && (row.getLastEventAt() == null || payload.lastEventAt().isAfter(row.getLastEventAt()));
        if (newer) {
            merge(row, payload, config.getId());
            row = repository.save(row);
        }
        persistReview(tenantId, row, details.review());

        List<MrViews.MrNote> notes = adapter.fetchMrNotes(
                config, row.getGitlabProject(), row.getMrIid());
        MrCommentService.Change change = commentService.syncAll(tenantId, row.getId(), notes);

        auditService.record("MR_REFRESHED", BIZ_TYPE, row.getId(), Map.of(
                "requirementId", requirementId,
                "added", change.added(),
                "updated", change.updated(),
                "comments", commentService.lineLevelCount(tenantId, row.getId())));
        return new MrViews.RefreshResult(toCard(row, tenantId), change.added(), change.updated());
    }

    /** 需求详情里的「代码」卡；没有同步过就是 null（前端显示空态）。 */
    @Transactional(readOnly = true)
    public MrViews.MrCard card(long requirementId) {
        RequirementEntity requirement = requirementService.requireVisible(requirementId);
        return repository
                .findFirstByTenantIdAndRequirementIdOrderByLastEventAtDesc(
                        requirement.getTenantId(), requirementId)
                .map(row -> toCard(row, requirement.getTenantId()))
                .orElse(null);
    }

    /**
     * webhook 落日志时补业务锚点：这条 MR 归哪个需求。找不到返回 {@code null}（不绑定需求）。
     *
     * <p>webhook 载荷只有 project+iid，拿不到 {@code mr_sync.id}，但日志的需求作用域要的
     * 正是 {@code requirement.id}——这里只做一次只读反查，判定仍归 upsert 自己。</p>
     */
    @Transactional(readOnly = true)
    public Long requirementIdOf(long tenantId, String gitlabProject, long mrIid) {
        return repository.findByTenantIdAndGitlabProjectAndMrIid(tenantId, gitlabProject, mrIid)
                .map(MrSyncEntity::getRequirementId)
                .orElse(null);
    }

    /**
     * webhook 入口：按 project+iid upsert。返回 {@code APPLIED / DUPLICATE / OUT_OF_ORDER /
     * IGNORED}——调用方据此回 200 + 同一个字符串，绝不因「重复投递」回非 2xx（GitLab 会重投）。
     */
    @Transactional
    public MrViews.ApplyOutcome applyPayload(long tenantId, long configId, MrViews.MrPayload payload) {
        if (payload == null || payload.gitlabProject() == null || payload.mrIid() <= 0) {
            return MrViews.ApplyOutcome.IGNORED;
        }
        MrSyncEntity row = repository
                .findByTenantIdAndGitlabProjectAndMrIid(tenantId, payload.gitlabProject(), payload.mrIid())
                .orElse(null);
        if (row == null) {
            Long candidate = resolveCandidateRequirement(tenantId, payload.gitlabProject());
            if (candidate == null) {
                log.debug("GitLab MR {}/{} 没有可绑定的需求，忽略", payload.gitlabProject(), payload.mrIid());
                return MrViews.ApplyOutcome.IGNORED;
            }
            row = new MrSyncEntity();
            row.setTenantId(tenantId);
            row.setConfigId(configId);
            row.setGitlabProject(payload.gitlabProject());
            row.setMrIid(payload.mrIid());
            row.setRequirementId(candidate);
        }
        if (row.getId() != null && row.getLastEventAt() != null && payload.lastEventAt() != null) {
            if (payload.lastEventAt().isEqual(row.getLastEventAt())) {
                return MrViews.ApplyOutcome.DUPLICATE;
            }
            if (payload.lastEventAt().isBefore(row.getLastEventAt())) {
                return MrViews.ApplyOutcome.OUT_OF_ORDER;
            }
        }
        merge(row, payload, configId);
        MrSyncEntity saved = repository.save(row);
        linkRequirement(tenantId, saved.getRequirementId(), payload.gitlabProject(), payload.mrIid());

        if (saved.isMerged() && saved.getRequirementId() != null) {
            try {
                advanceService.onMerged(saved.getRequirementId(), saved.getId());
            } catch (RuntimeException e) {
                // 推进失败不能把 webhook 变成 5xx（GitLab 会重投整批），但要在日志里留痕
                log.warn("MR merged 推进需求 {} 失败：{}", saved.getRequirementId(), e.toString());
            }
        }
        return MrViews.ApplyOutcome.APPLIED;
    }

    /** 手工同步与 webhook 共用的 upsert：单调判定 + 字段合并。 */
    private MrViews.ApplyOutcome apply(long tenantId, long configId, long requirementId,
                                       MrViews.MrPayload payload) {
        MrSyncEntity row = repository
                .findByTenantIdAndGitlabProjectAndMrIid(tenantId, payload.gitlabProject(), payload.mrIid())
                .orElse(null);
        if (row == null) {
            row = new MrSyncEntity();
            row.setTenantId(tenantId);
            row.setGitlabProject(payload.gitlabProject());
            row.setMrIid(payload.mrIid());
            row.setRequirementId(requirementId);
            row.setConfigId(configId);
        }
        if (row.getId() != null && row.getLastEventAt() != null && payload.lastEventAt() != null) {
            if (!payload.lastEventAt().isAfter(row.getLastEventAt())) {
                return payload.lastEventAt().isEqual(row.getLastEventAt())
                        ? MrViews.ApplyOutcome.DUPLICATE
                        : MrViews.ApplyOutcome.OUT_OF_ORDER;
            }
        }
        merge(row, payload, configId);
        MrSyncEntity saved = repository.save(row);
        linkRequirement(tenantId, requirementId, payload.gitlabProject(), payload.mrIid());
        if (saved.isMerged()) {
            advanceService.onMerged(requirementId, saved.getId());
        }
        return MrViews.ApplyOutcome.APPLIED;
    }

    /**
     * 字段合并。两条保护：
     * <ul>
     *   <li>MERGED 不会被非 MERGED 的载荷改写（单调判定之外的二道闸，防 payload 时间戳
     *       相同但事件类型不同）；</li>
     *   <li>降级载荷（state=null）不覆盖已有状态，只补 URL/原始载荷。</li>
     * </ul>
     */
    private void merge(MrSyncEntity row, MrViews.MrPayload payload, long configId) {
        row.setConfigId(configId);
        if (payload.mrUrl() != null && !payload.mrUrl().isBlank()) {
            row.setMrUrl(payload.mrUrl());
        }
        if (payload.title() != null) {
            row.setTitle(payload.title());
        }
        if (payload.sourceBranch() != null) {
            row.setSourceBranch(payload.sourceBranch());
        }
        if (payload.targetBranch() != null) {
            row.setTargetBranch(payload.targetBranch());
        }
        if (payload.approvalsCount() != null) {
            row.setApprovalsCount(payload.approvalsCount());
        }
        if (payload.additions() != null) {
            row.setAdditions(payload.additions());
        }
        if (payload.deletions() != null) {
            row.setDeletions(payload.deletions());
        }
        if (payload.state() != null) {
            boolean keepMerged = MrViews.STATE_MERGED.equals(row.getState())
                    && !MrViews.STATE_MERGED.equals(payload.state());
            if (!keepMerged) {
                row.setState(payload.state());
            }
        } else if (row.getState() == null) {
            row.setState(MrViews.STATE_OPENED);
        }
        if (payload.lastEventAt() != null) {
            row.setLastEventAt(payload.lastEventAt());
        }
        if (MrViews.STATE_MERGED.equals(row.getState())) {
            row.setMergedAt(payload.mergedAt() != null ? payload.mergedAt() : row.getLastEventAt());
        }
        if (payload.rawJson() != null) {
            row.setPayloadJson(payload.rawJson());
        }
    }

    /**
     * 版本级兜底：本项目下已被需求绑定过的 MR → 那些需求所在的版本 → 版本内
     * {@code TEST_PASSED} 且未绑 MR 的 RD。取 id 最小的一条，保证同一事件重复处理时
     * 结论稳定（幂等）。
     */
    private Long resolveCandidateRequirement(long tenantId, String project) {
        String sql = """
                SELECT DISTINCT cand.id
                  FROM mr_sync m
                  JOIN release_requirement rr ON rr.requirement_id = m.requirement_id
                  JOIN `release` rel ON rel.id = rr.release_id
                  JOIN release_requirement rr2 ON rr2.release_id = rel.id
                  JOIN requirement cand ON cand.id = rr2.requirement_id
                 WHERE m.tenant_id = ?
                   AND m.gitlab_project = ?
                   AND rel.mr_auto_advance = 1
                   AND cand.tenant_id = ?
                   AND cand.type = 'RD'
                   AND cand.status = 'TEST_PASSED'
                   AND cand.mr_iid IS NULL
                 ORDER BY cand.id ASC
                 LIMIT 1
                """;
        List<Long> ids = jdbc.queryForList(sql, Long.class, tenantId, project, tenantId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 需求上的 MR 绑定列（列表页与追溯矩阵要按需求直接读，不经 mr_sync 联表）。 */
    private void linkRequirement(long tenantId, Long requirementId, String project, long iid) {
        if (requirementId == null) {
            return;
        }
        try {
            jdbc.update("UPDATE requirement SET gitlab_project = ?, mr_iid = ? WHERE id = ? AND tenant_id = ?",
                    project, iid, requirementId, tenantId);
        } catch (RuntimeException e) {
            log.warn("回写需求 {} 的 MR 绑定失败：{}", requirementId, e.toString());
        }
    }

    /**
     * 评审事实落库（reviewers / review_state / changed_files / comments_count）。
     *
     * <p>这四列不在 {@link MrSyncEntity} 里（实体属于 M7 的既有边界，本任务不改它），
     * 所以用一条窄 UPDATE 写：列少、只碰这四列，不会和 webhook 的状态合并相互覆盖。</p>
     *
     * <p><b>合并/关闭后冻结评审事实</b>：{@code state} 已是 MERGED/CLOSED 时只更新
     * {@code comments_count}（评论数会继续涨），评审人与评审态保持历史值——
     * 否则「合并后有人撤销 approve」会把当初放行的证据洗掉。</p>
     */
    private void persistReview(long tenantId, MrSyncEntity row, MrViews.ReviewInfo review) {
        if (row == null || row.getId() == null || review == null) {
            return;
        }
        boolean frozen = MrViews.STATE_MERGED.equals(row.getState())
                || MrViews.STATE_CLOSED.equals(row.getState());
        try {
            if (frozen) {
                jdbc.update("""
                        UPDATE mr_sync SET comments_count = ? WHERE id = ? AND tenant_id = ?
                        """, review.commentsCount(), row.getId(), tenantId);
                return;
            }
            jdbc.update("""
                    UPDATE mr_sync
                       SET reviewers_json = ?, review_state = ?, changed_files = ?, comments_count = ?
                     WHERE id = ? AND tenant_id = ?
                    """,
                    reviewersJson(review.reviewers()), review.reviewState(), review.changedFiles(),
                    review.commentsCount(), row.getId(), tenantId);
        } catch (RuntimeException e) {
            // 评审事实是增强信息：写失败不能让「MR 同步」整体回滚（GitLab 会重投整批）
            log.warn("MR {} 评审事实落库失败：{}", row.getId(), e.toString());
        }
    }

    private String reviewersJson(List<MrViews.Reviewer> reviewers) {
        if (reviewers == null || reviewers.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(reviewers);
        } catch (Exception e) {
            log.warn("评审人序列化失败：{}", e.toString());
            return null;
        }
    }

    /**
     * 实体 → 卡片：13 个基础字段来自实体，{@code reviewers/reviewState/changedFiles/commentsCount}
     * 与评论预览来自 V9 的四列 + {@code mr_comment}。
     */
    private MrViews.MrCard toCard(MrSyncEntity row, long tenantId) {
        Map<String, Object> extras = extras(tenantId, row.getId());
        List<MrViews.Reviewer> reviewers = reviewers(Rows.str(extras, "reviewers_json"));
        return new MrViews.MrCard(row.getId(), row.getGitlabProject(), row.getMrIid(), row.getMrUrl(),
                row.getTitle(), row.getState(), row.getSourceBranch(), row.getTargetBranch(),
                row.getApprovalsCount(), row.getAdditions(), row.getDeletions(),
                row.getLastEventAt(), row.getMergedAt(), MrViews.isStale(row.getLastEventAt()),
                reviewers,
                Rows.str(extras, "review_state"),
                intOrNull(extras.get("changed_files")),
                intOrNull(extras.get("comments_count")),
                row.getId() == null ? List.of()
                        : commentService.preview(tenantId, row.getId(), MrViews.COMMENT_PREVIEW));
    }

    private Map<String, Object> extras(long tenantId, Long mrSyncId) {
        if (mrSyncId == null) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT reviewers_json, review_state, changed_files, comments_count
                  FROM mr_sync WHERE id = ? AND tenant_id = ?
                """, mrSyncId, tenantId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private List<MrViews.Reviewer> reviewers(String reviewersJson) {
        if (reviewersJson == null || reviewersJson.isBlank()) {
            return List.of();
        }
        try {
            MrViews.Reviewer[] parsed = objectMapper.readValue(reviewersJson, MrViews.Reviewer[].class);
            return parsed == null ? List.of() : List.of(parsed);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Integer intOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
