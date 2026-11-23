package com.example.platform.common.audit;

import java.util.Map;

/**
 * Side-channel published by {@link AuditService} after the audit row is written.
 *
 * <p>Called <b>synchronously, in the caller's transaction and thread</b>, between the audit
 * {@code INSERT} and the business commit. That is the whole point: an event derived from a
 * business action must disappear together with the action when it rolls back, so this cannot be
 * an async listener. The concrete consumer (r39 {@code AutomationEventOutbox}) translates the
 * audit action into automation events.</p>
 *
 * <p>The mapping from audit action to business event is <b>requirement-domain knowledge</b>, so it
 * lives in {@code req-service}; this SPI keeps the dependency direction
 * {@code req-service -> platform-common} instead of dragging domain semantics into the shared
 * module.</p>
 *
 * <p>Contract for implementations: <b>swallow your own exceptions</b> and log them. Audit is on
 * the main business path — a failing listener must never roll back the business transaction.
 * {@code detail} is a read-only view (already augmented with the inserted {@code auditId}); do not
 * mutate the map.</p>
 */
public interface AuditListener {

    /**
     * @param action  audit action, e.g. {@code CREATE} / {@code STATE_CHANGE} / {@code UPDATE}
     * @param bizType audited aggregate type, e.g. {@code REQUIREMENT} / {@code RELEASE}
     * @param bizId   audited aggregate id (the release id for release-level actions)
     * @param detail  audit detail; carries {@code auditId} (the new {@code audit_log.id}) when the
     *                row id could be read back
     */
    void onAudit(String action, String bizType, Long bizId, Map<String, Object> detail);
}
