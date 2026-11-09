package com.example.req.integration.outbound;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.platform.common.error.ApiException;
import com.example.req.integration.config.IntegrationProperties;
import com.example.req.integration.notify.IntegrationNotifier;
import com.example.req.integration.security.SsrfGuard;
import com.example.req.integration.sync.SyncLogWriter;
import com.example.req.integration.sync.SyncResult;

import tools.jackson.databind.ObjectMapper;

/**
 * 集成域唯一的出站 HTTP 入口。
 *
 * <p>两类调用刻意分开：</p>
 * <ul>
 *   <li>{@link #exchange(OutboundRequest)} —— 交互读，单次尝试，失败由调用方降级（页面不白屏）；</li>
 *   <li>{@link #exchangeWithRetry(OutboundRequest)} —— 后台写，1/2/4/8/16s 退避、最多 6 次尝试，
 *       用尽后写 {@code RETRY_EXHAUSTED} 并发站内告警。</li>
 * </ul>
 *
 * <p>SSRF 校验、超时、重定向、脱敏、每次尝试的 {@code integration_sync_log} 都只在这里实现，
 * 四个适配器只做协议映射，因此不存在「某个适配器忘了脱敏」的第二条路径。</p>
 */
@Component
public class OutboundHttpClient {

    private static final Logger log = LoggerFactory.getLogger(OutboundHttpClient.class);

