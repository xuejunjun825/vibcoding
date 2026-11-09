package com.example.deliver.executor;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AC-16 的边界：健康是派生值，24h / 72h 的阈值只在 {@link ExecutorHealth} 里定义一次。
 * 冻结 now，逐个边界钉死，避免页面与接口各算一套。
 */
class ExecutorHealthTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 12, 0);

    @Test
    @DisplayName("停用的执行器一律 DISABLED，即使刚刚上报过")
    void disabledWins() {
        assertEquals(ExecutorHealth.DISABLED, ExecutorHealth.of(false, NOW.minusMinutes(1), NOW));
    }

    @Test
    @DisplayName("从未上报 → NEVER，而不是 ONLINE")
    void neverReported() {
        assertEquals(ExecutorHealth.NEVER, ExecutorHealth.of(true, null, NOW));
    }

    @Test
    @DisplayName("23:59 前上报 → ONLINE")
    void justInsideOnlineWindow() {
        assertEquals(ExecutorHealth.ONLINE,
                ExecutorHealth.of(true, NOW.minusMinutes(23 * 60 + 59), NOW));
    }

    @Test
    @DisplayName("24:01 前上报 → STALE（刚过 24h 边界即降级）")
    void justOutsideOnlineWindow() {
        assertEquals(ExecutorHealth.STALE,
                ExecutorHealth.of(true, NOW.minusMinutes(24 * 60 + 1), NOW));
    }

    @Test
    @DisplayName("72:01 → OFFLINE；71:59 仍是 STALE")
    void offlineBoundary() {
        assertEquals(ExecutorHealth.STALE,
                ExecutorHealth.of(true, NOW.minusMinutes(72 * 60 - 1), NOW));
        assertEquals(ExecutorHealth.OFFLINE,
                ExecutorHealth.of(true, NOW.minusMinutes(72 * 60 + 1), NOW));
    }

    @Test
    @DisplayName("时钟回拨（上报时间在未来）按 ONLINE 处理，不抛异常")
    void futureReportIsOnline() {
        assertEquals(ExecutorHealth.ONLINE, ExecutorHealth.of(true, NOW.plusMinutes(5), NOW));
    }
}
