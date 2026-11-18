package com.example.req.requirement;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalStore;
import com.example.req.approval.ApprovalTask;
import com.example.req.approval.ApprovalViews;
import com.example.req.approval.ApproverDirectory;
import com.example.req.approval.StartApproval;
import com.example.req.sys.SysDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回填判定与事务边界的单测（r29）：dry-run 零写入、六态分类、失败隔离、二次 apply 幂等。
 *
 * <p>为什么用「真实 {@link WbsBackfillRowWriter} + 桩引擎」而不是连库：回填最贵的缺陷是
 * 「dry-run 偷偷建单」和「一条失败带走整批」。前者靠 spy 写入器（apply 零调用）+ 引擎零交互证明；
 * 后者靠真实走一遍「重读 → 校验 → 建单 → 真实待办 → 批准 → 复核」，坏行在建单前抛
 * {@link WbsBackfillRowWriter.RowFailure}，服务只把这一行收敛成 FAILED，整批继续。</p>
 *
 * <p>六态判定本身是 r27 的唯一真相（{@link WbsEstimateService#statusesOf}），这里桩掉；
 * 本测试只断言「拿到某态后回填落成哪一行」，避免把 0.20 STALE 阈值等规则复制进回填测试。
 * 扫描 SQL 的结果用 {@link FakeSysDb} 注入，夹具只造「扫描六列」。</p>
 */
class WbsBackfillServiceTest {

    private static final long TENANT = 1L;
    private static final long DOMAIN = 1L;
    private static final long OWNER = 9L;
    private static final String TENANT_CODE = "t1";
    private static final String BATCH = "wbsbf-test-0001";
    private static final String NOTE = "存量回填（" + BATCH + "）";

    /** 需求 id → 实例 id / 待办 id 的固定偏移：让 stubs 无需额外映射表即可反查。 */
    private static final long INSTANCE_OFFSET = 1_000L;
    private static final long TASK_OFFSET = 3_000L;

    private final RequirementService requirements = mock(RequirementService.class);
    private final WbsEstimateService estimates = mock(WbsEstimateService.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalStore store = mock(ApprovalStore.class);
    private final ApproverDirectory directory = mock(ApproverDirectory.class);

    /** 被桩成 PENDING 的需求：六态里的「已提交未决」。 */
    private final Set<Long> pending = new HashSet<>();
    /** 被桩成 APPROVED 的需求：engine.approve 的桩会把人天落地，复核才能读到 APPROVED。 */
    private final Set<Long> approved = new HashSet<>();
    /** requireVisible 的重读来源（写入器执行前必须取「建单那一刻」的实体）。 */
    private final Map<Long, RequirementEntity> entities = new LinkedHashMap<>();

    private final FakeSysDb db = new FakeSysDb();
    private final WbsBackfillRowWriter writer = spy(new WbsBackfillRowWriter(
            provider(engine), store, requirements, estimates, directory));

    @BeforeEach
    void setUp() {
        TenantContext.set(DataScope.system(TENANT));
        when(store.tasks(anyLong())).thenAnswer(invocation -> {
            long instanceId = invocation.getArgument(0);
            long requirementId = instanceId - INSTANCE_OFFSET;
            return List.of(task(requirementId + TASK_OFFSET, OWNER, instanceId));
        });
        when(store.history(anyLong())).thenReturn(List.of());
        when(requirements.requireVisible(anyLong()))
                .thenAnswer(invocation -> entities.get((Long) invocation.getArgument(0)));
        when(estimates.statusOf(any())).thenAnswer(invocation -> {
            RequirementEntity entity = invocation.getArgument(0);
            return entity == null ? WbsEstimateService.STATUS_NONE : statusOf(entity.getId());
        });
        when(estimates.statusesOf(anyList())).thenAnswer(invocation -> {
            List<WbsEstimateService.GateSubject> subjects = invocation.getArgument(0);
            Map<Long, String> statuses = new LinkedHashMap<>();
            for (WbsEstimateService.GateSubject subject : subjects) {
                statuses.put(subject.requirementId(), statusOf(subject.requirementId()));
            }
            return statuses;
        });
        when(directory.domainOwners(eq(TENANT), any())).thenReturn(List.of(OWNER));
        when(directory.activeUsersByRole(anyLong(), anyString())).thenReturn(List.of());
        when(engine.start(any())).thenAnswer(invocation -> {
            StartApproval command = invocation.getArgument(0);
            return ApprovalViews.InstanceView.of(
                    instance(command.bizId() + INSTANCE_OFFSET, command.bizId()));
        });
        doAnswer(invocation -> {
            long taskId = invocation.getArgument(0);
            approved.add(taskId - TASK_OFFSET);
            return null;
        }).when(engine).approve(anyLong(), anyString());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void dryRunNeverWritesAndReportsPlanned() {
        given(11L, "PDR-1", RequirementRules.TYPE_PDR, "PENDING_ANALYSIS", "8.0");
        given(12L, "RD-1", RequirementRules.TYPE_RD, "TODO", "3.5");

        WbsBackfillViews.Report report = run(true);

        assertThat(report.mode()).isEqualTo(WbsBackfillViews.MODE_DRY_RUN);
        assertThat(report.batchId()).isEqualTo(BATCH);
        assertThat(report.summary()).isEqualTo(new WbsBackfillViews.Summary(2, 2, 0, 0, 0));
        assertThat(report.rows()).allSatisfy(row -> {
            assertThat(row.result()).isEqualTo(WbsBackfillViews.RESULT_PLANNED);
            assertThat(row.reason()).isNull();
            assertThat(row.instanceId()).isNull();
            // 计划行也带「回填前六态」：与 CREATED 行同口径，不因 dry-run 就退化成 null。
            assertThat(row.previousStatus()).isEqualTo(WbsEstimateService.STATUS_NONE);
        });
        // 零写入：dry-run 根本进不了写入器（不建实例、不加待办、不回写人天）
        verify(writer, never()).apply(any(), anyLong(), anyString());
        verify(engine, never()).start(any());
        verify(engine, never()).approve(anyLong(), anyString());
        verify(requirements, never()).requireVisible(anyLong());
    }

    @Test
    void busAndTerminalAreSkippedWithReason() {
        given(21L, "BUS-1", RequirementRules.TYPE_BUS, "POOLED", "5.0");
        given(22L, "PDR-CLOSED", RequirementRules.TYPE_PDR, "ACCEPTED_CLOSED", "5.0");
        given(23L, "PDR-NODAYS", RequirementRules.TYPE_PDR, "PENDING_ANALYSIS", null);
        given(24L, "PDR-ZERO", RequirementRules.TYPE_PDR, "PENDING_ANALYSIS", "0");
        given(25L, "PDR-ACTIVE", RequirementRules.TYPE_PDR, "PENDING_ANALYSIS", "2.0");
        given(26L, "PDR-DONE", RequirementRules.TYPE_PDR, "PENDING_ANALYSIS", "2.0");
        pending.add(25L);
        approved.add(26L);

        WbsBackfillViews.Report report = run(true);

        // 全跳过：BUS 先按 type 判（不落到 NO_ESTIMATE_DAYS），终态按 StateTables，人天空/非正不算可补，
        // PENDING/APPROVED 按六态唯一真相跳过——回填不重写任何一条判定。
        assertThat(report.summary()).isEqualTo(new WbsBackfillViews.Summary(6, 0, 0, 6, 0));
        assertThat(row(report, "BUS-1").reason()).isEqualTo(WbsBackfillViews.REASON_TYPE_BUS);
        assertThat(row(report, "PDR-CLOSED").reason()).isEqualTo(WbsBackfillViews.REASON_TERMINAL);
        assertThat(row(report, "PDR-NODAYS").reason())
                .isEqualTo(WbsBackfillViews.REASON_NO_ESTIMATE_DAYS);
        assertThat(row(report, "PDR-ZERO").reason())
                .isEqualTo(WbsBackfillViews.REASON_NO_ESTIMATE_DAYS);
        assertThat(row(report, "PDR-ACTIVE").reason())
                .isEqualTo(WbsBackfillViews.REASON_HAS_ACTIVE_ESTIMATE);
        assertThat(row(report, "PDR-DONE").reason())
                .isEqualTo(WbsBackfillViews.REASON_HAS_APPROVED_ESTIMATE);
        verify(writer, never()).apply(any(), anyLong(), anyString());
        verify(engine, never()).start(any());
    }

    @Test
    void invalidHalfDayIsFailedAndOthersContinue() {
        given(31L, "PDR-BAD", RequirementRules.TYPE_PDR, "PENDING_ANALYSIS", "3.2");
        given(32L, "PDR-GOOD", RequirementRules.TYPE_PDR, "PENDING_ANALYSIS", "3.0");
        given(33L, "BUS-1", RequirementRules.TYPE_BUS, "POOLED", "5.0");

        WbsBackfillViews.Report report = run(false);

        assertThat(report.mode()).isEqualTo(WbsBackfillViews.MODE_APPLY);
        // 非法人天在建单前被 RequirementRules.checkHalfDay 拦下：本行 FAILED、无实例。
        assertThat(row(report, "PDR-BAD").result()).isEqualTo(WbsBackfillViews.RESULT_FAILED);
        assertThat(row(report, "PDR-BAD").reason())
                .isEqualTo(WbsBackfillViews.REASON_INVALID_ESTIMATE_DAYS);
        assertThat(row(report, "PDR-BAD").instanceId()).isNull();
        // 同批好行照常补齐（失败隔离：不是整批回滚）。
        WbsBackfillViews.Row good = row(report, "PDR-GOOD");
        assertThat(good.result()).isEqualTo(WbsBackfillViews.RESULT_CREATED);
        assertThat(good.reason()).isNull();
        assertThat(good.instanceId()).isEqualTo(32L + INSTANCE_OFFSET);
        assertThat(good.previousStatus()).isEqualTo(WbsEstimateService.STATUS_NONE);
        assertThat(good.approverId()).isEqualTo(OWNER);
        assertThat(good.approverSource()).isEqualTo(WbsBackfillViews.SOURCE_DOMAIN_OWNER);
        assertThat(good.flags()).isEmpty();
        assertThat(row(report, "BUS-1").reason()).isEqualTo(WbsBackfillViews.REASON_TYPE_BUS);
        // apply 口径：planned == created + failed，跳过行不计入计划。
        assertThat(report.summary()).isEqualTo(new WbsBackfillViews.Summary(3, 2, 1, 1, 1));

        // engine.start 只在「应建单」的那一行发生，且快照带批次/来源/人天。
        ArgumentCaptor<StartApproval> starts = ArgumentCaptor.forClass(StartApproval.class);
        verify(engine, times(1)).start(starts.capture());
        StartApproval start = starts.getValue();
        assertThat(start.bizId()).isEqualTo(32L);
        assertThat(start.bizType()).isEqualTo(WbsEstimateService.BIZ_TYPE);
        assertThat(start.attributes())
                .containsEntry("estimateDays", new BigDecimal("3.0"))
                .containsEntry("source", "BACKFILL")
                .containsEntry("batchId", BATCH)
                .containsEntry("note", NOTE);
        // 批准者是该实例真实待办的审批人，不是伪造的代批。
        verify(engine).approve(eq(32L + TASK_OFFSET), eq(NOTE));
    }

    @Test
    void secondApplyPlansNothing() {
        given(41L, "PDR-A", RequirementRules.TYPE_PDR, "PENDING_ANALYSIS", "2.0");
        given(42L, "RD-B", RequirementRules.TYPE_RD, "TODO", "1.5");

        WbsBackfillViews.Report first = run(false);
        assertThat(first.summary()).isEqualTo(new WbsBackfillViews.Summary(2, 2, 2, 0, 0));

        // 同批重跑：六态已变 APPROVED，计划必须为 0——幂等不靠批号，靠状态判定。
        WbsBackfillViews.Report second = run(false);

        assertThat(second.batchId()).isEqualTo(BATCH);
        assertThat(second.summary()).isEqualTo(new WbsBackfillViews.Summary(2, 0, 0, 2, 0));
        assertThat(second.rows()).allSatisfy(row -> {
            assertThat(row.result()).isEqualTo(WbsBackfillViews.RESULT_SKIPPED);
            assertThat(row.reason()).isEqualTo(WbsBackfillViews.REASON_HAS_APPROVED_ESTIMATE);
        });
        verify(engine, times(2)).start(any());
        verify(writer, times(2)).apply(any(), anyLong(), anyString());
    }

    // ---------------------------------------------------------------- 工具

    private WbsBackfillViews.Report run(boolean dryRun) {
        WbsBackfillService service = new WbsBackfillService(requirements, estimates, writer, db);
        return service.run(new WbsBackfillViews.Command(TENANT_CODE, BATCH, dryRun));
    }

    private String statusOf(long requirementId) {
        if (pending.contains(requirementId)) {
            return WbsEstimateService.STATUS_PENDING;
        }
        return approved.contains(requirementId)
                ? WbsEstimateService.STATUS_APPROVED : WbsEstimateService.STATUS_NONE;
    }

    /** 造一条扫描行 + 一条可被重读的需求实体（写入器不用计划快照的人天）。 */
    private void given(long id, String code, String type, String status, String days) {
        BigDecimal estimateDays = days == null ? null : new BigDecimal(days);
        db.add(new Object[]{id, code, type, status, estimateDays, DOMAIN});
        RequirementEntity entity = new RequirementEntity();
        entity.setId(id);
        entity.setTenantId(TENANT);
        entity.setCode(code);
        entity.setType(type);
        entity.setStatus(status);
        entity.setEstimateDays(estimateDays);
        entity.setDomainId(DOMAIN);
        entities.put(id, entity);
    }

    private static WbsBackfillViews.Row row(WbsBackfillViews.Report report, String code) {
        return report.rows().stream().filter(row -> code.equals(row.code())).findFirst()
                .orElseThrow(() -> new AssertionError("报告里没有行: " + code));
    }

    private static ApprovalInstance instance(long id, long requirementId) {
        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(id);
        instance.setTenantId(TENANT);
        instance.setBizType(WbsEstimateService.BIZ_TYPE);
        instance.setBizId(requirementId);
        instance.setStatus(ApprovalInstance.STATUS_PENDING);
        instance.setActiveFlag(ApprovalInstance.ACTIVE);
        return instance;
    }

    private static ApprovalTask task(long id, long approverId, long instanceId) {
        ApprovalTask task = new ApprovalTask();
        task.setId(id);
        task.setTenantId(TENANT);
        task.setInstanceId(instanceId);
        task.setNodeKey("WBS_ESTIMATE_APPROVE");
        task.setNodeName("工时评估审批");
        task.setApproverId(approverId);
        task.setMode("OR");
        task.setStatus(ApprovalTask.STATUS_PENDING);
        task.setOrigin(ApprovalTask.ORIGIN_INITIAL);
        return task;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /** 只替换扫描与租户解析的 SysDb：回填单测不该连库，也不该把 SQL 结果拼成实体。 */
    private static final class FakeSysDb extends SysDb {

        private final List<Object[]> scanned = new ArrayList<>();

        void add(Object[] row) {
            scanned.add(row);
        }

        @Override
        public Optional<Object[]> row(String sql, Object... params) {
            return Optional.of(new Object[]{TENANT});
        }

        @Override
        public List<Object[]> rows(String sql, Object... params) {
            return List.copyOf(scanned);
        }
    }
}
