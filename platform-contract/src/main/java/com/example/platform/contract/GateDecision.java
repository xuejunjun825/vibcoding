package com.example.platform.contract;

import java.util.List;
import java.util.Map;

/**
 * Verdict of a test gate or a release admission check.
 *
 * <p>The same object is produced by {@code TestGateService} (deliver), consumed by req-service
 * through {@link GatePort}, rendered by the frontend checklist and stored as the release
 * order's snapshot. Sharing the shape is the point: a second "passed/blockers" DTO is how
 * "the API says blocked but the UI says ready" happens.</p>
 *
 * <p>{@code mode} is {@code STRICT} or {@code LOOSE}; {@code blockers} is never {@code null}
 * (empty on pass) so consumers can iterate without a null check, and each blocker carries a
 * fix hint the checklist shows verbatim.</p>
 */
public final class GateDecision {

    private final boolean passed;
    private final String mode;
    private final String reason;
    private final List<GateBlocker> blockers;

    private GateDecision(boolean passed, String mode, String reason, List<GateBlocker> blockers) {
        this.passed = passed;
        this.mode = mode == null ? "STRICT" : mode;
        this.reason = reason;
        this.blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }

    public static GateDecision pass(String mode, String reason) {
        return new GateDecision(true, mode, reason, List.of());
    }

    public static GateDecision block(String mode, String reason, List<GateBlocker> blockers) {
        return new GateDecision(false, mode, reason, blockers);
    }

    public boolean passed() {
        return passed;
    }

    public String mode() {
        return mode;
    }

    public String reason() {
        return reason;
    }

    public List<GateBlocker> blockers() {
        return blockers;
    }

    /** Wire shape shared by HTTP responses and the admission snapshot. */
    public Map<String, Object> toMap() {
        return Map.of(
                "passed", passed,
                "mode", mode,
                "reason", reason == null ? "" : reason,
                "blockers", blockers.stream().map(GateBlocker::toMap).toList());
    }
}
