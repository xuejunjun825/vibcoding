package com.example.req.requirement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequirementStateMachineTest {

    private final RequirementStateMachine stateMachine = new RequirementStateMachine();

    @Test
    void initialStatusPerType() {
        assertThat(stateMachine.initialStatus("BUS")).isEqualTo("SUBMITTED");
        assertThat(stateMachine.initialStatus("PDR")).isEqualTo("PENDING_ANALYSIS");
        assertThat(stateMachine.initialStatus("RD")).isEqualTo("TODO");
    }

    @Test
    void rdHappyPathFromContractTable() {
        assertThat(stateMachine.target("RD", "TODO", "start_dev")).isEqualTo("IN_DEV");
        assertThat(stateMachine.target("RD", "IN_DEV", "submit_test")).isEqualTo("PENDING_TEST");
        assertThat(stateMachine.target("RD", "PENDING_TEST", "test_pass")).isEqualTo("TEST_PASSED");
        assertThat(stateMachine.target("RD", "TEST_PASSED", "merge")).isEqualTo("MERGED");
        assertThat(stateMachine.target("RD", "MERGED", "finish")).isEqualTo("DONE");
        assertThat(stateMachine.isTerminal("RD", "DONE")).isTrue();
    }

    @Test
    void busHappyPath() {
        assertThat(stateMachine.target("BUS", "SUBMITTED", "submit")).isEqualTo("APPROVING");
        assertThat(stateMachine.target("BUS", "APPROVING", "approve")).isEqualTo("PENDING_REVIEW");
        // review_start 是发起 REQ_REVIEW 审批的入口动作（D-10）：只建实例、状态不变
        assertThat(stateMachine.target("BUS", "PENDING_REVIEW", "review_start")).isEqualTo("PENDING_REVIEW");
        assertThat(stateMachine.allowedActions("BUS", "PENDING_REVIEW"))
                .contains("review_start", "review_pass");
        assertThat(stateMachine.target("BUS", "PENDING_REVIEW", "review_pass")).isEqualTo("POOLED");
        assertThat(stateMachine.target("BUS", "POOLED", "close")).isEqualTo("CLOSED");
    }

    @Test
    void illegalActionIsRejected() {
        assertThatThrownBy(() -> stateMachine.target("RD", "TODO", "merge"))
                .isInstanceOf(RuntimeException.class);
        assertThat(stateMachine.allowedActions("RD", "TODO")).contains("start_dev");
        assertThat(stateMachine.allowedActions("RD", "TODO")).doesNotContain("merge");
    }

    @Test
    void unknownStatusHasNoActions() {
        assertThat(stateMachine.allowedActions("RD", "NOT_A_STATUS")).isEmpty();
        assertThat(stateMachine.isTerminal("RD", "NOT_A_STATUS")).isFalse();
    }

    /**
     * 端到端（模型层）：每条状态链都必须能从初始态走到终止态，
     * 且终止态不再有可执行动作——否则单据会卡在半路，AC-3 的“闭环”就是假的。
     */
    @Test
    void everyTypeReachesTerminalWithoutGettingStuck() {
        assertLifecycle("BUS", "submit", "approve", "review_pass", "close");
        assertLifecycle("PDR", "schedule", "start_dev", "deliver", "accept");
        assertLifecycle("RD", "start_dev", "submit_test", "test_pass", "merge", "finish");
    }

    private void assertLifecycle(String type, String... actions) {
        String status = stateMachine.initialStatus(type);
        for (String action : actions) {
            assertThat(stateMachine.allowedActions(type, status))
                    .as("%s 在 %s 应允许 %s", type, status, action)
                    .contains(action);
            status = stateMachine.target(type, status, action);
        }
        assertThat(stateMachine.isTerminal(type, status))
                .as("%s 走完 %s 应到终止态，实际 %s", type, String.join("->", actions), status)
                .isTrue();
        assertThat(stateMachine.allowedActions(type, status)).isEmpty();
    }
}
