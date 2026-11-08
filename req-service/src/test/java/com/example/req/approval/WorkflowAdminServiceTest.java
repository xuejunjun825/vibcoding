package com.example.req.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-6：流程定义的发布/编辑边界——发布前校验、已发布不可改、默认流程唯一、版本自增。
 */
class WorkflowAdminServiceTest {

    private static final long TENANT = 1L;
    private static final long ADMIN = 900L;

    private WorkflowVersionRepository repository;
    private WorkflowAdminService service;
    private WorkflowJson json;
    private ApprovalEngineTest.FixedActors actors;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowVersionRepository.class);
        json = new WorkflowJson(JsonMapper.builder().build());
        actors = new ApprovalEngineTest.FixedActors(TENANT, ADMIN);
        actors.admin(ADMIN);
        FakeApproverDirectory directory = new FakeApproverDirectory().role("PMO", 11L);
        WorkflowDefinitionValidator validator =
                new WorkflowDefinitionValidator(new ApproverResolver(directory), actors);
        service = new WorkflowAdminService(repository, validator, json, actors,
                Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
        when(repository.save(any(WorkflowVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("AC-6 发布时校验审批人：解析不出 → 400 且不落库")
    void publishRejectsUnresolvableApprover() {
        WorkflowVersion row = draft(1L, 1, def(approval("n1", "空角色", "end", role("GHOST_ROLE"))));
        when(repository.findById(1L)).thenReturn(Optional.of(row));

        ApprovalException error = assertThrows(ApprovalException.class, () -> service.publish(1L));
        assertEquals(400, error.status());
        assertEquals(ApprovalException.APPROVER_UNRESOLVED, error.code());
        verify(repository, never()).save(any(WorkflowVersion.class));
    }

    @Test
    @DisplayName("AC-6 已发布版本不可编辑：409 WORKFLOW_PUBLISHED_IMMUTABLE")
    void publishedVersionIsImmutable() {
        WorkflowVersion row = draft(2L, 1, def(approval("n1", "审批", "end", role("PMO"))));
        row.setStatus(WorkflowVersion.STATUS_PUBLISHED);
        when(repository.findById(2L)).thenReturn(Optional.of(row));

        ApprovalException error = assertThrows(ApprovalException.class,
                () -> service.update(2L, new WorkflowAdminService.UpdateWorkflow("改名", null, null)));
        assertEquals(409, error.status());
        assertEquals(ApprovalException.WORKFLOW_PUBLISHED_IMMUTABLE, error.code());
    }

    @Test
    @DisplayName("AC-6 发布成功：状态置 PUBLISHED 并清掉同 bizType 的其他默认")
    void publishMarksPublishedAndClearsOtherDefaults() {
        WorkflowVersion row = draft(3L, 2, def(approval("n1", "审批", "end", role("PMO"))));
        row.setIsDefault(1);
        WorkflowVersion older = draft(4L, 1, def(approval("n1", "旧审批", "end", role("PMO"))));
        older.setIsDefault(1);
        older.setStatus(WorkflowVersion.STATUS_PUBLISHED);
        when(repository.findById(3L)).thenReturn(Optional.of(row));
        when(repository.findByTenantIdAndBizTypeAndIsDefault(TENANT, WorkflowVersion.BIZ_REQ_REVIEW, 1))
                .thenReturn(List.of(row, older));

        WorkflowAdminService.WorkflowView view = service.publish(3L);

        assertEquals(WorkflowVersion.STATUS_PUBLISHED, view.status());
        assertEquals(2, view.version());
        assertEquals(0, older.getIsDefault(), "同一 bizType 只允许一条默认流程");
        assertEquals(1, row.getIsDefault());
    }

    @Test
    @DisplayName("AC-6 新建草稿：版本号取同键最大值 +1")
    void createAssignsNextVersionAsDraft() {
        WorkflowVersion previous = draft(5L, 3, def(approval("n1", "审批", "end", role("PMO"))));
        when(repository.findFirstByTenantIdAndBizTypeAndReqTypeAndDomainIdOrderByVersionDesc(
                TENANT, WorkflowVersion.BIZ_REQ_REVIEW, WorkflowVersion.ANY_REQ_TYPE, WorkflowVersion.ANY_DOMAIN))
                .thenReturn(Optional.of(previous));

        WorkflowAdminService.WorkflowView view = service.create(new WorkflowAdminService.CreateWorkflow(
                "需求评审 v4", WorkflowVersion.BIZ_REQ_REVIEW, null, null, null,
                def(approval("n1", "审批", "end", role("PMO")))));

        assertEquals(4, view.version());
        assertEquals(WorkflowVersion.STATUS_DRAFT, view.status());
        assertEquals(WorkflowVersion.ANY_REQ_TYPE, view.reqType());
        assertEquals(WorkflowVersion.ANY_DOMAIN, view.domainId());
    }

    @Test
    @DisplayName("AC-6 非管理员不能维护流程定义：403")
    void nonAdminCannotManageWorkflows() {
        actors.as(11L);
        ApprovalException error = assertThrows(ApprovalException.class, () -> service.list(null));
        assertEquals(403, error.status());
        assertEquals(ApprovalException.FORBIDDEN, error.code());
    }

    private WorkflowVersion draft(long id, int version, WorkflowDefinition definition) {
        WorkflowVersion row = new WorkflowVersion();
        row.setId(id);
        row.setTenantId(TENANT);
        row.setName("需求评审");
        row.setBizType(WorkflowVersion.BIZ_REQ_REVIEW);
        row.setReqType(WorkflowVersion.ANY_REQ_TYPE);
        row.setDomainId(WorkflowVersion.ANY_DOMAIN);
        row.setVersion(version);
        row.setDefinitionJson(json.write(definition));
        row.setStatus(WorkflowVersion.STATUS_DRAFT);
        row.setIsDefault(0);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        return row;
    }

    private static WorkflowDefinition def(WorkflowDefinition.Node... nodes) {
        return new WorkflowDefinition(WorkflowDefinition.SUPPORTED_SCHEMA, "n1", List.of(nodes));
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
