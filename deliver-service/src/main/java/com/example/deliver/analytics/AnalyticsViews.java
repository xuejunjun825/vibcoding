package com.example.deliver.analytics;

import java.util.List;

/**
 * The response/definition records of the one analytics engine.
 *
 * <p>Every outlet of the workbench, the dashboard, the report editor and the report export
 * serialises these records and nothing else: a {@code Map<String,Object>} would let the three
 * surfaces drift into three slightly different field sets, and the frontend would have to guess
 * which field exists. Records make the contract inspectable at compile time.</p>
 *
 * <p>{@link Metric#value()} (and a cross-tab cell) is {@code null} when there is no sample or no
 * denominator — "no data" is not zero, and the UI renders {@code —}. Ratios are already
 * percentages here (unit {@code %}); the frontend never divides two numbers.</p>
 */
public final class AnalyticsViews {

    private AnalyticsViews() {
    }

    /** One metric. {@code prev}/{@code delta} are null when there is no comparable window. */
    public record Metric(String key, String label, String unit, int digits, Double value,
                         Double prev, Double delta, String caliber, String asOf) {
    }

    /** The metric strip, with one {@code dataAsOf} for the whole strip (not per tile). */
    public record Metrics(String dataAsOf, String cache, List<Metric> items) {
    }

    /** A whitelisted field of a dataset. {@code kind} is DIM | TIME | MEASURE. */
    public record Field(String key, String label, String kind, boolean measure) {

        public static final String DIM = "DIM";
        public static final String TIME = "TIME";
        public static final String MEASURE = "MEASURE";
    }

    /** A dataset and its whitelist. The frontend renders its field panel from this list. */
    public record Dataset(String code, String label, List<Field> fields) {
    }

    /** One row of a cross table: label + one value per selected measure + one source count. */
    public record CrossRow(String key, List<Double> values, List<Long> counts) {
    }

    /**
     * A cross table. {@code rowHeaders}/{@code colHeaders} are the observed dimension values;
     * {@code subtotal}/{@code total} aggregate with the same formulas as the cells (a rate is
     * re-derived from summed numerator/denominator, never averaged).
     */
    public record CrossTab(List<String> rowHeaders, List<String> colHeaders, List<CrossRow> rows,
                           CrossRow subtotal, CrossRow total, int groups, boolean truncated,
                           String dataAsOf, String cache,
                           // r14：机器可读的原始取值，与 *Headers 同长同序；「合计」列/行的 code 为 null。
                           // 追加在末尾：既有字段顺序与语义不变，旧调用方（含 9 参构造）不受影响。
                           List<String> colCodes, List<String> rowCodes) {

        /**
         * 9 参兼容构造：拿不到 code 的调用方（测试、缓存重建前的旧路径）落空列表，
         * 前端已按「可选字段」处理，不会按下标索引。
         */
        public CrossTab(List<String> rowHeaders, List<String> colHeaders, List<CrossRow> rows,
                        CrossRow subtotal, CrossRow total, int groups, boolean truncated,
                        String dataAsOf, String cache) {
            this(rowHeaders, colHeaders, rows, subtotal, total, groups, truncated, dataAsOf, cache,
                    List.of(), List.of());
        }

        /**
         * 导出用行标签：数据行优先用与 {@code rows} 同长同序的中文 {@code rowHeaders}；
         * 小计/总计（下标越界）或旧调用方（表头缺失/长度不符）回退到行自身的 key（本已是中文）。
         * 导出与页面因此读同一份显示串，{@code rowCodes} 只作机器可读值，绝不直接上屏。
         */
        public String rowLabel(int index, CrossRow row) {
            if (row != null && rowHeaders != null && rows != null
                    && rowHeaders.size() == rows.size()
                    && index >= 0 && index < rowHeaders.size()) {
                String label = rowHeaders.get(index);
                return label == null ? "" : label;
            }
            return row == null || row.key() == null ? "" : row.key();
        }
    }

    /** Dashboard block 1: literally a cross table, so it cannot read differently. */
    public record Breakdown(String key, String label, List<String> colHeaders,
                            List<CrossRow> rows, CrossRow subtotal, CrossRow total) {
    }

    /** One period of the period-over-period trend. {@code previous}/{@code delta} may be null. */
    public record TrendPoint(String bucket, Long current, Long previous, Double delta) {
    }

    /** Period-over-period counts; fewer than 3 points means "show the table, not a chart". */
    public record Trend(String unit, List<TrendPoint> points) {
    }

    /** One cycle-time bucket (<=3 / 4-7 / 8-15 / 16-30 / >30 days); {@code tail} marks >30. */
    public record Segment(String key, String label, long count, Double avg, Double p90, boolean tail) {
    }

    /** Estimate / actual / deviation in person-days, plus how many rows had no estimate. */
    public record Effort(Double estimate, Double actual, Double deviation, long missingEstimate,
                         String unit) {
    }

    /** One vendor row of the delivery ranking. {@code mine} is the viewer's own vendor. */
    public record VendorRank(int rank, Long vendorId, String vendorName, long completed,
                             Double onTimeRate, Double avgCycle, boolean mine) {
    }

    /** A quality card. {@code level} is GOOD | WARN | BAD | NONE, decided by the backend. */
    public record QualityCard(String key, String label, Double value, String unit, int digits,
                              String level, long sample) {

        public static final String GOOD = "GOOD";
        public static final String WARN = "WARN";
        public static final String BAD = "BAD";
        public static final String NONE = "NONE";
    }

    /** The three quality cards. */
    public record Quality(List<QualityCard> cards) {
    }

    /** The whole dashboard: five blocks, one cache stamp, one data-as-of. */
    public record Dashboard(List<Breakdown> breakdowns, Trend trend, List<Segment> segments,
                            Effort effort, List<VendorRank> vendorRank, Quality quality,
                            String dataAsOf, String cache) {
    }

    /** The metric dictionary row: what a key means, how it is labelled and its caliber wording. */
    public record MetricSpec(String key, String label, String unit, int digits, String caliber,
                             String kind) {
    }

    /** One definition filter. {@code op}: eq | ne | in | notIn | contains | gte | lte. */
    public record Filter(String field, String op, List<String> values) {

        public Filter {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    /** Sorting hint stored with a definition; the engine keeps its deterministic order. */
    public record Sort(String by, String dir) {
    }

    /**
     * A report definition. The same shape is stored as {@code definition_json} and posted by the
     * editor, so there is one parser and one validator (see {@link AnalyticsDatasets#check}).
     */
    public record ReportDefinition(List<String> rows, List<String> cols, List<String> measures,
                                   List<Filter> filters, Sort sort) {

        public ReportDefinition {
            rows = rows == null ? List.of() : List.copyOf(rows);
            cols = cols == null ? List.of() : List.copyOf(cols);
            measures = measures == null ? List.of() : List.copyOf(measures);
            filters = filters == null ? List.of() : List.copyOf(filters);
        }

        public static ReportDefinition of(List<String> rows, List<String> cols, List<String> measures,
                                          List<Filter> filters) {
            return new ReportDefinition(rows, cols, measures, filters, null);
        }

        /** Row dimensions followed by column dimensions, the order the SQL selects them in. */
        public List<String> dimensions() {
            List<String> all = new java.util.ArrayList<>(rows.size() + cols.size());
            all.addAll(rows);
            all.addAll(cols);
            return List.copyOf(all);
        }

        public boolean hasMeasure(String key) {
            return measures.contains(key);
        }
    }
}
