package com.example.deliver.flow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Wire contracts for the value-stream board (TECHNICAL §2.4 / §3.3).
 *
 * <p>Every number here is derived from {@code audit_log}; nothing is stored in a summary table
 * (TECHNICAL §8): disabling a stage and re-enabling it must produce the same history, which is only
 * possible when the history is the audit trail itself.</p>
 */
public final class FlowViews {

    /** Default analysis window (TECHNICAL §2.4: 90 days, today inclusive). */
    public static final int DEFAULT_WINDOW_DAYS = 90;

    private FlowViews() {
    }

    /**
     * Board filter. {@code from}/{@code to} are inclusive calendar days; a missing window is the
     * last {@value #DEFAULT_WINDOW_DAYS} days ending today.
     */
    public record Query(Long domainId, Long vendorId, LocalDate from, LocalDate to, String reqType) {
    }

    /** Resolved window echoed back so the page never has to guess what "last 90 days" meant. */
    public record Window(LocalDate from, LocalDate to) {
    }

    /** One configured stage, including the raw mapping the config page edits. */
    public record StageView(long id, String code, String name, int orderNo, String mappingJson,
                            String reqTypes, boolean enabled) {
    }

    /** Full-save payload for {@code PUT /flow/stages}; {@code id} null means "insert". */
    public record StageCmd(Long id, String code, String name, Integer orderNo, Boolean enabled,
                           String reqTypes, String mappingJson) {
    }

    public record StageSaveCmd(List<StageCmd> stages) {
    }

    /** Aggregated dwell statistics for one stage code; absent stages simply have no row. */
    public record StageAgg(String code, long wip, long sampleSize, Double dwellAvg, Double dwellP90) {
    }

    /** One column of the seven-column board. */
    public record StageCard(String code, String name, int orderNo, boolean applicable, long wip,
                            Double dwellAvg, Double dwellP90, long sampleSize, long noDataCount,
                            boolean bottleneck) {
    }

    /**
     * Flow efficiency: {@code (development + test dwell) / (submitted → delivered)}, as a
     * percentage with one decimal (DESIGN shows {@code 62.4%}). {@code included} counts the
     * delivered requirements that had both segments, {@code excluded} the ones dropped for a
     * missing segment or a non-positive denominator.
     */
    public record Efficiency(Double value, long included, long excluded) {
    }

    /** The whole board in one response: window, seven columns, efficiency, exclusions. */
    public record Board(Window window, List<StageCard> stages, Efficiency efficiency, long noDataCount,
                        LocalDateTime generatedAt) {
    }

    /** Drill-down filter: a stage column opened into its requirement list. */
    public record DrillQuery(String stageCode, Long domainId, Long vendorId, LocalDate from,
                             LocalDate to, String reqType, int page, int size) {
    }

    /** One requirement currently sitting in the drilled stage. */
    public record DrillRow(long id, String code, String title, String type, String status, Long ownerId,
                           LocalDateTime enteredAt, Double dwellDays) {
    }

    /**
     * One stage interval on a single requirement's timeline. {@code noData} rows are the explicit
     * "no audit trail" answer — the alternative (a zero-length bar) is the anti-pattern AC-9 bans.
     */
    public record TimelineRow(String stageCode, LocalDateTime enteredAt, LocalDateTime leftAt,
                              Double dwellDays, String sourceEvent, boolean noData) {
    }
}
