package com.example.req.requirement;

import com.example.platform.contract.ApprovalCallback;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

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
        requirementService.transition(bizId, "approve", "提报审批通过（实例 " + instanceId + "）",
                "APPROVAL", approvalDetail(instanceId, "APPROVED"));
    }

    @Override
    public void onRejected(long bizId, long instanceId, String reason) {
        if (!isInStatus(bizId, "APPROVING")) {
            return;
        }
        requirementService.transition(bizId, "reject",
                reason == null || reason.isBlank() ? "提报审批驳回" : reason,
                "APPROVAL", approvalDetail(instanceId, "REJECTED"));
    }

    @Override
    public void onWithdrawn(long bizId, long instanceId) {
        if (!isInStatus(bizId, "APPROVING")) {
            return;
        }
        requirementService.transition(bizId, "withdraw", "提报审批撤回",
                "APPROVAL", approvalDetail(instanceId, "WITHDRAWN"));
    }

    /**
     * 审批结论随 STATE_CHANGE 一起进审计 detail（r39 §3.3）：自动化条件里的
     * {@code approvalResult} 只能在审批路径上有值，手工流转拿不到——这正是要补它的原因。
     *
     * <p>注意：{@link ApprovalCallback} 的回调签名只带 {@code instanceId}，没有 taskId，
     * 所以 {@code approvalTaskId} 此处无法填（已作为 OUT-OF-SCOPE 上报，需扩 platform-contract）。</p>
     */
    private static Map<String, Object> approvalDetail(long instanceId, String result) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("approvalInstanceId", instanceId);
        detail.put("approvalBizType", BIZ_TYPE);
        detail.put("approvalResult", result);
        return detail;
    }

    private boolean isInStatus(long requirementId, String expected) {
        return expected.equals(requirementService.requireVisible(requirementId).getStatus());
    }
}
