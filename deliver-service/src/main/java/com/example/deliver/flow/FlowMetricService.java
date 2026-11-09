package com.example.deliver.flow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.platform.common.scope.PageResponse;
import org.springframework.stereotype.Service;

/**
 * The single numeric entry point for the value stream (AC-9).
 *
 * <p>The board, the metric dictionary and any report must return the <em>same number</em> for the
 * same window. That only holds if all three walk this class, and if this class computes nothing
 * itself: averages, the nearest-rank P90, efficiency and the bottleneck come from
 * {@link FlowMetricFormulas}, WIP and dwell samples from {@link FlowQuery}.</p>
 *
 * <p>{@link #values(FlowViews.Query)} is deliberately a projection of {@link #board}: it never
 * recomputes from raw rows, so a rounding difference between the dictionary and the board is
 * impossible by construction.</p>
 */
@Service
public class FlowMetricService {

    /** Unknown values carry {@code null} — the page prints 「样本不足」, never a fabricated 0. */
    public static final String CODE_WIP = "FLOW_WIP";
    public static final String CODE_DWELL_AVG = "FLOW_DWELL_AVG";
    public static final String CODE_DWELL_P90 = "FLOW_DWELL_P90";
    public static final String CODE_EFFICIENCY = "FLOW_EFFICIENCY";
    public static final String CODE_BOTTLENECK = "FLOW_BOTTLENECK";
    public static final String CODE_NO_DATA = "FLOW_NO_DATA";
    public static final String CODE_DELIVERED = "FLOW_DELIVERED";
    public static final String CODE_EFFICIENCY_INCLUDED = "FLOW_EFFICIENCY_INCLUDED";
    public static final String CODE_EFFICIENCY_EXCLUDED = "FLOW_EFFICIENCY_EXCLUDED";

    private final FlowQuery queries;
    private final FlowStageService stages;

    public FlowMetricService(FlowQuery queries, FlowStageService stages) {
        this.queries = queries;
        this.stages = stages;
    }

    // ------------------------------------------------------------------ board

    public FlowViews.Board board(FlowViews.Query query) {
        FlowViews.Query window = queries.window(query);
        List<FlowViews.StageView> configured = stages.enabled();
        Map<String, Long> wip = queries.wip(window);
        Map<String, List<Long>> samples = queries.dwellSamples(window);

        Map<String, Double> avgByStage = new LinkedHashMap<>();
        Map<String, Integer> sampleByStage = new LinkedHashMap<>();
        List<FlowViews.StageCard> cards = new ArrayList<>(configured.size());
        for (FlowViews.StageView stage : configured) {
            boolean applicable = FlowStageService.applicable(stage, window.reqType());
            List<Long> seconds = samples.getOrDefault(stage.code(), List.of());
            Double avg = FlowMetricFormulas.avgDwell(seconds);
            Double p90 = FlowMetricFormulas.p90Dwell(seconds);
            long stageWip = wip.getOrDefault(stage.code(), 0L);
            if (applicable) {
                avgByStage.put(stage.code(), avg);
                sampleByStage.put(stage.code(), seconds.size());
            }
            cards.add(new FlowViews.StageCard(stage.code(), stage.name(), stage.orderNo(), applicable,
                    applicable ? stageWip : 0L, applicable ? avg : null, applicable ? p90 : null,
                    applicable ? seconds.size() : 0, 0, false));
        }

        Optional<String> bottleneck = FlowMetricFormulas.bottleneck(avgByStage, sampleByStage);
        long noData = queries.noDataCount(window);
        List<FlowViews.StageCard> withBottleneck = new ArrayList<>(cards.size());
        for (FlowViews.StageCard card : cards) {
            boolean flagged = bottleneck.isPresent() && bottleneck.get().equals(card.code());
            withBottleneck.add(new FlowViews.StageCard(card.code(), card.name(), card.orderNo(),
                    card.applicable(), card.wip(), card.dwellAvg(), card.dwellP90(),
                    card.sampleSize(), noData, flagged));
        }
        FlowViews.Efficiency efficiency = queries.efficiency(window);
        return new FlowViews.Board(new FlowViews.Window(window.from(), window.to()),
                withBottleneck, efficiency, noData, LocalDateTime.now());
    }

    // ------------------------------------------------------------------ metric projection

    /**
     * Flat numeric projection of {@link #board} for the metric dictionary preview.
     *
     * <p>Keys: the five registered codes ({@code FLOW_WIP}, {@code FLOW_DWELL_AVG},
     * {@code FLOW_DWELL_P90}, {@code FLOW_EFFICIENCY}, {@code FLOW_BOTTLENECK}) plus
     * {@code <CODE>.<stageCode>} per column and the two efficiency denominators. A metric without
     * enough samples maps to {@code null}, which the dictionary renders as 「样本不足」.</p>
     */
    public Map<String, Double> values(FlowViews.Query query) {
        FlowViews.Board board = board(query);
        Map<String, Double> out = new LinkedHashMap<>();
        long wip = 0;
        double weighted = 0;
        long weightedSamples = 0;
        Double p90 = null;
        Double bottleneckAvg = null;
        for (FlowViews.StageCard card : board.stages()) {
            if (!card.applicable()) {
                continue;
            }
            wip += card.wip();
            out.put(CODE_WIP + "." + card.code(), (double) card.wip());
            out.put(CODE_DWELL_AVG + "." + card.code(), card.dwellAvg());
            out.put(CODE_DWELL_P90 + "." + card.code(), card.dwellP90());
            if (card.dwellAvg() != null && card.sampleSize() > 0) {
                weighted += card.dwellAvg() * card.sampleSize();
                weightedSamples += card.sampleSize();
            }
            if (card.dwellP90() != null && (p90 == null || card.dwellP90() > p90)) {
                p90 = card.dwellP90();
            }
            if (card.bottleneck()) {
                bottleneckAvg = card.dwellAvg();
            }
        }
        out.put(CODE_WIP, (double) wip);
        out.put(CODE_DWELL_AVG, weightedSamples == 0 ? null
                : round(weighted / weightedSamples));
        out.put(CODE_DWELL_P90, p90);
        out.put(CODE_BOTTLENECK, bottleneckAvg);
        out.put(CODE_EFFICIENCY, board.efficiency().value());
        out.put(CODE_EFFICIENCY_INCLUDED, (double) board.efficiency().included());
        out.put(CODE_EFFICIENCY_EXCLUDED, (double) board.efficiency().excluded());
        out.put(CODE_DELIVERED,
                (double) (board.efficiency().included() + board.efficiency().excluded()));
        out.put(CODE_NO_DATA, (double) board.noDataCount());
        return out;
    }

    /** Unit for a flow metric code, so the dictionary does not have to guess. */
    public static String unit(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case CODE_WIP, CODE_DELIVERED, CODE_NO_DATA, CODE_EFFICIENCY_INCLUDED,
                 CODE_EFFICIENCY_EXCLUDED -> "count";
            case CODE_EFFICIENCY -> "percent";
            default -> "days";
        };
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(FlowMetricFormulas.DWELL_SCALE,
                RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------------ drill

    public PageResponse<FlowViews.DrillRow> drill(FlowViews.DrillQuery query) {
        return queries.drill(query);
    }

    /** The normalised window (default 90 days), so callers echo exactly what was measured. */
    public FlowViews.Query window(FlowViews.Query query) {
        return queries.window(query);
    }

    public List<FlowViews.TimelineRow> timeline(long requirementId, java.time.LocalDate from,
                                                java.time.LocalDate to) {
        return queries.timeline(requirementId, from, to);
    }
}
