package com.example.req.integration.gitlab;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * GitLab 集成域对外的视图与载荷。
 *
 * <p>三个事实源各有一个类型：GitLab API 的应答（{@link MrPayload}）、数据库里的
 * {@code mr_sync} 行（{@link MrCard}）、以及 webhook 处理结果（{@link ApplyOutcome}）。
 * 状态字符串只在 {@link #normalizeState(String)} 一处归一，适配器、webhook 与卡片读取
 * 都走它——否则 {@code opened}/{@code OPENED}/{@code Open} 迟早各写各的。</p>
 */
public final class MrViews {

    /** GitLab MR 状态在库里的取值（与 V8 的 mr_sync.state 注释一致）。 */
    public static final String STATE_OPENED = "OPENED";
    public static final String STATE_MERGED = "MERGED";
    public static final String STATE_CLOSED = "CLOSED";
    public static final String STATE_LOCKED = "LOCKED";

    /** 卡片「陈旧」阈值：超过它没有新事件，说明 webhook 可能丢了，前端标黄。 */
    public static final long STALE_MINUTES = 60L;

    private MrViews() {
    }

    /**
     * GitLab 侧的一条 MR（webhook 载荷或 REST 应答，二者共用）。
     *
     * @param state     已归一为 OPENED/MERGED/CLOSED/LOCKED；{@code null} 表示这次没拿到
     *                  GitLab 的有效应答（降级），此时 {@code errorCode} 有值
     * @param rawJson   原始载荷（截断后入库，便于事后对账）
     * @param errorCode 降级原因（TIMEOUT/TRANSPORT_ERROR/HTTP_404 …），正常为 null
     */
    public record MrPayload(String gitlabProject,
                            long mrIid,
                            String mrUrl,
                            String title,
                            String state,
                            String sourceBranch,
                            String targetBranch,
                            Integer approvalsCount,
                            Integer additions,
                            Integer deletions,
                            LocalDateTime lastEventAt,
                            LocalDateTime mergedAt,
                            String rawJson,
                            String errorCode) {

        public boolean degraded() {
            return state == null;
        }

        public boolean merged() {
            return STATE_MERGED.equals(state);
        }

        /** 降级载荷：链接已解析、GitLab 没答上来，只落链接不落状态。 */
        public static MrPayload degraded(String project, long iid, String mrUrl, String errorCode) {
            return new MrPayload(project, iid, mrUrl, null, null, null, null, null, null, null,
                    null, null, null, errorCode);
        }
    }

    /**
     * 需求详情「代码」卡。{@code mrIid}/{@code state} 为空表示只存了链接（URL 解析失败或
     * 首次同步降级），前端据此显示「未结构化」，而不是伪造一个 OPENED。
     */
    public record MrCard(Long id,
                         String gitlabProject,
                         Long mrIid,
                         String mrUrl,
                         String title,
                         String state,
                         String sourceBranch,
                         String targetBranch,
                         Integer approvalsCount,
                         Integer additions,
                         Integer deletions,
                         LocalDateTime lastEventAt,
                         LocalDateTime mergedAt,
                         boolean stale) {

        /** 只有链接、没有结构化事实时的卡片（AC-4.1：不吃掉用户填的 URL）。 */
        public static MrCard linkOnly(String mrUrl) {
            return new MrCard(null, null, null, mrUrl, null, null, null, null, null, null,
                    null, null, null, true);
        }
    }

    /** {@code POST /requirements/{id}/mr-sync} 的请求体。 */
    public record SyncCmd(String mrUrl) {
    }

    /** {@code POST /requirements/{id}/mr/confirm-merge} 的请求体。 */
    public record ConfirmMergeCmd(String note) {
    }

    /** 人工确认合并的结果；{@code advanced=false} 表示需求本来就已经是 MERGED。 */
    public record ConfirmResult(long requirementId, String status, boolean advanced) {
    }

    /** webhook 处理结论（决定 HTTP 200 的 {@code status}/{@code result} 字段）。 */
    public enum ApplyOutcome {
        /** 事件更新了 mr_sync 行（并可能推进了需求）。 */
        APPLIED,
        /** 同时间戳重投，幂等丢弃。 */
        DUPLICATE,
        /** 比水位旧的事件，回退保护丢弃。 */
        OUT_OF_ORDER,
        /** 未知事件 / 未关联需求的 MR，不落业务行。 */
        IGNORED
    }

    /**
     * GitLab 的 MR 状态归一。GitLab 用 snake_case（opened/merged/closed/locked），
     * 库里用大写；未知值按 OPENED 处理（宁可显示「进行中」也不伪造终态）。
     */
    public static String normalizeState(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "MERGED", "MERGE" -> STATE_MERGED;
            case "CLOSED", "CLOSE" -> STATE_CLOSED;
            case "LOCKED" -> STATE_LOCKED;
            case "OPENED", "OPEN", "REOPENED", "UPDATE" -> STATE_OPENED;
            default -> STATE_OPENED;
        };
    }

    /**
     * GitLab 的时间戳（ISO-8601，带 {@code Z}）→ 库里的 {@link LocalDateTime}。
     *
     * <p>必须换算到 JVM 默认时区：实体写入用 {@code LocalDateTime.now()}（本地墙钟），
     * 直接把 {@code 06:00Z} 当 {@code 06:00} 存会让它比刚写入的行「更旧」，
     * 单调水位就会误判成 OUT_OF_ORDER。</p>
     */
    public static LocalDateTime parseIso(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // GitLab 也发过不带偏移的 "2026-11-09 06:00:00 UTC" / 纯本地时间，继续尝试
        }
        try {
            return Instant.parse(text).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // fallthrough
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public static boolean isStale(LocalDateTime lastEventAt) {
        return lastEventAt == null
                || lastEventAt.isBefore(LocalDateTime.now().minusMinutes(STALE_MINUTES));
    }
}
