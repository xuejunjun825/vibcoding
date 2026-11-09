package com.example.req.integration.gitlab;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MR 行级评论的视图与分页契约（AC-12 / TECHNICAL §2.6）。
 *
 * <p>分页用<b>键集</b>（keyset）而不是 OFFSET：{@code cursor} 是上一页最后一条
 * {@code mr_comment.id}，下一页取 {@code id < cursor}。刷新评论时新行会插到更大的 id 上，
 * OFFSET 分页会因此重复/漏条，键集不会。</p>
 */
public final class MrCommentViews {

    /** 每页默认条数（AC-12：默认 50 条 + 加载更多）。 */
    public static final int DEFAULT_LIMIT = 50;
    /** 单页上限：分页参数是外部输入，不能让它变成全表导出。 */
    public static final int MAX_LIMIT = 200;

    private MrCommentViews() {
    }

    /**
     * 一条行级评论。
     *
     * @param filePath 文件路径（{@code null} 表示该 note 的 position 里没有文件信息）
     * @param lineNo   新行行号；GitLab 对整文件评论可能只给 old_line，这里已归一到新行
     * @param resolved 是否已解决（GitLab 的 resolved 或 position 上的 resolvable 状态）
     */
    public record CommentRow(long id,
                             long mrSyncId,
                             long gitlabNoteId,
                             String author,
                             String body,
                             String filePath,
                             Integer lineNo,
                             boolean resolved,
                             LocalDateTime createdAt) {
    }

    /**
     * 一页评论。
     *
     * @param nextCursor 下一页游标；{@code null} 表示没有更多
     */
    public record CommentPage(List<CommentRow> items, String nextCursor, int count, boolean hasMore) {

        public CommentPage {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static CommentPage empty() {
            return new CommentPage(List.of(), null, 0, false);
        }
    }
}
