package com.example.deliver.analytics;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
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
    public static final String UNKNOWN_CARD = "PORTAL_UNKNOWN_CARD";
    public static final String FORBIDDEN = "FORBIDDEN";

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

    /**
     * Group cap is the caller's {@code cap}, not the page constant: the export path raises it to
     * {@link AnalyticsQueryService#EXPORT_MAX_ROWS}, and an error that reported 5000 for a
     * 10001-group export would name a limit the caller never used.
     */
    public static ApiException groupLimit(long groups, int cap) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("groups", groups);
        detail.put("maxGroups", cap);
        return new ApiException(400, GROUP_LIMIT,
                "分组数 " + groups + " 超过上限 " + cap
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

    /**
     * The card caps, in both flavours: {@code PUT /portal/config} rejects an over-long key list
     * with 400, while pinning the fifth card is a state conflict (409) that must hand back the
     * four cards already pinned so the dialog can show what would have to go.
     */
    public static ApiException cardLimit(int status, int maxCards, List<Map<String, Object>> cards,
                                         List<String> cardKeys) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("maxCards", maxCards);
        detail.put("cards", cards == null ? List.of() : List.copyOf(cards));
        detail.put("cardKeys", cardKeys == null ? List.of() : List.copyOf(cardKeys));
        return new ApiException(status, CARD_LIMIT, "工作台最多固定 " + maxCards + " 张卡片", detail);
    }

    /** An unknown template card key: the editor sent a key no template declares. */
    public static ApiException unknownCard(String key, List<String> known) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("cardKey", key);
        detail.put("known", known == null ? List.of() : List.copyOf(known));
        return new ApiException(400, UNKNOWN_CARD, "未知工作台卡片：" + key, detail);
    }

    /**
     * A 403 that names the permission it wanted. The interceptor's own 403 has the same body shape;
     * this factory exists for the checks a controller performs itself (a non-admin reading/writing
     * another role's card config), so those refusals are not hand-built Maps at the call site.
     */
    public static ApiException forbidden(String function, String method, String path) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("function", function);
        detail.put("functions", List.of(function));
        detail.put("method", method);
        detail.put("path", path);
        return new ApiException(403, FORBIDDEN,
                "缺少权限：" + function + "（" + method + " " + path + "）", detail);
    }
}
