package com.example.req.deploy;

import com.example.req.approval.ApprovalInstanceRepository;
import com.example.req.integration.DeliverGateClient;
import com.example.req.integration.DeliverQualityGateClient;
import com.example.req.requirement.RequirementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * R2 / AC-5：{@code legacy} 是读时派生，「没有第 6 项就是历史结论」——空集（NULL 快照）同样成立。
 *
 * <p>这条语义只有一处实现（{@link DeployViews.AdmissionSnapshot#legacyOf}），
 * 详情接口与前端都消费它；这里钉住空集与 5 项/6 项两个边界，避免再退回"空集 ⇒ 非历史"。</p>
 */
class AdmissionLegacySemanticsTest {

    private final AdmissionEvaluator evaluator = new AdmissionEvaluator(
            mock(RequirementRepository.class),
            mock(DeployOrderRequirementRepository.class),
            mock(ApprovalInstanceRepository.class),
            mock(DeliverGateClient.class),
            mock(DeliverQualityGateClient.class),
            new ObjectMapper());

    @Test
    @DisplayName("空集/NULL 一律是历史结论（旧单从未评估过质量门）")
    void emptyItemsAreLegacy() {
        assertTrue(DeployViews.AdmissionSnapshot.legacyOf(null));
        assertTrue(DeployViews.AdmissionSnapshot.legacyOf(List.of()));
        assertTrue(new DeployViews.AdmissionSnapshot(List.of(), null, false).legacy(),
                "3 参构造必须让空集派生 legacy=true");
    }

    @Test
    @DisplayName("5 项旧快照 legacy=true，含第 6 项的新快照 legacy=false")
    void sixItemSnapshotIsNotLegacy() {
        List<AdmissionItem> five = List.of(
                AdmissionItem.pass(AdmissionItem.REQ_TEST_PASSED, "测试", "ok"),
                AdmissionItem.pass(AdmissionItem.NO_OPEN_HIGH_DEFECT, "缺陷", "ok"),
                AdmissionItem.pass(AdmissionItem.MR_FILLED, "MR", "ok"),
                AdmissionItem.pass(AdmissionItem.MAN_DAYS_FILLED, "人天", "ok"),
                AdmissionItem.pass(AdmissionItem.REQ_APPROVALS_DONE, "审批", "ok"));
        assertTrue(DeployViews.AdmissionSnapshot.legacyOf(five));

        List<AdmissionItem> six = new java.util.ArrayList<>(five);
        six.add(AdmissionItem.pass(AdmissionItem.QUALITY_GATE, "质量门", "PASS"));
        assertFalse(DeployViews.AdmissionSnapshot.legacyOf(six));
    }

    @Test
    @DisplayName("admission_result_json 为 NULL 的旧单：items=[] 且 legacy=true、gate=null")
    void nullSnapshotRendersAsLegacy() {
        DeployOrderEntity order = new DeployOrderEntity();
        order.setId(1L);
        order.setTenantId(1L);
        order.setForcedRelease(false);
        assertNull(order.getAdmissionResultJson());

        DeployViews.AdmissionSnapshot snapshot = evaluator.snapshot(order);

        assertEquals(List.of(), snapshot.items());
        assertNull(snapshot.checkedAt());
        assertFalse(snapshot.stale());
        assertTrue(snapshot.legacy(), "NULL 快照 = 从未评估 → 历史单据");
        assertNull(snapshot.gate(), "历史单据不得伪造质量门证据");
        assertNull(snapshot.signature());
    }
}
