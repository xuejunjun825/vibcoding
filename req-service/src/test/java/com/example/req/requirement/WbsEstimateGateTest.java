package com.example.req.requirement;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.code.CodeGenerator;
import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.contract.ActionPermissionResolver;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalHistory;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalInstanceRepository;
import com.example.req.approval.ApprovalStore;
import com.example.req.approval.WorkflowJson;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AC-7/8/9/11/12：工时评估门的 6 状态 × 2 动作矩阵，以及 transition 里的判定顺序
 * （403/404 → 409 ILLEGAL_TRANSITION → 既有门 → 409 ESTIMATE_NOT_APPROVED，且被拦时不落库）。
 */
class WbsEstimateGateTest {

    private static final long TENANT = 1L;
    private static final long REQUIREMENT = 11L;
    private static final ActionPermissionResolver ALLOW_ALL =
            (menu, action) -> PermissionMatrix.Access.ALLOW;

    private final ApprovalStore store = mock(ApprovalStore.class);
    private final ApprovalInstanceRepository instances = mock(ApprovalInstanceRepository.class);
    private final RequirementStateMachine stateMachine = new RequirementStateMachine();
    private final WorkflowJson json = new WorkflowJson(new ObjectMapper());
    private final WbsEstimateService estimates = new WbsEstimateService(
            mock(RequirementService.class), provider(mock(ApprovalEngine.class)),
            store, instances, mock(AuditService.class), ALLOW_ALL, json, stateMachine);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------- 12 组矩阵

    @Test
    void gateMatrixCoversSixStatusesAndBothGatedActions() {
        List<Case> cases = List.of(
                new Case("NONE", null, new BigDecimal("10"), false),
                new Case("PENDING", instance(1L, ApprovalInstance.STATUS_PENDING,
                        ApprovalInstance.ACTIVE), new BigDecimal("10"), false),
                new Case("REJECTED", instance(2L, ApprovalInstance.STATUS_REJECTED, null),
                        new BigDecimal("10"), false),
                new Case("SUPERSEDED", instance(3L, ApprovalInstance.STATUS_SUPERSEDED, null),
                        new BigDecimal("10"), false),
                new Case("APPROVED", instance(4L, ApprovalInstance.STATUS_APPROVED, null),
                        new BigDecimal("10"), true),
                // 通过后需求人天改到 12.5：12.5−10 = 25% > 20% → 读取时派生 STALE。
                new Case("STALE", instance(5L, ApprovalInstance.STATUS_APPROVED, null),
                        new BigDecimal("12.5"), false));

        int checked = 0;
        for (Case c : cases) {
            reset(instances);
            given(c.instance());
            for (String[] pair : new String[][]{
                    {RequirementRules.TYPE_PDR, "schedule"},
                    {RequirementRules.TYPE_RD, "start_dev"}}) {
                RequirementEntity entity = requirement(pair[0], c.days());
                assertThat(estimates.statusOf(entity))
                        .as("%s 的六态派生", c.status())
                        .isEqualTo(c.status());
                assertThat(estimates.gated(pair[0], pair[1])).isTrue();
                checked++;
                if (c.open()) {
                    assertThatCode(() -> estimates.ensureGate(entity, pair[1]))
                            .doesNotThrowAnyException();
                    assertThat(estimates.applyGate(entity, List.of(pair[1]))).contains(pair[1]);
                } else {
                    ApiException blocked = catchThrowableOfType(
                            () -> estimates.ensureGate(entity, pair[1]), ApiException.class);
                    assertThat(blocked).isNotNull();
                    assertThat(blocked.status()).isEqualTo(409);
                    assertThat(blocked.code()).isEqualTo(RequirementErrors.ESTIMATE_NOT_APPROVED);
                    assertThat(blocked.detail().keySet())
                            .containsExactlyInAnyOrder("requirementId", "status", "required");
                    assertThat(blocked.detail().get("requirementId")).isEqualTo(REQUIREMENT);
                    assertThat(blocked.detail().get("status")).isEqualTo(c.status());
                    assertThat(blocked.detail().get("required")).isEqualTo("ESTIMATE_APPROVED");
                    assertThat(estimates.applyGate(entity, List.of(pair[1])))
                            .doesNotContain(pair[1]);
                    // 非受门动作零影响。
                    assertThat(estimates.applyGate(entity, List.of("deliver"))).contains("deliver");
                }
            }
        }
        assertThat(checked).isEqualTo(12);
    }

    @Test
    void busAndUngatedActionsAreNeverFiltered() {
        given(null);
        RequirementEntity bus = requirement(RequirementRules.TYPE_BUS, null);
        assertThat(estimates.applyGate(bus, List.of("schedule", "deliver")))
                .containsExactly("schedule", "deliver");
        assertThat(estimates.gated(RequirementRules.TYPE_BUS, "schedule")).isFalse();
        // PDR 的 start_dev 不受门（PRD §5.7：只锁 PDR+schedule、RD+start_dev）。
        assertThat(estimates.gated(RequirementRules.TYPE_PDR, "start_dev")).isFalse();
        assertThat(estimates.gated(RequirementRules.TYPE_RD, "schedule")).isFalse();
    }

    // ------------------------------------------------------------- 判定顺序

