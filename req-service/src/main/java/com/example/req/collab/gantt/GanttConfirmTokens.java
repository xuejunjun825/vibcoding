package com.example.req.collab.gantt;

import com.example.platform.common.error.ApiException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 甘特越窗改期的二次确认码：HMAC-SHA256 签名、5 分钟 TTL、一次性消费。
 *
 * <p>刻意不建表（AC-18 的 11 表是硬约束）：jti 只留在本进程内存，进程重启后所有未用确认码失效，
 * 用户重新拖一次即可——这是 fail-closed，不是丢数据。payload 绑定 type/id/起止日期，
 * 换了日期或换了行都必须重新签发。</p>
 */
@Component
public class GanttConfirmTokens {

    public static final Duration TTL = Duration.ofMinutes(5);

    private static final String HMAC = "HmacSHA256";
    private static final int MAX_TRACKED = 4096;

    private final String secret;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> consumed = new ConcurrentHashMap<>();

    @Autowired
    public GanttConfirmTokens(@Value("${collab.gantt.confirm-secret:}") String secret,
                              @Value("${platform.jwt.secret:dev-only-jwt-secret-please-change-32bytes-min}")
                              String jwtSecret) {
        this(resolveSecret(secret, jwtSecret), Clock.systemDefaultZone());
    }

    GanttConfirmTokens(String secret, Clock clock) {
        this.secret = secret;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    /** 签发一次性确认码；有效期 5 分钟，绑定行与起止日期。 */
    public String issue(String type, long id, LocalDate start, LocalDate end) {
        return issueAt(type, id, start, end, clock.instant(), TTL);
    }

    /** 仅供单测：把"现在"和 TTL 显式传入，才能稳定测过期与重放。 */
    String issueAt(String type, long id, LocalDate start, LocalDate end, Instant now, Duration ttl) {
        if (type == null || id <= 0 || start == null || end == null) {
            throw invalid("确认码缺少必要的改期信息");
        }
        Instant expiresAt = now.plus(ttl == null ? TTL : ttl);
        String payload = type + '|' + id + '|' + start + '|' + end + '|' + expiresAt.toEpochMilli()
                + '|' + UUID.randomUUID();
        return encode(payload) + '.' + sign(payload);
    }

    /** 校验并消费：签名/绑定/过期/重放任一不满足都抛 409 GANTT_CONFIRM_INVALID。 */
    public void verifyAndConsume(String token, String type, long id, LocalDate start, LocalDate end) {
        Payload payload = verify(token);
        if (!payload.type().equals(type) || payload.id() != id
                || !payload.start().equals(start) || !payload.end().equals(end)) {
            throw invalid("确认码与本次改期内容不匹配，请重新拖拽获取");
        }
        if (clock.instant().isAfter(payload.expiresAt())) {
            throw invalid("确认码已过期，请重新拖拽获取");
        }
        if (consumed.putIfAbsent(payload.jti(), payload.expiresAt()) != null) {
            throw invalid("确认码已使用，请重新拖拽获取");
        }
        purge();
    }

    /** 409 响应里的 expiresAt；token 非法时回退成 TTL 的粗略值，调用方已在 issue 之后才不会走到这里。 */
    public Instant expiresAt(String token) {
        try {
            return verify(token).expiresAt();
        } catch (RuntimeException ex) {
            return clock.instant().plus(TTL);
        }
    }

    private Payload verify(String token) {
        if (token == null || token.isBlank()) {
            throw invalid("确认码缺失");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw invalid("确认码格式不正确");
        }
        String payloadPart = token.substring(0, dot);
        String signaturePart = token.substring(dot + 1);
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(payloadPart), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw invalid("确认码格式不正确");
        }
        byte[] expected = sign(payload).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signaturePart.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw invalid("确认码签名不匹配");
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 6) {
            throw invalid("确认码内容不完整");
        }
        try {
            return new Payload(parts[0], Long.parseLong(parts[1]), LocalDate.parse(parts[2]),
                    LocalDate.parse(parts[3]), Instant.ofEpochMilli(Long.parseLong(parts[4])), parts[5]);
        } catch (RuntimeException ex) {
            throw invalid("确认码内容不完整");
        }
    }

    private String encode(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法签发甘特确认码", ex);
        }
    }

    private void purge() {
        if (consumed.size() <= MAX_TRACKED) {
            return;
        }
        Instant now = clock.instant();
        consumed.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }

    private static String resolveSecret(String secret, String jwtSecret) {
        if (secret != null && !secret.isBlank()) {
            return secret;
        }
        if (jwtSecret != null && !jwtSecret.isBlank()) {
            return jwtSecret + ":gantt";
        }
        // 两者都没配：每个实例随机——进程内可用，重启即全部失效（fail-closed）。
        return "gantt-confirm-" + UUID.randomUUID();
    }

    private static ApiException invalid(String message) {
        return new ApiException(409, "GANTT_CONFIRM_INVALID", message, Map.of());
    }

    private record Payload(String type, long id, LocalDate start, LocalDate end, Instant expiresAt, String jti) {
    }
}
