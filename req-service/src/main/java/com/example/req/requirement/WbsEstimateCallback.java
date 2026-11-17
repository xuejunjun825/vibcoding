package com.example.req.requirement;

import com.example.platform.contract.ApprovalCallback;
import com.example.req.approval.ApprovalStore;
import com.example.req.approval.WorkflowJson;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工时评估单（{@code WBS_ESTIMATE}）通过/驳回/撤回的回调。
 *
 * <p>与 {@code RequirementApprovalCallback} 同形：引擎事务内回调，因此「单据 APPROVED」与
 * 「需求人天落库」原子发生。需求动态 = 该审计表，前端无需新接口。</p>
 */
@Component
public class WbsEstimateCallback implements ApprovalCallback {

    private final RequirementService requirements;
    private final WbsEstimateService estimates;
    private final ApprovalStore store;
    private final WorkflowJson json;

    public WbsEstimateCallback(RequirementService requirements, WbsEstimateService estimates,
                               ApprovalStore store, WorkflowJson json) {
        this.requirements = requirements;
        this.estimates = estimates;
        this.store = store;
        this.json = json;
    }

    @Override
    public String bizType() {
        return WbsEstimateService.BIZ_TYPE;
    }

    @Override
    public void onApproved(long bizId, long instanceId) {
        BigDecimal days = EstimateSnapshot.days(
                EstimateSnapshot.document(store, json, instanceId));
        if (days != null) {
            requirements.applyApprovedEstimate(bizId, days, instanceId);
        }
        // 通过时把其它在途单置为 SUPERSEDED（正常只有一张，兜底并发场景）。
        estimates.supersedeActive(bizId, instanceId, 0L);
        // 新单通过即取代旧的有效单：AC-5 要求任意时刻 APPROVED ≤ 1（旧单不 supersede 会留两张）。
        estimates.supersedeApproved(bizId, instanceId, 0L);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("instanceId", instanceId);
        detail.put("estimateDays", days);
        requirements.recordEstimateEvent(bizId, "ESTIMATE_APPROVED", detail);
    }

    @Override
    public void onRejected(long bizId, long instanceId, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("instanceId", instanceId);
        detail.put("reason", reason == null ? "" : reason);
        requirements.recordEstimateEvent(bizId, "ESTIMATE_REJECTED", detail);
    }

    @Override
    public void onWithdrawn(long bizId, long instanceId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("instanceId", instanceId);
        requirements.recordEstimateEvent(bizId, "ESTIMATE_WITHDRAWN", detail);
    }
}
