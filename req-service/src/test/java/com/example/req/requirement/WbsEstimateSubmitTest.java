package com.example.req.requirement;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.contract.ActionPermissionResolver;
import com.example.req.approval.ApprovalViews;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalInstanceRepository;
import com.example.req.approval.ApprovalStore;
import com.example.req.approval.StartApproval;
import com.example.req.approval.WorkflowJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
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
 * AC-1/AC-2：可发起 + 参数校验。每个失败用例都断言「不建实例」（engine.start 零调用）。
 */
class WbsEstimateSubmitTest {

    private static final long TENANT = 1L;
    private static final long REQUIREMENT = 11L;
    private static final ActionPermissionResolver ALLOW_ALL =
            (menu, action) -> PermissionMatrix.Access.ALLOW;

    private final RequirementService requirements = mock(RequirementService.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalStore store = mock(ApprovalStore.class);
    private final ApprovalInstanceRepository instances = mock(ApprovalInstanceRepository.class);
    private final AuditService audit = mock(AuditService.class);

    @BeforeEach
    void setUp() {
        TenantContext.set(DataScope.system(TENANT));
        when(store.tasks(anyLong())).thenReturn(List.of());
        when(store.history(anyLong())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void busRequirementIsRejectedWithTypeField() {
        given(requirement(RequirementRules.TYPE_BUS, "POOLED"));
        ApiException error = catchThrowableOfType(() -> service().submit(REQUIREMENT,
                new BigDecimal("8.5"), null), ApiException.class);
        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo(RequirementErrors.VALIDATION_FAILED);
        assertThat(error.detail()).containsEntry("field", "type");
        verify(engine, never()).start(any());
    }

    @Test
    void terminalRequirementIsRejectedWithStatusField() {
        given(requirement(RequirementRules.TYPE_PDR, "ACCEPTED_CLOSED"));
        ApiException error = catchThrowableOfType(() -> service().submit(REQUIREMENT,
                new BigDecimal("8.5"), null), ApiException.class);
        assertThat(error.status()).isEqualTo(400);
        assertThat(error.detail()).containsEntry("field", "status");
        verify(engine, never()).start(any());
    }

    @Test
    void daysAreRequiredAndPositive() {
        given(requirement(RequirementRules.TYPE_PDR, "PENDING_ANALYSIS"));
        for (BigDecimal illegal : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-1")}) {
            ApiException error = catchThrowableOfType(() -> service().submit(REQUIREMENT, illegal,
                    null), ApiException.class);
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.detail()).containsEntry("field", "estimateDays");
        }
        verify(engine, never()).start(any());
    }

    @Test
    void nonHalfDayPrecisionIsRejected() {
        given(requirement(RequirementRules.TYPE_PDR, "PENDING_ANALYSIS"));
        ApiException error = catchThrowableOfType(() -> service().submit(REQUIREMENT,
                new BigDecimal("8.3"), null), ApiException.class);
        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo(RequirementRules.INVALID_DAY_PRECISION);
        verify(engine, never()).start(any());
    }

    @Test
    void noteIsCappedAtFiveHundredChars() {
        given(requirement(RequirementRules.TYPE_PDR, "PENDING_ANALYSIS"));
        ApiException error = catchThrowableOfType(() -> service().submit(REQUIREMENT,
                new BigDecimal("8.5"), "说".repeat(501)), ApiException.class);
        assertThat(error.status()).isEqualTo(400);
        assertThat(error.detail()).containsEntry("field", "note");
        verify(engine, never()).start(any());
    }

    @Test
    void submitterNeedsTheDeliverCell() {
        given(requirement(RequirementRules.TYPE_PDR, "PENDING_ANALYSIS"));
        WbsEstimateService denied = new WbsEstimateService(requirements, provider(engine), store,
                instances, audit, (menu, action) -> PermissionMatrix.Access.DENY,
                new WorkflowJson(new ObjectMapper()), new RequirementStateMachine());
        ApiException error = catchThrowableOfType(() -> denied.submit(REQUIREMENT,
                new BigDecimal("8.5"), null), ApiException.class);
        assertThat(error.status()).isEqualTo(403);
        assertThat(error.code()).isEqualTo(RequirementErrors.FORBIDDEN);
        // r41：403 的落点统一指向动作码 req:estimate_submit（= req:deliver any-of task:manage），
        // 内部账号的判定仍是 req:deliver 那一格，厂商账号走 estimate_submit——提示文案只说出口。
        assertThat(error.detail()).containsEntry("required", "req:estimate_submit");
        verify(engine, never()).start(any());
    }

    @Test
    void submitStartsWbsEstimateInstanceWithSnapshot() {
        RequirementEntity entity = given(requirement(RequirementRules.TYPE_PDR,
                "PENDING_ANALYSIS"));
        when(instances.findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT, ApprovalInstance.ACTIVE))
                .thenReturn(Optional.of(instance(42L, ApprovalInstance.STATUS_PENDING,
                        ApprovalInstance.ACTIVE)));
        when(engine.start(any())).thenReturn(ApprovalViews.InstanceView.of(
                instance(42L, ApprovalInstance.STATUS_PENDING, ApprovalInstance.ACTIVE)));

        WbsEstimateViews.EstimateView view = service().submit(REQUIREMENT,
                new BigDecimal("8.5"), "  初评  ");

        ArgumentCaptor<StartApproval> captor = ArgumentCaptor.forClass(StartApproval.class);
        verify(engine).start(captor.capture());
        StartApproval command = captor.getValue();
        assertThat(command.bizType()).isEqualTo("WBS_ESTIMATE");
        assertThat(command.bizId()).isEqualTo(REQUIREMENT);
        assertThat(command.reqType()).isEqualTo(RequirementRules.TYPE_PDR);
        assertThat(command.attributes())
                .containsEntry("estimateDays", new BigDecimal("8.5"))
                .containsEntry("note", "初评")
                .containsEntry("source", "UI");
        // 单据人天只在通过后才回写需求；提交时需求人天保持原值（AC-2）。
        assertThat(entity.getEstimateDays()).isNull();
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.instanceId()).isEqualTo(42L);
        verify(audit).record(eq("ESTIMATE_SUBMIT"), eq("REQUIREMENT"), eq(REQUIREMENT), any());
    }

