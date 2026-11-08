package com.example.req.requirement;

import com.example.platform.contract.ApprovalCallback;
import org.springframework.stereotype.Component;

/**
 * 需求提报审批（REQ_SUBMIT）的结果回调：APPROVING → PENDING_REVIEW。
 *
 * <p>审批引擎在**单据事务内**调用本回调（TECHNICAL §5.6），因此这里直接复用需求的流转服务，
 * 状态变更、审计与通知与手工流转完全同一条路径；重复回调按当前状态幂等跳过。</p>
 */
@Component
public class RequirementApprovalCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = "REQ_SUBMIT";

    private final RequirementService requirementService;

    public RequirementApprovalCallback(RequirementService requirementService) {
        this.requirementService = requirementService;
    }

    @Override
    public String bizType() {
        return BIZ_TYPE;
    }

    @Override
    public void onApproved(long bizId, long instanceId) {
        if (!isInStatus(bizId, "APPROVING")) {
            return;
        }
        requirementService.transition(bizId, "approve", "提报审批通过（实例 " + instanceId + "）", "APPROVAL");
    }

    @Override
    public void onRejected(long bizId, long instanceId, String reason) {
        if (!isInStatus(bizId, "APPROVING")) {
            return;
        }
        requirementService.transition(bizId, "reject",
                reason == null || reason.isBlank() ? "提报审批驳回" : reason, "APPROVAL");
    }

    @Override
    public void onWithdrawn(long bizId, long instanceId) {
        if (!isInStatus(bizId, "APPROVING")) {
            return;
        }
        requirementService.transition(bizId, "withdraw", "提报审批撤回", "APPROVAL");
    }

    private boolean isInStatus(long requirementId, String expected) {
        return expected.equals(requirementService.requireVisible(requirementId).getStatus());
    }
}
