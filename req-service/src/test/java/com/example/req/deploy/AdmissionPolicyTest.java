package com.example.req.deploy;

import com.example.platform.contract.GateBlocker;
import com.example.platform.contract.GateDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AC-12 的五项准入：顺序固定、逐项可断言、服务不可用时 fail-closed。 */
class AdmissionPolicyTest {

    private static final long REQ = 501L;

    private static AdmissionPolicy.ReqFact rd(String status, String mrUrl, String estimate, String actual) {
        return rd(status, mrUrl, estimate, actual, List.of());
    }

    private static AdmissionPolicy.ReqFact rd(String status, String mrUrl, String estimate, String actual,
                                              List<String> blockingApprovals) {
        return new AdmissionPolicy.ReqFact(REQ, "RD-0001", "RD", status, mrUrl,
                estimate == null ? null : new BigDecimal(estimate),
                actual == null ? null : new BigDecimal(actual),
                blockingApprovals);
    }

    private static AdmissionPolicy.ReqFact complete() {
        return rd("TEST_PASSED", "https://git/mr/1", "3", "2.5");
    }

    private static Map<Long, GateDecision> gatePassed() {
        return Map.of(REQ, GateDecision.pass("STRICT", "2/2 用例通过"));
    }

    private static Map<Long, Integer> noDefects() {
        return Map.of(REQ, 0);
    }

    private static AdmissionItem find(List<AdmissionItem> items, String code) {
        return items.stream().filter(item -> code.equals(item.code())).findFirst()
                .orElseThrow(() -> new AssertionError("准入项缺失：" + code));
    }

    @Test
    @DisplayName("全部满足：5 项按固定顺序全 PASS")
    void allPassedInFixedOrder() {
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(
                new AdmissionPolicy.Inputs(List.of(complete()), gatePassed(), noDefects()));

        assertEquals(AdmissionItem.ORDER, items.stream().map(AdmissionItem::code).toList());
        assertEquals(5, items.size());
        assertTrue(items.stream().allMatch(AdmissionItem::passed));
        assertTrue(AdmissionPolicy.failedCodes(items).isEmpty());
    }

    @Test
    @DisplayName("需求未到「测试通过」→ REQ_TEST_PASSED FAIL，且带明细")
    void requirementNotTestPassed() {
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(new AdmissionPolicy.Inputs(
                List.of(rd("PENDING_TEST", "https://git/mr/1", "3", "2.5")), gatePassed(), noDefects()));

        AdmissionItem item = find(items, AdmissionItem.REQ_TEST_PASSED);
        assertFalse(item.passed());
        assertTrue(item.detail().contains("PENDING_TEST"), "明细要写清当前状态：" + item.detail());
        assertFalse(item.fixHint() == null || item.fixHint().isBlank());
    }

    @Test
    @DisplayName("门禁不通过 → REQ_TEST_PASSED FAIL，明细包含失败用例")
    void gateBlocked() {
        GateDecision blocked = GateDecision.block("STRICT", "1 个用例未通过", List.of(
                new GateBlocker("CASE_FAILED", "TC-0007 失败", "修复后重跑")));
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(new AdmissionPolicy.Inputs(
                List.of(complete()), Map.of(REQ, blocked), noDefects()));

        AdmissionItem item = find(items, AdmissionItem.REQ_TEST_PASSED);
        assertFalse(item.passed());
        assertTrue(item.detail().contains("TC-0007") || item.detail().contains("用例"),
                "明细要能指到问题：" + item.detail());
    }

    @Test
    @DisplayName("门禁服务不可用 → FAIL（fail-closed），修复指引提示稍后重试")
    void gateUnavailableFailsClosed() {
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(new AdmissionPolicy.Inputs(
                List.of(complete()), null, noDefects()));

        AdmissionItem item = find(items, AdmissionItem.REQ_TEST_PASSED);
        assertFalse(item.passed(), "取不到门禁结论必须按不通过处理，不能静默放行");
        assertEquals(AdmissionPolicy.FIX_SERVICE_DOWN, item.fixHint());
        assertTrue(AdmissionPolicy.failedCodes(items).contains(AdmissionItem.REQ_TEST_PASSED));
    }

