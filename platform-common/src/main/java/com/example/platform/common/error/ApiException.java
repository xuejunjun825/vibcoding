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
        this.detail = copyWithoutNulls(detail);
    }

    /**
     * Detail maps are diagnostic bodies, not contracts: a {@code null} entry means "this dimension
     * does not apply" (e.g. a page-only decision has no action code). {@link Map#copyOf} rejects
     * nulls, which used to turn a denied request into a 500 on the way out — the caller must see
     * the 403 it earned. Null-valued entries are dropped rather than propagated.
     */
    private static Map<String, Object> copyWithoutNulls(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> clean = new java.util.LinkedHashMap<>();
        detail.forEach((key, value) -> {
            if (key != null && value != null) {
                clean.put(key, value);
            }
        });
        return clean.isEmpty() ? Map.of() : Map.copyOf(clean);
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
