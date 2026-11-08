package com.example.req.requirement;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.approval.DocumentSummaryProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 需求类单据的摘要/属性来源基类（TECHNICAL §5.6）。
 *
 * <p>{@code REQ_SUBMIT} 与 {@code REQ_REVIEW} 共用同一套字段裁剪，只差 bizType，
 * 所以抽基类而不是复制两份 Map 组装代码。摘要必须按<b>查看人</b>范围裁剪：
 * 越权时只给编码、状态，标题打码——审批中心不因摘要泄露需求内容。</p>
 */
public abstract class RequirementDocumentSummary implements DocumentSummaryProvider {

    protected final RequirementRepository repository;

    protected RequirementDocumentSummary(RequirementRepository repository) {
        this.repository = repository;
    }

    /** 供条件分支/抄送解析：只含路由需要的非敏感字段。 */
    @Override
    public Map<String, Object> attributes(long tenantId, long bizId) {
        RequirementEntity entity = repository.findByIdAndTenantId(bizId, tenantId).orElse(null);
        if (entity == null) {
            return Map.of();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("priority", entity.getPriority());
        attributes.put("reqType", entity.getType());
        attributes.put("domainId", entity.getDomainId());
        if (entity.getEstimateDays() != null) {
            attributes.put("estimateDays", entity.getEstimateDays());
        }
        return attributes;
    }

    /** 供审批中心展示：按查看人范围裁剪，越权只给最小可识别信息。 */
    @Override
    public Map<String, Object> summary(long tenantId, long bizId, long viewerId) {
        RequirementEntity entity = repository.findByIdAndTenantId(bizId, tenantId).orElse(null);
        if (entity == null) {
            return Map.of("id", bizId, "state", "MISSING");
        }
        boolean visible = visibleTo(entity);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", entity.getId());
        summary.put("code", entity.getCode());
        summary.put("type", entity.getType());
        summary.put("status", entity.getStatus());
        summary.put("priority", entity.getPriority());
        summary.put("domainId", entity.getDomainId());
        summary.put("ownerUserId", entity.getOwnerUserId());
        summary.put("estimateDays", entity.getEstimateDays());
        summary.put("expectedDeliveryDate", String.valueOf(entity.getExpectedDeliveryDate()));
        summary.put("title", visible ? truncate(entity.getTitle()) : "（无权查看标题）");
        summary.put("masked", !visible);
        return summary;
    }

    private boolean visibleTo(RequirementEntity entity) {
        DataScope scope;
        try {
            scope = TenantContext.require();
        } catch (RuntimeException ex) {
            // 没有请求范围（定时任务/内部调用）时不放大：按不可见处理
            return false;
        }
        if (!java.util.Objects.equals(scope.tenantId(), entity.getTenantId())) {
            return false;
        }
        if (!scope.domainIds().isEmpty() && !scope.domainIds().contains(entity.getDomainId())) {
            return false;
        }
        Long vendorId = entity.getAssigneeVendorId() != null
                ? entity.getAssigneeVendorId() : entity.getOwnerVendorId();
        return scope.canSeeVendor(vendorId);
    }

    private String truncate(String title) {
        if (title == null) {
            return "";
        }
        return title.length() <= 60 ? title : title.substring(0, 60) + "…";
    }
}
