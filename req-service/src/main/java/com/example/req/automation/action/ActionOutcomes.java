package com.example.req.automation.action;

import com.example.req.automation.api.AutomationDtos.ActionOutcome;

/**
 * Outcome vocabulary shared by the four actions and the run log.
 *
 * <p>The record itself lives in {@link com.example.req.automation.api.AutomationDtos} because it is
 * serialised into {@code automation_run.actions_json} and read back by the run API; keeping one
 * type means the log page cannot drift from what the executor wrote. This holder exists only for
 * the status constants, so no action writes a literal by hand.</p>
 */
public final class ActionOutcomes {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    private ActionOutcomes() {
    }

    public static ActionOutcome success(String type, String message, String target) {
        return new ActionOutcome(type, SUCCESS, message, target);
    }

    public static ActionOutcome failed(String type, String message, String target) {
        return new ActionOutcome(type, FAILED, message, target);
    }

    public static ActionOutcome skipped(String type, String reason, String target) {
        return new ActionOutcome(type, SKIPPED, reason, target);
    }
}
