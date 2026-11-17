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
import com.example.req.approval.WorkflowJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AC-10：通过后人天变动超阈值即失效（STALE 读取时派生）。阈值严格大于 20%：
 * 10 → 12.0 放行，10 → 12.5 拦截；2 → 0.5 失效；人天清空必然失效。
 */
class WbsEstimateStaleTest {

    private static final long TENANT = 1L;
    private static final long REQUIREMENT = 11L;
    private static final ActionPermissionResolver ALLOW_ALL =
            (menu, action) -> PermissionMatrix.Access.ALLOW;

    private final ApprovalStore store = mock(ApprovalStore.class);
    private final ApprovalInstanceRepository instances = mock(ApprovalInstanceRepository.class);
    private final WbsEstimateService service = new WbsEstimateService(
            mock(RequirementService.class), provider(mock(ApprovalEngine.class)), store, instances,
            mock(AuditService.class), ALLOW_ALL, new WorkflowJson(new ObjectMapper()),
            new RequirementStateMachine());

    @BeforeEach
    void setUp() {
        TenantContext.set(DataScope.system(TENANT));
        when(store.history(anyLong())).thenReturn(List.of());
        when(store.tasks(anyLong())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void twentyPercentExactlyIsStillApproved() {
        givenApproved(new BigDecimal("10"));
        RequirementEntity entity = requirement(new BigDecimal("12.0"));
        assertThat(service.statusOf(entity)).isEqualTo("APPROVED");
        assertThatCode(() -> service.ensureGate(entity, "schedule")).doesNotThrowAnyException();
        assertThat(service.applyGate(entity, List.of("schedule"))).contains("schedule");
        assertThat(service.view(entity).approvedDays()).isEqualByComparingTo("10");
    }

    @Test
    void beyondTwentyPercentIsStaleAndBlocksTheGate() {
        givenApproved(new BigDecimal("10"));
        RequirementEntity entity = requirement(new BigDecimal("12.5"));
        assertThat(service.statusOf(entity)).isEqualTo("STALE");
        ApiException error = catchThrowableOfType(() -> service.ensureGate(entity, "schedule"),
                ApiException.class);
        assertThat(error.status()).isEqualTo(409);
        assertThat(error.detail()).containsEntry("status", "STALE");
        assertThat(service.applyGate(entity, List.of("schedule"))).doesNotContain("schedule");
        WbsEstimateViews.EstimateView view = service.view(entity);
        assertThat(view.status()).isEqualTo("STALE");
        assertThat(view.reason()).contains("25.0%").contains("超过 20% 阈值");
    }

    @Test
    void smallBaseIsAlsoStale() {
        givenApproved(new BigDecimal("2"));
        RequirementEntity entity = requirement(new BigDecimal("0.5"));
        assertThat(service.statusOf(entity)).isEqualTo("STALE");
    }

    @Test
    void clearedDaysAreAlwaysStale() {
        givenApproved(new BigDecimal("10"));
        RequirementEntity entity = requirement(null);
        assertThat(service.statusOf(entity)).isEqualTo("STALE");
        WbsEstimateViews.EstimateView view = service.view(entity);
        assertThat(view.status()).isEqualTo("STALE");
        assertThat(view.reason()).contains("人天缺失");
    }

    @Test
    void stalenessIsDerivedOnReadSoBothWritePathsAreCovered() {
        givenApproved(new BigDecimal("10"));
        RequirementEntity entity = requirement(new BigDecimal("10"));
        assertThat(service.statusOf(entity)).isEqualTo("APPROVED");
        // 模拟 PUT extra.estimateDays / POST deliver 把需求人天改掉：无需任何落库标记即为 STALE。
        entity.setEstimateDays(new BigDecimal("12.5"));
        assertThat(service.statusOf(entity)).isEqualTo("STALE");
        entity.setEstimateDays(new BigDecimal("11.0"));
        assertThat(service.statusOf(entity)).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------- 工具

    private void givenApproved(BigDecimal days) {
        ApprovalInstance approved = new ApprovalInstance();
        approved.setId(9L);
        approved.setTenantId(TENANT);
        approved.setBizType(WbsEstimateService.BIZ_TYPE);
        approved.setBizId(REQUIREMENT);
        approved.setStatus(ApprovalInstance.STATUS_APPROVED);
        approved.setStartedAt(LocalDateTime.now().minusDays(1));
        approved.setEndedAt(LocalDateTime.now());
        when(instances.findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                TENANT, WbsEstimateService.BIZ_TYPE, REQUIREMENT))
                .thenReturn(Optional.of(approved));
        ApprovalHistory submit = new ApprovalHistory();
        submit.setAction(ApprovalHistory.SUBMIT);
        submit.setDetailJson("{\"document\":{\"estimateDays\":" + days.toPlainString() + "}}");
        when(store.history(9L)).thenReturn(List.of(submit));
    }

    private RequirementEntity requirement(BigDecimal days) {
        RequirementEntity entity = new RequirementEntity();
        entity.setId(REQUIREMENT);
        entity.setTenantId(TENANT);
        entity.setType(RequirementRules.TYPE_PDR);
        entity.setStatus("PENDING_ANALYSIS");
        entity.setReporterId(5L);
        entity.setDomainId(1L);
        entity.setEstimateDays(days);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
