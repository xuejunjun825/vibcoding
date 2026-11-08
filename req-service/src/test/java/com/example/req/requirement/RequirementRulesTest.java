package com.example.req.requirement;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementRulesTest {

    @Test
    void busMustBeTopLevel() {
        assertThat(RequirementRules.checkParentCombination("BUS", null)).isNull();
        RequirementRules.Violation violation =
                RequirementRules.checkParentCombination("BUS", "PDR");
        assertThat(violation).isNotNull();
        assertThat(violation.status()).isEqualTo(400);
        assertThat(violation.code()).isEqualTo(RequirementRules.INVALID_PARENT);
    }

    @Test
    void pdrNeedsBusParentAndRdNeedsPdrParent() {
        assertThat(RequirementRules.checkParentCombination("PDR", "BUS")).isNull();
        assertThat(RequirementRules.checkParentCombination("RD", "PDR")).isNull();
        assertThat(RequirementRules.checkParentCombination("RD", "BUS")).isNotNull();
        assertThat(RequirementRules.checkParentCombination("PDR", "PDR")).isNotNull();
        assertThat(RequirementRules.checkParentCombination("PDR", null)).isNotNull();
    }

    @Test
    void ancestorCycleIsRejected() {
        Map<Long, Long> parents = new HashMap<>();
        parents.put(1L, null);
        parents.put(2L, 1L);
        parents.put(3L, 2L);
        // 把 1 的父改成 3 → 1 → 3 → 2 → 1 成环
        assertThat(RequirementRules.checkAncestorCycle(1L, 3L, parents::get)).isNotNull();
        // 正常挂载不报
        assertThat(RequirementRules.checkAncestorCycle(4L, 3L, parents::get)).isNull();
    }

    @Test
    void parentDaysAreReadOnly() {
        RequirementRules.Violation bus =
                RequirementRules.checkDays("BUS", new BigDecimal("3.0"), null);
        assertThat(bus).isNotNull();
        assertThat(bus.code()).isEqualTo(RequirementRules.PARENT_DAYS_READONLY);
        RequirementRules.Violation pdr =
                RequirementRules.checkDays("PDR", null, new BigDecimal("1.0"));
        assertThat(pdr).isNotNull();
        assertThat(pdr.code()).isEqualTo(RequirementRules.PARENT_DAYS_READONLY);
    }

    @Test
    void rdDaysMustBeHalfDayGranularity() {
        assertThat(RequirementRules.checkDays("RD", new BigDecimal("0.5"), new BigDecimal("2.5")))
                .isNull();
        RequirementRules.Violation violation =
                RequirementRules.checkDays("RD", new BigDecimal("0.3"), null);
        assertThat(violation).isNotNull();
        assertThat(violation.status()).isEqualTo(400);
        assertThat(violation.code()).isEqualTo(RequirementRules.INVALID_DAY_PRECISION);
    }

    @Test
    void negativeDaysRejected() {
        assertThat(RequirementRules.checkHalfDay(new BigDecimal("-0.5"), "estimateDays")).isNotNull();
    }

    @Test
    void childTypeDerivation() {
        assertThat(RequirementRules.childTypeOf("BUS")).isEqualTo("PDR");
        assertThat(RequirementRules.childTypeOf("PDR")).isEqualTo("RD");
        assertThat(RequirementRules.childTypeOf("RD")).isNull();
    }
}