    @Test
    void noteIsOptional() {
        given(requirement(RequirementRules.TYPE_RD, "TODO"));
        when(instances.findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT, ApprovalInstance.ACTIVE))
                .thenReturn(Optional.of(instance(42L, ApprovalInstance.STATUS_PENDING,
                        ApprovalInstance.ACTIVE)));
        when(engine.start(any())).thenReturn(ApprovalViews.InstanceView.of(
                instance(42L, ApprovalInstance.STATUS_PENDING, ApprovalInstance.ACTIVE)));

        service().submit(REQUIREMENT, new BigDecimal("3.0"), null);

        ArgumentCaptor<StartApproval> captor = ArgumentCaptor.forClass(StartApproval.class);
        verify(engine).start(captor.capture());
        assertThat(captor.getValue().attributes()).doesNotContainKey("note");
    }

    // ---------------------------------------------------------------- 工具

    private WbsEstimateService service() {
        return new WbsEstimateService(requirements, provider(engine), store, instances, audit,
                ALLOW_ALL, new WorkflowJson(new ObjectMapper()), new RequirementStateMachine());
    }

    private RequirementEntity given(RequirementEntity entity) {
        when(requirements.requireVisible(REQUIREMENT)).thenReturn(entity);
        return entity;
    }

    private RequirementEntity requirement(String type, String status) {
        RequirementEntity entity = new RequirementEntity();
        entity.setId(REQUIREMENT);
        entity.setTenantId(TENANT);
        entity.setType(type);
        entity.setStatus(status);
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
        return instance;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
