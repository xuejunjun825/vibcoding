package com.example.platform.common.error;

import java.util.Map;

/**
 * A domain error with a stable HTTP status, a machine-readable code and optional detail.
 *
 * <p>Controllers never build error JSON by hand: the code is the contract QA asserts, and one
 * handler renders it. Subclasses (e.g. admission failure) keep the same shape.</p>
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final transient Map<String, Object> detail;

    public ApiException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(int status, String code, String message, Map<String, Object> detail) {
        super(message == null ? code : message);
        this.status = status;
        this.code = code == null ? "ERROR" : code;
        this.detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return getMessage();
    }

    public Map<String, Object> detail() {
        return detail;
    }
}
