package com.example.deliver.openapi;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.HmacSigner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication for the open API (AC-17), before any business validation.
 *
 * <p>The order is the contract, because each step answers a different question and the code must
 * not leak the answer to a question the caller has not passed yet:</p>
 * <ol>
 *   <li>headers present → 401</li>
 *   <li>client known by {@code app_key} → 401</li>
 *   <li>a usable secret (reversible copy decrypts) → 401</li>
 *   <li>timestamp within ±{@code platform.openapi.skew-seconds} → 401</li>
 *   <li>HMAC-SHA256 over the canonical string → 401</li>
 *   <li>client active → 403; source IP whitelisted → 403; scope granted → 403</li>
 *   <li>rate limit → 429 with {@code Retry-After}</li>
 * </ol>
 *
 * <p>Every call — rejected or accepted — is written to {@code api_call_log} in its own
 * transaction, and the resolved tenant is installed as the request's {@link DataScope} so the
 * ingest service cannot accidentally read across tenants.</p>
 */
@Component
public class HmacAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_CLIENT_ID = "platform.openapi.clientId";
    public static final String ATTR_TENANT_ID = "platform.openapi.tenantId";
    public static final String ATTR_REQUEST_ID = "platform.openapi.requestId";

    private static final Set<String> PATHS =
            Set.of("/v1/test-executions", "/openapi/v1/test-executions");
    private static final long MAX_BODY_BYTES = 1024 * 1024;
    private static final String SCOPE_TEST_REPORT = "test:report";
    private static final String SCOPE_TEST_EXECUTION = "test-execution";

    private final ApiClientStore clients;
    private final ClientSecretCodec secrets;
    private final RateLimiter limiter;
    private final ApiCallLogService callLog;
    private final long skewSeconds;

    public HmacAuthFilter(ApiClientStore clients, ClientSecretCodec secrets, RateLimiter limiter,
                          ApiCallLogService callLog,
                          @Value("${platform.openapi.skew-seconds:300}") long skewSeconds) {
        this.clients = clients;
        this.secrets = secrets;
        this.limiter = limiter;
        this.callLog = callLog;
        this.skewSeconds = skewSeconds <= 0 ? 300 : skewSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATHS.contains(stripTrailingSlash(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String canonicalPath = canonicalPath(request.getRequestURI());
        String ip = clientIp(request);
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        Long clientId = null;
        long tenantId = 0L;
        String resultCode = "OK";
        byte[] body;
        try {
            body = readBody(request);
            String appKey = header(request, "X-App-Key");
            String timestamp = header(request, "X-Timestamp");
            String signature = header(request, "X-Signature");
            if (appKey.isEmpty() || timestamp.isEmpty() || signature.isEmpty()) {
                throw new AuthFailure(401, "SIGNATURE_MISSING",
                        "缺少 X-App-Key / X-Timestamp / X-Signature");
            }
            ApiClientStore.ApiClientRow client = clients.byAppKey(appKey).orElse(null);
            if (client == null) {
                throw new AuthFailure(401, "CLIENT_UNKNOWN", "未知的 app_key");
            }
            clientId = client.id();
            tenantId = client.tenantId();
            String secret = secrets.decode(client.secretEnc(), client.secretHash());
            if (secret == null) {
                throw new AuthFailure(401, "CLIENT_SECRET_UNAVAILABLE", "客户端密钥不可用，请重新轮换");
            }
            long seconds = parseTimestamp(timestamp);
            long now = System.currentTimeMillis() / 1000L;
            if (Math.abs(now - seconds) > skewSeconds) {
                throw new AuthFailure(401, "TIMESTAMP_SKEW",
                        "时间戳超出 ±" + skewSeconds + " 秒");
            }
            String canonical = HmacSigner.canonical("POST", canonicalPath, appKey, timestamp, body);
            if (!HmacSigner.verify(secret, canonical, signature)) {
                throw new AuthFailure(401, "SIGNATURE_INVALID", "签名不匹配");
            }
            if (!client.active()) {
                throw new AuthFailure(403, "CLIENT_DISABLED", "客户端已停用");
            }
            if (!ipAllowed(client, ip)) {
                throw new AuthFailure(403, "IP_NOT_ALLOWED", "来源 IP 不在白名单：" + ip);
            }
            if (!scopeAllowed(client)) {
                throw new AuthFailure(403, "SCOPE_DENIED",
                        "客户端 scope 不含 " + SCOPE_TEST_REPORT);
            }
            Long wait = limiter.tryAcquire(client.id(), client.rateLimitQps());
            if (wait != null) {
                response.setHeader("Retry-After", String.valueOf(wait));
                throw new AuthFailure(429, "RATE_LIMITED", "请求过于频繁，请 " + wait + " 秒后重试");
            }

            TenantContext.set(DataScope.system(tenantId));
            CachedBodyRequest cached = new CachedBodyRequest(request, body);
            cached.setAttribute(ATTR_CLIENT_ID, client.id());
            cached.setAttribute(ATTR_TENANT_ID, tenantId);
            cached.setAttribute(ATTR_REQUEST_ID, requestId);
            chain.doFilter(cached, response);
        } catch (AuthFailure failure) {
            resultCode = failure.code;
            writeError(response, failure.status, failure.code, failure.message);
        } finally {
            TenantContext.clear();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            int status = response.getStatus();
            callLog.record(tenantId, clientId, canonicalPath, request.getMethod(), status,
                    resultCode, elapsedMs, ip, requestId);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String canonicalPath(String uri) {
        String path = stripTrailingSlash(uri == null ? "" : uri);
        return path.startsWith("/openapi") ? path : "/openapi" + path;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 1 && value.endsWith("/")
                ? value.substring(0, value.length() - 1) : value;
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? "" : value.trim();
    }

    private static long parseTimestamp(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new AuthFailure(401, "TIMESTAMP_INVALID", "X-Timestamp 必须为秒级 Unix 时间戳");
        }
    }

    private static boolean ipAllowed(ApiClientStore.ApiClientRow client, String ip) {
        var allowed = client.ipList();
        if (allowed.isEmpty() || ip == null || ip.isBlank()) {
            return allowed.isEmpty();
        }
        for (String candidate : allowed) {
            if (candidate.equals(ip) || candidate.equals("*")) {
                return true;
            }
            if (candidate.endsWith("*") && ip.startsWith(candidate.substring(0, candidate.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean scopeAllowed(ApiClientStore.ApiClientRow client) {
        for (String scope : client.scopeList()) {
            if ("*".equals(scope) || SCOPE_TEST_REPORT.equals(scope)
                    || SCOPE_TEST_EXECUTION.equals(scope)) {
                return true;
            }
        }
        return false;
    }

    /** First hop of {@code X-Forwarded-For}, else the socket address. */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            throw new AuthFailure(413, "PAYLOAD_TOO_LARGE", "请求体超过 1MB");
        }
        byte[] body = request.getInputStream().readNBytes((int) MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new AuthFailure(413, "PAYLOAD_TOO_LARGE", "请求体超过 1MB");
        }
        return body;
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        // Hand-written JSON: the filter must answer even when no message converter is resolvable,
        // and it keeps the error body identical whether the rejection happened here or downstream.
        String body = "{\"code\":" + quote(code)
                + ",\"message\":" + quote(message) + ",\"detail\":{}}";
        response.resetBuffer();
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body);
        response.getWriter().flush();
    }

    private static String quote(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** A rejection decided by the filter itself; carries what the response and the log need. */
    private static final class AuthFailure extends RuntimeException {

        private final int status;
        private final String code;
        private final String message;

        private AuthFailure(int status, String code, String message) {
            super(message, null, false, false);
            this.status = status;
            this.code = code;
            this.message = message;
        }
    }

    /** Replays the already-read body to the controller. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {

                @Override
                public boolean isFinished() {
                    return stream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // Asynchronous reads are not used on this endpoint.
                }

                @Override
                public int read() {
                    return stream.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return stream.read(buffer, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
