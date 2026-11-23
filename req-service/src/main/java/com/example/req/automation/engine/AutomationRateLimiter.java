package com.example.req.automation.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tenant-wide minute-level limiter for automation runs (one tenant cannot flood the worker).
 *
 * <p>AC-11 fixes the budget at {@code platform.automation.max-runs-per-minute} runs per tenant per
 * minute (default 120). The key is the tenant id only: a per-rule key would multiply the ceiling by
 * the number of enabled rules, so a tenant with ten rules could execute 1200 runs in a minute while
 * the checkbox still claimed 120.</p>
 *
 * <p>In-memory on purpose: a database-backed window would add a write to every event just to
 * protect the database. The known cost is documented in {@code memory.md}: with N instances the
 * effective ceiling is N x the configured limit. That is acceptable for a runaway guard (the goal
 * is to stop a rule storm, not to bill exactly 120 runs) and it is flagged as technical debt rather
 * than hidden.</p>
 *
 * <p>The counter resets when the epoch minute changes, so no scheduled sweep is needed and an idle
 * tenant costs one map entry.</p>
 */
@Component
public class AutomationRateLimiter {

    private static final class Window {
        private final AtomicInteger count = new AtomicInteger();
        private long epochMinute = -1L;
    }

    private final int limitPerMinute;
    private final Map<Long, Window> windows = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;

    /**
     * Two constructors exist (the package-private one is the test seam for the minute boundary), so
     * the container needs to be told which one to inject — without this annotation Spring looks for
     * a default constructor and the service fails to start.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AutomationRateLimiter(
            @Value("${platform.automation.max-runs-per-minute:120}") int limitPerMinute) {
        this(limitPerMinute, System::currentTimeMillis);
    }

    /** Test seam: the minute boundary is the only thing a unit test cannot wait for. */
    AutomationRateLimiter(int limitPerMinute, java.util.function.LongSupplier clock) {
        this.limitPerMinute = Math.max(1, limitPerMinute);
        this.clock = clock;
    }

    /**
     * @return true when this run may proceed; false when the tenant already used its minute.
     *
     * <p>The window is keyed by tenant alone, per AC-11. Cross-rule starvation is the accepted
     * semantic of a tenant budget: a burst from one rule consumes the shared minute and other
     * rules record {@code SKIPPED/RATE_LIMITED} until the next minute. That is the contract the
     * gate asserts (121 events -> at least one RATE_LIMITED), and it is the only key that keeps the
     * configured 120 an actual tenant ceiling.</p>
     */
    public boolean tryAcquire(long tenantId) {
        long minute = clock.getAsLong() / 60_000L;
        Window window = windows.computeIfAbsent(tenantId, ignored -> new Window());
        synchronized (window) {
            if (window.epochMinute != minute) {
                window.epochMinute = minute;
                window.count.set(0);
            }
            return window.count.incrementAndGet() <= limitPerMinute;
        }
    }

    public int limitPerMinute() {
        return limitPerMinute;
    }

    /** Test hook: forget every tenant's window. */
    public void reset() {
        windows.clear();
    }
}
