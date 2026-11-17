package com.example.req.requirement;

import com.example.platform.common.audit.AuditService;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AC-2/AC-7（r27）：需求池批量派生与详情的六态必须完全同源。
 *
 * <p>同一批需求同时跑「逐条 {@link WbsEstimateService#statusOf}」与「批量
 * {@link WbsEstimateService#statusesOf}」两条路径，结果必须逐 id 相等——派生逻辑一旦出现
 * 第二份实现，这里立刻变红。同时锁住「每页查询数与行数无关」和「门只摘受门动作、保序」。</p>
 */
class WbsEstimateBatchParityTest {

    private static final long TENANT = 1L;
    private static final ActionPermissionResolver ALLOW_ALL =
            (menu, action) -> PermissionMatrix.Access.ALLOW;

    private ApprovalStore store;
    private ApprovalInstanceRepository instances;
    private WbsEstimateService estimates;

    @BeforeEach
    void setUp() {
        TenantContext.set(DataScope.system(TENANT));
        store = mock(ApprovalStore.class);
        instances = mock(ApprovalInstanceRepository.class);
        estimates = new WbsEstimateService(mock(RequirementService.class),
                provider(mock(ApprovalEngine.class)), store, instances,
                mock(AuditService.class), ALLOW_ALL, new WorkflowJson(new ObjectMapper()),
                new RequirementStateMachine());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------- 批量 = 逐条

    @Test
    void batchStatusesEqualPerRowStatus() {
        Map<Long, RequirementEntity> rows = new LinkedHashMap<>();
        Map<Long, ApprovalInstance> active = new LinkedHashMap<>();
        Map<Long, ApprovalInstance> latest = new LinkedHashMap<>();
        Map<Long, List<ApprovalHistory>> histories = new LinkedHashMap<>();

        rows.put(1L, requirement(1L, RequirementRules.TYPE_PDR, new BigDecimal("10")));   // 从未评估
        rows.put(2L, requirement(2L, RequirementRules.TYPE_PDR, new BigDecimal("10")));   // 在途
        active.put(2L, instance(21L, 2L, ApprovalInstance.STATUS_PENDING, ApprovalInstance.ACTIVE));
        rows.put(3L, requirement(3L, RequirementRules.TYPE_PDR, new BigDecimal("10")));   // 已通过
        latest.put(3L, instance(31L, 3L, ApprovalInstance.STATUS_APPROVED, null));
        histories.put(31L, List.of(submit(31L, "10")));
        rows.put(4L, requirement(4L, RequirementRules.TYPE_RD, new BigDecimal("13")));    // 失效（30%）
        latest.put(4L, instance(41L, 4L, ApprovalInstance.STATUS_APPROVED, null));
        histories.put(41L, List.of(submit(41L, "10")));
        rows.put(5L, requirement(5L, RequirementRules.TYPE_PDR, new BigDecimal("10")));   // 已驳回
        latest.put(5L, instance(51L, 5L, ApprovalInstance.STATUS_REJECTED, null));
        rows.put(6L, requirement(6L, RequirementRules.TYPE_RD, new BigDecimal("10")));    // 仅撤回
        latest.put(6L, instance(61L, 6L, ApprovalInstance.STATUS_WITHDRAWN, null));

        when(instances.findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                eq(TENANT), eq(WbsEstimateService.BIZ_TYPE), anyLong(), anyInt()))
                .thenAnswer(call -> Optional.ofNullable(
                        active.get(call.getArgument(2, Long.class))));
        when(instances.findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                eq(TENANT), eq(WbsEstimateService.BIZ_TYPE), anyLong()))
                .thenAnswer(call -> Optional.ofNullable(
                        latest.get(call.getArgument(2, Long.class))));
        when(store.history(anyLong()))
                .thenAnswer(call -> histories.getOrDefault(call.getArgument(0, Long.class), List.of()));
        when(instances.findAllByTenantIdAndBizTypeAndBizIdInOrderByIdDesc(
                eq(TENANT), eq(WbsEstimateService.BIZ_TYPE), anyCollection()))
                .thenAnswer(call -> ordered(call.getArgument(2), active, latest));
        when(store.histories(anyCollection()))
                .thenAnswer(call -> buckets(call.getArgument(0), histories));

        List<RequirementEntity> entities = new ArrayList<>(rows.values());
        Map<Long, String> perRow = new LinkedHashMap<>();
        for (RequirementEntity entity : entities) {
            perRow.put(entity.getId(), estimates.statusOf(entity));
        }

        Map<Long, String> batch = estimates.statusesOf(subjects(entities));

        assertThat(batch).isEqualTo(perRow);
        assertThat(perRow.values()).containsExactly("NONE", "PENDING", "APPROVED", "STALE",
                "REJECTED", "NONE");
    }

    // ------------------------------------------------- 每页查询数恒定

    @Test
    void batchUsesConstantQueries() {
        RequirementEntity approved = requirement(11L, RequirementRules.TYPE_PDR, new BigDecimal("10"));
        RequirementEntity stale = requirement(12L, RequirementRules.TYPE_RD, new BigDecimal("20"));
        RequirementEntity idle = requirement(13L, RequirementRules.TYPE_PDR, new BigDecimal("5"));
        ApprovalInstance first = instance(111L, 11L, ApprovalInstance.STATUS_APPROVED, null);
        ApprovalInstance second = instance(121L, 12L, ApprovalInstance.STATUS_APPROVED, null);

        when(instances.findAllByTenantIdAndBizTypeAndBizIdInOrderByIdDesc(
                eq(TENANT), eq(WbsEstimateService.BIZ_TYPE), anyCollection()))
                .thenReturn(List.of(second, first));
        when(store.histories(anyCollection())).thenReturn(Map.of(
                111L, List.of(submit(111L, "10")),
                121L, List.of(submit(121L, "10"))));

        Map<Long, String> batch = estimates.statusesOf(
                subjects(List.of(approved, stale, idle)));

        assertThat(batch).containsEntry(11L, "APPROVED")
                .containsEntry(12L, "STALE")
                .containsEntry(13L, "NONE");
        verify(instances, times(1)).findAllByTenantIdAndBizTypeAndBizIdInOrderByIdDesc(
                eq(TENANT), eq(WbsEstimateService.BIZ_TYPE), anyCollection());
        verify(store, times(1)).histories(anyCollection());
        verify(instances, never()).findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                anyLong(), eq(WbsEstimateService.BIZ_TYPE), anyLong(), anyInt());
        verify(instances, never()).findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                anyLong(), eq(WbsEstimateService.BIZ_TYPE), anyLong());
        verify(store, never()).history(anyLong());
    }

    // ------------------------------------------------------- 门过滤保序

    @Test
    void gateKeepsOrderAndDropsOnlyGatedActions() {
        List<String> all = List.of("schedule", "edit", "start_dev", "deliver");

        assertThat(estimates.applyGate(RequirementRules.TYPE_PDR,
                WbsEstimateService.STATUS_PENDING, all))
                .containsExactly("edit", "start_dev", "deliver");
        assertThat(estimates.applyGate(RequirementRules.TYPE_PDR,
                WbsEstimateService.STATUS_APPROVED, all)).isEqualTo(all);
        assertThat(estimates.applyGate(RequirementRules.TYPE_RD,
                WbsEstimateService.STATUS_STALE, all))
                .containsExactly("schedule", "edit", "deliver");
        assertThat(estimates.applyGate(RequirementRules.TYPE_BUS,
                WbsEstimateService.STATUS_NONE, all)).isEqualTo(all);
    }

    // ------------------------------------------------------- BUS 不取数

    @Test
    void busIsNeverEvaluated() {
        RequirementEntity bus = requirement(71L, RequirementRules.TYPE_BUS, new BigDecimal("3"));

        assertThat(estimates.statusesOf(subjects(List.of(bus)))).isEmpty();
        verifyNoInteractions(instances);
        verifyNoInteractions(store);
        assertThat(estimates.applyGate(RequirementRules.TYPE_BUS,
                WbsEstimateService.STATUS_NONE, List.of("schedule"))).containsExactly("schedule");
    }

    // ------------------------------------------------------------ helpers

    private static List<WbsEstimateService.GateSubject> subjects(List<RequirementEntity> entities) {
        return entities.stream()
                .map(entity -> new WbsEstimateService.GateSubject(entity.getId(), entity.getType(),
                        entity.getEstimateDays()))
                .toList();
    }

    private static List<ApprovalInstance> ordered(Collection<Long> ids,
                                                  Map<Long, ApprovalInstance> active,
                                                  Map<Long, ApprovalInstance> latest) {
        List<ApprovalInstance> out = new ArrayList<>();
        for (Long id : ids) {
            if (active.containsKey(id)) {
                out.add(active.get(id));
            }
            ApprovalInstance last = latest.get(id);
            if (last != null && last != active.get(id)) {
                out.add(last);
            }
        }
        out.sort(Comparator.comparing(ApprovalInstance::getId).reversed());
        return out;
    }

    private static Map<Long, List<ApprovalHistory>> buckets(
            Collection<Long> ids, Map<Long, List<ApprovalHistory>> histories) {
        Map<Long, List<ApprovalHistory>> out = new LinkedHashMap<>();
        for (Long id : ids) {
            out.put(id, histories.getOrDefault(id, List.of()));
        }
        return out;
    }

    private static RequirementEntity requirement(long id, String type, BigDecimal days) {
        RequirementEntity entity = new RequirementEntity();
        entity.setId(id);
        entity.setTenantId(TENANT);
        entity.setType(type);
        entity.setStatus("PENDING_ANALYSIS");
        entity.setReporterId(5L);
        entity.setDomainId(1L);
        entity.setEstimateDays(days);
        return entity;
    }

    private static ApprovalInstance instance(long id, long bizId, String status, Integer activeFlag) {
        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(id);
        instance.setTenantId(TENANT);
        instance.setBizType(WbsEstimateService.BIZ_TYPE);
        instance.setBizId(bizId);
        instance.setStatus(status);
        instance.setActiveFlag(activeFlag);
        instance.setStartedAt(LocalDateTime.now().minusDays(1));
        instance.setEndedAt(LocalDateTime.now());
        return instance;
    }

    private static ApprovalHistory submit(long instanceId, String days) {
        ApprovalHistory history = new ApprovalHistory();
        history.setInstanceId(instanceId);
        history.setAction(ApprovalHistory.SUBMIT);
        history.setDetailJson("{\"document\":{\"estimateDays\":" + days + "}}");
        return history;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
