package com.example.req.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批中心（AC-9 的「可查」面）：待办能看到、详情按参与方鉴权、历史带版本。
 */
class ApprovalCenterServiceTest {

    private static final long TENANT = 1L;
    private static final long INITIATOR = 100L;
    private static final long APPROVER = 11L;
    private static final long OUTSIDER = 999L;

    private final InMemoryApprovalStore store = new InMemoryApprovalStore();
    private final FakeApproverDirectory directory = new FakeApproverDirectory()
            .userInOrg(INITIATOR, 10L, 20L)
            .role("PMO", APPROVER)
            .userInOrg(APPROVER, 20L);
    private final TestWorkflows registry = new TestWorkflows();
    private final ApprovalEngineTest.FixedActors actors = new ApprovalEngineTest.FixedActors(TENANT, INITIATOR);
    private final WorkflowJson json = new WorkflowJson(JsonMapper.builder().build());

    private long startInstance() {
        WorkflowDefinition definition = new WorkflowDefinition(WorkflowDefinition.SUPPORTED_SCHEMA, "n1", List.of(
                new WorkflowDefinition.Node("n1", WorkflowDefinition.TYPE_APPROVAL, "上级审批",
                        WorkflowDefinition.MODE_ANY,
                        List.of(new WorkflowDefinition.Approver(
                                WorkflowDefinition.Approver.TYPE_ROLE, "PMO", null)),
                        List.of(), null, "end", List.of())));
        registry.publish(7, 3, WorkflowVersion.BIZ_REQ_REVIEW, definition);
        ApprovalEngine engine = new ApprovalEngine(store, registry, new ApproverResolver(directory), directory,
                actors, new ApprovalEngineTest.RecordingNotifier(),
                List.of(new ApprovalEngineTest.RecordingCallback(WorkflowVersion.BIZ_REQ_REVIEW)),
                List.of(), json, Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
        return engine.start(new StartApproval(WorkflowVersion.BIZ_REQ_REVIEW, 6001L, "RD", 5L,
                Map.of("title", "结算对账"), null, null)).id();
    }

    private ApprovalCenterService center() {
        return new ApprovalCenterService(store, registry, actors, List.of());
    }

    @Test
    @DisplayName("待办只给当事人，详情对参与方开放、对无关人员 403，历史带钉住的版本")
    void todoDetailAndHistory() {
        long instanceId = startInstance();

        actors.as(APPROVER);
        PageSlice<ApprovalViews.TaskRow> todo = center().todo(1, 20);
        assertEquals(1, todo.total());
        assertEquals(instanceId, todo.items().getFirst().instanceId());
        assertEquals("n1", todo.items().getFirst().nodeKey());
        assertEquals(WorkflowVersion.BIZ_REQ_REVIEW, todo.items().getFirst().bizType());

        ApprovalViews.InstanceDetail detail = center().detail(instanceId);
        assertEquals(instanceId, detail.instance().id());
        assertEquals(3, detail.instance().definitionVersion(), "详情必须暴露实例钉住的版本");
        assertEquals(1, detail.nodes().size());
        assertEquals("n1", detail.nodes().getFirst().key());
        assertTrue(detail.timeline().stream().anyMatch(entry -> ApprovalHistory.SUBMIT.equals(entry.action())));

        actors.as(INITIATOR);
        assertEquals(instanceId, center().mine(1, 20).items().getFirst().instanceId());

        actors.as(OUTSIDER);
        ApprovalException forbidden = assertThrows(ApprovalException.class,
                () -> center().detail(instanceId));
        assertEquals(403, forbidden.status());
        assertEquals(0, center().todo(1, 20).total());

        actors.as(APPROVER);
        ApprovalViews.HistoryView history = center().history(instanceId);
        assertEquals(3, history.definitionVersion());
        assertTrue(history.entries().size() >= 1);
    }
}
