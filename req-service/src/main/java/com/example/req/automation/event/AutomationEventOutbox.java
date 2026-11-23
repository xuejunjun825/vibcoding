package com.example.req.automation.event;

import com.example.platform.common.audit.AuditListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Audit listener that turns audit rows into {@code automation_event} rows.
 *
 * <p>Registered as a bean, so {@code AuditService} calls it in the business transaction; the
 * automation engine therefore has exactly one event source and the frontend has none
 * ({@code /automation/events} does not exist).</p>
 *
 * <p>Failure policy: the listener <b>never</b> propagates. Audit rides on the main business path —
 * a broken outbox must cost the event, not the user's save. Losses are logged with the action so
 * they are greppable, and the event itself can always be reconstructed from {@code audit_log}.</p>
 */
@Component
public class AutomationEventOutbox implements AuditListener {

    private static final Logger log = LoggerFactory.getLogger(AutomationEventOutbox.class);

    private final AutomationEventStore store;

    public AutomationEventOutbox(AutomationEventStore store) {
        this.store = store;
    }

    @Override
    public void onAudit(String action, String bizType, Long bizId, Map<String, Object> detail) {
        try {
            List<AutomationEventDraft> drafts =
                    AutomationEventMapper.map(action, bizType, bizId, detail);
            if (drafts.isEmpty()) {
                return;
            }
            int inserted = store.insertIgnore(drafts);
            if (inserted < drafts.size()) {
                // Duplicates are the normal path when a request is retried; say so at debug level.
                log.debug("automation events deduped: {} {} {} drafted={} inserted={}",
                        action, bizType, bizId, drafts.size(), inserted);
            }
        } catch (RuntimeException ex) {
            log.warn("automation event publish failed: {} {} {} ({})",
                    action, bizType, bizId, ex.toString());
        }
    }

    /**
     * Escape hatch for event producers that are not audit actions (the daily scan inserts
     * {@code SCHEDULE} drafts directly). Shares the dedupe key with {@link #onAudit}.
     */
    public int publish(List<AutomationEventDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return 0;
        }
        try {
            return store.insertIgnore(drafts);
        } catch (RuntimeException ex) {
            log.warn("automation event publish failed: drafted={} ({})", drafts.size(), ex.toString());
            return 0;
        }
    }
}
