package com.example.req.automation.event;

/**
 * A claimed {@code automation_event} row, as handed from the outbox to the dispatcher.
 *
 * <p>{@code payloadJson} is the snapshot captured at event-creation time. It is kept as text on
 * purpose: the dispatcher parses it exactly once, and a rule edited after the event was queued can
 * never be evaluated against a newer snapshot than the one the event described.</p>
 *
 * @param attempts how many times the row has been claimed <b>before</b> this claim (the claim
 *                 increments it, so the value here is the attempt that is now running, 0-based)
 */
public record AutomationEventRow(long id,
                                 long tenantId,
                                 String eventId,
                                 String eventType,
                                 Long requirementId,
                                 String origin,
                                 int chainDepth,
                                 String payloadJson,
                                 int attempts) {
}
