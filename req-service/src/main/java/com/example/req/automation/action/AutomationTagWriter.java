package com.example.req.automation.action;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.automation.api.AutomationDtos.ActionOutcome;
import com.example.req.automation.spec.AutomationActionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent tag writer.
 *
 * <p>{@code INSERT IGNORE} eats {@code uk_req_tag}, so a redelivered event that reaches a tag it
 * already added is a success ("exists"), not an error. That is the difference between a rule being
 * safely retryable and a rule producing a duplicate-key exception in the run log.</p>
 */
@Component
public class AutomationTagWriter {

    public static final String SOURCE_RULE = "RULE";
    public static final String AUDIT_ACTION = "REQUIREMENT_TAG_ADD";

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public AutomationTagWriter(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public ActionOutcome addTag(long requirementId, String tag, String source, long operatorId) {
        return addTag(requirementId, tag, source, operatorId, 0);
    }

    public ActionOutcome addTag(long requirementId, String tag, String source, long operatorId,
                                int chainDepth) {
        long tenantId = TenantContext.require().tenantId();
        int inserted = jdbc.update("INSERT IGNORE INTO requirement_tag"
                        + " (tenant_id, requirement_id, tag, source, created_by, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, NOW(3))",
                tenantId, requirementId, tag, source, operatorId == 0 ? null : operatorId);
        boolean exists = inserted == 0;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tag", tag);
        detail.put("source", source);
        detail.put("automationOrigin", "RULE");
        if (chainDepth > 0) {
            detail.put("automationChainDepth", chainDepth);
        }
        audit.record(AUDIT_ACTION, "REQUIREMENT", requirementId, detail);
        return ActionOutcomes.success(AutomationActionType.ADD_TAG.name(),
                exists ? "exists" : "created", tag);
    }
}
