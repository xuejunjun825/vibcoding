package com.example.req.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-6：发出前就把「审批人解析不出」「条件没有默认分支」「节点不可达」挡在发布动作里，
 * 而不是让真实单据在运行时卡住。
 */
class WorkflowDefinitionValidatorTest {

    private static final long TENANT = 1L;
    private static final long INITIATOR = 100L;

    private FakeApproverDirectory directory;
    private WorkflowDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        directory = new FakeApproverDirectory();
        validator = new WorkflowDefinitionValidator(new ApproverResolver(directory),
                new ApprovalEngineTest.FixedActors(TENANT, INITIATOR));
    }

    @Test
    @DisplayName("AC-6 合法定义通过校验，并能解析出审批人")
    void validDefinitionPasses() {
        directory.role("PMO", 11L, 12L);
        WorkflowDefinition definition = def("n1", approval("n1", "PMO 会签", "end", role("PMO")));

        validator.validate(definition);
        assertEquals(List.of(11L, 12L), validator.resolveApprovers(definition.nodes().getFirst(), TENANT, INITIATOR));
    }

    @Test
    @DisplayName("AC-6 审批人解析不出：400 APPROVER_UNRESOLVED 且指出节点 key")
    void unresolvedApproverFailsWithNodeKey() {
        WorkflowDefinition definition = def("n1", approval("n1", "空角色", "end", role("GHOST_ROLE")));

        ApprovalException error = assertThrows(ApprovalException.class, () -> validator.validate(definition));
        assertEquals(400, error.status());
        assertEquals(ApprovalException.APPROVER_UNRESOLVED, error.code());
        assertEquals("n1", error.detail().get("nodeKey"));
    }

    @Test
    @DisplayName("AC-6 条件节点缺默认分支：400 WORKFLOW_INVALID")
    void conditionWithoutDefaultBranchIsInvalid() {
        directory.role("PMO", 11L);
        WorkflowDefinition definition = def("n1",
                approval("n1", "初审", "cond", role("PMO")),
                new WorkflowDefinition.Node("cond", WorkflowDefinition.TYPE_CONDITION, "条件", null,
                        List.of(), List.of(new WorkflowDefinition.Branch(
                        new WorkflowDefinition.When(null, null, List.of("P0"), null), "end")),
                        null, null, List.of()));

        ApprovalException error = assertThrows(ApprovalException.class, () -> validator.validate(definition));
        assertEquals(400, error.status());
        assertEquals(ApprovalException.WORKFLOW_INVALID, error.code());
    }

    @Test
    @DisplayName("AC-6 不可达节点：400 WORKFLOW_INVALID（发布时不放过死节点）")
    void unreachableNodeIsInvalid() {
        directory.role("PMO", 11L);
        WorkflowDefinition definition = def("n1",
                approval("n1", "初审", "end", role("PMO")),
                approval("orphan", "孤立节点", "end", role("PMO")));

        ApprovalException error = assertThrows(ApprovalException.class, () -> validator.validate(definition));
        assertEquals(400, error.status());
        assertEquals(ApprovalException.WORKFLOW_INVALID, error.code());
    }

    @Test
    @DisplayName("AC-6 重复/缺失节点：400 WORKFLOW_INVALID")
    void duplicateOrMissingStartIsInvalid() {
        directory.role("PMO", 11L);
        WorkflowDefinition duplicate = def("n1",
                approval("n1", "a", "end", role("PMO")),
                approval("n1", "b", "end", role("PMO")));
        ApprovalException dup = assertThrows(ApprovalException.class, () -> validator.validate(duplicate));
        assertEquals(ApprovalException.WORKFLOW_INVALID, dup.code());

        WorkflowDefinition missingStart = new WorkflowDefinition(WorkflowDefinition.SUPPORTED_SCHEMA, "nope",
                List.of(approval("n1", "a", "end", role("PMO"))));
        ApprovalException noStart = assertThrows(ApprovalException.class,
                () -> validator.validate(missingStart));
        assertEquals(ApprovalException.WORKFLOW_INVALID, noStart.code());
        assertTrue(noStart.getMessage().contains("nope") || noStart.getMessage().contains("start"));
    }

    private static WorkflowDefinition def(String start, WorkflowDefinition.Node... nodes) {
        return new WorkflowDefinition(WorkflowDefinition.SUPPORTED_SCHEMA, start, List.of(nodes));
    }

    private static WorkflowDefinition.Node approval(String key, String name, String next,
                                                    WorkflowDefinition.Approver... approvers) {
        return new WorkflowDefinition.Node(key, WorkflowDefinition.TYPE_APPROVAL, name,
                WorkflowDefinition.MODE_ALL, List.of(approvers), List.of(), null, next, List.of());
    }

    private static WorkflowDefinition.Approver role(String roleCode) {
        return new WorkflowDefinition.Approver(WorkflowDefinition.Approver.TYPE_ROLE, roleCode, null);
    }
}
