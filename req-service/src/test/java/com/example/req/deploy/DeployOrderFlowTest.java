package com.example.req.deploy;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.code.CodeGenerator;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import com.example.platform.contract.quality.GateSnapshot;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalException;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalInstanceRepository;
import com.example.req.approval.ApproverDirectory;
import com.example.req.approval.StartApproval;
import com.example.req.requirement.RequirementRepository;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseRepository;
import com.example.req.release.ReleaseService;
import com.example.req.review.ReleaseReviewService;
import com.example.req.review.ReleaseReviewViews;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AC-12 的流程断言：提交重算准入 → 409 带 5 项；空原因 400；非 PMO 403；放行留痕/审计/通知；执行只认服务端耗时。
 *
 * <p>准入的判定细节在 {@link AdmissionPolicyTest}（纯函数），这里只钉"服务层不许绕过"的部分。</p>
 */
class DeployOrderFlowTest {

    private static final long TENANT = 1L;
    private static final long USER = 7L;
    private static final long ORDER_ID = 5L;
    private static final long RELEASE_ID = 10L;

    private DeployOrderRepository repository;
    private DeployOrderRequirementRepository orderRequirementRepository;
    private RequirementRepository requirementRepository;
    private ReleaseRepository releaseRepository;
    private ReleaseService releaseService;
    private ReleaseReviewService releaseReviewService;
    private AdmissionEvaluator admissionEvaluator;
    private CodeGenerator codeGenerator;
    private AuditService auditService;
    private NotificationWriter notificationWriter;
    private ApproverDirectory approverDirectory;
    private ApprovalInstanceRepository instanceRepository;
    private ApprovalEngine engine;

    private DeployOrderService service;
    private DeployOrderEntity order;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(DeployOrderRepository.class);
        orderRequirementRepository = mock(DeployOrderRequirementRepository.class);
        requirementRepository = mock(RequirementRepository.class);
        releaseRepository = mock(ReleaseRepository.class);
        releaseService = mock(ReleaseService.class);
        releaseReviewService = mock(ReleaseReviewService.class);
        admissionEvaluator = mock(AdmissionEvaluator.class);
        codeGenerator = mock(CodeGenerator.class);
        auditService = mock(AuditService.class);
        notificationWriter = mock(NotificationWriter.class);
        approverDirectory = mock(ApproverDirectory.class);
        instanceRepository = mock(ApprovalInstanceRepository.class);
        engine = mock(ApprovalEngine.class);
        ObjectProvider<ApprovalEngine> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(engine);

        service = new DeployOrderService(repository, orderRequirementRepository, requirementRepository,
                releaseRepository, releaseService, releaseReviewService, admissionEvaluator, codeGenerator, auditService,
                notificationWriter, approverDirectory, instanceRepository, new ObjectMapper(), provider);

        order = new DeployOrderEntity();
        order.setId(ORDER_ID);
        order.setTenantId(TENANT);
        order.setCode("DEP-0001");
        order.setReleaseId(RELEASE_ID);
        order.setEnv("PROD");
        order.setStatus(DeployOrderStates.DRAFT);
        order.setForcedRelease(false);
        order.setSteps(null);

        ReleaseEntity release = mock(ReleaseEntity.class);
        when(release.getId()).thenReturn(RELEASE_ID);
        when(release.getName()).thenReturn("2026-12 生产版本");
        when(release.getDomainId()).thenReturn(3L);
        when(release.getTenantId()).thenReturn(TENANT);

