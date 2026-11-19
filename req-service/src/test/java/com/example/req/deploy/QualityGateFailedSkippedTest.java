package com.example.req.deploy;

import com.example.platform.contract.quality.GateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质量门 409 的文案与 detail 只认 FAIL（r33 / PRD AC-1、AC-2）。
 *
 * <p>{@link DeployViews.GateEvidence#failedRules()} 仍按 {@code !passed()} 过滤，因此旧快照里
 * {@code SKIPPED} 的行会混在里面；提交被门禁拦下时若直接用它拼 409，就会出现「门是 FAIL、
 * 原因里却列出 2 条（其中 QG-05 只是本轮未启用）」的自相矛盾——与门禁 reason
 * 「1 条门规则未通过」对不上。这里逐字钉住：message 只列 FAIL，detail 把跳过单列。</p>
 */
class QualityGateFailedSkippedTest {

    private static GateSnapshot.GateRuleResult rule(String code, String status) {
        return new GateSnapshot.GateRuleResult(code, status, true, "ok", "ok", null);
    }

    /** 演示版的真实形状：QG-04 FAIL，QG-05（本轮未接入外部质量源）SKIPPED。 */
    private static DeployViews.GateEvidence gate(String result, String... statuses) {
        String[] codes = {"QG-01", "QG-02", "QG-03", "QG-04", "QG-05"};
        List<GateSnapshot.GateRuleResult> rules = new java.util.ArrayList<>();
        for (int i = 0; i < codes.length && i < statuses.length; i++) {
            rules.add(rule(codes[i], statuses[i]));
        }
        return new DeployViews.GateEvidence(9001L, 7001L, 501L, result, "digest-abc",
                Instant.parse("2026-11-19T02:00:00Z"), rules);
    }

    @Test
    @DisplayName("409 message 只列 FAIL：跳过规则不写进「未通过」清单")
    void message_lists_failed_rules_only() {
        DeployOrderService.QualityGateFailedException ex = new DeployOrderService.QualityGateFailedException(
                gate("FAIL", "PASS", "PASS", "PASS", "FAIL", "SKIPPED"), List.of());

        assertEquals(409, ex.status());
        assertEquals("QUALITY_GATE_FAILED", ex.code());
        assertEquals("发布质量门未通过（快照 #9001）：QG-04", ex.getMessage());
        assertFalse(ex.getMessage().contains("QG-05"), ex.getMessage());
    }

    @Test
    @DisplayName("detail：failedRules 只装 FAIL，skippedRules 单列跳过，openP0P1 不含跳过")
    void detail_splits_failed_and_skipped() {
        DeployOrderService.QualityGateFailedException ex = new DeployOrderService.QualityGateFailedException(
                gate("FAIL", "PASS", "PASS", "PASS", "FAIL", "SKIPPED"), List.of());

        assertEquals(List.of("QG-04"), codes(ex.detail().get("failedRules")));
        assertEquals(List.of("QG-05"), codes(ex.detail().get("skippedRules")));
        assertEquals(List.of("QG-04"), ex.detail().get("openP0P1"));
    }

    @Test
    @DisplayName("没有任何 FAIL 时 message 退回门结果，不会列出跳过项的 code")
    void no_failed_rule_falls_back_to_result() {
        DeployOrderService.QualityGateFailedException ex = new DeployOrderService.QualityGateFailedException(
                gate("FAIL", "PASS", "PASS", "PASS", "PASS", "SKIPPED"), List.of());

        assertEquals("发布质量门未通过（快照 #9001）：FAIL", ex.getMessage());
        assertTrue(ex.detail().get("failedRules") instanceof List);
        assertEquals(List.of(), codes(ex.detail().get("failedRules")));
        assertEquals(List.of("QG-05"), codes(ex.detail().get("skippedRules")));
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(Object value) {
        return ((List<GateSnapshot.GateRuleResult>) value).stream()
                .map(GateSnapshot.GateRuleResult::code)
                .toList();
    }
}
