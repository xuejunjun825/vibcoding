package com.example.req.requirement.change;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.security.PermissionService;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.PermissionMatrix;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalViews;
import com.example.req.approval.PageSlice;
import com.example.req.approval.StartApproval;
import com.example.req.approval.WorkflowJson;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-2/AC-4/AC-5/AC-14：创建即冻结 before/after/impact 并起第 6 类审批；preview 不落库；
 * 通过状态由回调推进，{@code canApply} 由服务端算。
 */
class RequirementChangeServiceTest {

    private static final long TENANT = 1L;
    private static final long REQUIREMENT = 11L;
    private static final long CHANGE = 99L;
    private static final long INSTANCE = 42L;

    private final RequirementService requirements = mock(RequirementService.class);
    private final RequirementChangeRepository changes = mock(RequirementChangeRepository.class);
    private final RequirementChangeItemRepository items = mock(RequirementChangeItemRepository.class);
    private final RequirementChangeValidator validator = mock(RequirementChangeValidator.class);
    private final RequirementChangeImpactAssessor assessor = mock(RequirementChangeImpactAssessor.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final AuditService audit = mock(AuditService.class);
    private final WorkflowJson json = new WorkflowJson(new ObjectMapper());
    private final PermissionService permissions = mock(PermissionService.class);

    private final RequirementChangeService service = new RequirementChangeService(
            requirements, changes, items, validator, assessor, provider(engine), audit, json, permissions);

    @BeforeEach
    void setUp() {
        TenantContext.set(DataScope.system(TENANT));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createFreezesSnapshotAndStartsChangeApproval() {
        RequirementEntity entity = requirement();
        Map<String, Object> before = RequirementChangeFields.snapshot(entity);
        Map<String, Object> after = RequirementChangeFields.merge(before, Map.of("title", "改后的标题"));
        when(requirements.requireWritable(REQUIREMENT)).thenReturn(entity);
        // 服务层把原始原因原样交给 validator（去空格是 validator 的职责），落库时才 strip。
        when(validator.validateCreate(entity, "CONTENT", "  标题写错了  ", Map.of("title", "改后的标题")))
                .thenReturn(after);
        when(assessor.assess(entity, "CONTENT", after)).thenReturn(Map.of("frozenAt", "2026-11-23T10:00:00Z"));
        when(engine.start(any())).thenReturn(ApprovalViews.InstanceView.of(instance()));
        when(changes.save(any())).thenAnswer(call -> {
            RequirementChangeEntity row = call.getArgument(0);
            if (row.getId() == null) {
                row.setId(CHANGE);
            }
            return row;
        });

        RequirementChangeViews.Created created = service.create(REQUIREMENT,
                new RequirementChangeDtos.CreateRequest("CONTENT", "  标题写错了  ", Map.of("title", "改后的标题")));

        assertThat(created.id()).isEqualTo(CHANGE);
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.instanceId()).isEqualTo(INSTANCE);
        assertThat(created.impact()).containsEntry("frozenAt", "2026-11-23T10:00:00Z");

        ArgumentCaptor<RequirementChangeEntity> rows = ArgumentCaptor.forClass(RequirementChangeEntity.class);
        verify(changes, times(2)).save(rows.capture());
        RequirementChangeEntity first = rows.getAllValues().get(0);
        assertThat(first.getStatus()).isEqualTo("PENDING");
        assertThat(first.getChangeType()).isEqualTo("CONTENT");
        assertThat(first.getReason()).isEqualTo("标题写错了");
        assertThat(first.getTenantId()).isEqualTo(TENANT);
        assertThat(json.readMap(first.getBeforeJson())).containsEntry("title", "原标题");
        assertThat(json.readMap(first.getAfterJson())).containsEntry("title", "改后的标题");
        assertThat(first.getImpactJson()).contains("frozenAt");
        assertThat(rows.getAllValues().get(1).getInstanceId()).isEqualTo(INSTANCE);

        ArgumentCaptor<StartApproval> command = ArgumentCaptor.forClass(StartApproval.class);
        verify(engine).start(command.capture());
        assertThat(command.getValue().bizType()).isEqualTo("REQ_CHANGE");
        assertThat(command.getValue().bizId()).isEqualTo(CHANGE);
        assertThat(command.getValue().reqType()).isEqualTo("PDR");
        assertThat(command.getValue().attributes())
                .containsEntry("requirementId", REQUIREMENT)
                .containsEntry("changeType", "CONTENT")
                .containsEntry("fields", List.of("title"));
        // 审计按变更单收口（biz_id=changeId），需求 id 只在 detail 里。
        verify(audit).record(eq("CHANGE_REQUESTED"), eq("REQ_CHANGE"), eq(CHANGE), any());
    }

    @Test
    void previewReturnsSameShapeWithoutWritingAnything() {
        RequirementEntity entity = requirement();
        Map<String, Object> before = RequirementChangeFields.snapshot(entity);
        Map<String, Object> after = RequirementChangeFields.merge(before, Map.of("priority", "P1"));
        when(requirements.requireWritable(REQUIREMENT)).thenReturn(entity);
        when(validator.validateCreate(entity, "CONTENT", "提级", Map.of("priority", "P1"))).thenReturn(after);
        when(assessor.assess(entity, "CONTENT", after)).thenReturn(Map.of("children", Map.of("count", 0)));

        RequirementChangeViews.Preview preview = service.preview(REQUIREMENT,
                new RequirementChangeDtos.CreateRequest("CONTENT", "提级", Map.of("priority", "P1")));

        assertThat(preview.fields()).containsExactly("priority");
        assertThat(preview.before()).containsEntry("priority", "P2");
        assertThat(preview.after()).containsEntry("priority", "P1");
        assertThat(preview.impact()).containsKey("children");
        verify(changes, never()).save(any());
        verify(engine, never()).start(any());
        verify(audit, never()).record(any(), any(), any(), any());
    }

    @Test
    void listRejectsSizeAboveOneHundredBeforeQuerying() {
        when(requirements.requireVisible(REQUIREMENT)).thenReturn(requirement());

        ApiException error = catchThrowableOfType(
                () -> service.list(REQUIREMENT, null, 1, 500), ApiException.class);

        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        verify(changes, never()).page(any(), any(), any(), any());
    }

    @Test
    void listRejectsUnknownStatus() {
        when(requirements.requireVisible(REQUIREMENT)).thenReturn(requirement());

        ApiException error = catchThrowableOfType(
                () -> service.list(REQUIREMENT, "DONE", 1, 20), ApiException.class);

        assertThat(error.status()).isEqualTo(400);
        verify(changes, never()).page(any(), any(), any(), any());
    }

    @Test
    void listMapsFrozenImpactToSummaryKeys() {
        when(requirements.requireVisible(REQUIREMENT)).thenReturn(requirement());
        RequirementChangeEntity row = changeRow("APPROVED");
        when(changes.page(eq(TENANT), eq(REQUIREMENT), eq("APPROVED"), any()))
                .thenReturn(new PageImpl<>(List.of(row)));
        when(assessor.summary(any())).thenReturn(Map.of("becomesStale", true));

        PageSlice<RequirementChangeViews.Row> page = service.list(REQUIREMENT, "approved", 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).impactSummary()).containsEntry("becomesStale", true);
        assertThat(page.items().get(0).status()).isEqualTo("APPROVED");
        assertThat(page.items().get(0).fields()).containsExactly("title");
        // 响应契约：fields 是 Row 的一等键（前端 7 列里的「目标字段」直接读它）。
        assertThat(json.write(page.items().get(0))).contains("\"fields\":[\"title\"]");
    }

