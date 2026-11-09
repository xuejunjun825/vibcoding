package com.example.deliver.defect;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire shapes for the defect endpoints. Records only — the persistence entity never leaves the
 * service layer, so adding a column cannot accidentally expose it.
 */
public final class DefectViews {

    private DefectViews() {
    }

    /**
     * Create request. Deliberately has no domain/vendor field: those are derived (AC-13).
     *
     * <p>{@code source} 缺省 {@code MANUAL}；{@code PROD} 表示生产逃逸提报，必须能关联到版本
     * （请求里的 releaseId 或需求自身的版本），否则无法划定观察窗，服务端返回 400。</p>
     */
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
                               LocalDate expectedFixDate,
                               String source,
                               String foundPhase) {
    }

    /**
     * 构建失败转缺陷（内部端点）。{@code idempotencyKey} 是 {@code job#buildNumber}：
     * 同一个构建重复回调、用户重复点「转缺陷」，都必须落到同一个缺陷上。
     *
     * <p>这里没有 reproSteps / handlerId：构建失败没有复现步骤，也不指派给具体人——
     * 谁接手由缺陷池的分派规则决定，而不是由 CI 猜。</p>
     */
    public record CreateFromBuild(Long requirementId,
                                  Long releaseId,
                                  String title,
                                  String description,
                                  String severity,
                                  String idempotencyKey) {
    }

    /** {@code action} is accepted in either case; the service normalises before the lookup. */    public record TransitionCmd(String action, String comment, Long handlerId) {
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
                             List<HistoryView> history,
                             String source,
                             String foundPhase,
                             @JsonProperty("isEscape") boolean isEscape,
                             LocalDateTime escapeWindowEnd) {
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
