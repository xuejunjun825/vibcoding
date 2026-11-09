package com.example.req.integration.gitlab;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * GitLab 集成域对外的视图与载荷。
 *
 * <p>三个事实源各有一个类型：GitLab API 的应答（{@link MrPayload}）、数据库里的
 * {@code mr_sync} 行（{@link MrCard}）、以及 webhook 处理结果（{@link ApplyOutcome}）。
 * 状态字符串只在 {@link #normalizeState(String)} 一处归一，适配器、webhook 与卡片读取
 * 都走它——否则 {@code opened}/{@code OPENED}/{@code Open} 迟早各写各的。</p>
 *
 * <p>AC-12/AC-19：{@link MrCard} 的既有 13 个字段一个都不改名（老断言依赖字段名），
 * 评审人/评审态/变更文件数/评论数四个字段<b>追加</b>在末尾；{@code comments} 是最近 3 条
 * 行级评论预览，评论区分页仍走 {@code /mr-comments}。</p>
 */
public final class MrViews {

    /** GitLab MR 状态在库里的取值（与 V8 的 mr_sync.state 注释一致）。 */
    public static final String STATE_OPENED = "OPENED";
    public static final String STATE_MERGED = "MERGED";
    public static final String STATE_CLOSED = "CLOSED";
    public static final String STATE_LOCKED = "LOCKED";

    /**
     * 合并方式的三个规范值（AC-10）。规范化的地方只有一处——{@link GitLabAdapter}
     * 把 GitLab 的 {@code merge_method}/{@code squash_option} 折叠成这三个值，
     * 分支策略侧直接引用这里的常量，避免「集成说 SQUASH、策略认 squash」的口径分裂。
     */
    public static final String MERGE_METHOD_MERGE = "MERGE";
    public static final String MERGE_METHOD_SQUASH = "SQUASH";
    public static final String MERGE_METHOD_FF_ONLY = "FF_ONLY";

    /** 评审态（AC-12）：未决 / 已通过 / 要求修改，前端徽标只认这三个值。 */
    public static final String REVIEW_PENDING = "PENDING";
    public static final String REVIEW_APPROVED = "APPROVED";
    public static final String REVIEW_CHANGES_REQUESTED = "CHANGES_REQUESTED";

    /** 卡片「陈旧」阈值：超过它没有新事件，说明 webhook 可能丢了，前端标黄。 */
    public static final long STALE_MINUTES = 60L;

    /** 需求详情里评论预览的条数（完整列表走分页接口）。 */
    public static final int COMMENT_PREVIEW = 3;

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

    /** MR 上的一名评审人；{@code state} 是 GitLab 的评审态原文（unreviewed/reviewed/…）。 */
    public record Reviewer(Long id, String name, String username, String state) {
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
                         boolean stale,
                         List<Reviewer> reviewers,
                         String reviewState,
                         Integer changedFiles,
                         Integer commentsCount,
                         List<MrCommentViews.CommentRow> comments) {

        public MrCard {
            reviewers = reviewers == null ? List.of() : List.copyOf(reviewers);
            comments = comments == null ? List.of() : List.copyOf(comments);
        }

        /** 只有链接、没有结构化事实时的卡片（AC-4.1：不吃掉用户填的 URL）。 */
        public static MrCard linkOnly(String mrUrl) {
            return new MrCard(null, null, null, mrUrl, null, null, null, null, null, null,
                    null, null, null, true, List.of(), null, null, null, List.of());
        }
    }

    /**
     * 一次 MR 读取的完整事实：载荷（state/分支/行数…）+ 评审事实（评审人/评审态/变更文件数）。
     *
     * <p>两者必须来自<b>同一个</b> GET 应答——分两次读会出现「卡片是旧的、评审人是新的」，
     * 刷新又是唯一外呼入口，多打一次 GitLab 只会让超时预算更紧。</p>
     */
    public record MrDetails(MrPayload payload, ReviewInfo review) {
    }

    /** 一次 MR 刷新（唯一外呼）的结果：卡片 + 新增/更新评论计数。 */
    public record RefreshResult(MrCard card, int added, int updated) {
    }

    /** GitLab 项目设置（只读，用于分支策略核验）。 */
    public record ProjectSettings(String mergeMethod,
                                  Integer approvalsBeforeMerge,
                                  Boolean onlyAllowMergeIfPipelineSucceeds) {
    }

    /** GitLab 保护分支；角色名已由适配器映射为 Guest/Reporter/Developer/Maintainer/Owner。 */
    public record ProtectedBranch(String name,
                                  List<String> pushAccessLevels,
                                  List<String> mergeAccessLevels) {

        public ProtectedBranch {
            pushAccessLevels = pushAccessLevels == null ? List.of() : List.copyOf(pushAccessLevels);
            mergeAccessLevels = mergeAccessLevels == null ? List.of() : List.copyOf(mergeAccessLevels);
        }
    }

    /**
     * 一条 MR note。{@code lineLevel=false} 的普通评论不落 {@code mr_comment}
     * （只在 {@code commentsCount} 里计数），因此这里保留标志位而不是提前丢弃。
     */
    public record MrNote(long id,
                         String author,
                         String body,
                         String filePath,
                         Integer lineNo,
                         boolean resolved,
                         LocalDateTime createdAt,
                         boolean lineLevel) {
    }

    /** 从 MR 应答里解析出的评审事实（刷新时一次性落 {@code mr_sync}）。 */
    public record ReviewInfo(List<Reviewer> reviewers,
                             String reviewState,
                             Integer changedFiles,
                             Integer commentsCount) {

        public ReviewInfo {
            reviewers = reviewers == null ? List.of() : List.copyOf(reviewers);
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
     * 评审态归一：GitLab 的 reviewer.state 为 {@code requested_changes} 即要求修改；
     * 有审批（{@code approved_by}/{@code approvalsCount>0}）即通过；其余未决。
     * 未知输入不猜成通过——AC-11 的反面目标同样适用于评审态。
     */
    public static String normalizeReviewState(List<Reviewer> reviewers, Integer approvalsCount) {
        if (reviewers != null) {
            for (Reviewer reviewer : reviewers) {
                String raw = reviewer == null ? null : reviewer.state();
                if (raw != null && "REQUESTED_CHANGES".equals(
                        raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_'))) {
                    return REVIEW_CHANGES_REQUESTED;
                }
            }
        }
        if (approvalsCount != null && approvalsCount > 0) {
            return REVIEW_APPROVED;
        }
        return REVIEW_PENDING;
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
