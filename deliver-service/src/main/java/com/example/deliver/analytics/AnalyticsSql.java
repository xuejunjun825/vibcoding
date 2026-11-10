package com.example.deliver.analytics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The only place analytics SQL text is assembled.
 *
 * <p>Every statement starts from {@link #base(String)} and therefore carries a
 * scope marker comment naming an alias: the row-level filter is not something a caller may
 * forget, because the base is the string the caller must use. The aliases are bound in the
 * {@code FROM}/{@code JOIN} clause here, so the marker always resolves to a table the scope layer
 * knows ({@code requirement}, {@code release}, {@code defect}, ...). The {@code wf_*} tables are
 * <em>not</em> registered with {@code ScopeSql}, which is why the approval dataset is scoped
 * through the {@code requirement} it joins to rather than through {@code wf_task}.</p>
 *
 * <p>Column expressions are whitelisted by {@link AnalyticsDatasets}; this class only knows how a
 * whitelisted key becomes SQL. A measure contributes either a numerator/denominator pair (so the
 * ratio is re-derived from summed parts) or a raw sample in seconds (so the mean and the P90 come
 * from {@link com.example.deliver.flow.FlowMetricFormulas}, not from SQL).</p>
 */
public final class AnalyticsSql {

    /** How one measure contributes to a cell: parts, or a sample to aggregate in Java. */
    public record Contribution(String num, String den, String sample) {

        public boolean sampleBased() {
            return sample != null;
        }

        public boolean hasDen() {
            return den != null;
        }
    }

    private static final String BUCKET_WEEK = "'%x-W%v'";
    private static final String BUCKET_MONTH = "'%Y-%m'";

    private static final Map<String, String> BASES = buildBases();
    private static final Map<String, Map<String, String>> COLUMNS = buildColumns();
    private static final Map<String, Map<String, Contribution>> CONTRIBUTIONS = buildContributions();
    private static final Map<String, String> BUCKET_BASE = buildBucketBase();

    private AnalyticsSql() {
    }

    /**
     * The FROM/JOIN clause plus the scope marker for a dataset.
     *
     * <p>Includes the {@code WHERE} so a statement can never omit the marker; callers append
     * {@code " AND <window>"} / {@code " AND <filter>"}.</p>
     */
    public static String base(String dataset) {
        String base = dataset == null ? null : BASES.get(dataset);
        if (base == null) {
            throw AnalyticsErrors.unknownDataset(dataset);
        }
        return base;
    }

    /** The SQL expression behind a whitelisted dimension/time field. */
    public static String column(String dataset, String fieldKey) {
        Map<String, String> columns = COLUMNS.get(dataset);
        if (columns == null) {
            throw AnalyticsErrors.unknownDataset(dataset);
        }
        String column = columns.get(fieldKey);
        if (column == null) {
            // distinguishes "a measure in a dimension slot" from "unknown field"
            AnalyticsDatasets.field(dataset, fieldKey);
            throw AnalyticsErrors.unknownField(dataset, fieldKey);
        }
        return column;
    }

    /** The SQL expression(s) behind a whitelisted measure. */
    public static Contribution contribution(String dataset, String measureKey) {
        Map<String, Contribution> contributions = CONTRIBUTIONS.get(dataset);
        if (contributions == null) {
            throw AnalyticsErrors.unknownDataset(dataset);
        }
        Contribution contribution = contributions.get(measureKey);
        if (contribution == null) {
            AnalyticsDatasets.field(dataset, measureKey);
            throw AnalyticsErrors.unknownField(dataset, measureKey);
        }
        return contribution;
    }

    public static boolean isMeasure(String dataset, String fieldKey) {
        return AnalyticsDatasets.isMeasure(dataset, fieldKey);
    }

    /** The time window predicate for a dataset, always appended after {@link #base(String)}. */
    public static String windowClause(String dataset) {
        return switch (dataset) {
            case AnalyticsDatasets.APPROVAL ->
                    " AND wi.started_at >= :fromTs AND wi.started_at < :toTs";
            case AnalyticsDatasets.DELIVERY, AnalyticsDatasets.REQUIREMENT, AnalyticsDatasets.QUALITY ->
                    " AND r.created_at >= :fromTs AND r.created_at < :toTs";
            default -> throw AnalyticsErrors.unknownDataset(dataset);
        };
    }

    /** The column a filter compares against; the scope chips are not dataset dimensions. */
    public static String filterColumn(String dataset, String fieldKey) {
        return switch (fieldKey) {
            case "domainId" -> "r.domain_id";
            case "vendorId" -> "r.assignee_vendor_id";
            case "releaseId" -> "r.release_id";
            default -> column(dataset, fieldKey);
        };
    }

    /** Group a time field at a coarser grain: {@code WEEK} or {@code MONTH}. */
    public static String bucket(String dataset, String fieldKey, String unit) {
        String raw = BUCKET_BASE.get(dataset + "|" + fieldKey);
        if (raw == null) {
            throw AnalyticsErrors.unknownField(dataset, fieldKey);
        }
        String format = "WEEK".equalsIgnoreCase(unit) ? BUCKET_WEEK : BUCKET_MONTH;
        return "DATE_FORMAT(" + raw + ", " + format + ")";
    }

    /** {@code WEEK}/{@code MONTH} → the canonical bucket unit. */
    public static String normalizeBucket(String unit) {
        String normalized = unit == null ? "" : unit.trim().toUpperCase(Locale.ROOT);
        return "WEEK".equals(normalized) ? "WEEK" : "MONTH";
    }

    public static List<String> bucketUnits() {
        return List.of("WEEK", "MONTH");
    }

    private static Map<String, String> buildBases() {
        Map<String, String> bases = new LinkedHashMap<>();
        bases.put(AnalyticsDatasets.REQUIREMENT,
                "FROM requirement r"
                        + " LEFT JOIN biz_domain bd ON bd.id = r.domain_id AND bd.tenant_id = r.tenant_id"
                        + " LEFT JOIN sys_vendor sv ON sv.id = r.assignee_vendor_id AND sv.tenant_id = r.tenant_id"
                        + " LEFT JOIN sys_org so ON so.id = r.origin_org_id AND so.tenant_id = r.tenant_id"
                        + " LEFT JOIN sys_user su ON su.id = r.owner_user_id AND su.tenant_id = r.tenant_id"
                        + " WHERE /*scope:r*/");
        bases.put(AnalyticsDatasets.DELIVERY,
                "FROM requirement r"
                        + " LEFT JOIN release rel ON rel.id = r.release_id AND rel.tenant_id = r.tenant_id"
                        + " LEFT JOIN biz_domain bd ON bd.id = r.domain_id AND bd.tenant_id = r.tenant_id"
                        + " LEFT JOIN sys_vendor sv ON sv.id = r.assignee_vendor_id AND sv.tenant_id = r.tenant_id"
                        + " WHERE /*scope:r*/");
        bases.put(AnalyticsDatasets.APPROVAL,
                "FROM wf_instance wi"
                        + " JOIN wf_task wt ON wt.instance_id = wi.id AND wt.tenant_id = wi.tenant_id"
                        + " JOIN requirement r ON r.id = wi.biz_id AND r.tenant_id = wi.tenant_id"
                        + " WHERE /*scope:r*/"
                        + " AND wi.biz_type IN ('REQ_SUBMIT','REQ_REVIEW')");
        bases.put(AnalyticsDatasets.QUALITY,
                "FROM requirement r"
                        + " LEFT JOIN release rel ON rel.id = r.release_id AND rel.tenant_id = r.tenant_id"
                        + " LEFT JOIN biz_domain bd ON bd.id = r.domain_id AND bd.tenant_id = r.tenant_id"
                        + " LEFT JOIN sys_vendor sv ON sv.id = r.assignee_vendor_id AND sv.tenant_id = r.tenant_id"
                        + " WHERE /*scope:r*/");
        return Map.copyOf(bases);
    }

    private static Map<String, Map<String, String>> buildColumns() {
        Map<String, Map<String, String>> all = new LinkedHashMap<>();

        Map<String, String> requirement = new LinkedHashMap<>();
        requirement.put("domain", "COALESCE(bd.name, CONCAT('域#', r.domain_id))");
        requirement.put("vendor", "COALESCE(sv.name, CONCAT('厂商#', r.assignee_vendor_id))");
        requirement.put("org", "COALESCE(so.name, CONCAT('单位#', r.origin_org_id))");
        requirement.put("type", "r.type");
        requirement.put("priority", "r.priority");
        requirement.put("status", "r.status");
        requirement.put("owner", "COALESCE(su.name, CONCAT('用户#', r.owner_user_id))");
        requirement.put("week", "DATE_FORMAT(r.created_at, " + BUCKET_WEEK + ")");
        requirement.put("month", "DATE_FORMAT(r.created_at, " + BUCKET_MONTH + ")");
        requirement.put("planMonth", "DATE_FORMAT(r.plan_end_date, " + BUCKET_MONTH + ")");
        all.put(AnalyticsDatasets.REQUIREMENT, requirement);

        Map<String, String> delivery = new LinkedHashMap<>();
        delivery.put("domain", "COALESCE(bd.name, CONCAT('域#', r.domain_id))");
        delivery.put("vendor", "COALESCE(sv.name, CONCAT('厂商#', r.assignee_vendor_id))");
        delivery.put("release", "COALESCE(rel.name, CONCAT('发布#', r.release_id))");
        delivery.put("status", "r.status");
        delivery.put("planMonth", "DATE_FORMAT(rel.planned_release_date, " + BUCKET_MONTH + ")");
        delivery.put("month", "DATE_FORMAT(r.created_at, " + BUCKET_MONTH + ")");
        all.put(AnalyticsDatasets.DELIVERY, delivery);

        Map<String, String> approval = new LinkedHashMap<>();
        approval.put("bizType", "CASE wi.biz_type"
                + " WHEN 'REQ_SUBMIT' THEN '评审入池'"
                + " WHEN 'REQ_REVIEW' THEN '需求评审'"
                + " WHEN 'REQ_RELEASE' THEN '发布'"
                + " WHEN 'REQ_CHANGE' THEN '变更'"
                + " WHEN 'REQ_DEPLOY' THEN '上线'"
                + " ELSE wi.biz_type END");
        approval.put("node", "COALESCE(wt.node_name, wt.node_key)");
        approval.put("result", "wt.status");
        approval.put("month", "DATE_FORMAT(wi.started_at, " + BUCKET_MONTH + ")");
        all.put(AnalyticsDatasets.APPROVAL, approval);

        Map<String, String> quality = new LinkedHashMap<>();
        quality.put("domain", "COALESCE(bd.name, CONCAT('域#', r.domain_id))");
        quality.put("vendor", "COALESCE(sv.name, CONCAT('厂商#', r.assignee_vendor_id))");
        quality.put("plan", "COALESCE((SELECT tp.name FROM test_execution te"
                + " JOIN test_plan tp ON tp.id = te.plan_id AND tp.tenant_id = te.tenant_id"
                + " WHERE te.requirement_id = r.id ORDER BY te.executed_at DESC LIMIT 1), '未关联计划')");
        quality.put("severity", "COALESCE((SELECT df.severity FROM defect df"
                + " WHERE df.requirement_id = r.id"
                + " ORDER BY FIELD(df.severity, 'P0', 'P1', 'P2', 'P3'), df.id LIMIT 1), '无缺陷')");
        quality.put("status", "r.status");
        quality.put("month", "DATE_FORMAT(r.created_at, " + BUCKET_MONTH + ")");
        all.put(AnalyticsDatasets.QUALITY, quality);

        return Map.copyOf(all);
    }

    private static Map<String, Map<String, Contribution>> buildContributions() {
        Map<String, Map<String, Contribution>> all = new LinkedHashMap<>();

        Map<String, Contribution> requirement = new LinkedHashMap<>();
        requirement.put("req.total", new Contribution("1", null, null));
        requirement.put("cycle.avg",
                new Contribution(null, null, "TIMESTAMPDIFF(SECOND, r.created_at, r.closed_at)"));
        requirement.put("cycle.p90",
                new Contribution(null, null, "TIMESTAMPDIFF(SECOND, r.created_at, r.closed_at)"));
        requirement.put("req.avgDwell", new Contribution(null, null,
                "TIMESTAMPDIFF(SECOND, r.created_at, COALESCE(r.closed_at, NOW(3)))"));
        requirement.put("effort.estimate", new Contribution("COALESCE(r.estimate_days, 0)", null, null));
        requirement.put("effort.actual", new Contribution("COALESCE(r.actual_days, 0)", null, null));
        requirement.put("effort.deviation",
                new Contribution("COALESCE(r.actual_days, 0)", "COALESCE(r.estimate_days, 0)", null));
        all.put(AnalyticsDatasets.REQUIREMENT, requirement);

        String onTime = "CASE WHEN r.closed_at IS NOT NULL AND r.expected_delivery_date IS NOT NULL"
                + " AND DATE(r.closed_at) <= r.expected_delivery_date THEN 1 ELSE 0 END";
        Map<String, Contribution> delivery = new LinkedHashMap<>();
        delivery.put("req.total", new Contribution("1", null, null));
        delivery.put("delivery.onTime", new Contribution(onTime, null, null));
        delivery.put("vendor.onTimeRate", new Contribution(onTime,
                "CASE WHEN r.expected_delivery_date IS NOT NULL THEN 1 ELSE 0 END", null));
        delivery.put("cycle.avg",
                new Contribution(null, null, "TIMESTAMPDIFF(SECOND, r.created_at, r.closed_at)"));
        delivery.put("cycle.p90",
                new Contribution(null, null, "TIMESTAMPDIFF(SECOND, r.created_at, r.closed_at)"));
        all.put(AnalyticsDatasets.DELIVERY, delivery);

        Map<String, Contribution> approval = new LinkedHashMap<>();
        approval.put("approval.total", new Contribution("1", null, null));
        String taskSeconds = "TIMESTAMPDIFF(SECOND, wt.created_at, COALESCE(wt.handled_at, NOW(3)))";
        approval.put("approval.avgHours", new Contribution(null, null, taskSeconds));
        approval.put("approval.p90Hours", new Contribution(null, null, taskSeconds));
        approval.put("approval.passRate", new Contribution(
                "CASE WHEN wt.status = 'APPROVED' THEN 1 ELSE 0 END",
                "CASE WHEN wt.status IN ('APPROVED', 'REJECTED') THEN 1 ELSE 0 END", null));
        all.put(AnalyticsDatasets.APPROVAL, approval);

        Map<String, Contribution> quality = new LinkedHashMap<>();
        String defects = "(SELECT COUNT(*) FROM defect df WHERE df.requirement_id = r.id)";
        String executed = "(SELECT COUNT(*) FROM test_execution te WHERE te.requirement_id = r.id"
                + " AND te.result IN ('PASS', 'FAIL', 'BLOCKED'))";
        String passed = "(SELECT COUNT(*) FROM test_execution te WHERE te.requirement_id = r.id"
                + " AND te.result = 'PASS')";
        quality.put("req.total", new Contribution("1", null, null));
        quality.put("quality.defects", new Contribution(defects, null, null));
        quality.put("quality.passRate", new Contribution(passed, executed, null));
        quality.put("quality.defectDensity", new Contribution(defects, "1", null));
        quality.put("quality.releaseSuccess", new Contribution(
                "CASE WHEN rel.released_at IS NOT NULL AND rel.planned_release_date IS NOT NULL"
                        + " AND DATE(rel.released_at) <= rel.planned_release_date THEN 1 ELSE 0 END",
                "CASE WHEN rel.planned_release_date IS NOT NULL THEN 1 ELSE 0 END", null));
        all.put(AnalyticsDatasets.QUALITY, quality);

        return Map.copyOf(all);
    }

    private static Map<String, String> buildBucketBase() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(AnalyticsDatasets.REQUIREMENT + "|week", "r.created_at");
        map.put(AnalyticsDatasets.REQUIREMENT + "|month", "r.created_at");
        map.put(AnalyticsDatasets.REQUIREMENT + "|planMonth", "r.plan_end_date");
        map.put(AnalyticsDatasets.DELIVERY + "|month", "r.created_at");
        map.put(AnalyticsDatasets.DELIVERY + "|planMonth", "rel.planned_release_date");
        map.put(AnalyticsDatasets.APPROVAL + "|month", "wi.started_at");
        map.put(AnalyticsDatasets.QUALITY + "|month", "r.created_at");
        return Map.copyOf(map);
    }
}
