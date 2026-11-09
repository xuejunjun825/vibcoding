package com.example.deliver.review;

import com.example.platform.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AC-14 的四类拒绝可脱库单测：意见长度、重复评审、过期版本、非评审人。
 * 这些规则是包内静态方法，就是为了不启 Spring 也能钉死错误码。
 */
class CaseReviewRulesTest {

    private static final String LONG_ENOUGH = "按钮在弱网下没反应，需要补重试";

    @Test
    @DisplayName("NEED_CHANGE 意见少于 10 字 → 400 CASE_REVIEW_COMMENT_REQUIRED")
    void commentTooShort() {
        ApiException ex = assertThrows(ApiException.class,
                () -> CaseReviewService.requireComment("NEED_CHANGE", "不行"));
        assertEquals(400, ex.status());
        assertEquals("CASE_REVIEW_COMMENT_REQUIRED", ex.code());
    }

    @Test
    @DisplayName("REJECT 完全没填意见同样 400")
    void commentMissing() {
        ApiException ex = assertThrows(ApiException.class,
                () -> CaseReviewService.requireComment("REJECT", "   "));
        assertEquals("CASE_REVIEW_COMMENT_REQUIRED", ex.code());
    }

    @Test
    @DisplayName("PASS 允许不填意见")
    void passNeedsNoComment() {
        assertDoesNotThrow(() -> CaseReviewService.requireComment("PASS", null));
    }

    @Test
    @DisplayName("意见超过 2000 字 → 400 CASE_REVIEW_COMMENT_TOO_LONG")
    void commentTooLong() {
        ApiException ex = assertThrows(ApiException.class,
                () -> CaseReviewService.requireComment("PASS", "字".repeat(2001)));
        assertEquals("CASE_REVIEW_COMMENT_TOO_LONG", ex.code());
    }

    @Test
    @DisplayName("同用例同版本已有有效评审 → 409 CASE_REVIEW_DUPLICATE")
    void duplicateReview() {
        ApiException ex = assertThrows(ApiException.class,
                () -> CaseReviewService.requireNoActiveReview(42L));
        assertEquals(409, ex.status());
        assertEquals("CASE_REVIEW_DUPLICATE", ex.code());
        assertDoesNotThrow(() -> CaseReviewService.requireNoActiveReview(null));
    }

    @Test
    @DisplayName("评审 v1 而用例已是 v2 → 409 CASE_REVIEW_VERSION_STALE")
    void versionStale() {
        ApiException ex = assertThrows(ApiException.class,
                () -> CaseReviewService.requireFreshVersion(1, 2));
        assertEquals(409, ex.status());
        assertEquals("CASE_REVIEW_VERSION_STALE", ex.code());
    }

    @Test
    @DisplayName("别人不能替评审人下结论 → 403 CASE_REVIEW_NOT_REVIEWER")
    void notReviewer() {
        ApiException ex = assertThrows(ApiException.class,
                () -> CaseReviewService.requireReviewer(7L, 9L));
        assertEquals(403, ex.status());
        assertEquals("CASE_REVIEW_NOT_REVIEWER", ex.code());
        assertDoesNotThrow(() -> CaseReviewService.requireReviewer(7L, 7L));
    }

    @Test
    @DisplayName("已过期/已完成的评审不能再下结论")
    void terminalStates() {
        assertEquals("CASE_REVIEW_INVALIDATED", assertThrows(ApiException.class,
                () -> CaseReviewService.requireOpenReview("INVALIDATED", "NEW_VERSION")).code());
        assertEquals("CASE_REVIEW_DECIDED", assertThrows(ApiException.class,
                () -> CaseReviewService.requireOpenReview("DONE", null)).code());
        assertDoesNotThrow(() -> CaseReviewService.requireOpenReview("SUBMITTED", null));
    }

    @Test
    @DisplayName("结论只接受 PASS/NEED_CHANGE/REJECT，大小写归一")
    void conclusionNormalisation() {
        assertEquals("PASS", CaseReviewService.normaliseConclusion(" pass "));
        assertEquals("NEED_CHANGE", CaseReviewService.normaliseConclusion("NEED_CHANGE"));
        assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class,
                () -> CaseReviewService.normaliseConclusion("LGTM")).code());
        assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class,
                () -> CaseReviewService.normaliseConclusion(null)).code());
    }

    @Test
    @DisplayName("合格意见与版本一致时全部放行")
    void happyPathRules() {
        assertDoesNotThrow(() -> CaseReviewService.requireComment("NEED_CHANGE", LONG_ENOUGH));
        assertDoesNotThrow(() -> CaseReviewService.requireFreshVersion(3, 3));
    }
}
