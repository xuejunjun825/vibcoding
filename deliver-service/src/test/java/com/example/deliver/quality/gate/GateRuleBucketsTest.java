package com.example.deliver.quality.gate;

import java.util.Arrays;
import java.util.List;

import com.example.platform.contract.quality.GateRuleBuckets;
import com.example.platform.contract.quality.GateSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bucket truth table and its identities.
 *
 * <p>Every downstream number (release-flow subtitle, quality-gate page label, admission sentence,
 * the S8 gate assertions) is derived from {@link GateRuleBuckets}. If one of these tests breaks,
 * three surfaces will disagree about the same snapshot — so the split is pinned here first, before
 * any caller is written.</p>
 */
class GateRuleBucketsTest {

    private static GateSnapshot.GateRuleResult rule(String code, String status) {
        return new GateSnapshot.GateRuleResult(code, status, true, "actual", "threshold", "fix");
    }

    @Test
    void splits_failed_and_skipped() {
        GateRuleBuckets buckets = GateRuleBuckets.of(List.of(
                rule("QG-01", "PASS"),
                rule("QG-02", "WAIVED"),
                rule("QG-04", "FAIL"),
                rule("QG-05", "SKIPPED")));

        assertThat(buckets.passedCount()).isEqualTo(1);
        assertThat(buckets.waivedCount()).isEqualTo(1);
        assertThat(buckets.failedCount()).isEqualTo(1);
        assertThat(buckets.skippedCount()).isEqualTo(1);

        // The two lists are disjoint and carry only their own status: a skipped rule may never
        // appear in failedRules again, which is the bug (QG-05 counted as "未通过").
        assertThat(buckets.failedRules()).extracting(GateSnapshot.GateRuleResult::code)
                .containsExactly("QG-04");
        assertThat(buckets.skippedRules()).extracting(GateSnapshot.GateRuleResult::code)
                .containsExactly("QG-05");

        // Identities every caller relies on.
        assertThat(buckets.totalCount()).isEqualTo(4);
        assertThat(buckets.judgedCount()).isEqualTo(3);
        assertThat(buckets.passedLikeCount()).isEqualTo(2);
        assertThat(buckets.hasSkipped()).isTrue();
        assertThat(buckets.totalCount())
                .isEqualTo(buckets.passedCount() + buckets.waivedCount()
                        + buckets.failedCount() + buckets.skippedCount());
        assertThat(buckets.judgedCount() + buckets.skippedCount())
                .isEqualTo(buckets.totalCount());
    }

    @Test
    void skipped_is_neither_passed_nor_waived() {
        GateSnapshot.GateRuleResult skipped = rule("QG-05", "SKIPPED");
        assertThat(skipped.skipped()).isTrue();
        assertThat(skipped.passed()).isFalse();
        assertThat(skipped.waived()).isFalse();

        assertThat(rule("QG-02", "WAIVED").waived()).isTrue();
        assertThat(rule("QG-02", "WAIVED").skipped()).isFalse();
        assertThat(rule("QG-01", "PASS").passed()).isTrue();
    }

    @Test
    void without_skipped_the_legacy_numbers_hold() {
        GateRuleBuckets buckets = GateRuleBuckets.of(List.of(
                rule("QG-01", "PASS"),
                rule("QG-02", "PASS"),
                rule("QG-03", "PASS")));

        assertThat(buckets.hasSkipped()).isFalse();
        assertThat(buckets.skippedRules()).isEmpty();
        assertThat(buckets.failedRules()).isEmpty();
        assertThat(buckets.totalCount()).isEqualTo(3);
        assertThat(buckets.judgedCount()).isEqualTo(3);
        assertThat(buckets.passedLikeCount()).isEqualTo(3);
    }

    @Test
    void unknown_status_counts_as_failed() {
        GateRuleBuckets buckets = GateRuleBuckets.of(Arrays.asList(
                rule("QG-99", "NOT_A_STATUS"),
                rule("QG-98", null),
                null));

        assertThat(buckets.passedCount()).isZero();
        assertThat(buckets.waivedCount()).isZero();
        assertThat(buckets.skippedCount()).isZero();
        // Fail-closed: a status this build cannot read must never be reported as a pass.
        assertThat(buckets.failedCount()).isEqualTo(3);
        // A null hole has no row to list, but it still counts as a failure.
        assertThat(buckets.failedRules()).extracting(GateSnapshot.GateRuleResult::code)
                .containsExactly("QG-99", "QG-98");
        assertThat(buckets.totalCount()).isEqualTo(3);
        assertThat(buckets.judgedCount()).isEqualTo(3);
    }

    @Test
    void null_rules_is_all_zero() {
        for (GateRuleBuckets buckets : List.of(
                GateRuleBuckets.of(null),
                GateRuleBuckets.of(List.of()))) {
            assertThat(buckets.passedCount()).isZero();
            assertThat(buckets.waivedCount()).isZero();
            assertThat(buckets.failedCount()).isZero();
            assertThat(buckets.skippedCount()).isZero();
            assertThat(buckets.failedRules()).isEmpty();
            assertThat(buckets.skippedRules()).isEmpty();
            assertThat(buckets.totalCount()).isZero();
            assertThat(buckets.judgedCount()).isZero();
            assertThat(buckets.passedLikeCount()).isZero();
            assertThat(buckets.hasSkipped()).isFalse();
        }
    }
}
