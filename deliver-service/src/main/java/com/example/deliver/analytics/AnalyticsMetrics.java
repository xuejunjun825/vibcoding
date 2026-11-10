package com.example.deliver.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.flow.FlowMetricFormulas;
import com.example.platform.contract.MetricFormulas;

/**
 * The metric dictionary and the only formula entry point of the analytics engine.
 *
 * <p>Three surfaces read these numbers — the workbench tiles, the dashboard blocks and the
 * report cross table (plus its export). If any of them divided two columns itself, a rate would
 * drift between surfaces the first time one of them rounded differently. So the division happens
 * here, once, and it delegates to the formulas the product already owns:</p>
 *
 * <ul>
 *   <li>ratios → {@link MetricFormulas#passRate} / {@link MetricFormulas#defectDensity}
 *       (the same 0..1 {@code BigDecimal} the quality pages show);</li>
 *   <li>mean dwell and P90 dwell → {@link FlowMetricFormulas#avgDwell} /
 *       {@link FlowMetricFormulas#p90Dwell} (nearest-rank, the same implementation as
 *       {@code cycle.p90} on the flow page). There is no second percentile in the product, and
 *       the static gate {@code scripts/report-verify.sh r0} asserts exactly that.</li>
 * </ul>
 *
 * <p>Ratios are returned as percentages because every consumer displays a percentage and the
 * frontend is not allowed to multiply either. A ratio with a zero denominator is {@code null}
 * ("no sample"), never {@code 0} — a 0% completion rate and "nothing was created" are different
 * statements on a dashboard.</p>
 */
public final class AnalyticsMetrics {

    public static final String COUNT = "COUNT";
    public static final String SUM = "SUM";
    public static final String RATE = "RATE";
    public static final String DENSITY = "DENSITY";
    public static final String DEVIATION = "DEVIATION";
    public static final String AVG_DAYS = "AVG_DAYS";
    public static final String P90_DAYS = "P90_DAYS";
    public static final String AVG_HOURS = "AVG_HOURS";
    public static final String P90_HOURS = "P90_HOURS";

    private static final double SECONDS_PER_HOUR = 3_600d;

    private static final Map<String, AnalyticsViews.MetricSpec> SPECS = build();

    private AnalyticsMetrics() {
    }

    public static AnalyticsViews.MetricSpec spec(String key) {
        AnalyticsViews.MetricSpec spec = key == null ? null : SPECS.get(key);
        if (spec == null) {
            throw AnalyticsErrors.unknownField("metrics", String.valueOf(key));
        }
        return spec;
    }

    public static List<String> keys() {
        return List.copyOf(SPECS.keySet());
    }

    /**
     * Compute one metric from a numerator, a denominator and (for dwell metrics) raw samples.
     *
     * <p>{@code num}/{@code den} are sums over the group, so a rate is re-derived from the summed
     * parts and never averaged over averages.</p>
     */
    public static AnalyticsViews.Metric compute(String key, Number num, Number den, List<Long> samples) {
        AnalyticsViews.MetricSpec spec = spec(key);
        Double value = switch (spec.kind()) {
            case COUNT -> num == null ? null : num.doubleValue();
            case SUM -> num == null ? null : num.doubleValue();
            case RATE -> num == null || den == null
                    ? null
                    : completionRate(num.longValue(), den.longValue());
            case DENSITY -> num == null || den == null
                    ? null
                    : defectDensity(num.longValue(), den.longValue());
            case DEVIATION -> num == null || den == null ? null : deviation(num, den);
            case AVG_DAYS -> avgCycle(samples);
            case P90_DAYS -> p90(samples);
            case AVG_HOURS -> hours(avgCycle(samples));
            case P90_HOURS -> hours(p90(samples));
            default -> null;
        };
        return new AnalyticsViews.Metric(spec.key(), spec.label(), spec.unit(), spec.digits(),
                value, null, null, spec.caliber(), null);
    }

    /** Mean dwell in days — the product's one mean: {@link FlowMetricFormulas#avgDwell}. */
    public static Double avgCycle(List<Long> seconds) {
        return FlowMetricFormulas.avgDwell(seconds);
    }

    /** P90 dwell in days, nearest-rank — the product's one percentile. */
    public static Double p90(List<Long> seconds) {
        return FlowMetricFormulas.p90Dwell(seconds);
    }

