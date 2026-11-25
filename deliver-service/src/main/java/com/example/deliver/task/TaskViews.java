package com.example.deliver.task;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class TaskViews {

    private TaskViews() {
    }

    /**
     * Task creation. {@code description} 是 r41 追加的：PDR 评审意见转任务时要带上
     * 「来源：PDR 评审 #id · 分类 · 原文」（设计 §4.5）。空值表示「这条任务不是从意见转来的」，
     * 与其它客户端不传该字段的旧行为完全一致。
     */
    public record CreateTask(Long requirementId,
                             Long sprintId,
                             String title,
                             String description,
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
                           String description,
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
