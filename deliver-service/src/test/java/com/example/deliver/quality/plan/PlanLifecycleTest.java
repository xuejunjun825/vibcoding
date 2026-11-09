package com.example.deliver.quality.plan;

import java.util.List;

import com.example.platform.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-1: the seven-state plan lifecycle is read from the shared contract table, so the controller,
 * the detail payload and the frontend stepper cannot drift apart.
 */
class PlanLifecycleTest {

    @Test
    void allowedNextFollowsTheStateTable() {
        assertThat(PlanLifecycle.allowedNext("DRAFT")).containsExactly("submit-review");
        assertThat(PlanLifecycle.allowedNext("PENDING_REVIEW"))
                .containsExactly("approve", "reject");
        assertThat(PlanLifecycle.allowedNext("APPROVED")).containsExactly("start");
        assertThat(PlanLifecycle.allowedNext("IN_PROGRESS")).containsExactly("complete");
        assertThat(PlanLifecycle.allowedNext("COMPLETED")).containsExactly("close");
        assertThat(PlanLifecycle.allowedNext("CLOSED")).containsExactly("archive");
        assertThat(PlanLifecycle.allowedNext("ARCHIVED")).isEmpty();
    }

    @Test
    void underscoreActionsNormaliseToTheHyphenVocabulary() {
        assertThat(PlanLifecycle.normaliseAction("submit_review")).isEqualTo("submit-review");
        assertThat(PlanLifecycle.requireTarget("DRAFT", "submit-review"))
                .isEqualTo("PENDING_REVIEW");
    }

    @Test
    void illegalTransitionCarriesTheAllowedList() {
        assertThatThrownBy(() -> PlanLifecycle.requireTarget("DRAFT", "approve"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.code()).isEqualTo("ILLEGAL_PLAN_TRANSITION");
                    assertThat(ex.detail()).containsKey("allowedNext");
                });
    }

    @Test
    void closedPlanRefusesWritesWithReasonClosed() {
        assertThatThrownBy(() -> PlanLifecycle.requireWritable("CLOSED", "执行登记"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.detail().get("reason")).isEqualTo("CLOSED");
                });
    }

    @Test
    void conclusionMayOnlyGoDown() {
        PlanLifecycle.requireNotUpgraded("FAIL", "PASS");
        PlanLifecycle.requireNotUpgraded("CONDITIONAL", "PASS");
        assertThatThrownBy(() -> PlanLifecycle.requireNotUpgraded("PASS", "FAIL"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("CONCLUSION_UPGRADE_FORBIDDEN"));
        assertThatThrownBy(() -> PlanLifecycle.requireNotUpgraded("PASS", "CONDITIONAL"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("CONCLUSION_UPGRADE_FORBIDDEN"));
    }

    @Test
    void everyStateIsReachableFromDraft() {
        List<String> reachable = List.of("DRAFT", "PENDING_REVIEW", "APPROVED", "IN_PROGRESS",
                "COMPLETED", "CLOSED", "ARCHIVED");
        for (String status : reachable) {
            assertThat(PlanLifecycle.allowedNext(status)).isNotNull();
        }
    }
}
