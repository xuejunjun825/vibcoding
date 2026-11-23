package com.example.req.automation.engine;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.req.automation.action.AutomationActionExecutor;
import com.example.req.automation.api.AutomationDtos.ActionOutcome;
import com.example.req.automation.event.AutomationEventMapper;
import com.example.req.automation.event.AutomationEventRow;
import com.example.req.automation.event.AutomationEventStore;
import com.example.req.automation.rule.AutomationRuleEntity;
import com.example.req.automation.rule.AutomationRuleRepository;
import com.example.req.automation.rule.AutomationRuleValidation;
import com.example.req.automation.spec.AutomationSpecs;
import com.example.req.automation.spec.AutomationSpecs.ActionSpec;
import com.example.req.automation.spec.AutomationSpecs.ConditionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * One claimed event, start to finish.
 *
 * <p>The two loop guards sit at the very top, before any rule is considered, so no branch can get
 * around them: an event that a rule produced ({@code origin=RULE}) or that is four hops deep is
 * dropped with <b>zero</b> runs. That is what makes "a rule chain terminates" a property of the
 * code rather than of the rules people happen to write.</p>
 *
 * <p>Per rule the order is fixed by {@link AutomationRunWriter#tryStart}: claim the idempotency slot
 * first, decide afterwards. A decision that comes before the claim would let two workers both
 * decide yes.</p>
 */
@Component
public class AutomationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AutomationDispatcher.class);

    /** Chain depth ceiling: an event whose depth is already this deep must not spawn another. */
    public static final int MAX_CHAIN_DEPTH = 4;

    private final AutomationEventStore events;
    private final AutomationRuleRepository rules;
    private final AutomationRunWriter runs;
    private final AutomationActionExecutor actions;
    private final AutomationRateLimiter rateLimiter;
    private final ScopedJdbc jdbc;
    /**
     * Unscoped reader, used only to enumerate tenants that have queued work. That query cannot carry
     * a {@code /*scope:r*}{@code /} marker — it is the query that decides which scopes to bind.
     */
    private final JdbcTemplate plainJdbc;
    private final ObjectMapper objectMapper;
    private final int claimBatchSize;
    private final int staleSeconds;

    public AutomationDispatcher(AutomationEventStore events, AutomationRuleRepository rules,
                                AutomationRunWriter runs, AutomationActionExecutor actions,
                                AutomationRateLimiter rateLimiter, ScopedJdbc jdbc,
                                JdbcTemplate plainJdbc,
                                ObjectMapper objectMapper,
                                @Value("${platform.automation.claim-batch-size:20}")
                                int claimBatchSize,
                                @Value("${platform.automation.stale-seconds:60}")
                                int staleSeconds) {
        this.events = events;
        this.rules = rules;
        this.runs = runs;
        this.actions = actions;
        this.rateLimiter = rateLimiter;
        this.jdbc = jdbc;
        this.plainJdbc = plainJdbc;
        this.objectMapper = objectMapper;
        this.claimBatchSize = Math.max(1, claimBatchSize);
        this.staleSeconds = Math.max(1, staleSeconds);
    }

    public void dispatch(AutomationEventRow event) {
        try {
            if (AutomationEventMapper.ORIGIN_RULE.equals(event.origin())) {
                events.markDropped(event.id(), "RULE_ORIGIN");
                return;
            }
            if (event.chainDepth() >= MAX_CHAIN_DEPTH) {
                events.markDropped(event.id(), "CHAIN_DEPTH");
                return;
            }
            if (event.requirementId() == null) {
                // The daily scanner writes one batch-truncation marker per rule and its SKIPPED run
                // itself (a NULL requirement cannot take part in the run unique key), so this event
                // is only a trace: consuming it must not fan out into per-rule runs.
                if (AutomationEventMapper.EVENT_SCHEDULE_TRUNCATED.equals(event.eventType())
                        || event.eventId().endsWith(":TRUNCATED")) {
                    events.markDone(event.id());
                    return;
                }
                events.markDropped(event.id(), "MALFORMED");
                return;
            }
            for (AutomationRuleEntity rule : rules
                    .findByTenantIdAndEnabledAndTriggerTypeOrderByPriorityAscIdAsc(
                            event.tenantId(), true, event.eventType())) {
                if (!AutomationRuleMatcher.matches(rule.getTriggerType(), event)) {
                    continue;
                }
                applyRule(rule, event);
            }
            events.markDone(event.id());
        } catch (RuntimeException ex) {
            // The event stays PROCESSING and the stale reaper turns it into a FAILED run; swallowing
            // here only stops one poisoned event from killing the whole poll cycle.
            log.warn("automation dispatch failed: event={} error={}", event.eventId(),
                    ex.getMessage(), ex);
        }
    }

    private void applyRule(AutomationRuleEntity rule, AutomationEventRow event) {
        Optional<Long> started = runs.tryStart(event.tenantId(), rule.getId(),
                rule.getVersion() == null ? 1 : rule.getVersion(), event.requirementId(),
                event.eventId(), event.eventType());
        if (started.isEmpty()) {
            return;
        }
        long runId = started.get();
        long begin = System.nanoTime();
        DataScope ruleScope = scopeOf(rule);
        try {
            Map<String, Object> snapshot = AutomationScopes.withScope(ruleScope,
                    () -> snapshot(rule, event));
            if (snapshot == null) {
                runs.finish(runId, AutomationRunWriter.STATUS_SKIPPED,
                        AutomationRunWriter.SKIP_SCOPE_LOST, List.of(), millis(begin));
                return;
            }
            ConditionSpec spec;
            List<ActionSpec> actionSpecs;
            try {
                spec = parseCondition(rule);
                actionSpecs = AutomationSpecs.ActionSpec.parseArray(json(rule.getActionsJson()));
            } catch (RuntimeException ex) {
                log.warn("automation rule schema invalid: rule={} error={}", rule.getId(),
                        ex.getMessage());
                runs.finish(runId, AutomationRunWriter.STATUS_SKIPPED,
                        AutomationRunWriter.SKIP_SCHEMA_INVALID, List.of(), millis(begin));
                return;
            }
            AutomationConditionEvaluator.Verdict verdict =
                    AutomationConditionEvaluator.evaluate(spec, snapshot);
            if (!verdict.matched()) {
                runs.finish(runId, AutomationRunWriter.STATUS_SKIPPED,
                        AutomationRunWriter.SKIP_CONDITION_NOT_MET, List.of(), millis(begin));
                return;
            }
            if (!rateLimiter.tryAcquire(event.tenantId())) {
                runs.finish(runId, AutomationRunWriter.STATUS_SKIPPED,
                        AutomationRunWriter.SKIP_RATE_LIMITED, List.of(), millis(begin));
                return;
            }
            List<ActionOutcome> outcomes = AutomationScopes.withScope(ruleScope,
                    () -> actions.execute(rule, spec, actionSpecs, event, ruleScope, snapshot));
            String status = AutomationActionExecutor.statusOf(outcomes);
            String skipReason = AutomationRunWriter.STATUS_SKIPPED.equals(status)
                    ? AutomationActionExecutor.firstSkipReason(outcomes) : null;
            runs.finish(runId, status, skipReason, outcomes, millis(begin));
        } catch (RuntimeException ex) {
            log.warn("automation rule execution failed: rule={} event={} error={}", rule.getId(),
                    event.eventId(), ex.getMessage(), ex);
            runs.finish(runId, AutomationRunWriter.STATUS_FAILED, "ERROR", List.of(),
                    millis(begin));
        }
    }

    /**
     * Row snapshot read <b>under the rule's scope</b>: no row means the requirement is outside what
     * the rule may touch, which is a SKIPPED run, never an error.
     */
    private Map<String, Object> snapshot(AutomationRuleEntity rule, AutomationEventRow event) {
        List<Map<String, Object>> rows = jdbc.list(
                "SELECT r.* FROM requirement r WHERE r.id = :id AND /*scope:r*/",
                Map.of("id", event.requirementId()));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> merged = new LinkedHashMap<>(
                AutomationSnapshot.fromRow(rows.get(0)).asMap());
        AutomationSnapshot overlay = AutomationSnapshot.fromRow(rows.get(0))
                .overlay(event.payloadJson(), objectMapper);
        merged.putAll(overlay.asMap());
        merged.put("requirementId", event.requirementId());
        merged.put("ruleId", rule.getId());
        return merged;
    }

    private ConditionSpec parseCondition(AutomationRuleEntity rule) {
        String json = rule.getConditionJson();
        if (json == null || json.isBlank()) {
            return ConditionSpec.EMPTY;
        }
        return ConditionSpec.parse(json(rule.getConditionJson()));
    }

    private tools.jackson.databind.JsonNode json(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(raw);
    }

    /** Rule scope, expressed as the same {@link DataScope} the request path uses. */
    public static DataScope scopeOf(AutomationRuleEntity rule) {
        long tenantId = rule.getTenantId() == null ? 0L : rule.getTenantId();
        Long scopeId = rule.getScopeId();
        if (AutomationRuleValidation.isDomainScope(rule.getScopeType()) && scopeId != null) {
            return DataScope.of(tenantId, ScopeTier.DOMAIN, java.util.Set.of(scopeId), null, 0L,
                    java.util.Set.of());
        }
        if (scopeId != null) {
            return DataScope.of(tenantId, ScopeTier.VENDOR, java.util.Set.of(), scopeId, 0L,
                    java.util.Set.of());
        }
        return DataScope.system(tenantId);
    }

    /**
     * Drain every tenant that has work: stale recovery first (so a crashed poll cannot strand rows
     * in PROCESSING forever), then claim-and-dispatch batches until the queue is empty.
     */
    public void drain() {
        List<Long> tenants = plainJdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM automation_event"
                        + " WHERE status IN ('PENDING', 'PROCESSING')",
                Long.class);
        for (Long tenantId : tenants) {
            try {
                events.recoverStale(tenantId, staleSeconds);
                runs.failStaleRuns(tenantId, staleSeconds);
                while (true) {
                    List<AutomationEventRow> batch = events.claim(tenantId, claimBatchSize);
                    if (batch.isEmpty()) {
                        break;
                    }
                    batch.forEach(this::dispatch);
                    if (batch.size() < claimBatchSize) {
                        break;
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("automation drain failed: tenant={} error={}", tenantId, ex.getMessage());
            }
        }
    }

    private static long millis(long beginNanos) {
        return (System.nanoTime() - beginNanos) / 1_000_000L;
    }
}
