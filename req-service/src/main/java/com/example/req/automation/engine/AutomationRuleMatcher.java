package com.example.req.automation.engine;

import com.example.req.automation.event.AutomationEventMapper;
import com.example.req.automation.event.AutomationEventRow;

/**
 * Rule trigger -> event matching.
 *
 * <p>Deliberately dumb: a rule fires only for the event type it declares, and a SCHEDULE rule only
 * for a schedule event that names a requirement (the per-rule truncation event has no requirement
 * and must never fan out to requirements).</p>
 */
public final class AutomationRuleMatcher {

    private AutomationRuleMatcher() {
    }

    public static boolean matches(String triggerType, AutomationEventRow event) {
        if (triggerType == null || event == null) {
            return false;
        }
        if (!triggerType.equals(event.eventType())) {
            return false;
        }
        if (AutomationEventMapper.EVENT_SCHEDULE.equals(triggerType)) {
            return event.requirementId() != null;
        }
        return true;
    }
}
