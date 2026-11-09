package com.example.req.integration.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.example.req.integration.config.IntegrationProperties;

/**
 * 无 JWT 的 webhook 端点必须限流：按 configId 的令牌桶，默认 20 req/s。
 *
 * <p>单实例部署下内存实现足够；桶按需懒创建，超过阈值时顺手清理空闲桶，
 * 避免攻击者用随机 configId 把内存撑大。超出时由控制器返回 429 WEBHOOK_RATE_LIMITED。</p>
 */
@Component
public class WebhookRateLimiter {

    public static final String CODE_RATE_LIMITED = "WEBHOOK_RATE_LIMITED";

    private static final int CLEANUP_THRESHOLD = 4096;

    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupAt = new AtomicLong();

    private final double permitsPerSecond;

    public WebhookRateLimiter(IntegrationProperties properties) {
        int configured = properties == null ? 20 : properties.getWebhookRateLimitPerSecond();
        this.permitsPerSecond = configured <= 0 ? 20d : configured;
    }

    /** @return true 表示放行；false 表示本秒配额已用完 */
    public boolean allow(long configId) {
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(configId, key -> new Bucket(permitsPerSecond, now));
        synchronized (bucket) {
            long elapsed = now - bucket.lastRefillNanos;
            if (elapsed > 0) {
                bucket.tokens = Math.min(permitsPerSecond, bucket.tokens
                        + (elapsed / 1_000_000_000d) * permitsPerSecond);
                bucket.lastRefillNanos = now;
            }
            if (bucket.tokens >= 1d) {
                bucket.tokens -= 1d;
                bucket.lastAccessNanos = now;
                return true;
            }
            bucket.lastAccessNanos = now;
        }
        cleanupIfNeeded(now);
        return false;
    }

    /** 测试与运维复位用：清空全部桶。 */
    public void reset() {
        buckets.clear();
    }

    public void reset(long configId) {
        buckets.remove(configId);
    }

    int bucketCount() {
        return buckets.size();
    }

    private void cleanupIfNeeded(long now) {
        if (buckets.size() <= CLEANUP_THRESHOLD) {
            return;
        }
        long previous = lastCleanupAt.get();
        if (now - previous < 60_000_000_000L || !lastCleanupAt.compareAndSet(previous, now)) {
            return;
        }
        long idleNanos = 120_000_000_000L;
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccessNanos > idleNanos);
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;
        private volatile long lastAccessNanos;

        private Bucket(double capacity, long now) {
            this.tokens = capacity;
            this.lastRefillNanos = now;
            this.lastAccessNanos = now;
        }
    }
}
