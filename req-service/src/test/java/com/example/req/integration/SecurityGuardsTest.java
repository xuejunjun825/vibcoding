package com.example.req.integration;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.platform.common.error.ApiException;
import com.example.req.integration.config.IntegrationProperties;
import com.example.req.integration.security.SensitiveMasker;
import com.example.req.integration.security.SsrfGuard;
import com.example.req.integration.security.WebhookRateLimiter;
import com.example.req.integration.security.WebhookSignatureVerifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AC-10：凭据 0 命中、验签失败 401、限流 429、SSRF 与重定向白名单。 */
class SecurityGuardsTest {

    private static IntegrationProperties props(List<String> allowHosts) {
        IntegrationProperties properties = new IntegrationProperties();
        properties.setEgressAllowHosts(allowHosts);
        return properties;
    }

    // ---------------------------------------------------------------- 脱敏

    @Test
    @DisplayName("JSON 里的 password/token/secret 一律打码")
    void masksJsonSecrets() {
        String masked = SensitiveMasker.mask(
                "{\"password\":\"unit-only-secret\",\"token\":\"abc123\",\"user\":\"admin\"}");
        assertFalse(masked.contains("unit-only-secret"));
        assertFalse(masked.contains("abc123"));
        assertTrue(masked.contains("***"));
        assertTrue(masked.contains("admin"), "非敏感字段保留，便于排障");
    }

    @Test
    @DisplayName("请求头 Authorization / X-Gitlab-Token 打码，普通头保留")
    void masksSensitiveHeaders() {
        String digest = SensitiveMasker.headers(java.util.Map.of(
                "Authorization", "Basic YWRtaW46UzNjcjN0",
                "X-Gitlab-Token", "stub-secret",
                "Accept", "application/json"));
        assertFalse(digest.contains("YWRtaW46UzNjcjN0"));
        assertFalse(digest.contains("stub-secret"));
        assertTrue(digest.contains("application/json"));
    }

    @Test
    @DisplayName("URL 去掉 userinfo 与 token 类 query 参数")
    void masksUrl() {
        String masked = SensitiveMasker.url("http://admin:pw@nexus.local/repo?token=abc&a=1");
        assertEquals("http://nexus.local/repo?a=1", masked);
        assertFalse(SensitiveMasker.url("http://h/x?private_token=zzz").contains("zzz"));
    }

    @Test
    @DisplayName("摘要长度不超过 500")
    void truncatesDigest() {
        assertTrue(SensitiveMasker.mask("x".repeat(2000)).length() <= 500);
    }

    // ---------------------------------------------------------------- SSRF

    @Test
    @DisplayName("危险协议、userinfo、私网地址在连接前拒绝")
    void blocksDangerousTargets() {
        SsrfGuard guard = new SsrfGuard(props(List.of()));
        for (String url : List.of(
                "file:///etc/passwd",
                "gopher://127.0.0.1:6379/_INFO",
                "jar:file:/tmp/a.jar!/x",
                "http://user:pass@example.com/x",
                "http://127.0.0.1/x",
                "http://localhost/x",
                "http://169.254.169.254/latest/meta-data",
                "http://10.1.2.3/x",
                "http://172.16.0.1/x",
                "http://192.168.1.1/x",
                "http://[::1]/x",
                "http://[fc00::1]/x")) {
            ApiException ex = assertThrows(ApiException.class, () -> guard.check(url), url);
            assertEquals(400, ex.status(), url);
            assertTrue(ex.code().equals(SsrfGuard.CODE_BLOCKED)
                    || ex.code().equals(SsrfGuard.CODE_INVALID_URL), url);
        }
    }

    @Test
    @DisplayName("公网地址放行；白名单只放宽指定 host:port")
    void allowlistOnlyLoosensNamedTarget() {
        SsrfGuard guard = new SsrfGuard(props(List.of("127.0.0.1:19090")));
        assertDoesNotThrow(() -> guard.check("http://127.0.0.1:19090/api/json"));
        assertThrows(ApiException.class, () -> guard.check("http://127.0.0.1:19091/api/json"));
        SsrfGuard open = new SsrfGuard(props(List.of()));
        assertDoesNotThrow(() -> open.check("http://93.184.216.34/"));
    }

    @Test
    @DisplayName("重定向必须同主机；跨主机与 https 降级一律 REDIRECT_BLOCKED")
    void redirectsStayOnHost() {
        SsrfGuard guard = new SsrfGuard(props(List.of()));
        URI from = URI.create("http://93.184.216.34/a");
        assertEquals(URI.create("http://93.184.216.34/b"),
                guard.resolveRedirect(from, "/b"));
        ApiException cross = assertThrows(ApiException.class,
                () -> guard.resolveRedirect(from, "http://evil.example/x"));
        assertEquals(SsrfGuard.CODE_REDIRECT, cross.code());
        ApiException downgrade = assertThrows(ApiException.class,
                () -> guard.resolveRedirect(URI.create("https://93.184.216.34/a"), "http://93.184.216.34/b"));
        assertEquals(SsrfGuard.CODE_REDIRECT, downgrade.code());
    }

    // ---------------------------------------------------------------- 验签

    @Test
    @DisplayName("Jenkins HMAC 验签：合法通过，篡改与过期分别给出两个 401 错误码")
    void jenkinsSignature() {
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();
        byte[] body = "{\"buildId\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String ts = String.valueOf(now);
        String signature = "sha256=" + WebhookSignatureVerifier.hex(
                WebhookSignatureVerifier.hmacSha256("stub-secret", ts + "." + new String(body)));
        assertDoesNotThrow(() -> verifier.verifyJenkins("stub-secret", signature, ts, body));

        ApiException tampered = assertThrows(ApiException.class,
                () -> verifier.verifyJenkins("stub-secret", signature, ts, "{\"buildId\":2}".getBytes()));
        assertEquals(401, tampered.status());
        assertEquals(WebhookSignatureVerifier.CODE_SIGNATURE, tampered.code());

        String stale = String.valueOf(now - 1000);
        String staleSig = WebhookSignatureVerifier.hex(
                WebhookSignatureVerifier.hmacSha256("stub-secret", stale + "." + new String(body)));
        ApiException expired = assertThrows(ApiException.class,
                () -> verifier.verifyJenkins("stub-secret", staleSig, stale, body));
        assertEquals(401, expired.status());
        assertEquals(WebhookSignatureVerifier.CODE_TIMESTAMP, expired.code());
    }

    @Test
    @DisplayName("GitLab 共享 secret 常量时间比较")
    void gitlabToken() {
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();
        assertDoesNotThrow(() -> verifier.verifyGitlab("stub-secret", "stub-secret"));
        ApiException ex = assertThrows(ApiException.class,
                () -> verifier.verifyGitlab("stub-secret", "wrong"));
        assertEquals(WebhookSignatureVerifier.CODE_SIGNATURE, ex.code());
    }

    // ---------------------------------------------------------------- 限流

    @Test
    @DisplayName("每 configId 20 req/s，第 21 次拒绝；不同 config 互不影响")
    void rateLimitPerConfig() {
        WebhookRateLimiter limiter = new WebhookRateLimiter(new IntegrationProperties());
        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.allow(7L), "第 " + (i + 1) + " 次应放行");
        }
        assertFalse(limiter.allow(7L));
        assertTrue(limiter.allow(8L));
        limiter.reset(7L);
        assertTrue(limiter.allow(7L));
    }
}