        when(repository.findByIdAndTenantId(ORDER_ID, TENANT)).thenReturn(Optional.of(order));
        when(releaseService.requireVisible(RELEASE_ID)).thenReturn(release);
        when(releaseRepository.findByIdAndTenantId(RELEASE_ID, TENANT)).thenReturn(Optional.of(release));
        when(instanceRepository.findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(admissionEvaluator.facts(any())).thenReturn(List.of());
        when(admissionEvaluator.snapshot(any())).thenReturn(null);

        TenantContext.set(DataScope.of(TENANT, ScopeTier.DOMAIN, Set.of(3L), null, USER, Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static DeployViews.AdmissionResult result(boolean allPassed) {
        List<AdmissionItem> items = allPassed
                ? List.of(
                        AdmissionItem.pass(AdmissionItem.REQ_TEST_PASSED, "需求测试通过", "1 条均已通过"),
                        AdmissionItem.pass(AdmissionItem.NO_OPEN_HIGH_DEFECT, "无未关闭 P0/P1", "0 个"),
                        AdmissionItem.pass(AdmissionItem.MR_FILLED, "MR 已填", "已填"),
                        AdmissionItem.pass(AdmissionItem.MAN_DAYS_FILLED, "人天已填", "3/2.5"),
                        AdmissionItem.pass(AdmissionItem.REQ_APPROVALS_DONE, "审批已通过", "2/2"))
                : List.of(
                        AdmissionItem.pass(AdmissionItem.REQ_TEST_PASSED, "需求测试通过", "1 条均已通过"),
                        AdmissionItem.pass(AdmissionItem.NO_OPEN_HIGH_DEFECT, "无未关闭 P0/P1", "0 个"),
                        AdmissionItem.pass(AdmissionItem.MR_FILLED, "MR 已填", "已填"),
                        AdmissionItem.fail(AdmissionItem.MAN_DAYS_FILLED, "人天已填", "RD-0001 实际人天未填", "补填实际人天"),
                        AdmissionItem.pass(AdmissionItem.REQ_APPROVALS_DONE, "审批已通过", "2/2"));
        return new DeployViews.AdmissionResult(items, LocalDateTime.now(), allPassed ? "sig-ok" : "sig-bad");
    }

    /** 第 6 项证据：PASS 时 5 条规则全过；FAIL 时 QG-01 不达标（88.0% < 95%）。 */
    private static DeployViews.GateEvidence gate(String result) {
        boolean pass = "PASS".equals(result);
        List<GateSnapshot.GateRuleResult> rules = List.of(
                new GateSnapshot.GateRuleResult("QG-01", pass ? "PASS" : "FAIL", true,
                        pass ? "97.0%" : "88.0%", "≥95%", pass ? null : "补齐失败用例后重新生成报告"),
                new GateSnapshot.GateRuleResult("QG-05", "PASS", true, "三级会签", "三级会签", null));
        return new DeployViews.GateEvidence(9001L, 7001L, 501L, result, "digest-" + result.toLowerCase(),
                java.time.Instant.parse("2026-11-09T02:00:00Z"), rules);
    }

    private static DeployViews.AdmissionResult withGate(DeployViews.AdmissionResult base,
                                                        DeployViews.GateEvidence gate) {
        return new DeployViews.AdmissionResult(base.items(), base.checkedAt(), base.signature(), gate);
    }

    @Test
    @DisplayName("准入未过：409 ADMISSION_FAILED + detail.items 五项，状态不动，不发起审批")
    void submitBlockedByAdmission() {
        when(admissionEvaluator.evaluateAndStore(order)).thenReturn(result(false));

        DeployErrors.AdmissionFailedException ex = assertThrows(DeployErrors.AdmissionFailedException.class,
                () -> service.submit(ORDER_ID));

        assertEquals(409, ex.status());
        assertEquals(DeployErrors.ADMISSION_FAILED, ex.code());
        assertEquals(5, ex.items().size());
        @SuppressWarnings("unchecked")
        List<Object> detailItems = (List<Object>) ((Map<String, Object>) ex.detail()).get("items");
        assertEquals(5, detailItems.size(), "detail.items 必须原样给前端渲染清单");
        assertEquals(DeployOrderStates.DRAFT, order.getStatus());
        verify(engine, never()).start(any());
    }

    @Test
    @DisplayName("最新 NO-GO 评审：未二次确认 409 NO_GO_CONFIRM_REQUIRED，确认后继续准入")
    void noGoRequiresConfirmation() {
        when(releaseReviewService.latest(RELEASE_ID)).thenReturn(new ReleaseReviewViews.ReviewRow(
                9L, RELEASE_ID, ORDER_ID, null, null, null, null, "NO_GO", null, false,
                "RECORDED", USER, LocalDateTime.now(), null, null));

        ApiException denied = assertThrows(ApiException.class, () -> service.submit(ORDER_ID));
        assertEquals(409, denied.status());
        assertEquals(DeployErrors.NO_GO_CONFIRM_REQUIRED, denied.code());
        verify(admissionEvaluator, never()).evaluateAndStore(any());
        assertEquals(DeployOrderStates.DRAFT, order.getStatus());

        TenantContext.setPrincipal(new TenantContext.Principal(
                USER, "pmo", "PMO", "t1", "演示租户", List.of("PMO"), Set.of(3L), null));
        when(admissionEvaluator.evaluateAndStore(order)).thenReturn(result(true));
        when(admissionEvaluator.allPassed(any())).thenReturn(true);

        DeployViews.SubmitResult submitted = service.submit(ORDER_ID, new DeployViews.SubmitCmd(true));
        assertEquals(DeployOrderStates.PENDING_APPROVAL, submitted.status());
        verify(auditService).record(eq("NO_GO_CONFIRM"), eq("deploy_order"), eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("准入未过但已强制放行：允许提交并进入审批")
    void forcedReleaseAllowsSubmit() {
        order.setForcedRelease(true);
        when(admissionEvaluator.evaluateAndStore(order)).thenReturn(result(false));

        DeployViews.SubmitResult submitted = service.submit(ORDER_ID);

        assertEquals(DeployOrderStates.PENDING_APPROVAL, submitted.status());
        assertEquals(DeployOrderStates.PENDING_APPROVAL, order.getStatus());
        ArgumentCaptor<StartApproval> captor = ArgumentCaptor.forClass(StartApproval.class);
        verify(engine).start(captor.capture());
        assertEquals(DeployOrderService.APPROVAL_BIZ_TYPE, captor.getValue().bizType());
        assertEquals(ORDER_ID, captor.getValue().bizId());
        verify(auditService).record(eq("STATE_CHANGE"), eq("deploy_order"), eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("没有已发布的需求发布流程：400，不静默通过、不进入待审批")
    void submitWithoutWorkflow() {
        when(admissionEvaluator.evaluateAndStore(order)).thenReturn(result(true));
        when(admissionEvaluator.allPassed(any())).thenReturn(true);
        when(engine.start(any())).thenThrow(ApprovalException.badRequest(ApprovalException.NO_WORKFLOW, "no wf"));

        ApiException ex = assertThrows(ApiException.class, () -> service.submit(ORDER_ID));

        assertEquals(DeployErrors.APPROVAL_NOT_CONFIGURED, ex.code());
        assertEquals(DeployOrderStates.DRAFT, order.getStatus());
    }

    @Test
    @DisplayName("强制放行已退役：恒 409 FORCE_RELEASE_RETIRED，不写强制标记、不改状态，审计 retired")
    void forceReleaseRetired() {
        ApiException ex = assertThrows(ApiException.class, () -> service.forceRelease(ORDER_ID, "  紧急上线  "));

        assertEquals(409, ex.status());
        assertEquals(DeployOrderService.FORCE_RELEASE_RETIRED, ex.code());
        assertEquals(DeployOrderService.FORCE_RELEASE_HINT, ex.detail().get("hint"));
        assertFalse(order.isForcedRelease());
        assertEquals(DeployOrderStates.DRAFT, order.getStatus(), "退役后不再有任何状态写入");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordInNewTx(eq("deploy.force-release.retired"), eq("deploy_order"),
                eq(ORDER_ID), captor.capture());
        assertEquals("紧急上线", captor.getValue().get("reason"));
        assertEquals(true, captor.getValue().get("retired"));
        verifyNoInteractions(notificationWriter);

        // 空原因同样 409：退役是路由级语义，与参数/角色无关（旧脚本不必先补原因才知道入口已下线）
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.forceRelease(ORDER_ID, "   ")).status());
        verifyNoInteractions(approverDirectory);
    }

    @Test
    @DisplayName("质量门未过：409 QUALITY_GATE_FAILED + detail.failedRules/snapshotId，状态不动、不发起审批")
    void submitBlockedByQualityGate() {
        when(admissionEvaluator.evaluateAndStore(order)).thenReturn(withGate(result(true), gate("FAIL")));

        DeployOrderService.QualityGateFailedException ex = assertThrows(
                DeployOrderService.QualityGateFailedException.class, () -> service.submit(ORDER_ID));

        assertEquals(409, ex.status());
        assertEquals("QUALITY_GATE_FAILED", ex.code());
        assertEquals(9001L, ex.detail().get("snapshotId"));
        assertEquals("FAIL", ex.detail().get("result"));
        @SuppressWarnings("unchecked")
        List<com.example.platform.contract.quality.GateSnapshot.GateRuleResult> failed =
                (List<com.example.platform.contract.quality.GateSnapshot.GateRuleResult>) ex.detail().get("failedRules");
        assertEquals(1, failed.size());
        assertEquals("QG-01", failed.get(0).code());
        assertTrue(ex.getMessage().contains("QG-01"), ex.getMessage());
        assertEquals(DeployOrderStates.DRAFT, order.getStatus());
        assertTrue(order.getQualityGateSnapshotId() == null, "拦下时不得固化快照列");
        verify(engine, never()).start(any());
    }

    @Test
    @DisplayName("质量门通过：提交通过并固化 snapshot/gate/report 三列（AC-5.6）")
    void submitPersistsQualityGateRefs() {
        when(admissionEvaluator.evaluateAndStore(order)).thenReturn(withGate(result(true), gate("PASS")));
        when(admissionEvaluator.allPassed(any())).thenReturn(true);

        DeployViews.SubmitResult submitted = service.submit(ORDER_ID);

        assertEquals(DeployOrderStates.PENDING_APPROVAL, submitted.status());
        assertEquals(9001L, order.getQualityGateSnapshotId());
        assertEquals(7001L, order.getQualityGateId());
        assertEquals(501L, order.getReportId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq("STATE_CHANGE"), eq("deploy_order"), eq(ORDER_ID), captor.capture());
        assertEquals(9001L, captor.getValue().get("qualityGateSnapshotId"));
        assertEquals("PASS", captor.getValue().get("gateResult"));
    }

    @Test
    @DisplayName("未审批通过不能登记执行：409")
    void executionBlockedBeforeApproval() {
        ApiException ex = assertThrows(ApiException.class, () -> service.recordExecution(ORDER_ID,
                new DeployOrderService.ExecutionCmd("SUCCESS", "2026-12-02T10:00:00+08:00",
                        "2026-12-02T11:00:00+08:00", null, null)));

        assertEquals(409, ex.status());
        verify(auditService, never()).record(eq("EXECUTION"), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("审批通过后登记执行：耗时时长由服务端算（向上取整到分钟），状态=执行结果")
    void executionComputesDuration() {
        order.setStatus(DeployOrderStates.APPROVED);

        DeployViews.OrderDetail detail = service.recordExecution(ORDER_ID,
                new DeployOrderService.ExecutionCmd("success", "2026-12-02T10:00:00+08:00",
                        "2026-12-02T11:00:30+08:00", "灰度完成", null));

        assertEquals(DeployOrderStates.SUCCESS, order.getStatus());
        assertEquals(61, order.getDurationMinutes(), "3630 秒 → 61 分钟");
        assertEquals(DeployOrderStates.SUCCESS, detail.execution().result());
        verify(auditService).record(eq("EXECUTION"), eq("deploy_order"), eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("执行结果只认三值；结束早于开始 400")
    void executionValidatesInput() {
        order.setStatus(DeployOrderStates.APPROVED);
        assertEquals(400, assertThrows(ApiException.class, () -> service.recordExecution(ORDER_ID,
                new DeployOrderService.ExecutionCmd("DONE", "2026-12-02T10:00:00+08:00",
                        "2026-12-02T11:00:00+08:00", null, null))).status());
        assertEquals(400, assertThrows(ApiException.class, () -> service.recordExecution(ORDER_ID,
                new DeployOrderService.ExecutionCmd("FAILED", "2026-12-02T12:00:00+08:00",
                        "2026-12-02T11:00:00+08:00", null, null))).status());
    }

    @Test
    @DisplayName("审批驳回：单据回 REJECTED（可改后重提），不触碰需求状态；重复回调幂等")
    void approvalRejectedIsIdempotent() {
        order.setStatus(DeployOrderStates.PENDING_APPROVAL);
        service.onApprovalRejected(ORDER_ID, "窗口冲突");
        assertEquals(DeployOrderStates.REJECTED, order.getStatus());

        service.onApprovalRejected(ORDER_ID, "再次回调");
        assertEquals(DeployOrderStates.REJECTED, order.getStatus());
        verify(auditService).record(eq("STATE_CHANGE"), eq("deploy_order"), eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("详情：强制放行留痕随单展示（原因/人/时间），准入快照 stale 由事实指纹决定")
    void detailShowsForceTrace() {
        order.setForcedRelease(true);
        order.setForceReleaseReason("紧急修复上线");
        order.setForceBy(42L);
        order.setForceAt(LocalDateTime.of(2026, 12, 2, 9, 30));
        order.setAdmissionResultJson("{\"items\":[]}");
        when(admissionEvaluator.parse(any())).thenReturn(result(false));
        when(admissionEvaluator.snapshot(order)).thenReturn(new DeployViews.AdmissionSnapshot(
                AdmissionPolicy.markForced(result(false).items(), true), LocalDateTime.now(), true));

        DeployViews.OrderDetail detail = service.detail(ORDER_ID);

        assertEquals("紧急修复上线", detail.forcedReleaseDetail().reason());
        assertTrue(detail.forcedReleaseDetail().by().contains("42"));
        assertTrue(detail.forcedRelease());
        assertEquals(5, detail.admission().items().size());
        assertTrue(detail.admission().stale(), "事实变了要提示重新校验");
        assertTrue(detail.admission().items().stream()
                .filter(item -> !item.passed()).allMatch(AdmissionItem::forced));
    }

    @Test
    @DisplayName("端到端：创建→提交被准入拦(409)→审批通过→登记执行→统计复算（终态 SUCCESS）")
    void endToEndFromDraftToSuccess() {
        DeployOrderEntity[] saved = new DeployOrderEntity[1];
        when(repository.save(any())).thenAnswer(invocation -> {
            DeployOrderEntity entity = invocation.getArgument(0);
            entity.setId(ORDER_ID);
            saved[0] = entity;
            return entity;
        });
        when(repository.findByIdAndTenantId(ORDER_ID, TENANT)).thenAnswer(invocation ->
                saved[0] == null ? Optional.empty() : Optional.of(saved[0]));
        when(requirementRepository.findByTenantIdAndReleaseId(TENANT, RELEASE_ID)).thenReturn(List.of());
        when(codeGenerator.next("DEP", TENANT)).thenReturn("DEP-0001");
        when(admissionEvaluator.evaluateAndStore(any())).thenReturn(result(false));
        when(admissionEvaluator.allPassed(any())).thenReturn(false);

        DeployViews.OrderDetail created = service.create(new DeployOrderService.CreateOrder(RELEASE_ID, "prod",
                "2026-12-02T10:00:00+08:00", "2026-12-02T12:00:00+08:00", "1.4.0",
                List.of(Map.of("name", "停流量", "order", 1), Map.of("name", "滚动发布", "order", 2)),
                "回滚到 1.3.0", "只影响 PROD", null, null));

        assertEquals(DeployOrderStates.DRAFT, created.status());
        assertEquals("PROD", created.env(), "环境统一大写");
        assertEquals(2, created.steps().size());

        DeployErrors.AdmissionFailedException blocked = assertThrows(DeployErrors.AdmissionFailedException.class,
                () -> service.submit(ORDER_ID));
        assertEquals(5, blocked.items().size());
        assertEquals(DeployOrderStates.DRAFT, saved[0].getStatus(), "被拦住时单据仍在草稿");

        // 强制放行已退役（恒 409），正常路径是走完需求发布审批
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.forceRelease(ORDER_ID, "验证-受控放行")).status());
        assertEquals(DeployOrderStates.DRAFT, saved[0].getStatus());

        service.onApprovalApproved(ORDER_ID);
        assertEquals(DeployOrderStates.APPROVED, saved[0].getStatus());

        service.recordExecution(ORDER_ID, new DeployOrderService.ExecutionCmd("SUCCESS",
                "2026-12-02T10:05:00+08:00", "2026-12-02T10:35:00+08:00", "灰度完成", null));
        assertEquals(DeployOrderStates.SUCCESS, saved[0].getStatus());
        assertEquals(30, saved[0].getDurationMinutes());

        when(repository.searchForStats(any(), anyLong(), anyString(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(saved[0]));
        DeployViews.DeployStats stats = service.stats(null, null, null, null);

        assertEquals(new java.math.BigDecimal("1.0000"), stats.successRate());
        assertEquals(new java.math.BigDecimal("30.0"), stats.avgDurationMinutes());

        verify(auditService).recordInNewTx(eq("deploy.force-release.retired"), eq("deploy_order"),
                eq(ORDER_ID), any());
        verify(auditService).record(eq("EXECUTION"), eq("deploy_order"), eq(ORDER_ID), any());
        verifyNoInteractions(notificationWriter);
    }
}
