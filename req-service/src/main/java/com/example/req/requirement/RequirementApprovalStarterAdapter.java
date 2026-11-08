package com.example.req.requirement;

import com.example.platform.common.tenant.TenantContext;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalInstanceRepository;
import com.example.req.approval.ApprovalViews;
import com.example.req.approval.StartApproval;
import com.example.req.approval.WorkflowVersion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把需求单据接到审批引擎上的适配器：只组装 {@link StartApproval} 的"单据属性快照"，
 * 不改审批引擎任何语义。用 {@link ObjectProvider} 延迟取引擎，避免
 * 「引擎 → 回调列表 → 需求服务 → 引擎」的构造期循环依赖。
 */
@Component
public class RequirementApprovalStarterAdapter implements RequirementApprovalStarter {

    private final ObjectProvider<ApprovalEngine> engine;
    private final RequirementRepository repository;
    private final ApprovalInstanceRepository instanceRepository;

    public RequirementApprovalStarterAdapter(ObjectProvider<ApprovalEngine> engine,
                                             RequirementRepository repository,
                                             ApprovalInstanceRepository instanceRepository) {
        this.engine = engine;
        this.repository = repository;
        this.instanceRepository = instanceRepository;
    }

    @Override
    public long start(long requirementId, String bizType, String source) {
        RequirementEntity entity = requireRequirement(requirementId);
        ApprovalViews.InstanceView view = engine.getObject().start(new StartApproval(
                bizType, requirementId, entity.getType(), entity.getDomainId(),
                attributesOf(entity, source), null, null));
        return view.id();
    }

    /**
     * 评审入池审批：同一需求同时只允许一个在途 REQ_REVIEW 实例（wf_instance.active_flag 唯一键），
     * 因此重复点击「发起评审」幂等返回已有实例，而不是撞唯一键 500。
     */
    @Override
    public long startReview(long requirementId, String source, Long definitionId) {
        long tenantId = TenantContext.require().tenantId();
        return instanceRepository
                .findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                        tenantId, WorkflowVersion.BIZ_REQ_REVIEW, requirementId, 1)
                .map(ApprovalInstance::getId)
                .orElseGet(() -> startReviewInstance(requirementId, source, definitionId));
    }

    private long startReviewInstance(long requirementId, String source, Long definitionId) {
        RequirementEntity entity = requireRequirement(requirementId);
        ApprovalViews.InstanceView view = engine.getObject().start(new StartApproval(WorkflowVersion.BIZ_REQ_REVIEW, requirementId, entity.getType(), entity.getDomainId(), attributesOf(entity, source), definitionId, null));
        return view.id();
    }

    @Override
    public boolean isApproved(long requirementId, String bizType) {
        long tenantId = TenantContext.require().tenantId();
        return instanceRepository
                .findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(tenantId, bizType, requirementId)
                .map(instance -> ApprovalInstance.STATUS_APPROVED.equals(instance.getStatus()))
                .orElse(false);
    }

    private RequirementEntity requireRequirement(long requirementId) {
        return repository.findById(requirementId)
                .orElseThrow(() -> RequirementErrors.notFound("需求", requirementId));
    }

    private static Map<String, Object> attributesOf(RequirementEntity entity, String source) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("priority", entity.getPriority());
        attributes.put("reqType", entity.getType());
        attributes.put("domainId", entity.getDomainId());
        if (entity.getEstimateDays() != null) {
            attributes.put("estimateDays", entity.getEstimateDays());
        }
        attributes.put("source", source);
        return attributes;
    }
}
