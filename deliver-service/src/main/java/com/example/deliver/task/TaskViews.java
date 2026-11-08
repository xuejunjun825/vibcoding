package com.example.deliver.task;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class TaskViews {

    private TaskViews() {
    }

    public record CreateTask(Long requirementId,
                             Long sprintId,
                             String title,
                             Long assigneeId,
                             BigDecimal estimateHours,
                             LocalDate startDate,
                             LocalDate dueDate) {
    }

    /** Every field optional: PATCH carries only what changed. */
    public record PatchTask(Long sprintId,
                            String title,
                            Long assigneeId,
                            BigDecimal estimateHours,
                            LocalDate startDate,
                            LocalDate dueDate,
                            String status,
                            Integer sortNo) {
    }

    public record MoveTask(String status, Integer sortNo) {
    }

    public record TaskView(long id,
                           long requirementId,
                           Long sprintId,
                           String title,
                           Long assigneeId,
                           BigDecimal estimateHours,
                           LocalDate startDate,
                           LocalDate dueDate,
                           String status,
                           int sortNo,
                           long domainId,
                           Long vendorId,
                           LocalDateTime updatedAt) {
    }

    /**
     * Completion rollup for one requirement. {@code completionRate} is done/total — the same
     * division rule as every other ratio (2dp, zero denominator → 0.00), so the number the
     * requirement page shows and the number the board shows cannot round differently.
     */
    public record TaskProgress(long requirementId,
                               long total,
                               long todo,
                               long doing,
                               long done,
                               long blocked,
                               BigDecimal completionRate,
                               List<TaskView> items) {
    }
}
