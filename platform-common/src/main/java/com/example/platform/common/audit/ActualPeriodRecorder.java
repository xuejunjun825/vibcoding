package com.example.platform.common.audit;

import java.time.LocalDate;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The single writer of the four business objects' actual period ({@code actual_start_date} /
 * {@code actual_end_date}).
 *
 * <p>Why one place: the gantt draws a <b>light bar</b> under the planned bar, and that light bar is
 * only trustworthy if every status transition writes the same two columns with the same caliber as
 * the V9 backfill. A second implementation inside the gantt read/write path would drift (one counts
 * the plan bar's start, the other the first {@code IN_DEV} audit) and the chart would show two
 * different truths for the same requirement.</p>
 *
 * <p>Caliber, identical to {@code V9__ccode_cteam_ctest_p0.sql} step 4 (first entry wins, nothing
 * is ever overwritten):</p>
 * <ul>
 *   <li>{@code REQUIREMENT}: opens on {@code IN_DEV}; closes on
 *       {@code DELIVERED / DONE / ACCEPTED_CLOSED / CLOSED}.</li>
 *   <li>{@code SPRINT}: opens on {@code ACTIVE}; closes on {@code CLOSED}.</li>
 *   <li>{@code TASK}: opens on {@code IN_PROGRESS}; closes on {@code DONE / CANCELLED}.</li>
 *   <li>{@code RELEASE}: opens on {@code IN_PROGRESS} (or on {@code PUBLISHED} when a release never
 *       passed through {@code IN_PROGRESS}); closes on {@code RELEASED / CLOSED}.</li>
 * </ul>
 *
 * <p>Write-once is enforced by SQL ({@code ... AND actual_start_date IS NULL}), not by a read-then-
 * write in Java: two transitions racing on the same row would otherwise both see {@code NULL} and
 * the second would overwrite the first. Same-transaction as the transition, so a rolled-back
 * transition leaves no half period behind.</p>
 *
 * <p>Static because the call sites are the status-change methods of two services, which should not
 * have to inject a date helper to record a date; the {@link JdbcTemplate} is installed once by
 * {@link Wiring} at startup (component scan covers {@code com.example}).</p>
 */
public final class ActualPeriodRecorder {

    /** Audit {@code biz_type} values this recorder understands - the same strings callers audit. */
    public static final String REQUIREMENT = "REQUIREMENT";
    public static final String RELEASE = "RELEASE";
    public static final String SPRINT = "SPRINT";
    public static final String TASK = "TASK";

    private static final Logger log = LoggerFactory.getLogger(ActualPeriodRecorder.class);

    private static volatile JdbcTemplate jdbc;

    private ActualPeriodRecorder() {
    }

    /**
     * Records the actual-period consequence of one status transition.
     *
     * <p>Never throws: a missing date must not fail a delivery. It is however loud in the log,
     * because a silently missing actual bar is the kind of "the chart is just empty" bug nobody
     * reports.</p>
     *
     * @param bizType one of {@link #REQUIREMENT} / {@link #RELEASE} / {@link #SPRINT} / {@link #TASK}
     * @param id      the biz row id (already resolved by the caller)
     * @param from    status before the transition (may be {@code null} for a created row)
     * @param to      status after the transition
     */
    public static void record(String bizType, long id, String from, String to) {
        if (id <= 0 || to == null) {
            return;
        }
        // A re-issue of the same status (or a no-op action) is not a period boundary.
        if (from != null && from.equalsIgnoreCase(to)) {
            return;
        }
        boolean opensStart = startsActualStart(bizType, to);
        boolean closesEnd = closesActualEnd(bizType, to);
        if (!opensStart && !closesEnd) {
            return;
        }
        DataScope scope = TenantContext.scopeOrNull();
        if (scope == null) {
            // No tenant => no safe target row. Scheduled work that changes status must establish a
            // scope first (TenantContext.set), exactly like AuditService requires for its rows.
            log.warn("actual period skipped: no tenant scope (bizType={} id={} to={})", bizType, id, to);
            return;
        }
        JdbcTemplate template = jdbc;
        if (template == null) {
            log.warn("actual period skipped: recorder is not wired (bizType={} id={})", bizType, id);
            return;
        }
        String table = tableFor(bizType);
        if (table == null) {
            log.warn("actual period skipped: unknown bizType {} (id={})", bizType, id);
            return;
        }
        LocalDate today = LocalDate.now();
        try {
            if (opensStart) {
                template.update("UPDATE " + table + " SET actual_start_date = ?"
                                + " WHERE id = ? AND tenant_id = ? AND actual_start_date IS NULL",
                        today, id, scope.tenantId());
            }
            if (closesEnd) {
                template.update("UPDATE " + table + " SET actual_end_date = ?"
                                + " WHERE id = ? AND tenant_id = ? AND actual_end_date IS NULL",
                        today, id, scope.tenantId());
            }
        } catch (RuntimeException ex) {
            log.warn("actual period write failed (bizType={} id={} to={}): {}",
                    bizType, id, to, ex.getMessage());
        }
    }

    /** True when this target status opens the actual period (shared with the V9 backfill caliber). */
    public static boolean startsActualStart(String bizType, String to) {
        if (to == null) {
            return false;
        }
        String status = to.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (bizType == null ? "" : bizType.trim().toUpperCase(java.util.Locale.ROOT)) {
            case REQUIREMENT -> "IN_DEV".equals(status);
            // A release can jump straight to PUBLISHED; that is still the day work actually began.
            case RELEASE -> "IN_PROGRESS".equals(status) || "PUBLISHED".equals(status);
            case SPRINT -> "ACTIVE".equals(status);
            case TASK -> "IN_PROGRESS".equals(status);
            default -> false;
        };
    }

    /** True when this target status closes the actual period. */
    public static boolean closesActualEnd(String bizType, String to) {
        if (to == null) {
            return false;
        }
        String status = to.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (bizType == null ? "" : bizType.trim().toUpperCase(java.util.Locale.ROOT)) {
            case REQUIREMENT -> "DELIVERED".equals(status) || "DONE".equals(status)
                    || "ACCEPTED_CLOSED".equals(status) || "CLOSED".equals(status);
            case RELEASE -> "RELEASED".equals(status) || "CLOSED".equals(status);
            case SPRINT -> "CLOSED".equals(status);
            case TASK -> "DONE".equals(status) || "CANCELLED".equals(status);
            default -> false;
        };
    }

    /**
     * Table for a biz type, or {@code null} for anything else. The four names are constants - never
     * caller input - so concatenating them into SQL cannot inject.
     */
    private static String tableFor(String bizType) {
        if (bizType == null) {
            return null;
        }
        return switch (bizType.trim().toUpperCase(java.util.Locale.ROOT)) {
            case REQUIREMENT -> "requirement";
            // `release` is a reserved word in MySQL: the backticks are mandatory.
            case RELEASE -> "`release`";
            case SPRINT -> "sprint";
            case TASK -> "task";
            default -> null;
        };
    }

    /** Startup wiring for the static template (component scan covers {@code com.example}). */
    @org.springframework.stereotype.Component
    static final class Wiring {

        Wiring(JdbcTemplate jdbc) {
            ActualPeriodRecorder.jdbc = jdbc;
        }
    }
}
