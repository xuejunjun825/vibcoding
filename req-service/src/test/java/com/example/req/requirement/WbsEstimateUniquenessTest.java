package com.example.req.requirement;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.ActionPermissionResolver;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.PermissionMatrix;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalHistory;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalInstanceRepository;
import com.example.req.approval.ApprovalStore;
import com.example.req.approval.ApprovalTask;
import com.example.req.approval.ApprovalViews;
import com.example.req.approval.WorkflowJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-5：唯一性与重提。同一需求 PENDING ≤1 / APPROVED ≤1；重提把旧 PENDING 置 SUPERSEDED；
 * 已通过且人天未变（或仍在内 ±20%）→ 409 ESTIMATE_ALREADY_APPROVED。
 */
class WbsEstimateUniquenessTest {

    private static final long TENANT = 1L;
    private static final long REQUIREMENT = 11L;
    private static final ActionPermissionResolver ALLOW_ALL =
            (menu, action) -> PermissionMatrix.Access.ALLOW;

    private final RequirementService requirements = mock(RequirementService.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalStore store = mock(ApprovalStore.class);
    private final ApprovalInstanceRepository instances = mock(ApprovalInstanceRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final WbsEstimateService service = new WbsEstimateService(requirements,
            provider(engine), store, instances, audit, ALLOW_ALL,
            new WorkflowJson(new ObjectMapper()), new RequirementStateMachine());

    @BeforeEach
    void setUp() {
        TenantContext.set(DataScope.system(TENANT));
        when(store.tasks(anyLong())).thenReturn(List.of());
        when(store.history(anyLong())).thenReturn(List.of());
        when(requirements.requireVisible(REQUIREMENT)).thenReturn(requirement());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void sameDaysAsApprovedInstanceIsRejected() {
        givenApproved(new BigDecimal("8.5"));
        ApiException error = catchThrowableOfType(() -> service.submit(REQUIREMENT,
                new BigDecimal("8.5"), null), ApiException.class);
        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo(RequirementErrors.ESTIMATE_ALREADY_APPROVED);
        assertThat(error.detail())
                .containsEntry("requirementId", REQUIREMENT)
                .containsEntry("instanceId", 9L);
        verify(engine, never()).start(any());
    }

    @Test
    void changeWithinTwentyPercentDoesNotCreateASecondDocument() {
        givenApproved(new BigDecimal("8.5"));
        // 8.5 → 10.0 是 17.6%，仍在阈值内：不重复建单。
        ApiException error = catchThrowableOfType(() -> service.submit(REQUIREMENT,
                new BigDecimal("10.0"), null), ApiException.class);
        assertThat(error.code()).isEqualTo(RequirementErrors.ESTIMATE_ALREADY_APPROVED);
        verify(engine, never()).start(any());
    }

    @Test
    void changeBeyondTwentyPercentIsAllowedAndOldApprovedStaysApproved() {
        givenApproved(new BigDecimal("10.0"));
        when(engine.start(any())).thenReturn(ApprovalViews.InstanceView.of(
                instance(42L, ApprovalInstance.STATUS_PENDING, ApprovalInstance.ACTIVE)));
        when(instances.findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT, ApprovalInstance.ACTIVE))
                .thenReturn(Optional.of(instance(42L, ApprovalInstance.STATUS_PENDING,
                        ApprovalInstance.ACTIVE)));

        service.submit(REQUIREMENT, new BigDecimal("12.5"), null);

        verify(engine).start(any());
        // 旧的 APPROVED 单不被改动（不 supersede、不重写人天）。
        verify(store, never()).saveInstance(org.mockito.ArgumentMatchers.argThat(
                instance -> instance != null && Long.valueOf(9L).equals(instance.getId())));
        verify(audit).record(eq("ESTIMATE_SUBMIT"), eq("REQUIREMENT"), eq(REQUIREMENT), any());
    }

    @Test
    void resubmitSupersedesOldPendingInstanceAndCancelsItsTasks() {
        ApprovalInstance pending = instance(7L, ApprovalInstance.STATUS_PENDING,
                ApprovalInstance.ACTIVE);
        ApprovalTask task = new ApprovalTask();
        task.setId(31L);
        task.setTenantId(TENANT);
        task.setInstanceId(7L);
        task.setApproverId(4L);
        task.setStatus(ApprovalTask.STATUS_PENDING);
        when(instances.findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT, ApprovalInstance.ACTIVE))
                .thenReturn(Optional.of(pending));
        when(instances.findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT)).thenReturn(Optional.of(pending));
        when(store.tasks(7L)).thenReturn(List.of(task));
        when(engine.start(any())).thenReturn(ApprovalViews.InstanceView.of(
                instance(42L, ApprovalInstance.STATUS_PENDING, ApprovalInstance.ACTIVE)));

        service.submit(REQUIREMENT, new BigDecimal("9.0"), "重提");

        ArgumentCaptor<ApprovalInstance> instanceCaptor =
                ArgumentCaptor.forClass(ApprovalInstance.class);
        verify(store).saveInstance(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getId()).isEqualTo(7L);
        assertThat(instanceCaptor.getValue().getStatus())
                .isEqualTo(ApprovalInstance.STATUS_SUPERSEDED);
        ArgumentCaptor<ApprovalTask> taskCaptor = ArgumentCaptor.forClass(ApprovalTask.class);
        verify(store).saveTask(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(ApprovalTask.STATUS_CANCELLED);
        // 旧单不删：时间线（历史流水）仍可查。
        ArgumentCaptor<ApprovalHistory> historyCaptor =
                ArgumentCaptor.forClass(ApprovalHistory.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).appendHistory(historyCaptor.capture());
        verify(engine).start(any());
        assertThat(historyCaptor.getAllValues()).isNotEmpty();
    }

    @Test
    void withdrawnLatestFallsBackToPreviousApprovedInstance() {
        ApprovalInstance approved = instance(9L, ApprovalInstance.STATUS_APPROVED, null);
        ApprovalInstance withdrawn = instance(12L, ApprovalInstance.STATUS_WITHDRAWN, null);
        when(instances.findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT)).thenReturn(Optional.of(withdrawn));
        when(instances.findAllByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT))
                .thenReturn(List.of(withdrawn, approved));
        ApprovalHistory submit = new ApprovalHistory();
        submit.setAction(ApprovalHistory.SUBMIT);
        submit.setDetailJson("{\"document\":{\"estimateDays\":10.0}}");
        when(store.history(9L)).thenReturn(List.of(submit));

