package com.example.deliver.executor;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The one place that turns an executor's facts into a health word (AC-16).
 *
 * <p>{@code enabled} and {@code last_report_at} are the only stored facts; the five words are
 * derived here so the ledger page, a report card and the API can never disagree about whether an
 * executor is late. The thresholds live here as constants — a page that re-implements "24 hours"
 * is a defect, not a styling choice.</p>
 */
public final class ExecutorHealth {

    /** A report inside this window means the executor is online. */
    public static final int ONLINE_HOURS = 24;
    /** A report inside this window (but outside {@link #ONLINE_HOURS}) means it is stale. */
    public static final int STALE_HOURS = 72;

    public static final String ONLINE = "ONLINE";
    public static final String STALE = "STALE";
    public static final String OFFLINE = "OFFLINE";
    public static final String NEVER = "NEVER";
    public static final String DISABLED = "DISABLED";

    private ExecutorHealth() {
    }

    /**
     * Derive the health word.
     *
     * <p>Order matters: disabled beats a recent report (a disabled executor is refused, so a stale
     * timestamp there would be a lie), and "never reported" is not the same as "offline" — the page
     * shows a different sentence for a freshly registered executor.</p>
     *
     * @param enabled      the stored flag; false always means {@link #DISABLED}
     * @param lastReportAt the stored fact, null when it never reported
     * @param now          injected so the boundary is testable with a frozen clock (null = wall clock)
     */
    public static String of(boolean enabled, LocalDateTime lastReportAt, LocalDateTime now) {
        if (!enabled) {
            return DISABLED;
        }
        if (lastReportAt == null) {
            return NEVER;
        }
        LocalDateTime reference = now == null ? LocalDateTime.now() : now;
        Duration age = Duration.between(lastReportAt, reference);
        if (age.isNegative()) {
            // A report timestamp slightly ahead of the server clock is still "just reported":
            // calling it offline would page someone at 3am for a clock skew.
            return ONLINE;
        }
        if (age.compareTo(Duration.ofHours(ONLINE_HOURS)) <= 0) {
            return ONLINE;
        }
        if (age.compareTo(Duration.ofHours(STALE_HOURS)) <= 0) {
            return STALE;
        }
        return OFFLINE;
    }
}
