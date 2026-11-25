package com.example.req.requirement;

import com.example.platform.common.error.ApiException;
import com.example.req.requirement.RequirementMergeRules.Source;
import com.example.req.requirement.RequirementMergeRules.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 合并前置的六条契约（r47 §3.3）：每条错误码一个样本，顺序也钉住——重复合并必须报
 * ALREADY_MERGED 而不是 REQUIREMENT_CLOSED。
 */
class RequirementMergeRulesTest {

    private static Source source(String type, String status, Long mergedInto, boolean terminal) {
        return new Source(11L, type, status, mergedInto, terminal);
    }

    private static Target target(String type, String status, Long mergedInto) {
        return new Target(22L, type, status, mergedInto);
    }

    private static ApiException violation(Source source, Target target, boolean related) {
        try {
            RequirementMergeRules.check(source, target, related);
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("预期抛出 ApiException，但没有");
    }

    @Test
    @DisplayName("正常合并：同类型、源在途、目标可承接、无父子关系 → 不抛")
    void happyPathPasses() {
        assertThatCode(() -> RequirementMergeRules.check(
                source("PDR", "IN_DEV", null, false),
                target("PDR", "SCHEDULED", null), false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("源已合并 → 409 ALREADY_MERGED（即使源已被置为 CLOSED 也优先报这个）")
    void alreadyMergedWinsOverClosed() {
        ApiException e = violation(source("PDR", "CLOSED", 33L, true),
                target("PDR", "POOLED", null), false);
        assertThat(e.status()).isEqualTo(409);
        assertThat(e.code()).isEqualTo(RequirementErrors.ALREADY_MERGED);
        assertThat(e.detail()).containsEntry("id", 11L).containsEntry("mergedIntoId", 33L);
    }

    @Test
    @DisplayName("目标已合并 → 400 TARGET_MERGED")
    void targetMergedIsRejected() {
        ApiException e = violation(source("PDR", "IN_DEV", null, false),
                target("PDR", "POOLED", 44L), false);
        assertThat(e.status()).isEqualTo(400);
        assertThat(e.code()).isEqualTo(RequirementErrors.TARGET_MERGED);
    }

    @Test
    @DisplayName("跨类型 → 400 VALIDATION_FAILED（不允许 BUS→PDR 这类合并且不提示原因）")
    void crossTypeIsRejected() {
        ApiException e = violation(source("BUS", "POOLED", null, false),
                target("PDR", "POOLED", null), false);
        assertThat(e.status()).isEqualTo(400);
        assertThat(e.code()).isEqualTo(RequirementErrors.VALIDATION_FAILED);
        assertThat(e.getMessage()).contains("同类型");
    }

    @Test
    @DisplayName("源终态 → 409 REQUIREMENT_CLOSED")
    void closedSourceIsRejected() {
        ApiException e = violation(source("PDR", "ACCEPTED_CLOSED", null, true),
                target("PDR", "POOLED", null), false);
        assertThat(e.status()).isEqualTo(409);
        assertThat(e.code()).isEqualTo(RequirementErrors.REQUIREMENT_CLOSED);
    }

    @Test
    @DisplayName("目标状态不在 POOLED/SCHEDULED/IN_DEV → 400 VALIDATION_FAILED")
    void targetStatusMustAcceptContent() {
        ApiException e = violation(source("PDR", "IN_DEV", null, false),
                target("PDR", "DELIVERED", null), false);
        assertThat(e.status()).isEqualTo(400);
        assertThat(e.code()).isEqualTo(RequirementErrors.VALIDATION_FAILED);
        assertThat(e.detail()).containsEntry("status", "DELIVERED");
    }

    @Test
    @DisplayName("目标与源存在父子关系 → 400 MERGE_CYCLE；自己合自己也一样")
    void relatedAndSelfAreCycles() {
        ApiException ancestor = violation(source("PDR", "IN_DEV", null, false),
                target("PDR", "POOLED", null), true);
        assertThat(ancestor.status()).isEqualTo(400);
        assertThat(ancestor.code()).isEqualTo(RequirementErrors.MERGE_CYCLE);

        ApiException self = violation(new Source(7L, "PDR", "IN_DEV", null, false),
                new Target(7L, "PDR", "POOLED", null), false);
        assertThat(self.code()).isEqualTo(RequirementErrors.MERGE_CYCLE);
    }

    @Test
    @DisplayName("终态集合是契约白名单，不能顺手扩容")
    void targetStatusWhitelistIsClosed() {
        assertThat(RequirementMergeRules.TARGET_STATUSES)
                .containsExactlyInAnyOrder("POOLED", "SCHEDULED", "IN_DEV");
    }

    @Test
    @DisplayName("错误不吞：check 是 void 且必抛，避免调用方误以为已校验")
    void checkNeverReturnsOnViolation() {
        assertThatThrownBy(() -> RequirementMergeRules.check(
                source("PDR", "IN_DEV", 9L, false), target("PDR", "POOLED", null), false))
                .isInstanceOf(ApiException.class);
    }
}
