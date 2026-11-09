package com.example.deliver.quality.aggregate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Evaluates the structured {@code exit_criteria_json} against an aggregate.
 *
 * <p>The same JSON is read by the plan detail page, the plan completion action and the report's
 * "准出" section, so the three can never use different thresholds (AC-3 / AC-5). A rate stored as
 * a ratio ({@code 0.95}) is compared against a percentage threshold ({@code 95}) by scaling the
 * actual value — the wire keeps the product-wide ratio convention.</p>
 */
@Component
public class ExitCriteriaEvaluator {

    public static final String DEFAULT_EXIT_CRITERIA = """
            {"items":[{"key":"passRate","op":">=","value":95},\
            {"key":"openP0P1","op":"==","value":0},\
            {"key":"roundClosed","op":"==","value":1}]}""";

    public static final String DEFAULT_ENTRY_CRITERIA = """
            {"items":[{"key":"strategyActive","op":"==","value":1}]}""";

    private static final Map<String, String> LABELS = Map.of(
            "passRate", "通过率",
            "coverage", "覆盖率",
            "openP0P1", "未关闭 P0/P1 缺陷数",
            "roundClosed", "轮次已关闭",
            "executed", "已执行用例数",
            "total", "计划用例数",
            "notRun", "未执行用例数",
            "failed", "失败用例数",
            "blocked", "阻塞用例数",
            "strategyActive", "策略已生效");

    private static final Map<String, String> FIX_HINTS = Map.of(
            "passRate", "修复失败用例后重跑；确需放宽阈值请走计划变更（reason + revision）",
            "coverage", "执行剩余用例后再提交完成",
            "openP0P1", "关闭 P0/P1 缺陷后再提交完成",
            "roundClosed", "先关闭进行中的轮次",
            "notRun", "执行剩余用例后再提交完成",
            "failed", "修复失败用例后重跑",
            "blocked", "解除阻塞后重新执行",
            "strategyActive", "先启用一条测试策略版本");

    private final ObjectMapper objectMapper;

    public ExitCriteriaEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Evaluate with only the aggregate's numbers; open-defect/round keys resolve to 0. */
    public List<UnmetCriterion> evaluate(String exitCriteriaJson, QualityAggregate aggregate) {
        return evaluate(exitCriteriaJson, aggregate, Map.of());
    }

    /**
     * @param extra values not carried by the aggregate (openP0P1, roundClosed, strategyActive…)
     */
    public List<UnmetCriterion> evaluate(String exitCriteriaJson, QualityAggregate aggregate,
                                         Map<String, BigDecimal> extra) {
        Map<String, BigDecimal> values = values(aggregate, extra);
        List<UnmetCriterion> unmet = new ArrayList<>();
        for (Criterion criterion : parse(exitCriteriaJson)) {
            BigDecimal actual = values.get(criterion.key());
            if (actual == null) {
                // A criterion nobody can evaluate must fail closed, not pass silently.
                unmet.add(new UnmetCriterion(criterion.key(), label(criterion.key()),
                        "—", criterion.op() + " " + plain(criterion.value()),
                        "该准出项暂不可计算，请检查数据或调整准出条件"));
                continue;
            }
            BigDecimal expected = criterion.value();
            BigDecimal comparable = actual;
            if (isRate(criterion.key()) && expected.abs().compareTo(BigDecimal.ONE) > 0) {
                comparable = actual.multiply(BigDecimal.valueOf(100));
            }
            if (!holds(comparable, criterion.op(), expected)) {
                unmet.add(new UnmetCriterion(criterion.key(), label(criterion.key()),
                        plain(comparable), criterion.op() + " " + plain(expected),
                        FIX_HINTS.getOrDefault(criterion.key(), "满足准出条件后再提交完成")));
            }
        }
        return unmet;
    }

    /** Parsed items, for the detail page (never used to decide anything). */
    public List<Criterion> parse(String json) {
        String payload = json == null || json.isBlank() ? DEFAULT_EXIT_CRITERIA : json;
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode items = root.path("items");
            List<Criterion> criteria = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String key = item.path("key").asText(null);
                    if (key == null || key.isBlank()) {
                        continue;
                    }
                    String op = item.path("op").asText(">=");
                    BigDecimal value = item.path("value").isNumber()
                            ? item.path("value").decimalValue()
                            : new BigDecimal(item.path("value").asText("0"));
                    criteria.add(new Criterion(key.trim(), op.trim(), value));
                }
            }
            return criteria.isEmpty() ? parse(DEFAULT_EXIT_CRITERIA) : criteria;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("exit_criteria_json 不是合法 JSON: " + ex.getMessage(), ex);
        }
    }

    private static Map<String, BigDecimal> values(QualityAggregate aggregate,
                                                  Map<String, BigDecimal> extra) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("passRate", aggregate.passRate());
        values.put("coverage", aggregate.coverage());
        values.put("total", BigDecimal.valueOf(aggregate.totalPlanned()));
        values.put("executed", BigDecimal.valueOf(aggregate.executed()));
        values.put("passed", BigDecimal.valueOf(aggregate.passed()));
        values.put("failed", BigDecimal.valueOf(aggregate.failed()));
        values.put("blocked", BigDecimal.valueOf(aggregate.blocked()));
        values.put("notRun", BigDecimal.valueOf(aggregate.notRun()));
        if (extra != null) {
            values.putAll(extra);
        }
        return values;
    }

    private static boolean holds(BigDecimal actual, String op, BigDecimal expected) {
        int comparison = actual.compareTo(expected);
        return switch (op) {
            case ">=", "=>" -> comparison >= 0;
            case ">" -> comparison > 0;
            case "<=", "=<" -> comparison <= 0;
            case "<" -> comparison < 0;
            case "==", "=" -> comparison == 0;
            case "!=", "<>" -> comparison != 0;
            default -> false;
        };
    }

    private static boolean isRate(String key) {
        return "passRate".equals(key) || "coverage".equals(key);
    }

    private static String label(String key) {
        return LABELS.getOrDefault(key, key);
    }

    private static String plain(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0, RoundingMode.UNNECESSARY);
        }
        return stripped.toPlainString();
    }

    /** One parsed criterion. */
    public record Criterion(String key, String op, BigDecimal value) {
    }

    /** One unmet criterion; the 409 body carries this list verbatim. */
    public record UnmetCriterion(String key, String label, String actual, String expected,
                                 String fixHint) {
    }
}
