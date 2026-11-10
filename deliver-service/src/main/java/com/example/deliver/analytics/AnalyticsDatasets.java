package com.example.deliver.analytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.analytics.AnalyticsViews.Field;

/**
 * The four datasets and their field whitelists — the only field table in the product.
 *
 * <p>The editor's field panel, the drag target, the server-side validation and the export header
 * row all come from here: a second list in the frontend would let a field be offered and then
 * rejected, or accepted and then silently ignored. The limits live here too, because
 * "rows ≤ 3, cols ≤ 2, rows+cols ≤ 4" is part of the same contract as the field names.</p>
 */
public final class AnalyticsDatasets {

    public static final String REQUIREMENT = "requirement";
    public static final String DELIVERY = "delivery";
    public static final String APPROVAL = "approval";
    public static final String QUALITY = "quality";

    public static final int MAX_ROWS = 3;
    public static final int MAX_COLS = 2;
    public static final int MAX_DIMS = 4;
    public static final int MAX_MEASURES = 3;
    public static final int MAX_FILTERS = 8;

    public static final List<String> OPERATORS =
            List.of("eq", "ne", "in", "notIn", "contains", "gte", "lte", "gt", "lt");

    /** Filter fields that are not dataset dimensions: the request-level scope filter chips. */
    private static final List<String> SPECIAL_FILTERS = List.of("domainId", "vendorId", "releaseId");

    private static final List<String> CODES = List.of(REQUIREMENT, DELIVERY, APPROVAL, QUALITY);

    private static final Map<String, AnalyticsViews.Dataset> DATASETS = build();

    private AnalyticsDatasets() {
    }

    public static List<String> codes() {
        return CODES;
    }

    public static boolean knows(String dataset) {
        return dataset != null && DATASETS.containsKey(dataset);
    }

    public static AnalyticsViews.Dataset dataset(String code) {
        AnalyticsViews.Dataset dataset = code == null ? null : DATASETS.get(code);
        if (dataset == null) {
            throw AnalyticsErrors.unknownDataset(code);
        }
        return dataset;
    }

    /** The dataset's default measures, in the order the editor drops them in. */
    public static List<String> measuresFor(String code) {
        return switch (dataset(code).code()) {
            case REQUIREMENT -> List.of("req.total", "cycle.avg", "cycle.p90");
            case DELIVERY -> List.of("req.total", "vendor.onTimeRate", "cycle.avg");
            case APPROVAL -> List.of("approval.total", "approval.avgHours", "approval.passRate");
            default -> List.of("req.total", "quality.passRate", "quality.defectDensity");
        };
    }

    /** Comma-joined defaults, for a card spec that stores one string instead of a list. */
    public static String defaultMeasures(String code) {
        return String.join(",", measuresFor(code));
    }

    /**
     * Resolve a field, distinguishing "typo" from "belongs to another dataset".
     *
     * <p>Both are 400, but different codes: {@code REPORT_DATASET_MISMATCH} tells the editor that
     * switching dataset clears the slots, a plain unknown field highlights the single slot.</p>
     */
    public static Field field(String dataset, String fieldKey) {
        AnalyticsViews.Dataset known = dataset(dataset);
        for (Field field : known.fields()) {
            if (field.key().equals(fieldKey)) {
                return field;
            }
        }
        for (AnalyticsViews.Dataset other : DATASETS.values()) {
            if (other.code().equals(known.code())) {
                continue;
            }
            for (Field field : other.fields()) {
                if (field.key().equals(fieldKey)) {
                    throw AnalyticsErrors.datasetMismatch(known.code(), fieldKey);
                }
            }
        }
        throw AnalyticsErrors.unknownField(known.code(), fieldKey);
    }

    public static boolean isMeasure(String dataset, String fieldKey) {
        return field(dataset, fieldKey).measure();
    }

    /** Whether a field may be used as a filter (dimensions plus the scope chips). */
    public static boolean isFilterable(String dataset, String fieldKey) {
        return SPECIAL_FILTERS.contains(fieldKey) || !field(dataset, fieldKey).measure();
    }

    public static boolean isSpecialFilter(String fieldKey) {
        return SPECIAL_FILTERS.contains(fieldKey);
    }

