package com.example.req.automation.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.contract.DataScope;
import com.example.req.automation.api.AutomationDtos.ActionOutcome;
import com.example.req.automation.event.AutomationEventRow;
import com.example.req.automation.rule.AutomationRuleEntity;
import com.example.req.automation.spec.AutomationActionType;
import com.example.req.automation.spec.AutomationSpecs.ActionSpec;
import com.example.req.automation.spec.AutomationSpecs.ConditionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the four action types in declared order, with one ordering rule that cannot be relaxed:
 * actions that can still be rolled back (tag, owner) commit first, then irreversible side effects
 * (notification, webhook) fire on top of an already-committed state.
 *
 * <p>Why: a notification cannot be un-sent. If both were in one transaction, a later failure would
 * roll the tag back while the user had already been told the requirement was tagged — the log would
 * then disagree with reality. Doing the durable half first makes "SUCCESS" mean "you could re-read
 * it from the database".</p>
 */
@Component
public class AutomationActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(AutomationActionExecutor.class);

    private final AutomationTagWriter tags;
    private final AutomationOwnerAssigner assigner;
    private final AutomationNotifier notifier;
    private final AutomationWebhookSender webhooks;
    private final TransactionTemplate dbTransaction;

    public AutomationActionExecutor(AutomationTagWriter tags, AutomationOwnerAssigner assigner,
                                    AutomationNotifier notifier, AutomationWebhookSender webhooks,
                                    PlatformTransactionManager transactionManager) {
        this.tags = tags;
        this.assigner = assigner;
        this.notifier = notifier;
        this.webhooks = webhooks;
        this.dbTransaction = new TransactionTemplate(transactionManager);
        this.dbTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<ActionOutcome> execute(AutomationRuleEntity rule, ConditionSpec spec,
                                       List<ActionSpec> actions, AutomationEventRow event,
                                       DataScope ruleScope) {
        return execute(rule, spec, actions, event, ruleScope, Map.of());
    }

    public List<ActionOutcome> execute(AutomationRuleEntity rule, ConditionSpec spec,
                                       List<ActionSpec> actions, AutomationEventRow event,
                                       DataScope ruleScope, Map<String, Object> snapshot) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<ActionOutcome> outcomes = new ArrayList<>(
                Collections.nCopies(actions.size(), (ActionOutcome) null));
        List<Integer> deferred = new ArrayList<>();

        // Phase 1: the half that can still be rolled back, in one short transaction that commits
        // before anything leaves the process.
        dbTransaction.executeWithoutResult(status -> {
            for (int i = 0; i < actions.size(); i++) {
                ActionSpec action = actions.get(i);
                try {
                    switch (AutomationActionType.of(action.type())) {
                        case ADD_TAG -> outcomes.set(i, tags.addTag(event.requirementId(), action.tag(),
                                AutomationTagWriter.SOURCE_RULE, actorOf(rule), ruleChainDepth(event)));
                        case ASSIGN_OWNER -> outcomes.set(i, assigner.assign(event.requirementId(),
                                action.userId(), rule.getId(), 0L, ruleChainDepth(event)));
                        default -> deferred.add(i);
                    }
                } catch (RuntimeException ex) {
                    log.warn("automation db action failed: rule={} type={} error={}", rule.getId(),
                            action.type(), ex.getMessage());
                    outcomes.set(i, ActionOutcomes.failed(action.type(),
                            ActionOutcomes.failureCode(ex), null));
                }
            }
        });

        // Phase 2: side effects, after the state above is durable.
        for (int i : deferred) {
            ActionSpec action = actions.get(i);
            try {
                switch (AutomationActionType.of(action.type())) {
                    case NOTIFY -> outcomes.set(i, notifier.notify(rule, action, snapshot, ruleScope,
                            event.requirementId()));
                    case WEBHOOK -> outcomes.set(i, webhooks.send(rule, action.configId(), action.path(),
                            event, snapshot));
                    default -> outcomes.set(i, ActionOutcomes.skipped(action.type(),
                            "ACTION_NOT_ALLOWED", null));
                }
            } catch (RuntimeException ex) {
                log.warn("automation side effect failed: rule={} type={} error={}", rule.getId(),
                        action.type(), ex.getMessage());
                outcomes.set(i, ActionOutcomes.failed(action.type(),
                        ActionOutcomes.failureCode(ex), null));
            }
        }
        return outcomes;
    }

    /**
     * Run status from per-action outcomes: any failure beside a success is PARTIAL, all-skipped is
     * SKIPPED. The run log is the only place a user learns what happened, so "PARTIAL" must mean
     * exactly "some of it happened", never "we are not sure".
     */
    public static String statusOf(List<ActionOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return "SUCCESS";
        }
        boolean anySuccess = false;
        boolean anyFailed = false;
        boolean anySkipped = false;
        for (ActionOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            switch (outcome.status()) {
                case ActionOutcomes.SUCCESS -> anySuccess = true;
                case ActionOutcomes.FAILED -> anyFailed = true;
                case ActionOutcomes.SKIPPED -> anySkipped = true;
                default -> anyFailed = true;
            }
        }
        if (anyFailed) {
            return anySuccess ? "PARTIAL" : "FAILED";
        }
        if (anySkipped) {
            return anySuccess ? "PARTIAL" : "SKIPPED";
        }
        return "SUCCESS";
    }

    /** First skip reason, for {@code automation_run.skip_reason} on an all-skipped run. */
    public static String firstSkipReason(List<ActionOutcome> outcomes) {
        if (outcomes == null) {
            return null;
        }
        for (ActionOutcome outcome : outcomes) {
            if (outcome != null && ActionOutcomes.SKIPPED.equals(outcome.status())) {
                return outcome.message();
            }
        }
        return null;
    }

    private static int ruleChainDepth(AutomationEventRow event) {
        int depth = event.chainDepth() + 1;
        return depth > 9 ? 9 : depth;
    }

    private static long actorOf(AutomationRuleEntity rule) {
        return rule.getCreatedBy() == null ? 0L : rule.getCreatedBy();
    }

    static Map<String, Object> copy(Map<String, Object> snapshot) {
        return snapshot == null ? Map.of() : new LinkedHashMap<>(snapshot);
    }
}
