package com.example.platform.contract.quality;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The immutable result of one quality-gate evaluation, plus the two shapes that travel with it.
 *
 * <p>Why a snapshot and not "ask the gate again": a release's admission decision must be
 * reproducible months later, after cases, defects and reports have all moved on. {@code deliver}
 * writes the evaluated rule list, the {@code result_digest} it was evaluated against, and the open
 * P0/P1 set into {@code quality_gate_snapshot}; {@code req} freezes {@link #snapshotId()} onto the
 * deploy order. Replay (AC-7) reads this record back and re-renders it — it never recomputes.</p>
 *
 * <p>{@link #hash()} is the {@code result_digest} of the evidence the gate saw, not a hash of this
 * object: identical digests mean the gate would decide the same way, which is what lets a second
 * submit of the same deploy order reuse the snapshot instead of adding a row.</p>
 *
 * <p>{@link GateRuleResult}, {@link QualitySummary} and the snapshot share this file on purpose:
 * they are one contract (all three are read by {@code req} through {@link QualityGatePort}), and
 * three single-purpose record files would only make the port harder to read. They are nested so
 * the import is always {@code GateSnapshot.GateRuleResult} — unambiguous at the call site.</p>
 *
 * @param snapshotId      primary key in {@code quality_gate_snapshot}; 0 before it is persisted
 * @param releaseId       the release being gated
 * @param deployOrderId   the deploy order that asked; 0 for a configuration preview
 * @param reportVersionId the signed report version the gate accepted, {@code null} when none was required
 * @param result          {@link #PASS}, {@link #PASS_WITH_WAIVER} or {@link #FAIL} (StateTables GATE)
 * @param hash            {@code result_digest} (case_id:case_version:result, sorted, SHA-256)
 * @param computedAt      evaluation instant, in UTC
 * @param rules           every configured rule with its outcome, in configuration order
 * @param openP0P1        ids of open (unclosed) P0/P1 defects at evaluation time
 */
public record GateSnapshot(
        long snapshotId,
        long releaseId,
        long deployOrderId,
        Long reportVersionId,
        String result,
        String hash,
        Instant computedAt,
        List<GateRuleResult> rules,
        List<Long> openP0P1) {

    /** Rule status codes, as stored in {@code quality_gate_snapshot.rules_json}. */
    public static final String PASS = "PASS";
    public static final String PASS_WITH_WAIVER = "PASS_WITH_WAIVER";
    public static final String FAIL = "FAIL";

    public GateSnapshot {
        rules = rules == null ? List.of() : List.copyOf(rules);
        openP0P1 = openP0P1 == null ? List.of() : List.copyOf(openP0P1);
    }

    /**
     * Whether this snapshot lets a deployment proceed.
     *
     * <p>A waived pass still lets it through — the waiver is recorded on the rule, not on the
     * result — and {@code req} shows the waivers in the admission payload so nobody reads
     * "PASS_WITH_WAIVER" as a clean pass.</p>
     */
    public boolean passed() {
        return PASS.equals(result) || PASS_WITH_WAIVER.equals(result);
    }

    /** The rules that did not pass, in configuration order (drives the 409 body and the UI list). */
    public List<GateRuleResult> failedRules() {
        return rules.stream().filter(rule -> !rule.passed()).toList();
    }

    /**
     * One gate rule's outcome.
     *
     * @param code      rule code, {@code QG-01} … {@code QG-05}; the stable key the waiver table uses
     * @param status    {@code PASS} / {@code WAIVED} / {@code FAIL} / {@code SKIPPED}
     * @param required  whether a FAIL blocks admission (QG-04's P0/P1 rule is non-waivable)
     * @param actual    the measured value as display text ("92.1%", "3"), never a raw count
     * @param threshold the configured threshold as display text ("≥ 95%", "0")
     * @param fixHint   what the operator must do to turn this rule green, in one sentence
     */
    public record GateRuleResult(
            String code,
            String status,
            boolean required,
            String actual,
            String threshold,
            String fixHint) {

        public boolean passed() {
            return "PASS".equals(status) || "WAIVED".equals(status);
        }
    }

    /**
     * The release-level quality summary {@code req} reads to render the Go/No-Go sheet.
     *
     * <p>Counts, not verdicts: the decision belongs to the gate, and the summary exists so the
     * review page does not call five endpoints. {@code fpy} is {@code null} when the release has no
     * first-round execution (see {@code MetricFormulas.ratioOrNull}) — render "—", not 0%.</p>
     *
     * @param escapeCount        defects whose {@code is_escape} is 1
     * @param postReleaseDefects defects created inside the observation window after a successful deploy
     * @param openP0P1           currently open P0/P1 defects attributed to the release
     * @param rollbackCount      deploy orders of the release that ended in rollback
     * @param executedOrders     deploy orders of the release that actually executed
     * @param gateSnapshotIds    snapshot ids, newest first (history for AC-7 replay)
     * @param reportVersionIds   report version ids, newest first
     */
    public record QualitySummary(
            long releaseId,
            long escapeCount,
            long postReleaseDefects,
            long openP0P1,
            long rollbackCount,
            long executedOrders,
            BigDecimal fpy,
            List<Long> gateSnapshotIds,
            List<Long> reportVersionIds) {

        public QualitySummary {
            gateSnapshotIds = gateSnapshotIds == null ? List.of() : List.copyOf(gateSnapshotIds);
            reportVersionIds = reportVersionIds == null ? List.of() : List.copyOf(reportVersionIds);
        }
    }
}
