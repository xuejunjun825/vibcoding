package com.example.req.integration.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.example.platform.common.error.ApiException;

/**
 * Webhook 验签：Jenkins 用 HMAC-SHA256 签名 + 时间戳窗口，GitLab 用共享 Secret Token。
 *
 * <p>两者方式不同，但失败语义一致：401 + 稳定错误码，调用方在任何业务写之前调用本类。
 * 时间戳窗口 ±300s 用来挡重放；比较一律走 {@link MessageDigest#isEqual(byte[], byte[])}。</p>
 */
@Component
public class WebhookSignatureVerifier {

    public static final String CODE_SIGNATURE = "WEBHOOK_SIGNATURE_INVALID";
    public static final String CODE_TIMESTAMP = "WEBHOOK_TIMESTAMP_INVALID";

    private static final long MAX_SKEW_SECONDS = 300L;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Jenkins：签名 = HMAC-SHA256(secret, timestamp + "." + rawBody)，头里可带 {@code sha256=} 前缀。 */
    public void verifyJenkins(String secret, String signature, String timestamp, byte[] rawBody) {
        if (secret == null || secret.isBlank()) {
            throw new ApiException(401, CODE_SIGNATURE, "webhook secret 未配置");
        }
        long epochSeconds = parseTimestamp(timestamp);
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - epochSeconds) > MAX_SKEW_SECONDS) {
            throw new ApiException(401, CODE_TIMESTAMP,
                    "webhook 时间戳超出 ±" + MAX_SKEW_SECONDS + "s 窗口");
        }
        String provided = signature == null ? "" : signature.trim();
        if (provided.regionMatches(true, 0, "sha256=", 0, 7)) {
            provided = provided.substring(7).trim();
        }
        byte[] given = hexToBytes(provided);
        if (given == null || given.length == 0) {
            throw new ApiException(401, CODE_SIGNATURE, "webhook 签名缺失或格式非法");
        }
        String payload = timestamp.trim() + "." + new String(rawBody == null ? new byte[0] : rawBody,
                StandardCharsets.UTF_8);
        byte[] expected = hmacSha256(secret, payload);
        if (!MessageDigest.isEqual(expected, given)) {
            throw new ApiException(401, CODE_SIGNATURE, "webhook 签名不匹配");
        }
    }

    /** GitLab：{@code X-Gitlab-Token} 与配置里的 secret 常量时间比较。 */
    public void verifyGitlab(String secret, String token) {
        if (secret == null || secret.isBlank()) {
            throw new ApiException(401, CODE_SIGNATURE, "webhook secret 未配置");
        }
        if (token == null || token.isEmpty()) {
            throw new ApiException(401, CODE_SIGNATURE, "webhook token 缺失");
        }
        boolean equal = MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
        if (!equal) {
            throw new ApiException(401, CODE_SIGNATURE, "webhook token 不匹配");
        }
    }

    /** 供测试与本地桩复用的 HMAC 计算。 */
    public static byte[] hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 不可用", ex);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    private static long parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            throw new ApiException(401, CODE_TIMESTAMP, "webhook 时间戳缺失");
        }
        String value = timestamp.trim();
        try {
            if (value.indexOf('T') > 0 || value.indexOf('-') > 0) {
                return Instant.parse(value).getEpochSecond();
            }
            long parsed = Long.parseLong(value);
            // 13 位以上按毫秒处理（Jenkins 插件两种都有）。
            return value.length() >= 13 ? parsed / 1000L : parsed;
        } catch (NumberFormatException | DateTimeParseException ex) {
            throw new ApiException(401, CODE_TIMESTAMP, "webhook 时间戳格式非法");
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty() || (hex.length() & 1) != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }
}
