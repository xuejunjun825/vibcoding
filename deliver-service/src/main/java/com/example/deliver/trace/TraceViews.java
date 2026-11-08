package com.example.deliver.trace;

import java.util.List;
import java.util.Map;

/** Traceability wire shapes (AC-15). */
public final class TraceViews {

    private TraceViews() {
    }

    /**
     * One cell of the six-ring matrix.
     *
     * @param state  COMPLETE / GAP / NA
     * @param label  short human label shown under the icon
     * @param count  item count behind the cell, when there is one
     * @param detail sentence the detail tab shows; also the tooltip
     */
    public record TraceCell(String state, String label, Integer count, String detail) {

        public static TraceCell complete(String label, Integer count, String detail) {
            return new TraceCell("COMPLETE", label, count, detail);
        }

        public static TraceCell gap(String label, Integer count, String detail) {
            return new TraceCell("GAP", label, count, detail);
        }

        public static TraceCell na(String label, String detail) {
            return new TraceCell("NA", label, null, detail);
        }
    }

    /**
     * One requirement row.
     *
     * <p>{@code cells} carries every column the UI renders; {@code gaps} is derived from those
     * same cells, so {@code complete == gaps.isEmpty()} holds by construction rather than by a
     * second query. {@code execution} is exposed twice ({@code test} and {@code execution})
     * because the matrix and the requirement detail tab read different keys.</p>
     */
    public record TraceRow(long requirementId,
                           String requirementCode,
                           String title,
                           String type,
                           String status,
                           String domainName,
                           String vendorName,
                           Map<String, TraceCell> cells,
                           List<String> gaps,
                           boolean complete) {
    }
}
