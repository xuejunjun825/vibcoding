package com.example.req.integration.adapter;

/**
 * 连通性测试结果。{@code ok=false} 时 {@code code} 为稳定错误码（TIMEOUT / HTTP_401 /
 * SSRF_BLOCKED 等），{@code message} 只放可展示的短句，绝不带凭据。
 */
public record AdapterResult(boolean ok, int latencyMs, String code, String message) {

    public static AdapterResult success(int latencyMs) {
        return new AdapterResult(true, Math.max(0, latencyMs), null, "连接正常");
    }

    public static AdapterResult failure(int latencyMs, String code, String message) {
        return new AdapterResult(false, Math.max(0, latencyMs), code, message);
    }
}
