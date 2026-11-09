package com.example.req.integration.gitlab;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseRepository;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementRules;
import com.example.req.requirement.RequirementService;
import com.example.req.requirement.RequirementViews;

/**
 * MR 合并 → 需求状态推进（TECHNICAL §5.7 / §9.7）。
 *
 * <p>默认必须人工确认：{@code release.mr_auto_advance=0} 时 webhook 只记录 MR 状态，
 * 需求停在原地，由 {@link #confirmMerge(long, String)} 走一次显式动作。开启自动后也只做
 * 状态机允许的那一条迁移（RD: {@code TEST_PASSED --merge--> MERGED}），别的状态一律拒绝
 * 并留 {@code MR_ADVANCE_REJECTED} 审计——静默失败是最糟的结果。</p>
 *
 * <p>幂等：需求已是 MERGED 时直接返回 {@code advanced=false}，不重复写审计，也不重复
 * 触发状态机（webhook 会重投，GitLab 的世界里幂等不是可选项）。</p>
 */
@Service
public class MrAdvanceService {

    /** 自动/人工推进成功后的审计动作名（PRD 指定）。 */
    public static final String ACTION_ADVANCED = "REQ_MR_MERGED_ADVANCE";

    /** 状态机拒绝推进时的审计动作名（不静默）。 */
    public static final String ACTION_REJECTED = "MR_ADVANCE_REJECTED";

    public static final String STATUS_MERGED = "MERGED";

    private static final String BIZ_TYPE = "REQUIREMENT";

    private static final String ACTION_MERGE = "merge";

    private final RequirementService requirementService;
    private final ReleaseRepository releaseRepository;
    private final AuditService auditService;

    public MrAdvanceService(RequirementService requirementService,
                            ReleaseRepository releaseRepository,
                            AuditService auditService) {
        this.requirementService = requirementService;
        this.releaseRepository = releaseRepository;
        this.auditService = auditService;
    }

    /**
     * webhook 驱动的自动推进。返回 {@code advanced=false} 有三种正当原因：需求已 MERGED、
     * 版本没开 {@code mrAutoAdvance}、状态机不允许（非法迁移会写拒绝审计）。
     *
     * <p>webhook 路径上不抛 409：GitLab 收到非 2xx 会一直重投，状态机拒绝是「结论」
     * 而不是「暂时失败」，用审计记录即可。人工确认接口才把拒绝翻成 409。</p>
     */
    @Transactional
    public MrViews.ConfirmResult onMerged(long requirementId, long mrSyncId) {
        RequirementEntity requirement = requirementService.requireVisible(requirementId);
        if (STATUS_MERGED.equals(requirement.getStatus())) {
            return new MrViews.ConfirmResult(requirementId, requirement.getStatus(), false);
        }
        if (!autoAdvanceEnabled(requirement)) {
            return new MrViews.ConfirmResult(requirementId, requirement.getStatus(), false);
        }
        return advance(requirementId, requirement.getStatus(),
                "GitLab MR merged（mr_sync=" + mrSyncId + "）", mrSyncId);
    }

    /**
     * 人工确认合并（{@code POST /requirements/{id}/mr/confirm-merge}）。这是默认路径：
     * 用户看过 MR 状态后点一下，需求才动。
     */
    @Transactional
    public MrViews.ConfirmResult confirmMerge(long requirementId, String note) {
        RequirementEntity requirement = requirementService.requireWritable(requirementId);
        if (STATUS_MERGED.equals(requirement.getStatus())) {
            return new MrViews.ConfirmResult(requirementId, requirement.getStatus(), false);
        }
        String comment = note == null || note.isBlank() ? "MR 合并人工确认" : note.trim();
        try {
            RequirementViews.TransitionResult result = requirementService.transition(
                    requirementId, ACTION_MERGE, comment, "INTEGRATION_MR_MANUAL");
            auditService.record(ACTION_ADVANCED, BIZ_TYPE, requirementId,
                    Map.of("mode", "MANUAL", "status", result.status(), "note", comment));
            return new MrViews.ConfirmResult(requirementId, result.status(), true);
        } catch (ApiException e) {
            auditService.recordInNewTx(ACTION_REJECTED, BIZ_TYPE, requirementId,
                    Map.of("mode", "MANUAL", "errorCode", e.code(), "from", requirement.getStatus()));
            throw new ApiException(409, "STATE_TRANSITION_REJECTED",
                    "需求当前状态不允许合并推进：" + requirement.getStatus(),
                    Map.of("requirementId", requirementId, "from", requirement.getStatus(),
                            "reason", e.message()));
        }
    }

    /** 版本是否开启了自动推进（需求未加入版本时视为关闭）。 */
    @Transactional(readOnly = true)
    public boolean autoAdvanceEnabled(RequirementEntity requirement) {
        Long releaseId = requirement.getReleaseId();
        if (releaseId == null) {
            return false;
        }
        ReleaseEntity release = releaseRepository.findByIdAndTenantId(releaseId, requirement.getTenantId())
                .orElse(null);
        if (release == null) {
            return false;
        }
        Integer flag = release.getMrAutoAdvance();
        return flag != null && flag == 1;
    }

    /** 只对 RD 生效（MR 是开发交付物，BUS/PDR 不绑 MR）。 */
    public static boolean isRd(RequirementEntity requirement) {
        return RequirementRules.TYPE_RD.equals(requirement.getType());
    }

    private MrViews.ConfirmResult advance(long requirementId, String fromStatus, String comment,
                                          Long mrSyncId) {
        try {
            RequirementViews.TransitionResult result = requirementService.transition(
                    requirementId, ACTION_MERGE, comment, "INTEGRATION_MR_AUTO");
            auditService.record(ACTION_ADVANCED, BIZ_TYPE, requirementId,
                    Map.of("mode", "AUTO", "from", fromStatus, "status", result.status(),
                            "mrSyncId", mrSyncId));
            return new MrViews.ConfirmResult(requirementId, result.status(), true);
        } catch (ApiException e) {
            // 自动推进失败不阻断 webhook（2xx 才不会让 GitLab 反复重投），但必须留痕
            auditService.recordInNewTx(ACTION_REJECTED, BIZ_TYPE, requirementId,
                    Map.of("mode", "AUTO", "errorCode", e.code(), "from", fromStatus,
                            "reason", e.getMessage() == null ? "" : e.getMessage()));
            return new MrViews.ConfirmResult(requirementId, fromStatus, false);
        }
    }
}
