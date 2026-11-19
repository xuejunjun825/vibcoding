package com.example.platform.contract.quality;

import java.util.ArrayList;
import java.util.List;

/**
 * One gate rule list cut into its four outcome buckets: passed / waived / skipped / failed.
 *
 * <p>Why this exists in the contract rather than in a service: {@code deliver} produces the
 * release-flow segment, {@code req} renders the admission sentence and the release-flow gate
 * asserts on the numbers. Three hand-rolled copies of "which status counts as what" would drift
 * the moment a fifth status appears — and the drift is exactly the bug this contract fixes
 * (QG-05 is {@code SKIPPED}, "not enabled this round", and was being counted as a failure).</p>
 *
 * <p>Bucket rules, the single truth table for the whole product:</p>
 * <ul>
 *   <li>{@code PASS} → passed, {@code WAIVED} → waived</li>
 *   <li>{@code SKIPPED} → skipped: neither a pass nor a failure, and kept out of every ratio</li>
 *   <li>anything else, including {@code FAIL}, an unknown future status and {@code null} →
 *       <b>failed</b> (fail-closed: a status this build cannot read must never be reported as a
 *       pass)</li>
 * </ul>
 *
 * <p>{@link #failedRules()} holds FAIL rules only and {@link #skippedRules()} SKIPPED rules only;
 * both keep configuration order so the UI lists them the way the gate evaluated them. Neither list
 * is derived from {@link GateSnapshot#failedRules()}, which still filters on {@code !passed()} and
 * therefore still contains SKIPPED rows from snapshots frozen before this contract existed.</p>
 *
 * @param passedCount  rules with status {@code PASS}
 * @param waivedCount  rules with status {@code WAIVED}
 * @param failedCount  rules with status {@code FAIL} (or any status this build cannot read)
 * @param skippedCount rules with status {@code SKIPPED}
 * @param failedRules  the FAIL rules, in configuration order
 * @param skippedRules the SKIPPED rules, in configuration order
 */
public record GateRuleBuckets(
        int passedCount,
        int waivedCount,
        int failedCount,
        int skippedCount,
        List<GateSnapshot.GateRuleResult> failedRules,
        List<GateSnapshot.GateRuleResult> skippedRules) {

    public GateRuleBuckets {
        failedRules = failedRules == null ? List.of() : List.copyOf(failedRules);
        skippedRules = skippedRules == null ? List.of() : List.copyOf(skippedRules);
    }

    /** Cuts a rule list into the four buckets; {@code null} or empty yields an all-zero bucket set. */
    public static GateRuleBuckets of(List<GateSnapshot.GateRuleResult> rules) {
        if (rules == null || rules.isEmpty()) {
            return new GateRuleBuckets(0, 0, 0, 0, List.of(), List.of());
        }
        int passed = 0;
        int waived = 0;
        int failed = 0;
        int skipped = 0;
        List<GateSnapshot.GateRuleResult> failedRules = new ArrayList<>();
        List<GateSnapshot.GateRuleResult> skippedRules = new ArrayList<>();
        for (GateSnapshot.GateRuleResult rule : rules) {
            if (rule == null) {
                // A hole in the list is not evidence of a pass: count it as a failure, but there
                // is no rule row to list.
                failed++;
                continue;
            }
            if (rule.skipped()) {
                skipped++;
                skippedRules.add(rule);
            } else if (rule.waived()) {
                waived++;
            } else if (rule.passed()) {
                passed++;
            } else {
                failed++;
                failedRules.add(rule);
            }
        }
        return new GateRuleBuckets(passed, waived, failed, skipped,
                List.copyOf(failedRules), List.copyOf(skippedRules));
    }

    /** Every configured rule, judged or not. */
    public int totalCount() {
        return passedCount + waivedCount + failedCount + skippedCount;
    }

    /**
     * The denominator of every pass ratio: total minus skipped.
     *
     * <p>Skipped rules were never evaluated, so letting them into the denominator would quietly
     * lower the pass rate for a rule nobody ran.</p>
     */
    public int judgedCount() {
        return totalCount() - skippedCount;
    }

    /** Rules that did not fail: pass plus waiver, matching {@code QualityGateService}'s "passed". */
    public int passedLikeCount() {
        return passedCount + waivedCount;
    }

    /** Whether any rule was skipped — the only case where the admission sentence may mention it. */
    public boolean hasSkipped() {
        return skippedCount > 0;
    }
}