    @Test
    @DisplayName("缺陷服务不可用 → NO_OPEN_HIGH_DEFECT FAIL；有未关闭 P0/P1 也 FAIL 并给出计数")
    void defectCheck() {
        AdmissionItem unavailable = find(new AdmissionPolicy().evaluate(new AdmissionPolicy.Inputs(
                List.of(complete()), gatePassed(), null)), AdmissionItem.NO_OPEN_HIGH_DEFECT);
        assertFalse(unavailable.passed());
        assertEquals(AdmissionPolicy.FIX_SERVICE_DOWN, unavailable.fixHint());

        AdmissionItem open = find(new AdmissionPolicy().evaluate(new AdmissionPolicy.Inputs(
                List.of(complete()), gatePassed(), Map.of(REQ, 2))), AdmissionItem.NO_OPEN_HIGH_DEFECT);
        assertFalse(open.passed());
        assertTrue(open.detail().contains("2"), "明细要写清几条：" + open.detail());
    }

    @Test
    @DisplayName("MR 未填 / 人天缺失或为 0 → 各自 FAIL")
    void mrAndManDays() {
        List<AdmissionItem> noMr = new AdmissionPolicy().evaluate(new AdmissionPolicy.Inputs(
                List.of(rd("TEST_PASSED", "  ", "3", "2")), gatePassed(), noDefects()));
        assertFalse(find(noMr, AdmissionItem.MR_FILLED).passed());

        List<AdmissionItem> zero = new AdmissionPolicy().evaluate(new AdmissionPolicy.Inputs(
                List.of(rd("TEST_PASSED", "https://git/mr/1", "0", "2")), gatePassed(), noDefects()));
        assertFalse(find(zero, AdmissionItem.MAN_DAYS_FILLED).passed());

        List<AdmissionItem> missing = new AdmissionPolicy().evaluate(new AdmissionPolicy.Inputs(
                List.of(rd("TEST_PASSED", "https://git/mr/1", "3", null)), gatePassed(), noDefects()));
        assertFalse(find(missing, AdmissionItem.MAN_DAYS_FILLED).passed());
    }

    @Test
    @DisplayName("需求审批未通过 → REQ_APPROVALS_DONE FAIL，明细列出未通过的审批")
    void approvalsNotDone() {
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(new AdmissionPolicy.Inputs(
                List.of(rd("TEST_PASSED", "https://git/mr/1", "3", "2", List.of("REQ_REVIEW=PENDING"))),
                gatePassed(), noDefects()));

        AdmissionItem item = find(items, AdmissionItem.REQ_APPROVALS_DONE);
        assertFalse(item.passed());
        assertTrue(item.detail().contains("REQ_REVIEW"), "明细要指出是哪条审批：" + item.detail());
    }

    @Test
    @DisplayName("强制放行只标记失败项，不把 FAIL 改成 PASS")
    void forcedMarksOnlyFailedItems() {
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(new AdmissionPolicy.Inputs(
                List.of(rd("PENDING_TEST", "https://git/mr/1", "3", "2")), gatePassed(), noDefects()));
        List<AdmissionItem> marked = AdmissionPolicy.markForced(items, true);

        assertFalse(find(marked, AdmissionItem.REQ_TEST_PASSED).passed(), "强制放行不能改写事实");
        assertTrue(find(marked, AdmissionItem.REQ_TEST_PASSED).forced());
        assertFalse(find(marked, AdmissionItem.MR_FILLED).forced(), "PASS 项不带 forced 标记");
        assertEquals(AdmissionItem.ORDER, marked.stream().map(AdmissionItem::code).toList());
    }

    @Test
    @DisplayName("指纹随事实变化：改了 MR 或状态，快照即视作过期")
    void signatureChangesWithFacts() {
        String base = AdmissionPolicy.signature(List.of(complete()));
        String mrChanged = AdmissionPolicy.signature(List.of(
                rd("TEST_PASSED", "https://git/mr/2", "3", "2.5")));
        String statusChanged = AdmissionPolicy.signature(List.of(
                rd("MERGED", "https://git/mr/1", "3", "2.5")));

        assertEquals(base, AdmissionPolicy.signature(List.of(complete())), "同一组事实指纹必须稳定");
        assertNotEquals(base, mrChanged);
        assertNotEquals(base, statusChanged);
    }

    @Test
    @DisplayName("没有关联研发需求 → 5 项都 FAIL（发布单不能空转）")
    void emptyRequirementsNeverPass() {
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(
                new AdmissionPolicy.Inputs(new ArrayList<>(), new LinkedHashMap<>(), new LinkedHashMap<>()));

        assertEquals(5, items.size());
        assertTrue(items.stream().noneMatch(AdmissionItem::passed));
    }
}
