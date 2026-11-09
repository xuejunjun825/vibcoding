package com.example.deliver.quality.aggregate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-3: the structured exit criteria decide whether a plan may be completed. The rate keys are
 * stored as ratios (0.95) but configured as percentages (95) — this test pins that conversion.
 */
class ExitCriteriaEvaluatorTest {

    private final ExitCriteriaEvaluator evaluator = new ExitCriteriaEvaluator(new ObjectMapper());

    private QualityAggregate aggregate(String passRate) {
        return new QualityAggregate(1L, 100, 100, 95, 5, 0, 0,
                new BigDecimal(passRate), new BigDecimal("1.00"), List.of(), Map.of());
    }

    @Test
    void defaultCriteriaPassWhenThePlanIsClean() {
        List<ExitCriteriaEvaluator.UnmetCriterion> unmet = evaluator.evaluate(
                ExitCriteriaEvaluator.DEFAULT_EXIT_CRITERIA, aggregate("0.95"),
                Map.of("openP0P1", BigDecimal.ZERO, "roundClosed", BigDecimal.ONE));
        assertThat(unmet).isEmpty();
    }

    @Test
    void lowPassRateIsReportedWithActualAndThreshold() {
        List<ExitCriteriaEvaluator.UnmetCriterion> unmet = evaluator.evaluate(
                ExitCriteriaEvaluator.DEFAULT_EXIT_CRITERIA, aggregate("0.90"),
                Map.of("openP0P1", BigDecimal.ZERO, "roundClosed", BigDecimal.ONE));
        assertThat(unmet).hasSize(1);
        assertThat(unmet.get(0).key()).isEqualTo("passRate");
        assertThat(unmet.get(0).expected()).isEqualTo(">= 95");
        assertThat(unmet.get(0).actual()).contains("90");
        assertThat(unmet.get(0).fixHint()).isNotBlank();
    }

    @Test
    void openHighSeverityDefectBlocksCompletion() {
        List<ExitCriteriaEvaluator.UnmetCriterion> unmet = evaluator.evaluate(
                ExitCriteriaEvaluator.DEFAULT_EXIT_CRITERIA, aggregate("1.00"),
                Map.of("openP0P1", BigDecimal.ONE, "roundClosed", BigDecimal.ONE));
        assertThat(unmet).extracting(ExitCriteriaEvaluator.UnmetCriterion::key)
                .containsExactly("openP0P1");
    }

    @Test
    void openRoundBlocksCompletion() {
        List<ExitCriteriaEvaluator.UnmetCriterion> unmet = evaluator.evaluate(
                ExitCriteriaEvaluator.DEFAULT_EXIT_CRITERIA, aggregate("1.00"),
                Map.of("openP0P1", BigDecimal.ZERO, "roundClosed", BigDecimal.ZERO));
        assertThat(unmet).extracting(ExitCriteriaEvaluator.UnmetCriterion::key)
                .containsExactly("roundClosed");
    }

    @Test
    void unknownCriterionFailsClosed() {
        String json = "{\"items\":[{\"key\":\"madeUpMetric\",\"op\":\">=\",\"value\":1}]}";
        List<ExitCriteriaEvaluator.UnmetCriterion> unmet = evaluator.evaluate(json, aggregate("1.00"));
        assertThat(unmet).extracting(ExitCriteriaEvaluator.UnmetCriterion::key)
                .containsExactly("madeUpMetric");
    }

    @Test
    void nullCriteriaFallsBackToTheDefaultSet() {
        assertThat(evaluator.parse(null)).hasSize(3);
    }
}
