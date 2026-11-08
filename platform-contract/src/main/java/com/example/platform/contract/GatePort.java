package com.example.platform.contract;

import java.util.Collection;
import java.util.Map;

/**
 * Port through which the requirement side asks for a gate verdict.
 *
 * <p>Implemented by {@code DeliverGateClient} in req-service (HTTP, fail-closed) and by
 * {@code TestGateService} in deliver-service (in-process). The requirement state machine
 * depends on this interface only, so RD → TEST_PASSED cannot accidentally grow a second
 * "check the cases myself" implementation, and the flow can be unit-tested with a stub.</p>
 */
public interface GatePort {

    /** Gate verdict for one requirement. Never {@code null}; unavailable means blocked. */
    GateDecision evaluate(long requirementId);

    /** Batch verdicts for release admission; one round trip instead of N. */
    Map<Long, GateDecision> evaluateAll(Collection<Long> requirementIds);
}
