package com.example.req.requirement;

import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.RequirementActionMatrix;
import com.example.platform.contract.RequirementActionMatrix.Caller;
import com.example.platform.contract.StateTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the r17 action matrix (AC-3 / AC-4 / AC-6).
 *
 * <p>The matrix is the only place that decides who may fire a requirement transition, and the
 * route table no longer stands in front of {@code POST /requirements/{id}/actions} — so these
 * assertions are what keeps the endpoint from silently opening up: a {@link StateTables} action
 * with no matrix row would otherwise be skipped (409) or, worse, allowed by a future default.</p>
 *
 * <p>Lives in {@code req-service} on the {@code PermissionMatrixGridTest} precedent: the two
 * contract modules carry no {@code src/test}, and adding test scaffolding there would mean
 * changing poms for no gain.</p>
 */
class RequirementActionMatrixTest {

    // Seeded ids used by the demo fixtures: biz1 reports, pm reviews, dev1/qa1 deliver, vendor1 is
    // a vendor account whose role code is literally PMO (that collision is the point of test 4).
    private static final long BIZ1 = 2L;
    private static final long PM1 = 9L;
    private static final long DEV1 = 12L;
    private static final long QA1 = 15L;
    private static final long VENDOR1 = 30L;
    private static final long VENDOR_ID = 1L;

    private static final Caller BIZ1_CALLER = caller(BIZ1, null, "BUSINESS");
    private static final Caller PM_CALLER = caller(PM1, null, "PRODUCT");
    private static final Caller PMO_CALLER = caller(4L, null, "PMO");
    private static final Caller DEV_CALLER = caller(DEV1, null, "DEV");
    private static final Caller QA_CALLER = caller(QA1, null, "QA");
    private static final Caller VENDOR1_CALLER = caller(VENDOR1, VENDOR_ID, "PMO");

    @Test
    @DisplayName("every StateTables action has exactly one matrix row — both directions")
    void everyStateActionHasARow() {
        Set<String> stateActions = new LinkedHashSet<>();
        for (String type : List.of(StateTables.BUS, StateTables.PDR, StateTables.RD)) {
            for (String status : StateTables.statuses(type)) {
                stateActions.addAll(StateTables.actions(type, status));
            }
        }

        assertThat(stateActions)
                .as("状态机里的动作必须全部登记到权限矩阵，反之亦然")
                .isEqualTo(new LinkedHashSet<>(RequirementActionMatrix.actions()));
        assertThat(RequirementActionMatrix.actions())
                .as("16 个流转动作为 r17 的契约基数")
                .hasSize(16);
        assertThat(stateActions).hasSize(16);

        for (String action : RequirementActionMatrix.actions()) {
            assertThat(RequirementActionMatrix.known(action)).as("known(%s)", action).isTrue();
            String function = RequirementActionMatrix.requiredFunction(action);
            assertThat(function).as("%s 必须映射到真实权限码", action).isNotNull();
            assertThat(PermissionMatrix.functions())
                    .as("%s 的权限码 %s 必须存在于 PermissionMatrix", action, function)
                    .contains(function);
        }
    }