    /**
     * A ratio as a percentage (0..100), one decimal.
     *
     * <p>The division itself is {@link MetricFormulas#passRate}; the ×100 and the rounding are the
     * only local steps, so a ratio shown as {@code 62.5%} here matches {@code 0.63} on the quality
     * page after the same two-decimal ratio rounding.</p>
     */
    public static Double completionRate(long done, long total) {
        if (total <= 0) {
            return null;
        }
        return round(MetricFormulas.passRate(done, total).doubleValue() * 100d, 1);
    }

    /** Defects per requirement, two decimals; {@code null} when there is no requirement. */
    public static Double defectDensity(long defects, long requirements) {
        if (requirements <= 0) {
            return null;
        }
        return round(MetricFormulas.defectDensity(defects, requirements).doubleValue(), 2);
    }

    /**
     * {@code (actual - estimate) / estimate} as a percentage.
     *
     * <p>Rows without an estimate do not contribute to either side, so "3 of 40 requirements have
     * no estimate" does not silently drag the deviation toward zero.</p>
     */
    public static Double deviation(Number actual, Number estimate) {
        if (estimate == null || estimate.doubleValue() <= 0) {
            return null;
        }
        double act = actual == null ? 0d : actual.doubleValue();
        return round((act - estimate.doubleValue()) / estimate.doubleValue() * 100d, 1);
    }

    /** Period-over-period change in percent; {@code null} with no base period (do not draw 0). */
    public static Double change(Double current, Double previous) {
        if (current == null || previous == null || previous == 0d) {
            return null;
        }
        return round((current - previous) / previous * 100d, 1);
    }

    /** Days → hours, one decimal, for the approval dataset. */
    public static Double hours(Double days) {
        if (days == null) {
            return null;
        }
        return round(days * 24d, 1);
    }

    /** Seconds → hours, one decimal. */
    public static Double hoursFromSeconds(Long seconds) {
        if (seconds == null) {
            return null;
        }
        return round(seconds / SECONDS_PER_HOUR, 1);
    }

