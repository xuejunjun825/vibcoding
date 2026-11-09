package com.example.req.integration.sonar;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sonar 五度量的键、阈值与三态判定（PRD AC-6.2 / AC-6.3，DESIGN「Sonar 卡」）。
 *
 * <p>阈值是<b>本平台</b>的展示口径，Sonar 服务端的质量门结论才是准入依据（AC-6.3
 * "快照 OK → PASS"）。两者分开的理由：服务端可能用更严的阈值给出 ERROR，此时卡片仍要能
 * 逐行解释「哪一项没达标、差多少」，而本平台阈值只在服务端没给条件时兜底。</p>
 *
 * <p>纯函数：没有 IO、没有租户上下文，因此五度量的全部三态组合可以穷举单测。</p>
 */
public final class SonarThresholds {

    public static final String KEY_COVERAGE = "coverage";
    public static final String KEY_BUGS = "bugs";
    public static final String KEY_VULNERABILITIES = "vulnerabilities";
    public static final String KEY_CODE_SMELLS = "code_smells";
    public static final String KEY_DUPLICATED_LINES = "duplicated_lines_density";

    /** 五度量的固定顺序：快照 JSON、hash 规范化、卡片网格都按它来。 */
    public static final List<String> KEYS = List.of(
            KEY_COVERAGE, KEY_BUGS, KEY_VULNERABILITIES, KEY_CODE_SMELLS, KEY_DUPLICATED_LINES);

    public static final String OK = "OK";
    public static final String WARN = "WARN";
    public static final String ERROR = "ERROR";

    /**
     * 一条度量规则：{@code higherIsBetter=true} 表示越大越好（覆盖率），
     * 否则越小越好（缺陷/漏洞/异味/重复率）。
     */
    public record Rule(String key,
                       String label,
                       boolean higherIsBetter,
                       double ok,
                       double warn,
                       String unit) {

        public String thresholdText() {
            return (higherIsBetter ? "≥ " : "≤ ") + plain(ok) + unit;
        }
    }

    private static final List<Rule> RULES = List.of(
            new Rule(KEY_COVERAGE, "覆盖率", true, 80, 75, "%"),
            new Rule(KEY_BUGS, "Bug", false, 0, 2, ""),
            new Rule(KEY_VULNERABILITIES, "漏洞", false, 0, 1, ""),
            new Rule(KEY_CODE_SMELLS, "代码异味", false, 50, 100, ""),
            new Rule(KEY_DUPLICATED_LINES, "重复率", false, 3, 5, "%"));

    private SonarThresholds() {
    }

    public static List<Rule> rules() {
        return RULES;
    }

    public static Rule rule(String key) {
        for (Rule rule : RULES) {
            if (rule.key().equals(key)) {
                return rule;
            }
        }
        return null;
    }

    public static String labelOf(String key) {
        Rule rule = rule(key);
        return rule == null ? key : rule.label();
    }

    public static String unitOf(String key) {
        Rule rule = rule(key);
        return rule == null ? "" : rule.unit();
    }

