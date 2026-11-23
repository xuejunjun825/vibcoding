package com.example.req.automation.event;

import com.example.platform.common.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The only class that touches {@code automation_event}.
 *
 * <p>Write/claim/recover are split from the dispatcher on purpose: the row's {@code status} has
 * exactly one state machine, and it lives here. Two places writing {@code status} is how a row
 * ends up {@code PROCESSING} forever.</p>
 *
 * <p>{@link #claim} runs in its own short {@code REQUIRES_NEW} transaction because
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} only holds its locks for the length of a transaction:
 * it must commit before the (slow, external-call-containing) dispatch starts, otherwise a webhook
 * timeout would pin row locks for the whole call.</p>
 */
@Component
public class AutomationEventStore {

    private static final Logger log = LoggerFactory.getLogger(AutomationEventStore.class);

    private static final String COLUMNS = "id, tenant_id, event_id, event_type, requirement_id,"
            + " origin, chain_depth, payload_json, attempts";

    private static final String INSERT_SQL = "INSERT IGNORE INTO automation_event"
            + " (tenant_id, event_id, event_type, requirement_id, origin, chain_depth,"
            + " payload_json, status, attempts, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, NOW(3))";

    private static final RowMapper<AutomationEventRow> ROW_MAPPER = (rs, rowNum) ->
            new AutomationEventRow(
                    rs.getLong("id"),
                    rs.getLong("tenant_id"),
                    rs.getString("event_id"),
                    rs.getString("event_type"),
                    rs.getObject("requirement_id") == null ? null : rs.getLong("requirement_id"),
                    rs.getString("origin"),
                    rs.getInt("chain_depth"),
                    rs.getString("payload_json"),
                    rs.getInt("attempts"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public AutomationEventStore(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Writes the drafts into the caller's transaction, so the event and the business change commit
     * or roll back together. {@code INSERT IGNORE} turns a duplicate
     * {@code uk_event_dedupe(tenant_id, event_id)} into "0 rows" instead of a constraint error: the
     * same business action retried must not fail, it must simply not produce a second event.
     *
     * @return how many rows were actually inserted (0 for every duplicate)
     */
    public int insertIgnore(List<AutomationEventDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return 0;
        }
        long tenantId = TenantContext.require().tenantId();
        int inserted = 0;
        for (AutomationEventDraft draft : drafts) {
            inserted += jdbc.update(INSERT_SQL,
                    tenantId,
                    draft.eventId(),
                    draft.eventType(),
                    draft.requirementId(),
                    draft.origin(),
                    draft.chainDepth(),
                    json(draft.payload()));
        }
        return inserted;
    }

    /**
     * Claims up to {@code limit} pending rows for this tenant.
     *
     * <p>{@code SKIP LOCKED} is what lets two worker threads (or two instances) drain the same
     * queue without either blocking or double-processing; the returned rows are already
     * {@code PROCESSING}, so a crash after this point is recovered by {@link #recoverStale}.</p>
     */
    public List<AutomationEventRow> claim(long tenantId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<AutomationEventRow> claimed = requiresNew.execute(status -> {
            List<AutomationEventRow> rows = jdbc.query(
                    "SELECT " + COLUMNS + " FROM automation_event"
                            + " WHERE tenant_id = ? AND status = 'PENDING'"
                            + " ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                    ROW_MAPPER, tenantId, limit);
            if (rows.isEmpty()) {
                return rows;
            }
            List<Object> args = new ArrayList<>(rows.size() + 1);
            args.add(Timestamp.valueOf(LocalDateTime.now()));
            for (AutomationEventRow row : rows) {
                args.add(row.id());
            }
            jdbc.update("UPDATE automation_event SET status = 'PROCESSING', locked_at = ?,"
                            + " attempts = attempts + 1 WHERE id IN ("
                            + String.join(", ", Collections.nCopies(rows.size(), "?")) + ")",
                    args.toArray());
            return rows;
        });
        return claimed == null ? List.of() : claimed;
    }

    public void markDone(long id) {
        jdbc.update("UPDATE automation_event SET status = 'DONE', processed_at = NOW(3)"
                + " WHERE id = ?", id);
    }

    /** Drops an event that no rule asked for (no active rule at dispatch time). */
    public void markDropped(long id, String dropReason) {
        jdbc.update("UPDATE automation_event SET status = 'DROPPED', drop_reason = ?,"
                + " processed_at = NOW(3) WHERE id = ?", trim(dropReason), id);
    }

    /**
     * Returns rows stuck in {@code PROCESSING} to the queue (worker crash / restart).
     *
     * @return how many rows were recovered
     */
    public int recoverStale(long tenantId, int staleSeconds) {
        if (staleSeconds <= 0) {
            return 0;
        }
        Timestamp threshold = Timestamp.valueOf(LocalDateTime.now().minusSeconds(staleSeconds));
        int recovered = jdbc.update("UPDATE automation_event SET status = 'PENDING',"
                + " locked_at = NULL WHERE tenant_id = ? AND status = 'PROCESSING'"
                + " AND locked_at IS NOT NULL AND locked_at < ?", tenantId, threshold);
        if (recovered > 0) {
            log.info("automation events recovered: tenant={} rows={}", tenantId, recovered);
        }
        return recovered;
    }

    private String json(java.util.Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? java.util.Map.of() : payload);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("automation event payload is not serialisable", ex);
        }
    }

    private static String trim(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= 32 ? trimmed : trimmed.substring(0, 32);
    }
}
