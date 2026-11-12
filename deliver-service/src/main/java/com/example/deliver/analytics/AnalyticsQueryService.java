package com.example.deliver.analytics;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.RequirementStates;

/**
 * The one query orchestrator behind the workbench, the dashboard, the report cross table and the
 * report export.
 *
 * <p>All four outlets ask this class, and this class asks {@link AnalyticsSql} for SQL and
 * {@link AnalyticsMetrics} for formulas. That is the whole design: a rate or a P90 computed at a
 * second call site is how the same product starts showing three different "完成率" numbers. The
 * page and the export call the same {@link #crossTab} (5000 rows for the page, 10000 for the
 * export), so "the export has the same rows as the page" is true by construction rather than by a
 * test that has to keep two code paths in sync.</p>
 *
 * <p>Aggregation happens in Java, not in a SQL {@code GROUP BY}: the row-level scope filter is
 * already applied by {@link ScopedJdbc}, and pulling the group's numerator, denominator and raw
 * seconds through {@link AnalyticsMetrics} keeps exactly one percentile and one ratio
 * implementation. The cost is a bounded stream ({@link #MAX_GROUPS} groups per query), the benefit
 * is that {@code cycle.p90} on the dashboard and on the flow page cannot drift.</p>
 */
@Service
public class AnalyticsQueryService {

    /** Distinct row/column combinations one cross table may hold. */
    public static final int MAX_GROUPS = 5000;

    /** Rows one export may hold (the page uses {@link #MAX_GROUPS}). */
    public static final int EXPORT_MAX_ROWS = 10000;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final String NO_COLUMN = "\u0000";

    private static final List<String> DEFAULT_KEYS = List.of(
            "req.total", "req.newCount", "req.completionRate", "req.pendingTest",
            "cycle.avg", "cycle.p90", "effort.deviation", "quality.passRate");

    private final ScopedJdbc jdbc;
    private final AnalyticsCache cache;

