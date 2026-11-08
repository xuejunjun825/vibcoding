package com.example.deliver.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.example.platform.contract.GateDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate rule, asserted without Spring or a database: STRICT/LOOSE, empty plan, and the
 * "unexplained skip" case LOOSE must still block (AC-16).
 */
class GatePolicyTest {

    private static GateCase planCase(long id, String code, int version, String result, String note) {
        return new GateCase(id, code, version, result, note, 100L, 7L);
    }

    @Test
    @DisplayName("STRICT：全部通过才放行")
    void strictPassesWhenAllPassed() {
        GateDecision decision = GatePolicy.evaluate("STRICT", List.of(
                planCase(1, "TC-0001", 1, "PASS", null),
                planCase(2, "TC-0002", 2, "PASS", null)));
        assertTrue(decision.passed());
        assertEquals("STRICT", decision.mode());
        assertTrue(decision.blockers().isEmpty());
    }

    @Test
    @DisplayName("STRICT：未执行即阻断，并列出用例与版本")
    void strictBlocksNotRun() {
        GateDecision decision = GatePolicy.evaluate("STRICT", List.of(
                planCase(1, "TC-0001", 1, "PASS", null),
                planCase(2, "TC-0002", 3, "NOT_RUN", null)));
        assertFalse(decision.passed());
        assertEquals(1, decision.blockers().size());
        assertEquals("CASE_NOT_RUN", decision.blockers().get(0).code());
        assertTrue(decision.blockers().get(0).message().contains("TC-0002"));
        assertTrue(decision.blockers().get(0).message().contains("v3"));
        assertEquals(2L, decision.blockers().get(0).detail().get("caseId"));
        assertEquals(3, decision.blockers().get(0).detail().get("caseVersion"));
    }

    @Test
    @DisplayName("STRICT：失败/阻塞/跳过都阻断")
    void strictBlocksFailBlockedSkip() {
        GateDecision decision = GatePolicy.evaluate("STRICT", List.of(
                planCase(1, "TC-0001", 1, "FAIL", null),
                planCase(2, "TC-0002", 1, "BLOCKED", "环境不可用"),
                planCase(3, "TC-0003", 1, "SKIP", "不适用")));
        assertFalse(decision.passed());
        assertEquals(List.of("CASE_FAILED", "CASE_BLOCKED", "CASE_SKIPPED"),
                decision.blockers().stream().map(blocker -> blocker.code()).toList());
    }

    @Test
    @DisplayName("LOOSE：只阻断失败，允许写了原因的阻塞/跳过")
    void looseBlocksOnlyFailures() {
        GateDecision decision = GatePolicy.evaluate("LOOSE", List.of(
                planCase(1, "TC-0001", 1, "PASS", null),
                planCase(2, "TC-0002", 1, "BLOCKED", "环境不可用"),
                planCase(3, "TC-0003", 1, "SKIP", "本轮不适用")));
        assertTrue(decision.passed());
    }

    @Test
    @DisplayName("LOOSE：阻塞/跳过没写原因仍然阻断")
    void looseRequiresReason() {
        GateDecision decision = GatePolicy.evaluate("LOOSE", List.of(
                planCase(1, "TC-0001", 1, "BLOCKED", " "),
                planCase(2, "TC-0002", 1, "SKIP", null)));
        assertFalse(decision.passed());
        assertEquals(List.of("CASE_BLOCKED_NO_REASON", "CASE_SKIPPED_NO_REASON"),
                decision.blockers().stream().map(blocker -> blocker.code()).toList());
    }

    @Test
    @DisplayName("LOOSE：失败仍然阻断")
    void looseStillBlocksFail() {
        GateDecision decision = GatePolicy.evaluate("LOOSE", List.of(
                planCase(1, "TC-0001", 1, "FAIL", null),
                planCase(2, "TC-0002", 1, "SKIP", "不适用")));
        assertFalse(decision.passed());
        assertEquals(1, decision.blockers().size());
        assertEquals("CASE_FAILED", decision.blockers().get(0).code());
    }

    @Test
    @DisplayName("计划无用例：两种模式都不满足门禁")
    void emptyPlanBlocks() {
        assertFalse(GatePolicy.evaluate("STRICT", List.of()).passed());
        assertFalse(GatePolicy.evaluate("LOOSE", List.of()).passed());
        assertEquals("NO_CASES", GatePolicy.evaluate("STRICT", List.of()).blockers().get(0).code());
    }

    @Test
    @DisplayName("未知模式按 STRICT 处理（不能静默变宽松）")
    void unknownModeFallsBackToStrict() {
        GateDecision decision = GatePolicy.evaluate("whatever", List.of(
                planCase(1, "TC-0001", 1, "SKIP", "不适用")));
        assertFalse(decision.passed());
        assertEquals("STRICT", decision.mode());
    }

    @Test
    @DisplayName("结果为空按未执行处理")
    void nullResultIsNotRun() {
        GateCase row = new GateCase(1, "TC-0001", 1, null, null, 100L, 7L);
        assertEquals("NOT_RUN", row.result());
    }

    @Test
    @DisplayName("放行结论带 mode 与 reason，供 req 侧 409 文案复用")
    void passCarriesModeAndReason() {
        GateDecision decision = GatePolicy.evaluate("LOOSE", List.of(
                planCase(1, "TC-0001", 1, "PASS", null)));
        assertTrue(decision.passed());
        assertEquals("LOOSE", decision.mode());
        assertTrue(decision.reason().contains("1"));
        assertTrue(decision.blockers().isEmpty());
    }
}
