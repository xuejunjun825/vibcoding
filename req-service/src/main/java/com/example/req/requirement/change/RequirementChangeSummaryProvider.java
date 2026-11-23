package com.example.req.requirement.change;

import com.example.req.approval.DocumentSummaryProvider;
import com.example.req.approval.WorkflowJson;
import com.example.req.approval.WorkflowVersion;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementRepository;
import com.example.req.requirement.RequirementSummaryProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批中心第 6 类（{@code REQ_CHANGE}）的摘要来源。
 *
 * <p>标题裁剪复用 {@link RequirementSummaryProvider}：变更单本身没有标题，读者要看的是「哪条需求」，
 * 而越权可见性判断（域外 / 厂商）只在 {@code RequirementDocumentSummary} 里有一份实现，
 * 这里<b>不再抄一遍</b>，否则两处口径迟早分叉。
 */
@Component
public class RequirementChangeSummaryProvider implements DocumentSummaryProvider {

    private final RequirementChangeRepository changes;
    private final RequirementRepository requirements;
    private final RequirementSummaryProvider requirementSummary;
    private final WorkflowJson json;

    public RequirementChangeSummaryProvider(RequirementChangeRepository changes,
                                            RequirementRepository requirements,
                                            RequirementSummaryProvider requirementSummary,
                                            WorkflowJson json) {
        this.changes = changes;
        this.requirements = requirements;
        this.requirementSummary = requirementSummary;
        this.json = json;
    }

    @Override
    public String bizType() {
        return WorkflowVersion.BIZ_REQ_CHANGE;
    }

    @Override
    public Map<String, Object> attributes(long tenantId, long bizId) {
        RequirementChangeEntity change = changes.findByIdAndTenantId(bizId, tenantId).orElse(null);
        RequirementEntity requirement = change == null ? null
                : requirements.findByIdAndTenantId(change.getRequirementId(), tenantId).orElse(null);
        if (change == null || requirement == null) {
            return Map.of();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("changeType", change.getChangeType());
        attributes.put("fieldCount", fieldCount(change));
        attributes.put("priority", requirement.getPriority());
        attributes.put("reqType", requirement.getType());
        attributes.put("domainId", requirement.getDomainId());
        return attributes;
    }

    @Override
    public Map<String, Object> summary(long tenantId, long bizId, long viewerId) {
        RequirementChangeEntity change = changes.findByIdAndTenantId(bizId, tenantId).orElse(null);
        RequirementEntity requirement = change == null ? null
                : requirements.findByIdAndTenantId(change.getRequirementId(), tenantId).orElse(null);
        if (change == null || requirement == null) {
            // 摘要缺失就明确说缺失：审批中心退回只显示单号，不臆造标题。
            return Map.of("id", bizId, "state", "MISSING");
        }
        Map<String, Object> base = requirementSummary.summary(tenantId, requirement.getId(), viewerId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", change.getId());
        summary.put("requirementId", requirement.getId());
        summary.put("code", base.get("code"));
        summary.put("title", base.get("title"));
        summary.put("type", base.get("type"));
        summary.put("status", change.getStatus());
        summary.put("requirementStatus", requirement.getStatus());
        summary.put("changeType", change.getChangeType());
        summary.put("fieldCount", fieldCount(change));
        summary.put("masked", Boolean.TRUE.equals(base.get("masked")));
        return summary;
    }

    /**
     * 变更字段数取冻结快照的差集，而不是 {@code req_change_item}：items 只在 apply 之后才写，
     * 用它算会让待审批的单子显示 0 个字段。
     */
    private int fieldCount(RequirementChangeEntity change) {
        return RequirementChangeFields.changedFields(json.readMap(change.getBeforeJson()),
                json.readMap(change.getAfterJson())).size();
    }
}