    public AnalyticsQueryService(ScopedJdbc jdbc, AnalyticsCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    /** The metric strip a workbench tile row asks for when it does not name keys. */
    public static List<String> defaultKeys() {
        return DEFAULT_KEYS;
    }

    // ------------------------------------------------------------------ metrics

    /**
     * The metric strip: current window plus the equidistant previous window, with the ratio to it.
     *
     * <p>Both windows are read through the same {@link Snapshot}, so a delta compares like with
     * like (same scope, same filters, only the dates moved).</p>
     */
    public AnalyticsViews.Metrics metrics(AnalyticsRequest req, List<String> keys, String[] header) {
        AnalyticsRequest resolved = resolve(req);
        List<String> wanted = keys == null || keys.isEmpty() ? DEFAULT_KEYS : List.copyOf(keys);
        for (String key : wanted) {
            AnalyticsMetrics.spec(key);
        }
        String cacheKey = cachePrefix(resolved, 0L) + "|metrics|" + String.join(",", wanted);
        AnalyticsViews.Metrics cached = cache.get(cacheKey, AnalyticsViews.Metrics.class, header);
        if (cached != null) {
            return withCache(cached, AnalyticsCache.HIT);
        }
        String asOf = dataAsOf();
        Snapshot current = new Snapshot(resolved);
        Snapshot previous = new Snapshot(resolved.previous());
        List<AnalyticsViews.Metric> items = new ArrayList<>(wanted.size());
        for (String key : wanted) {
            AnalyticsViews.MetricSpec spec = AnalyticsMetrics.spec(key);
            Double value = current.value(key);
            Double prev = previous.value(key);
            items.add(new AnalyticsViews.Metric(spec.key(), spec.label(), spec.unit(), spec.digits(),
                    value, prev, AnalyticsMetrics.change(value, prev), spec.caliber(), asOf));
        }
        AnalyticsViews.Metrics result = new AnalyticsViews.Metrics(asOf, AnalyticsCache.MISS, items);
        cache.put(cacheKey, result);
        return result;
    }

    // ------------------------------------------------------------------ dashboard

    /** The five dashboard blocks, all read from the same resolved request. */
    public AnalyticsViews.Dashboard dashboard(AnalyticsRequest req, String bucket, String[] header) {
        AnalyticsRequest resolved = resolve(req);
        String unit = AnalyticsSql.normalizeBucket(bucket);
        String cacheKey = cachePrefix(resolved, 0L) + "|dashboard|" + unit;
        AnalyticsViews.Dashboard cached = cache.get(cacheKey, AnalyticsViews.Dashboard.class, header);
        if (cached != null) {
            return withCache(cached, AnalyticsCache.HIT);
        }
        String asOf = dataAsOf();
        Snapshot snapshot = new Snapshot(resolved);

        AnalyticsViews.CrossTab matrix = crossTab(AnalyticsDatasets.REQUIREMENT,
                AnalyticsViews.ReportDefinition.of(List.of("domain"), List.of("status"),
                        List.of("req.total"), List.of()),
                resolved, MAX_GROUPS, null);
        AnalyticsViews.Breakdown breakdown = new AnalyticsViews.Breakdown("domainStatus",
                "需求量 · 域 × 状态", matrix.colHeaders(), matrix.rows(), matrix.subtotal(),
                matrix.total());

        AnalyticsViews.Effort effort = new AnalyticsViews.Effort(
                snapshot.value("effort.estimate"), snapshot.value("effort.actual"),
                snapshot.value("effort.deviation"), snapshot.missingEstimate(), "人天");

        AnalyticsViews.Dashboard result = new AnalyticsViews.Dashboard(List.of(breakdown),
                trend(resolved, unit), segments(snapshot), effort, vendorRank(resolved),
                quality(snapshot), asOf, AnalyticsCache.MISS);
        cache.put(cacheKey, result);
        return result;
    }

    private AnalyticsViews.Trend trend(AnalyticsRequest req, String unit) {
        String timeField = "WEEK".equals(unit) ? "week" : "month";
        AnalyticsViews.ReportDefinition def = AnalyticsViews.ReportDefinition.of(
                List.of(timeField), List.of(), List.of("req.total"), List.of());
        List<AnalyticsViews.CrossRow> current = sortedByKey(crossTab(
                AnalyticsDatasets.REQUIREMENT, def, req, MAX_GROUPS, null).rows());
        List<AnalyticsViews.CrossRow> previous = sortedByKey(crossTab(
                AnalyticsDatasets.REQUIREMENT, def, req.previous(), MAX_GROUPS, null).rows());
        int offset = previous.size() - current.size();
        List<AnalyticsViews.TrendPoint> points = new ArrayList<>(current.size());
        for (int i = 0; i < current.size(); i++) {
            AnalyticsViews.CrossRow row = current.get(i);
            Double value = first(row);
            int mirror = i + offset;
            Double prev = mirror >= 0 && mirror < previous.size() ? first(previous.get(mirror)) : null;
            points.add(new AnalyticsViews.TrendPoint(row.key(),
                    value == null ? null : Math.round(value), prev == null ? null : Math.round(prev),
                    AnalyticsMetrics.change(value, prev)));
        }
        return new AnalyticsViews.Trend(unit, points);
    }

    private List<AnalyticsViews.Segment> segments(Snapshot snapshot) {
        List<List<Long>> buckets = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            buckets.add(new ArrayList<>());
        }
        for (Long seconds : snapshot.cycleSamples()) {
            if (seconds == null) {
                continue;
            }
            double days = seconds / 86_400d;
            int index = days <= 3 ? 0 : days <= 7 ? 1 : days <= 15 ? 2 : days <= 30 ? 3 : 4;
            buckets.get(index).add(seconds);
        }
        String[] keys = {"lte3", "d4_7", "d8_15", "d16_30", "gt30"};
        String[] labels = {"≤3 天", "4–7 天", "8–15 天", "16–30 天", ">30 天"};
        List<AnalyticsViews.Segment> segments = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            List<Long> samples = buckets.get(i);
            segments.add(new AnalyticsViews.Segment(keys[i], labels[i], samples.size(),
                    AnalyticsMetrics.avgCycle(samples), AnalyticsMetrics.p90(samples), i == 4));
        }
        return segments;
    }

    private List<AnalyticsViews.VendorRank> vendorRank(AnalyticsRequest req) {
        Map<String, Object> params = windowParams(req);
        params.put("complete", new ArrayList<>(RequirementStates.completeStatuses()));
        String sql = "SELECT r.assignee_vendor_id AS vid,"
                + " COALESCE(sv.name, CONCAT('厂商#', r.assignee_vendor_id)) AS vname,"
                + " CASE WHEN r.expected_delivery_date IS NOT NULL THEN 1 ELSE 0 END AS expected_n,"
                + " CASE WHEN r.expected_delivery_date IS NOT NULL AND DATE(r.closed_at)"
                + " <= r.expected_delivery_date THEN 1 ELSE 0 END AS ontime_n,"
                + " TIMESTAMPDIFF(SECOND, r.created_at, r.closed_at) AS sec"
                + " FROM requirement r"
                + " LEFT JOIN sys_vendor sv ON sv.id = r.assignee_vendor_id"
                + " AND sv.tenant_id = r.tenant_id"
                + " WHERE /*scope:r*/ AND r.status IN (:complete)"
                + " AND r.closed_at >= :fromTs AND r.closed_at < :toTs";
        Map<String, VendorAgg> aggs = new LinkedHashMap<>();
        jdbc.stream(sql, params, row -> {
            Number vid = (Number) row.get("vid");
            String key = vid == null ? "-" : String.valueOf(vid.longValue());
            VendorAgg agg = aggs.computeIfAbsent(key, ignored -> new VendorAgg(
                    vid == null ? null : vid.longValue(), text(row.get("vname"))));
            agg.completed++;
            agg.expected += AnalyticsQueryService.number(row.get("expected_n"));
            agg.onTime += AnalyticsQueryService.number(row.get("ontime_n"));
            Number seconds = (Number) row.get("sec");
            if (seconds != null) {
                agg.samples.add(seconds.longValue());
            }
        });

        DataScope scope = TenantContext.scopeOrNull();
        Long mineVendor = scope == null ? null : scope.vendorId();
        List<AnalyticsViews.VendorRank> ranked = new ArrayList<>();
        aggs.values().stream()
                .sorted((a, b) -> {
                    int byCount = Long.compare(b.completed, a.completed);
                    if (byCount != 0) {
                        return byCount;
                    }
                    Double rateA = AnalyticsMetrics.completionRate((long) a.onTime, (long) a.expected);
                    Double rateB = AnalyticsMetrics.completionRate((long) b.onTime, (long) b.expected);
                    if (rateA == null && rateB == null) {
                        return a.name.compareTo(b.name);
                    }
                    if (rateA == null) {
                        return 1;
                    }
                    if (rateB == null) {
                        return -1;
                    }
                    return Double.compare(rateB, rateA);
                })
                .limit(10)
                .forEach(agg -> ranked.add(new AnalyticsViews.VendorRank(ranked.size() + 1,
                        agg.vendorId, agg.name, agg.completed,
                        AnalyticsMetrics.completionRate((long) agg.onTime, (long) agg.expected),
                        AnalyticsMetrics.avgCycle(agg.samples),
                        mineVendor != null && mineVendor.equals(agg.vendorId))));
        List<AnalyticsViews.VendorRank> ordered = new ArrayList<>(ranked.size());
        for (AnalyticsViews.VendorRank rank : ranked) {
            if (rank.mine()) {
                ordered.add(rank);
            }
        }
        for (AnalyticsViews.VendorRank rank : ranked) {
            if (!rank.mine()) {
                ordered.add(rank);
            }
        }
        return ordered;
    }

    private AnalyticsViews.Quality quality(Snapshot snapshot) {
        List<AnalyticsViews.QualityCard> cards = new ArrayList<>(3);
        cards.add(card("quality.passRate", snapshot, 90d, 80d, true));
        cards.add(card("quality.defectDensity", snapshot, 0.1d, 0.3d, false));
        cards.add(card("quality.releaseSuccess", snapshot, 95d, 85d, true));
        return new AnalyticsViews.Quality(cards);
    }

    private AnalyticsViews.QualityCard card(String key, Snapshot snapshot, double good, double warn,
                                            boolean higherIsBetter) {
        AnalyticsViews.MetricSpec spec = AnalyticsMetrics.spec(key);
        Double value = snapshot.value(key);
        return new AnalyticsViews.QualityCard(spec.key(), spec.label(), value, spec.unit(),
                spec.digits(), level(value, good, warn, higherIsBetter), snapshot.sample(key));
    }

    private static String level(Double value, double good, double warn, boolean higherIsBetter) {
        if (value == null) {
            return AnalyticsViews.QualityCard.NONE;
        }
        if (higherIsBetter) {
            return value >= good ? AnalyticsViews.QualityCard.GOOD
                    : value >= warn ? AnalyticsViews.QualityCard.WARN
                    : AnalyticsViews.QualityCard.BAD;
        }
        return value <= good ? AnalyticsViews.QualityCard.GOOD
                : value <= warn ? AnalyticsViews.QualityCard.WARN
                : AnalyticsViews.QualityCard.BAD;
    }

    // ------------------------------------------------------------------ cross table

    /** The page path (and the dashboard blocks): no report id, so eviction cannot hit it. */
    public AnalyticsViews.CrossTab crossTab(String dataset, AnalyticsViews.ReportDefinition def,
                                            AnalyticsRequest req, int maxRows, String[] header) {
        return crossTab(dataset, def, req, maxRows, 0L, header);
    }

    /**
     * The cross table shared by the report page and the export.
     *
     * @param reportId the definition the answer belongs to, so a save can evict only its entry
     */
    public AnalyticsViews.CrossTab crossTab(String dataset, AnalyticsViews.ReportDefinition def,
                                            AnalyticsRequest req, int maxRows, long reportId,
                                            String[] header) {
        AnalyticsRequest resolved = resolve(req);
        AnalyticsDatasets.check(dataset, def);
        int cap = maxRows > 0 ? maxRows : MAX_GROUPS;
        String cacheKey = cachePrefix(resolved, reportId) + "|cross|" + dataset + '|'
                + signature(def) + '|' + cap;
        AnalyticsViews.CrossTab cached = cache.get(cacheKey, AnalyticsViews.CrossTab.class, header);
        if (cached != null) {
            return withCache(cached, AnalyticsCache.HIT);
        }
        String asOf = dataAsOf();
        long groups = countGroups(dataset, def, resolved);
        if (groups > cap) {
            throw AnalyticsErrors.groupLimit(groups, cap);
        }

        List<String> dims = def.dimensions();
        List<String> measures = def.measures();
        int rowDimCount = def.rows().size();
        int measureCount = measures.size();
        boolean[] sampleBased = new boolean[measureCount];
        boolean[] hasDen = new boolean[measureCount];

        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < dims.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(AnalyticsSql.column(dataset, dims.get(i))).append(" AS d").append(i);
        }
        for (int i = 0; i < measureCount; i++) {
            AnalyticsSql.Contribution contribution = AnalyticsSql.contribution(dataset, measures.get(i));
            sampleBased[i] = contribution.sampleBased();
            hasDen[i] = contribution.hasDen();
            if (contribution.sampleBased()) {
                sql.append(", ").append(contribution.sample()).append(" AS s").append(i);
            } else {
                sql.append(", ").append(contribution.num()).append(" AS n").append(i);
                if (contribution.hasDen()) {
                    sql.append(", ").append(contribution.den()).append(" AS t").append(i);
                }
            }
        }
        Map<String, Object> params = windowParams(resolved);
        sql.append(' ').append(AnalyticsSql.base(dataset))
                .append(AnalyticsSql.windowClause(dataset))
                .append(filterClause(dataset, def.filters(), params));

        Map<String, Mail[]> cells = new LinkedHashMap<>();
        Map<String, Mail[]> rowAggs = new LinkedHashMap<>();
        Map<String, Mail[]> colAggs = new LinkedHashMap<>();
        Set<String> rowKeys = new LinkedHashSet<>();
        Set<String> colKeys = new LinkedHashSet<>();
        // r14：raw 与上屏并行采集。key 一律用 raw（cells/rowAggs/colAggs 的键、排序、合并语义都不变），
        // 显示串单独存一张表，出表头时按 raw 键回查，避免把中文塞进聚合键导致同一列被拆成两列。
        Map<String, String> rowDisplays = new LinkedHashMap<>();
        Map<String, String> colDisplays = new LinkedHashMap<>();
        Mail[] grand = newMails(measureCount);

        jdbc.stream(sql.toString(), params, row -> {
            StringBuilder rowBuffer = new StringBuilder();
            StringBuilder colBuffer = new StringBuilder();
            StringBuilder rowShow = new StringBuilder();
            StringBuilder colShow = new StringBuilder();
            for (int i = 0; i < dims.size(); i++) {
                String value = text(row.get("d" + i));
                String shown = AnalyticsLabels.display(dataset, dims.get(i), value);
                if (i < rowDimCount) {
                    if (rowBuffer.length() > 0) {
                        rowBuffer.append(" / ");
                        rowShow.append(" / ");
                    }
                    rowBuffer.append(value);
                    rowShow.append(shown);
                } else {
                    if (colBuffer.length() > 0) {
                        colBuffer.append(" / ");
                        colShow.append(" / ");
                    }
                    colBuffer.append(value);
                    colShow.append(shown);
                }
            }
            String rowKey = rowBuffer.toString();
            String colKey = colBuffer.length() == 0 ? "合计" : colBuffer.toString();
            rowKeys.add(rowKey);
            colKeys.add(colKey);
            rowDisplays.putIfAbsent(rowKey, rowShow.toString());
            if (colBuffer.length() > 0) {
                colDisplays.putIfAbsent(colKey, colShow.toString());
            }
            Mail[] cell = cells.computeIfAbsent(rowKey + NO_COLUMN + colKey,
                    ignored -> newMails(measureCount));
            Mail[] rowMail = rowAggs.computeIfAbsent(rowKey, ignored -> newMails(measureCount));
            Mail[] colMail = colAggs.computeIfAbsent(colKey, ignored -> newMails(measureCount));
            for (int i = 0; i < measureCount; i++) {
                if (sampleBased[i]) {
                    Number seconds = (Number) row.get("s" + i);
                    if (seconds != null) {
                        addSample(cell[i], rowMail[i], colMail[i], grand[i], seconds.longValue());
                    }
                } else {
                    double numerator = number(row.get("n" + i));
                    double denominator = hasDen[i] ? number(row.get("t" + i)) : 1d;
                    addParts(cell[i], rowMail[i], colMail[i], grand[i], numerator, denominator);
                }
            }
        });

        // 排序仍对 raw 做：中文排序会打乱列序，与 r13 的逐列对齐会失守（AC-2 列序不变）
        List<String> sortedKeys = new ArrayList<>(rowKeys);
        Collections.sort(sortedKeys);
        List<String> orderedCols = new ArrayList<>(colKeys);
        Collections.sort(orderedCols);

        List<String> colHeaders = new ArrayList<>();
        List<String> colCodes = new ArrayList<>();
        List<String> colOrder = new ArrayList<>();
        List<Integer> measureOrder = new ArrayList<>();
        for (String colKey : orderedCols) {
            String shownCol = colDisplays.getOrDefault(colKey, colKey);
            for (int i = 0; i < measureCount; i++) {
                String label = AnalyticsMetrics.spec(measures.get(i)).label();
                colHeaders.add(def.cols().isEmpty() ? label : shownCol + " · " + label);
                // 无列维时 colCodes 为空数组（PRD AC-3 特例）；有列维时与 colHeaders 同长同序
                colCodes.add(def.cols().isEmpty() ? null : colKey);
                colOrder.add(def.cols().isEmpty() ? NO_COLUMN : colKey);
                measureOrder.add(i);
            }
        }
        if (def.cols().isEmpty()) {
            colCodes = new ArrayList<>();
        }

        List<AnalyticsViews.CrossRow> rows = new ArrayList<>(sortedKeys.size());
        for (String rowKey : sortedKeys) {
            List<Double> values = new ArrayList<>(colHeaders.size());
            List<Long> counts = new ArrayList<>(colHeaders.size());
            for (int idx = 0; idx < colHeaders.size(); idx++) {
                Mail[] mail = def.cols().isEmpty()
                        ? rowAggs.get(rowKey)
                        : cells.get(rowKey + NO_COLUMN + colOrder.get(idx));
                Mail slot = mail == null ? new Mail() : mail[measureOrder.get(idx)];
                values.add(value(measures.get(measureOrder.get(idx)), slot));
                counts.add(slot.rows);
            }
            rows.add(new AnalyticsViews.CrossRow(rowKey, values, counts));
        }
        sortRows(rows);
        // 行头/行 code 只能在 sortRows 之后按 rows 的 key 生成：sortRows 是按首个度量的值降序重排
        // （不是按 key），先建行头再排序会让标签错位到别的行——r13 里看不出来只因为前端一直读 row.key。
        List<String> rowHeaders = rowHeadersOf(rows, rowDisplays);
        List<String> rowCodes = new ArrayList<>(rows.size());
        for (AnalyticsViews.CrossRow row : rows) {
            rowCodes.add(row.key());
        }

        List<Double> subtotalValues = new ArrayList<>(colHeaders.size());
        List<Long> subtotalCounts = new ArrayList<>(colHeaders.size());
        for (int idx = 0; idx < colHeaders.size(); idx++) {
            int measureIndex = measureOrder.get(idx);
            if (def.cols().isEmpty()) {
                subtotalValues.add(value(measures.get(measureIndex), grand[measureIndex]));
                subtotalCounts.add(grand[measureIndex].rows);
            } else {
                Mail[] mail = colAggs.get(colOrder.get(idx));
                Mail slot = mail == null ? new Mail() : mail[measureIndex];
                subtotalValues.add(value(measures.get(measureIndex), slot));
                subtotalCounts.add(slot.rows);
            }
        }

        List<Double> totalValues = new ArrayList<>(measureCount);
        List<Long> totalCounts = new ArrayList<>(measureCount);
        for (int i = 0; i < measureCount; i++) {
            totalValues.add(value(measures.get(i), grand[i]));
            totalCounts.add(grand[i].rows);
        }

        AnalyticsViews.CrossTab result = new AnalyticsViews.CrossTab(rowHeaders, colHeaders, rows,
                new AnalyticsViews.CrossRow("小计", subtotalValues, subtotalCounts),
                new AnalyticsViews.CrossRow("总计", totalValues, totalCounts),
                (int) Math.min(groups, Integer.MAX_VALUE), false, asOf, AnalyticsCache.MISS,
                Collections.unmodifiableList(colCodes), Collections.unmodifiableList(rowCodes));
        cache.put(cacheKey, result);
        return result;
    }

    /**
     * How many row/column groups the definition would produce.
     *
     * <p>Counted in SQL before streaming so an over-broad definition fails with
     * {@code REPORT_GROUP_LIMIT} and a number, instead of streaming 200k rows into memory.</p>
     */
    public long countGroups(String dataset, AnalyticsViews.ReportDefinition def, AnalyticsRequest req) {
        AnalyticsRequest resolved = resolve(req);
        AnalyticsDatasets.check(dataset, def);
        List<String> dims = def.dimensions();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM (SELECT DISTINCT ");
        for (int i = 0; i < dims.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(AnalyticsSql.column(dataset, dims.get(i))).append(" AS c").append(i);
        }
        Map<String, Object> params = windowParams(resolved);
        sql.append(' ').append(AnalyticsSql.base(dataset))
                .append(AnalyticsSql.windowClause(dataset))
                .append(filterClause(dataset, def.filters(), params))
                .append(") g");
        return jdbc.count(sql.toString(), params);
    }

    private Double value(String measureKey, Mail mail) {
        return AnalyticsMetrics.compute(measureKey, mail.num, mail.den,
                mail.samples.isEmpty() ? null : mail.samples).value();
    }

    private static void addSample(Mail cell, Mail row, Mail col, Mail grand, long seconds) {
        for (Mail mail : new Mail[]{cell, row, col, grand}) {
            mail.samples.add(seconds);
            mail.rows++;
        }
    }

    private static void addParts(Mail cell, Mail row, Mail col, Mail grand, double num, double den) {
        for (Mail mail : new Mail[]{cell, row, col, grand}) {
            mail.num += num;
            mail.den += den;
            mail.rows++;
        }
    }

    private static Mail[] newMails(int count) {
        Mail[] mails = new Mail[count];
        for (int i = 0; i < count; i++) {
            mails[i] = new Mail();
        }
        return mails;
    }

    /**
     * 行头与 {@code rows} 同序（r14 AC-3）：rows 已排好序，这里只按 {@code key()} 回查上屏串，
     * 未登记的 key 原样返回。包级可见，便于单测钉住「行头不会错位到别的行」。
     */
    static List<String> rowHeadersOf(List<AnalyticsViews.CrossRow> rows, Map<String, String> displays) {
        List<String> headers = new ArrayList<>(rows.size());
        for (AnalyticsViews.CrossRow row : rows) {
            headers.add(displays.getOrDefault(row.key(), row.key()));
        }
        return headers;
    }

    private static void sortRows(List<AnalyticsViews.CrossRow> rows) {
        rows.sort((a, b) -> {
            Double left = a.values().isEmpty() ? null : a.values().get(0);
            Double right = b.values().isEmpty() ? null : b.values().get(0);
            if (left == null && right == null) {
                return a.key().compareTo(b.key());
            }
            if (left == null) {
                return 1;
            }
            if (right == null) {
                return -1;
            }
            int byValue = Double.compare(right, left);
            return byValue != 0 ? byValue : a.key().compareTo(b.key());
        });
    }

    private static List<AnalyticsViews.CrossRow> sortedByKey(List<AnalyticsViews.CrossRow> rows) {
        List<AnalyticsViews.CrossRow> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> a.key().compareTo(b.key()));
        return sorted;
    }

    private static Double first(AnalyticsViews.CrossRow row) {
        return row.values().isEmpty() ? null : row.values().get(0);
    }

    // ------------------------------------------------------------------ filters / params

    private String filterClause(String dataset, List<AnalyticsViews.Filter> filters,
                                Map<String, Object> params) {
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < filters.size(); index++) {
            AnalyticsViews.Filter filter = filters.get(index);
            List<String> values = filter.values();
            if (values.isEmpty()) {
                continue;
            }
            String column = AnalyticsSql.filterColumn(dataset, filter.field());
            String op = filter.op() == null ? "eq" : filter.op();
            switch (op) {
                case "in", "notIn" -> {
                    StringBuilder names = new StringBuilder();
                    for (int value = 0; value < values.size(); value++) {
                        String name = "f" + index + "_" + value;
                        if (value > 0) {
                            names.append(", ");
                        }
                        names.append(':').append(name);
                        params.put(name, typed(values.get(value)));
                    }
                    sql.append(" AND ").append(column)
                            .append("in".equals(op) ? " IN (" : " NOT IN (")
                            .append(names).append(')');
                }
                case "ne", "gte", "lte", "gt", "lt" -> {
                    String name = "f" + index + "_0";
                    params.put(name, typed(values.get(0)));
                    String symbol = switch (op) {
                        case "ne" -> "<>";
                        case "gte" -> ">=";
                        case "lte" -> "<=";
                        case "gt" -> ">";
                        default -> "<";
                    };
                    sql.append(" AND ").append(column).append(' ').append(symbol)
                            .append(" :").append(name);
                }
                case "contains" -> {
                    String name = "f" + index + "_0";
                    params.put(name, "%" + values.get(0) + "%");
                    sql.append(" AND ").append(column).append(" LIKE :").append(name);
                }
                default -> {
                    String name = "f" + index + "_0";
                    params.put(name, typed(values.get(0)));
                    sql.append(" AND ").append(column).append(" = :").append(name);
                }
            }
        }
        return sql.toString();
    }

    private static Object typed(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static Map<String, Object> windowParams(AnalyticsRequest req) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fromTs", req.from().atStartOfDay());
        params.put("toTs", req.to().plusDays(1).atStartOfDay());
        return params;
    }

    private String cachePrefix(AnalyticsRequest req, long reportId) {
        DataScope scope = TenantContext.scopeOrNull();
        String tenant = scope == null ? "-" : String.valueOf(scope.tenantId());
        long userId = scope == null ? 0L : scope.userId();
        return "report:" + reportId + '|' + req.cacheKey(tenant, userId);
    }

    private static String signature(AnalyticsViews.ReportDefinition def) {
        return def.rows() + "|" + def.cols() + "|" + def.measures() + "|" + def.filters();
    }

    private AnalyticsRequest resolve(AnalyticsRequest req) {
        AnalyticsRequest safe = req == null ? AnalyticsRequest.empty() : req;
        return safe.resolve(LocalDate.now(ZONE));
    }

    private static String dataAsOf() {
        return ZonedDateTime.now(ZONE).format(STAMP);
    }

    // ------------------------------------------------------------------ snapshot

    /**
     * Every metric's parts for one window, read once.
     *
     * <p>One snapshot per window keeps the strip internally consistent: "需求总量" and "完成率" on
     * the same tile row are computed from the same read, not from two queries milliseconds apart.
     * A missing sample stays {@code null} all the way to the JSON.</p>
     */
    private final class Snapshot {

        private final Map<String, Object> params;
        private final double totalAll;
        private final double windowCount;
        private final double done;
        private final double pooled;
        private final double pendingTest;
        private final double active;
        private final double overdue;
        private final double mineOpen;
        private final double split;
        private final double estimate;
        private final double actual;
        private final double missingEstimate;
        private final double members;
        private final double accepted;
        private final double expected;
        private final double onTime;
        private final double executed;
        private final double passed;
        private final double defects;
        private final double releasedOnTime;
        private final double plannedReleases;
        private final double rejected;
        private final double approvalTotal;
        private final double approved;
        private final double decided;
        private final double definitions;
        private final List<Long> cycleSamples = new ArrayList<>();
        private final List<Long> dwellSamples = new ArrayList<>();
        private final List<Long> approvalSamples = new ArrayList<>();

        private Snapshot(AnalyticsRequest req) {
            this.params = windowParams(req);
            params.put("complete", new ArrayList<>(RequirementStates.completeStatuses()));
            params.put("pooled", RequirementStates.POOLED);
            params.put("pendingTest", RequirementStates.PENDING_TEST);
            params.put("uid", viewerId());

            Map<String, Object> core = jdbc.one(CORE_SQL, params);
            this.windowCount = number(core == null ? null : core.get("n"));
            this.done = number(core == null ? null : core.get("done"));
            this.pooled = number(core == null ? null : core.get("pooled"));
            this.pendingTest = number(core == null ? null : core.get("pending_test"));
            this.active = number(core == null ? null : core.get("active_n"));
            this.overdue = number(core == null ? null : core.get("overdue"));
            this.mineOpen = number(core == null ? null : core.get("mine_open"));
            this.split = number(core == null ? null : core.get("split_n"));
            this.estimate = number(core == null ? null : core.get("est"));
            this.actual = number(core == null ? null : core.get("act"));
            this.missingEstimate = number(core == null ? null : core.get("missing_est"));
            this.members = number(core == null ? null : core.get("members"));
            this.totalAll = jdbc.count(COUNT_ALL_SQL, params);

            Map<String, Object> delivery = jdbc.one(DELIVERY_SQL, params);
            this.accepted = number(delivery == null ? null : delivery.get("accepted"));
            this.expected = number(delivery == null ? null : delivery.get("expected_n"));
            this.onTime = number(delivery == null ? null : delivery.get("ontime"));

            Map<String, Object> tests = jdbc.one(EXECUTION_SQL, params);
            this.executed = number(tests == null ? null : tests.get("executed"));
            this.passed = number(tests == null ? null : tests.get("passed"));

            Map<String, Object> quality = jdbc.one(DEFECT_SQL, params);
            this.defects = number(quality == null ? null : quality.get("n"));

            Map<String, Object> releases = jdbc.one(RELEASE_SQL, params);
            this.releasedOnTime = number(releases == null ? null : releases.get("ontime"));
            this.plannedReleases = number(releases == null ? null : releases.get("planned_n"));

            Map<String, Object> approvals = jdbc.one(APPROVAL_SQL, params);
            this.approvalTotal = number(approvals == null ? null : approvals.get("n"));
            this.approved = number(approvals == null ? null : approvals.get("approved"));
            this.decided = number(approvals == null ? null : approvals.get("decided"));
            this.definitions = number(approvals == null ? null : approvals.get("defs"));

            this.rejected = jdbc.count(REJECTED_SQL, params);

            collect(CYCLE_SQL, cycleSamples);
            collect(DWELL_SQL, dwellSamples);
            collect(APPROVAL_HOURS_SQL, approvalSamples);
        }

        private void collect(String sql, List<Long> target) {
            jdbc.stream(sql, params, row -> {
                Number seconds = (Number) row.get("sec");
                if (seconds != null) {
                    target.add(seconds.longValue());
                }
            });
        }

        Double value(String key) {
            return switch (key) {
                case "req.total" -> totalAll;
                case "req.newCount" -> windowCount;
                case "req.inPoolRate" -> AnalyticsMetrics.completionRate((long) pooled,
                        (long) windowCount);
                case "req.completionRate" -> AnalyticsMetrics.completionRate((long) done,
                        (long) windowCount);
                case "req.splitRate" -> AnalyticsMetrics.completionRate((long) split,
                        (long) windowCount);
                case "req.overdueCount" -> overdue;
                case "req.pendingTest" -> pendingTest;
                case "req.mineOpen" -> mineOpen;
                case "req.rejected" -> rejected;
                case "req.avgDwell" -> AnalyticsMetrics.avgCycle(dwellSamples);
                case "cycle.avg" -> AnalyticsMetrics.avgCycle(cycleSamples);
                case "cycle.p90" -> AnalyticsMetrics.p90(cycleSamples);
                case "effort.estimate" -> estimate;
                case "effort.actual" -> actual;
                case "effort.deviation" -> AnalyticsMetrics.deviation(actual, estimate);
                case "vendor.accepted" -> accepted;
                case "vendor.onTimeRate" -> AnalyticsMetrics.completionRate((long) onTime,
                        (long) expected);
                case "delivery.onTime" -> onTime;
                case "quality.passRate" -> AnalyticsMetrics.completionRate((long) passed,
                        (long) executed);
                case "quality.defects" -> defects;
                case "quality.defectDensity" -> AnalyticsMetrics.defectDensity((long) defects,
                        (long) windowCount);
                case "quality.releaseSuccess" -> AnalyticsMetrics.completionRate((long) releasedOnTime,
                        (long) plannedReleases);
                case "approval.total" -> approvalTotal;
                case "approval.avgHours" -> AnalyticsMetrics.hours(
                        AnalyticsMetrics.avgCycle(approvalSamples));
                case "approval.p90Hours" -> AnalyticsMetrics.hours(
                        AnalyticsMetrics.p90(approvalSamples));
                case "approval.passRate" -> AnalyticsMetrics.completionRate((long) approved,
                        (long) decided);
                case "org.memberCount" -> members;
                case "org.activeReq" -> active;
                case "workflow.enabled" -> definitions;
                default -> throw AnalyticsErrors.unknownField("metrics", key);
            };
        }

        long sample(String key) {
            return switch (key) {
                case "quality.passRate" -> (long) executed;
                case "quality.defectDensity" -> (long) windowCount;
                case "quality.releaseSuccess" -> (long) plannedReleases;
                default -> (long) windowCount;
            };
        }

        long missingEstimate() {
            return (long) missingEstimate;
        }

        List<Long> cycleSamples() {
            return cycleSamples;
        }
    }

    private static final String CORE_SQL = "SELECT COUNT(*) AS n,"
            + " SUM(CASE WHEN r.status IN (:complete) THEN 1 ELSE 0 END) AS done,"
            + " SUM(CASE WHEN r.status = :pooled THEN 1 ELSE 0 END) AS pooled,"
            + " SUM(CASE WHEN r.status = :pendingTest THEN 1 ELSE 0 END) AS pending_test,"
            + " SUM(CASE WHEN r.status NOT IN (:complete) THEN 1 ELSE 0 END) AS active_n,"
            + " SUM(CASE WHEN r.plan_end_date IS NOT NULL AND r.plan_end_date < CURDATE()"
            + " AND r.status NOT IN (:complete) THEN 1 ELSE 0 END) AS overdue,"
            + " SUM(CASE WHEN r.owner_user_id = :uid AND r.status NOT IN (:complete)"
            + " THEN 1 ELSE 0 END) AS mine_open,"
            + " SUM(CASE WHEN EXISTS (SELECT 1 FROM requirement c WHERE c.parent_id = r.id"
            + " AND c.tenant_id = r.tenant_id) THEN 1 ELSE 0 END) AS split_n,"
            + " SUM(COALESCE(r.estimate_days, 0)) AS est,"
            + " SUM(COALESCE(r.actual_days, 0)) AS act,"
            + " SUM(CASE WHEN r.estimate_days IS NULL THEN 1 ELSE 0 END) AS missing_est,"
            + " COUNT(DISTINCT r.owner_user_id) AS members"
            + " FROM requirement r WHERE /*scope:r*/"
            + " AND r.created_at >= :fromTs AND r.created_at < :toTs";

    private static final String COUNT_ALL_SQL =
            "SELECT COUNT(*) FROM requirement r WHERE /*scope:r*/";

    private static final String DELIVERY_SQL = "SELECT COUNT(*) AS accepted,"
            + " SUM(CASE WHEN r.expected_delivery_date IS NOT NULL THEN 1 ELSE 0 END) AS expected_n,"
            + " SUM(CASE WHEN r.expected_delivery_date IS NOT NULL AND r.closed_at IS NOT NULL"
            + " AND DATE(r.closed_at) <= r.expected_delivery_date THEN 1 ELSE 0 END) AS ontime"
            + " FROM requirement r WHERE /*scope:r*/ AND r.status IN (:complete)"
            + " AND r.closed_at >= :fromTs AND r.closed_at < :toTs";

    private static final String EXECUTION_SQL = "SELECT COUNT(*) AS executed,"
            + " SUM(CASE WHEN e.result = 'PASS' THEN 1 ELSE 0 END) AS passed"
            + " FROM test_execution e"
            + " JOIN requirement r ON r.id = e.requirement_id AND r.tenant_id = e.tenant_id"
            + " WHERE /*scope:r*/ AND e.executed_at >= :fromTs AND e.executed_at < :toTs";

    private static final String DEFECT_SQL = "SELECT COUNT(*) AS n FROM defect d"
            + " WHERE /*scope:d*/ AND d.created_at >= :fromTs AND d.created_at < :toTs";

    private static final String RELEASE_SQL = "SELECT COUNT(*) AS released,"
            + " SUM(CASE WHEN rel.planned_release_date IS NOT NULL THEN 1 ELSE 0 END) AS planned_n,"
            + " SUM(CASE WHEN rel.planned_release_date IS NOT NULL AND rel.released_at IS NOT NULL"
            + " AND DATE(rel.released_at) <= rel.planned_release_date THEN 1 ELSE 0 END) AS ontime"
            + " FROM `release` rel WHERE /*scope:rel*/ AND rel.released_at >= :fromTs"
            + " AND rel.released_at < :toTs";

    private static final String APPROVAL_SQL = "SELECT COUNT(*) AS n,"
            + " COUNT(DISTINCT wi.definition_id) AS defs,"
            + " SUM(CASE WHEN wt.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved,"
            + " SUM(CASE WHEN wt.status IN ('APPROVED', 'REJECTED') THEN 1 ELSE 0 END) AS decided"
            + " FROM wf_instance wi"
            + " JOIN wf_task wt ON wt.instance_id = wi.id AND wt.tenant_id = wi.tenant_id"
            + " JOIN requirement r ON r.id = wi.biz_id AND r.tenant_id = wi.tenant_id"
            + " WHERE /*scope:r*/ AND wi.biz_type IN ('REQ_SUBMIT','REQ_REVIEW')"
            + " AND wi.started_at >= :fromTs AND wi.started_at < :toTs";

    private static final String REJECTED_SQL = "SELECT COUNT(*) FROM requirement r"
            + " WHERE /*scope:r*/ AND r.status NOT IN (:complete)"
            + " AND EXISTS (SELECT 1 FROM wf_instance x WHERE x.biz_id = r.id"
            + " AND x.biz_type IN ('REQ_SUBMIT','REQ_REVIEW') AND x.status = 'REJECTED'"
            + " AND x.ended_at = (SELECT MAX(y.ended_at) FROM wf_instance y WHERE y.biz_id = r.id"
            + " AND y.biz_type IN ('REQ_SUBMIT','REQ_REVIEW')))";

    private static final String CYCLE_SQL = "SELECT TIMESTAMPDIFF(SECOND, r.created_at, r.closed_at)"
            + " AS sec FROM requirement r WHERE /*scope:r*/ AND r.closed_at IS NOT NULL"
            + " AND r.closed_at >= :fromTs AND r.closed_at < :toTs";

    private static final String DWELL_SQL = "SELECT TIMESTAMPDIFF(SECOND, r.created_at,"
            + " COALESCE(r.closed_at, NOW(3))) AS sec FROM requirement r WHERE /*scope:r*/"
            + " AND r.created_at >= :fromTs AND r.created_at < :toTs";

    private static final String APPROVAL_HOURS_SQL = "SELECT TIMESTAMPDIFF(SECOND, wt.created_at,"
            + " COALESCE(wt.handled_at, NOW(3))) AS sec FROM wf_instance wi"
            + " JOIN wf_task wt ON wt.instance_id = wi.id AND wt.tenant_id = wi.tenant_id"
            + " JOIN requirement r ON r.id = wi.biz_id AND r.tenant_id = wi.tenant_id"
            + " WHERE /*scope:r*/ AND wi.biz_type IN ('REQ_SUBMIT','REQ_REVIEW')"
            + " AND wi.started_at >= :fromTs AND wi.started_at < :toTs";

    // ------------------------------------------------------------------ helpers

    private static long viewerId() {
        DataScope scope = TenantContext.scopeOrNull();
        return scope == null ? 0L : scope.userId();
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    private static String text(Object value) {
        if (value == null) {
            return "未设置";
        }
        String text = String.valueOf(value);
        return text.isBlank() ? "未设置" : text;
    }

    private static AnalyticsViews.Metrics withCache(AnalyticsViews.Metrics metrics, String cache) {
        return new AnalyticsViews.Metrics(metrics.dataAsOf(), cache, metrics.items());
    }

    private static AnalyticsViews.CrossTab withCache(AnalyticsViews.CrossTab table, String cache) {
        // 命中缓存也要带上 colCodes/rowCodes：只改主路径会让缓存响应缺字段（AC-3）
        return new AnalyticsViews.CrossTab(table.rowHeaders(), table.colHeaders(), table.rows(),
                table.subtotal(), table.total(), table.groups(), table.truncated(), table.dataAsOf(),
                cache, table.colCodes(), table.rowCodes());
    }

    private static AnalyticsViews.Dashboard withCache(AnalyticsViews.Dashboard dashboard,
                                                      String cache) {
        return new AnalyticsViews.Dashboard(dashboard.breakdowns(), dashboard.trend(),
                dashboard.segments(), dashboard.effort(), dashboard.vendorRank(),
                dashboard.quality(), dashboard.dataAsOf(), cache);
    }

    /** One cell's accumulator: summed parts, or the raw samples the formulas need. */
    private static final class Mail {

        private double num;
        private double den;
        private long rows;
        private final List<Long> samples = new ArrayList<>();
    }

    private static final class VendorAgg {

        private final Long vendorId;
        private final String name;
        private long completed;
        private double expected;
        private double onTime;
        private final List<Long> samples = new ArrayList<>();

        private VendorAgg(Long vendorId, String name) {
            this.vendorId = vendorId;
            this.name = name;
        }
    }
}