    @Test
    void listRowFieldsFollowCanonicalOrderNotSnapshotOrder() {
        RequirementEntity entity = requirement();
        Map<String, Object> before = RequirementChangeFields.snapshot(entity);
        Map<String, Object> after = RequirementChangeFields.merge(before,
                Map.of("estimateDays", "5", "priority", "P1", "title", "改后的标题"));
        RequirementChangeEntity row = changeRow("APPROVED");
        row.setBeforeJson(json.write(before));
        row.setAfterJson(json.write(after));
        when(requirements.requireVisible(REQUIREMENT)).thenReturn(entity);
        when(changes.page(eq(TENANT), eq(REQUIREMENT), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(row)));
        when(assessor.summary(any())).thenReturn(Map.of());

        PageSlice<RequirementChangeViews.Row> page = service.list(REQUIREMENT, null, 1, 20);

        // 规范序 = RequirementChangeFields.FIELDS（title → priority → estimateDays），
        // 与写入 patch / JSON 键序无关。
        assertThat(page.items().get(0).fields()).containsExactly("title", "priority", "estimateDays");
    }

    @Test
    void listRowWithEmptySnapshotYieldsEmptyFieldsAndStillServes() {
        RequirementChangeEntity row = changeRow("APPROVED");
        row.setBeforeJson("{}");
        row.setAfterJson(null);
        when(requirements.requireVisible(REQUIREMENT)).thenReturn(requirement());
        when(changes.page(eq(TENANT), eq(REQUIREMENT), eq("APPROVED"), any()))
                .thenReturn(new PageImpl<>(List.of(row)));
        when(assessor.summary(any())).thenReturn(Map.of());

        PageSlice<RequirementChangeViews.Row> page = service.list(REQUIREMENT, "approved", 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).fields()).isEmpty();
    }

    @Test
    void detailExposesCanApplyFromServerSidePermission() {
        RequirementEntity entity = requirement();
        when(requirements.requireVisible(REQUIREMENT)).thenReturn(entity);
        when(changes.findByIdAndTenantId(CHANGE, TENANT)).thenReturn(Optional.of(changeRow("APPROVED")));
        when(items.findByChangeIdOrderByFieldAsc(CHANGE)).thenReturn(List.of());
        when(permissions.decide("req.pool", "req.action.change_apply"))
                .thenReturn(PermissionMatrix.Access.ALLOW);

        RequirementChangeViews.Detail detail = service.detail(CHANGE);

        assertThat(detail.canApply()).isTrue();
        assertThat(detail.requirement().code()).isEqualTo("PDR-0001");
        assertThat(detail.items()).isEmpty();
    }

    @Test
    void detailHidesApplyForPendingOrDenied() {
        when(requirements.requireVisible(REQUIREMENT)).thenReturn(requirement());
        when(items.findByChangeIdOrderByFieldAsc(CHANGE)).thenReturn(List.of());

        when(changes.findByIdAndTenantId(CHANGE, TENANT)).thenReturn(Optional.of(changeRow("PENDING")));
        assertThat(service.detail(CHANGE).canApply()).isFalse();

        when(changes.findByIdAndTenantId(CHANGE, TENANT)).thenReturn(Optional.of(changeRow("APPROVED")));
        when(permissions.decide("req.pool", "req.action.change_apply"))
                .thenReturn(PermissionMatrix.Access.DENY);
        assertThat(service.detail(CHANGE).canApply()).isFalse();
    }

    @Test
    void detailOfUnknownChangeIs404() {
        when(changes.findByIdAndTenantId(CHANGE, TENANT)).thenReturn(Optional.empty());

        ApiException error = catchThrowableOfType(() -> service.detail(CHANGE), ApiException.class);

        assertThat(error.status()).isEqualTo(404);
        assertThat(error.code()).isEqualTo("NOT_FOUND");
    }

    // ---------------------------------------------------------------- fixtures

    private static RequirementEntity requirement() {
        RequirementEntity entity = new RequirementEntity();
        entity.setId(REQUIREMENT);
        entity.setTenantId(TENANT);
        entity.setCode("PDR-0001");
        entity.setTitle("原标题");
        entity.setType("PDR");
        entity.setStatus("PENDING_ANALYSIS");
        entity.setDomainId(3L);
        entity.setPriority("P2");
        return entity;
    }

    private RequirementChangeEntity changeRow(String status) {
        RequirementChangeEntity row = new RequirementChangeEntity();
        row.setId(CHANGE);
        row.setTenantId(TENANT);
        row.setRequirementId(REQUIREMENT);
        row.setChangeType("CONTENT");
        row.setStatus(status);
        row.setReason("标题写错了");
        row.setBeforeJson("{\"title\":\"原标题\"}");
        row.setAfterJson("{\"title\":\"改后的标题\"}");
        row.setImpactJson("{\"days\":{\"becomesStale\":false}}");
        row.setInstanceId(INSTANCE);
        row.setCreatedAt(LocalDateTime.now());
        row.setCreatedBy(7L);
        return row;
    }

    private static ApprovalInstance instance() {
        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(INSTANCE);
        instance.setTenantId(TENANT);
        instance.setBizType("REQ_CHANGE");
        instance.setBizId(CHANGE);
        instance.setStatus(ApprovalInstance.STATUS_PENDING);
        instance.setActiveFlag(ApprovalInstance.ACTIVE);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
