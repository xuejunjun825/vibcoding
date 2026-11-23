package com.example.req.automation.engine;

import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The polling entry point. Deliberately a separate scheduled bean rather than work performed on the
 * request thread: "business request returns in under a second" (AC-13) is then a structural fact,
 * not a tuning outcome — nothing the user's request does can wait for a rule to run.
 *
 * <p>The semaphore is the only concurrency control: at most {@code worker-concurrency} drain cycles
 * are in flight, and cycles that lose the race are skipped rather than queued, because the next tick
 * is one second away anyway.</p>
 */
@Component
public class AutomationWorker {

    private static final Logger log = LoggerFactory.getLogger(AutomationWorker.class);

    private final AutomationDispatcher dispatcher;
    private final Semaphore permits;

    public AutomationWorker(AutomationDispatcher dispatcher,
                            @Value("${platform.automation.worker-concurrency:2}") int concurrency) {
        this.dispatcher = dispatcher;
        this.permits = new Semaphore(Math.max(1, concurrency));
    }

    @Scheduled(fixedDelayString = "${platform.automation.worker-poll-ms:1000}",
            initialDelayString = "${platform.automation.worker-poll-ms:1000}")
    public void poll() {
        if (!permits.tryAcquire()) {
            return;
        }
        try {
            drainOnce();
        } catch (RuntimeException ex) {
            log.warn("automation worker poll failed: {}", ex.getMessage());
        } finally {
            permits.release();
        }
    }

    /** One full pass over every tenant that has work; returns nothing because the outcome is rows. */
    public void drainOnce() {
        dispatcher.drain();
    }
}
