package com.example.req.requirement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 需求对外视图（记录型 DTO，Controller 直接序列化）。 */
public final class RequirementViews {

    private RequirementViews() {
    }

    /** 父需求人天/完成度汇总，父与子共用同一算法。 */
    public record Rollup(long childCount, long doneCount, BigDecimal estimateDays,
                         BigDecimal actualDays, int progressPercent) {
        public static final Rollup EMPTY = new Rollup(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    public record Child(long id, String code, String type, String status, String title,
                        Long ownerUserId, BigDecimal estimateDays, BigDecimal actualDays) {
    }

    /** 列表/详情共用的主档字段。 */
    public record View(Long id, String code, String type, String status, String priority, String title,
                       String description, Long parentId, String parentCode, Long domainId,
                       Long originOrgId, Long ownerUserId, Long ownerVendorId, Long assigneeVendorId,
                       Long releaseId, BigDecimal estimateDays, BigDecimal actualDays, String mrUrl,
                       LocalDate expectedDeliveryDate, LocalDate planStartDate, LocalDate planEndDate,
                       LocalDateTime closedAt, LocalDateTime updatedAt,
                       Rollup rollup, List<String> allowedActions, Map<String, Boolean> permissions) {
    }

    public record Detail(View view, List<Child> children, List<String> allowedActions,
                         Map<String, Boolean> permissions) {
    }

    public record Created(long id, String code, String type, String status,
                          List<String> allowedActions) {
    }

    public record TransitionResult(long id, String status, List<String> allowedActions) {
    }

    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    public record ReleaseStats(long total, long done, BigDecimal estimateDays, BigDecimal actualDays) {
    }

    public record ParentGroup(Long parentId, String parentCode, String parentTitle,
                              long childCount, long doneCount) {
    }

    public record ReleaseDetail(ReleaseView release, List<View> requirements,
                                List<ParentGroup> groups, ReleaseStats stats, List<View> riskItems,
                                List<MilestoneView> milestones) {
    }

    public record ReleaseView(Long id, String code, String name, Long domainId, String status,
                              LocalDate startDate, LocalDate endDate, LocalDate plannedReleaseDate,
                              String gateMode, LocalDateTime lockedAt, LocalDateTime releasedAt,
                              List<String> allowedActions) {
    }

    public record MilestoneView(Long id, String name, LocalDate plannedDate, String status) {
    }

    public record QuickTest(long executionId, String result) {
    }
}
