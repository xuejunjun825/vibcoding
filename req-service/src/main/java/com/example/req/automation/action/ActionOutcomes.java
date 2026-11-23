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

    /**
     * 失败原因码（r39 Q-3）。
     *
     * <p>执行日志是用户唯一能看到"为什么没做成"的地方：以前两处 catch 都把异常压成裸
     * {@code FAILED}，真实原因只留在应用日志里，界面上四个动作失败长得一模一样、也没法排查。
     * 这里把异常归一成稳定的原因码（码是人话映射的键，不是异常的 toString——异常文本可能带
     * 内部细节），未知异常保持 {@code FAILED}，所以调用方永远拿得到可存库的短串。</p>
     *
     * <p>码只增不改：前端 {@code AutomationRunVerdict.SKIP_LABELS} 未登记的码原样渲染，不会变空白。</p>
     */
    public static String failureCode(Throwable error) {
        if (error == null) {
            return FAILED;
        }
        if (error instanceof com.example.platform.common.error.ApiException api
                && api.code() != null && !api.code().isBlank()) {
            return api.code();
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (message.contains("secret is required") || message.contains("secret is blank")) {
            return "SECRET_MISSING";
        }
        if (lower.contains("ssrf") || (lower.contains("not allowed") && lower.contains("host"))) {
            return "SSRF_BLOCKED";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "TIMEOUT";
        }
        if (lower.contains("unknownhost") || lower.contains("unknown host")
                || lower.contains("connection refused")) {
            return "UNREACHABLE";
        }
        return FAILED;
    }
}