    /**
     * Validate a definition against the dataset's whitelist and limits.
     *
     * <p>Called by the save path and again by the run path: a stored definition may have been
     * written by an older build, and the run must not trust it.</p>
     */
    public static void check(String dataset, AnalyticsViews.ReportDefinition def) {
        AnalyticsViews.Dataset known = dataset(dataset);
        if (def == null) {
            throw AnalyticsErrors.unknownField(known.code(), "definition");
        }
        if (def.rows().size() > MAX_ROWS || def.cols().size() > MAX_COLS
                || def.rows().size() + def.cols().size() > MAX_DIMS) {
            throw AnalyticsErrors.dimLimit(def.rows().size(), def.cols().size());
        }
        if (def.rows().isEmpty()) {
            throw AnalyticsErrors.dimLimit(0, def.cols().size());
        }
        if (def.measures().isEmpty() || def.measures().size() > MAX_MEASURES) {
            throw AnalyticsErrors.measureLimit(def.measures().size());
        }
        if (def.filters().size() > MAX_FILTERS) {
            throw AnalyticsErrors.filterLimit(def.filters().size());
        }
        for (String row : def.rows()) {
            requireDimension(known.code(), row);
        }
        for (String col : def.cols()) {
            requireDimension(known.code(), col);
        }
        for (String measure : def.measures()) {
            if (!isMeasure(known.code(), measure)) {
                throw AnalyticsErrors.unknownField(known.code(), measure);
            }
        }
        for (AnalyticsViews.Filter filter : def.filters()) {
            if (filter.field() == null) {
                throw AnalyticsErrors.unknownField(known.code(), "filter.field");
            }
            if (!isFilterable(known.code(), filter.field())) {
                throw AnalyticsErrors.unknownField(known.code(), filter.field());
            }
            String op = filter.op() == null ? "eq" : filter.op();
            if (!OPERATORS.contains(op)) {
                throw AnalyticsErrors.badOperator(op);
            }
            if (filter.values().isEmpty()
                    && !List.of("isEmpty", "isNotEmpty").contains(op)) {
                throw AnalyticsErrors.unknownField(known.code(), filter.field());
            }
        }
    }

    private static void requireDimension(String dataset, String key) {
        if (isMeasure(dataset, key)) {
            throw AnalyticsErrors.unknownField(dataset, key);
        }
    }

    private static Map<String, AnalyticsViews.Dataset> build() {
        Map<String, AnalyticsViews.Dataset> map = new LinkedHashMap<>();

        map.put(REQUIREMENT, new AnalyticsViews.Dataset(REQUIREMENT, "需求", List.of(
                dim("domain", "域"), dim("vendor", "厂商（承接）"), dim("org", "提出单位"),
                dim("type", "类型"), dim("priority", "优先级"), dim("status", "状态"),
                dim("owner", "负责人"),
                time("week", "创建周"), time("month", "创建月"), time("planMonth", "计划完成月"),
                measure("req.total", "需求数"),
                measure("cycle.avg", "平均交付周期"),
                measure("cycle.p90", "P90 交付周期"),
                measure("req.avgDwell", "平均停留"),
                measure("effort.estimate", "预估人天"),
                measure("effort.actual", "实际人天"),
                measure("effort.deviation", "人天偏差"))));

        map.put(DELIVERY, new AnalyticsViews.Dataset(DELIVERY, "交付", List.of(
                dim("domain", "域"), dim("vendor", "厂商"), dim("release", "发布"),
                dim("status", "状态"),
                time("planMonth", "计划上线月"), time("month", "创建月"),
                measure("req.total", "需求数"),
                measure("delivery.onTime", "按期完成数"),
                measure("vendor.onTimeRate", "准时率"),
                measure("cycle.avg", "平均周期"),
                measure("cycle.p90", "P90 周期"))));

        map.put(APPROVAL, new AnalyticsViews.Dataset(APPROVAL, "审批效率", List.of(
                dim("bizType", "业务类型"), dim("node", "节点"), dim("result", "结果"),
                time("month", "发起月"),
                measure("approval.total", "审批单数"),
                measure("approval.avgHours", "平均耗时（小时）"),
                measure("approval.p90Hours", "P90 耗时（小时）"),
                measure("approval.passRate", "通过率"))));

        map.put(QUALITY, new AnalyticsViews.Dataset(QUALITY, "质量", List.of(
                dim("domain", "域"), dim("vendor", "厂商"), dim("plan", "测试计划"),
                dim("severity", "缺陷等级"), dim("status", "状态"),
                time("month", "月份"),
                measure("req.total", "需求数"),
                measure("quality.passRate", "测试通过率"),
                measure("quality.defectDensity", "缺陷密度"),
                measure("quality.releaseSuccess", "发布成功率"),
                measure("quality.defects", "缺陷数"))));

        return Map.copyOf(map);
    }

    private static Field dim(String key, String label) {
        return new Field(key, label, Field.DIM, false);
    }

    private static Field time(String key, String label) {
        return new Field(key, label, Field.TIME, false);
    }

    private static Field measure(String key, String label) {
        return new Field(key, label, Field.MEASURE, true);
    }

    /** The field list as a plain list of keys — used by the static SQL-coverage check. */
    public static List<String> keys(String dataset) {
        List<String> keys = new ArrayList<>();
        for (Field field : dataset(dataset).fields()) {
            keys.add(field.key());
        }
        return keys;
    }
}
