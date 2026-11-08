package com.example.req.requirement;

import com.example.platform.contract.ApprovalCallback;
import org.springframework.stereotype.Component;

/**
 * 需求评审审批（REQ_REVIEW）的结果回调：PENDING_REVIEW → POOLED（通过）或留待修改（驳回）。
 */
@Component
public class RequirementReviewCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = "REQ_REVIEW";

    private final RequirementService requirementService;

    public RequirementReviewCallback(RequirementService requirementService) {
        this.requirementService = requirementService;
    }

    @Override
    public String bizType() {
        return BIZ_TYPE;
    }

    @Override
    public void onApproved(long bizId, long instanceId) {
        if (!isInStatus(bizId, "PENDING_REVIEW")) {
            return;
        }
        requirementService.transition(bizId, "review_pass", "评审通过（实例 " + instanceId + "）", "APPROVAL");
    }

    @Override
    public void onRejected(long bizId, long instanceId, String reason) {
        if (!isInStatus(bizId, "PENDING_REVIEW")) {
            return;
        }
        requirementService.transition(bizId, "review_reject",
                reason == null || reason.isBlank() ? "评审驳回" : reason, "APPROVAL");
    }

    @Override
    public void onWithdrawn(long bizId, long instanceId) {
        // 评审阶段撤回不属于本流程的合法动作：由引擎侧阻止，回调保持空实现
    }

    private boolean isInStatus(long requirementId, String expected) {
        return expected.equals(requirementService.requireVisible(requirementId).getStatus());
    }
}
