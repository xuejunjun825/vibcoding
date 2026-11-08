package com.example.req.requirement;

import com.example.platform.common.error.ApiException;
import com.example.platform.contract.GateBlocker;
import com.example.platform.contract.GateDecision;

import java.util.List;
import java.util.Map;

/**
 * 需求域错误工厂：把稳定错误码集中在一处，Controller/Service 不再各自拼字符串。
 * 错误体结构由 platform-common 的 GlobalExceptionHandler 统一输出。
 */
public final class RequirementErrors {

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String OUT_OF_SCOPE = "OUT_OF_SCOPE";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String STATUS_NOT_WRITABLE = "STATUS_NOT_WRITABLE";
    public static final String INVALID_PARENT = "INVALID_PARENT";
    public static final String PARENT_DAYS_READONLY = "PARENT_DAYS_READONLY";
    public static final String INVALID_DAY_PRECISION = "INVALID_DAY_PRECISION";
    public static final String ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";
    public static final String GATE_BLOCKED = "GATE_BLOCKED";
    public static final String RELEASE_LOCKED = "RELEASE_LOCKED";
    public static final String REASON_REQUIRED = "REASON_REQUIRED";
    public static final String REVIEW_APPROVAL_REQUIRED = "REVIEW_APPROVAL_REQUIRED";
    public static final String APPROVAL_UNAVAILABLE = "APPROVAL_UNAVAILABLE";
    public static final String FORBIDDEN = "FORBIDDEN";

    private RequirementErrors() {
    }

    public static ApiException notFound(String what, Object id) {
        return new ApiException(404, NOT_FOUND, what + "不存在或不可见", Map.of("id", id));
    }

    public static ApiException outOfScope(String what, Object id) {
        return new ApiException(403, OUT_OF_SCOPE, "无权访问该" + what, Map.of("id", id));
    }

    public static ApiException validation(String message) {
        return new ApiException(400, VALIDATION_FAILED, message, Map.of());
    }

    public static ApiException validation(String message, Map<String, Object> detail) {
        return new ApiException(400, VALIDATION_FAILED, message, detail);
    }

    public static ApiException statusNotWritable() {
        return new ApiException(400, STATUS_NOT_WRITABLE,
                "status 只能通过流转动作或审批回调修改", Map.of("field", "status"));
    }

    public static ApiException rule(RequirementRules.Violation violation) {
        return new ApiException(violation.status(), violation.code(), violation.message(), violation.detail());
    }

    public static ApiException illegalTransition(String type, String status, String action,
                                                 List<String> allowedTargets) {
        return new ApiException(409, ILLEGAL_TRANSITION,
                "当前状态不允许该动作：" + status + " --" + action + "-->",
                Map.of("type", type, "from", status, "action", String.valueOf(action),
                        "allowedTargets", allowedTargets));
    }

    public static ApiException gateBlocked(GateDecision decision) {
        List<GateBlocker> blockers = decision == null || decision.blockers() == null
                ? List.of() : decision.blockers();
        return new ApiException(409, GATE_BLOCKED,
                decision == null || decision.reason() == null ? "测试门禁未通过" : decision.reason(),
                Map.of("mode", decision == null ? "STRICT" : decision.mode(),
                        "blockers", blockers));
    }

    public static ApiException releaseLocked(long releaseId) {
        return new ApiException(409, RELEASE_LOCKED,
                "版本已锁定，不能再调整需求", Map.of("releaseId", releaseId));
    }

    public static ApiException reasonRequired() {
        return new ApiException(400, REASON_REQUIRED, "必须填写原因", Map.of("field", "reason"));
    }

    /** AC-10：「评审通过进已入池」必须由 REQ_REVIEW 审批回调驱动，不能走直连动作绕过。 */
    public static ApiException reviewApprovalRequired(long requirementId) {
        return new ApiException(409, REVIEW_APPROVAL_REQUIRED,
                "评审入池必须先发起 REQ_REVIEW 审批并通过；无审批会话时不允许直接 review_pass",
                Map.of("requirementId", requirementId, "action", "review_start"));
    }

    public static ApiException approvalUnavailable() {
        return new ApiException(503, APPROVAL_UNAVAILABLE, "审批引擎不可用，无法发起评审", Map.of());
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, FORBIDDEN, message, Map.of());
    }
}
