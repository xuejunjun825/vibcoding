package com.example.deliver.analytics;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.platform.common.error.ApiException;

/**
 * The report/analytics error codes, in one place.
 *
 * <p>The codes are part of the contract (the editor maps them to a field, the portal shows a
 * banner), so they are never built ad-hoc at a call site: a second spelling of
 * {@code REPORT_UNKNOWN_FIELD} is a second contract. Every response carries a {@code detail} map
 * with the offending input, so the editor can highlight the exact slot instead of a toast.</p>
 */
public final class AnalyticsErrors {

    public static final String UNKNOWN_FIELD = "REPORT_UNKNOWN_FIELD";
    public static final String DATASET_MISMATCH = "REPORT_DATASET_MISMATCH";
    public static final String DIM_LIMIT = "REPORT_DIM_LIMIT";
    public static final String MEASURE_LIMIT = "REPORT_MEASURE_LIMIT";
    public static final String FILTER_LIMIT = "REPORT_FILTER_LIMIT";
    public static final String SPAN_LIMIT = "REPORT_SPAN_LIMIT";
    public static final String GROUP_LIMIT = "REPORT_GROUP_LIMIT";
    public static final String EXPORT_LIMIT = "REPORT_EXPORT_LIMIT";
    public static final String NAME_DUPLICATED = "REPORT_NAME_DUPLICATED";
    public static final String NOT_FOUND = "REPORT_NOT_FOUND";
    public static final String SCOPE_FORBIDDEN = "REPORT_SCOPE_FORBIDDEN";
    public static final String CARD_LIMIT = "PORTAL_CARD_LIMIT";

    private AnalyticsErrors() {
    }

    public static ApiException unknownDataset(String dataset) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("dataset", dataset);
        detail.put("known", AnalyticsDatasets.codes());
        return new ApiException(400, UNKNOWN_FIELD, "未知数据集：" + dataset, detail);
    }

    public static ApiException unknownField(String dataset, String field) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("dataset", dataset);
        detail.put("field", field);
        return new ApiException(400, UNKNOWN_FIELD,
                "字段不在数据集白名单内：" + dataset + "." + field, detail);
    }

    public static ApiException datasetMismatch(String dataset, String field) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("dataset", dataset);
        detail.put("field", field);
        return new ApiException(400, DATASET_MISMATCH,
                "字段 " + field + " 不属于数据集 " + dataset + "（不允许跨数据集混用）", detail);
    }

    public static ApiException dimLimit(int rows, int cols) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rows", rows);
        detail.put("cols", cols);
        detail.put("maxRows", AnalyticsDatasets.MAX_ROWS);
        detail.put("maxCols", AnalyticsDatasets.MAX_COLS);
        detail.put("maxDims", AnalyticsDatasets.MAX_DIMS);
        return new ApiException(400, DIM_LIMIT,
                "维度超限：行 " + rows + "（≤" + AnalyticsDatasets.MAX_ROWS + "）、列 " + cols
                        + "（≤" + AnalyticsDatasets.MAX_COLS + "），合计 ≤"
                        + AnalyticsDatasets.MAX_DIMS, detail);
    }

    public static ApiException measureLimit(int measures) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("measures", measures);
        detail.put("maxMeasures", AnalyticsDatasets.MAX_MEASURES);
        return new ApiException(400, MEASURE_LIMIT,
                "度量超限：最多 " + AnalyticsDatasets.MAX_MEASURES + " 个", detail);
    }

    public static ApiException filterLimit(int filters) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("filters", filters);
        detail.put("maxFilters", AnalyticsDatasets.MAX_FILTERS);
        return new ApiException(400, FILTER_LIMIT,
                "筛选超限：最多 " + AnalyticsDatasets.MAX_FILTERS + " 个", detail);
    }

    public static ApiException badOperator(String op) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", op);
        detail.put("known", AnalyticsDatasets.OPERATORS);
        return new ApiException(400, UNKNOWN_FIELD, "筛选运算符不支持：" + op, detail);
    }

    public static ApiException spanLimit(LocalDate from, LocalDate to) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", from);
        detail.put("to", to);
        detail.put("maxSpanDays", AnalyticsRequest.MAX_SPAN_DAYS);
        return new ApiException(400, SPAN_LIMIT,
                "时间跨度超过 " + AnalyticsRequest.MAX_SPAN_DAYS + " 天：" + from + " ~ " + to, detail);
    }

    public static ApiException groupLimit(long groups) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("groups", groups);
        detail.put("maxGroups", AnalyticsQueryService.MAX_GROUPS);
        return new ApiException(400, GROUP_LIMIT,
                "分组数 " + groups + " 超过上限 " + AnalyticsQueryService.MAX_GROUPS
                        + "，请增加筛选或减少维度", detail);
    }

    public static ApiException exportLimit(long rows) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rows", rows);
        detail.put("maxRows", AnalyticsQueryService.EXPORT_MAX_ROWS);
        return new ApiException(400, EXPORT_LIMIT,
                "导出行数 " + rows + " 超过上限 " + AnalyticsQueryService.EXPORT_MAX_ROWS, detail);
    }

    public static ApiException nameDuplicated(String name) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", name);
        return new ApiException(409, NAME_DUPLICATED, "已有同名报表：" + name, detail);
    }

    public static ApiException notFound(long id) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", id);
        return new ApiException(404, NOT_FOUND, "报表不存在或无权访问：" + id, detail);
    }

    public static ApiException scopeForbidden(String action) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", action);
        return new ApiException(403, SCOPE_FORBIDDEN, "当前角色不能" + action, detail);
    }

    public static ApiException cardLimit(int max) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("maxCards", max);
        return new ApiException(409, CARD_LIMIT, "工作台最多固定 " + max + " 张卡片", detail);
    }
}
