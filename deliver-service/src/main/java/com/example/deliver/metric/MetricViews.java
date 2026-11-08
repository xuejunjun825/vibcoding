package com.example.deliver.metric;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class MetricViews {

    private MetricViews() {
    }

    /** Raw counts first, derived values second — a reader can always recompute the ratio. */
    public record DefectMetrics(long total,
                                long openHigh,
                                long reopened,
                                long closed,
                                long requirements,
                                BigDecimal density,
                                BigDecimal reopenRate,
                                BigDecimal mttrMinutes) {
    }

    public record PlanMetrics(long planId,
                              long planned,
                              long executed,
                              long passed,
                              long failed,
                              long blocked,
                              long notRun,
                              BigDecimal passRate,
                              BigDecimal executedPassRate,
                              BigDecimal coverage) {
    }

    public record DefinitionView(long id,
                                 String code,
                                 String name,
                                 String topic,
                                 String formula,
                                 String datasource,
                                 String caliberNote,
                                 int version,
                                 LocalDateTime effectiveAt) {
    }

    public record DefinitionsView(List<DefinitionView> items, boolean allVersions) {
    }

    /** Payload for publishing a new definition version (append-only, never an update). */
    public record DefinitionCmd(String code,
                                String name,
                                String topic,
                                String formula,
                                String datasource,
                                String caliberNote) {
    }
}
