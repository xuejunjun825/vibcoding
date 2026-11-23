package com.example.req.requirement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.example.req.release.FlowChainViews;
import com.example.req.release.ReleaseLevel;

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

    /**
     * 详情里的「能否拆解」前置信息：只描述类型/状态，不含权限（权限由前端 can('req:split') 另判）。
     * {@link #from} 是 409 与详情共用的唯一映射点，保证 reason 与错误 message 逐字一致。
     */
    public record DecomposeGate(boolean allowed, String reason, String parentStatus, Object required) {
        public static DecomposeGate from(RequirementRules.ParentGate gate, String parentStatus) {
            if (gate == null) {
                return new DecomposeGate(false, null, parentStatus, null);
            }
            return new DecomposeGate(gate.allowed(), gate.message(), parentStatus, gate.required());
        }
    }

    /** 列表/详情共用的主档字段。 */
    public record View(Long id, String code, String type, String status, String priority, String title,
                       String description, Long parentId, String parentCode, Long domainId,
                       Long originOrgId, Long ownerUserId, Long ownerVendorId, Long assigneeVendorId,
                       Long releaseId, BigDecimal estimateDays, BigDecimal actualDays, String mrUrl,
                       LocalDate expectedDeliveryDate, LocalDate planStartDate, LocalDate planEndDate,
                       LocalDateTime closedAt, LocalDateTime updatedAt,
                       Rollup rollup, List<String> allowedActions, Map<String, Object> permissions,
                       DecomposeGate decomposeGate, List<TagView> tags) {

        /*
         * 只有一个构造器，`tags` 是必填参数（r39 Q-1）。
         *
         * 原来还有一个把 tags 默认成 List.of() 的兼容构造器，结果详情路径（RequirementService.toView）
         * 每次都拿到空标签：列表有「大需求」chip、详情没有，用户看到的是两个不同的需求
         * （AC-14 §9.2 要求列表与详情同源）。删掉兼容构造器后，「忘记传标签」从运行期静默丢数据
         * 变成编译错误——新增调用点无法再悄悄丢掉标签。lint L22 静态锁住这条。
         */
    }

    /** 需求标签（r39）：{@code source} 区分人工打标与规则打标，前端据此给出不同样式。 */
    public record TagView(String tag, String source) {
    }

    public record Detail(View view, List<Child> children, List<String> allowedActions,
                         Map<String, Object> permissions,
                         WbsEstimateViews.EstimateView estimate) {
    }

    /** 提交评估 201 响应（{@code instanceId} 供前端直接跳审批单）。 */
    public record EstimateCreated(long instanceId, String status,
                                  WbsEstimateViews.EstimateView estimate) {
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

    /**
     * 版本详情（r30 起还带分级、逐级审批与四段链路）。
     *
     * <p>为什么把 {@code flowChain / approvalLevels / rejected*} 直接挂在这个既有记录上，
     * 而不是另开 {@code GET /releases/{id}/flow}：AC-8 要求「一次 GET 版本详情拿全」，
     * 另开接口会让前端出现两个 loading 态，也让「详情与链路来自不同时刻」成为可能
     * （面板显示 PASS、审批却已因链路过期被驳回）。</p>
     *
     * <p>{@code activeInstanceId} 只指向<b>在途</b>实例；驳回后它为 null，但
     * {@code approvalLevels} 仍是最后一次实例的投影（AC-3 要看到“被哪一级驳回”）。</p>
     */
    public record ReleaseDetail(ReleaseView release, List<View> requirements,
                                List<ParentGroup> groups, ReleaseStats stats, List<View> riskItems,
                                List<MilestoneView> milestones,
                                Long activeInstanceId,
                                List<FlowChainViews.ApprovalLevel> approvalLevels,
                                FlowChainViews.FlowChain flowChain,
                                String rejectedNodeKey, String rejectedNodeName, Long rejectedBy,
                                LocalDateTime rejectedAt, String rejectReason) {
    }

    /**
     * 版本行：等级与两个必填项跟着行一起返回（列表/详情/PATCH 三处同源）。
     *
     * <p>{@code releaseLevelLabel} 由后端给中文（AC-5「中文标签由后端统一」），
     * 前端不再维护等级字典；{@code emergencyReason/previousDeployOrderCode} 恒返回，
     * 由前端按等级决定是否展示，避免「字段缺失」与「值为空」两种空在组件里要写两套分支。</p>
     */
    public record ReleaseView(Long id, String code, String name, Long domainId, String status,
                              LocalDate startDate, LocalDate endDate, LocalDate plannedReleaseDate,
                              String gateMode, LocalDateTime lockedAt, LocalDateTime releasedAt,
                              List<String> allowedActions,
                              String releaseLevel, String releaseLevelLabel,
                              String emergencyReason, String previousDeployOrderCode) {

        /** 老调用点兼容：不带等级字段时按常规发布展示。 */
        public ReleaseView(Long id, String code, String name, Long domainId, String status,
                           LocalDate startDate, LocalDate endDate, LocalDate plannedReleaseDate,
                           String gateMode, LocalDateTime lockedAt, LocalDateTime releasedAt,
                           List<String> allowedActions) {
            this(id, code, name, domainId, status, startDate, endDate, plannedReleaseDate, gateMode,
                    lockedAt, releasedAt, allowedActions, ReleaseLevel.NORMAL,
                    ReleaseLevel.label(ReleaseLevel.NORMAL), null, null);
        }
    }

    public record MilestoneView(Long id, String name, LocalDate plannedDate, String status) {
    }

    public record QuickTest(long executionId, String result) {
    }
}
