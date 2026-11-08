package com.example.req.release;

import com.example.platform.contract.ApprovalCallback;
import org.springframework.stereotype.Component;

/**
 * 版本发布审批的回调（引擎不 import 单据类，单据反向注册）。
 *
 * <p>通过 → 发布并级联需求；驳回/撤回 → 版本回 PLAN，需求状态不动（D3）。</p>
 */
@Component
public class ReleaseApprovalCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = ReleaseService.BIZ_TYPE;

    private final ReleaseService releaseService;

    public ReleaseApprovalCallback(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @Override
    public String bizType() {
        return BIZ_TYPE;
    }

    @Override
    public void onApproved(long bizId, long instanceId) {
        releaseService.publish(bizId);
    }

    @Override
    public void onRejected(long bizId, long instanceId, String reason) {
        releaseService.onApprovalRejected(bizId, reason);
    }

    @Override
    public void onWithdrawn(long bizId, long instanceId) {
        releaseService.onApprovalRejected(bizId, "发起人撤回");
    }
}
