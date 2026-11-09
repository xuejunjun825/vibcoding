package com.example.req.integration.gitlab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.platform.common.scope.ScopedJdbc;
import com.example.req.requirement.RequirementService;
import com.example.req.requirement.Rows;

/**
 * MR 行级评论的落库与读取（AC-12 / TECHNICAL §3.5）。
 *
 * <p>三条约束都在这一个类里收口：</p>
 * <ol>
 *   <li><b>幂等</b>：唯一键 {@code uk_note(tenant_id, mr_sync_id, gitlab_note_id)} +
 *       {@code ON DUPLICATE KEY UPDATE}。同一 note 同步两次，行数不变（AC-12 负向用例），
 *       解决状态/正文会跟着 GitLab 走。</li>
 *   <li><b>脱敏</b>：{@link #mask(String)} 在<b>落库前</b>执行。存明文、展示时再打码等于
 *       把凭据留在库里——AC-12 要求凭据串不得出现在响应里，最省事的证明方式就是库里没有。</li>
 *   <li><b>可见性</b>：列表 SQL 以 {@code requirement} 为锚（SQL 里的 scope 标记指向 {@code r}），
 *       跨域/跨厂商的需求直接被 ScopeSql 谓词挡掉，再叠加 {@code requireVisible} 的 404。</li>
 * </ol>
 */
@Service
public class MrCommentService {

    /** 列宽即上限：超长正文截断而不是让 INSERT 失败（一条评论不该毁掉整次刷新）。 */
    public static final int MAX_BODY_LENGTH = 2000;

