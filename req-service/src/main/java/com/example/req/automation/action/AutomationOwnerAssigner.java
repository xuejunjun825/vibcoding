package com.example.req.automation.action;

import java.util.List;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.common.error.ApiException;
import com.example.req.approval.ApproverDirectory;
import com.example.req.automation.api.AutomationDtos.ActionOutcome;
import com.example.req.automation.spec.AutomationActionType;
import com.example.req.requirement.RequirementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ASSIGN_OWNER: the only automation code that may call {@code RequirementService.assignOwner}.
 *
 * <p>Concentrating it in one class is what makes lint L21 able to say "no other automation file may
 * call {@code .transition(} / {@code ApprovalEngine}" — a rule may change who owns a requirement,
 * never what state it is in.</p>
 *
 * <p>The target is validated <b>before</b> the call: {@code assignOwner} deliberately does not
 * validate the user (it is a narrow owner setter), and an invalid id must become a
 * {@code TARGET_INVALID} outcome rather than an exception that poisons the action transaction.</p>
 */
@Component
public class AutomationOwnerAssigner {

    private static final Logger log = LoggerFactory.getLogger(AutomationOwnerAssigner.class);

    private final RequirementService requirements;
    private final ApproverDirectory directory;

    public AutomationOwnerAssigner(RequirementService requirements, ApproverDirectory directory) {
        this.requirements = requirements;
        this.directory = directory;
    }

    public ActionOutcome assign(long requirementId, long userId, long ruleId, long runId) {
        return assign(requirementId, userId, ruleId, runId, 0);
    }

    public ActionOutcome assign(long requirementId, long userId, long ruleId, long runId,
                                int chainDepth) {
        long tenantId = TenantContext.require().tenantId();
        List<Long> active = directory.activeUsersByIds(tenantId, List.of(userId));
        if (!active.contains(userId)) {
            return ActionOutcomes.skipped(AutomationActionType.ASSIGN_OWNER.name(),
                    "TARGET_INVALID", String.valueOf(userId));
        }
        try {
            requirements.assignOwner(requirementId, userId, "AUTOMATION", ruleId, runId,
                    chainDepth);
        } catch (ApiException ex) {
            log.warn("automation assignOwner rejected: requirement={} user={} code={}",
                    requirementId, userId, ex.code());
            return ActionOutcomes.failed(AutomationActionType.ASSIGN_OWNER.name(), ex.code(),
                    String.valueOf(userId));
        }
        return ActionOutcomes.success(AutomationActionType.ASSIGN_OWNER.name(), "assigned",
                String.valueOf(userId));
    }
}
