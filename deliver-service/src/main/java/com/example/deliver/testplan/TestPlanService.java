package com.example.deliver.testplan;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.deliver.reqread.ReqReadModel;
import com.example.deliver.testcase.TestCaseEntity;
import com.example.deliver.testcase.TestCaseRepository;
import com.example.deliver.testcase.TestCaseStepEntity;
import com.example.deliver.testcase.TestCaseStepRepository;
import com.example.deliver.testcase.TestCaseViews;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans and execution.
 *
 * <p>Two rules carry AC-16 here. First: adding a case pins {@code case_version} at that moment, so
 * editing the case afterwards does not silently change what the plan tests. Second: an execution
 * is written with the pinned version and the summary is computed from plan cases (not from
 * executions), so "planned / executed / passed" describe the plan, never an arbitrary subset.</p>
 */
@Service
public class TestPlanService {

    private static final List<String> EXECUTION_RESULTS = List.of("PASS", "FAIL", "BLOCKED", "SKIP");
    private static final List<String> PLAN_STATUSES = List.of("DRAFT", "RUNNING", "DONE");

    private final TestPlanRepository plans;
    private final TestPlanCaseRepository planCases;
    private final TestExecutionRepository executions;
    private final TestCaseRepository cases;
    private final TestCaseStepRepository steps;
    private final TestPlanQuery query;
    private final ReqReadModel requirements;
    private final ScopedJdbc jdbc;
    private final AuditService audit;

