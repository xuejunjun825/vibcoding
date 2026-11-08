package com.example.req.approval;

import com.example.platform.contract.ApprovalCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批引擎行为断言：AC-6（匹配与版本）、AC-7（会签/或签/条件/抄送/驳回）、
 * AC-8（v1 在途 + v2 新单、重复处理 409、不重复建任务）、AC-9（加签/减签/转办/撤回/催办/流水）。
 */
class ApprovalEngineTest {

    private static final long TENANT = 1L;
    private static final long INITIATOR = 100L;
    private static final long ALICE = 11L;
    private static final long BOB = 12L;
    private static final long CAROL = 13L;
    private static final long ADMIN = 999L;

    private InMemoryApprovalStore store;
    private FakeApproverDirectory directory;
    private TestWorkflows registry;
    private RecordingNotifier notifier;
    private RecordingCallback callback;
    private FixedActors actors;
    private ApprovalEngine engine;

    @BeforeEach
    void setUp() {
        store = new InMemoryApprovalStore();
        directory = new FakeApproverDirectory();
        registry = new TestWorkflows();
        notifier = new RecordingNotifier();
        callback = new RecordingCallback(WorkflowVersion.BIZ_REQ_REVIEW);
        actors = new FixedActors(TENANT, INITIATOR);
        engine = new ApprovalEngine(store, registry, new ApproverResolver(directory), directory, actors,
                notifier, List.of(callback), List.of(),
                new WorkflowJson(JsonMapper.builder().build()),
                Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    // ---------------------------------------------------------------- AC-7 会签

    @Test
    @DisplayName("AC-7 会签：所有人通过才流转，中途仍是待审批")
    void allSignRequiresEveryApprover() {
        directory.role("PMO", ALICE, BOB);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "PMO 会签", WorkflowDefinition.MODE_ALL, "end", role("PMO"))));

        ApprovalViews.InstanceView view = start(2001L, Map.of());
        assertEquals(ApprovalInstance.STATUS_PENDING, view.status());
        List<ApprovalTask> tasks = store.tasks(view.id());
        assertEquals(2, tasks.size());

        actors.as(ALICE);
        engine.approve(taskOf(tasks, ALICE).getId(), "同意");
        assertEquals(ApprovalInstance.STATUS_PENDING, store.instanceOf(view.id()).getStatus());
        assertEquals(0, callback.approved);

