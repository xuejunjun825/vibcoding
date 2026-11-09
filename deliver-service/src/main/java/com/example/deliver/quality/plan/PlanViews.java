package com.example.deliver.quality.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire models of the plan lifecycle, rounds, assignments and the workbench.
 *
 * <p>{@link PlanDetail#allowedNext()} is always rendered from {@code StateTables.PLAN}: the
 * frontend stepper must never carry its own copy of the transition table, otherwise a button and
 * the API disagree the moment a state is added.</p>
 */
public final class PlanViews {

    private PlanViews() {
    }

    /** Full plan detail: lifecycle + pinned strategy/env + criteria + round/assignment rollups. */
    public record PlanDetail(
            long id,
            String code,
            String name,
            long releaseId,
            String status,
            List<String> allowedNext,
            int revision,
            String gateMode,
            String windowStart,
            String windowEnd,
            Long ownerId,
            String ownerName,
            StrategyRef strategy,
            EnvRef env,
            Criteria criteria,
            RoundsSummary rounds,
            AssignmentSummary assignment,
            Summary summary) {
    }

    public record StrategyRef(long id, int versionNo, String status) {
    }

    public record EnvRef(long id, String name, String status) {
    }

    /** Entry/exit criteria stay as the raw JSON the evaluator reads, plus the parsed items. */
    public record Criteria(String entryCriteriaJson, String exitCriteriaJson,
                           List<CriterionItem> entryItems, List<CriterionItem> exitItems) {
    }

    public record CriterionItem(String key, String op, BigDecimal value) {
    }

    public record RoundsSummary(int total, Long activeRoundId, Integer activeRoundNo,
                                String conclusion) {
    }

    public record AssignmentSummary(int rows, long plannedTotal, long caseTotal, long diff,
                                    long unassignedCases) {
    }

    /** Counts shared by the plan summary, a round and the workbench totals. */
    public record Summary(long total, long executed, long passed, long failed, long blocked,
                          long notRun, BigDecimal passRate, BigDecimal coverage) {
    }

    // ------------------------------------------------------------------ rounds

    public record RoundView(long id, long planId, int roundNo, String roundType, String name,
                            String status, String conclusion, String startAt, String endAt,
                            int caseTotal, int executed, int passed, int failed, int blocked,
                            BigDecimal passRate, BigDecimal coverage, boolean active,
                            List<Long> executorIds, String note) {
    }

    public record RoundDetail(RoundView round, List<RoundCaseRow> cases, Summary counts) {
    }

    public record RoundCaseRow(long caseId, String caseCode, String title, String result,
                               String executedAt, Long executorId, String note) {
    }

    // ------------------------------------------------------------------ assignment / progress

    public record AssignmentView(long id, long planId, Long roundId, long executorId,
                                 String executorName, Long moduleId, String moduleName,
                                 int plannedCount, String dueDate, int executedCount,
                                 boolean overdue) {
    }

    public record AssignmentRow(Long executorId, Long moduleId, int plannedCount,
                                LocalDate dueDate) {
    }

    public record AssignmentReplace(Long roundId, List<AssignmentRow> rows) {
    }

    public record Progress(List<ByPerson> byPerson, List<ByModule> byModule,
                           List<Overdue> overdue, Summary totals) {
    }

    public record ByPerson(long executorId, String executorName, int planned, int executed,
                           int passed, int failed, int blocked, BigDecimal passRate) {
    }

    public record ByModule(Long moduleId, String moduleName, int planned, int executed, int passed,
                           int failed, int blocked) {
    }

    public record Overdue(long caseId, String caseCode, String title, long executorId,
                          String executorName, String dueDate, String result) {
    }

    // ------------------------------------------------------------------ change log

    public record ChangeLogRow(long id, int revision, String changeType, String reason,
                               Long operatorId, String operatorName, Long approverId,
                               String approverName, String createdAt, String beforeJson,
                               String afterJson) {
    }

    // ------------------------------------------------------------------ request bodies

    public record UpdatePlan(String name, LocalDate windowStart, LocalDate windowEnd, Long envId,
                             Object entryCriteria, Object exitCriteria, Long approverId,
                             String reason) {
    }

    /** Body shared by submit/approve/reject/close/archive; every field is optional. */
    public record ActionBody(String reason, String comment, Long approverId) {
    }

    public record OpenRound(String roundType, String name, String scopeMode, List<Long> caseIds,
                            List<Long> executorIds, String note) {
    }

    public record CloseRound(String conclusion, String note) {
    }

    public record AssignmentBody(Long roundId, List<AssignmentRow> rows) {
    }
}