        RequirementEntity entity = requirement();
        entity.setEstimateDays(new BigDecimal("10.0"));

        // 撤回不是评估结论：门回落到上一张有效 APPROVED，而不是把需求判成 NONE（D-02）。
        assertThat(service.statusOf(entity)).isEqualTo(WbsEstimateService.STATUS_APPROVED);
        WbsEstimateViews.EstimateView view = service.view(entity);
        assertThat(view.instanceId()).isEqualTo(9L);
        assertThat(view.approvedDays()).isEqualByComparingTo("10.0");

        // 全部单据都被撤回，才真的是「从未评估」。
        when(instances.findAllByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT)).thenReturn(List.of(withdrawn));
        assertThat(service.statusOf(entity)).isEqualTo(WbsEstimateService.STATUS_NONE);
    }

    @Test
    void approvingNewInstanceSupersedesPreviousApproved() {
        ApprovalInstance old = instance(9L, ApprovalInstance.STATUS_APPROVED, null);
        ApprovalInstance fresh = instance(42L, ApprovalInstance.STATUS_APPROVED, null);
        when(instances.findAllByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT)).thenReturn(List.of(fresh, old));

        service.supersedeApproved(REQUIREMENT, 42L, 0L);

        // AC-5：任意时刻 APPROVED ≤ 1 —— 新单通过时旧 APPROVED 落 SUPERSEDED。
        ArgumentCaptor<ApprovalInstance> instanceCaptor =
                ArgumentCaptor.forClass(ApprovalInstance.class);
        verify(store).saveInstance(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getId()).isEqualTo(9L);
        assertThat(instanceCaptor.getValue().getStatus())
                .isEqualTo(ApprovalInstance.STATUS_SUPERSEDED);
        ArgumentCaptor<ApprovalHistory> historyCaptor =
                ArgumentCaptor.forClass(ApprovalHistory.class);
        verify(store).appendHistory(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getComment()).contains("取代");
    }

    // ---------------------------------------------------------------- 工具

    private void givenApproved(BigDecimal days) {
        ApprovalInstance approved = instance(9L, ApprovalInstance.STATUS_APPROVED, null);
        when(instances.findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT))
                .thenReturn(Optional.of(approved));
        ApprovalHistory submit = new ApprovalHistory();
        submit.setAction(ApprovalHistory.SUBMIT);
        submit.setDetailJson("{\"document\":{\"estimateDays\":" + days.toPlainString() + "}}");
        when(store.history(9L)).thenReturn(List.of(submit));
    }

    private RequirementEntity requirement() {
        RequirementEntity entity = new RequirementEntity();
        entity.setId(REQUIREMENT);
        entity.setTenantId(TENANT);
        entity.setType(RequirementRules.TYPE_PDR);
        entity.setStatus("PENDING_ANALYSIS");
        entity.setReporterId(5L);
        entity.setDomainId(1L);
        // r41：工时评估必须指向承接厂商（设计 §6 R4），未指派的单在 submit 时 409 VENDOR_NOT_ASSIGNED。
        entity.setAssigneeVendorId(3L);
        return entity;
    }

    private ApprovalInstance instance(long id, String status, Integer activeFlag) {
        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(id);
        instance.setTenantId(TENANT);
        instance.setBizType(WbsEstimateService.BIZ_TYPE);
        instance.setBizId(REQUIREMENT);
        instance.setStatus(status);
        instance.setActiveFlag(activeFlag);
        instance.setStartedAt(LocalDateTime.now());
        return instance;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
