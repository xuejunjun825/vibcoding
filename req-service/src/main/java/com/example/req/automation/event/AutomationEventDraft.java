package com.example.req.automation.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One automation event, ready to be inserted into {@code automation_event}.
 *
 * <p>Produced by {@link AutomationEventMapper} from an audit action. {@code eventId} is the
 * dedupe key ({@code uk_event_dedupe(tenant_id, event_id)}): it always ends with the
 * {@code audit_log.id} of the action that caused it, so a retried request produces a second row
 * only when it produced a second audit row — and a rolled-back action produces none at all.</p>
 *
 * <p>{@code requirementId} is null only for the daily-scan truncation row
 * ({@code SCHEDULE_TRUNCATED}); every other event must name a requirement, otherwise the run
 * unique key {@code (tenant, rule, requirement, event)} would degrade into a global lock.</p>
 *
 * @param payload the requirement snapshot the conditions are later evaluated against (see
 *                TECHNICAL §4.2); it stays JSON in the outbox so a rule edit cannot change the
 *                meaning of an event that was already accepted.
 */
public record AutomationEventDraft(String eventId,
                                   String eventType,
                                   Long requirementId,
                                   String origin,
                                   int chainDepth,
                                   Map<String, Object> payload) {

    public AutomationEventDraft {
        // Not Map.copyOf: the snapshot keeps explicit null placeholders for the keys an audit
        // action does not carry (§4.2), and Map.of / copyOf reject null values.
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
