package com.example.platform.common.error;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The single error body shape: {@code {status, code, message, detail?}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String code, String message, Map<String, Object> detail) {

    public static ApiError of(int status, String code, String message) {
        return new ApiError(status, code, message, null);
    }

    public static ApiError of(int status, String code, String message, Map<String, Object> detail) {
        return new ApiError(status, code, message,
                detail == null || detail.isEmpty() ? null : detail);
    }
}
