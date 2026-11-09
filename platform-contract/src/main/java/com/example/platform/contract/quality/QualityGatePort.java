package com.example.platform.contract.quality;

import java.util.List;

/**
 * The quality gate, as the release-admission side sees it.
 *
 * <p>Implemented by {@code deliver-service} ({@code QualityGateService}, the only place the five
 * rules are evaluated) and called by {@code req-service} ({@code DeliverQualityGateClient}) when
 * it assembles the sixth admission item. It is a contract-module interface for the same reason
 * {@code GatePort} is: {@code req} must not depend on {@code deliver} at compile time, and a
 * direct HTTP call written at the call site would duplicate the timeout budget and the internal
 * token.</p>
 *
 * <p>Semantics the caller may rely on:</p>
 * <ul>
 *   <li>{@link #evaluate} is idempotent per {@code (deployOrderId, result_digest)}: asking twice
 *       without new evidence returns the same snapshot instead of appending another row.</li>
 *   <li>A thrown exception means "the gate did not answer". {@code req} turns that into a
 *       {@code FAIL} with {@code GATE_UNAVAILABLE} — never into a pass, and never into a 5xx that
 *       an operator could retry into an approval.</li>
 *   <li>{@link #snapshot} never evaluates; it reads a stored row. An unknown id is an exception,
 *       not an empty snapshot.</li>
 * </ul>
 */
public interface QualityGatePort {

    /**
     * Evaluate the gate for a release as part of this deploy order's admission.
     *
     * @param releaseId       release under admission
     * @param deployOrderId   the order being submitted (drives snapshot reuse)
     * @param requirementIds  requirements in the order, for defect attribution; may be empty
     * @return the stored snapshot, never {@code null}
     */
    GateSnapshot evaluate(long releaseId, long deployOrderId, List<Long> requirementIds);

    /**
     * Read a snapshot back for replay (AC-7).
     *
     * @param snapshotId id frozen on the deploy order
     * @return the same rules and digest that were stored, never {@code null}
     */
    GateSnapshot snapshot(long snapshotId);
}
