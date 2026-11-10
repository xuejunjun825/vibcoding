package com.example.deliver.reports;

import java.util.List;

/**
 * The report wire types — the JSON contract with {@code web/src/api/analytics.ts}.
 *
 * <p>They are deliberately separate from {@link com.example.deliver.analytics.AnalyticsViews}:
 * the editor speaks {@code columns} / {@code value} / {@code IN}, the engine speaks {@code cols} /
 * {@code values} / {@code in}. Converting once, in {@link ReportDefinitionCodec}, is what lets a
 * saved definition come back byte-identical (AC-7) instead of being normalised on the way out.</p>
 */
public final class ReportViews {

    private ReportViews() {
    }

    public record ReportSummary(long id, String name, String datasetId, String datasetLabel,
                                String scope, String scopeRef, long ownerId, String ownerName,
                                int dimCount, int measureCount, String chartType, String updatedAt) {
    }

    public record ReportDetail(long id, String name, String datasetId, String datasetLabel,
                               String scope, String scopeRef, long ownerId, String ownerName,
                               int dimCount, int measureCount, String chartType, String updatedAt,
                               ReportDefinitionDto definition, boolean canWrite, boolean canShare) {
    }

    /** The editor's definition, exactly as posted and stored. */
    public record ReportDefinitionDto(String datasetId, List<String> rows, List<String> columns,
                                      List<String> measures, List<ReportFilterDto> filters) {

        public ReportDefinitionDto {
            rows = rows == null ? List.of() : List.copyOf(rows);
            columns = columns == null ? List.of() : List.copyOf(columns);
            measures = measures == null ? List.of() : List.copyOf(measures);
            filters = filters == null ? List.of() : List.copyOf(filters);
        }
    }

    /** One filter in editor form; {@code value} is a string or an array for {@code IN}. */
    public record ReportFilterDto(String field, String op, Object value) {
    }

    public record ReportCmd(String name, String datasetId, ReportDefinitionDto definition,
                            String chartType) {
    }

    public record ShareCmd(String scope, Object scopeRef) {
    }

    public record RunCmd(ReportDefinitionDto definition, Long domainId, Long vendorId,
                         String preset, String from, String to) {
    }
}
