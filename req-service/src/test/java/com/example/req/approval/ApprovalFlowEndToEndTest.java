package com.example.req.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端主路径（7c）：真实引擎 + 真实校验器 + 真实 JSON 序列化，
 * 一条供应链需求走完「上级审批 → 条件分流 → PMO 会签（含后加签）→ 抄送域负责人 → 通过」，
 * 断言终态而不是只看中间状态。持久化用内存实现（T0 的 platform-common/MySQL 尚未落地），
 * 因此这里验证的是状态机与回调，不验证 SQL。
 */
class ApprovalFlowEndToEndTest {

    private static final long TENANT = 1L;
    private static final long INITIATOR = 100L;
    private static final long MANAGER = 11L;
    private static final long PMO = 12L;
    private static final long ADDED = 13L;
    private static final long DOMAIN_OWNER = 21L;

    @Test
    @DisplayName("端到端：提交 → 上级 → 条件 → 会签+后加签 → 抄送 → APPROVED（终态）")
    void supplyChainRequirementReachesApprovedTerminalState() {
        InMemoryApprovalStore store = new InMemoryApprovalStore();
        FakeApproverDirectory directory = new FakeApproverDirectory()
                .userInOrg(INITIATOR, 10L, 20L, 30L)
                .role("PMO", MANAGER, PMO)
                .userInOrg(MANAGER, 20L, 30L)
                .userInOrg(PMO, 20L, 30L)
                .active(ADDED)
                .domainOwner(5L, DOMAIN_OWNER);
        TestWorkflows registry = new TestWorkflows();
        ApprovalEngineTest.RecordingNotifier notifier = new ApprovalEngineTest.RecordingNotifier();
        ApprovalEngineTest.RecordingCallback callback =
                new ApprovalEngineTest.RecordingCallback(WorkflowVersion.BIZ_REQ_REVIEW);
        ApprovalEngineTest.FixedActors actors = new ApprovalEngineTest.FixedActors(TENANT, INITIATOR);

        WorkflowDefinition definition = new WorkflowDefinition(WorkflowDefinition.SUPPORTED_SCHEMA, "n1", List.of(
                new WorkflowDefinition.Node("n1", WorkflowDefinition.TYPE_APPROVAL, "上级审批",
                        WorkflowDefinition.MODE_ANY,
                        List.of(new WorkflowDefinition.Approver(
                                WorkflowDefinition.Approver.TYPE_SUPERVISOR, null, "INITIATOR_ORG")),
                        List.of(), null, "n2", List.of()),
                new WorkflowDefinition.Node("n2", WorkflowDefinition.TYPE_CONDITION, "按优先级分流", null,
                        List.of(),
                        List.of(new WorkflowDefinition.Branch(new WorkflowDefinition.When(
                                List.of("RD"), null, List.of("P0"),
                                new WorkflowDefinition.Days(new BigDecimal("5"), null, null, null)), "n3")),
                        "n4", null, List.of()),
                new WorkflowDefinition.Node("n3", WorkflowDefinition.TYPE_APPROVAL, "PMO 会签",
                        WorkflowDefinition.MODE_ALL,
                        List.of(new WorkflowDefinition.Approver(
                                WorkflowDefinition.Approver.TYPE_ROLE, "PMO", null)),
                        List.of(), null, "n4", List.of()),
                new WorkflowDefinition.Node("n4", WorkflowDefinition.TYPE_CC, "抄送域负责人", null,
                        List.of(), List.of(), null, "end",
                        List.of(new WorkflowDefinition.Approver(
                                WorkflowDefinition.Approver.TYPE_DOMAIN_OWNER, null, null)))));

        WorkflowDefinitionValidator validator = new WorkflowDefinitionValidator(
                new ApproverResolver(directory), actors);
        validator.validate(definition, TENANT, INITIATOR, List.of(5L));

        JsonMapper mapper = JsonMapper.builder().build();
        WorkflowJson json = new WorkflowJson(mapper);
        // 定义经过一次序列化/反序列化：发布后实例读到的只能是落库的那份 JSON。
        WorkflowDefinition roundTrip = json.parse(json.write(definition));
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW, roundTrip);