    /** Rounding helper: half-up at the display precision, so the server and the UI agree. */
    public static Double round(double value, int digits) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).doubleValue();
    }

    private static Map<String, AnalyticsViews.MetricSpec> build() {
        Map<String, AnalyticsViews.MetricSpec> map = new LinkedHashMap<>();

        count(map, "req.total", "需求总量", "个", "筛选范围内该 Scope 可见的全部需求数，不受时间窗限制");
        count(map, "req.newCount", "新增需求", "个", "创建时间落在选中时间窗内的需求数");
        rate(map, "req.inPoolRate", "入池率", "%", "状态为 POOLED 的需求数 ÷ 窗口内需求数");
        rate(map, "req.completionRate", "完成率", "%",
                "完成态需求数 ÷ 窗口内需求数；完成态取 RequirementStates.completeStatuses()");
        rate(map, "req.splitRate", "拆分率", "%", "存在子需求的需求数 ÷ 窗口内需求数");
        count(map, "req.overdueCount", "逾期需求", "个",
                "计划完成日早于今天且未完成的需求数");
        count(map, "req.pendingTest", "待测试", "个", "状态为 PENDING_TEST 的需求数");
        count(map, "req.mineOpen", "我的待处理", "个", "负责人是当前用户且未完成的需求数");
        count(map, "req.rejected", "被驳回需求", "个",
                "最近一次 REQ_SUBMIT/REQ_REVIEW 审批实例为 REJECTED 且需求未终态（BUS 流的驳回是回到 SUBMITTED，没有 REJECTED 状态）");
        avgDays(map, "req.avgDwell", "平均停留", "天",
                "从创建到关闭（未关闭则到今天）的平均停留天数，nearest-rank 同源公式");
        avgDays(map, "cycle.avg", "平均交付周期", "天", "从创建到关闭的平均天数，仅统计已关闭需求");
        p90Days(map, "cycle.p90", "P90 交付周期", "天",
                "已关闭需求交付周期的 P90（nearest-rank：ceil(0.9×n)，与流程页同源）");
        sum(map, "effort.estimate", "预估人天", "人天", "estimate_days 求和，未填按 0 计");
        sum(map, "effort.actual", "实际人天", "人天", "actual_days 求和，未填按 0 计");
        dev(map, "effort.deviation", "人天偏差", "%",
                "(实际人天 − 预估人天) ÷ 预估人天；无预估的需求不计入两侧");
        count(map, "vendor.accepted", "完成需求", "个", "窗口内完成的（完成态、关闭时间在窗口内）需求数");
        rate(map, "vendor.onTimeRate", "准时率", "%",
                "关闭日 ≤ 期望交付日的需求数 ÷ 有期望交付日的需求数；期望日为空的行不计入分母");
        total(map, "delivery.onTime", "按期完成数", "个",
                "关闭日 ≤ 期望交付日的需求数");
        rate(map, "quality.passRate", "测试通过率", "%",
                "PASS 的执行数 ÷ 有结论（PASS/FAIL/BLOCKED）的执行数");
        density(map, "quality.defectDensity", "缺陷密度", "个/需求",
                "窗口内新增缺陷数 ÷ 窗口内需求数");
        rate(map, "quality.releaseSuccess", "发布成功率", "%",
                "发布日 ≤ 计划上线日的发布数 ÷ 有计划上线日的发布数");
        count(map, "quality.defects", "缺陷数", "个", "窗口内新增缺陷数");
        count(map, "approval.total", "审批单数", "个",
                "需求评审/评审入池实例的审批任务数（部署审批不在本口径内）");
        avgHours(map, "approval.avgHours", "平均耗时", "小时",
                "审批任务从创建到处理（未处理则到现在）的平均耗时");
        p90Hours(map, "approval.p90Hours", "P90 耗时", "小时", "审批任务处理耗时的 P90，nearest-rank");
        rate(map, "approval.passRate", "审批通过率", "%", "APPROVED 任务数 ÷ 已决策（APPROVED/REJECTED）任务数");
        count(map, "org.memberCount", "参与成员", "人",
                "筛选范围内出现过需求负责人的去重数（sys_user 不在 ScopeSql 登记表内，故按需求参与口径统计）");
        count(map, "org.activeReq", "进行中需求", "个", "窗口内创建且未完成的需求数");
        count(map, "workflow.enabled", "启用流程", "个",
                "窗口内发起过需求评审实例的流程定义去重数");

        return Map.copyOf(map);
    }

    private static void count(Map<String, AnalyticsViews.MetricSpec> map, String key, String label,
                              String unit, String caliber) {
        map.put(key, new AnalyticsViews.MetricSpec(key, label, unit, 0, caliber, COUNT));
    }

    private static void total(Map<String, AnalyticsViews.MetricSpec> map, String key, String label,
                              String unit, String caliber) {
        map.put(key, new AnalyticsViews.MetricSpec(key, label, unit, 0, caliber, SUM));
    }

    private static void sum(Map<String, AnalyticsViews.MetricSpec> map, String key, String label,
                            String unit, String caliber) {
        map.put(key, new AnalyticsViews.MetricSpec(key, label, unit, 1, caliber, SUM));
    }

    private static void rate(Map<String, AnalyticsViews.MetricSpec> map, String key, String label,
                             String unit, String caliber) {
        map.put(key, new AnalyticsViews.MetricSpec(key, label, unit, 1, caliber, RATE));
    }

    private static void density(Map<String, AnalyticsViews.MetricSpec> map, String key, String label,
                                String unit, String caliber) {
        map.put(key, new AnalyticsViews.MetricSpec(key, label, unit, 2, caliber, DENSITY));
    }

    private static void dev(Map<String, AnalyticsViews.MetricSpec> map, String key, String label,
                            String unit, String caliber) {
        map.put(key, new AnalyticsViews.MetricSpec(key, label, unit, 1, caliber, DEVIATION));
    }

    private static void avgDays(Map<String, AnalyticsViews.MetricSpec> map, String key, String label,
                                String unit, String caliber) {
        map.put(key, new AnalyticsViews.MetricSpec(key, label, unit, 1, caliber, AVG_DAYS));
    }

    private static void p90Days(Map<String, AnalyticsViews.MetricSpec> map, String key, String label,
                                String unit, String caliber) {
        map.put(key, new AnalyticsViews.MetricSpec(key, label, unit, 1, caliber, P90_DAYS));
    }

    private static void avgHours(Map<String, AnalyticsViews.MetricSpec> map, String key, String label,
                                 String unit, String caliber) {
        map.put(key, new AnalyticsViews.MetricSpec(key, label, unit, 1, caliber, AVG_HOURS));
    }

    private static void p90Hours(Map<String, AnalyticsViews.MetricSpec> map, String key, String label,
                                 String unit, String caliber) {
        map.put(key, new AnalyticsViews.MetricSpec(key, label, unit, 1, caliber, P90_HOURS));
    }
}