    public TestPlanService(TestPlanRepository plans,
                           TestPlanCaseRepository planCases,
                           TestExecutionRepository executions,
                           TestCaseRepository cases,
                           TestCaseStepRepository steps,
                           TestPlanQuery query,
                           ReqReadModel requirements,
                           ScopedJdbc jdbc,
                           AuditService audit) {
        this.plans = plans;
        this.planCases = planCases;
        this.executions = executions;
        this.cases = cases;
        this.steps = steps;
        this.query = query;
        this.requirements = requirements;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ plans

    @Transactional
    public TestPlanViews.PlanDetail create(TestPlanViews.CreatePlan cmd) {
        DataScope scope = TenantContext.require();
        if (cmd == null || cmd.releaseId() == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "测试计划必须关联版本",
                    Map.of("field", "releaseId"));
        }
        Map<String, Object> release = requirements.release(cmd.releaseId());
        if (release == null) {
            throw new ApiException(404, "RELEASE_NOT_FOUND", "版本不存在或不在你的数据范围内",
                    Map.of("releaseId", cmd.releaseId()));
        }
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "测试计划名称不能为空", Map.of("field", "name"));
        }
        if (cmd.windowStart() != null && cmd.windowEnd() != null
                && cmd.windowEnd().isBefore(cmd.windowStart())) {
            throw new ApiException(400, "VALIDATION_FAILED", "计划结束时间不能早于开始时间",
                    Map.of("field", "windowEnd"));
        }
        TestPlanEntity entity = new TestPlanEntity();
        entity.setTenantId(scope.tenantId());
        entity.setReleaseId(cmd.releaseId());
        entity.setName(cmd.name().trim());
        entity.setWindowStart(startOf(cmd.windowStart()));
        entity.setWindowEnd(endOf(cmd.windowEnd()));
        entity.setOwnerId(cmd.ownerId() == null ? scope.userId() : cmd.ownerId());
        entity.setStatus("DRAFT");
        entity.setGateMode(normaliseGateMode(cmd.gateMode()));
        entity.setDomainId(releaseDomain(cmd.releaseId()));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        entity.setCreatedBy(scope.userId());
        entity.setUpdatedBy(scope.userId());
        plans.save(entity);
        if (cmd.caseIds() != null && !cmd.caseIds().isEmpty()) {
            addCases(entity, cmd.caseIds(), scope);
        }
        audit.record("PLAN_CREATE", "TEST_PLAN", entity.getId(),
                Map.of("releaseId", entity.getReleaseId(), "name", entity.getName()));
        return detail(entity.getId());
    }

    @Transactional(readOnly = true)
    public TestPlanViews.PlanDetail detail(long planId) {
        DataScope scope = TenantContext.require();
        TestPlanEntity plan = requirePlan(planId, scope.tenantId());
        TestPlanViews.PlanHeader header = query.header(planId);
        if (header == null) {
            throw new ApiException(404, "PLAN_NOT_FOUND", "测试计划不存在或不在你的数据范围内",
                    Map.of("planId", planId));
        }
        return new TestPlanViews.PlanDetail(header, query.cases(planId), query.summary(planId));
    }

    @Transactional
    public TestPlanViews.PlanDetail addCases(long planId, List<Long> caseIds) {
        DataScope scope = TenantContext.require();
        TestPlanEntity plan = requirePlan(planId, scope.tenantId());
        addCases(plan, caseIds, scope);
        return detail(planId);
    }

    private void addCases(TestPlanEntity plan, List<Long> caseIds, DataScope scope) {
        int added = 0;
        for (Long caseId : caseIds) {
            if (caseId == null) {
                continue;
            }
            if (planCases.findByPlanIdAndCaseId(plan.getId(), caseId).isPresent()) {
                continue;
            }
            TestCaseEntity testCase = cases.findByIdAndTenantId(caseId, scope.tenantId())
                    .filter(entity -> entity.getDeletedAt() == null)
                    .orElseThrow(() -> new ApiException(404, "CASE_NOT_FOUND",
                            "用例不存在或不在你的数据范围内", Map.of("caseId", caseId)));
            TestPlanCaseEntity row = new TestPlanCaseEntity();
            row.setTenantId(plan.getTenantId());
            row.setPlanId(plan.getId());
            row.setCaseId(testCase.getId());
            row.setCaseVersion(testCase.getCurrentVersion());
            row.setRequirementId(testCase.getRequirementId());
            row.setResult("NOT_RUN");
            planCases.save(row);
            added++;
        }
        if (added > 0) {
            plan.setUpdatedAt(LocalDateTime.now());
            plan.setUpdatedBy(scope.userId());
            plans.save(plan);
        }
    }

    @Transactional
    public void removeCase(long planId, long caseId) {
        DataScope scope = TenantContext.require();
        TestPlanEntity plan = requirePlan(planId, scope.tenantId());
        TestPlanCaseEntity row = planCases.findByPlanIdAndCaseId(planId, caseId)
                .orElseThrow(() -> new ApiException(404, "CASE_NOT_IN_PLAN", "该用例不在计划内",
                        Map.of("planId", planId, "caseId", caseId)));
        if (!"NOT_RUN".equals(row.getResult())) {
            throw new ApiException(409, "CASE_ALREADY_EXECUTED", "用例已执行过，不能从计划中移除",
                    Map.of("planId", planId, "caseId", caseId, "result", row.getResult()));
        }
        planCases.delete(row);
        plan.setUpdatedAt(LocalDateTime.now());
        plan.setUpdatedBy(scope.userId());
        plans.save(plan);
    }

    // ------------------------------------------------------------------ execution

    @Transactional
    public TestPlanViews.ExecutionResult execute(long planId, TestPlanViews.ExecuteCommand cmd) {
        DataScope scope = TenantContext.require();
        TestPlanEntity plan = requirePlan(planId, scope.tenantId());
        if (cmd == null || cmd.caseId() == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "执行登记必须指定用例",
                    Map.of("field", "caseId"));
        }
        String result = normaliseResult(cmd.result());
        TestPlanCaseEntity planCase = planCases.findByPlanIdAndCaseId(planId, cmd.caseId())
                .orElseThrow(() -> new ApiException(404, "CASE_NOT_IN_PLAN", "该用例不在计划内",
                        Map.of("planId", planId, "caseId", cmd.caseId())));
        int version = planCase.getCaseVersion();
        LocalDateTime now = LocalDateTime.now();
        TestExecutionEntity execution = new TestExecutionEntity();
        execution.setTenantId(scope.tenantId());
        execution.setPlanId(planId);
        execution.setCaseId(planCase.getCaseId());
        execution.setCaseVersion(version);
        execution.setRequirementId(planCase.getRequirementId());
        execution.setSource("MANUAL");
        execution.setResult(result);
        execution.setEvidenceFile(cmd.evidence());
        execution.setExecutorId(scope.userId());
        execution.setExecutedAt(now);
        executions.save(execution);

        planCase.setResult(result);
        planCase.setExecutionId(execution.getId());
        planCase.setLastExecutedAt(now);
        planCase.setExecutorId(scope.userId());
        planCase.setNote(cmd.note());
        planCases.save(planCase);

        if ("DRAFT".equals(plan.getStatus())) {
            plan.setStatus("RUNNING");
        }
        TestPlanViews.PlanSummary summary = query.summary(planId);
        if (summary.planned() > 0 && summary.notRun() == 0) {
            plan.setStatus("DONE");
        }
        plan.setUpdatedAt(now);
        plan.setUpdatedBy(scope.userId());
        plans.save(plan);

        audit.record("EXECUTION_REPORT", "TEST_EXECUTION", execution.getId(),
                Map.of("planId", planId, "caseId", planCase.getCaseId(), "caseVersion", version,
                        "result", result));
        return new TestPlanViews.ExecutionResult(execution.getId(), planCase.getCaseId(), version,
                result, now.toString(), stepsOf(planCase.getCaseId(), version));
    }

    @Transactional(readOnly = true)
    public TestPlanViews.PlanSummary summary(long planId) {
        DataScope scope = TenantContext.require();
        requirePlan(planId, scope.tenantId());
        return query.summary(planId);
    }

    @Transactional(readOnly = true)
    public com.example.platform.common.scope.PageResponse<TestPlanViews.PlanHeader> page(
            Long releaseId, String status, String keyword, int page, int size) {
        TenantContext.require();
        return query.page(releaseId, status, keyword, page, size);
    }

    // ------------------------------------------------------------------ helpers

    private List<TestCaseViews.StepView> stepsOf(long caseId, int versionNo) {
        List<TestCaseViews.StepView> out = new ArrayList<>();
        for (TestCaseStepEntity step : steps.findByCaseIdAndVersionNoOrderByStepNoAsc(caseId, versionNo)) {
            out.add(new TestCaseViews.StepView(step.getStepNo(), step.getAction(), step.getExpected()));
        }
        return out;
    }

    private TestPlanEntity requirePlan(long planId, long tenantId) {
        return plans.findByIdAndTenantId(planId, tenantId)
                .orElseThrow(() -> new ApiException(404, "PLAN_NOT_FOUND",
                        "测试计划不存在或不在你的数据范围内", Map.of("planId", planId)));
    }

    private Long releaseDomain(long releaseId) {
        Map<String, Object> row = jdbc.one("""
                SELECT rel.domain_id AS domain_id FROM `release` rel
                WHERE rel.id = :releaseId AND /*scope:rel*/
                """, Map.of("releaseId", releaseId));
        Object value = row == null ? null : row.get("domain_id");
        return value == null ? null : ((Number) value).longValue();
    }

    private static String normaliseGateMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        String normalised = mode.trim().toUpperCase(Locale.ROOT);
        if (!List.of("STRICT", "LOOSE").contains(normalised)) {
            throw new ApiException(400, "VALIDATION_FAILED", "门禁模式必须为 STRICT 或 LOOSE",
                    Map.of("field", "gateMode", "value", mode));
        }
        return normalised;
    }

    private static String normaliseResult(String result) {
        if (result == null || result.isBlank()) {
            throw new ApiException(400, "EXECUTION_RESULT_INVALID", "执行结果不能为空",
                    Map.of("allowed", EXECUTION_RESULTS));
        }
        String normalised = result.trim().toUpperCase(Locale.ROOT);
        if (!EXECUTION_RESULTS.contains(normalised)) {
            throw new ApiException(400, "EXECUTION_RESULT_INVALID",
                    "执行结果必须为 PASS/FAIL/BLOCKED/SKIP（未执行由计划初始化）",
                    Map.of("allowed", EXECUTION_RESULTS, "value", result));
        }
        return normalised;
    }

    private static LocalDateTime startOf(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private static LocalDateTime endOf(LocalDate date) {
        return date == null ? null : date.atTime(LocalTime.of(23, 59, 59));
    }

    /** Plan statuses accepted by the UI; exposed for validation reuse in the controller tests. */
    public static List<String> planStatuses() {
        return PLAN_STATUSES;
    }

    /** Visible for the harness: the row shape of a plan case, keyed for assertions. */
    static Map<String, Object> asMap(TestPlanCaseEntity row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("caseId", row.getCaseId());
        map.put("caseVersion", row.getCaseVersion());
        map.put("result", row.getResult());
        return map;
    }
}
