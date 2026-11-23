package com.example.req.automation.action;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.security.UserIdentity;
import com.example.platform.common.security.UserScopeResolver;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.approval.ApproverDirectory;
import com.example.req.automation.api.AutomationDtos.ActionOutcome;
import com.example.req.automation.engine.AutomationScopes;
import com.example.req.automation.engine.AutomationSnapshot;
import com.example.req.automation.rule.AutomationRuleEntity;
import com.example.req.automation.spec.AutomationSpecs.ActionSpec;
import com.example.req.automation.spec.AutomationSpecs.Recipient;
import com.example.req.automation.spec.AutomationActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves a NOTIFY action's recipients, filters them by their own visibility of the requirement,
 * then sends one notification each.
 *
 * <p>Two policies are structural, not stylistic:</p>
 * <ul>
 *   <li><b>Nobody, or everybody-or-nobody.</b> Zero recipients is a skip, and more than the
 *       configured ceiling is a skip that sends <em>nothing</em>: a partial fan-out is worse than no
 *       fan-out because it looks like the rule worked.</li>
 *   <li><b>Visibility is the request path's rule.</b> The candidate's scope is built by
 *       {@link UserIdentity#scope()}, the same code the auth filter uses, then probed with the same
 *       {@code ScopeSql} predicate. Automation must not invent a second opinion about who may see a
 *       requirement.</li>
 * </ul>
 */
@Component
public class AutomationNotifier {

    private static final Logger log = LoggerFactory.getLogger(AutomationNotifier.class);

    private static final String TYPE_AUTOMATION_RULE = "AUTOMATION_RULE";

    private final ApproverDirectory directory;
    private final NotificationWriter notifications;
    private final UserScopeResolver identities;
    private final ScopedJdbc jdbc;
    private final int maxRecipients;

    public AutomationNotifier(ApproverDirectory directory,
                              NotificationWriter notifications,
                              UserScopeResolver identities,
                              ScopedJdbc jdbc,
                              @Value("${platform.automation.max-recipients:50}") int maxRecipients) {
        this.directory = directory;
        this.notifications = notifications;
        this.identities = identities;
        this.jdbc = jdbc;
        this.maxRecipients = Math.max(1, maxRecipients);
    }

    public ActionOutcome notify(ActionSpec spec, Map<String, Object> snapshot,
                                DataScope ruleScope, long requirementId) {
        return notify(null, spec, snapshot, ruleScope, requirementId);
    }

    public ActionOutcome notify(AutomationRuleEntity rule, ActionSpec spec,
                                Map<String, Object> snapshot, DataScope ruleScope,
                                long requirementId) {
        List<Long> recipients = resolveRecipients(spec, snapshot);
        if (recipients.isEmpty()) {
            return ActionOutcomes.skipped(AutomationActionType.NOTIFY.name(),
                    "NO_RECIPIENT", null);
        }
        if (recipients.size() > maxRecipients) {
            log.warn("automation notify skipped: rule={} recipients={} limit={}",
                    rule == null ? null : rule.getId(), recipients.size(), maxRecipients);
            return ActionOutcomes.skipped(AutomationActionType.NOTIFY.name(),
                    "TOO_MANY_RECIPIENTS", String.valueOf(recipients.size()));
        }
        String code = string(snapshot, AutomationSnapshot.K_CODE);
        String ruleName = rule == null ? "自动化规则" : rule.getName();
        String title = "自动化规则触发：" + ruleName;
        String content = "需求 " + (code == null ? "#" + requirementId : code) + " 命中了规则「"
                + ruleName + "」";
        int sent = 0;
        for (Long userId : recipients) {
            try {
                notifications.send(userId, TYPE_AUTOMATION_RULE, title, content,
                        "REQUIREMENT", requirementId);
                sent++;
            } catch (RuntimeException ex) {
                // NotificationWriter already swallows most failures; this is the last resort so one
                // bad recipient cannot abort the remaining recipients of the same action.
                log.warn("automation notify failed: rule={} user={} error={}",
                        rule == null ? null : rule.getId(), userId, ex.getMessage());
            }
        }
        return ActionOutcomes.success(AutomationActionType.NOTIFY.name(),
                "sent:" + sent, null);
    }

    /**
     * Candidate resolution followed by a per-candidate visibility probe. The order of the declared
     * recipient specs is preserved; duplicates collapse.
     */
    public List<Long> resolveRecipients(ActionSpec spec, Map<String, Object> snapshot) {
        if (spec == null || spec.recipients() == null || spec.recipients().isEmpty()) {
            return List.of();
        }
        long tenantId = TenantContext.require().tenantId();
        Set<Long> candidates = new LinkedHashSet<>();
        for (Recipient recipient : spec.recipients()) {
            if (recipient == null || recipient.kind() == null) {
                continue;
            }
            switch (recipient.kind()) {
                case Recipient.KIND_USER -> {
                    if (recipient.id() != null) {
                        candidates.add(recipient.id());
                    }
                }
                case Recipient.KIND_ROLE -> {
                    if (recipient.code() != null) {
                        candidates.addAll(directory.activeUsersByRole(tenantId, recipient.code()));
                    }
                }
                case Recipient.KIND_DOMAIN_OWNER -> {
                    Long domainId = longValue(snapshot, AutomationSnapshot.K_DOMAIN_ID);
                    if (domainId != null) {
                        candidates.addAll(directory.domainOwners(tenantId, Set.of(domainId)));
                    }
                }
                default -> log.debug("unknown automation recipient kind ignored: {}",
                        recipient.kind());
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Long> active = directory.activeUsersByIds(tenantId, candidates);
        List<Long> visible = new ArrayList<>(active.size());
        UserScopeResolver.TenantRow tenant = identities.tenantById(tenantId).orElse(null);
        if (tenant == null) {
            return List.of();
        }
        for (Long userId : active) {
            UserScopeResolver.UserRow user = identities.userById(tenantId, userId).orElse(null);
            if (user == null || !"ACTIVE".equals(user.status())) {
                continue;
            }
            UserIdentity identity = identities.identity(user, tenant);
            boolean canSee = AutomationScopes.withScope(identity.scope(),
                    () -> jdbc.count("SELECT COUNT(*) FROM requirement r WHERE r.id = :id"
                            + " AND /*scope:r*/", Map.of("id", requestId(snapshot))) > 0);
            if (canSee) {
                visible.add(userId);
            }
        }
        return visible;
    }

    private static Object requestId(Map<String, Object> snapshot) {
        return snapshot == null ? null : snapshot.get("requirementId");
    }

    private static String string(Map<String, Object> snapshot, String key) {
        Object value = snapshot == null ? null : snapshot.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Map<String, Object> snapshot, String key) {
        Object value = snapshot == null ? null : snapshot.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
