package com.example.req.requirement;

import com.example.platform.common.security.PermissionRouteTable;
import com.example.platform.common.security.PermissionService;
import com.example.platform.contract.ActionPermissionResolver;
import com.example.platform.contract.ActionRegistry;
import com.example.platform.contract.MenuRegistry;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.PermissionMatrix.Access;
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
 * Contract tests for the r17 action matrix (AC-3 / AC-4 / AC-6), r22 signature.
 *
 * <p>The matrix is the only place that decides who may fire a requirement transition, and the
 * route table no longer stands in front of {@code POST /requirements/{id}/actions} — so these
 * assertions are what keeps the endpoint from silently opening up: a {@link StateTables} action
 * with no matrix row would otherwise be skipped (409) or, worse, allowed by a future default.</p>
 *
 * <p>r22 moved the <b>access level</b> of an action into {@code sys_role_permission} and left the
 * <b>relationship semantics</b> (own row, delegation, vendor ban) here, so every predicate call now
 * takes an {@link ActionPermissionResolver}. The stub below is not an arbitrary lambda: it derives
 * each {@code req.action.<action>} cell with the exact formula {@code RbacSeeder.preset} uses —
 * {@code accessForAny(role, ActionRegistry.require(code).functionCodes())} per role, merged with
 * {@link PermissionService#union} ("any explicit DENY wins"). That equivalence is what makes this
 * file a regression test rather than a test of its own stub: it stands for the seeded database, so
 * a wrong registry binding (the two rows r22 corrected) turns {@code submit_test} / {@code
 * start_dev} assertions red instead of silently re-defining the expectation.</p>
 *
 * <p>The page step is a no-op here because {@code req.pool} binds no function code: every role's
 * MENU cell for it is ALLOW, which {@link #everyActionIsRegisteredAndPartitioned} pins against
 * {@link MenuRegistry}. The page-first behaviour itself (a DENY page closing every action) can only
 * be observed through the real {@link PermissionService}; {@code RequirementActionMatrixWiringTest}
 * is the one that proves it.</p>
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
            String code = RequirementActionMatrix.actionCode(action);
            assertThat(code).as("%s 必须映射到自己的动作码", action).isNotNull();
            assertThat(code).as("%s 的动作码前缀", action)
                    .isEqualTo(RequirementActionMatrix.ACTION_PREFIX + action);
            assertThat(ActionRegistry.isRegistered(code))
                    .as("%s 的动作码 %s 必须登记在 ActionRegistry（ACTION 格的种源）", action, code)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("16 动作 ↔ 注册表 ↔ 评审侧划分：动作码、所属页面、功能码绑定三者对齐")
    void everyActionIsRegisteredAndPartitioned() {
        // The review side is declared twice on purpose: here and inside the matrix. Neither may
        // derive it from the registry — "which actions are review-side" is relationship semantics,
        // and one registry edit must not silently move the vendor boundary. A disagreement between
        // the two literal sets turns this assertion red, which is the point.
        List<String> reviewSide = List.of("approve", "reject", "review_start", "review_pass",
                "review_reject", "accept", "close");

        assertThat(MenuRegistry.isRegistered(RequirementActionMatrix.PAGE))
                .as("页面码 %s 必须是已登记页面", RequirementActionMatrix.PAGE)
                .isTrue();
        assertThat(MenuRegistry.page(RequirementActionMatrix.PAGE).orElseThrow().functionCodes())
                .as("req.pool 不绑定功能码，所以每个角色该页的 MENU 格都是 ALLOW —— 页面序对内置角色是空操作")
                .isEmpty();

        for (String action : RequirementActionMatrix.actions()) {
            String code = RequirementActionMatrix.actionCode(action);
            ActionRegistry.ActionDef def = ActionRegistry.require(code);
            assertThat(def.menuCode()).as("%s 的所属页面", action)
                    .isEqualTo(RequirementActionMatrix.PAGE);
            if (reviewSide.contains(action)) {
                assertThat(def.functionCodes()).as("%s 属于评审侧，只能绑评审功能码", action)
                        .containsExactly(RequirementActionMatrix.REVIEW);
            } else {
                assertThat(def.functionCodes()).as("%s 不属于评审侧，不得绑定评审功能码", action)
                        .doesNotContain(RequirementActionMatrix.REVIEW);
            }
        }

        // r22 的两条登记校正（TECH §3.3）：不校正就不是等价升级而是行为变化 ——
        // PRODUCT 对 req:split 是 RESTRICTED（沿用 = 扩权），QA 对 req:deliver 是 DENY（沿用 = 提测人不能提测）。
        assertThat(ActionRegistry.require("req.action.start_dev").functionCodes())
                .as("开始开发按 req:deliver 判定，不能沿用 req:split")
                .contains("req:deliver")
                .doesNotContain("req:split");
        assertThat(ActionRegistry.require("req.action.submit_test").functionCodes())
                .as("提测按 test:quick 判定，不能沿用 req:deliver")
                .contains("test:quick")
                .doesNotContain("req:deliver");

        // 页面码只有一个字面量的“真相”，四处引用必须一致：矩阵常量、页面表、注册表、路由表。
        assertThat(ActionRegistry.menus()).contains(RequirementActionMatrix.PAGE);
        assertThat(ActionRegistry.of(RequirementActionMatrix.PAGE))
                .as("16 个流转动作 + decompose/assign-vendor + r40 的两个变更动作（变更不是状态迁移）")
                .hasSize(20);
        PermissionRouteTable.Requirement rule =
                PermissionRouteTable.requirementFor("POST", "/requirements/7/actions");
        assertThat(rule).as("动作端点必须仍由路由表守住").isNotNull();
        assertThat(rule.pages()).containsExactly(RequirementActionMatrix.PAGE);
        assertThat(rule.bodyPrefix()).as("动作码来自请求体")
                .isEqualTo(RequirementActionMatrix.ACTION_PREFIX);
    }

    @Test
    @DisplayName("BUSINESS 提交自己的需求：放行（r17 的原始 bug）")
    void businessSubmitsOwnRequirement() {
        assertThat(can("submit", BIZ1, BIZ1_CALLER)).isTrue();
        assertThat(can("withdraw", BIZ1, BIZ1_CALLER)).isTrue();
        // 自服务不走评审授权，所以 BUSINESS 没有任何评审权限也照样能提交自己的
        // （r22 起仍要求动作格非 DENY，只是 BUSINESS 的 req:report:bus 本来就不是 DENY）。
        assertThat(PermissionMatrix.accessOf("req:review", BIZ1_CALLER.roles()))
                .isEqualTo(PermissionMatrix.Access.DENY);
    }

    @Test
    @DisplayName("BUSINESS 不能提交/撤回他人的需求")
    void businessCannotSubmitOthers() {
        assertThat(can("submit", PM1, BIZ1_CALLER)).isFalse();
        assertThat(can("withdraw", PM1, BIZ1_CALLER)).isFalse();
        // 其他无评审授权的采购方角色也一样：没有 approve 的 ALLOW 就不能代提交。
        assertThat(can("submit", DEV1, BIZ1_CALLER)).isFalse();
    }

    @Test
    @DisplayName("厂商账号即使持 req:review（PMO）也不能代他人提交/撤回")
    void vendorWithReviewRoleCannotSubmitForOthers() {
        // vendor1 的角色码就是 PMO —— 加权限码修不了，只有 vendorId 能区分。
        assertThat(PermissionMatrix.accessOf("req:review", VENDOR1_CALLER.roles()))
                .as("前提：vendor1 确实持全量 req:review")
                .isEqualTo(PermissionMatrix.Access.ALLOW);
        assertThat(can("submit", BIZ1, VENDOR1_CALLER)).isFalse();
        assertThat(can("withdraw", BIZ1, VENDOR1_CALLER)).isFalse();
        // 厂商仍可推进自己负责的交付动作（本次改动不得误伤）。
        assertThat(can("submit", VENDOR1, VENDOR1_CALLER)).isTrue();
    }

    @Test
    @DisplayName("厂商账号不得执行评审侧动作（BUG-1：vendor1 持 req:review 也不能 review_start 内部需求）")
    void vendorCannotFireReviewSideActions() {
        List<String> reviewSide = List.of("approve", "reject", "review_start", "review_pass",
                "review_reject", "accept", "close");
        for (String action : reviewSide) {
            assertThat(ActionRegistry.require(RequirementActionMatrix.actionCode(action))
                    .functionCodes())
                    .as("%s 属于评审侧，档位来自评审功能码", action)
                    .containsExactly(RequirementActionMatrix.REVIEW);
            assertThat(can(action, BIZ1, VENDOR1_CALLER))
                    .as("vendor1 不得对内部需求执行 %s", action)
                    .isFalse();
        }
        // 同一条谓词也驱动按钮列表：评审中的内部需求对厂商不再直出评审动作（AC-6）。
        assertThat(filter("BUS", "PENDING_REVIEW", BIZ1, VENDOR1_CALLER)).isEmpty();
        // 内部账号不受影响（含角色码同样是 PMO 的非厂商账号）。
        assertThat(can("review_start", BIZ1, PM_CALLER)).isTrue();
        assertThat(can("accept", BIZ1, PMO_CALLER)).isTrue();
        assertThat(can("close", BIZ1, PMO_CALLER)).isTrue();
        // 不得误伤厂商自己的自服务与交付动作（行范围仍由 DataScope 负责）。
        assertThat(can("submit", VENDOR1, VENDOR1_CALLER)).isTrue();
        assertThat(can("start_dev", BIZ1, VENDOR1_CALLER)).isTrue();
        assertThat(can("schedule", BIZ1, VENDOR1_CALLER)).isTrue();
    }

    @Test
    @DisplayName("非厂商的 PMO 可以代提交（AC-2 不回归）")
    void pmoWithoutVendorMaySubmitForOthers() {
        assertThat(can("submit", BIZ1, PMO_CALLER)).isTrue();
        assertThat(can("withdraw", BIZ1, PMO_CALLER)).isTrue();
        assertThat(can("submit", BIZ1, PM_CALLER)).isTrue();
    }

    @Test
    @DisplayName("评审 / 开发 / 测试动作各按自己的动作档位判定")
    void reviewAndDevAndTestFunctions() {
        // review side: req:review（ACTION 格由该功能码种出）
        assertThat(can("review_start", BIZ1, BIZ1_CALLER)).isFalse();
        assertThat(can("review_start", BIZ1, PM_CALLER)).isTrue();
        assertThat(can("review_pass", BIZ1, PM_CALLER)).isTrue();
        assertThat(can("review_pass", BIZ1, BIZ1_CALLER)).isFalse();
        assertThat(can("accept", BIZ1, BIZ1_CALLER)).isFalse();
        assertThat(can("close", BIZ1, BIZ1_CALLER)).isFalse();
        // delivery side: req:deliver
        assertThat(can("start_dev", BIZ1, DEV_CALLER)).isTrue();
        assertThat(can("start_dev", BIZ1, BIZ1_CALLER)).isFalse();
        assertThat(can("merge", BIZ1, DEV_CALLER)).isTrue();
        assertThat(can("finish", BIZ1, DEV_CALLER)).isTrue();
        assertThat(can("deliver", BIZ1, BIZ1_CALLER)).isFalse();
        // test side: test:quick
        assertThat(can("test_pass", BIZ1, QA_CALLER)).isTrue();
        assertThat(can("submit_test", BIZ1, QA_CALLER)).isTrue();
        assertThat(can("test_pass", BIZ1, BIZ1_CALLER)).isFalse();
        // planning side: release:plan（PMO 全量，DEV 只有 RESTRICTED —— 通过档位门，范围另由 DataScope 收窄）
        assertThat(can("schedule", BIZ1, PMO_CALLER)).isTrue();
        assertThat(can("schedule", BIZ1, BIZ1_CALLER)).isFalse();
    }

    @Test
    @DisplayName("allowedActions（filter）与执行鉴权（can）同一口径")
    void filterMatchesCan() {
        // 具体口径（AC-6 的量化断言）
        assertThat(filter("BUS", "SUBMITTED", BIZ1, BIZ1_CALLER)).containsExactly("submit");
        assertThat(filter("BUS", "SUBMITTED", BIZ1, VENDOR1_CALLER)).isEmpty();
        assertThat(filter("BUS", "SUBMITTED", BIZ1, PM_CALLER)).containsExactly("submit");
        assertThat(filter("BUS", "PENDING_REVIEW", BIZ1, BIZ1_CALLER)).isEmpty();
        assertThat(filter("BUS", "PENDING_REVIEW", BIZ1, PM_CALLER))
                .containsExactly("review_start", "review_pass", "review_reject", "close");
        assertThat(filter("RD", "IN_DEV", BIZ1, DEV_CALLER)).containsExactly("submit_test");
        assertThat(filter("RD", "IN_DEV", BIZ1, BIZ1_CALLER)).isEmpty();
        assertThat(filter("BUS", "CLOSED", BIZ1, PM_CALLER)).isEmpty();
        assertThat(filter("BUS", "NOT_A_STATUS", BIZ1, PM_CALLER)).isEmpty();
        assertThat(filter("NOPE", "SUBMITTED", BIZ1, PM_CALLER)).isEmpty();

        // 全表一致性：filter 必须是「状态机动作 ∩ can」，不能有第二条判据。
        List<Caller> callers = List.of(BIZ1_CALLER, PM_CALLER, PMO_CALLER, DEV_CALLER,
                QA_CALLER, VENDOR1_CALLER);
        for (String type : List.of(StateTables.BUS, StateTables.PDR, StateTables.RD)) {
            for (String status : StateTables.statuses(type)) {
                for (Caller caller : callers) {
                    List<String> expected = new ArrayList<>();
                    for (String action : StateTables.actions(type, status)) {
                        if (can(action, BIZ1, caller)) {
                            expected.add(action);
                        }
                    }
                    assertThat(filter(type, status, BIZ1, caller))
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
        assertThat(can("bogus", BIZ1, PM_CALLER)).isFalse();
        assertThat(can("decompose", BIZ1, PM_CALLER)).isFalse();
        assertThat(RequirementActionMatrix.actionCode("bogus")).isNull();
        assertThat(RequirementActionMatrix.actionCode(null)).isNull();
        // 空调用者 → fail-closed；空 resolver 也 fail-closed（端口约定非 null，授权路径不信任它）。
        assertThat(RequirementActionMatrix.can("submit", BIZ1, null, resolver(List.of()))).isFalse();
        assertThat(RequirementActionMatrix.can("submit", BIZ1, BIZ1_CALLER, null)).isFalse();
        // 无角色的调用者：本人提报也要过动作档位（r22 收紧），一格都没有 → 全 DENY
        assertThat(can("review_start", BIZ1, caller(BIZ1, null))).isFalse();
        assertThat(can("submit", PM1, caller(BIZ1, null))).isFalse();
        // 同一条 fail-closed 规则：没有角色时连自己的需求也提不了（MGMT 的现状，见 TECH §4）
        assertThat(can("submit", BIZ1, caller(BIZ1, null))).isFalse();
    }

    @Test
    @DisplayName("矩阵只向 resolver 问 req.pool + req.action.* —— 页面码与动作码都不是各写一份")
    void everyDecisionAsksTheRequirementPage() {
        List<String> menus = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        ActionPermissionResolver recording = (menuCode, actionCode) -> {
            menus.add(menuCode);
            codes.add(actionCode);
            return Access.ALLOW;
        };

        for (String type : List.of(StateTables.BUS, StateTables.PDR, StateTables.RD)) {
            for (String status : StateTables.statuses(type)) {
                RequirementActionMatrix.filter(type, status, BIZ1, PM_CALLER, recording);
            }
        }
        assertThat(menus).as("每次判定都先问同一个页面").isNotEmpty()
                .containsOnly(RequirementActionMatrix.PAGE);
        assertThat(codes).as("动作码一律带 req.action. 前缀").isNotEmpty()
                .allMatch(code -> code.startsWith(RequirementActionMatrix.ACTION_PREFIX));
        assertThat(codes).containsAll(RequirementActionMatrix.actions().stream()
                .map(RequirementActionMatrix::actionCode)
                .toList());
    }

    /** {@code can} with a stub resolver derived from the caller's own roles. */
    private static boolean can(String action, Long reporterId, Caller caller) {
        return RequirementActionMatrix.can(action, reporterId, caller,
                resolver(caller == null ? List.of() : caller.roles()));
    }

    /** {@code filter} with the same stub, so the two predicates cannot be stubbed differently. */
    private static List<String> filter(String type, String status, Long reporterId, Caller caller) {
        return RequirementActionMatrix.filter(type, status, reporterId, caller,
                resolver(caller == null ? List.of() : caller.roles()));
    }

    /**
     * The {@code req.action.<code>} cell for a role set, derived exactly the way
     * {@code RbacSeeder.preset} derives it: the any-of union over the action's function codes,
     * merged across roles with {@link PermissionService#union} ("any explicit DENY wins").
     *
     * <p>Only the seed formula is reproduced here — never a second copy of the decision rule.</p>
     */
    private static Access cell(String actionCode, List<String> roles) {
        if (actionCode == null || roles == null || roles.isEmpty()) {
            return Access.DENY;
        }
        List<String> functionCodes = ActionRegistry.require(actionCode).functionCodes();
        Access merged = null;
        for (String role : roles) {
            Access value = PermissionMatrix.accessForAny(role, functionCodes);
            merged = merged == null ? value : PermissionService.union(merged, value);
        }
        return merged == null ? Access.DENY : merged;
    }

    /**
     * Stub resolver. The page step is implied rather than implemented: {@code req.pool} binds no
     * function code, so its MENU cell is ALLOW for every role (asserted in
     * {@link #everyActionIsRegisteredAndPartitioned}); a blank action is the page-only decision.
     */
    private static ActionPermissionResolver resolver(List<String> roles) {
        return (menuCode, actionCode) -> actionCode == null || actionCode.isBlank()
                ? Access.ALLOW
                : cell(actionCode, roles);
    }

    private static Caller caller(long userId, Long vendorId, String... roles) {
        return new Caller(userId, List.of(roles), vendorId);
    }
}