    @Test
    void illegalTransitionWinsOverEstimateGate() {
        RequirementRepository repository = mock(RequirementRepository.class);
        RequirementStateMachine machine = mock(RequirementStateMachine.class);
        AuditService audit = mock(AuditService.class);
        RequirementEntity entity = requirement(RequirementRules.TYPE_PDR, null);
        when(repository.findByIdAndTenantId(REQUIREMENT, TENANT)).thenReturn(Optional.of(entity));
        when(machine.isTerminal(anyString(), anyString())).thenReturn(false);
        when(machine.target(RequirementRules.TYPE_PDR, "PENDING_ANALYSIS", "schedule"))
                .thenThrow(RequirementErrors.illegalTransition(RequirementRules.TYPE_PDR,
                        "PENDING_ANALYSIS", "schedule", List.of()));

        RequirementService service = service(repository, machine, audit);

        TenantContext.set(DataScope.system(TENANT));
        ApiException error = catchThrowableOfType(
                () -> service.transition(REQUIREMENT, "schedule", null, "UI", (Long) null),
                ApiException.class);
        assertThat(error.code()).isEqualTo(RequirementErrors.ILLEGAL_TRANSITION);
        // 门根本没有被问过：状态机先拒。
        verifyNoInteractions(instances);
        verify(repository, never()).save(any());
    }

    @Test
    void notVisibleWinsOverEstimateGate() {
        RequirementRepository repository = mock(RequirementRepository.class);
        when(repository.findByIdAndTenantId(REQUIREMENT, TENANT)).thenReturn(Optional.empty());
        RequirementService service = service(repository, mock(RequirementStateMachine.class),
                mock(AuditService.class));

        TenantContext.set(DataScope.system(TENANT));
        ApiException error = catchThrowableOfType(
                () -> service.transition(REQUIREMENT, "schedule", null, "UI", (Long) null),
                ApiException.class);
        assertThat(error.status()).isEqualTo(404);
        assertThat(error.code()).isEqualTo(RequirementErrors.NOT_FOUND);
        verifyNoInteractions(instances);
    }

    @Test
    void blockedGateNeitherSavesNorAudits() {
        RequirementRepository repository = mock(RequirementRepository.class);
        RequirementStateMachine machine = mock(RequirementStateMachine.class);
        AuditService audit = mock(AuditService.class);
        NotificationWriter notifications = mock(NotificationWriter.class);
        RequirementEntity entity = requirement(RequirementRules.TYPE_PDR, null);
        when(repository.findByIdAndTenantId(REQUIREMENT, TENANT)).thenReturn(Optional.of(entity));
        when(machine.isTerminal(anyString(), anyString())).thenReturn(false);
        when(machine.target(RequirementRules.TYPE_PDR, "PENDING_ANALYSIS", "schedule"))
                .thenReturn("SCHEDULED");
        given(null);

        RequirementService service = service(repository, machine, audit, notifications);

        TenantContext.set(DataScope.system(TENANT));
        ApiException error = catchThrowableOfType(
                () -> service.transition(REQUIREMENT, "schedule", null, "UI", (Long) null),
                ApiException.class);
        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo(RequirementErrors.ESTIMATE_NOT_APPROVED);
        assertThat(error.detail().keySet())
                .containsExactlyInAnyOrder("requirementId", "status", "required");
        assertThat(entity.getStatus()).isEqualTo("PENDING_ANALYSIS");
        verify(repository, never()).save(any());
        verify(audit, never()).record(eq("STATE_CHANGE"), any(), any(), any());
        verify(notifications, never()).send(anyLong(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- 工具

    private record Case(String status, ApprovalInstance instance, BigDecimal days, boolean open) {
    }

    private RequirementService service(RequirementRepository repository,
                                       RequirementStateMachine machine, AuditService audit) {
        return service(repository, machine, audit, mock(NotificationWriter.class));
    }

    private RequirementService service(RequirementRepository repository,
                                       RequirementStateMachine machine, AuditService audit,
                                       NotificationWriter notifications) {
        ObjectProvider<WbsEstimateService> gateProvider = provider(estimates);
        // 标签端口传 null：本测试只关心人天门，tagsOf(null 端口) 返回空表（见 RequirementService 注释）。
        // r40：变更单的版本关联端口在 PUT 收口里才用到，本测试不覆盖，传 null provider。
        // r41：项目组端口只在请求带 projectId 时用到，本测试不覆盖，传 null。
        return new RequirementService(repository,
                mock(RequirementDomainRelRepository.class), machine, mock(CodeGenerator.class),
                audit, notifications, provider(null), provider(null),
                mock(JdbcTemplate.class), ALLOW_ALL, gateProvider, null, null, null);
    }

    private void given(ApprovalInstance instance) {
        when(instances.findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT))
                .thenReturn(Optional.ofNullable(instance));
        when(instances.findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT, ApprovalInstance.ACTIVE))
                .thenReturn(instance != null
                        && ApprovalInstance.ACTIVE.equals(instance.getActiveFlag())
                        ? Optional.of(instance) : Optional.empty());
        ApprovalHistory submit = new ApprovalHistory();
        submit.setAction(ApprovalHistory.SUBMIT);
        submit.setDetailJson("{\"document\":{\"estimateDays\":10,\"note\":\"初评\"}}");
        when(store.history(anyLong())).thenReturn(List.of(submit));
    }

    private RequirementEntity requirement(String type, BigDecimal days) {
        RequirementEntity entity = new RequirementEntity();
        entity.setId(REQUIREMENT);
        entity.setTenantId(TENANT);
        entity.setType(type);
        entity.setStatus(RequirementRules.TYPE_RD.equals(type) ? "TODO" : "PENDING_ANALYSIS");
        entity.setReporterId(5L);
        entity.setDomainId(1L);
        entity.setEstimateDays(days);
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
