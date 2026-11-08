package com.example.platform.contract;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * The four metric formulas, in one place.
 *
 * <p>Queries return raw counts only; every division happens here. That is what makes
 * "board card = drill-down rows = matrix column = release report" reproducible: a second
 * formula in a SQL statement is how two screens end up disagreeing by a rounding step.</p>
 *
 * <p>Zero denominators return {@code 0.00} instead of {@code NaN}/{@code null} — a dashboard
 * tile must always render a number, and an empty domain legitimately has density 0.</p>
 */
public final class MetricFormulas {

    public static final String DEFECT_DENSITY = "DEFECT_DENSITY";
    public static final String REOPEN_RATE = "REOPEN_RATE";
    public static final String TEST_PASS_RATE = "TEST_PASS_RATE";
    public static final String MTTR = "MTTR";

    /** Two decimals is the display precision of every board card. */
    public static final int SCALE = 2;

    private MetricFormulas() {
    }

    /** DEFECT_DENSITY = defects / requirements. */
    public static BigDecimal defectDensity(long defects, long requirements) {
        return ratio(defects, requirements);
    }

    /** REOPEN_RATE = defects with reopen_count &gt; 0 / all defects. */
    public static BigDecimal reopenRate(long reopened, long total) {
        return ratio(reopened, total);
    }

    /** TEST_PASS_RATE = passed / planned (strict: NOT_RUN counts against the rate). */
    public static BigDecimal passRate(long passed, long planned) {
        return ratio(passed, planned);
    }

    /** Executed pass rate = passed / executed (auxiliary column, never the headline). */
    public static BigDecimal executedPassRate(long passed, long executed) {
        return ratio(passed, executed);
    }

    /** Coverage = executed / planned. */
    public static BigDecimal coverage(long executed, long planned) {
        return ratio(executed, planned);
    }

    /**
     * MTTR = mean of (closed_at − found_at) over closed defects, in minutes.
     *
     * <p>Durations are computed by SQL/JPA from the row's timestamps; this method only averages
     * them, so "closed after 3 days" and "closed in 20 minutes" share one rounding rule.</p>
     */
    public static BigDecimal mttrMinutes(Collection<Duration> closedDurations) {
        if (closedDurations == null || closedDurations.isEmpty()) {
            return scale(BigDecimal.ZERO);
        }
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (Duration duration : closedDurations) {
            if (duration == null || duration.isNegative()) {
                continue;
            }
            total = total.add(BigDecimal.valueOf(duration.toSeconds()));
            count++;
        }
        if (count == 0) {
            return scale(BigDecimal.ZERO);
        }
        return total.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(60), SCALE, RoundingMode.HALF_UP);
    }

    /** Same as {@link #mttrMinutes(Collection)} but from raw second values (SQL AVG result). */
    public static BigDecimal mttrFromSeconds(BigDecimal averageSeconds) {
        if (averageSeconds == null) {
            return scale(BigDecimal.ZERO);
        }
        return averageSeconds.divide(BigDecimal.valueOf(60), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return scale(BigDecimal.ZERO);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Metric dictionary rows seeded once per tenant; the code is the stable key. */
    public static List<String> codes() {
        return List.of(DEFECT_DENSITY, REOPEN_RATE, TEST_PASS_RATE, MTTR);
    }
}
