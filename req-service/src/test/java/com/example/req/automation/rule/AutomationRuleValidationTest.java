package com.example.req.automation.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.platform.common.error.ApiException;
import com.example.req.automation.spec.AutomationErrors;
import com.example.req.automation.spec.AutomationSpecs.ConditionSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 规则定义层的三条约束：名称、体积、作用域收窄。
 *
 * <p>收窄判定留在这里而不是运行时求值：{@code CONDITION_WIDENS_SCOPE} 说的是
 * 「这条规则的定义比它的作用域更宽」，一旦写进库，任何一次求值都已经太晚。</p>
 */
class AutomationRuleValidationTest {

    private final AutomationRuleValidation validation = new AutomationRuleValidation();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String codeOf(Runnable action) {
        ApiException ex = catchThrowableOfType(ApiException.class, action::run);
        assertThat(ex).as("应当抛出 ApiException").isNotNull();
        return ex.code();
    }

    private static ConditionSpec condition(String json) {
        return ConditionSpec.parse(MAPPER.readTree(json));
    }

    @Test
    void nameIsTrimmedAndBounded() {
        assertThat(validation.checkName("  到期提醒  ")).isEqualTo("到期提醒");
        assertThat(codeOf(() -> validation.checkName("   "))).isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> validation.checkName("x".repeat(101)))).isEqualTo(AutomationErrors.NAME_TOO_LONG);
    }

    @Test
    void payloadIsBoundedByEightKilobytes() {
        String big = "y".repeat(9000);
        assertThat(codeOf(() -> validation.checkSize(
                MAPPER.readTree("{\"fields\":[\"priority\"]}"),
                MAPPER.readTree("[{\"type\":\"ADD_TAG\",\"tag\":\"" + big + "\"}]"))))
                .isEqualTo(AutomationErrors.PAYLOAD_TOO_LARGE);
    }

    @Test
    void triggerAndScopeShapeAreValidated() {
        assertThat(codeOf(() -> validation.checkTrigger("EVERY_MINUTE")))
                .isEqualTo(AutomationErrors.INVALID_TRIGGER);
        assertThat(codeOf(() -> validation.checkScope("GLOBAL", 1L)))
                .isEqualTo(AutomationErrors.INVALID_SCOPE);
        assertThat(codeOf(() -> validation.checkScope("DOMAIN", null)))
                .isEqualTo(AutomationErrors.INVALID_SCOPE);
        assertThat(validation.checkPriority(null)).isEqualTo(AutomationRuleValidation.DEFAULT_PRIORITY);
    }

    @Test
    void domainRuleMayNotWidenToVendorsOrOtherDomains() {
        validation.checkConditionScope("DOMAIN", 5L,
                condition("{\"domainIds\":[5],\"types\":[\"BUS\"]}"));

        assertThat(codeOf(() -> validation.checkConditionScope("DOMAIN", 5L,
                condition("{\"domainIds\":[6]}")))).isEqualTo(AutomationErrors.CONDITION_WIDENS_SCOPE);
        assertThat(codeOf(() -> validation.checkConditionScope("DOMAIN", 5L,
                condition("{\"vendorIds\":[3]}")))).isEqualTo(AutomationErrors.CONDITION_WIDENS_SCOPE);
    }

    @Test
    void vendorRuleMayNotWidenToDomainsOrOtherVendors() {
        validation.checkConditionScope("VENDOR", 3L, condition("{\"vendorIds\":[3]}"));

        assertThat(codeOf(() -> validation.checkConditionScope("VENDOR", 3L,
                condition("{\"vendorIds\":[4]}")))).isEqualTo(AutomationErrors.CONDITION_WIDENS_SCOPE);
        assertThat(codeOf(() -> validation.checkConditionScope("VENDOR", 3L,
                condition("{\"domainIds\":[1]}")))).isEqualTo(AutomationErrors.CONDITION_WIDENS_SCOPE);
    }

    @Test
    void scopeTypeConstantsAreTheOnlyTwo() {
        assertThat(AutomationRuleValidation.SCOPE_TYPES)
                .containsExactlyInAnyOrder(AutomationRuleValidation.SCOPE_DOMAIN,
                        AutomationRuleValidation.SCOPE_VENDOR);
        assertThat(List.of(AutomationRuleValidation.SCOPE_DOMAIN,
                AutomationRuleValidation.SCOPE_VENDOR)).hasSize(2);
    }
}
