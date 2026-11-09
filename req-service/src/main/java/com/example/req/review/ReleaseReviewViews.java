package com.example.req.review;

import com.example.platform.contract.quality.GateSnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Go/No-Go 评审的对外形状（TECHNICAL §5.3）。
 *
 * <p>{@link ReviewDetail} 是<b>扁平</b>结构：它继承不了 {@link ReviewRow}（record 不能继承），
 * 所以把 row 的每个字段再列一遍，而不是嵌一个 {@code review} 子对象——前端
 * {@code ReviewDetail extends ReviewRow} 直接读取 {@code detail.status}，嵌套会让它读到 undefined。</p>
 *
 * <p>{@link ReviewPage} 同时给 {@code items} 与 {@code records} 两个键：req 的既有分页形状是
 * {@code items}，而质量域 QA 契约按 {@code records} 读取；同一份数据给两个别名，比让一端改协议便宜，
 * 且两键始终由同一个列表构造，不会漂移。</p>
 */
public final class ReleaseReviewViews {

    private ReleaseReviewViews() {
    }

    /**
     * 创建评审的请求体。{@code meetingAt} 收字符串而不是 {@code LocalDateTime}：前端传的是带时区的
     * ISO 瞬时（{@code 2026-12-01T02:00:00.000Z}），直接用 LocalDateTime 绑定会 400；
     * 服务端按 OffsetDateTime/Instant/LocalDateTime 依次宽松解析后落到本机时区。
     */
    public record CreateReview(
            String meetingAt,
            List<Object> participants,
            String decision,
            String conditions,
            Object risks,
            Object openDefects,
            Long deployOrderId,
            Long reportId,
            Integer reportVersionNo,
            String docPath) {
    }

    /** 确认请求；{@code note} 只进审计，不改评审内容。 */
    public record ConfirmReview(String note) {
    }

    public record ReviewRow(
            long id,
            long releaseId,
            Long deployOrderId,
            Long gateSnapshotId,
            Long reportId,
            Integer reportVersionNo,
            LocalDateTime meetingAt,
            String decision,
            String conditions,
            boolean gateEvaluated,
            String status,
            Long createdBy,
            LocalDateTime createdAt,
            Long confirmedBy,
            LocalDateTime confirmedAt) {
    }

    /**
     * 详情固定输出全部键：契约写的是 {@code gateSnapshot: GateSnapshot|null}，页面据此区分
     * 「门禁未评估」（gateEvaluated=false 且 gateSnapshot=null）与「评估过」，因此不能用全局
     * NON_NULL 的默认序列化把空键吞掉。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReviewDetail(
            long id,
            long releaseId,
            Long deployOrderId,
            Long gateSnapshotId,
            Long reportId,
            Integer reportVersionNo,
            LocalDateTime meetingAt,
            String decision,
            String conditions,
            boolean gateEvaluated,
            String status,
            Long createdBy,
            LocalDateTime createdAt,
            Long confirmedBy,
            LocalDateTime confirmedAt,
            List<Object> participants,
            Object risks,
            Object openDefects,
            String docPath,
            GateSnapshot gateSnapshot) {
    }

    public record ReviewPage(List<ReviewRow> items, List<ReviewRow> records, long total, int page, int size) {
        public static ReviewPage of(List<ReviewRow> rows, long total, int page, int size) {
            return new ReviewPage(rows, rows, total, page, size);
        }
    }
}
