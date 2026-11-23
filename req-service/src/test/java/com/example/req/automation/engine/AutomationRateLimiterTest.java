package com.example.req.automation.engine;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the per-tenant minute window (r39 T5, TECHNICAL §6.2).
 *
 * <p>The limit is a runaway guard, not billing, so the exact boundary is what matters: the 120th
 * run of a minute is allowed and the 121st is not. The clock seam exists only for the reset test —
 * waiting out a real minute would make the suite slower and flakier without proving anything
 * more.</p>
 */
class AutomationRateLimiterTest {

    @Test
    @DisplayName("119 次允许")
    void allowsWithinLimit() {
        AutomationRateLimiter limiter = new AutomationRateLimiter(120, () -> 1_700_000_000_000L);
        for (int i = 0; i < 119; i++) {
            assertThat(limiter.tryAcquire(1L)).isTrue();
        }
    }

    @Test
    @DisplayName("第 120 次仍允许（边界包含）")
    void allowsExactlyAtLimit() {
        AutomationRateLimiter limiter = new AutomationRateLimiter(120, () -> 1_700_000_000_000L);
        for (int i = 0; i < 120; i++) {
            assertThat(limiter.tryAcquire(1L)).isTrue();
        }
    }

    @Test
    @DisplayName("第 121 次拒绝")
    void rejectsOverLimit() {
        AutomationRateLimiter limiter = new AutomationRateLimiter(120, () -> 1_700_000_000_000L);
        for (int i = 0; i < 120; i++) {
            limiter.tryAcquire(1L);
        }
        assertThat(limiter.tryAcquire(1L)).isFalse();
    }

    @Test
    @DisplayName("跨分钟重置，且租户之间互不影响")
    void resetsAcrossMinutesAndIsolatesTenants() {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        AutomationRateLimiter limiter = new AutomationRateLimiter(2, now::get);
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
        assertThat(limiter.tryAcquire(2L)).isTrue();
        now.addAndGet(60_000L);
        assertThat(limiter.tryAcquire(1L)).isTrue();
    }
}
