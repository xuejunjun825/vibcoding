package com.example.deliver.defect;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Wire shapes for the defect endpoints. Records only — the persistence entity never leaves the
 * service layer, so adding a column cannot accidentally expose it.
 */
public final class DefectViews {

    private DefectViews() {
    }

    /** Create request. Deliberately has no domain/vendor field: those are derived (AC-13). */
    public record CreateDefect(Long requirementId,
                               Long releaseId,
                               Long testCaseId,
                               Integer testCaseVersion,
                               Long testExecutionId,
                               String title,
                               String description,
                               String reproSteps,
                               String severity,
                               String priority,
                               Long handlerId,
                               LocalDate expectedFixDate) {
    }

    /** {@code action} is accepted in either case; the service normalises before the lookup. */
    public record TransitionCmd(String action, String comment, Long handlerId) {
    }

    public record HistoryView(long id,
                              String action,
                              String fromStatus,
                              String toStatus,
                              Long operatorId,
                              String comment,
                              LocalDateTime createdAt) {
    }

    public record DefectView(long id,
                             String code,
                             Long requirementId,
                             String requirementCode,
                             String requirementTitle,
                             Long releaseId,
                             Long testCaseId,
                             Integer testCaseVersion,
                             Long testExecutionId,
                             String title,
                             String description,
                             String reproSteps,
                             String severity,
                             String priority,
                             String status,
                             Long reporterId,
                             Long handlerId,
                             long domainId,
                             Long vendorId,
                             int reopenCount,
                             LocalDate expectedFixDate,
                             LocalDateTime foundAt,
                             LocalDateTime closedAt,
                             List<String> allowedActions,
                             List<HistoryView> history) {
    }

    /**
     * Business-side summary (AC-13): status, severity and expected fix date only. No description,
     * repro steps, attachments, handler or history — the fields that would leak delivery detail.
     */
    public record Summary(long id,
                          String code,
                          String title,
                          String status,
                          String severity,
                          LocalDate expectedFixDate,
                          boolean summaryOnly) {
    }

    public record PageView<T>(List<T> items, long total, int page, int size) {        public static <T> PageView<T> of(List<T> all, int page, int size) {
            int safeSize = size <= 0 ? 20 : size;
            int safePage = page <= 0 ? 1 : page;
            int from = Math.min((safePage - 1) * safeSize, all.size());
            int to = Math.min(from + safeSize, all.size());
            return new PageView<>(all.subList(from, to), all.size(), safePage, safeSize);
        }
    }

    /** Raw counts plus the derived numbers, so a reader can always recompute the ratio. */
    public record BoardMetrics(long openHigh,
                               long total,
                               long reopened,
                               long closed,
                               long requirements,
                               java.math.BigDecimal density,
                               java.math.BigDecimal reopenRate,
                               java.math.BigDecimal mttrMinutes) {
    }

    public record MetricDefinitionRow(String code,
                                      String name,
                                      String formula,
                                      int version) {
    }

    public record BoardView(BoardMetrics metrics,
                            java.util.List<com.example.deliver.metric.MetricViews.DefinitionView> definitions,
                            String drill,
                            DefectFilter filter) {
    }

    /** One immutable filter for cards, drill-down, CSV and traceability (same WHERE). */
    public record DefectFilter(Long domainId,
                               Long vendorId,
                               Long requirementId,
                               Long releaseId,
                               Long handlerId,
                               String status,
                               String severity,
                               String keyword,
                               LocalDate from,
                               LocalDate to) {
        public static DefectFilter empty() {
            return new DefectFilter(null, null, null, null, null, null, null, null, null, null);
        }
    }
}