        actors.as(BOB);
        engine.approve(taskOf(tasks, BOB).getId(), "同意");
        assertEquals(ApprovalInstance.STATUS_APPROVED, store.instanceOf(view.id()).getStatus());
        assertEquals(1, callback.approved);
        assertEquals(2, countAction(view.id(), ApprovalHistory.APPROVE));
    }

    @Test
    @DisplayName("AC-7 或签：一人通过即完成，其余待办取消")
    void anySignCompletesOnFirstApproval() {
        directory.role("PMO", ALICE, BOB);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "负责人", WorkflowDefinition.MODE_ANY, "end", role("PMO"))));

        ApprovalViews.InstanceView view = start(2002L, Map.of());
        List<ApprovalTask> tasks = store.tasks(view.id());
        actors.as(ALICE);
        engine.approve(taskOf(tasks, ALICE).getId(), "同意");

        assertEquals(ApprovalInstance.STATUS_APPROVED, store.instanceOf(view.id()).getStatus());
        assertEquals(ApprovalTask.STATUS_CANCELLED, taskOf(store.tasks(view.id()), BOB).getStatus());
    }

    // ---------------------------------------------------------------- AC-7 条件

    @Test
    @DisplayName("AC-7 条件：命中第一个分支，未命中走默认分支")
    void conditionUsesFirstMatchingBranchElseDefault() {
        directory.role("PMO", ALICE);
        directory.role("P0_REVIEWER", BOB);
        directory.role("DEFAULT_REVIEWER", CAROL);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW, def("n1",
                approval("n1", "初审", WorkflowDefinition.MODE_ANY, "cond", role("PMO")),
                new WorkflowDefinition.Node("cond", WorkflowDefinition.TYPE_CONDITION, "按优先级分流", null,
                        List.of(), List.of(new WorkflowDefinition.Branch(
                        new WorkflowDefinition.When(null, null, List.of("P0"), null), "n2")),
                        "n3", null, List.of()),
                approval("n2", "P0 复核", WorkflowDefinition.MODE_ANY, "end", role("P0_REVIEWER")),
                approval("n3", "常规复核", WorkflowDefinition.MODE_ANY, "end", role("DEFAULT_REVIEWER"))));

        ApprovalViews.InstanceView high = start(2003L, Map.of("priority", "P0"));
        actors.as(ALICE);
        engine.approve(taskOf(store.tasks(high.id()), ALICE).getId(), "ok");
        assertEquals(BOB, store.tasks(high.id()).stream()
                .filter(task -> "n2".equals(task.getNodeKey())).findFirst().orElseThrow().getApproverId());

        ApprovalViews.InstanceView normal = start(2004L, Map.of("priority", "P2"));
        actors.as(ALICE);
        engine.approve(taskOf(store.tasks(normal.id()), ALICE).getId(), "ok");
        assertEquals(CAROL, store.tasks(normal.id()).stream()
                .filter(task -> "n3".equals(task.getNodeKey())).findFirst().orElseThrow().getApproverId());
    }

    // ---------------------------------------------------------------- AC-7 抄送

    @Test
    @DisplayName("AC-7 抄送：写 wf_cc、发通知、不阻塞流程")
    void ccNodeNotifiesAndDoesNotBlock() {
        directory.role("PMO", ALICE);
        directory.domainOwner(5L, 21L);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW, def("n1",
                approval("n1", "负责人", WorkflowDefinition.MODE_ANY, "cc1", role("PMO")),
                new WorkflowDefinition.Node("cc1", WorkflowDefinition.TYPE_CC, "抄送域负责人", null,
                        List.of(), List.of(), null, "end", List.of(domainOwner()))));

        ApprovalViews.InstanceView view = start(2005L, Map.of(), 5L);
        actors.as(ALICE);
        engine.approve(taskOf(store.tasks(view.id()), ALICE).getId(), "ok");

        assertEquals(ApprovalInstance.STATUS_APPROVED, store.instanceOf(view.id()).getStatus());
        assertEquals(1, store.ccs(view.id()).size());
        assertEquals(21L, store.ccs(view.id()).getFirst().getReceiverId());
        assertTrue(notifier.sentTo(21L), "抄送人必须收到通知");
        assertEquals(1, countAction(view.id(), ApprovalHistory.CC_SEND));
    }

    // ---------------------------------------------------------------- AC-7 驳回

    @Test
    @DisplayName("AC-7 驳回：实例终止并回调单据，其余待办取消")
    void rejectTerminatesInstanceAndCallsBack() {
        directory.role("PMO", ALICE, BOB);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "会签", WorkflowDefinition.MODE_ALL, "end", role("PMO"))));

        ApprovalViews.InstanceView view = start(2006L, Map.of());
        List<ApprovalTask> tasks = store.tasks(view.id());
        actors.as(ALICE);
        engine.reject(taskOf(tasks, ALICE).getId(), "预算不足");

        ApprovalInstance instance = store.instanceOf(view.id());
        assertEquals(ApprovalInstance.STATUS_REJECTED, instance.getStatus());
        assertEquals(ApprovalTask.STATUS_CANCELLED, taskOf(store.tasks(view.id()), BOB).getStatus());
        assertEquals(1, callback.rejected);
        assertEquals("预算不足", callback.rejectReason);
        assertEquals(view.id(), store.instanceOf(view.id()).getId());
    }

    // ---------------------------------------------------------------- AC-8 版本

    @Test
    @DisplayName("AC-8 v2 发布后：v1 在途按 v1 走完，新单走 v2")
    void inFlightInstanceStaysOnV1WhileNewOnesUseV2() {
        directory.role("PMO", ALICE);
        directory.role("PROJECT_V2", BOB);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "v1 审批", WorkflowDefinition.MODE_ALL, "end", role("PMO"))));

        ApprovalViews.InstanceView first = start(2101L, Map.of());
        assertEquals(1, first.definitionVersion());
        assertEquals(ALICE, store.tasks(first.id()).getFirst().getApproverId());

        registry.publish(2, 2, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "v2 审批", WorkflowDefinition.MODE_ALL, "end", role("PROJECT_V2"))));

        ApprovalViews.InstanceView second = start(2102L, Map.of());
        assertEquals(2, second.definitionVersion());
        assertEquals(BOB, store.tasks(second.id()).getFirst().getApproverId());

        actors.as(ALICE);
        engine.approve(taskOf(store.tasks(first.id()), ALICE).getId(), "按 v1 走完");
        assertEquals(ApprovalInstance.STATUS_APPROVED, store.instanceOf(first.id()).getStatus());
        assertEquals(1, store.instanceOf(first.id()).getDefinitionVersion());
        assertEquals(ApprovalInstance.STATUS_PENDING, store.instanceOf(second.id()).getStatus());
        assertEquals(1, store.tasks(second.id()).size());
    }

    @Test
    @DisplayName("AC-8 重复处理同一任务：409，且不重复生成任务")
    void duplicateApproveConflictsWithoutExtraTasks() {
        directory.role("PMO", ALICE);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "审批", WorkflowDefinition.MODE_ANY, "end", role("PMO"))));

        ApprovalViews.InstanceView view = start(2103L, Map.of());
        ApprovalTask task = store.tasks(view.id()).getFirst();
        actors.as(ALICE);
        engine.approve(task.getId(), "ok");

        ApprovalException conflict = assertThrows(ApprovalException.class,
                () -> engine.approve(task.getId(), "ok"));
        assertEquals(409, conflict.status());
        assertEquals(ApprovalException.TASK_ALREADY_HANDLED, conflict.code());
        assertEquals(1, store.tasks(view.id()).size());
        assertEquals(1, countAction(view.id(), ApprovalHistory.APPROVE));
    }

    @Test
    @DisplayName("AC-6 没有匹配流程：400 NO_WORKFLOW")
    void startWithoutMatchingWorkflowFails() {
        ApprovalException error = assertThrows(ApprovalException.class,
                () -> start(2104L, Map.of()));
        assertEquals(400, error.status());
        assertEquals(ApprovalException.NO_WORKFLOW, error.code());
    }

    @Test
    @DisplayName("AC-6/8 同一单据在途时不能重复发起")
    void secondStartOnSameDocumentConflicts() {
        directory.role("PMO", ALICE);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "审批", WorkflowDefinition.MODE_ANY, "end", role("PMO"))));

        start(2105L, Map.of());
        ApprovalException error = assertThrows(ApprovalException.class, () -> start(2105L, Map.of()));
        assertEquals(409, error.status());
    }

    // ---------------------------------------------------------------- AC-9 加签

    @Test
    @DisplayName("AC-9 前加签：新人先处理，本人通过后才流转")
    void addSignBeforeKeepsNodeOpenUntilOriginalApproves() {
        directory.role("PMO", ALICE);
        directory.active(BOB);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "负责人", WorkflowDefinition.MODE_ANY, "end", role("PMO"))));

        ApprovalViews.InstanceView view = start(2201L, Map.of());
        ApprovalTask aliceTask = store.tasks(view.id()).getFirst();
        actors.as(ALICE);
        engine.addSign(aliceTask.getId(), ApprovalTask.SIGN_BEFORE, List.of(BOB), "请先复核");

        List<ApprovalTask> afterAdd = store.tasks(view.id());
        assertEquals(2, afterAdd.size());
        ApprovalTask bobTask = taskOf(afterAdd, BOB);
        assertTrue(bobTask.getSortNo() < aliceTask.getSortNo(), "前加签排序必须排在本人之前");
        assertTrue(bobTask.isAddSign());
        assertTrue(notifier.sentTo(BOB));

        actors.as(BOB);
        engine.approve(bobTask.getId(), "复核通过");
        assertEquals(ApprovalInstance.STATUS_PENDING, store.instanceOf(view.id()).getStatus());

        actors.as(ALICE);
        engine.approve(aliceTask.getId(), "同意");
        assertEquals(ApprovalInstance.STATUS_APPROVED, store.instanceOf(view.id()).getStatus());
        assertEquals(1, countAction(view.id(), ApprovalHistory.ADD_SIGN));
    }

    @Test
    @DisplayName("AC-9 后加签：本人通过后节点保持开放，直到新人也处理完")
    void addSignAfterKeepsNodeOpenForNewApprover() {
        directory.role("PMO", ALICE);
        directory.active(CAROL);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "负责人", WorkflowDefinition.MODE_ANY, "end", role("PMO"))));

        ApprovalViews.InstanceView view = start(2202L, Map.of());
        ApprovalTask aliceTask = store.tasks(view.id()).getFirst();
        actors.as(ALICE);
        engine.addSign(aliceTask.getId(), ApprovalTask.SIGN_AFTER, List.of(CAROL), "请会签确认");
        engine.approve(aliceTask.getId(), "同意");

        assertEquals(ApprovalInstance.STATUS_PENDING, store.instanceOf(view.id()).getStatus());
        ApprovalTask carolTask = taskOf(store.tasks(view.id()), CAROL);
        assertTrue(carolTask.getSortNo() > aliceTask.getSortNo(), "后加签排序必须排在本人之后");

        actors.as(CAROL);
        engine.approve(carolTask.getId(), "同意");
        assertEquals(ApprovalInstance.STATUS_APPROVED, store.instanceOf(view.id()).getStatus());
    }

    @Test
    @DisplayName("AC-9 会签加签：新成员未处理完不流转")
    void addSignOnAllNodeRequiresNewMemberToo() {
        directory.role("PMO", ALICE, BOB);
        directory.active(CAROL);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "会签", WorkflowDefinition.MODE_ALL, "end", role("PMO"))));

        ApprovalViews.InstanceView view = start(2203L, Map.of());
        List<ApprovalTask> tasks = store.tasks(view.id());
        actors.as(ALICE);
        engine.addSign(taskOf(tasks, ALICE).getId(), ApprovalTask.SIGN_AFTER, List.of(CAROL), "补签");
        engine.approve(taskOf(tasks, ALICE).getId(), "同意");
        actors.as(BOB);
        engine.approve(taskOf(tasks, BOB).getId(), "同意");

        assertEquals(ApprovalInstance.STATUS_PENDING, store.instanceOf(view.id()).getStatus(),
                "会签加签后新成员未处理，节点不能关闭");

        actors.as(CAROL);
        engine.approve(taskOf(store.tasks(view.id()), CAROL).getId(), "同意");
        assertEquals(ApprovalInstance.STATUS_APPROVED, store.instanceOf(view.id()).getStatus());
    }

    @Test
    @DisplayName("AC-9 已处理任务加签：409")
    void addSignOnHandledTaskConflicts() {
        directory.role("PMO", ALICE);
        directory.active(BOB);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "会签", WorkflowDefinition.MODE_ALL, "end", role("PMO"))));

        ApprovalViews.InstanceView view = start(2204L, Map.of());
        ApprovalTask task = store.tasks(view.id()).getFirst();
        actors.as(ALICE);
        engine.approve(task.getId(), "同意");

        ApprovalException error = assertThrows(ApprovalException.class,
                () -> engine.addSign(task.getId(), ApprovalTask.SIGN_AFTER, List.of(BOB), "补签"));
        assertEquals(409, error.status());
        assertEquals(ApprovalException.TASK_ALREADY_HANDLED, error.code());
    }

    // ---------------------------------------------------------------- AC-9 减签

    @Test
    @DisplayName("AC-9 减签：缺原因 400；被减签人收通知；末位减签 409")
    void removeSignRequiresReasonAndKeepsLastApprover() {
        directory.role("PMO", ALICE, BOB);
        directory.active(CAROL);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "会签", WorkflowDefinition.MODE_ALL, "end", role("PMO"))));

        ApprovalViews.InstanceView view = start(2205L, Map.of());
        List<ApprovalTask> tasks = store.tasks(view.id());
        actors.as(ALICE);
        ApprovalException noReason = assertThrows(ApprovalException.class,
                () -> engine.removeSign(taskOf(tasks, ALICE).getId(), BOB, " "));
        assertEquals(400, noReason.status());
        assertEquals(ApprovalException.REASON_REQUIRED, noReason.code());

        engine.removeSign(taskOf(tasks, ALICE).getId(), BOB, "已线下确认");
        assertEquals(ApprovalTask.STATUS_CANCELLED, taskOf(store.tasks(view.id()), BOB).getStatus());
        assertTrue(notifier.sentTo(BOB), "被减签人必须收到通知");
        assertEquals(1, countAction(view.id(), ApprovalHistory.REMOVE_SIGN));

        // 只剩最后一名审批人时（本人也不例外）必须 409，而不是把自己减掉让节点无人可批。
        actors.as(ALICE);
        ApprovalException last = assertThrows(ApprovalException.class,
                () -> engine.removeSign(taskOf(store.tasks(view.id()), ALICE).getId(), ALICE, "清空"));
        assertEquals(409, last.status());
        assertEquals(ApprovalException.LAST_APPROVER, last.code());
    }

    // ---------------------------------------------------------------- AC-9 转办

    @Test
    @DisplayName("AC-9 转办：任务改派、通知新审批人、留痕")
    void transferReassignsTask() {
        directory.role("PMO", ALICE);
        directory.active(CAROL);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "审批", WorkflowDefinition.MODE_ANY, "end", role("PMO"))));

        ApprovalViews.InstanceView view = start(2206L, Map.of());
        ApprovalTask task = store.tasks(view.id()).getFirst();
        actors.as(ALICE);
        engine.transfer(task.getId(), CAROL);

        ApprovalTask moved = store.taskOf(task.getId());
        assertEquals(CAROL, moved.getApproverId());
        assertTrue(notifier.sentTo(CAROL));
        assertEquals(1, countAction(view.id(), ApprovalHistory.TRANSFER));

        actors.as(ALICE);
        ApprovalException forbidden = assertThrows(ApprovalException.class,
                () -> engine.approve(task.getId(), "同意"));
        assertEquals(403, forbidden.status());

        actors.as(CAROL);
        engine.approve(task.getId(), "同意");
        assertEquals(ApprovalInstance.STATUS_APPROVED, store.instanceOf(view.id()).getStatus());
    }

    // ---------------------------------------------------------------- AC-9 撤回

    @Test
    @DisplayName("AC-9 撤回：未处理可撤并回调，处理过则 409")
    void withdrawOnlyBeforeAnyTaskHandled() {
        directory.role("PMO", ALICE, BOB);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "会签", WorkflowDefinition.MODE_ALL, "end", role("PMO"))));

        ApprovalViews.InstanceView first = start(2207L, Map.of());
        actors.as(INITIATOR);
        engine.withdraw(first.id());
        assertEquals(ApprovalInstance.STATUS_WITHDRAWN, store.instanceOf(first.id()).getStatus());
        assertTrue(store.tasks(first.id()).stream().noneMatch(ApprovalTask::isPending));
        assertEquals(1, callback.withdrawn);

        ApprovalViews.InstanceView second = start(2208L, Map.of());
        List<ApprovalTask> tasks = store.tasks(second.id());
        actors.as(ALICE);
        engine.approve(taskOf(tasks, ALICE).getId(), "同意");
        actors.as(INITIATOR);
        ApprovalException error = assertThrows(ApprovalException.class, () -> engine.withdraw(second.id()));
        assertEquals(409, error.status());
        assertEquals(ApprovalException.ILLEGAL_TRANSITION, error.code());
    }

    // ---------------------------------------------------------------- AC-9 催办

    @Test
    @DisplayName("AC-9 催办：通知审批人并留痕，60 秒内重复 429")
    void urgeNotifiesOncePerWindow() {
        directory.role("PMO", ALICE);
        registry.publish(1, 1, WorkflowVersion.BIZ_REQ_REVIEW,
                def("n1", approval("n1", "审批", WorkflowDefinition.MODE_ANY, "end", role("PMO"))));

        ApprovalViews.InstanceView view = start(2209L, Map.of());
        ApprovalTask task = store.tasks(view.id()).getFirst();
        actors.as(INITIATOR);
        engine.urge(task.getId());
        assertTrue(notifier.sentTo(ALICE));
        assertEquals(1, countAction(view.id(), ApprovalHistory.URGE));

        ApprovalException tooOften = assertThrows(ApprovalException.class, () -> engine.urge(task.getId()));
        assertEquals(429, tooOften.status());
        assertEquals(ApprovalException.URGE_TOO_FREQUENT, tooOften.code());
    }

    // ---------------------------------------------------------------- 辅助

    private ApprovalViews.InstanceView start(long bizId, Map<String, Object> attributes) {
        return start(bizId, attributes, null);
    }

    private ApprovalViews.InstanceView start(long bizId, Map<String, Object> attributes, Long domainId) {
        return engine.start(new StartApproval(WorkflowVersion.BIZ_REQ_REVIEW, bizId, "RD", domainId,
                attributes, null, null));
    }

    private long countAction(long instanceId, String action) {
        return store.history(instanceId).stream().filter(entry -> action.equals(entry.getAction())).count();
    }

    private static ApprovalTask taskOf(List<ApprovalTask> tasks, long approverId) {
        return tasks.stream()
                .filter(task -> task.getApproverId() != null && task.getApproverId() == approverId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有审批人为 " + approverId + " 的任务"));
    }

    private static WorkflowDefinition def(String start, WorkflowDefinition.Node... nodes) {
        return new WorkflowDefinition(WorkflowDefinition.SUPPORTED_SCHEMA, start, List.of(nodes));
    }

    private static WorkflowDefinition.Node approval(String key, String name, String mode, String next,
                                                    WorkflowDefinition.Approver... approvers) {
        return new WorkflowDefinition.Node(key, WorkflowDefinition.TYPE_APPROVAL, name, mode,
                List.of(approvers), List.of(), null, next, List.of());
    }

    private static WorkflowDefinition.Approver role(String roleCode) {
        return new WorkflowDefinition.Approver(WorkflowDefinition.Approver.TYPE_ROLE, roleCode, null);
    }

    private static WorkflowDefinition.Approver domainOwner() {
        return new WorkflowDefinition.Approver(WorkflowDefinition.Approver.TYPE_DOMAIN_OWNER, null, null);
    }

    // ---------------------------------------------------------------- 测试替身

    static final class FixedActors implements ApprovalActorProvider {

        private final long tenantId;
        private long userId;
        private final Set<Long> admins = new HashSet<>();

        FixedActors(long tenantId, long userId) {
            this.tenantId = tenantId;
            this.userId = userId;
        }

        FixedActors as(long userId) {
            this.userId = userId;
            return this;
        }

        void admin(long... userIds) {
            for (long id : userIds) {
                admins.add(id);
            }
        }

        @Override
        public ApprovalActor current() {
            return new ApprovalActor(tenantId, userId);
        }

        @Override
        public boolean isAdmin(long tenantId, long userId) {
            return admins.contains(userId);
        }
    }

    static final class RecordingNotifier implements ApprovalNotifier {

        record Sent(long userId, String type, String title, String content, String bizType, Long bizId) {
        }

        private final List<Sent> sent = new ArrayList<>();

        @Override
        public void notify(long userId, String type, String title, String content, String bizType, Long bizId) {
            sent.add(new Sent(userId, type, title, content, bizType, bizId));
        }

        boolean sentTo(long userId) {
            return sent.stream().anyMatch(entry -> entry.userId() == userId);
        }

        List<Sent> all() {
            return sent;
        }
    }

    static final class RecordingCallback implements ApprovalCallback {

        private final String bizType;
        int approved;
        int rejected;
        int withdrawn;
        String rejectReason;

        RecordingCallback(String bizType) {
            this.bizType = bizType;
        }

        @Override
        public String bizType() {
            return bizType;
        }

        @Override
        public void onApproved(long bizId, long instanceId) {
            approved++;
        }

        @Override
        public void onRejected(long bizId, long instanceId, String reason) {
            rejected++;
            rejectReason = reason;
        }

        @Override
        public void onWithdrawn(long bizId, long instanceId) {
            withdrawn++;
        }
    }
}
