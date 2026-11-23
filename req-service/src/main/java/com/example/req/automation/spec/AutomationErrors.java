package com.example.req.automation.spec;

import com.example.platform.common.error.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自动化错误码工厂：所有 400/404/409 只在这里拼字符串。
 *
 * <p>错误码是 QA 的契约（automation-verify S2 按码断言），controller 里手拼字符串必然漂移；
 * 因此每个码对应一个方法，字段名与 {@code detail.field} 一并固定，前端据此把常驻条挂到具体输入框。</p>
 */
public final class AutomationErrors {

    /** 条件 / 动作 JSON 不符合封闭 schema。 */
    public static final String SCHEMA_INVALID = "AUTOMATION_SCHEMA_INVALID";
    /** 未知触发类型。 */
    public static final String INVALID_TRIGGER = "INVALID_TRIGGER";
    /** 作用域类型非法，或 scopeId 不存在 / 不可见。 */
    public static final String INVALID_SCOPE = "INVALID_SCOPE";
    /** 动作不在白名单内（含越权动作）。 */
    public static final String ACTION_NOT_ALLOWED = "ACTION_NOT_ALLOWED";
    public static final String TOO_MANY_ACTIONS = "TOO_MANY_ACTIONS";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String NAME_TOO_LONG = "NAME_TOO_LONG";
    /** 条件把作用域放宽了（规则定义约束，不是运行期求值）。 */
    public static final String CONDITION_WIDENS_SCOPE = "CONDITION_WIDENS_SCOPE";
    /** 同一租户规则名重复。 */
    public static final String NAME_TAKEN = "AUTOMATION_RULE_NAME_TAKEN";
    /** 乐观锁：提交的 version 落后于库内当前值。 */
    public static final String VERSION_CONFLICT = "AUTOMATION_RULE_VERSION_CONFLICT";
    public static final String RULE_NOT_FOUND = "AUTOMATION_RULE_NOT_FOUND";
    /** 厂商账号访问自动化（服务端唯一判定点）。 */
    public static final String INTERNAL_ONLY = "INTERNAL_ONLY";

    /** 规则名上限（字符数，trim 后）。 */
    public static final int MAX_NAME_LENGTH = 100;
    /** 条件 + 动作 JSON 合计上限（字节，UTF-8）。 */
    public static final int MAX_PAYLOAD_BYTES = 8 * 1024;
    /** 单条规则的动作用量上限。 */
    public static final int MAX_ACTIONS = 10;

    private AutomationErrors() {
    }

    public static ApiException schema(String field, String message) {
        return new ApiException(400, SCHEMA_INVALID, message, fieldDetail(field));
    }

    public static ApiException invalidTrigger(String triggerType) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", "triggerType");
        detail.put("value", triggerType == null ? "" : triggerType);
        detail.put("allowed", AutomationTriggerType.codes());
        return new ApiException(400, INVALID_TRIGGER, "未知的触发类型：" + triggerType, detail);
    }

    public static ApiException invalidScope(String scopeType, Long scopeId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", "scopeId");
        detail.put("scopeType", scopeType == null ? "" : scopeType);
        detail.put("scopeId", scopeId == null ? "" : scopeId);
        return new ApiException(400, INVALID_SCOPE, "作用域不存在或不可见：" + scopeType + "/" + scopeId, detail);
    }

    public static ApiException actionNotAllowed(String type) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", "actions");
        detail.put("value", type == null ? "" : type);
        detail.put("allowed", AutomationActionType.codes());
        String reason = AutomationActionType.forbiddenReason(type);
        String message = reason != null
                ? reason
                : "动作不在白名单内：" + type + "（只允许 " + String.join("/", AutomationActionType.codes()) + "）";
        return new ApiException(400, ACTION_NOT_ALLOWED, message, detail);
    }

    /** 空动作是「载荷形状不对」，不是「动作越权」，所以走 schema 码（PRD 3.8 同一批 400）。 */
    public static ApiException emptyActions() {
        return new ApiException(400, SCHEMA_INVALID, "至少需要一个动作",
                Map.of("field", "actions"));
    }

    public static ApiException tooManyActions(int count) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", "actions");
        detail.put("count", count);
        detail.put("max", MAX_ACTIONS);
        return new ApiException(400, TOO_MANY_ACTIONS,
                "一条规则最多 " + MAX_ACTIONS + " 个动作，当前 " + count + " 个", detail);
    }

    public static ApiException payloadTooLarge(int bytes) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", "condition");
        detail.put("bytes", bytes);
        detail.put("max", MAX_PAYLOAD_BYTES);
        return new ApiException(400, PAYLOAD_TOO_LARGE,
                "条件与动作 JSON 合计不能超过 " + (MAX_PAYLOAD_BYTES / 1024) + "KB，当前 " + bytes + " 字节", detail);
    }

    public static ApiException nameTooLong(int length) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", "name");
        detail.put("length", length);
        detail.put("max", MAX_NAME_LENGTH);
        return new ApiException(400, NAME_TOO_LONG,
                "规则名不能超过 " + MAX_NAME_LENGTH + " 个字符，当前 " + length + " 个", detail);
    }

    public static ApiException nameRequired() {
        return schema("name", "规则名不能为空");
    }

    public static ApiException conditionWidensScope(String field) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", field);
        return new ApiException(400, CONDITION_WIDENS_SCOPE,
                "条件不能比规则作用域更宽（" + field + "）", detail);
    }

    public static ApiException nameTaken(String name) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", "name");
        detail.put("name", name == null ? "" : name);
        return new ApiException(409, NAME_TAKEN, "同名规则已存在：" + name, detail);
    }

    public static ApiException versionConflict(int currentVersion) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", "version");
        detail.put("currentVersion", currentVersion);
        return new ApiException(409, VERSION_CONFLICT,
                "规则已被他人修改，当前版本 " + currentVersion + "，请刷新后重试", detail);
    }

    public static ApiException ruleNotFound(long id) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", id);
        return new ApiException(404, RULE_NOT_FOUND, "规则不存在：" + id, detail);
    }

    private static Map<String, Object> fieldDetail(String field) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (field != null && !field.isBlank()) {
            detail.put("field", field);
        }
        return detail;
    }
}
