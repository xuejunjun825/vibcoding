package com.example.deliver.openapi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Per-client token bucket (AC-17 rate limit).
 *
 * <p>In-memory on purpose: the limit protects one instance from one misbehaving client, and the
 * DDL keeps {@code rate_limit_qps} per client so the number is configurable without a redeploy.
 * A cluster-wide limit would need shared state the schema does not have.</p>
 */
@Component
public class RateLimiter {

    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    /** @return null when the call is allowed, else the seconds to wait before retrying */
    public Long tryAcquire(long clientId, int qps) {
        int limit = qps <= 0 ? 100 : qps;
        Bucket bucket = buckets.computeIfAbsent(clientId, id -> new Bucket(limit));
        return bucket.take(limit);
    }

    private static final class Bucket {

        private double tokens;
        private long lastRefillNanos;
        private final int capacity;

        private Bucket(int capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        private synchronized Long take(int limit) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                tokens = Math.min(limit, tokens + elapsedSeconds * limit);
                lastRefillNanos = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return null;
            }
            double missing = 1.0 - tokens;
            long waitSeconds = (long) Math.ceil(missing / Math.max(1, limit));
            return Math.max(1, Math.min(waitSeconds, 60));
        }

        @SuppressWarnings("unused")
        private int capacity() {
            return capacity;
        }
    }
}
