package com.example.req.automation.engine;

import com.example.req.automation.event.AutomationEventMapper;
import com.example.req.automation.event.AutomationEventRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for trigger matching (r39 T5, TECHNICAL §8).
 *
 * <p>Small on purpose: this is the only place where an event chooses a set of rules, so the four
 * triggers are pinned literally. The SCHEDULE case carries the subtle rule — a truncation event has
 * no requirement, and must match nothing rather than every requirement of the rule.</p>
 */
class AutomationRuleMatcherTest {

    private static AutomationEventRow event(String type, Long requirementId) {
        return new AutomationEventRow(1L, 1L, "E-1", type, requirementId,
                AutomationEventMapper.ORIGIN_USER, 1, "{}", 0);
    }

    @Test
    @DisplayName("REQUIREMENT_CREATED 只匹配新建事件")
    void matchesRequirementCreated() {
        assertThat(AutomationRuleMatcher.matches(AutomationEventMapper.EVENT_REQUIREMENT_CREATED,
                event(AutomationEventMapper.EVENT_REQUIREMENT_CREATED, 9L))).isTrue();
        assertThat(AutomationRuleMatcher.matches(AutomationEventMapper.EVENT_REQUIREMENT_CREATED,
                event(AutomationEventMapper.EVENT_FIELD_CHANGE, 9L))).isFalse();
    }

    @Test
    @DisplayName("STATUS_CHANGE 与 FIELD_CHANGE 各自独立")
    void matchesStatusAndFieldChange() {
        assertThat(AutomationRuleMatcher.matches(AutomationEventMapper.EVENT_STATUS_CHANGE,
                event(AutomationEventMapper.EVENT_STATUS_CHANGE, 9L))).isTrue();
        assertThat(AutomationRuleMatcher.matches(AutomationEventMapper.EVENT_FIELD_CHANGE,
                event(AutomationEventMapper.EVENT_STATUS_CHANGE, 9L))).isFalse();
        assertThat(AutomationRuleMatcher.matches(AutomationEventMapper.EVENT_FIELD_CHANGE,
                event(AutomationEventMapper.EVENT_FIELD_CHANGE, 9L))).isTrue();
    }

    @Test
    @DisplayName("SCHEDULE 匹配带需求的定时事件")
    void matchesScheduleWithRequirement() {
        assertThat(AutomationRuleMatcher.matches(AutomationEventMapper.EVENT_SCHEDULE,
                event(AutomationEventMapper.EVENT_SCHEDULE, 9L))).isTrue();
    }

    @Test
    @DisplayName("SCHEDULE 不匹配无需求的截断事件")
    void scheduleTruncationMatchesNothing() {
        assertThat(AutomationRuleMatcher.matches(AutomationEventMapper.EVENT_SCHEDULE,
                event(AutomationEventMapper.EVENT_SCHEDULE_TRUNCATED, null))).isFalse();
    }

    @Test
    @DisplayName("SCHEDULE 规则不匹配需求事件，null 输入一律不匹配")
    void scheduleDoesNotMatchRequirementEvents() {
        assertThat(AutomationRuleMatcher.matches(AutomationEventMapper.EVENT_SCHEDULE,
                event(AutomationEventMapper.EVENT_REQUIREMENT_CREATED, 9L))).isFalse();
        assertThat(AutomationRuleMatcher.matches(null, event(
                AutomationEventMapper.EVENT_SCHEDULE, 9L))).isFalse();
        assertThat(AutomationRuleMatcher.matches(AutomationEventMapper.EVENT_SCHEDULE, null))
                .isFalse();
    }
}
