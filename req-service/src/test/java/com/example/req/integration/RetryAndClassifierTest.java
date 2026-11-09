package com.example.req.integration;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.req.integration.outbound.RetryClassifier;
import com.example.req.integration.outbound.RetryPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-2.2：只有传输失败 / 5xx / 429 重试，4xx 不重试；退避 1/2/4/8/16s、±20% 抖动、最多 6 次尝试。
 */
class RetryAndClassifierTest {

    @Test
    @DisplayName("none 单次；standard 六次尝试（首次 + 5 次重试）")
    void attemptBudgets() {
        assertEquals(1, RetryPolicy.none().maxAttempts());
        assertEquals(6, RetryPolicy.standard().maxAttempts());
        assertEquals(3, RetryPolicy.of(3).maxAttempts());
        assertEquals(1, RetryPolicy.of(0).maxAttempts());
    }

    @Test
    @DisplayName("退避序列 1/2/4/8/16s（0 基第 n 次重试），抖动不超过 ±20% 且单调递增")
    void backoffSequence() {
        RetryPolicy policy = RetryPolicy.standard();
        long[] expected = {1000, 2000, 4000, 8000, 16000};
        long previous = 0;
        for (int retry = 0; retry < expected.length; retry++) {
            long base = policy.baseDelayFor(retry).toMillis();
            assertEquals(expected[retry], base, "第 " + (retry + 1) + " 次重试的退避基准");
            for (int i = 0; i < 40; i++) {
                Duration delay = policy.delayFor(retry);
                assertTrue(delay.toMillis() >= (long) (base * 0.8) - 1,
                        "抖动下界: " + delay.toMillis());
                assertTrue(delay.toMillis() <= (long) (base * 1.2) + 1,
                        "抖动上界: " + delay.toMillis());
            }
            assertTrue(base > previous);
            previous = base;
        }
        // 六次尝试只有五次重试：最后一次失败后不再等待。
        assertEquals(RetryPolicy.standard().maxAttempts() - 1, expected.length);
    }

    @Test
    @DisplayName("单次策略没有退避；超出序列长度沿用最后一项而不是归零")
    void delayEdges() {
        assertEquals(Duration.ZERO, RetryPolicy.none().delayFor(0));
        assertEquals(Duration.ofSeconds(16), RetryPolicy.standard().baseDelayFor(5));
    }

    @Test
    @DisplayName("重试判定穷举：传输失败/429/5xx 为真，其余 4xx 为假")
    void classifierMatrix() {
        assertTrue(RetryClassifier.shouldRetry(0, true));
        assertTrue(RetryClassifier.shouldRetry(429, false));
        for (int status : new int[] {500, 502, 503, 504, 599}) {
            assertTrue(RetryClassifier.shouldRetry(status, false), "status=" + status);
        }
        for (int status : new int[] {200, 201, 204, 301, 400, 401, 403, 404, 409, 422}) {
            assertFalse(RetryClassifier.shouldRetry(status, false), "status=" + status);
        }
        assertFalse(RetryClassifier.shouldRetry(200, false));
    }
}
