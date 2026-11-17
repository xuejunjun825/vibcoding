package com.example.req.requirement;

import com.example.req.approval.ApprovalHistory;
import com.example.req.approval.ApprovalStore;
import com.example.req.approval.WorkflowJson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-3/AC-4：回调落人天与动态。通过 → 单据人天写需求 + 记为 ESTIMATE_APPROVED 动态；
 * 驳回 → 只记动态（含原因）不动人天；撤回 → 记动态。
 */
class WbsEstimateCallbackTest {

    private static final long REQUIREMENT = 7L;
    private static final long INSTANCE = 42L;

    private final RequirementService requirements = mock(RequirementService.class);
    private final WbsEstimateService estimates = mock(WbsEstimateService.class);
    private final ApprovalStore store = mock(ApprovalStore.class);
    private final WbsEstimateCallback callback = new WbsEstimateCallback(requirements, estimates,
            store, new WorkflowJson(new ObjectMapper()));

    @Test
    void bizTypeIsWbsEstimate() {
        assertThat(callback.bizType()).isEqualTo("WBS_ESTIMATE");
    }

    @Test
    void approvalWritesDaysAndEvent() {
        givenSubmitSnapshot("{\"document\":{\"estimateDays\":8.5,\"note\":\"初评\"}}");

        callback.onApproved(REQUIREMENT, INSTANCE);

        verify(requirements).applyApprovedEstimate(eq(REQUIREMENT),
                eq(new BigDecimal("8.5")), eq(INSTANCE));
        verify(estimates).supersedeActive(REQUIREMENT, INSTANCE, 0L);
        // AC-5：新单通过后旧 APPROVED 落 SUPERSEDED，任意时刻只有一张有效单。
        verify(estimates).supersedeApproved(REQUIREMENT, INSTANCE, 0L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(requirements).recordEstimateEvent(eq(REQUIREMENT), eq("ESTIMATE_APPROVED"),
                detail.capture());
        assertThat(detail.getValue())
                .containsEntry("instanceId", INSTANCE)
                .containsEntry("estimateDays", new BigDecimal("8.5"));
    }

    @Test
    void rejectionRecordsReasonAndLeavesDaysAlone() {
        callback.onRejected(REQUIREMENT, INSTANCE, "预算不足");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(requirements).recordEstimateEvent(eq(REQUIREMENT), eq("ESTIMATE_REJECTED"),
                detail.capture());
        assertThat(detail.getValue())
                .containsEntry("instanceId", INSTANCE)
                .containsEntry("reason", "预算不足");
        verify(requirements, never()).applyApprovedEstimate(anyLong(), any(), anyLong());
    }

    @Test
    void withdrawalRecordsEvent() {
        callback.onWithdrawn(REQUIREMENT, INSTANCE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(requirements).recordEstimateEvent(eq(REQUIREMENT), eq("ESTIMATE_WITHDRAWN"),
                detail.capture());
        assertThat(detail.getValue()).containsEntry("instanceId", INSTANCE);
        verify(requirements, never()).applyApprovedEstimate(anyLong(), any(), anyLong());
    }

    private void givenSubmitSnapshot(String detailJson) {
        ApprovalHistory submit = new ApprovalHistory();
        submit.setAction(ApprovalHistory.SUBMIT);
        submit.setDetailJson(detailJson);
        when(store.history(INSTANCE)).thenReturn(List.of(submit));
    }
}