    /** {@code token=xxx} / {@code password: xxx} 这类赋值式泄漏。 */
    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
            "(?i)\\b(token|password|secret|authorization)\\b\\s*[=:]\\s*\\S+");

    /** GitLab 个人访问令牌本体（不依赖键名出现）。 */
    private static final Pattern GL_PAT = Pattern.compile("glpat-[A-Za-z0-9_-]{16,}");

    private static final String MASK = "***";

    private static final String SELECT_COLUMNS = """
            c.id, c.mr_sync_id, c.gitlab_note_id, c.author, c.body,
            c.file_path, c.line_no, c.resolved, c.note_created_at
            """;

    private static final String UPSERT = """
            INSERT INTO mr_comment
                (tenant_id, mr_sync_id, gitlab_note_id, author, body,
                 file_path, line_no, resolved, note_created_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?) AS incoming
            ON DUPLICATE KEY UPDATE
                author = incoming.author,
                body = incoming.body,
                file_path = incoming.file_path,
                line_no = incoming.line_no,
                resolved = incoming.resolved
            """;

    private final JdbcTemplate jdbc;
    private final ScopedJdbc scoped;
    private final RequirementService requirementService;

    public MrCommentService(JdbcTemplate jdbc, ScopedJdbc scoped, RequirementService requirementService) {
        this.jdbc = jdbc;
        this.scoped = scoped;
        this.requirementService = requirementService;
    }

    /**
     * 按需求分页读行级评论（键集分页：{@code cursor} = 上一页最后一条 id）。
     *
     * @throws com.example.platform.common.error.ApiException 需求不可见时 404
     */
    public List<MrCommentViews.CommentRow> list(long requirementId, String cursor, int limit) {
        requirementService.requireVisible(requirementId);
        Long cursorId = parseCursor(cursor);
        int size = clampLimit(limit);
        Map<String, Object> params = new HashMap<>();
        params.put("requirementId", requirementId);
        params.put("limit", size);
        String cursorClause = "";
        if (cursorId != null) {
            cursorClause = " AND c.id < :cursor";
            params.put("cursor", cursorId);
        }
        String sql = "SELECT " + SELECT_COLUMNS + """
                  FROM mr_comment c
                  JOIN mr_sync m ON m.id = c.mr_sync_id AND m.tenant_id = c.tenant_id
                  JOIN requirement r ON r.id = m.requirement_id
                 WHERE /*scope:r*/ AND m.requirement_id = :requirementId
                """ + cursorClause + """
                 ORDER BY c.id DESC
                 LIMIT :limit
                """;
        List<Map<String, Object>> rows = scoped.list(sql, params);
        List<MrCommentViews.CommentRow> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(toRow(row));
        }
        return items;
    }

    /** {@link #list} 的分页包装：多取一条判断是否还有下一页。 */
    public MrCommentViews.CommentPage page(long requirementId, String cursor, int limit) {
        int size = clampLimit(limit);
        List<MrCommentViews.CommentRow> rows = list(requirementId, cursor, size + 1);
        boolean hasMore = rows.size() > size;
        List<MrCommentViews.CommentRow> items = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore && !items.isEmpty()
                ? String.valueOf(items.get(items.size() - 1).id())
                : null;
        return new MrCommentViews.CommentPage(items, next, items.size(), hasMore);
    }

    /**
     * 需求卡片上的最近 N 条评论（AC-12：预览 3 条）。已经过 {@code requireVisible} 的调用方
     * 传入 tenantId，这里不再重复鉴权——但 SQL 仍带 tenant_id，避免跨租户串读。
     */
    public List<MrCommentViews.CommentRow> preview(long tenantId, Long mrSyncId, int limit) {
        if (mrSyncId == null) {
            return List.of();
        }
        int size = clampLimit(limit);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, mr_sync_id, gitlab_note_id, author, body,
                       file_path, line_no, resolved, note_created_at
                  FROM mr_comment
                 WHERE tenant_id = ? AND mr_sync_id = ?
                 ORDER BY id DESC
                 LIMIT ?
                """, tenantId, mrSyncId, size);
        List<MrCommentViews.CommentRow> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(toRow(row));
        }
        return items;
    }

    /**
     * 幂等写入一次刷新拿到的全部行级评论。
     *
     * @return 受影响行数（新增 + 更新；仅供日志，调用方用 {@link #knownNoteIds} 区分）
     */
    public int upsertAll(long tenantId, long mrSyncId, List<MrViews.MrNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (MrViews.MrNote note : notes) {
            if (note == null || note.id() <= 0 || !note.lineLevel()) {
                continue;
            }
            affected += jdbc.update(UPSERT,
                    tenantId,
                    mrSyncId,
                    note.id(),
                    clip(note.author(), 100),
                    mask(note.body()),
                    clip(note.filePath(), 500),
                    note.lineNo(),
                    note.resolved() ? 1 : 0,
                    note.createdAt());
        }
        return affected;
    }

    /** 库中已有该 MR 的哪些 note：刷新时用它把「新增」与「更新」分开报给前端。 */
    public Set<Long> knownNoteIds(long tenantId, long mrSyncId) {
        Set<Long> known = new LinkedHashSet<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT gitlab_note_id FROM mr_comment WHERE tenant_id = ? AND mr_sync_id = ?
                """, tenantId, mrSyncId)) {
            Long id = Rows.toLong(row.get("gitlab_note_id"));
            if (id != null) {
                known.add(id);
            }
        }
        return known;
    }

    /** 一次刷新的净变化：新增几条、更新几条（同一 note 重复同步不重复计数）。 */
    public record Change(int added, int updated) {
    }

    /**
     * 刷新时落库并如实报告「新增 / 更新」——前端据此显示「本次新增 2 条」。
     *
     * <p>判定用「库中已有签名 vs 本次签名」而不是 MySQL 的 affected-rows：后者对
     * 「值没变」返回 0、对「值变了」返回 2，把新增和更新混在一个数字里，
     * 页面就无法说清这次刷新到底改变了什么。</p>
     */
    public Change syncAll(long tenantId, long mrSyncId, List<MrViews.MrNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return new Change(0, 0);
        }
        Map<Long, String> before = signatures(tenantId, mrSyncId);
        int added = 0;
        int updated = 0;
        for (MrViews.MrNote note : notes) {
            if (note == null || note.id() <= 0 || !note.lineLevel()) {
                continue;
            }
            String previous = before.get(note.id());
            if (previous == null) {
                added++;
            } else if (!previous.equals(signature(note))) {
                updated++;
            }
        }
        upsertAll(tenantId, mrSyncId, notes);
        return new Change(added, updated);
    }

    private Map<Long, String> signatures(long tenantId, long mrSyncId) {
        Map<Long, String> result = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT gitlab_note_id, body, resolved, file_path, line_no
                  FROM mr_comment
                 WHERE tenant_id = ? AND mr_sync_id = ?
                """, tenantId, mrSyncId)) {
            Long id = Rows.toLong(row.get("gitlab_note_id"));
            if (id != null) {
                result.put(id, text(row, "body") + '|' + text(row, "resolved") + '|'
                        + text(row, "file_path") + '|' + text(row, "line_no"));
            }
        }
        return result;
    }

    private static String text(Map<String, Object> row, String key) {
        String value = Rows.str(row, key);
        return value == null ? "" : value;
    }

    /** 与 {@link #signatures} 同口径（正文按脱敏后的值比较，避免同一正文每次都被判成更新）。 */
    private static String signature(MrViews.MrNote note) {
        return mask(note.body()) + '|' + (note.resolved() ? "1" : "0") + '|'
                + clip(note.filePath(), 500) + '|' + (note.lineNo() == null ? "" : note.lineNo());
    }

    /** 该 MR 已落库的行级评论条数（普通评论不在这里，见 {@code commentsCount}）。 */
    public int lineLevelCount(long tenantId, long mrSyncId) {        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mr_comment WHERE tenant_id = ? AND mr_sync_id = ?
                """, Long.class, tenantId, mrSyncId);
        return count == null ? 0 : count.intValue();
    }

    /**
     * 凭据脱敏：先打令牌本体，再打赋值式键值对。顺序重要——先处理 {@code glpat-…}
     * 才能让 {@code token=glpat-xxx} 整段变成 {@code token=***} 而不是留下半截。
     */
    public static String mask(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        String masked = GL_PAT.matcher(body).replaceAll(MASK);
        masked = ASSIGNED_SECRET.matcher(masked).replaceAll("$1=" + MASK);
        return clip(masked, MAX_BODY_LENGTH);
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return MrCommentViews.DEFAULT_LIMIT;
        }
        return Math.min(limit, MrCommentViews.MAX_LIMIT);
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(cursor.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static MrCommentViews.CommentRow toRow(Map<String, Object> row) {
        return new MrCommentViews.CommentRow(
                Rows.toLong(Rows.object(row, "id"), 0L),
                Rows.toLong(Rows.object(row, "mr_sync_id"), 0L),
                Rows.toLong(Rows.object(row, "gitlab_note_id"), 0L),
                Rows.str(row, "author"),
                Rows.str(row, "body"),
                Rows.str(row, "file_path"),
                intOrNull(Rows.object(row, "line_no")),
                Rows.toLong(Rows.object(row, "resolved"), 0L) != 0L,
                Rows.toDateTime(Rows.object(row, "note_created_at")));
    }

    private static Integer intOrNull(Object value) {
        Long parsed = Rows.toLong(value);
        return parsed == null ? null : parsed.intValue();
    }
}
