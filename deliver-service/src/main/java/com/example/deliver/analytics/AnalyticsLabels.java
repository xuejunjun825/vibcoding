package com.example.deliver.analytics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分析域唯一 <em>code → 中文</em> 注册表（r14 AC-1 / AC-10）。
 *
 * <p>为什么是代码常量表而不是读 {@code sys_dict}：表头在导出线程里也要用，读库会多一次查询和
 * 一次租户上下文依赖；而这里要的是全站固定的枚举文案，不随租户变化。{@code ROWS} 是字面二维表，
 * 门禁脚本 {@code scripts/tests/analytics/i18n-labels-consistency.mjs} 直接正则解析它，与
 * {@code web/src/stores/dict.ts}、{@code sys_dict} 种子逐字符串比对（R1–R6）。</p>
 *
 * <p>本类<strong>不</strong>静态引用 {@link AnalyticsDatasets}：数据集常量在那边也引用本类时
 * 会形成静态初始化环。数据集 code 用字面量（{@code requirement}/{@code delivery}/{@code approval}/
 * {@code quality}），与 {@code AnalyticsDatasets} 的常量值一致，由一致性门禁兜住漂移。</p>
 */
public final class AnalyticsLabels {

    /** 未登记且形如枚举码时的上屏文案（AC-10）：不破英文枚举正则，也不假装知道它是什么。 */
    public static final String UNKNOWN = "未知状态";

    /**
     * 注册表：{dataset, fieldKey, code, 中文}。
     *
     * <p>顺序即文档顺序（数据集 → 字段 → 业务流序），不按字母排：审阅时能一眼看出状态机走向。</p>
     */
    private static final String[][] ROWS = {
            // —— 需求状态（与 dict.ts REQUIREMENT 逐字符串相等；DELIVERY/QUALITY 的 status 复用此表）——
            {"requirement", "status", "SUBMITTED", "已提报"},
            {"requirement", "status", "APPROVING", "审批中"},
            {"requirement", "status", "PENDING_REVIEW", "待评审"},
            {"requirement", "status", "POOLED", "已入池"},
            {"requirement", "status", "PENDING_ANALYSIS", "待分析"},
            {"requirement", "status", "SCHEDULED", "已排期"},
            {"requirement", "status", "IN_DEV", "开发中"},
            {"requirement", "status", "DELIVERED", "已交付"},
            {"requirement", "status", "ACCEPTED_CLOSED", "验收关闭"},
            {"requirement", "status", "TODO", "待开发"},
            {"requirement", "status", "PENDING_TEST", "待测试"},
            {"requirement", "status", "TEST_PASSED", "测试通过"},
            {"requirement", "status", "MERGED", "已合入"},
            {"requirement", "status", "DONE", "完成"},
            {"requirement", "status", "CLOSED", "已关闭"},
            {"requirement", "status", "CANCELLED", "已取消"},

            // —— 需求类型 / 优先级 ——
            {"requirement", "type", "BUS", "业务需求"},
            {"requirement", "type", "PDR", "产品需求"},
            {"requirement", "type", "RD", "研发需求"},
            {"requirement", "priority", "P0", "紧急"},
            {"requirement", "priority", "P1", "高"},
            {"requirement", "priority", "P2", "中"},
            {"requirement", "priority", "P3", "低"},

            // —— 缺陷等级（QUALITY.severity 是 P0..P3，与需求优先级同码不同义，所以两张表）——
            {"quality", "severity", "P0", "致命"},
            {"quality", "severity", "P1", "严重"},
            {"quality", "severity", "P2", "一般"},
            {"quality", "severity", "P3", "轻微"},

            // —— 审批结论 ——
            {"approval", "result", "PENDING", "待审批"},
            {"approval", "result", "RUNNING", "审批中"},
            {"approval", "result", "APPROVED", "已通过"},
            {"approval", "result", "REJECTED", "已驳回"},
            {"approval", "result", "WITHDRAWN", "已撤回"},
            {"approval", "result", "CANCELLED", "已取消"},
            {"approval", "result", "SKIPPED", "自动跳过"},

            // —— 审批业务类型（SQL 已用 CASE 译成中文，这里仍登记，防回退成裸码）——
            {"approval", "bizType", "REQ_SUBMIT", "评审入池"},
            {"approval", "bizType", "REQ_REVIEW", "需求评审"},
    };

