package com.example.req.deploy;

import com.example.platform.contract.GateBlocker;
import com.example.platform.contract.GateDecision;
import com.example.req.integration.admission.AdmissionViews;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 九项准入的纯判定（PRD AC-8.1..AC-8.6 / TECHNICAL §7）。
 *
 * <p>四态是这张表的全部内容：顺序固定、逐项可断言、SKIP 永不计通过、CLOSED 不计分母、
 * 服务/证据不可用时 fail-closed（绝不放行）。</p>
 */
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

    /** 第 6 项的证据：一份"通过"的质量门快照（5 条规则全 PASS）。 */
    private static DeployViews.GateEvidence gatePass(String result) {
        List<com.example.platform.contract.quality.GateSnapshot.GateRuleResult> rules = List.of(
                new com.example.platform.contract.quality.GateSnapshot.GateRuleResult(
                        "QG-01", "PASS", true, "100%", "≥95%", null),
                new com.example.platform.contract.quality.GateSnapshot.GateRuleResult(
                        "QG-02", "PASS", true, "0", "0", null),
                new com.example.platform.contract.quality.GateSnapshot.GateRuleResult(
                        "QG-03", "PASS", true, "0", "0", null),
                new com.example.platform.contract.quality.GateSnapshot.GateRuleResult(
                        "QG-04", "PASS", true, "0", "0", null),
                new com.example.platform.contract.quality.GateSnapshot.GateRuleResult(
                        "QG-05", "PASS", true, "会签完成", "三级会签", null));
        return new DeployViews.GateEvidence(9001L, 7001L, 501L, result, "digest-abc",
                java.time.Instant.parse("2026-11-09T02:00:00Z"), rules);
    }

    /** 第 6 项的证据：QG-01 通过率不达标（FAIL）。 */
    private static DeployViews.GateEvidence gateFail() {
        return new DeployViews.GateEvidence(9002L, 7001L, 501L, "FAIL", "digest-def",
                java.time.Instant.parse("2026-11-09T02:00:00Z"),
                List.of(new com.example.platform.contract.quality.GateSnapshot.GateRuleResult(
                        "QG-01", "FAIL", true, "88.0%", "≥95%", "补齐失败用例后重新生成报告")));
    }

    private static AdmissionViews.Evidence ok(String reason, String hash) {
        return AdmissionViews.Evidence.pass(reason, "ok", hash);
    }

    private static AdmissionViews.Evidence failed(String reason, String detail) {
        return AdmissionViews.Evidence.fail(reason, detail, "h-fail");
    }

    /** 九项全 PASS 的输入（⑦⑧⑨ 用本地证据塞满）。 */
    private static AdmissionPolicy.Inputs passing(AdmissionPolicy.ReqFact fact) {
        return new AdmissionPolicy.Inputs(List.of(fact), gatePassed(), noDefects(), gatePass("PASS"), Set.of(),
                ok("BUILD_SUCCESS", "build:1:SUCCESS:7"),
                ok("SONAR_OK", "sonar:1"),
                ok("ARTIFACT_OK", "artifact:1"));
    }

    private static AdmissionItem find(List<AdmissionItem> items, String code) {
        return items.stream().filter(item -> code.equals(item.code())).findFirst()
                .orElseThrow(() -> new AssertionError("准入项缺失：" + code));
    }

    @Test
    @DisplayName("全部满足：9 项按固定顺序全 PASS（第 6 项仍是发布质量门）")
    void allPassedInFixedOrder() {
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(passing(complete()));

        assertEquals(AdmissionItem.ORDER, items.stream().map(AdmissionItem::code).toList());
        assertEquals(9, items.size());
        assertEquals(AdmissionItem.QUALITY_GATE, items.get(5).code());
        assertEquals(AdmissionItem.BUILD_PASSED, items.get(6).code());
        assertEquals(AdmissionItem.SONAR_GATE_PASSED, items.get(7).code());
        assertEquals(AdmissionItem.ARTIFACT_VERIFIED, items.get(8).code());
        assertTrue(items.stream().allMatch(AdmissionItem::passed));
        assertTrue(items.stream().allMatch(item -> AdmissionItem.PASS.equals(item.status())));
        assertTrue(AdmissionPolicy.failedCodes(items).isEmpty());
    }

    @Test
    @DisplayName("PRD AC-8.1 顺序：①②③ 需求/审批 → ④MR ⑤人天 → ⑥质量门 → ⑦构建 ⑧Sonar ⑨制品")
    void orderMatchesPrd() {
        assertEquals(List.of(
                "REQ_TEST_PASSED", "NO_OPEN_HIGH_DEFECT", "REQ_APPROVALS_DONE",
                "MR_FILLED", "MAN_DAYS_FILLED", "QUALITY_GATE",
                "BUILD_PASSED", "SONAR_GATE_PASSED", "ARTIFACT_VERIFIED"), AdmissionItem.ORDER);
    }

    @Test
    @DisplayName("四态计数：SKIP 不计通过、CLOSED 不计分母，四者相加等于总数")
    void countsKeepSkippedAndClosedOutOfPassed() {
        AdmissionPolicy.Inputs inputs = new AdmissionPolicy.Inputs(List.of(complete()), gatePassed(), noDefects(),
                gatePass("PASS"), Set.of(AdmissionItem.MR_FILLED),
                ok("BUILD_SUCCESS", "build:1"),
                AdmissionViews.Evidence.skip(AdmissionViews.NOT_CONFIGURED, "跳过：需求未绑定 Sonar projectKey", "s1"),
                failed("ARTIFACT_MISSING", "发布单未绑定制品"));

        List<AdmissionItem> items = new AdmissionPolicy().evaluate(inputs);
        DeployViews.AdmissionCounts counts = DeployViews.AdmissionCounts.of(items);

        assertEquals(AdmissionItem.CLOSED, find(items, AdmissionItem.MR_FILLED).status());
        assertEquals(AdmissionItem.SKIP, find(items, AdmissionItem.SONAR_GATE_PASSED).status());
        assertFalse(find(items, AdmissionItem.SONAR_GATE_PASSED).passed(), "跳过永远不算通过");
        assertEquals(AdmissionItem.FAIL, find(items, AdmissionItem.ARTIFACT_VERIFIED).status());
        assertEquals(9, counts.total());
        assertEquals(1, counts.closed());
        assertEquals(8, counts.active());
        assertEquals(1, counts.skipped());
        assertEquals(1, counts.failed());
        assertEquals(6, counts.passed());
        assertEquals(9, counts.passed() + counts.failed() + counts.skipped() + counts.closed());
        assertEquals(List.of(AdmissionItem.ARTIFACT_VERIFIED), AdmissionPolicy.failedCodes(items));
        assertFalse(new DeployViews.AdmissionResult(items, java.time.LocalDateTime.now(), "sig").allPassed(),
                "有 FAIL 就不能提交");
    }

    @Test
    @DisplayName("SKIP/CLOSED 不阻断提交：没有 FAIL 的清单 allPassed=true，但 skipCount 要显式确认")
    void skipDoesNotBlockButMustBeAcknowledged() {
        AdmissionPolicy.Inputs inputs = new AdmissionPolicy.Inputs(List.of(complete()), gatePassed(), noDefects(),
                gatePass("PASS"), Set.of(AdmissionItem.ARTIFACT_VERIFIED),
                ok("BUILD_SUCCESS", "build:1"),
                AdmissionViews.Evidence.skip(AdmissionViews.NOT_CONFIGURED, "跳过：未绑定 Sonar projectKey", "s1"),
                ok("ARTIFACT_OK", "artifact:1"));

        DeployViews.AdmissionResult result = new DeployViews.AdmissionResult(
                new AdmissionPolicy().evaluate(inputs), java.time.LocalDateTime.now(), "sig");

        assertTrue(result.allPassed());
        assertFalse(result.hasBlocking());
        assertEquals(1, result.skipCount());
        assertEquals(8, result.counts().active());
        assertEquals(1, result.counts().closed());
    }

    @Test
    @DisplayName("锁定项不可被关闭（CLOSED 集合里出现也按原判定），可关项关闭即 CLOSED/ITEM_OFF")
    void lockedItemsIgnoreCloseFlags() {
        AdmissionPolicy.Inputs inputs = new AdmissionPolicy.Inputs(List.of(complete()), gatePassed(), noDefects(),
                gatePass("PASS"), Set.of(AdmissionItem.REQ_TEST_PASSED, AdmissionItem.QUALITY_GATE),
                ok("BUILD_SUCCESS", "build:1"), ok("SONAR_OK", "sonar:1"), ok("ARTIFACT_OK", "artifact:1"));

        List<AdmissionItem> items = new AdmissionPolicy().evaluate(inputs);

        assertEquals(AdmissionItem.PASS, find(items, AdmissionItem.REQ_TEST_PASSED).status(),
                "①②③⑥ 由代码锁死，脏配置不能关掉测试通过");
        assertEquals(AdmissionItem.PASS, find(items, AdmissionItem.QUALITY_GATE).status());
    }

    @Test
    @DisplayName("证据缺失 → FAIL（fail-closed）：⑦⑧⑨ 不能因为读不到就当没要求")
    void missingEvidenceFailsClosed() {
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(new AdmissionPolicy.Inputs(
                List.of(complete()), gatePassed(), noDefects(), gatePass("PASS")));

        for (String code : List.of(AdmissionItem.BUILD_PASSED, AdmissionItem.SONAR_GATE_PASSED,
                AdmissionItem.ARTIFACT_VERIFIED)) {
            AdmissionItem item = find(items, code);
            assertEquals(AdmissionItem.FAIL, item.status(), code);
            assertTrue(item.detail().contains("EVIDENCE_UNAVAILABLE"), item.detail());
            assertFalse(item.fixHint() == null || item.fixHint().isBlank(), "失败项必须给怎么改");
        }
    }

    @Test
    @DisplayName("集成未启用 ⇒ CLOSED/INTEGRATION_DISABLED（不阻断、不计分母）")
    void integrationDisabledIsClosed() {
        AdmissionPolicy.Inputs inputs = new AdmissionPolicy.Inputs(List.of(complete()), gatePassed(), noDefects(),
                gatePass("PASS"), Set.of(),
                AdmissionViews.Evidence.closed(AdmissionViews.INTEGRATION_DISABLED, "Jenkins 未启用"),
                AdmissionViews.Evidence.closed(AdmissionViews.INTEGRATION_DISABLED, "Sonar 未启用"),
                AdmissionViews.Evidence.closed(AdmissionViews.INTEGRATION_DISABLED, "Nexus 未启用"));

        DeployViews.AdmissionResult result = new DeployViews.AdmissionResult(
                new AdmissionPolicy().evaluate(inputs), java.time.LocalDateTime.now(), "sig");

        assertEquals(3, result.counts().closed());
        assertEquals(6, result.counts().active());
        assertTrue(result.allPassed(), "默认关闭的集成项不能把既有门禁卡死");
    }

    @Test
    @DisplayName("质量门带回 PASS_WITH_WAIVER：第 6 项仍算通过（豁免已在门禁侧生效）")
    void qualityGateWaivedStillPasses() {
        AdmissionPolicy.Inputs inputs = new AdmissionPolicy.Inputs(List.of(complete()), gatePassed(), noDefects(),
                gatePass("PASS_WITH_WAIVER"), Set.of(),
                ok("BUILD_SUCCESS", "build:1"), ok("SONAR_OK", "sonar:1"), ok("ARTIFACT_OK", "artifact:1"));

        assertTrue(find(new AdmissionPolicy().evaluate(inputs), AdmissionItem.QUALITY_GATE).passed());
    }

    @Test
    @DisplayName("质量门 FAIL：第 6 项失败并列出规则与实际值")
    void qualityGateFailed() {
        AdmissionPolicy.Inputs inputs = new AdmissionPolicy.Inputs(List.of(complete()), gatePassed(), noDefects(),
                gateFail(), Set.of(),
                ok("BUILD_SUCCESS", "build:1"), ok("SONAR_OK", "sonar:1"), ok("ARTIFACT_OK", "artifact:1"));
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(inputs);

        AdmissionItem gate = find(items, AdmissionItem.QUALITY_GATE);
        assertFalse(gate.passed());
        assertTrue(gate.detail().contains("QG-01"), gate.detail());
        assertTrue(gate.detail().contains("88.0%"), gate.detail());
        assertEquals("补齐失败用例后重新生成报告", gate.fixHint());
        assertTrue(AdmissionPolicy.failedCodes(items).contains(AdmissionItem.QUALITY_GATE));
    }

    @Test
    @DisplayName("质量门不可用：第 6 项 FAIL 且 detail 带 GATE_UNAVAILABLE（fail-closed）")
    void qualityGateUnavailableFailsClosed() {
        AdmissionPolicy.Inputs inputs = new AdmissionPolicy.Inputs(List.of(complete()), gatePassed(), noDefects(),
                null, Set.of(),
                ok("BUILD_SUCCESS", "build:1"), ok("SONAR_OK", "sonar:1"), ok("ARTIFACT_OK", "artifact:1"));
        AdmissionItem gate = find(new AdmissionPolicy().evaluate(inputs), AdmissionItem.QUALITY_GATE);

        assertFalse(gate.passed());
        assertTrue(gate.detail().contains(AdmissionPolicy.GATE_UNAVAILABLE), gate.detail());
        assertEquals(AdmissionPolicy.FIX_GATE_UNAVAILABLE, gate.fixHint());
    }

    @Test
    @DisplayName("需求未到「测试通过」→ REQ_TEST_PASSED FAIL，且带明细")
    void requirementNotTestPassed() {
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(passing(
                rd("PENDING_TEST", "https://git/mr/1", "3", "2.5")));

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
        AdmissionPolicy.Inputs inputs = new AdmissionPolicy.Inputs(List.of(complete()), Map.of(REQ, blocked),
                noDefects(), gatePass("PASS"), Set.of(),
                ok("BUILD_SUCCESS", "build:1"), ok("SONAR_OK", "sonar:1"), ok("ARTIFACT_OK", "artifact:1"));

        AdmissionItem item = find(new AdmissionPolicy().evaluate(inputs), AdmissionItem.REQ_TEST_PASSED);
        assertFalse(item.passed());
        assertTrue(item.detail().contains("TC-0007") || item.detail().contains("用例"),
                "明细要能指到问题：" + item.detail());
    }

    @Test
    @DisplayName("门禁服务不可用 → FAIL（fail-closed），修复指引提示稍后重试")
    void gateUnavailableFailsClosed() {
        AdmissionPolicy.Inputs inputs = new AdmissionPolicy.Inputs(List.of(complete()), null, noDefects(),
                gatePass("PASS"), Set.of(),
                ok("BUILD_SUCCESS", "build:1"), ok("SONAR_OK", "sonar:1"), ok("ARTIFACT_OK", "artifact:1"));

        AdmissionItem item = find(new AdmissionPolicy().evaluate(inputs), AdmissionItem.REQ_TEST_PASSED);
        assertFalse(item.passed(), "取不到门禁结论必须按不通过处理，不能静默放行");
        assertEquals(AdmissionPolicy.FIX_SERVICE_DOWN, item.fixHint());
        assertTrue(AdmissionPolicy.failedCodes(List.of(item)).contains(AdmissionItem.REQ_TEST_PASSED));
    }

    @Test
    @DisplayName("缺陷服务不可用 → NO_OPEN_HIGH_DEFECT FAIL；有未关闭 P0/P1 也 FAIL 并给出计数")
    void defectCheck() {
        AdmissionPolicy.Inputs unavailable = new AdmissionPolicy.Inputs(List.of(complete()), gatePassed(), null,
                gatePass("PASS"), Set.of(),
                ok("BUILD_SUCCESS", "build:1"), ok("SONAR_OK", "sonar:1"), ok("ARTIFACT_OK", "artifact:1"));
        AdmissionItem missing = find(new AdmissionPolicy().evaluate(unavailable), AdmissionItem.NO_OPEN_HIGH_DEFECT);
        assertFalse(missing.passed());
        assertEquals(AdmissionPolicy.FIX_SERVICE_DOWN, missing.fixHint());

        AdmissionPolicy.Inputs open = new AdmissionPolicy.Inputs(List.of(complete()), gatePassed(), Map.of(REQ, 2),
                gatePass("PASS"), Set.of(),
                ok("BUILD_SUCCESS", "build:1"), ok("SONAR_OK", "sonar:1"), ok("ARTIFACT_OK", "artifact:1"));
        AdmissionItem item = find(new AdmissionPolicy().evaluate(open), AdmissionItem.NO_OPEN_HIGH_DEFECT);
        assertFalse(item.passed());
        assertTrue(item.detail().contains("2"), "明细要写清几条：" + item.detail());
    }

    @Test
    @DisplayName("MR 未填 / 人天缺失或为 0 → 各自 FAIL")
    void mrAndManDays() {
        assertFalse(find(new AdmissionPolicy().evaluate(passing(
                rd("TEST_PASSED", "  ", "3", "2"))), AdmissionItem.MR_FILLED).passed());
        assertFalse(find(new AdmissionPolicy().evaluate(passing(
                rd("TEST_PASSED", "https://git/mr/1", "0", "2"))), AdmissionItem.MAN_DAYS_FILLED).passed());
        assertFalse(find(new AdmissionPolicy().evaluate(passing(
                rd("TEST_PASSED", "https://git/mr/1", "3", null))), AdmissionItem.MAN_DAYS_FILLED).passed());
    }

    @Test
    @DisplayName("需求审批未通过 → REQ_APPROVALS_DONE FAIL，明细列出未通过的审批")
    void approvalsNotDone() {
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(passing(
                rd("TEST_PASSED", "https://git/mr/1", "3", "2", List.of("REQ_REVIEW=PENDING"))));

        AdmissionItem item = find(items, AdmissionItem.REQ_APPROVALS_DONE);
        assertFalse(item.passed());
        assertTrue(item.detail().contains("REQ_REVIEW"), "明细要指出是哪条审批：" + item.detail());
    }

    @Test
    @DisplayName("强制放行只标记失败项，不把 FAIL 改成 PASS")
    void forcedMarksOnlyFailedItems() {
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(passing(
                rd("PENDING_TEST", "https://git/mr/1", "3", "2")));
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
    @DisplayName("没有关联研发需求 → 9 项里没有任何 PASS（发布单不能空转）")
    void emptyRequirementsNeverPass() {
        List<AdmissionItem> items = new AdmissionPolicy().evaluate(
                new AdmissionPolicy.Inputs(new ArrayList<>(), new LinkedHashMap<>(), new LinkedHashMap<>()));

        assertEquals(9, items.size());
        assertTrue(items.stream().noneMatch(AdmissionItem::passed));
    }

    @Test
    @DisplayName("指纹纳入质量门 hash 与 ⑦⑧⑨ 证据：任一变化都让旧快照过期（v9 前缀）")
    void signatureIncludesGateAndIntegrationEvidence() {
        String noGate = AdmissionPolicy.signature(List.of(complete()));
        String pass = AdmissionPolicy.signature(List.of(complete()), "digest-abc");
        String fail = AdmissionPolicy.signature(List.of(complete()), "digest-def");

        assertNotEquals(noGate, pass);
        assertNotEquals(pass, fail);
        assertTrue(pass.startsWith("v9|"), pass);

        List<AdmissionItem> first = new AdmissionPolicy().evaluate(passing(complete()));
        List<AdmissionItem> second = new AdmissionPolicy().evaluate(new AdmissionPolicy.Inputs(
                List.of(complete()), gatePassed(), noDefects(), gatePass("PASS"), Set.of(),
                ok("BUILD_SUCCESS", "build:2"), ok("SONAR_OK", "sonar:1"), ok("ARTIFACT_OK", "artifact:1")));
        assertNotEquals(AdmissionPolicy.integrationDigest(first, Set.of()),
                AdmissionPolicy.integrationDigest(second, Set.of()),
                "构建号变了，集成证据摘要必须变（否则页面不会提示重新校验）");
    }
}
