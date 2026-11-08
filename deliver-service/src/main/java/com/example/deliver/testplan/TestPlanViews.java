package com.example.deliver.testplan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.deliver.testcase.TestCaseViews;

/** Wire shapes for plans, execution and the summary card. Field names match the UI contract. */
public final class TestPlanViews {

    private TestPlanViews() {
    }

    public record PlanHeader(long id, String code, String name, Long releaseId, String status,
                             String gateMode, String windowStart, String windowEnd, String ownerName,
                             long planned, long executed) {
    }

    public record PlanDetail(PlanHeader plan, List<TestCaseViews.PlanCaseRow> cases,
                             PlanSummary summary) {
    }

    /**
     * Rates come from {@code MetricFormulas} so the card, the gate and the release admission agree
     * on rounding; a zero denominator yields 0.00 rather than a division error.
     */
    public record PlanSummary(long planned, long executed, long passed, long failed, long blocked,
                              long skip, long notRun, BigDecimal passRate,
                              BigDecimal executedPassRate, BigDecimal coverage, boolean complete) {
    }

    public record CreatePlan(Long releaseId, String name, Long ownerId, LocalDate windowStart,
                             LocalDate windowEnd, List<Long> caseIds, String gateMode) {
    }

    public record ExecuteCommand(Long caseId, String result, String note, String evidence) {
    }

    public record ExecutionResult(long executionId, long caseId, int caseVersion, String result,
                                  String executedAt, List<TestCaseViews.StepView> steps) {
    }

    public record AddCases(List<Long> caseIds) {
    }
}