    @Test
    @DisplayName("BUSINESS 提交自己的需求：放行（r17 的原始 bug）")
    void businessSubmitsOwnRequirement() {
        assertThat(RequirementActionMatrix.can("submit", BIZ1, BIZ1_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("withdraw", BIZ1, BIZ1_CALLER)).isTrue();
        // 自服务不走 req:review，所以 BUSINESS 没有任何评审权限也照样能提交自己的。
        assertThat(PermissionMatrix.accessOf("req:review", BIZ1_CALLER.roles()))
                .isEqualTo(PermissionMatrix.Access.DENY);
    }

    @Test
    @DisplayName("BUSINESS 不能提交/撤回他人的需求")
    void businessCannotSubmitOthers() {
        assertThat(RequirementActionMatrix.can("submit", PM1, BIZ1_CALLER)).isFalse();
        assertThat(RequirementActionMatrix.can("withdraw", PM1, BIZ1_CALLER)).isFalse();
        // 采购方（无 vendorId）也一样：没有 req:review 就不能代提交。
        assertThat(RequirementActionMatrix.can("submit", DEV1, BIZ1_CALLER)).isFalse();
    }

    @Test
    @DisplayName("厂商账号即使持 req:review（PMO）也不能代他人提交/撤回")
    void vendorWithReviewRoleCannotSubmitForOthers() {
        // vendor1 的角色码就是 PMO —— 加权限码修不了，只有 vendorId 能区分。
        assertThat(PermissionMatrix.accessOf("req:review", VENDOR1_CALLER.roles()))
                .as("前提：vendor1 确实持全量 req:review")
                .isEqualTo(PermissionMatrix.Access.ALLOW);
        assertThat(RequirementActionMatrix.can("submit", BIZ1, VENDOR1_CALLER)).isFalse();
        assertThat(RequirementActionMatrix.can("withdraw", BIZ1, VENDOR1_CALLER)).isFalse();
        // 厂商仍可推进自己负责的交付动作（本次改动不得误伤）。
        assertThat(RequirementActionMatrix.can("submit", VENDOR1, VENDOR1_CALLER)).isTrue();
    }

    @Test
    @DisplayName("厂商账号不得执行评审侧动作（BUG-1：vendor1 持 req:review 也不能 review_start 内部需求）")
    void vendorCannotFireReviewSideActions() {
        List<String> reviewSide = List.of("approve", "reject", "review_start", "review_pass",
                "review_reject", "accept", "close");
        for (String action : reviewSide) {
            assertThat(RequirementActionMatrix.requiredFunction(action))
                    .as("%s 属于评审侧", action)
                    .isEqualTo(RequirementActionMatrix.REVIEW);
            assertThat(RequirementActionMatrix.can(action, BIZ1, VENDOR1_CALLER))
                    .as("vendor1 不得对内部需求执行 %s", action)
                    .isFalse();
        }
        // 同一条谓词也驱动按钮列表：评审中的内部需求对厂商不再直出评审动作（AC-6）。
        assertThat(RequirementActionMatrix.filter("BUS", "PENDING_REVIEW", BIZ1, VENDOR1_CALLER))
                .isEmpty();
        // 内部账号不受影响（含角色码同样是 PMO 的非厂商账号）。
        assertThat(RequirementActionMatrix.can("review_start", BIZ1, PM_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("accept", BIZ1, PMO_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("close", BIZ1, PMO_CALLER)).isTrue();
        // 不得误伤厂商自己的自服务与交付动作（行范围仍由 DataScope 负责）。
        assertThat(RequirementActionMatrix.can("submit", VENDOR1, VENDOR1_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("start_dev", BIZ1, VENDOR1_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("schedule", BIZ1, VENDOR1_CALLER)).isTrue();
    }

    @Test
    @DisplayName("非厂商的 PMO 可以代提交（AC-2 不回归）")
    void pmoWithoutVendorMaySubmitForOthers() {
        assertThat(RequirementActionMatrix.can("submit", BIZ1, PMO_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("withdraw", BIZ1, PMO_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("submit", BIZ1, PM_CALLER)).isTrue();
    }

    @Test
    @DisplayName("评审 / 开发 / 测试动作各按自己的权限码判定")
    void reviewAndDevAndTestFunctions() {
        // review side: req:review
        assertThat(RequirementActionMatrix.can("review_start", BIZ1, BIZ1_CALLER)).isFalse();
        assertThat(RequirementActionMatrix.can("review_start", BIZ1, PM_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("review_pass", BIZ1, PM_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("review_pass", BIZ1, BIZ1_CALLER)).isFalse();
        assertThat(RequirementActionMatrix.can("accept", BIZ1, BIZ1_CALLER)).isFalse();
        assertThat(RequirementActionMatrix.can("close", BIZ1, BIZ1_CALLER)).isFalse();
        // delivery side: req:deliver
        assertThat(RequirementActionMatrix.can("start_dev", BIZ1, DEV_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("start_dev", BIZ1, BIZ1_CALLER)).isFalse();
        assertThat(RequirementActionMatrix.can("merge", BIZ1, DEV_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("finish", BIZ1, DEV_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("deliver", BIZ1, BIZ1_CALLER)).isFalse();
        // test side: test:quick
        assertThat(RequirementActionMatrix.can("test_pass", BIZ1, QA_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("submit_test", BIZ1, QA_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("test_pass", BIZ1, BIZ1_CALLER)).isFalse();
        // planning side: release:plan（PMO 全量，DEV 只有 RESTRICTED —— 通过函数门，范围另由 DataScope 收窄）
        assertThat(RequirementActionMatrix.can("schedule", BIZ1, PMO_CALLER)).isTrue();
        assertThat(RequirementActionMatrix.can("schedule", BIZ1, BIZ1_CALLER)).isFalse();
    }

    @Test
    @DisplayName("allowedActions（filter）与执行鉴权（can）同一口径")
    void filterMatchesCan() {
        // 具体口径（AC-6 的量化断言）
        assertThat(RequirementActionMatrix.filter("BUS", "SUBMITTED", BIZ1, BIZ1_CALLER))
                .containsExactly("submit");
        assertThat(RequirementActionMatrix.filter("BUS", "SUBMITTED", BIZ1, VENDOR1_CALLER))
                .isEmpty();
        assertThat(RequirementActionMatrix.filter("BUS", "SUBMITTED", BIZ1, PM_CALLER))
                .containsExactly("submit");
        assertThat(RequirementActionMatrix.filter("BUS", "PENDING_REVIEW", BIZ1, BIZ1_CALLER))
                .isEmpty();
        assertThat(RequirementActionMatrix.filter("BUS", "PENDING_REVIEW", BIZ1, PM_CALLER))
                .containsExactly("review_start", "review_pass", "review_reject", "close");
        assertThat(RequirementActionMatrix.filter("RD", "IN_DEV", BIZ1, DEV_CALLER))
                .containsExactly("submit_test");
        assertThat(RequirementActionMatrix.filter("RD", "IN_DEV", BIZ1, BIZ1_CALLER)).isEmpty();
        assertThat(RequirementActionMatrix.filter("BUS", "CLOSED", BIZ1, PM_CALLER)).isEmpty();
        assertThat(RequirementActionMatrix.filter("BUS", "NOT_A_STATUS", BIZ1, PM_CALLER)).isEmpty();
        assertThat(RequirementActionMatrix.filter("NOPE", "SUBMITTED", BIZ1, PM_CALLER)).isEmpty();

        // 全表一致性：filter 必须是「状态机动作 ∩ can」，不能有第二条判据。
        List<Caller> callers = List.of(BIZ1_CALLER, PM_CALLER, PMO_CALLER, DEV_CALLER,
                QA_CALLER, VENDOR1_CALLER);
        for (String type : List.of(StateTables.BUS, StateTables.PDR, StateTables.RD)) {
            for (String status : StateTables.statuses(type)) {
                for (Caller caller : callers) {
                    List<String> expected = new ArrayList<>();
                    for (String action : StateTables.actions(type, status)) {
                        if (RequirementActionMatrix.can(action, BIZ1, caller)) {
                            expected.add(action);
                        }
                    }
                    assertThat(RequirementActionMatrix.filter(type, status, BIZ1, caller))
                            .as("%s/%s for %s", type, status, caller.roles())
                            .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    @DisplayName("未登记动作既不是 known 也不被 can 放行")
    void unknownActionIsUnknown() {
        assertThat(RequirementActionMatrix.known("bogus")).isFalse();
        assertThat(RequirementActionMatrix.known(null)).isFalse();
        assertThat(RequirementActionMatrix.known("decompose")).as("decompose 是独立端点，不进矩阵").isFalse();
        assertThat(RequirementActionMatrix.known("assign-vendor")).isFalse();
        assertThat(RequirementActionMatrix.can("bogus", BIZ1, PM_CALLER)).isFalse();
        assertThat(RequirementActionMatrix.can("decompose", BIZ1, PM_CALLER)).isFalse();
        assertThat(RequirementActionMatrix.requiredFunction("bogus")).isNull();
        assertThat(RequirementActionMatrix.requiredFunction(null)).isNull();
        // 空调用者 → fail-closed
        assertThat(RequirementActionMatrix.can("submit", BIZ1, null)).isFalse();
        // 空角色：自服务仍可（本人提报是关系，不是权限码），但需要权限码的动作一律 DENY
        assertThat(RequirementActionMatrix.can("review_start", BIZ1, caller(BIZ1, null))).isFalse();
        assertThat(RequirementActionMatrix.can("submit", PM1, caller(BIZ1, null))).isFalse();
    }

    private static Caller caller(long userId, Long vendorId, String... roles) {
        return new Caller(userId, List.of(roles), vendorId);
    }
}
