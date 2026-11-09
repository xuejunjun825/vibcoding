package com.example.req.deploy;

import com.example.req.integration.DeliverPlanCoverageClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R1 的<b>唯一</b>校验点：发布单写库前拒绝未批准的覆盖计划（AC-1 / AC-2）。
 *
 * <p>三条判定收在这一处，调用点只负责"什么时候问"：</p>
 * <ul>
 *   <li>没有需求 ⇒ 放行（没有覆盖就没有可被阻塞的计划，AC-3）；</li>
 *   <li>覆盖查询不可用 ⇒ 503 {@code PLAN_CHECK_UNAVAILABLE}（fail-closed，绝不猜成"没有计划"）；</li>
 *   <li>任一覆盖计划待批 ⇒ 409 {@code PLAN_NOT_APPROVED} + {@code detail.planIds}。</li>
 * </ul>
 *
 * <p>AC-2 要求"所有写单入口"一致：当前只有 {@code DeployOrderService.create} 会落库，
 * 因此只有一个调用点；将来若新增 update/导入端点，必须复用本方法而不是另写一份判定。</p>
 */
@Component
public class DeployPlanGuard {

    private final DeliverPlanCoverageClient coverage;

    public DeployPlanGuard(DeliverPlanCoverageClient coverage) {
        this.coverage = coverage;
    }

    /**
     * 发布单引用 {@code requirementIds} 前必须通过：命中待批计划即抛 409，查询不可用抛 503。
     *
     * @param releaseId      发布单所属版本（计划按版本限定，他版本的待批计划不得阻塞本单）
     * @param requirementIds 本单最终需求集（已经过 resolveRequirementIds 定型）
     */
    public void requireApproved(long releaseId, List<Long> requirementIds) {
        if (requirementIds == null || requirementIds.isEmpty()) {
            return;
        }
        List<DeliverPlanCoverageClient.PlanRef> blocked;
        try {
            blocked = coverage.pending(releaseId, requirementIds);
        } catch (DeliverPlanCoverageClient.PlanCheckUnavailable ex) {
            throw DeployErrors.planCheckUnavailable(ex.getMessage());
        }
        if (!blocked.isEmpty()) {
            throw DeployErrors.planNotApproved(blocked, requirementIds);
        }
    }
}
