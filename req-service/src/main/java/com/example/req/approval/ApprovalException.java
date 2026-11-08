package com.example.req.approval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批域的业务异常，携带 HTTP 状态、稳定错误码与结构化 detail。
 *
 * <p>错误码取自 TECHNICAL §4.2 的稳定表（脚本按 {@code code} 断言），新增语义不得复用别的码：
 * <ul>
 *   <li>400 {@code WORKFLOW_INVALID} / {@code APPROVER_UNRESOLVED} / {@code REASON_REQUIRED} /
 *       {@code VALIDATION_FAILED} / {@code NO_WORKFLOW}</li>
 *   <li>403 {@code FORBIDDEN} / {@code TENANT_MISMATCH}</li>
 *   <li>404 {@code NOT_FOUND}</li>
 *   <li>409 {@code WORKFLOW_PUBLISHED_IMMUTABLE} / {@code TASK_ALREADY_HANDLED} /
 *       {@code LAST_APPROVER} / {@code ILLEGAL_TRANSITION}</li>
 *   <li>429 {@code URGE_TOO_FREQUENT}</li>
 * </ul>
 */
public class ApprovalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String WORKFLOW_INVALID = "WORKFLOW_INVALID";
    public static final String APPROVER_UNRESOLVED = "APPROVER_UNRESOLVED";
    public static final String NO_WORKFLOW = "NO_WORKFLOW";
    public static final String REASON_REQUIRED = "REASON_REQUIRED";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String WORKFLOW_PUBLISHED_IMMUTABLE = "WORKFLOW_PUBLISHED_IMMUTABLE";
    public static final String TASK_ALREADY_HANDLED = "TASK_ALREADY_HANDLED";
    public static final String LAST_APPROVER = "LAST_APPROVER";
    public static final String ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";
    public static final String URGE_TOO_FREQUENT = "URGE_TOO_FREQUENT";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String TENANT_MISMATCH = "TENANT_MISMATCH";

    private final int status;
    private final String code;
    /** 错误详情按请求返回，异常序列化不参与 RPC，因此不要求它的值可序列化。 */
    @SuppressWarnings("serial")
    private final Map<String, Object> detail;

    public ApprovalException(int status, String code, String message, Map<String, Object> detail) {
        super(message);
        this.status = status;
        this.code = code;
        this.detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> detail() {
        return detail;
    }

    public static ApprovalException badRequest(String code, String message) {
        return new ApprovalException(400, code, message, Map.of());
    }

    public static ApprovalException badRequest(String code, String message, String detailKey, Object detailValue) {
        return new ApprovalException(400, code, message, single(detailKey, detailValue));
    }

    public static ApprovalException forbidden(String message) {
        return new ApprovalException(403, FORBIDDEN, message, Map.of());
    }

    public static ApprovalException notFound(String message) {
        return new ApprovalException(404, NOT_FOUND, message, Map.of());
    }

    public static ApprovalException conflict(String code, String message) {
        return new ApprovalException(409, code, message, Map.of());
    }

    public static ApprovalException conflict(String code, String message, String detailKey, Object detailValue) {
        return new ApprovalException(409, code, message, single(detailKey, detailValue));
    }

    public static ApprovalException tooManyRequests(String code, String message) {
        return new ApprovalException(429, code, message, Map.of());
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