    /** Sonar 的度量值是字符串（可能带小数），解析失败一律视为"没拿到"。 */
    public static Double parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * hash 用的规范形式：{@code 82.50} 与 {@code 82.5} 必须同 hash，
     * 否则 Sonar 的小数位变化会让"同结论"的快照每次都被当成新事实增多一条。
     */
    public static String canonical(String raw) {
        Double value = parse(raw);
        if (value == null) {
            return "";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** 单度量三态；值缺失返回 {@code null}（由调用方决定 fail-closed 还是跳过）。 */
    public static String status(String key, Double value) {
        Rule rule = rule(key);
        if (rule == null || value == null) {
            return null;
        }
        if (rule.higherIsBetter()) {
            if (value >= rule.ok()) {
                return OK;
            }
            return value >= rule.warn() ? WARN : ERROR;
        }
        if (value <= rule.ok()) {
            return OK;
        }
        return value <= rule.warn() ? WARN : ERROR;
    }

    public static String statusOf(String key, String raw) {
        return status(key, parse(raw));
    }

    /** 五度量的聚合结论：取最差的一档；全缺失返回 {@code null}。 */
    public static String aggregate(Map<String, String> metrics) {
        String worst = null;
        for (String key : KEYS) {
            String status = statusOf(key, metrics == null ? null : metrics.get(key));
            if (status == null) {
                continue;
            }
            if (worst == null || rank(status) > rank(worst)) {
                worst = status;
            }
        }
        return worst;
    }

    /** 缺失（或不可解析）的度量键；非空即 fail-closed 不落快照。 */
    public static List<String> missing(Map<String, String> metrics) {
        List<String> missing = new ArrayList<>();
        for (String key : KEYS) {
            if (parse(metrics == null ? null : metrics.get(key)) == null) {
                missing.add(key);
            }
        }
        return missing;
    }

    /** 卡片逐行渲染需要的五度量明细（顺序固定，缺失的也给出占位行）。 */    public static List<SonarViews.MetricView> describe(Map<String, String> metrics) {
        List<SonarViews.MetricView> rows = new ArrayList<>(KEYS.size());
        for (String key : KEYS) {
            Rule rule = rule(key);
            String raw = metrics == null ? null : metrics.get(key);
            Double value = parse(raw);
            rows.add(new SonarViews.MetricView(key,
                    rule == null ? key : rule.label(),
                    value == null ? null : canonical(raw) + (rule == null ? "" : rule.unit()),
                    status(key, value),
                    rule == null ? null : rule.thresholdText()));
        }
        return rows;
    }

    /**
     * 卡片的线上形态用 camelCase（TECHNICAL §4.4 的 SonarCard 契约 + 前端
     * {@code SonarMetricKey}），快照里存 Sonar 原生 metricKey（PRD 6.2）。两者间的翻译只在
     * 这里做一次，任何一侧改键名都不会悄悄漂移。
     */
    public static String cardKey(String key) {
        if (key == null) {
            return null;
        }
        return switch (key) {
            case KEY_CODE_SMELLS -> "codeSmells";
            case KEY_DUPLICATED_LINES -> "duplicatedLinesDensity";
            default -> key;
        };
    }

    /** 卡片 metrics：camelCase 键、能解析就下发数值（前端类型是 number），顺序固定。 */
    public static Map<String, Object> cardMetrics(Map<String, String> metrics) {
        Map<String, Object> rows = new LinkedHashMap<>();
        for (String key : KEYS) {
            String raw = metrics == null ? null : metrics.get(key);
            if (raw == null) {
                continue;
            }
            Double value = parse(raw);
            rows.put(cardKey(key), value == null ? raw : (Object) value);
        }
        return rows;
    }

    /** 卡片 thresholds：与 metrics 同键，值是"≥ 80%"这类展示文案（口径只在本类）。 */
    public static Map<String, String> cardThresholds(Map<String, String> metrics) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String key : KEYS) {
            Rule rule = rule(key);
            if (rule != null) {
                rows.put(cardKey(key), rule.thresholdText());
            }
        }
        return rows;
    }

    /**
     * 本平台阈值下的未达标项：服务端 ERROR 但没给 conditions 时兜底，
     * 保证 AC-6.3「ERROR 时至少列出 1 条未达标阈值」。
     */
    public static List<SonarViews.ConditionView> unmet(Map<String, String> metrics) {
        List<SonarViews.ConditionView> rows = new ArrayList<>();
        for (String key : KEYS) {
            Rule rule = rule(key);
            String raw = metrics == null ? null : metrics.get(key);
            Double value = parse(raw);
            String status = status(key, value);
            if (rule == null || ERROR.equals(status)) {
                rows.add(new SonarViews.ConditionView(key, rule == null ? key : rule.label(),
                        value == null ? null : canonical(raw) + rule.unit(),
                        (rule.higherIsBetter() ? "≥ " : "≤ ") + plain(rule.ok()) + rule.unit(),
                        rule.higherIsBetter() ? ">=" : "<=",
                        ERROR));
            }
        }
        return rows;
    }

    /** 实测值/阈值的短格式：整数不带 .0，小数去掉尾零。 */
    public static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int rank(String status) {
        return switch (status) {
            case ERROR -> 3;
            case WARN -> 2;
            case OK -> 1;
            default -> 0;
        };
    }

    /** 度量键 → 中文标签（供前端字典兜底，不新增第二份状态字符串）。 */
    public static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Rule rule : RULES) {
            labels.put(rule.key(), rule.label());
        }
        return labels;
    }
}
