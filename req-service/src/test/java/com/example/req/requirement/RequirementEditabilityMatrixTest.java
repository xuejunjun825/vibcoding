package com.example.req.requirement;

import com.example.platform.contract.RequirementEditability;
import com.example.platform.contract.RequirementEditability.Decision;
import com.example.platform.contract.StateTables;
import com.example.req.requirement.change.RequirementChangeFields;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-1/2/3/4：可编辑性矩阵的全表契约。
 *
 * <p>矩阵是枚举型契约，抽样断言挡不住「新增状态漏配」「某个字段在某个状态静默放行」这两类
 * 缺陷——所以这里对 {@link StateTables#statuses} × {@link RequirementEditability#FIELDS}
 * 做全表遍历，而不是挑几个代表状态。三组对照（AC-2）只是全表断言之上的可读性锚点。</p>
 *
 * <p>判定顺序也在这里被钉住：终态改 {@code type} 必须是 {@code IMMUTABLE} 而不是
 * {@code TERMINAL}，BUS 改人天必须报 {@code PARENT_ROLLUP} 而不是 {@code NO_PARENT}。
 * 顺序调换只改一行代码，却会让前端文案与 409 的 reasonCode 全部漂移。</p>
 */
class RequirementEditabilityMatrixTest {

    private static final List<String> TYPES =
            List.of(StateTables.BUS, StateTables.PDR, StateTables.RD);

    /**
     * 终态没有出边，因此不在 {@code StateTables.statuses(type)} 的行里；矩阵仍必须覆盖它们
     * （AC-2/3/4 都要求终态报 {@code TERMINAL}），所以测试把终态补进遍历集合。
     */
    private static final Map<String, String> TERMINAL_STATES = Map.of(
            StateTables.BUS, "CLOSED",
            StateTables.PDR, "ACCEPTED_CLOSED",
            StateTables.RD, "DONE");

    private static final Set<String> REASON_CODES = Set.of(
            RequirementEditability.EARLY_STAGE,
            RequirementEditability.OPEN_STATE,
            RequirementEditability.CHANGE_REQUIRED,
            RequirementEditability.TERMINAL,
            RequirementEditability.PARENT_ROLLUP,
            RequirementEditability.NO_PARENT,
            RequirementEditability.ASSIGN_FLOW,
            RequirementEditability.IMMUTABLE,
            RequirementEditability.UNKNOWN_STATUS);

    @Test
    @DisplayName("FIELDS 是 11 个字段的规范序")
    void fieldsAreCanonicalElevenInOrder() {
        assertThat(RequirementEditability.FIELDS).containsExactly(
                "title", "description", "priority", "expectedDeliveryDate",
                "estimateDays", "parentId", "releaseId",
                "domainIds", "sonarProjectKey", "ownerUserId", "type");
        assertThat(RequirementEditability.FIELDS).hasSize(11).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("每个类型的每个状态、每个字段都有明确决策与原因码")
    void everyFieldDecidedForAllStates() {
        for (String type : TYPES) {
            List<String> statuses = allStates(type);
            assertThat(statuses).as("类型 %s 必须至少有状态", type).isNotEmpty();
            for (String status : statuses) {
                List<RequirementEditability.Rule> rules =
                        RequirementEditability.rules(type, status);
                assertThat(rules).as("%s/%s 规则数", type, status)
                        .hasSize(RequirementEditability.FIELDS.size());
                for (int i = 0; i < rules.size(); i++) {
                    RequirementEditability.Rule rule = rules.get(i);
                    String where = type + "/" + status + "/" + rule.field();
                    assertThat(rule.field()).as("%s 字段序", where)
                            .isEqualTo(RequirementEditability.FIELDS.get(i));
                    assertThat(rule.decision()).as("%s 决策", where).isNotNull();
                    assertThat(REASON_CODES).as("%s 原因码 %s", where, rule.reasonCode())
                            .contains(rule.reasonCode());
                    // 单字段入口必须与批量入口一致，否则 PUT 与详情会各判一套。
                    assertThat(RequirementEditability.rule(type, status, rule.field()))
                            .as("%s 单字段入口", where).isEqualTo(rule);
                    assertThat(RequirementEditability.decision(type, status, rule.field()))
                            .as("%s decision()", where).isEqualTo(rule.decision());
                    assertThat(RequirementEditability.reasonCode(type, status, rule.field()))
                            .as("%s reasonCode()", where).isEqualTo(rule.reasonCode());
                }
            }
        }
    }

    @Test
    @DisplayName("三数组互斥、并集为 11 字段、各自保持规范序")
    void partitionsAreDisjointAndCompleteAndOrdered() {
        for (String type : TYPES) {
            for (String status : allStates(type)) {
                String where = type + "/" + status;
                List<String> editable = RequirementEditability.editableFields(type, status);
                List<String> change = RequirementEditability.changeRequiredFields(type, status);
                List<String> locked = RequirementEditability.lockedFields(type, status);

                assertSubsequence(editable, where + " editableFields");
                assertSubsequence(change, where + " changeRequiredFields");
                assertSubsequence(locked, where + " lockedFields");

                Set<String> union = new LinkedHashSet<>(editable);
                union.addAll(change);
                union.addAll(locked);
                assertThat(union).as("%s 并集", where)
                        .containsExactlyInAnyOrderElementsOf(RequirementEditability.FIELDS);
                assertThat(editable.size() + change.size() + locked.size())
                        .as("%s 两两不交（总数应为 11）", where).isEqualTo(11);

                for (String field : RequirementEditability.FIELDS) {
                    Decision decision = RequirementEditability.decision(type, status, field);
                    assertThat(editable.contains(field)).as("%s/%s EDIT 归属", where, field)
                            .isEqualTo(decision == Decision.EDIT);
                    assertThat(change.contains(field)).as("%s/%s CHANGE_REQUIRED 归属", where, field)
                            .isEqualTo(decision == Decision.CHANGE_REQUIRED);
                    assertThat(locked.contains(field)).as("%s/%s LOCKED 归属", where, field)
                            .isEqualTo(decision == Decision.LOCKED);
                }
            }
        }
    }

    @Test
    @DisplayName("AC-2 三类型三组对照（以 title 为被测字段）")
    void threeGroupsForContentField() {
        for (String status : List.of("SUBMITTED", "APPROVING", "PENDING_REVIEW")) {
            assertRule(StateTables.BUS, status, "title", Decision.EDIT, RequirementEditability.EARLY_STAGE);
        }
        assertRule(StateTables.BUS, "POOLED", "title", Decision.CHANGE_REQUIRED, RequirementEditability.CHANGE_REQUIRED);
        assertRule(StateTables.BUS, "CLOSED", "title", Decision.LOCKED, RequirementEditability.TERMINAL);

        assertRule(StateTables.PDR, "PENDING_ANALYSIS", "title", Decision.EDIT, RequirementEditability.EARLY_STAGE);
        for (String status : List.of("SCHEDULED", "IN_DEV", "DELIVERED")) {
            assertRule(StateTables.PDR, status, "title", Decision.CHANGE_REQUIRED, RequirementEditability.CHANGE_REQUIRED);
        }
        assertRule(StateTables.PDR, "ACCEPTED_CLOSED", "title", Decision.LOCKED, RequirementEditability.TERMINAL);

        assertRule(StateTables.RD, "TODO", "title", Decision.EDIT, RequirementEditability.EARLY_STAGE);
        for (String status : List.of("IN_DEV", "PENDING_TEST", "TEST_PASSED", "MERGED")) {
            assertRule(StateTables.RD, status, "title", Decision.CHANGE_REQUIRED, RequirementEditability.CHANGE_REQUIRED);
        }
        assertRule(StateTables.RD, "DONE", "title", Decision.LOCKED, RequirementEditability.TERMINAL);
    }

    @Test
    @DisplayName("AC-4 type 在任何类型任何状态都不可变（终态也优先报 IMMUTABLE）")
    void typeIsImmutableEverywhere() {
        for (String type : TYPES) {
            for (String status : allStates(type)) {
                assertRule(type, status, "type", Decision.LOCKED, RequirementEditability.IMMUTABLE);
            }
        }
    }

    @Test
    @DisplayName("AC-4 BUS 的 parentId 恒为 NO_PARENT，终态收敛为 TERMINAL")
    void busParentIsNoParent() {
        for (String status : StateTables.statuses(StateTables.BUS)) {
            if (StateTables.isTerminal(StateTables.BUS, status)) {
                assertRule(StateTables.BUS, status, "parentId", Decision.LOCKED, RequirementEditability.TERMINAL);
            } else {
                assertRule(StateTables.BUS, status, "parentId", Decision.LOCKED, RequirementEditability.NO_PARENT);
            }
        }
    }

    @Test
    @DisplayName("AC-3 人天：非 RD 恒为父汇总，RD 按早期/受控/终态三分")
    void estimateDaysFollowsRollup() {
        for (String type : List.of(StateTables.BUS, StateTables.PDR)) {
            for (String status : allStates(type)) {
                if (StateTables.isTerminal(type, status)) {
                    assertRule(type, status, "estimateDays", Decision.LOCKED, RequirementEditability.TERMINAL);
                } else {
                    assertRule(type, status, "estimateDays", Decision.LOCKED, RequirementEditability.PARENT_ROLLUP);
                }
            }
        }
        assertRule(StateTables.RD, "TODO", "estimateDays", Decision.EDIT, RequirementEditability.EARLY_STAGE);
        for (String status : List.of("IN_DEV", "PENDING_TEST", "TEST_PASSED", "MERGED")) {
            assertRule(StateTables.RD, status, "estimateDays", Decision.CHANGE_REQUIRED, RequirementEditability.CHANGE_REQUIRED);
        }
        assertRule(StateTables.RD, "DONE", "estimateDays", Decision.LOCKED, RequirementEditability.TERMINAL);
    }

    @Test
    @DisplayName("AC-4 域/技术字段非终态恒 EDIT/OPEN_STATE（早期与受控态均可直改）")
    void domainAndSonarStayOpenUntilTerminal() {
        for (String type : TYPES) {
            for (String status : allStates(type)) {
                for (String field : List.of("domainIds", "sonarProjectKey")) {
                    if (StateTables.isTerminal(type, status)) {
                        assertRule(type, status, field, Decision.LOCKED, RequirementEditability.TERMINAL);
                    } else {
                        assertRule(type, status, field, Decision.EDIT, RequirementEditability.OPEN_STATE);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("AC-4 ownerUserId 走指派流程：非终态 ASSIGN_FLOW，终态 TERMINAL")
    void ownerFollowsAssignFlow() {
        for (String type : TYPES) {
            for (String status : allStates(type)) {
                if (StateTables.isTerminal(type, status)) {
                    assertRule(type, status, "ownerUserId", Decision.LOCKED, RequirementEditability.TERMINAL);
                } else {
                    assertRule(type, status, "ownerUserId", Decision.LOCKED, RequirementEditability.ASSIGN_FLOW);
                }
            }
        }
    }

    @Test
    @DisplayName("AC-4 PDR/RD 的 parentId 早期可改、受控态走变更单、终态锁定")
    void parentFollowsEarlyStageOutsideBus() {
        assertRule(StateTables.PDR, "PENDING_ANALYSIS", "parentId", Decision.EDIT, RequirementEditability.EARLY_STAGE);
        for (String status : List.of("SCHEDULED", "IN_DEV", "DELIVERED")) {
            assertRule(StateTables.PDR, status, "parentId", Decision.CHANGE_REQUIRED, RequirementEditability.CHANGE_REQUIRED);
        }
        assertRule(StateTables.PDR, "ACCEPTED_CLOSED", "parentId", Decision.LOCKED, RequirementEditability.TERMINAL);

        assertRule(StateTables.RD, "TODO", "parentId", Decision.EDIT, RequirementEditability.EARLY_STAGE);
        for (String status : List.of("IN_DEV", "PENDING_TEST", "TEST_PASSED", "MERGED")) {
            assertRule(StateTables.RD, status, "parentId", Decision.CHANGE_REQUIRED, RequirementEditability.CHANGE_REQUIRED);
        }
        assertRule(StateTables.RD, "DONE", "parentId", Decision.LOCKED, RequirementEditability.TERMINAL);
    }

    @Test
    @DisplayName("fail-closed：未知类型/状态/空值 → 11 字段全 LOCKED/UNKNOWN_STATUS")
    void unknownStateIsLockedEverywhere() {
        List<String[]> unknowns = List.of(
                new String[]{"BOGUS", "SUBMITTED"},
                new String[]{StateTables.BUS, "BOGUS"},
                new String[]{StateTables.DEFECT, "NEW"},
                new String[]{null, null},
                new String[]{StateTables.BUS, null});
        for (String[] unknown : unknowns) {
            String type = unknown[0];
            String status = unknown[1];
            String where = type + "/" + status;
            List<RequirementEditability.Rule> rules = RequirementEditability.rules(type, status);
            assertThat(rules).as("%s 规则数", where).hasSize(11);
            for (RequirementEditability.Rule rule : rules) {
                assertThat(rule.decision()).as("%s/%s 决策", where, rule.field())
                        .isEqualTo(Decision.LOCKED);
                assertThat(rule.reasonCode()).as("%s/%s 原因码", where, rule.field())
                        .isEqualTo(RequirementEditability.UNKNOWN_STATUS);
            }
            assertThat(RequirementEditability.editableFields(type, status)).as("%s EDIT", where).isEmpty();
            assertThat(RequirementEditability.changeRequiredFields(type, status)).as("%s CHANGE", where).isEmpty();
            assertThat(RequirementEditability.lockedFields(type, status)).as("%s LOCKED", where)
                    .containsExactlyElementsOf(RequirementEditability.FIELDS);
        }
    }

    @Test
    @DisplayName("未知字段名返回 LOCKED/UNKNOWN_STATUS，不抛异常（PUT 不得 500）")
    void unknownFieldIsLockedNotThrown() {
        RequirementEditability.Rule rule =
                RequirementEditability.rule(StateTables.RD, "TODO", "noSuchField");
        assertThat(rule.decision()).isEqualTo(Decision.LOCKED);
        assertThat(rule.reasonCode()).isEqualTo(RequirementEditability.UNKNOWN_STATUS);
        assertThat(rule.field()).isEqualTo("noSuchField");
    }

    @Test
    @DisplayName("earlyStage 与矩阵一致，未知状态一律 false")
    void earlyStageMatchesMatrix() {
        for (String status : List.of("SUBMITTED", "APPROVING", "PENDING_REVIEW")) {
            assertThat(RequirementEditability.earlyStage(StateTables.BUS, status)).as(status).isTrue();
        }
        assertThat(RequirementEditability.earlyStage(StateTables.BUS, "POOLED")).isFalse();
        assertThat(RequirementEditability.earlyStage(StateTables.BUS, "CLOSED")).isFalse();
        assertThat(RequirementEditability.earlyStage(StateTables.PDR, "PENDING_ANALYSIS")).isTrue();
        assertThat(RequirementEditability.earlyStage(StateTables.PDR, "SCHEDULED")).isFalse();
        assertThat(RequirementEditability.earlyStage(StateTables.RD, "TODO")).isTrue();
        assertThat(RequirementEditability.earlyStage(StateTables.RD, "IN_DEV")).isFalse();
        assertThat(RequirementEditability.earlyStage("BOGUS", "TODO")).isFalse();
        assertThat(RequirementEditability.earlyStage(null, null)).isFalse();
    }

    @Test
    @DisplayName("幂等：同一 (type,status) 重复调用逐字段相等")
    void repeatedCallsAreIdentical() {
        for (String type : TYPES) {
            for (String status : allStates(type)) {
                assertThat(RequirementEditability.rules(type, status))
                        .as("%s/%s", type, status)
                        .isEqualTo(RequirementEditability.rules(type, status));
                assertThat(RequirementEditability.editableFields(type, status))
                        .isEqualTo(RequirementEditability.editableFields(type, status));
            }
        }
    }

    @Test
    @DisplayName("RequirementChangeFields 只委托矩阵：无第二份早期态表，7 字段投影保持 r40 语义")
    void changeFieldsOnlyDelegates() {
        assertThat(Arrays.stream(RequirementChangeFields.class.getDeclaredFields())
                .map(Field::getName))
                .as("r43 起早期态表只允许存在于 platform-contract")
                .doesNotContain("EARLY_STATUSES");

        assertThat(RequirementChangeFields.earlyStage(StateTables.BUS, "SUBMITTED")).isTrue();
        assertThat(RequirementChangeFields.earlyStage(StateTables.BUS, "POOLED")).isFalse();
        assertThat(RequirementChangeFields.earlyStage(StateTables.RD, "TODO")).isTrue();
        assertThat(RequirementChangeFields.earlyStage("BOGUS", "TODO")).isFalse();
        assertThat(RequirementChangeFields.earlyStage(null, null)).isFalse();

        // BUS 早期：人天与父需求被矩阵锁住，兼容投影只剩 5 个内容/版本字段。
        List<String> busEarly = RequirementChangeFields.editableFields(StateTables.BUS, "SUBMITTED");
        assertThat(busEarly).containsExactly(
                "title", "description", "priority", "expectedDeliveryDate", "releaseId");

        // RD TODO：7 字段全部可直改，顺序沿用 RequirementChangeFields.FIELDS（r40 线上口径）。
        assertThat(RequirementChangeFields.editableFields(StateTables.RD, "TODO"))
                .containsExactlyElementsOf(RequirementChangeFields.FIELDS);
        assertThat(RequirementChangeFields.editableFields(StateTables.BUS, "POOLED")).isEmpty();
        assertThat(RequirementChangeFields.editableFields("BOGUS", "TODO")).isEmpty();
    }

    // ------------------------------------------------------------------ 辅助

    /** 该类型全部真实状态：有出边的状态 + 终态（终态没有出边，不在 statuses 行里）。 */
    private static List<String> allStates(String type) {
        List<String> states = new ArrayList<>(StateTables.statuses(type));
        String terminal = TERMINAL_STATES.get(type);
        if (terminal != null && !states.contains(terminal)) {
            states.add(terminal);
        }
        return List.copyOf(states);
    }

    private static void assertRule(String type, String status, String field,
                                   Decision decision, String reasonCode) {
        RequirementEditability.Rule rule = RequirementEditability.rule(type, status, field);
        String where = type + "/" + status + "/" + field;
        assertThat(rule.decision()).as("%s 决策", where).isEqualTo(decision);
        assertThat(rule.reasonCode()).as("%s 原因码", where).isEqualTo(reasonCode);
    }

    private static void assertSubsequence(List<String> fields, String where) {
        int index = -1;
        for (String field : new ArrayList<>(fields)) {
            int next = RequirementEditability.FIELDS.indexOf(field);
            assertThat(next).as("%s: %s 不在规范序内", where, field).isGreaterThan(index);
            index = next;
        }
    }
}
