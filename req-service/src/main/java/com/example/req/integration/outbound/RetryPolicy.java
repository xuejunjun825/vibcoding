package com.example.req.integration.outbound;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试策略对象：退避序列 + 抖动 + 最大尝试次数。
 *
 * <p>策略是对象而不是散落在各个适配器里的 {@code if}：交互读用 {@link #none()}（单次），
 * 后台写用 {@link #standard()}（1/2/4/8/16s，±20% 抖动，最多 6 次尝试）。同一个
 * {@link OutboundHttpClient} 只按这里的序列决定下一次什么时候发，适配器无法各自漂移。</p>
 */
public final class RetryPolicy {

    /** 后台写策略的总尝试次数（1 次首发 + 5 次重试），与 AC-2.2 的「上限 5 次」一致。 */
    public static final int STANDARD_MAX_ATTEMPTS = 6;

    private static final double STANDARD_JITTER = 0.20d;

    private static final RetryPolicy NONE = new RetryPolicy(1, new Duration[0], 0d);
    private static final RetryPolicy STANDARD = new RetryPolicy(
            STANDARD_MAX_ATTEMPTS,
            new Duration[] {
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(4),
                    Duration.ofSeconds(8),
                    Duration.ofSeconds(16)
            },
            STANDARD_JITTER);

    private final int maxAttempts;
    private final Duration[] delays;
    private final double jitter;

    private RetryPolicy(int maxAttempts, Duration[] delays, double jitter) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.delays = delays.clone();
        this.jitter = jitter;
    }

    /** 交互读：只尝试一次，不重试。 */
    public static RetryPolicy none() {
        return NONE;
    }

    /** 后台写：1/2/4/8/16s 指数退避，±20% 抖动，最多 6 次尝试。 */
    public static RetryPolicy standard() {
        return STANDARD;
    }

    /**
     * 自定义尝试次数（测试与特殊后台任务用）：退避仍取标准序列的前 n-1 项。
     * {@code maxAttempts<=1} 等价于 {@link #none()}。
     */
    public static RetryPolicy of(int maxAttempts) {
        if (maxAttempts <= 1) {
            return NONE;
        }
        if (maxAttempts > STANDARD_MAX_ATTEMPTS) {
            throw new IllegalArgumentException("maxAttempts must be <= " + STANDARD_MAX_ATTEMPTS);
        }
        Duration[] slice = new Duration[Math.max(0, maxAttempts - 1)];
        for (int i = 0; i < slice.length; i++) {
            slice[i] = STANDARD.delays[i];
        }
        return new RetryPolicy(maxAttempts, slice, STANDARD_JITTER);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * 第 {@code attempt} 次重试（0 基：0 表示第一次失败后等待的那一次）的退避时长。
     *
     * <p>超过序列长度时沿用最后一个值，调用方不该在超过 {@link #maxAttempts()} - 1 后继续问。</p>
     */
    public Duration delayFor(int attempt) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must be >= 0");
        }
        if (delays.length == 0) {
            return Duration.ZERO;
        }
        Duration base = delays[Math.min(attempt, delays.length - 1)];
        if (jitter <= 0d) {
            return base;
        }
        double factor = 1d + (ThreadLocalRandom.current().nextDouble(-1d, 1d) * jitter);
        long millis = Math.max(1L, Math.round(base.toMillis() * factor));
        return Duration.ofMillis(millis);
    }

    /** 供断言/日志使用的退避基线（不含抖动）。 */
    public Duration baseDelayFor(int attempt) {
        if (delays.length == 0) {
            return Duration.ZERO;
        }
        return delays[Math.min(Math.max(attempt, 0), delays.length - 1)];
    }

    public double jitter() {
        return jitter;
    }

    @Override
    public String toString() {
        return "RetryPolicy{maxAttempts=" + maxAttempts + ", jitter=" + jitter + '}';
    }
}
