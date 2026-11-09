package com.example.req.collab.gantt;

import com.example.platform.common.error.ApiException;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确认码的五条硬约束（AC-2）：5 分钟有效、绑定 type/id/起止、一次性、篡改不可用、跨密钥不可用。
 * 不新建表，因此"重启即失效"由进程内 map + fail-closed 密钥保证，这里只测可达的边界。
 */
class GanttConfirmTokensTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 20);
    private static final Instant NOW = Instant.parse("2026-11-09T02:00:00Z");

    @Test
    void tokenIsSingleUse() {
        GanttConfirmTokens tokens = tokensAt(NOW);
        String token = tokens.issue("REQUIREMENT", 42L, START, END);

        tokens.verifyAndConsume(token, "REQUIREMENT", 42L, START, END);

        ApiException again = assertThrows(ApiException.class,
                () -> tokens.verifyAndConsume(token, "REQUIREMENT", 42L, START, END));
        assertEquals(409, again.status());
        assertEquals("GANTT_CONFIRM_INVALID", again.code());
    }

    @Test
    void tokenIsBoundToRowAndDates() {
        GanttConfirmTokens tokens = tokensAt(NOW);
        String token = tokens.issue("REQUIREMENT", 42L, START, END);

        assertInvalid(tokens, token, "TASK", 42L, START, END);
        assertInvalid(tokens, token, "REQUIREMENT", 43L, START, END);
        assertInvalid(tokens, token, "REQUIREMENT", 42L, START.plusDays(1), END);
        assertInvalid(tokens, token, "REQUIREMENT", 42L, START, END.plusDays(1));
    }

    @Test
    void tokenExpiresAfterFiveMinutes() {
        GanttConfirmTokens tokens = tokensAt(NOW);
        String token = tokens.issueAt("REQUIREMENT", 42L, START, END, NOW, Duration.ofMinutes(5));
        assertEquals(NOW.plus(Duration.ofMinutes(5)), tokens.expiresAt(token));

        GanttConfirmTokens later = tokensAt(NOW.plus(Duration.ofMinutes(6)));
        ApiException expired = assertThrows(ApiException.class,
                () -> later.verifyAndConsume(token, "REQUIREMENT", 42L, START, END));
        assertEquals("GANTT_CONFIRM_INVALID", expired.code());
        assertTrue(expired.message().contains("过期"));
    }

    @Test
    void tamperedOrForeignSignatureIsRejected() {
        GanttConfirmTokens tokens = tokensAt(NOW);
        String token = tokens.issue("REQUIREMENT", 42L, START, END);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertInvalid(tokens, tampered, "REQUIREMENT", 42L, START, END);
        assertInvalid(tokens, "not-a-token", "REQUIREMENT", 42L, START, END);
        assertInvalid(tokens, null, "REQUIREMENT", 42L, START, END);

        GanttConfirmTokens other = new GanttConfirmTokens("another-secret", clockAt(NOW));
        assertInvalid(tokens, other.issue("REQUIREMENT", 42L, START, END), "REQUIREMENT", 42L, START, END);
    }

    @Test
    void issuingWithoutRowDataFailsClosed() {
        GanttConfirmTokens tokens = tokensAt(NOW);
        ApiException error = assertThrows(ApiException.class,
                () -> tokens.issue("REQUIREMENT", 0L, START, END));
        assertEquals("GANTT_CONFIRM_INVALID", error.code());
    }

    // ------------------------------------------------------------------ helpers

    private static GanttConfirmTokens tokensAt(Instant now) {
        return new GanttConfirmTokens("test-secret-gantt", clockAt(now));
    }

    private static Clock clockAt(Instant now) {
        return Clock.fixed(now, ZoneId.of("Asia/Shanghai"));
    }

    private static void assertInvalid(GanttConfirmTokens tokens, String token, String type, long id,
                                      LocalDate start, LocalDate end) {
        ApiException error = assertThrows(ApiException.class,
                () -> tokens.verifyAndConsume(token, type, id, start, end));
        assertEquals(409, error.status());
        assertEquals("GANTT_CONFIRM_INVALID", error.code());
    }
}
