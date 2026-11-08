package com.example.platform.contract;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One reason a gate or admission check is not satisfied.
 *
 * <p>{@code code} is stable for scripts and tests ({@code CASE_FAILED}, {@code NOT_RUN},
 * {@code OPEN_HIGH_DEFECT}, {@code MAN_DAYS_FILLED}, ...); {@code message} is the human
 * sentence; {@code fix} tells the reader what to do next and is rendered by the shared
 * checklist instead of a toast.</p>
 */
public final class GateBlocker {

    private final String code;
    private final String message;
    private final String fix;
    private final Map<String, Object> detail;

    public GateBlocker(String code, String message, String fix) {
        this(code, message, fix, Map.of());
    }

    public GateBlocker(String code, String message, String fix, Map<String, Object> detail) {
        this.code = code;
        this.message = message;
        this.fix = fix;
        this.detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public String fix() {
        return fix;
    }

    public Map<String, Object> detail() {
        return detail;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        map.put("fix", fix);
        if (!detail.isEmpty()) {
            map.put("detail", detail);
        }
        return map;
    }
}
