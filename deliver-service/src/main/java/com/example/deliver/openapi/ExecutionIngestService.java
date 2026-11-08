package com.example.deliver.openapi;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.deliver.testcase.TestCaseEntity;
import com.example.deliver.testcase.TestCaseRepository;
import com.example.deliver.testcase.TestCaseVersionRepository;
import com.example.deliver.testplan.TestPlanCaseEntity;
import com.example.deliver.testplan.TestPlanCaseRepository;
import com.example.deliver.testplan.TestPlanEntity;
import com.example.deliver.testplan.TestPlanRepository;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingests an execution reported by an external CI client (AC-17).
 *
 * <p>Validation order is fixed and matters: any rejection happens <em>before</em> the execution
 * row is written, so a bogus report can never move a plan's result — a CI job that reports an
 * unknown case must not turn a plan green. Idempotency is delegated to the unique key through
 * {@link ExecutionWriter}.</p>
 *
 * <p>Ownership is derived, never accepted: the requirement comes from the case, and the
 * {@code plan_id} is checked against the caller's tenant instead of trusted.</p>
 */
@Service
public class ExecutionIngestService {

    private static final List<String> RESULTS = List.of("PASS", "FAIL", "BLOCKED", "SKIP");
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private final TestCaseRepository cases;
    private final TestCaseVersionRepository versions;
    private final TestPlanRepository plans;
    private final TestPlanCaseRepository planCases;
    private final ExecutionWriter writer;

    public ExecutionIngestService(TestCaseRepository cases,
                                  TestCaseVersionRepository versions,
                                  TestPlanRepository plans,
                                  TestPlanCaseRepository planCases,
                                  ExecutionWriter writer) {
        this.cases = cases;
        this.versions = versions;
        this.plans = plans;
        this.planCases = planCases;
        this.writer = writer;
    }

    @Transactional
    public OpenApiViews.IngestResult ingest(OpenApiViews.IngestCommand cmd, long clientId,
                                            String ip) {
        DataScope scope = TenantContext.require();
        long tenantId = scope.tenantId();
        if (cmd == null) {
            throw new ApiException(400, "PAYLOAD_INVALID", "请求体不能为空", Map.of());
        }
        if (cmd.runId() == null || cmd.runId().isBlank()) {
            throw new ApiException(400, "PAYLOAD_INVALID", "run_id 不能为空",
                    Map.of("field", "run_id"));
        }
        if (cmd.caseId() == null) {
            throw new ApiException(400, "PAYLOAD_INVALID", "case_id 不能为空",
                    Map.of("field", "case_id"));
        }
        String result = normaliseResult(cmd.result());
        LocalDateTime executedAt = parseExecutedAt(cmd.executedAt());
        if (cmd.durationMs() != null && cmd.durationMs() < 0) {
            throw new ApiException(400, "PAYLOAD_INVALID", "duration_ms 不能为负数",
                    Map.of("field", "duration_ms"));
        }

        TestCaseEntity testCase = cases.findByIdAndTenantId(cmd.caseId(), tenantId)
                .filter(entity -> entity.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(422, "TEST_CASE_NOT_FOUND",
                        "用例不存在：" + cmd.caseId(), Map.of("caseId", cmd.caseId())));

        int version = resolveVersion(testCase, cmd.caseVersion());
        Long planId = cmd.planId();
        if (planId != null) {
            TestPlanEntity plan = plans.findByIdAndTenantId(planId, tenantId)
                    .orElseThrow(() -> new ApiException(422, "TEST_PLAN_NOT_FOUND",
                            "测试计划不存在：" + planId, Map.of("planId", planId)));
            planCases.findByPlanIdAndCaseId(plan.getId(), testCase.getId())
                    .orElseThrow(() -> new ApiException(422, "CASE_NOT_IN_PLAN",
                            "用例不在该测试计划内", Map.of("planId", planId, "caseId", testCase.getId())));
        }

        ExecutionWriter.Inserted inserted = writer.insert(tenantId, clientId, cmd.runId().trim(),
                testCase.getId(), version, testCase.getRequirementId(), planId, result,
                cmd.durationMs(), cmd.logUrl(), executedAt, ip);
        if (!inserted.idempotent() && planId != null) {
            mergePlanResult(planId, testCase.getId(), result, executedAt, inserted.id());
        }
        return new OpenApiViews.IngestResult(inserted.id(), inserted.idempotent());
    }

    /**
     * The plan row shows the latest report, so an older report that arrives late never overwrites
     * a newer one (CI retries and out-of-order callbacks both do this in practice).
     */
    private void mergePlanResult(long planId, long caseId, String result, LocalDateTime executedAt,
                                 long executionId) {
        planCases.findByPlanIdAndCaseId(planId, caseId).ifPresent(row -> {
            LocalDateTime previous = row.getLastExecutedAt();
            if (previous != null && executedAt.isBefore(previous)) {
                return;
            }
            row.setResult(result);
            row.setExecutionId(executionId);
            row.setLastExecutedAt(executedAt);
            row.setNote(null);
            planCases.save(row);
        });
    }

    private int resolveVersion(TestCaseEntity testCase, Integer requested) {
        if (requested == null) {
            return testCase.getCurrentVersion();
        }
        if (requested < 1) {
            throw new ApiException(422, "CASE_VERSION_NOT_FOUND",
                    "用例版本不存在：v" + requested,
                    Map.of("caseId", testCase.getId(), "caseVersion", requested));
        }
        versions.findByCaseIdAndVersionNo(testCase.getId(), requested)
                .orElseThrow(() -> new ApiException(422, "CASE_VERSION_NOT_FOUND",
                        "用例版本不存在：v" + requested,
                        Map.of("caseId", testCase.getId(), "caseVersion", requested)));
        return requested;
    }

    private static String normaliseResult(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "RESULT_INVALID", "result 不能为空",
                    Map.of("allowed", RESULTS));
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!RESULTS.contains(value)) {
            throw new ApiException(400, "RESULT_INVALID",
                    "result 必须为 PASS/FAIL/BLOCKED/SKIP（未执行不能上报）",
                    Map.of("allowed", RESULTS, "value", raw));
        }
        return value;
    }

    private static LocalDateTime parseExecutedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "PAYLOAD_INVALID", "executed_at 不能为空",
                    Map.of("field", "executed_at"));
        }
        try {
            return OffsetDateTime.parse(raw.trim()).atZoneSameInstant(DB_ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(raw.trim());
            } catch (DateTimeParseException second) {
                throw new ApiException(400, "EXECUTED_AT_INVALID",
                        "executed_at 必须为带时区的 ISO-8601 时间，例如 2026-11-09T00:00:00+08:00",
                        Map.of("value", raw));
            }
        }
    }
}
