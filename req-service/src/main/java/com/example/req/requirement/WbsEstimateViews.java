package com.example.req.requirement;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工时评估单的对外视图集合（r26）。字段名即前端 {@code EstimateBlock} 的契约，改一处必须同步
 * {@code web/src/api/types.ts}。
 */
public final class WbsEstimateViews {

    private WbsEstimateViews() {
    }

    /**
     * 详情/审批中心的评估单块。
     *
     * @param status         六态：{@code NONE|PENDING|APPROVED|REJECTED|SUPERSEDED|STALE}
     * @param instanceId     当前单据 id（{@code NONE} 时为 null）
     * @param instanceStatus 单据原始状态（{@code PENDING|APPROVED|REJECTED|SUPERSEDED|WITHDRAWN}）
     * @param proposedDays   单据人天（SUBMIT 快照）
     * @param approvedDays   已通过的人天（仅在通过的/失效的单据上有值）
     * @param note           说明
     * @param reason         结论原因：{@code REJECTED} 是驳回评论；{@code STALE} 是稳定失效码
     *                       （{@code VENDOR_CHANGED|DAYS_MISSING|DAYS_DEVIATION}），供门禁/客户端
     *                       按码判定而不去解析文案
     * @param reasonText     上面的可读版本（人话），STALE 时说明「为什么失效 / 该怎么做」；
     *                       其它状态与 {@code reason} 同值或为 null
     * @param approverId     审批人（在途时为待办归属人，已办时为处理人）
     * @param submittedAt    发起时间
     * @param decidedAt      结论时间
     * @param required       缺什么才能放行，固定 {@value #REQUIRED}
     */
    public record EstimateView(String status, Long instanceId, String instanceStatus,
                               BigDecimal proposedDays, BigDecimal approvedDays, String note,
                               String reason, String reasonText, Long approverId,
                               LocalDateTime submittedAt, LocalDateTime decidedAt,
                               Long vendorId, String vendorName,
                               String required) {

        /** 门要求的产物：一张已通过且未失效的评估单。 */
        public static final String REQUIRED = "ESTIMATE_APPROVED";

        /** 未评估：完整对象而非 null，前端卡片在「无单」时也要渲染。 */
        public static EstimateView none() {
            return new EstimateView(WbsEstimateService.STATUS_NONE, null, null, null, null, null,
                    null, null, null, null, null, null, null, REQUIRED);
        }
    }
}