        ApprovalEngine engine = new ApprovalEngine(store, registry, new ApproverResolver(directory), directory,
                actors, notifier, List.of(callback), List.of(), json,
                Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneId.of("Asia/Shanghai")));

        ApprovalViews.InstanceView started = engine.start(new StartApproval(
                WorkflowVersion.BIZ_REQ_REVIEW, 5001L, "RD", 5L,
                Map.of("priority", "P0", "estimateDays", 8, "title", "供应商结算对账"), null, null));
        long instanceId = started.id();

        // 上级审批（或签，两人中的一个）
        actors.as(MANAGER);
        engine.approve(taskOf(store.tasks(instanceId), "n1", MANAGER).getId(), "同意");
        assertEquals(ApprovalInstance.STATUS_PENDING, store.instanceOf(instanceId).getStatus());

        // PMO 会签：先给本人后加签，本人通过后节点仍开着，直到加签人也处理
        ApprovalTask managerPmoTask = taskOf(store.tasks(instanceId), "n3", MANAGER);
        engine.addSign(managerPmoTask.getId(), ApprovalTask.SIGN_AFTER, List.of(ADDED), "请补充财务意见");
        engine.approve(managerPmoTask.getId(), "同意");
        assertEquals(ApprovalInstance.STATUS_PENDING, store.instanceOf(instanceId).getStatus(),
                "会签 + 加签：新成员未处理前不得关闭节点");

        actors.as(PMO);
        engine.approve(taskOf(store.tasks(instanceId), "n3", PMO).getId(), "同意");
        assertEquals(ApprovalInstance.STATUS_PENDING, store.instanceOf(instanceId).getStatus());

        actors.as(ADDED);
        engine.approve(taskOf(store.tasks(instanceId), "n3", ADDED).getId(), "财务无异议");

        ApprovalInstance terminal = store.instanceOf(instanceId);
        List<ApprovalHistory> timeline = store.history(instanceId);
        System.out.println("[approval-e2e] instance=" + terminal.getId()
                + " status=" + terminal.getStatus()
                + " currentNode=" + terminal.getCurrentNode()
                + " definitionVersion=" + terminal.getDefinitionVersion()
                + " tasks=" + store.tasks(instanceId).size()
                + " history=" + timeline.size()
                + " cc=" + store.ccs(instanceId).size()
                + " callbacks.approved=" + callback.approved);

        assertEquals(ApprovalInstance.STATUS_APPROVED, terminal.getStatus());
        assertEquals(WorkflowDefinition.END, terminal.getCurrentNode());
        assertNotNull(terminal.getEndedAt());
        assertEquals(1, terminal.getDefinitionVersion());
        assertEquals(1, callback.approved);
        assertEquals(1, store.ccs(instanceId).size());
        assertEquals(DOMAIN_OWNER, store.ccs(instanceId).getFirst().getReceiverId());
        assertTrue(notifier.sentTo(DOMAIN_OWNER));
        assertTrue(timeline.stream().anyMatch(entry -> ApprovalHistory.INSTANCE_APPROVED.equals(entry.getAction())));
        assertTrue(timeline.stream().anyMatch(entry -> ApprovalHistory.ADD_SIGN.equals(entry.getAction())));
        assertTrue(store.tasks(instanceId).stream()
                        .filter(task -> "n3".equals(task.getNodeKey()))
                        .allMatch(task -> ApprovalTask.STATUS_APPROVED.equals(task.getStatus())),
                "会签节点三个人都应是通过终态，不能被误取消");
    }

    private static ApprovalTask taskOf(List<ApprovalTask> tasks, String nodeKey, long approverId) {
        return tasks.stream()
                .filter(task -> nodeKey.equals(task.getNodeKey()))
                .filter(task -> task.getApproverId() != null && task.getApproverId() == approverId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 " + nodeKey + " 节点上 " + approverId + " 的任务"));
    }
}
