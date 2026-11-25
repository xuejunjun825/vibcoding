package com.example.req.pdr;

import java.util.List;

/**
 * PDR 评审（r41）的请求/响应契约。
 *
 * <p>评审单的读模型刻意把「评审人」和「意见」分成两个字段：{@code reviewerIds} 是发起时的
 * 快照（谁应该评），{@code opinions} 是实际发生的（谁评了什么）。两者在 MODE_ALL 下的差集就是
 * 「还没评的人」——前端据此画进度（2/4 已评），服务端据此判断能不能出结论。</p>
 */
public final class PdrViews {

    private PdrViews() {
    }

    /** 发起评审：{@code reviewerIds} 为空 = 从项目组名册取，仍为空则回退产品经理 + 域负责人。 */
    public record StartReview(List<Long> reviewerIds, String note, String dueAt, String mode) {
    }

    /** 一条待提交意见。{@code category} 必填（九类之一），{@code comment} 在非 PASS 时必填。 */
    public record OpinionInput(String decision, String category, String comment) {
    }

    /**
     * 提交意见：既可以只带顶层三字段（单条），也可以带 {@code opinions[]}（多条，一次提交
     * 属于同一位评审人）。两者同时出现时 {@code opinions[]} 优先，顶层字段仅在数组为空时兜底。
     */
    public record SubmitOpinions(List<OpinionInput> opinions, String decision, String category,
                                 String comment) {
    }

    /** 评审单（列表与详情同形；{@code fallback=true} 表示评审人来自回退而不是项目组）。 */
    public record ReviewView(long id, long requirementId, Long projectId, String status, String mode,
                             String conclusion, long initiatorId, String reviewerSource,
                             boolean fallback, List<Long> reviewerIds, String note,
                             String dueAt, String createdAt, String closedAt) {
    }

    /** 意见（带评审人姓名：列表要直接渲染，不让前端再查一次用户表）。 */
    public record OpinionView(long id, long reviewId, long reviewerId, String reviewerName, int seq,
                              String decision, String category, String categoryLabel,
                              String comment, String status, Long taskId, String createdAt) {
    }

    /** 详情：评审单 + 全部意见（按 reviewerId, seq 排序）。 */
    public record ReviewDetail(ReviewView review, List<OpinionView> opinions) {
    }

    /** 意见分页（GET /requirements/{id}/pdr-review/opinions）。 */
    public record OpinionPage(List<OpinionView> items, long total, int page, int size) {
    }

    /** 转任务请求：全部可空。{@code title} 缺省取意见前 40 字，{@code description} 缺省带来源。 */
    public record ToTask(Long assigneeId, String dueDate, java.math.BigDecimal estimateHours,
                         String title, String description) {
    }

    /**
     * 转任务响应。{@code taskCode} 由交付域合成（{@code TASK-<id>}，task 表自 V1 起没有 code 列），
     * {@code opinion} 是回写后的意见行（status=TASKED、taskId 已填）。
     */
    public record ToTaskResult(long taskId, String taskCode, OpinionView opinion) {
    }

    /** 结论判定的依据（随详情下发，前端不必自己数票）。 */
    public record Tally(int reviewerCount, int submittedCount, int passCount, int needChangeCount,
                        int rejectCount, List<Long> pendingReviewerIds) {
    }

    /** 统计的一个分组桶：{@code key} 是字典码/用户 id/决策码，{@code label} 是展示文本。 */
    public record Bucket(String key, String label, long count, java.math.BigDecimal ratio) {
    }

    /**
     * 评审统计（设计 §4.4）。时长口径是 created_at → closed_at 的小时数，只统计已关闭的单；
     * 闭环率 = (TASKED + RESOLVED) / 全部意见。
     */
    public record StatsView(String groupBy, long reviewCount, long decidedCount,
                            long opinionCount, long taskedCount, long resolvedCount,
                            java.math.BigDecimal closureRate,
                            java.math.BigDecimal avgReviewHours,
                            java.math.BigDecimal p90ReviewHours,
                            List<Bucket> buckets) {
    }

    /** 报表数据集描述（GET /pdr-reviews/datasets）：交给 /reports 的编辑器选字段。 */
    public record DatasetInfo(String code, String name, List<String> dimensions,
                              List<String> measures) {
    }
}
