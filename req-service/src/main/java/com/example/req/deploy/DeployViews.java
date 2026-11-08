package com.example.req.deploy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 发布单对外视图（与前端 {@code web/src/api/types.ts} 的 DeployOrder* 一一对应）。 */
public final class DeployViews {

    private DeployViews() {
    }

    /** 发布步骤：{@code order} 从 1 开始，前端按它排序（不让渲染顺序决定执行顺序）。 */
    public record Step(String name, Integer order) {
    }

    /** 随单展示的需求行（只读快照，用于让审批人知道这一单要发什么）。 */
    public record RequirementRow(long id, String code, String type, String status, String mrUrl,
                                 BigDecimal estimateDays, BigDecimal actualDays) {
    }

    public record OrderRow(long id, String code, long releaseId, String releaseName, String env,
                           String status, LocalDateTime windowStart, LocalDateTime windowEnd,
                           String artifactVersion, Long ownerId, String ownerName, boolean forcedRelease,
                           LocalDateTime createdAt) {
    }

    public record ForcedRelease(String reason, String by, LocalDateTime at) {
    }

    public record Execution(String result, LocalDateTime startedAt, LocalDateTime endedAt, String note,
                            Long defectId, Integer durationMinutes) {
    }

    /** 准入快照对外形态；{@code stale=true} 表示需求集合/状态已变，页面必须提示重新校验。 */
    public record AdmissionSnapshot(List<AdmissionItem> items, LocalDateTime checkedAt, boolean stale) {
    }

    public record OrderDetail(long id, String code, long releaseId, String releaseName, String env,
                              String status, LocalDateTime windowStart, LocalDateTime windowEnd,
                              String artifactVersion, List<Step> steps, String rollbackPlan, String impact,
                              Long ownerId, String ownerName, boolean forcedRelease,
                              List<String> allowedActions, LocalDateTime createdAt, LocalDateTime updatedAt,
                              List<RequirementRow> requirements, AdmissionSnapshot admission,
                              ForcedRelease forcedReleaseDetail,
                              Execution execution) {
    }

    /** 提交结果：正常路径只回状态（审批实例 id 属实现细节，前端不需要）。 */
    public record SubmitResult(String status) {
    }

    public record FailReason(String reason, long count) {
    }

    /**
     * 发布统计。{@code successRate} 是比率（0–1，四位小数），前端 {@code formatPercent} 两种形态都认；
     * 三个数字都能由单据明细逐单复算：成功率=成功/(成功+失败+回滚)，平均耗时=已完成单据 durationMinutes 均值，
     * 失败原因取执行记录的备注（没填备注时按执行结果归类）。
     */
    public record DeployStats(BigDecimal successRate, BigDecimal avgDurationMinutes, List<FailReason> failReasons,
                              long total, long succeeded, long failed, long rolledBack) {
    }

    /** 准入判定结果（带快照指纹，用于判断详情页展示的结论是否已过期）。 */
    public record AdmissionResult(List<AdmissionItem> items, LocalDateTime checkedAt, String signature) {

        public boolean allPassed() {
            return items != null && !items.isEmpty() && items.stream().allMatch(AdmissionItem::passed);
        }

        public List<String> failedCodes() {
            return AdmissionPolicy.failedCodes(items);
        }
    }
}
