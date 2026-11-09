package com.example.deliver.flow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single implementation of every value-stream formula (TECHNICAL §3.3).
 *
 * <p>Pure functions only: no clock, no SQL, no tenant. That is what makes "board, metric
 * dictionary and report must agree to the decimal" testable — they all call these four methods
 * with their own raw counts, and the arithmetic exists exactly once.</p>
 *
 * <p>Durations arrive as seconds (as stored) and leave as days with one decimal, the precision
 * the board prints ({@code 6.2 天}). Efficiency is a percentage ({@code 62.4}), not a ratio: the
 * UI shows one number and it is the one the formula returns.</p>
 */
public final class FlowMetricFormulas {

    /** Dwell is displayed with one decimal day. */
    public static final int DWELL_SCALE = 1;

    /** Efficiency is displayed with one decimal percent. */
    public static final int EFFICIENCY_SCALE = 1;

    /** A stage needs at least this many finished samples to compete for "bottleneck" (AC-9). */
    public static final int BOTTLENECK_MIN_SAMPLE = 5;

    private static final double SECONDS_PER_DAY = 86_400d;

    private FlowMetricFormulas() {
    }

    /**
     * Mean dwell in days.
     *
     * @param seconds full intervals, in seconds; {@code null}/empty means "no sample" and yields
     *                {@code null} — never {@code 0}, because a stage with no sample is not a stage
     *                that took zero days (AC-9 anti-pattern 5)
     */
    public static Double avgDwell(List<Long> seconds) {
        if (seconds == null || seconds.isEmpty()) {
            return null;
        }
        long total = 0L;
        int counted = 0;
        for (Long value : seconds) {
            if (value != null) {
                total += value;
                counted++;
            }
        }
        if (counted == 0) {
            return null;
        }
        return days((double) total / counted);
    }

    /**
     * Nearest-rank P90 dwell in days: sort ascending and take the element at
     * {@code ceil(0.9 × n)} (1-based). For {@code n=10} that is the 9th, for {@code n=11} the
     * 10th — the same definition the SQL row-number variant implements.
     */
    public static Double p90Dwell(List<Long> seconds) {
        if (seconds == null || seconds.isEmpty()) {
            return null;
        }
        List<Long> values = new ArrayList<>(seconds.size());
        for (Long value : seconds) {
            if (value != null) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        Collections.sort(values);
        // ceil(0.9 × n) in integer arithmetic: 0.9 is not exact in binary and Math.ceil(0.9*10)
        // would answer 10, not the 9 nearest-rank asks for. The SQL variant uses (9*n+9) DIV 10.
        int rank = (9 * values.size() + 9) / 10;
        if (rank < 1) {
            rank = 1;
        }
        return days(values.get(rank - 1));
    }

    /**
     * Flow efficiency as a percentage of the total lead time spent in development plus test.
     *
     * @return {@code null} when the denominator is not positive — the requirement is then
     *         excluded from the KPI instead of contributing a bogus 0 or 100
     */
    public static Double efficiency(long activeSeconds, long totalSeconds) {
        if (totalSeconds <= 0L || activeSeconds <= 0L) {
            return null;
        }
        double percent = (double) activeSeconds * 100d / (double) totalSeconds;
        if (percent > 100d) {
            percent = 100d;
        }
        return BigDecimal.valueOf(percent).setScale(EFFICIENCY_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * The stage with the longest average dwell among stages with at least
     * {@value #BOTTLENECK_MIN_SAMPLE} samples (AC-9).
     *
     * <p>Ties keep the first entry of the (order-no sorted) map, so the answer is deterministic
     * without a second sort key. Stages below the sample floor are ignored here; the page still
     * shows them with "样本不足", which it reads from {@code sampleSize}.</p>
     */
    public static Optional<String> bottleneck(Map<String, Double> avgByStage,
                                              Map<String, Integer> samples) {
        if (avgByStage == null || avgByStage.isEmpty()) {
            return Optional.empty();
        }
        String winner = null;
        double best = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> entry : avgByStage.entrySet()) {
            Double avg = entry.getValue();
            if (avg == null) {
                continue;
            }
            int count = sampleOf(samples, entry.getKey());
            if (count < BOTTLENECK_MIN_SAMPLE) {
                continue;
            }
            if (avg > best) {
                best = avg;
                winner = entry.getKey();
            }
        }
        return Optional.ofNullable(winner);
    }

    /** Ordered copy of {@code (stage → avg)} for stages that have a sample; used by the board. */
    public static Map<String, Double> avgByStage(Map<String, FlowViews.StageAgg> aggs,
                                                 List<String> codes) {
        Map<String, Double> averages = new LinkedHashMap<>();
        for (String code : codes) {
            FlowViews.StageAgg agg = aggs.get(code);
            if (agg != null && agg.dwellAvg() != null) {
                averages.put(code, agg.dwellAvg());
            }
        }
        return averages;
    }

    /** Ordered copy of {@code (stage → sampleSize)} matching {@link #avgByStage}. */
    public static Map<String, Integer> samplesByStage(Map<String, FlowViews.StageAgg> aggs,
                                                      List<String> codes) {
        Map<String, Integer> samples = new LinkedHashMap<>();
        for (String code : codes) {
            FlowViews.StageAgg agg = aggs.get(code);
            if (agg != null && agg.dwellAvg() != null) {
                samples.put(code, (int) Math.min(Integer.MAX_VALUE, agg.sampleSize()));
            }
        }
        return samples;
    }

    private static int sampleOf(Map<String, Integer> samples, String code) {
        if (samples == null) {
            return 0;
        }
        Integer value = samples.get(code);
        return value == null ? 0 : value;
    }

    private static Double days(double seconds) {
        double value = seconds / SECONDS_PER_DAY;
        if (value < 0d) {
            value = 0d;
        }
        return BigDecimal.valueOf(value).setScale(DWELL_SCALE, RoundingMode.HALF_UP).doubleValue();
    }
}
