package com.example.req.automation.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.platform.common.error.ApiException;
import org.junit.jupiter.api.Test;

/** 触发类型的字面量就是 API 契约（PRD 3.1），改一个字母前端与历史日志都会对不上。 */
class AutomationTriggerTypeTest {

    @Test
    void literalsMatchThePrdContract() {
        assertThat(AutomationTriggerType.codes())
                .containsExactly("REQUIREMENT_CREATED", "STATUS_CHANGE", "FIELD_CHANGE", "SCHEDULE");
        assertThat(AutomationTriggerType.of("STATUS_CHANGE")).isEqualTo(AutomationTriggerType.STATUS_CHANGE);
        assertThat(AutomationTriggerType.of("SCHEDULE").label()).isEqualTo("定时扫描");
        assertThat(AutomationTriggerType.isValid("FIELD_CHANGE")).isTrue();
        assertThat(AutomationTriggerType.isValid("status_change")).isFalse();

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> AutomationTriggerType.of("EVERY_MINUTE"));
        assertThat(ex.code()).isEqualTo(AutomationErrors.INVALID_TRIGGER);
        assertThat(catchThrowableOfType(ApiException.class, () -> AutomationTriggerType.of(null)).code())
                .isEqualTo(AutomationErrors.INVALID_TRIGGER);
    }
}
