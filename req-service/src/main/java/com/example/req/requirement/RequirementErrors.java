package com.example.req.requirement;

import com.example.platform.common.error.ApiException;
import com.example.platform.contract.GateBlocker;
import com.example.platform.contract.GateDecision;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
    /** 工时评估前置门（r26）。 */
    public static final String ESTIMATE_NOT_APPROVED = "ESTIMATE_NOT_APPROVED";
    public static final String ESTIMATE_ALREADY_APPROVED = "ESTIMATE_ALREADY_APPROVED";
    /** 需求变更单（r40）：字段白名单/收口/审批/应用五类错误码。 */
    public static final String CHANGE_FIELD_NOT_ALLOWED = "CHANGE_FIELD_NOT_ALLOWED";
    public static final String CHANGE_REQUIRED = "CHANGE_REQUIRED";
    public static final String CHANGE_NOT_APPROVED = "CHANGE_NOT_APPROVED";
    public static final String CHANGE_ALREADY_APPLIED = "CHANGE_ALREADY_APPLIED";
    public static final String CHANGE_CONFLICT = "CHANGE_CONFLICT";
    /**
     * 字段在当前类型/状态下不可改（r43 可编辑性矩阵）：与 {@code CHANGE_REQUIRED} 同为 409，
     * 但语义是「不可能改」而非「走变更单」——前端据此不给「发起变更」入口。
     */
    public static final String FIELD_LOCKED = "FIELD_LOCKED";

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

    /**
     * 工时评估门（r26）：detail 恰好三键 {@code requirementId / status / required}，status 取六态之一
     * （缺省按 NONE）。{@code ApiException} 会丢弃 null 值并重建 map，故键集合是契约、顺序不是。
     */
    public static ApiException estimateNotApproved(long requirementId, String status) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requirementId", requirementId);
        detail.put("status", status == null ? "NONE" : status);
        detail.put("required", WbsEstimateViews.EstimateView.REQUIRED);
        return new ApiException(409, ESTIMATE_NOT_APPROVED,
                "排期/开始开发前必须先通过工时评估", detail);
    }

    /** 已有生效的评估单（人天未变或变化仍在 ±20% 内），不重复建单。 */
    public static ApiException estimateAlreadyApproved(long requirementId, Long instanceId) {
        return new ApiException(409, ESTIMATE_ALREADY_APPROVED,
                "该需求已有生效的工时评估单；若人天确有较大变化（超过 20%），请修改人天后重新提交",
                instanceId == null ? Map.of("requirementId", requirementId)
                        : Map.of("requirementId", requirementId, "instanceId", instanceId));
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, FORBIDDEN, message, Map.of());
    }

    /** 提报时选了调用者无权使用的业务域（403 OUT_OF_SCOPE，detail 带 field 与越权 id）。 */
    public static ApiException domainOutOfScope(List<Long> domainIds) {
        return new ApiException(403, OUT_OF_SCOPE, "无权在这些业务域下提报",
                Map.of("field", "domainIds", "domainIds", domainIds == null ? List.of() : domainIds));
    }

    /**
     * 动作级拒绝（r17）：detail 带 action 与「此刻仍可执行的动作」，前端据此就地刷新按钮，
     * 负向脚本据此断言 403 确实来自动作矩阵而不是别的分支。
     */
    public static ApiException actionForbidden(String action, List<String> allowedActions) {
        return new ApiException(403, FORBIDDEN, "无权执行该操作",
                Map.of("action", action == null ? "" : action,
                        "allowedActions", allowedActions == null ? List.of() : allowedActions));
    }

    // ------------------------------------------------------------ 需求变更单（r40）

    /**
     * 字段不在该变更类型的白名单里（或变更类型本身未知）。
     * detail 固定三键 {@code changeType / field / allowedFields}，field 为 null 时被 ApiException 丢弃。
     */
    public static ApiException changeFieldNotAllowed(String changeType, String field,
                                                     List<String> allowedFields) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("changeType", changeType);
        detail.put("field", field);
        detail.put("allowedFields", allowedFields == null ? List.of() : allowedFields);
        return new ApiException(400, CHANGE_FIELD_NOT_ALLOWED,
                field == null ? "该变更类型不允许修改字段" : "字段 " + field + " 不允许通过该变更类型修改",
                detail);
    }

    /**
     * 变更类型缺失或未知（r44）：在既有三键之后追加 {@code allowedTypes}（四种合法值），
     * 前端/QA 据此直接给出可选类型，而不是只看到「字段不允许」。缺类型时 changeType 为
     * null，由 {@code ApiException} 丢弃，故实际形状是两键 + allowedTypes。
     */
    public static ApiException changeFieldNotAllowed(String changeType, String field,
                                                     List<String> allowedFields,
                                                     List<String> allowedTypes) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("changeType", changeType);
        detail.put("field", field);
        detail.put("allowedFields", allowedFields == null ? List.of() : allowedFields);
        detail.put("allowedTypes", allowedTypes == null ? List.of() : allowedTypes);
        return new ApiException(400, CHANGE_FIELD_NOT_ALLOWED,
                field == null ? "该变更类型不允许修改字段" : "字段 " + field + " 不允许通过该变更类型修改",
                detail);
    }

    /** 晚期态直接改受管字段：409，detail 带需求 id、命中的字段与发起变更的端点。 */
    public static ApiException changeRequired(long requirementId, List<String> fields,
                                              String changeEndpoint) {
        return new ApiException(409, CHANGE_REQUIRED, "该字段需通过变更单修改",
                Map.of("requirementId", requirementId,
                        "fields", fields == null ? List.of() : fields,
                        "changeEndpoint", changeEndpoint == null ? "" : changeEndpoint));
    }

    /**
     * 字段被可编辑性矩阵判为 {@code LOCKED}：409，detail 与 {@code CHANGE_REQUIRED} 同源
     * （{@code requirementId/fields}），另带首个命中字段 {@code field} 与其原因 {@code reasonCode}，
     * 前端才能按同一套解析给出「不可改 + 原因」，而不是泛化成「参数错误」。
     */
    public static ApiException fieldLocked(long requirementId, String field, String reasonCode,
                                           List<String> fields) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requirementId", requirementId);
        detail.put("field", field == null ? "" : field);
        detail.put("reasonCode", reasonCode == null ? "" : reasonCode);
        detail.put("fields", fields == null ? List.of() : fields);
        return new ApiException(409, FIELD_LOCKED, "该字段在当前状态不可修改", detail);
    }

    /** 审批未通过（或仍在审批中）时应用变更单。 */
    public static ApiException changeNotApproved(String status) {
        return new ApiException(409, CHANGE_NOT_APPROVED, "变更单未通过审批，不能应用",
                Map.of("status", status == null ? "PENDING" : status));
    }

    /** 同一变更单第二次应用：409 且带上首次应用时间，前端据此提示「已生效」而不是「失败」。 */
    public static ApiException changeAlreadyApplied(LocalDateTime appliedAt) {
        return new ApiException(409, CHANGE_ALREADY_APPLIED, "变更单已应用，不能重复应用",
                appliedAt == null ? Map.of() : Map.of("appliedAt", appliedAt.toString()));
    }

    /** apply 时 before 与当前需求值不一致（期间被别人改过）：409，逐字段指出差异。 */
    public static ApiException changeConflict(String field, Object expected, Object actual) {
        return new ApiException(409, CHANGE_CONFLICT, "变更单字段与当前值不一致，请重新发起",
                Map.of("field", field == null ? "" : field,
                        "expected", expected == null ? "" : expected,
                        "actual", actual == null ? "" : actual));
    }
}
