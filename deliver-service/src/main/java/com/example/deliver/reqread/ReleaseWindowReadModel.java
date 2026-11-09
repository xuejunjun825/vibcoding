package com.example.deliver.reqread;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.scope.ScopedJdbc;
import org.springframework.stereotype.Component;

/**
 * The read-only release observation window: when the release went live and how long it is watched.
 *
 * <p>The gate's "post-release 72h defects" metric and the defect escape flag have to use the same
 * window, otherwise a defect can be counted as escaped on one page and as in-release on another
 * (AC-8.3/8.5). The window starts at the earliest successful deploy of the release — the
 * {@code release} table carries no {@code ended_at} — and lasts
 * {@code release.observation_hours} (default 72, bounds 24..168 enforced by the V5 CHECK).</p>
 *
 * <p>This is the only cross-domain read of {@code deploy_order}; it is read-only by contract and
 * the table is not writable from this package.</p>
 */
@Component
public class ReleaseWindowReadModel {

    public static final int DEFAULT_OBSERVATION_HOURS = 72;

    /** {@code successEndedAt} is null when the release was never deployed successfully. */
    public record Window(Instant successEndedAt, int observationHours, boolean hasSuccessRelease) {

        /** End of the observation window, or null when it never opened. */
        public Instant windowEnd() {
            return successEndedAt == null ? null : successEndedAt.plusSeconds(observationHours * 3600L);
        }
    }

    private final ScopedJdbc jdbc;

    public ReleaseWindowReadModel(ScopedJdbc jdbc) {
        this.jdbc = jdbc;
    }

    public Window window(long releaseId) {
        List<Map<String, Object>> rows = jdbc.list("""
                SELECT rel.observation_hours, o.ended_at
                FROM `release` rel
                LEFT JOIN deploy_order o
                       ON o.release_id = rel.id
                      AND o.tenant_id = rel.tenant_id
                      AND o.execution_result = 'SUCCESS'
                      AND o.ended_at IS NOT NULL
                WHERE /*scope:rel*/ AND rel.id = :releaseId
                ORDER BY o.ended_at ASC
                LIMIT 1
                """, Map.of("releaseId", releaseId));
        if (rows.isEmpty()) {
            return new Window(null, DEFAULT_OBSERVATION_HOURS, false);
        }
        Map<String, Object> row = rows.get(0);
        int hours = Rows.toInt(Rows.object(row, "observation_hours"), DEFAULT_OBSERVATION_HOURS);
        if (hours < 24 || hours > 168) {
            hours = DEFAULT_OBSERVATION_HOURS;
        }
        LocalDateTime endedAt = Rows.toDateTime(Rows.object(row, "ended_at"));
        if (endedAt == null) {
            return new Window(null, hours, false);
        }
        return new Window(endedAt.atZone(ZoneId.systemDefault()).toInstant(), hours, true);
    }
}
