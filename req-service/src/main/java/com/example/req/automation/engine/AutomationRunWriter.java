package com.example.req.automation.engine;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.example.req.automation.api.AutomationDtos.ActionOutcome;
import com.example.req.automation.event.AutomationEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The only class that writes {@code automation_run}.
 *
 * <p>Two invariants live here because they cannot be enforced by a caller:</p>
 * <ul>
 *   <li><b>Claim before deciding.</b> {@link #tryStart} inserts the row first and returns empty on
 *       the unique key, so "at most once per (rule, requirement, event)" is a database guarantee
 *       rather than a check the dispatcher could forget. A skipped run still occupies its slot:
 *       the semantics are at-most-once, not at-least-once.</li>
 *   <li><b>A run always names a requirement</b>, except the one documented exception below.</li>
 * </ul>
 *
 * <p>The single exception to the non-null requirement is a per-rule schedule truncation
 * ({@code SCHEDULE_TRUNCATED}, {@code requirement_id IS NULL}). MySQL unique keys treat NULLs as
 * distinct, so that row cannot be deduplicated by {@code uk_run_dedupe}; the scanner writes it with
 * an existence check instead. Every other null requirement is a programming error and throws in
 * development rather than silently defeating the unique key.</p>
 */
@Component
public class AutomationRunWriter {

    private static final Logger log = LoggerFactory.getLogger(AutomationRunWriter.class);

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String SKIP_CONDITION_NOT_MET = "CONDITION_NOT_MET";
    public static final String SKIP_RATE_LIMITED = "RATE_LIMITED";
    public static final String SKIP_SCOPE_LOST = "SCOPE_LOST";
    public static final String SKIP_SCHEDULE_TRUNCATED = "SCHEDULE_TRUNCATED";
    public static final String SKIP_SCHEMA_INVALID = "SCHEMA_INVALID";
    public static final String SKIP_NO_RECIPIENT = "NO_RECIPIENT";
    public static final String SKIP_TOO_MANY_RECIPIENTS = "TOO_MANY_RECIPIENTS";
    public static final String SKIP_TARGET_INVALID = "TARGET_INVALID";

    private static final String INSERT_SQL = "INSERT INTO automation_run"
            + " (tenant_id, rule_id, rule_version, requirement_id, event_id, event_type,"
            + " status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', NOW(3))";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AutomationRunWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Claims the (tenant, rule, requirement, event) slot.
     *
     * @return the new run id, or empty when this rule already ran for this event
     * @throws IllegalArgumentException when {@code requirementId} is null and the event is not a
     *                                  schedule truncation (see class comment)
     */
    public java.util.Optional<Long> tryStart(long tenantId, long ruleId, int ruleVersion,
                                             Long requirementId, String eventId, String eventType) {
        if (requirementId == null
                && !AutomationEventMapper.EVENT_SCHEDULE_TRUNCATED.equals(eventType)) {
            throw new IllegalArgumentException(
                    "automation_run.requirement_id may be NULL only for SCHEDULE_TRUNCATED, got "
                            + eventType + " (event " + eventId + ")");
        }
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(INSERT_SQL,
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, tenantId);
                ps.setLong(2, ruleId);
                ps.setInt(3, ruleVersion);
                if (requirementId == null) {
                    ps.setNull(4, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(4, requirementId);
                }
                ps.setString(5, eventId);
                ps.setString(6, eventType);
                return ps;
            }, keys);
        } catch (DuplicateKeyException ex) {
            // Expected on every redelivery: the slot is taken, so this rule is done for this event.
            log.debug("automation run already claimed: tenant={} rule={} event={}",
                    tenantId, ruleId, eventId);
            return java.util.Optional.empty();
        }
        Number key = keys.getKey();
        return java.util.Optional.of(key == null ? 0L : key.longValue());
    }

    /** Closes a run. {@code outcomes == null} is stored as an empty array, never as SQL NULL. */
    public void finish(long runId, String status, String skipReason, List<ActionOutcome> outcomes,
                       long durationMs) {
        String actionsJson = "[]";
        if (outcomes != null && !outcomes.isEmpty()) {
            actionsJson = objectMapper.writeValueAsString(outcomes);
        }
        jdbc.update("UPDATE automation_run SET status = ?, skip_reason = ?, actions_json = ?,"
                + " duration_ms = ? WHERE id = ?",
                status, skipReason, actionsJson, Math.max(0, durationMs), runId);
    }

    /**
     * Marks runs whose event was recovered as stale (event back to PENDING) as FAILED.
     *
     * <p>Deliberately scoped to runs whose event is <b>not</b> PROCESSING any more: a run that is
     * genuinely in flight still owns a PROCESSING event and must never be touched. Actions are not
     * replayed, only the bookkeeping is closed.</p>
     */
    public int failStaleRuns(long tenantId, int staleSeconds) {
        if (staleSeconds <= 0) {
            return 0;
        }
        Timestamp threshold = Timestamp.valueOf(LocalDateTime.now().minusSeconds(staleSeconds));
        int updated = jdbc.update("UPDATE automation_run ar"
                + " JOIN automation_event ae ON ae.tenant_id = ar.tenant_id"
                + " AND ae.event_id = ar.event_id"
                + " SET ar.status = 'FAILED', ar.skip_reason = NULL,"
                + " ar.duration_ms = TIMESTAMPDIFF(MICROSECOND, ar.created_at, NOW(3)) / 1000"
                + " WHERE ar.tenant_id = ? AND ar.status = 'RUNNING' AND ae.status = 'PENDING'"
                + " AND ar.created_at < ?", tenantId, threshold);
        if (updated > 0) {
            log.warn("automation runs marked FAILED after crash recovery: tenant={} rows={}",
                    tenantId, updated);
        }
        return updated;
    }
}
