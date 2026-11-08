package com.example.req.deploy;

import com.example.platform.contract.ApprovalCallback;
import org.springframework.stereotype.Component;

/**
 * 需求发布审批（{@code REQ_DEPLOY}）的回调。
 *
 * <p>通过 → 单据转 APPROVED（此后才能登记执行结果）；驳回/撤回 → REJECTED，可改窗口与制品后重新提交。
 * 两种情况都不动需求本身的状态：需求是发布单的输入，不是它的下半场。</p>
 */
@Component
public class DeployApprovalCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = DeployOrderService.APPROVAL_BIZ_TYPE;

    private final DeployOrderService deployOrderService;

    public DeployApprovalCallback(DeployOrderService deployOrderService) {
        this.deployOrderService = deployOrderService;
    }

    @Override
    public String bizType() {
        return BIZ_TYPE;
    }

    @Override
    public void onApproved(long bizId, long instanceId) {
        deployOrderService.onApprovalApproved(bizId);
    }

    @Override
    public void onRejected(long bizId, long instanceId, String reason) {
        deployOrderService.onApprovalRejected(bizId, reason);
    }

    @Override
    public void onWithdrawn(long bizId, long instanceId) {
        deployOrderService.onApprovalRejected(bizId, "发起人撤回");
    }
}
