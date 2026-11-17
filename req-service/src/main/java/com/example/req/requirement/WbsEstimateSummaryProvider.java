package com.example.req.requirement;

import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalStore;
import com.example.req.approval.WorkflowJson;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工时评估单（{@code WBS_ESTIMATE}）的审批中心摘要来源。
 *
 * <p>路由属性沿用需求基类（priority/reqType/domainId），摘要只叠加单据字段；实例重载读的是
 * SUBMIT 快照，因此审批人看到的是<b>提交时</b>的人天，而不是需求被改动后的当前值。</p>
 *
 * <p>越权裁剪完全复用基类（域/厂商不可越权看），本类不新增可见性规则。</p>
 */
@Component
public class WbsEstimateSummaryProvider extends RequirementDocumentSummary {

    public static final String BIZ_TYPE = WbsEstimateService.BIZ_TYPE;

    private final WbsEstimateService estimates;
    private final ApprovalStore store;
    private final WorkflowJson json;

    public WbsEstimateSummaryProvider(RequirementRepository repository,
                                      WbsEstimateService estimates,
                                      ApprovalStore store,
                                      WorkflowJson json) {
        super(repository);
        this.estimates = estimates;
        this.store = store;
        this.json = json;
    }

    @Override
    public String bizType() {
        return BIZ_TYPE;
    }

    @Override
    public Map<String, Object> summary(long tenantId, long bizId, long viewerId) {
        Map<String, Object> base = super.summary(tenantId, bizId, viewerId);
        if (masked(base)) {
            return base;
        }
        RequirementEntity entity = repository.findByIdAndTenantId(bizId, tenantId).orElse(null);
        if (entity == null) {
            return base;
        }
        WbsEstimateViews.EstimateView view = estimates.view(entity);
        Map<String, Object> out = new LinkedHashMap<>(base);
        if (view == null) {
            return out;
        }
        out.put("estimateStatus", view.status());
        out.put("proposedDays", view.proposedDays());
        out.put("approvedDays", view.approvedDays());
        out.put("estimateNote", view.note());
        return out;
    }

    /** 实例感知：单据字段取该实例的 SUBMIT 快照，与需求当前值解耦（AC-6）。 */
    @Override
    public Map<String, Object> summary(ApprovalInstance instance, long viewerId) {
        Map<String, Object> base = super.summary(instance.getTenantId(), instance.getBizId(),
                viewerId);
        if (masked(base)) {
            return base;
        }
        Map<String, Object> document =
                EstimateSnapshot.document(store, json, instance.getId());
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("estimateStatus", instance.getStatus());
        out.put("proposedDays", EstimateSnapshot.days(document));
        out.put("approvedDays", ApprovalInstance.STATUS_APPROVED.equals(instance.getStatus())
                ? EstimateSnapshot.days(document) : null);
        out.put("estimateNote", EstimateSnapshot.note(document));
        return out;
    }

    private static boolean masked(Map<String, Object> summary) {
        return summary == null || summary.isEmpty() || Boolean.TRUE.equals(summary.get("masked"));
    }
}