    private static final Map<String, String> INDEX = index();
    private static final String DELIVERY = "delivery";
    private static final String QUALITY = "quality";
    private static final String REQUIREMENT = "requirement";
    private static final String STATUS = "status";
    /** 未登记告警去重：同一个 code 反复出图时不刷屏（上限 200 条，剩下的静默）。 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final int WARN_LIMIT = 200;
    private static final java.util.regex.Pattern ENUM_CODE =
            java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]{1,}$");
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(AnalyticsLabels.class);

    private AnalyticsLabels() {
    }

    private static Map<String, String> index() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String[] row : ROWS) {
            map.put(key(row[0], row[1], row[2]), row[3]);
        }
        return Map.copyOf(map);
    }

    private static String key(String dataset, String fieldKey, String code) {
        return dataset + '\u0000' + fieldKey + '\u0000' + code;
    }

    /** 该 (dataset, field, code) 是否有中文登记。 */
    public static boolean isRegistered(String dataset, String fieldKey, String code) {
        return label(dataset, fieldKey, code) != null;
    }

    /**
     * 查中文；未登记返回 {@code null}（调用方据此决定兜底）。
     *
     * <p>{@code status} 字段回退到需求状态表：交付/质量的 {@code status} 都是 {@code r.status}，
     * 复用同一张 16 行表，不复制三份。</p>
     */
    public static String label(String dataset, String fieldKey, String code) {
        if (dataset == null || fieldKey == null || code == null || code.isBlank()) {
            return null;
        }
        String direct = INDEX.get(key(dataset, fieldKey, code));
        if (direct != null) {
            return direct;
        }
        if (STATUS.equals(fieldKey)) {
            return INDEX.get(key(REQUIREMENT, STATUS, code));
        }
        return null;
    }

    /**
     * 上屏文案，<strong>永不返回裸枚举码</strong>。
     *
     * <p>规则顺序即优先级：空白 → {@code ""}（保持 r13 的空值行为，不新增占位、不改列数）；
     * 命中 → 中文；形如枚举码 → {@link #UNKNOWN} 并记一次去重 WARN；其余（中文、{@code 2026-11}、
     * {@code 第 45 周}、{@code 未关联计划}、节点名…）原样透传。</p>
     */
    public static String display(String dataset, String fieldKey, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String hit = label(dataset, fieldKey, value);
        if (hit != null) {
            return hit;
        }
        if (ENUM_CODE.matcher(value).matches()) {
            warnOnce(dataset, fieldKey, value);
            return UNKNOWN;
        }
        return value;
    }

    /**
     * 多维多值拼接：与 {@code AnalyticsQueryService} 原来的 {@code " / "} 拼接逐条等价
     * （只有目标缓冲非空时才前置分隔符），逐段 {@link #display}。
     *
     * @param from 起始下标（行维传 0，列维传 {@code def.rows().size()}）
     * @param to   结束下标（不含）
     */
    public static String joinDisplay(String dataset, List<String> dims, int from, int to, String[] values) {
        if (dims == null || values == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int start = Math.max(from, 0);
        int end = Math.min(Math.min(to, dims.size()), values.length);
        for (int i = start; i < end; i++) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(display(dataset, dims.get(i), values[i]));
        }
        return sb.toString();
    }

    private static void warnOnce(String dataset, String fieldKey, String code) {
        String token = key(dataset, fieldKey, code);
        if (WARNED.size() >= WARN_LIMIT || !WARNED.add(token)) {
            return;
        }
        LOG.warn("未登记的分析域取值：dataset={} field={} code={} → 显示为「{}」（请在 AnalyticsLabels.ROWS 登记）",
                dataset, fieldKey, code, UNKNOWN);
    }
}