    /** PRD AC-2.4：连接阶段不超过 2s。 */
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration MIN_READ_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    /** JDK HttpClient 不允许手工设置的受限头。 */
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "connection", "content-length", "expect", "host", "upgrade", "date", "via", "warning");

    private final SsrfGuard ssrfGuard;
    private final SyncLogWriter syncLogWriter;
    private final IntegrationNotifier notifier;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OutboundHttpClient(SsrfGuard ssrfGuard,
                              SyncLogWriter syncLogWriter,
                              IntegrationNotifier notifier,
                              ObjectMapper objectMapper,
                              IntegrationProperties properties) {
        this.ssrfGuard = ssrfGuard;
        this.syncLogWriter = syncLogWriter;
        this.notifier = notifier;
        this.objectMapper = objectMapper;
        Duration connect = Duration.ofMillis(Math.max(200,
                properties == null ? 3000 : properties.getConnectTimeoutMs()));
        if (connect.compareTo(MAX_CONNECT_TIMEOUT) > 0) {
            connect = MAX_CONNECT_TIMEOUT;
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connect)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** 交互读：单次尝试，不重试。 */
    public OutboundResponse exchange(OutboundRequest request) {
        return execute(request, RetryPolicy.none());
    }

    /** 后台写：按请求上的策略重试（缺省 standard）。 */
    public OutboundResponse exchangeWithRetry(OutboundRequest request) {
        RetryPolicy policy = request == null || request.retry() == null
                ? RetryPolicy.standard() : request.retry();
        return execute(request, policy);
    }

    private OutboundResponse execute(OutboundRequest request, RetryPolicy policy) {
        if (request == null) {
            throw new IllegalArgumentException("outbound request is required");
        }
        OutboundRequest req = request.correlationId() == null || request.correlationId().isBlank()
                ? request.withCorrelationId(OutboundRequest.newCorrelationId())
                : request;
        int maxAttempts = Math.max(1, policy.maxAttempts());
        long startedAll = System.nanoTime();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            OutboundResponse current = attemptOnce(req, attempt);
            boolean retryable = RetryClassifier.shouldRetry(current.httpStatus(), current.transportFailure());
            boolean lastAttempt = attempt >= maxAttempts;
            // 单次交互读（maxAttempts=1）没有"重试耗尽"这回事：原样返回 TIMEOUT / TRANSPORT_ERROR / HTTP_5xx，
            // 供调用方降级；只有真的重试过并全部失败才是 RETRY_EXHAUSTED。
            boolean exhausted = retryable && lastAttempt && maxAttempts > 1;

            SyncResult result = exhausted ? SyncResult.RETRY_EXHAUSTED
                    : current.successful() ? SyncResult.SUCCESS : SyncResult.FAILED;
            syncLogWriter.write(req, current.withAttempts(attempt), result, current.errorCode());

            if (exhausted) {
                notifier.retryExhausted(req, current.withAttempts(attempt));
            }
            if (!retryable || lastAttempt) {
                long totalMs = elapsedMs(startedAll);
                String code = exhausted ? OutboundResponse.RETRY_EXHAUSTED : current.errorCode();
                return new OutboundResponse(current.httpStatus(), current.body(), totalMs,
                        attempt, code, current.transportFailure());
            }
            // delayFor 是 0 基「第几次重试」：第一次重试等 1s，第二次 2s……
            Duration delay = policy.delayFor(attempt - 1);
            try {
                Thread.sleep(Math.max(0, delay.toMillis()));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return new OutboundResponse(0, null, elapsedMs(startedAll), attempt,
                        OutboundResponse.INTERRUPTED, true);
            }
        }
        // 循环必然 return；保底返回耗尽语义。
        return new OutboundResponse(0, null, elapsedMs(startedAll), maxAttempts,
                OutboundResponse.RETRY_EXHAUSTED, true);
    }

    private OutboundResponse attemptOnce(OutboundRequest req, int attempt) {
        long started = System.nanoTime();
        try {
            ssrfGuard.check(req.url());
        } catch (ApiException ex) {
            return new OutboundResponse(0, null, elapsedMs(started), attempt, ex.code(), false);
        }

        URI target;
        try {
            target = URI.create(req.url().trim());
        } catch (IllegalArgumentException ex) {
            return new OutboundResponse(0, null, elapsedMs(started), attempt,
                    OutboundResponse.INVALID_URL, false);
        }

        Object body = req.body();
        String method = req.method() == null ? "GET" : req.method().name();
        int redirects = 0;
        while (true) {
            HttpRequest httpRequest;
            try {
                httpRequest = buildRequest(req, target, method, body);
            } catch (RuntimeException ex) {
                log.debug("outbound request build failed: {}", ex.toString());
                return new OutboundResponse(0, null, elapsedMs(started), attempt,
                        OutboundResponse.INVALID_REQUEST, false);
            }
            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            } catch (HttpTimeoutException ex) {
                return new OutboundResponse(0, null, elapsedMs(started), attempt,
                        OutboundResponse.TIMEOUT, true);
            } catch (IOException ex) {
                log.debug("outbound transport failure {} {}: {}", method, target.getHost(), ex.toString());
                return new OutboundResponse(0, null, elapsedMs(started), attempt,
                        OutboundResponse.TRANSPORT_ERROR, true);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return new OutboundResponse(0, null, elapsedMs(started), attempt,
                        OutboundResponse.INTERRUPTED, false);
            }

            int status = response.statusCode();
            String location = response.headers().firstValue("Location").orElse(null);
            if (isRedirect(status) && location != null && !location.isBlank()) {
                if (redirects++ >= MAX_REDIRECTS) {
                    return new OutboundResponse(status, null, elapsedMs(started), attempt,
                            OutboundResponse.REDIRECT_BLOCKED, false);
                }
                try {
                    target = ssrfGuard.resolveRedirect(target, location);
                } catch (ApiException ex) {
                    return new OutboundResponse(status, null, elapsedMs(started), attempt,
                            ex.code(), false);
                }
                if (status == 303 || ((status == 301 || status == 302) && "POST".equals(method))) {
                    method = "GET";
                    body = null;
                }
                continue;
            }

            byte[] raw = response.body();
            String text = decode(raw, response);
            String errorCode = status >= 200 && status < 300 ? null : "HTTP_" + status;
            return new OutboundResponse(status, text, elapsedMs(started), attempt, errorCode, false);
        }
    }

    private HttpRequest buildRequest(OutboundRequest req, URI target, String method, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(readTimeout(req))
                .method(method, bodyPublisher(body));

        boolean hasContentType = false;
        if (req.headers() != null) {
            for (Map.Entry<String, String> header : req.headers().entrySet()) {
                if (header.getKey() == null || header.getValue() == null) {
                    continue;
                }
                String name = header.getKey().trim();
                if (name.isEmpty() || RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if ("content-type".equalsIgnoreCase(name)) {
                    hasContentType = true;
                }
                builder.header(name, header.getValue());
            }
        }
        if (body != null && !hasContentType) {
            builder.header("Content-Type", "application/json; charset=utf-8");
        }
        return builder.build();
    }

    private HttpRequest.BodyPublisher bodyPublisher(Object body) {
        if (body == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        if (body instanceof byte[] bytes) {
            return HttpRequest.BodyPublishers.ofByteArray(bytes);
        }
        if (body instanceof CharSequence text) {
            return HttpRequest.BodyPublishers.ofString(text.toString(), StandardCharsets.UTF_8);
        }
        return HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body),
                StandardCharsets.UTF_8);
    }

    private Duration readTimeout(OutboundRequest req) {
        Duration configured = req.readTimeout();
        if (configured == null || configured.isZero() || configured.isNegative()) {
            configured = Duration.ofMillis(5000);
        }
        if (configured.compareTo(MIN_READ_TIMEOUT) < 0) {
            return MIN_READ_TIMEOUT;
        }
        if (configured.compareTo(MAX_READ_TIMEOUT) > 0) {
            return MAX_READ_TIMEOUT;
        }
        return configured;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String decode(byte[] raw, HttpResponse<byte[]> response) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        int length = Math.min(raw.length, MAX_RESPONSE_BYTES);
        Charset charset = charsetOf(response.headers().firstValue("Content-Type").orElse(null));
        return new String(raw, 0, length, charset);
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (idx >= 0) {
                String name = contentType.substring(idx + 8).split(";")[0].trim().replace("\"", "");
                try {
                    return Charset.forName(name);
                } catch (RuntimeException ignored) {
                    // 非法 charset 回落到 UTF-8。
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
