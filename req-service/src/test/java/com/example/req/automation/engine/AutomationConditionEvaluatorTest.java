package com.example.req.automation.engine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.req.automation.spec.AutomationSpecs.ConditionSpec;
import com.example.req.automation.spec.AutomationSpecs.EstimateDays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for condition evaluation (r39 T5, TECHNICAL §4.2).
 *
 * <p>The evaluator is a pure function precisely so this file can be exhaustive: five comparison
 * operators, the AND across keys and the OR inside an array are all decided here, without a
 * database. The two behaviours that carry the most risk are pinned explicitly — a missing field is
 * a miss with a reason (never a wildcard), and an empty condition matches everything.</p>
 */
class AutomationConditionEvaluatorTest {

    private static ConditionSpec spec(List<String> types, List<String> priorityIn,
                                      List<String> statusIn, List<String> approvalResults,
                                      EstimateDays estimateDays, List<Long> domainIds,
                                      List<Long> vendorIds, List<String> fields) {
        return new ConditionSpec(types, priorityIn, statusIn, approvalResults, estimateDays,
                domainIds, vendorIds, fields);
    }

    private static Map<String, Object> snapshot(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }

    @Test
    @DisplayName("空条件命中一切（不限制 = 命中，而不是不命中）")
    void emptyConditionAlwaysMatches() {
        assertThat(AutomationConditionEvaluator.evaluate(ConditionSpec.EMPTY, Map.of()).matched())
                .isTrue();
        assertThat(AutomationConditionEvaluator.evaluate(null, null).matched()).isTrue();
    }

    @Test
    @DisplayName("键之间是 AND：类型命中而优先级不命中，整体不命中")
    void keysAreCombinedWithAnd() {
        ConditionSpec spec = spec(List.of("BUS"), List.of("P0"), null, null, null, null, null, null);
        assertThat(AutomationConditionEvaluator.evaluate(spec,
                snapshot("type", "BUS", "priority", "P0")).matched()).isTrue();
        assertThat(AutomationConditionEvaluator.evaluate(spec,
                snapshot("type", "BUS", "priority", "P2")).matched()).isFalse();
        assertThat(AutomationConditionEvaluator.evaluate(spec,
                snapshot("type", "RD", "priority", "P0")).matched()).isFalse();
    }

    @Test
    @DisplayName("数组内是 OR：priorityIn 命中任一项即通过")
    void arraysAreOred() {
        ConditionSpec spec = spec(null, List.of("P0", "P1"), null, null, null, null, null, null);
        assertThat(AutomationConditionEvaluator.evaluate(spec, snapshot("priority", "P1")).matched())
                .isTrue();
        assertThat(AutomationConditionEvaluator.evaluate(spec, snapshot("priority", "P3")).matched())
                .isFalse();
    }

    @Test
    @DisplayName("五个比较符逐个覆盖 estimateDays")
    void allComparisonOperators() {
        Map<String, Object> ten = snapshot("estimateDays", new BigDecimal("10"));
        assertThat(AutomationConditionEvaluator.evaluate(
                spec(null, null, null, null, new EstimateDays("lt", new BigDecimal("10")), null, null,
                        null), ten).matched()).isFalse();
        assertThat(AutomationConditionEvaluator.evaluate(
                spec(null, null, null, null, new EstimateDays("lte", new BigDecimal("10")), null, null,
                        null), ten).matched()).isTrue();
        assertThat(AutomationConditionEvaluator.evaluate(
                spec(null, null, null, null, new EstimateDays("gt", new BigDecimal("10")), null, null,
                        null), ten).matched()).isFalse();
        assertThat(AutomationConditionEvaluator.evaluate(
                spec(null, null, null, null, new EstimateDays("gte", new BigDecimal("10")), null, null,
                        null), ten).matched()).isTrue();
        assertThat(AutomationConditionEvaluator.evaluate(
                spec(null, null, null, null, new EstimateDays("eq", new BigDecimal("10")), null, null,
                        null), ten).matched()).isTrue();
    }

    @Test
    @DisplayName("字段缺失不命中，并给出可读原因（不是通配）")
    void missingFieldNeverMatches() {
        ConditionSpec spec = spec(List.of("BUS"), null, null, null, null, null, null, null);
        AutomationConditionEvaluator.Verdict verdict =
                AutomationConditionEvaluator.evaluate(spec, Map.of());
        assertThat(verdict.matched()).isFalse();
        assertThat(verdict.reason()).isEqualTo("types_missing");

        AutomationConditionEvaluator.Verdict estimate = AutomationConditionEvaluator.evaluate(
                spec(null, null, null, null, new EstimateDays("gte", BigDecimal.ONE), null, null, null),
                Map.of());
        assertThat(estimate.matched()).isFalse();
        assertThat(estimate.reason()).isEqualTo("estimateDays_missing");
    }

    @Test
    @DisplayName("statusIn 用状态真值比较，厂商域按 owner/assignee 任一命中")
    void statusAndVendorRules() {
        ConditionSpec spec = spec(null, null, List.of("IN_PROGRESS", "REVIEW"), null, null, null,
                List.of(7L), null);
        assertThat(AutomationConditionEvaluator.evaluate(spec,
                snapshot("status", "REVIEW", "assigneeVendorId", 7L)).matched()).isTrue();
        assertThat(AutomationConditionEvaluator.evaluate(spec,
                snapshot("status", "DONE", "assigneeVendorId", 7L)).matched()).isFalse();
        assertThat(AutomationConditionEvaluator.evaluate(spec,
                snapshot("status", "REVIEW", "assigneeVendorId", 8L)).matched()).isFalse();
    }

    @Test
    @DisplayName("fields 只对字段变更事件有意义：无 field 时不命中")
    void fieldsRequiresFieldName() {
        ConditionSpec spec = spec(null, null, null, null, null, null, null, List.of("estimate_days"));
        assertThat(AutomationConditionEvaluator.evaluate(spec,
                snapshot("field", "estimate_days")).matched()).isTrue();
        assertThat(AutomationConditionEvaluator.evaluate(spec,
                snapshot("field", "priority")).matched()).isFalse();
        assertThat(AutomationConditionEvaluator.evaluate(spec, Map.of()).reason())
                .isEqualTo("fields_missing");
    }
}
