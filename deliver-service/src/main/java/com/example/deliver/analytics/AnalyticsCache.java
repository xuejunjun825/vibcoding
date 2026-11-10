package com.example.deliver.analytics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The in-process TTL cache for analytics answers (5 minutes by default).
 *
 * <p>This product has no Redis and no Caffeine; the established convention is a
 * {@code ConcurrentHashMap} with lazy expiry, and the analytics engine follows it. Two rules make
 * the cache safe rather than merely fast:</p>
 *
 * <ul>
 *   <li><b>The key carries the scope.</b> {@link AnalyticsRequest#cacheKey(String, long)} mixes in
 *       tenant, viewer, scope tier and a digest of the domain/vendor/org sets, so two accounts with
 *       identical filter chips do not share an entry (AC-12). A cache that only keys on the
 *       filters is exactly how one vendor sees another vendor's numbers.</li>
 *   <li><b>Report writes evict their own entries.</b> Keys are prefixed {@code report:<id>|}, so
 *       changing one definition does not empty the cache for every other report.</li>
 * </ul>
 *
 * <p>Expired entries are removed on read; the map also clears itself past a hard size cap, which
 * is cheaper than a sweeper thread and bounded enough for a single analyst workload.</p>
 */
@Component
public class AnalyticsCache {

    public static final String HIT = "HIT";
    public static final String MISS = "MISS";

    /** Above this many live entries the cache is dropped instead of grown. */
    private static final int MAX_ENTRIES = 512;

    private final long ttlMillis;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public AnalyticsCache(@Value("${analytics.cache.ttl-seconds:300}") long ttlSeconds) {
        this.ttlMillis = Math.max(1L, ttlSeconds) * 1000L;
    }

    /**
     * Read a cached value, telling the caller whether it was a hit through {@code header[0]}.
     *
     * @param header a one-element array the caller forwards to {@code X-Report-Cache}; may be null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type, String[] header) {
        Entry entry = key == null ? null : entries.get(key);
        if (entry == null || entry.expired()) {
            if (entry != null) {
                entries.remove(key, entry);
            }
            mark(header, MISS);
            return null;
        }
        mark(header, HIT);
        Object value = entry.value();
        return type.isInstance(value) ? (T) value : null;
    }

    public void put(String key, Object value) {
        if (key == null || value == null) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES) {
            invalidateAll();
        }
        entries.put(key, new Entry(value, System.currentTimeMillis() + ttlMillis));
    }

    /** Drop the entries of one report definition, leaving every other report warm. */
    public void evictReport(long reportId) {
        String prefix = "report:" + reportId + "|";
        entries.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void invalidateAll() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    public long ttlSeconds() {
        return ttlMillis / 1000L;
    }

    private static void mark(String[] header, String value) {
        if (header != null && header.length > 0) {
            header[0] = value;
        }
    }

    private record Entry(Object value, long expiresAt) {

        boolean expired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }
}
