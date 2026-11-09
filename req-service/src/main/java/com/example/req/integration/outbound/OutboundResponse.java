package com.example.req.integration.outbound;

/**
 * 一次出站尝试的结果（不含重试编排）。
 *
 * <p>{@code httpStatus == 0} 表示没拿到 HTTP 响应（传输失败 / 被 SSRF 拦截），此时
 * {@code errorCode} 必有值、{@code transportFailure} 表示是否属于可重试的传输失败。
 * 适配器使用这个对象做协议映射，任何异常都不越过 {@link OutboundHttpClient} 的边界。</p>
 */
public record OutboundResponse(int httpStatus,
                               String body,
                               long durationMs,
                               int attempts,
                               String errorCode,
                               boolean transportFailure) {

    public static final String TIMEOUT = "TIMEOUT";
    public static final String TRANSPORT_ERROR = "TRANSPORT_ERROR";
    public static final String SSRF_BLOCKED = "SSRF_BLOCKED";
    public static final String REDIRECT_BLOCKED = "REDIRECT_BLOCKED";
    public static final String INVALID_URL = "INVALID_URL";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    public static final String INTERRUPTED = "INTERRUPTED";

    public boolean successful() {
        return !transportFailure && httpStatus >= 200 && httpStatus < 300;
    }

    public boolean hasHttpStatus() {
        return httpStatus > 0;
    }

    public OutboundResponse withAttempts(int value) {
        return new OutboundResponse(httpStatus, body, durationMs, value, errorCode, transportFailure);
    }

    public OutboundResponse withErrorCode(String value) {
        return new OutboundResponse(httpStatus, body, durationMs, attempts, value, transportFailure);
    }
}
