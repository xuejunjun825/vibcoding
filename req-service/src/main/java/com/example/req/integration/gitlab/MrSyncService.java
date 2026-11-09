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
    private final JdbcTemplate jdbc;

    public MrSyncService(MrSyncRepository repository,
                         GitLabAdapter adapter,
                         AdapterRegistry adapterRegistry,
                         RequirementService requirementService,
                         MrAdvanceService advanceService,
                         AuditService auditService,
                         JdbcTemplate jdbc) {
        this.repository = repository;
        this.adapter = adapter;
        this.adapterRegistry = adapterRegistry;
        this.requirementService = requirementService;
        this.advanceService = advanceService;
        this.auditService = auditService;
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
        MrViews.MrPayload payload = adapter.fetchMergeRequest(config, ref.project(), ref.iid(), requirementId);

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
        return saved == null ? MrViews.MrCard.linkOnly(mrUrl) : toCard(saved);
    }

    /** 需求详情里的「代码」卡；没有同步过就是 null（前端显示空态）。 */
    @Transactional(readOnly = true)
    public MrViews.MrCard card(long requirementId) {
        RequirementEntity requirement = requirementService.requireVisible(requirementId);
        return repository
                .findFirstByTenantIdAndRequirementIdOrderByLastEventAtDesc(
                        requirement.getTenantId(), requirementId)
                .map(this::toCard)
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

    private MrViews.MrCard toCard(MrSyncEntity row) {
        return new MrViews.MrCard(row.getId(), row.getGitlabProject(), row.getMrIid(), row.getMrUrl(),
                row.getTitle(), row.getState(), row.getSourceBranch(), row.getTargetBranch(),
                row.getApprovalsCount(), row.getAdditions(), row.getDeletions(),
                row.getLastEventAt(), row.getMergedAt(), MrViews.isStale(row.getLastEventAt()));
    }
}
