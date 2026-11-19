package com.example.req.deploy;

import com.example.platform.contract.GateDecision;
import com.example.platform.contract.quality.GateSnapshot;
import com.example.req.integration.admission.AdmissionViews;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 6 项准入的文案四态（PRD AC-6 / TECHNICAL §5.4）。
 *
 * <p>这是本需求唯一会变的对外字符串，所以逐字钉住三件事：</p>
 * <ul>
 *   <li>有 {@code SKIPPED} 时不许再说「全部通过」——跳过是「本轮未启用」，不是「已检且通过」；</li>
 *   <li>没有跳过时保留原句（既有夹具与对外表述都不受影响）；</li>
 *   <li>判定（PASS/FAIL）仍然只由 {@code evidence.result} 派生，文案改不了结论。</li>
 * </ul>
 */
class AdmissionPolicySkippedDetailTest {

    private static final long REQ = 501L;

    private static AdmissionPolicy.ReqFact fact() {
        return new AdmissionPolicy.ReqFact(REQ, "RD-0001", "RD", "TEST_PASSED", "https://git/mr/1",
                new BigDecimal("3"), new BigDecimal("2.5"), List.of());
    }

    private static GateSnapshot.GateRuleResult rule(String code, String status) {
        return new GateSnapshot.GateRuleResult(code, status, true, "ok", "ok", null);
    }

    /** 演示版的真实形状：QG-01..QG-04 通过，QG-05（本轮未接入外部质量源）SKIPPED。 */
    private static DeployViews.GateEvidence gate(String result, String... statuses) {
        String[] codes = {"QG-01", "QG-02", "QG-03", "QG-04", "QG-05"};
        List<GateSnapshot.GateRuleResult> rules = new java.util.ArrayList<>();
        for (int i = 0; i < codes.length && i < statuses.length; i++) {
            rules.add(rule(codes[i], statuses[i]));
        }
        return new DeployViews.GateEvidence(9001L, 7001L, 501L, result, "digest-abc",
                Instant.parse("2026-11-19T02:00:00Z"), rules);
    }

    private static AdmissionItem gateItem(DeployViews.GateEvidence evidence) {
        AdmissionViews.Evidence ok = AdmissionViews.Evidence.pass("ok", "ok", "h");
        AdmissionPolicy.Inputs inputs = new AdmissionPolicy.Inputs(
                List.of(fact()), Map.of(REQ, GateDecision.pass("STRICT", "2/2 用例通过")), Map.of(REQ, 0),
                evidence, Set.of(), ok, ok, ok);
        return new AdmissionPolicy().evaluate(inputs).stream()
                .filter(item -> AdmissionItem.QUALITY_GATE.equals(item.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("准入项缺失：" + AdmissionItem.QUALITY_GATE));
    }

    @Test
    @DisplayName("有跳过：detail 写「4 条通过 · 1 条跳过（未启用），质量门 PASS」，不再说「全部通过」")
    void skipped_rule_is_not_reported_as_passed() {
        AdmissionItem item = gateItem(gate("PASS", "PASS", "PASS", "PASS", "PASS", "SKIPPED"));

        assertEquals(AdmissionItem.PASS, item.status());
        assertEquals("4 条通过 · 1 条跳过（未启用），质量门 PASS", item.detail());
        assertFalse(item.detail().contains("全部通过"), item.detail());
    }

    @Test
    @DisplayName("无跳过：保留原句「5 条规则全部通过，质量门快照 #9001」")
    void all_pass_keeps_legacy_sentence() {
        AdmissionItem item = gateItem(gate("PASS", "PASS", "PASS", "PASS", "PASS", "PASS"));

        assertEquals(AdmissionItem.PASS, item.status());
        assertEquals("5 条规则全部通过，质量门快照 #9001", item.detail());
    }

    @Test
    @DisplayName("PASS_WITH_WAIVER 且有跳过：文案带跳过数并在末尾追加「（含规则级豁免）」")
    void waived_and_skipped_keeps_waiver_suffix() {
        AdmissionItem item = gateItem(gate("PASS_WITH_WAIVER", "PASS", "PASS", "PASS", "WAIVED", "SKIPPED"));

        assertEquals(AdmissionItem.PASS, item.status());
        assertEquals("4 条通过 · 1 条跳过（未启用），质量门 PASS（含规则级豁免）", item.detail());
    }

    @Test
    @DisplayName("result=FAIL 且旁边有 SKIPPED：仍失败，明细只列 FAIL 规则，跳过不许混进「未通过」")
    void fail_result_is_unchanged_by_skipped_rule() {
        AdmissionItem item = gateItem(gate("FAIL", "PASS", "PASS", "PASS", "FAIL", "SKIPPED"));

        assertEquals(AdmissionItem.FAIL, item.status());
        assertTrue(item.detail().contains("QG-04"), item.detail());
        assertFalse(item.detail().contains("QG-05"), item.detail());
        assertFalse(item.detail().contains("未启用"), item.detail());
    }
}
