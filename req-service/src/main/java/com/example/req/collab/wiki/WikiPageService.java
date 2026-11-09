package com.example.req.collab.wiki;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.attachment.AttachmentService;
import com.example.req.attachment.AttachmentViews;
import com.example.req.requirement.Rows;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wiki 页面：树、软删/恢复/清理、保存、关联、评论（TECHNICAL §3.2）。
 *
 * <p>本类是唯一写页面与版本的地方：保存与回滚共用 {@link #writeVersion}，因此
 * 「noChange 不产版」「回滚 = 旧内容当新内容保存」「历史 hash 不变」三条只有一份实现。
 * 树深上限 4 在服务端判定（DB 没有 CHECK），超限一律 400 而不是静默夹断。</p>
 *
 * <p>可见性：详情走 {@link WikiVisibility}；树/回收站/关联列表在 SQL 里拼同一谓词，
 * 不允许各出口自写 {@code domain_id}。</p>
 */
@Service
public class WikiPageService {

    private static final int MAX_DEPTH = 4;
    private static final int TRASH_DAYS = 30;
    private static final List<String> BIZ_TYPES = List.of("REQUIREMENT", "DEFECT", "RELEASE");

    private final NamedParameterJdbcTemplate jdbc;
    private final ScopedJdbc scoped;
    private final WikiVisibility visibility;
    private final WikiSpaceService spaceService;
    private final AuditService audit;
    private final AttachmentService attachments;

    public WikiPageService(NamedParameterJdbcTemplate jdbc,
                           ScopedJdbc scoped,
                           WikiVisibility visibility,
                           WikiSpaceService spaceService,
                           AuditService audit,
                           AttachmentService attachments) {
        this.jdbc = jdbc;
        this.scoped = scoped;
        this.visibility = visibility;
        this.spaceService = spaceService;
        this.audit = audit;
        this.attachments = attachments;
    }

    @Transactional
    public WikiViews.PageDetail create(WikiViews.CreateCmd cmd) {
        if (cmd == null || cmd.title() == null || cmd.title().isBlank()) {
            throw new ApiException(400, "WIKI_TITLE_REQUIRED", "页面标题不能为空");
        }
        String title = cmd.title().trim();
        if (title.length() > 200) {
            throw new ApiException(400, "WIKI_TITLE_TOO_LONG", "标题不能超过 200 字");
        }
        Map<String, Object> space = spaceService.requireEditable(cmd.spaceId());
        long spaceId = Rows.toLong(space.get("id"), 0L);
        long tenantId = TenantContext.require().tenantId();
        long userId = TenantContext.require().userId();

        long depth = 1L;
        if (cmd.parentId() != null) {
            Map<String, Object> parent = requirePageRow(cmd.parentId(), tenantId, false);
            if (Rows.toLong(parent.get("space_id"), 0L) != spaceId) {
                throw new ApiException(400, "WIKI_PARENT_SPACE_MISMATCH", "父页面不在同一空间");
            }
            depth = Rows.toLong(parent.get("depth"), 1L) + 1;
            if (depth > MAX_DEPTH) {
                throw new ApiException(400, "WIKI_DEPTH_EXCEEDED", "Wiki 树最多 4 层");
            }
        }
        assertSiblingFree(spaceId, cmd.parentId(), title, null, tenantId);

        String content = cmd.contentMd() == null ? "" : cmd.contentMd();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("spaceId", spaceId)
                .addValue("parentId", cmd.parentId())
                .addValue("title", title)
                .addValue("content", content)
                .addValue("depth", depth)
                .addValue("sortNo", nextSortNo(spaceId, cmd.parentId(), tenantId))
                .addValue("userId", userId);
        try {
            jdbc.update("""
                    INSERT INTO wiki_page (tenant_id, space_id, parent_id, title, content_md, status,
                                           depth, sort_no, current_version, editor_id)
                    VALUES (:tenantId, :spaceId, :parentId, :title, :content, 'PUBLISHED',
                            :depth, :sortNo, 1, :userId)
                    """, params);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "WIKI_PAGE_EXISTS", "同级已有同名页面");
        }
        long pageId = lastId();
        insertVersion(tenantId, pageId, 1, title, content, userId);
        audit.record("WIKI_PAGE_CREATE", "WIKI_PAGE", pageId,
                Map.of("spaceId", spaceId, "title", title, "depth", depth));
        return detail(pageId);
    }

    public WikiViews.PageDetail detail(long pageId) {
        visibility.assertVisiblePageIncludingDeleted(pageId);
        long tenantId = TenantContext.require().tenantId();
        Map<String, Object> row = requirePageRow(pageId, tenantId, true);
        long spaceId = Rows.toLong(row.get("space_id"), 0L);
        Map<String, Object> space = spaceService.requireVisible(spaceId);
        boolean deleted = "DELETED".equals(Rows.toStr(row.get("status")));

        List<WikiViews.Link> links = links(pageId);
        List<WikiViews.Attachment> files = deleted ? List.of() : attachmentsOf(pageId);
        LocalDateTime deletedAt = Rows.toDateTime(row.get("deleted_at"));
        Integer remainingDays = deleted && deletedAt != null
                ? (int) Math.max(0, TRASH_DAYS - ChronoUnit.DAYS.between(deletedAt, LocalDateTime.now()))
                : null;
        boolean canEdit = !deleted && spaceService.canEdit(space);
        int currentVersion = (int) Rows.toLong(row.get("current_version"), 1L);
        String hash = currentHash(pageId, tenantId, currentVersion);

        WikiViews.Page page = new WikiViews.Page(
                pageId,
                spaceId,
                Rows.toStr(row.get("title")),
                Rows.toStr(row.get("content_md")),
                currentVersion,
                canEdit,
                Rows.toStr(space.get("visibility")),
                Rows.toDateTime(row.get("updated_at")),
                Rows.toStr(row.get("editor_name")),
                deletedAt,
                remainingDays,
                hash,
                links,
                files);
        return new WikiViews.PageDetail(pageId, spaceId, page.title(), currentVersion,
                page, breadcrumb(row), links, files);
    }

    public List<WikiViews.TreeNode> tree(long spaceId) {
        spaceService.requireVisible(spaceId);
        DataScope scope = TenantContext.require();
        String sql = """
                SELECT p.id, p.parent_id, p.title, p.current_version, p.depth
                  FROM wiki_page p
                  JOIN wiki_space s ON s.id = p.space_id
                 WHERE p.space_id = :spaceId AND p.status <> 'DELETED'
                   AND /*scope:p*/%s
                 ORDER BY p.sort_no, p.id
                """.formatted(WikiVisibility.predicate(scope, "s"));
        List<Map<String, Object>> rows = scoped.list(sql, Map.of("spaceId", spaceId));
        Map<Long, List<Map<String, Object>>> byParent = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long parent = Rows.toLong(row.get("parent_id"));
            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(row);
        }
        return buildNodes(byParent, null);
    }

    @Transactional
    public void move(long pageId, Long parentId) {
        Map<String, Object> page = editablePage(pageId);
        long tenantId = TenantContext.require().tenantId();
        long spaceId = Rows.toLong(page.get("space_id"), 0L);
        long oldDepth = Rows.toLong(page.get("depth"), 1L);
        long newDepth = 1L;
        if (parentId != null) {
            if (parentId == pageId) {
                throw new ApiException(409, "WIKI_CYCLE", "不能把页面移动到自身之下");
            }
            Map<String, Object> parent = requirePageRow(parentId, tenantId, false);
            if (Rows.toLong(parent.get("space_id"), 0L) != spaceId) {
                throw new ApiException(400, "WIKI_PARENT_SPACE_MISMATCH", "父页面不在同一空间");
            }
            if (isDescendant(pageId, parentId, tenantId)) {
                throw new ApiException(409, "WIKI_CYCLE", "不能把页面移动到自己的子孙之下");
            }
            newDepth = Rows.toLong(parent.get("depth"), 1L) + 1;
        }
        List<Map<String, Object>> subtree = subtree(pageId, tenantId, false);
        long maxDepth = subtree.stream().mapToLong(r -> Rows.toLong(r.get("depth"), 1L)).max().orElse(oldDepth);
        if (newDepth + (maxDepth - oldDepth) > MAX_DEPTH) {
            throw new ApiException(400, "WIKI_DEPTH_EXCEEDED", "Wiki 树最多 4 层");
        }
        assertSiblingFree(spaceId, parentId, Rows.toStr(page.get("title")), pageId, tenantId);
        long delta = newDepth - oldDepth;
        jdbc.update("UPDATE wiki_page SET parent_id = :parentId, depth = :depth, updated_at = NOW(3)"
                + " WHERE id = :id AND tenant_id = :tenantId", new MapSqlParameterSource()
                .addValue("parentId", parentId)
                .addValue("depth", newDepth)
                .addValue("id", pageId)
                .addValue("tenantId", tenantId));
        if (delta != 0) {
            for (Map<String, Object> node : subtree) {
                if (Rows.toLong(node.get("id"), 0L) == pageId) {
                    continue;
                }
                jdbc.update("UPDATE wiki_page SET depth = :depth WHERE id = :id AND tenant_id = :tenantId",
                        Map.of("depth", Rows.toLong(node.get("depth"), 1L) + delta,
                                "id", Rows.toLong(node.get("id"), 0L),
                                "tenantId", tenantId));
            }
        }
        audit.record("WIKI_PAGE_MOVE", "WIKI_PAGE", pageId,
                Map.of("parentId", parentId == null ? "null" : parentId, "depth", newDepth));
    }

    @Transactional
    public void delete(long pageId) {
        Map<String, Object> page = editablePage(pageId);
        long tenantId = TenantContext.require().tenantId();
        List<Map<String, Object>> subtree = subtree(pageId, tenantId, false);
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> node : subtree) {
            jdbc.update("""
                    UPDATE wiki_page
                       SET status = 'DELETED', deleted_at = :deletedAt, deleted_root_id = :rootId,
                           updated_at = NOW(3)
                     WHERE id = :id AND tenant_id = :tenantId
                    """, new MapSqlParameterSource()
                    .addValue("deletedAt", now)
                    .addValue("rootId", pageId)
                    .addValue("id", Rows.toLong(node.get("id"), 0L))
                    .addValue("tenantId", tenantId));
        }
        audit.record("WIKI_PAGE_DELETE", "WIKI_PAGE", pageId,
                Map.of("title", Rows.toStr(page.get("title")), "subtreeSize", subtree.size()));
    }

    public List<WikiViews.TrashItem> trash(Long spaceId) {
        DataScope scope = TenantContext.require();
        if (spaceId != null) {
            spaceService.requireVisible(spaceId);
        }
        String spaceFilter = spaceId == null ? "" : " AND p.space_id = :spaceId";
        String sql = """
                SELECT p.id, p.title, p.space_id, p.deleted_at, p.deleted_root_id,
                       u.name AS deleted_by_name
                  FROM wiki_page p
                  JOIN wiki_space s ON s.id = p.space_id
                  LEFT JOIN sys_user u ON u.id = p.editor_id
                 WHERE p.status = 'DELETED' AND p.deleted_root_id = p.id
                   AND /*scope:p*/%s%s
                 ORDER BY p.deleted_at DESC, p.id DESC
                """.formatted(WikiVisibility.predicate(scope, "s"), spaceFilter);
        Map<String, Object> params = new HashMap<>();
        if (spaceId != null) {
            params.put("spaceId", spaceId);
        }
        return scoped.list(sql, params).stream().map(row -> {
            LocalDateTime deletedAt = Rows.toDateTime(row.get("deleted_at"));
            int remaining = deletedAt == null ? 0
                    : (int) Math.max(0, TRASH_DAYS - ChronoUnit.DAYS.between(deletedAt, LocalDateTime.now()));
            return new WikiViews.TrashItem(
                    Rows.toLong(row.get("id"), 0L),
                    Rows.toStr(row.get("title")),
                    Rows.toLong(row.get("space_id"), 0L),
                    deletedAt,
                    Rows.toStr(row.get("deleted_by_name")),
                    remaining,
                    Rows.toLong(row.get("deleted_root_id")));
        }).toList();
    }

    @Transactional
    public void restore(long pageId) {
        visibility.assertVisiblePageIncludingDeleted(pageId);
        long tenantId = TenantContext.require().tenantId();
        Map<String, Object> page = requirePageRow(pageId, tenantId, true);
        if (!"DELETED".equals(Rows.toStr(page.get("status")))) {
            throw new ApiException(400, "WIKI_NOT_DELETED", "页面不在回收站");
        }
        long spaceId = Rows.toLong(page.get("space_id"), 0L);
        spaceService.requireEditable(spaceId);
        LocalDateTime deletedAt = Rows.toDateTime(page.get("deleted_at"));
        if (deletedAt != null && ChronoUnit.DAYS.between(deletedAt, LocalDateTime.now()) > TRASH_DAYS) {
            throw new ApiException(409, "WIKI_RESTORE_EXPIRED", "超过 30 天的页面不可恢复");
        }
        List<Map<String, Object>> subtree = subtree(pageId, tenantId, true);
        long oldDepth = Rows.toLong(page.get("depth"), 1L);
        Long originalParent = Rows.toLong(page.get("parent_id"));
        Long newParent = originalParent;
        if (newParent != null) {
            Map<String, Object> parent = findPageRow(newParent, tenantId);
            if (parent == null || "DELETED".equals(Rows.toStr(parent.get("status")))) {
                // 原父仍在回收站（或已被清理）→ 挂回根级，否则恢复后仍不可见。
                newParent = null;
            }
        }
        long newDepth = 1L;
        if (newParent != null) {
            Map<String, Object> parent = findPageRow(newParent, tenantId);
            newDepth = Rows.toLong(parent.get("depth"), 1L) + 1;
        }
        long maxDepth = subtree.stream().mapToLong(r -> Rows.toLong(r.get("depth"), 1L)).max().orElse(oldDepth);
        if (newDepth + (maxDepth - oldDepth) > MAX_DEPTH) {
            throw new ApiException(400, "WIKI_DEPTH_EXCEEDED", "恢复后树深超过 4 层");
        }
        assertSiblingFree(spaceId, newParent, Rows.toStr(page.get("title")), pageId, tenantId);
        long delta = newDepth - oldDepth;
        for (Map<String, Object> node : subtree) {
            long id = Rows.toLong(node.get("id"), 0L);
            long depth = Rows.toLong(node.get("depth"), 1L) + delta;
            jdbc.update("""
                    UPDATE wiki_page
                       SET status = 'PUBLISHED', deleted_at = NULL, deleted_root_id = NULL,
                           depth = :depth, updated_at = NOW(3)
                     WHERE id = :id AND tenant_id = :tenantId
                    """, Map.of("depth", depth, "id", id, "tenantId", tenantId));
        }
        jdbc.update("UPDATE wiki_page SET parent_id = :parentId WHERE id = :id AND tenant_id = :tenantId",
                new MapSqlParameterSource()
                        .addValue("parentId", newParent)
                        .addValue("id", pageId)
                        .addValue("tenantId", tenantId));
        audit.record("WIKI_PAGE_RESTORE", "WIKI_PAGE", pageId,
                Map.of("title", Rows.toStr(page.get("title")), "subtreeSize", subtree.size()));
    }

    @Transactional
    public void purge(long pageId) {
        if (!TenantContext.can("WIKI_ADMIN")) {
            throw new ApiException(403, "WIKI_FORBIDDEN", "需要 Wiki 管理员权限");
        }
        visibility.assertVisiblePageIncludingDeleted(pageId);
        long tenantId = TenantContext.require().tenantId();
        Map<String, Object> page = requirePageRow(pageId, tenantId, true);
        if (!"DELETED".equals(Rows.toStr(page.get("status")))) {
            throw new ApiException(400, "WIKI_NOT_DELETED", "只能清理回收站中的页面");
        }
        List<Map<String, Object>> subtree = subtree(pageId, tenantId, true);
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> node : subtree) {
            ids.add(Rows.toLong(node.get("id"), 0L));
        }
        if (ids.isEmpty()) {
            ids.add(pageId);
        }
        audit.record("WIKI_PURGE", "WIKI_PAGE", pageId,
                Map.of("title", Rows.toStr(page.get("title")), "subtreeSize", ids.size()));
        Map<String, Object> params = Map.of("ids", ids, "tenantId", tenantId);
        jdbc.update("DELETE FROM wiki_page_version WHERE page_id IN (:ids) AND tenant_id = :tenantId", params);
        jdbc.update("DELETE FROM wiki_comment WHERE page_id IN (:ids) AND tenant_id = :tenantId", params);
        jdbc.update("DELETE FROM wiki_page_link WHERE page_id IN (:ids) AND tenant_id = :tenantId", params);
        jdbc.update("DELETE FROM wiki_page WHERE id IN (:ids) AND tenant_id = :tenantId", params);
    }

    @Transactional
    public WikiViews.SaveResult save(long pageId, WikiViews.SaveCmd cmd) {
        if (cmd == null) {
            throw new ApiException(400, "WIKI_SAVE_EMPTY", "保存内容不能为空");
        }
        visibility.assertVisiblePage(pageId);
        editablePage(pageId);
        long tenantId = TenantContext.require().tenantId();
        Map<String, Object> locked = lockPage(pageId, tenantId);
        if (locked == null) {
            throw new ApiException(404, "WIKI_PAGE_NOT_FOUND", "页面不存在");
        }
        String title = cmd.title() == null || cmd.title().isBlank()
                ? Rows.toStr(locked.get("title")) : cmd.title().trim();
        if (title.length() > 200) {
            throw new ApiException(400, "WIKI_TITLE_TOO_LONG", "标题不能超过 200 字");
        }
        String content = cmd.contentMd() == null ? "" : cmd.contentMd();
        long current = Rows.toLong(locked.get("current_version"), 1L);
        if (cmd.baseVersion() != null && cmd.baseVersion() != current) {
            throw new ApiException(409, "WIKI_EDIT_CONFLICT", "页面已被他人修改，请刷新后重试");
        }
        String newHash = sha256(title + content);
        String oldHash = currentHash(pageId, tenantId, (int) current);
        if (newHash.equals(oldHash) && title.equals(Rows.toStr(locked.get("title")))) {
            return new WikiViews.SaveResult(pageId, true, (int) current, title, (int) current);
        }
        if (!title.equals(Rows.toStr(locked.get("title")))) {
            assertSiblingFree(Rows.toLong(locked.get("space_id"), 0L),
                    Rows.toLong(locked.get("parent_id")), title, pageId, tenantId);
        }
        int versionNo = (int) current + 1;
        apply(tenantId, pageId, versionNo, title, content);
        audit.record("WIKI_PAGE_UPDATE", "WIKI_PAGE", pageId,
                Map.of("versionNo", versionNo, "title", title));
        return new WikiViews.SaveResult(pageId, false, versionNo, title, versionNo);
    }

    /** 回滚：读旧版本 → 走同一保存路径生成 N+1；历史版本行不动。 */
    @Transactional
    public WikiViews.SaveResult rollbackTo(long pageId, int versionNo, String title, String content) {
        visibility.assertVisiblePage(pageId);
        editablePage(pageId);
        long tenantId = TenantContext.require().tenantId();
        Map<String, Object> locked = lockPage(pageId, tenantId);
        if (locked == null) {
            throw new ApiException(404, "WIKI_PAGE_NOT_FOUND", "页面不存在");
        }
        long current = Rows.toLong(locked.get("current_version"), 1L);
        String safeTitle = title == null || title.isBlank() ? Rows.toStr(locked.get("title")) : title.trim();
        String safeContent = content == null ? "" : content;
        if (!safeTitle.equals(Rows.toStr(locked.get("title")))) {
            assertSiblingFree(Rows.toLong(locked.get("space_id"), 0L),
                    Rows.toLong(locked.get("parent_id")), safeTitle, pageId, tenantId);
        }
        int next = (int) current + 1;
        apply(tenantId, pageId, next, safeTitle, safeContent);
        audit.record("WIKI_ROLLBACK", "WIKI_PAGE", pageId,
                Map.of("fromVersion", current, "toVersion", versionNo, "newVersion", next));
        return new WikiViews.SaveResult(pageId, false, next, safeTitle, next);
    }

    public List<WikiViews.Link> links(long pageId) {
        long tenantId = TenantContext.require().tenantId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, biz_type, biz_id FROM wiki_page_link
                 WHERE page_id = :pageId AND tenant_id = :tenantId ORDER BY id
                """, Map.of("pageId", pageId, "tenantId", tenantId));
        Map<String, Map<Long, String>> titles = new HashMap<>();
        for (String type : BIZ_TYPES) {
            List<Long> ids = rows.stream()
                    .filter(r -> type.equals(Rows.toStr(r.get("biz_type"))))
                    .map(r -> Rows.toLong(r.get("biz_id"), 0L)).distinct().toList();
            if (!ids.isEmpty()) {
                titles.put(type, titles(type, ids, tenantId));
            }
        }
        return rows.stream().map(row -> {
            String type = Rows.toStr(row.get("biz_type"));
            long bizId = Rows.toLong(row.get("biz_id"), 0L);
            return new WikiViews.Link(Rows.toLong(row.get("id"), 0L), type, bizId,
                    titles.getOrDefault(type, Map.of()).get(bizId));
        }).toList();
    }

    public List<WikiViews.SearchHit> listLinks(String bizType, Long bizId) {
        if (!BIZ_TYPES.contains(bizType) || bizId == null) {
            throw new ApiException(400, "WIKI_BIZ_TYPE_INVALID", "非法关联类型");
        }
        DataScope scope = TenantContext.require();
        String sql = """
                SELECT DISTINCT p.id AS page_id, p.title, p.updated_at, s.name AS space_name
                  FROM wiki_page_link l
                  JOIN wiki_page p ON p.id = l.page_id
                  JOIN wiki_space s ON s.id = p.space_id
                 WHERE l.biz_type = :bizType AND l.biz_id = :bizId
                   AND p.status <> 'DELETED' AND /*scope:p*/%s
                 ORDER BY p.updated_at DESC, p.id DESC
                """.formatted(WikiVisibility.predicate(scope, "s"));
        return scoped.list(sql, Map.of("bizType", bizType, "bizId", bizId)).stream()
                .map(row -> new WikiViews.SearchHit(
                        Rows.toLong(row.get("page_id"), 0L),
                        Rows.toStr(row.get("title")),
                        null,
                        Rows.toStr(row.get("space_name")),
                        Rows.toDateTime(row.get("updated_at"))))
                .toList();
    }

    @Transactional
    public WikiViews.Link addLink(long pageId, WikiViews.LinkCmd cmd) {
        visibility.assertVisiblePage(pageId);
        editablePage(pageId);
        if (cmd == null || cmd.bizType() == null || !BIZ_TYPES.contains(cmd.bizType()) || cmd.bizId() == null) {
            throw new ApiException(400, "WIKI_BIZ_TYPE_INVALID", "非法关联类型");
        }
        long tenantId = TenantContext.require().tenantId();
        String title = bizTitle(cmd.bizType(), cmd.bizId(), tenantId);
        if (title == null) {
            throw new ApiException(404, "WIKI_BIZ_NOT_FOUND", "关联对象不存在");
        }
        try {
            jdbc.update("""
                    INSERT INTO wiki_page_link (tenant_id, page_id, biz_type, biz_id)
                    VALUES (:tenantId, :pageId, :bizType, :bizId)
                    """, Map.of("tenantId", tenantId, "pageId", pageId,
                    "bizType", cmd.bizType(), "bizId", cmd.bizId()));
        } catch (DuplicateKeyException ignored) {
            // 幂等：同一对重复关联不报错。
        }
        audit.record("WIKI_PAGE_LINK", "WIKI_PAGE", pageId,
                Map.of("bizType", cmd.bizType(), "bizId", cmd.bizId()));
        return new WikiViews.Link(0L, cmd.bizType(), cmd.bizId(), title);
    }

    @Transactional
    public void removeLink(long pageId, WikiViews.LinkCmd cmd) {
        visibility.assertVisiblePage(pageId);
        editablePage(pageId);
        if (cmd == null || cmd.bizType() == null || cmd.bizId() == null) {
            throw new ApiException(400, "WIKI_BIZ_TYPE_INVALID", "非法关联类型");
        }
        long tenantId = TenantContext.require().tenantId();
        jdbc.update("""
                DELETE FROM wiki_page_link
                 WHERE page_id = :pageId AND biz_type = :bizType AND biz_id = :bizId AND tenant_id = :tenantId
                """, Map.of("pageId", pageId, "bizType", cmd.bizType(), "bizId", cmd.bizId(),
                "tenantId", tenantId));
        audit.record("WIKI_PAGE_UNLINK", "WIKI_PAGE", pageId,
                Map.of("bizType", cmd.bizType(), "bizId", cmd.bizId()));
    }

    public List<WikiViews.Comment> comments(long pageId) {
        visibility.assertVisiblePageIncludingDeleted(pageId);
        long tenantId = TenantContext.require().tenantId();
        return jdbc.queryForList("""
                SELECT c.id, c.user_id, c.content, c.created_at, u.name AS user_name
                  FROM wiki_comment c LEFT JOIN sys_user u ON u.id = c.user_id
                 WHERE c.page_id = :pageId AND c.tenant_id = :tenantId
                 ORDER BY c.created_at, c.id
                """, Map.of("pageId", pageId, "tenantId", tenantId)).stream()
                .map(row -> new WikiViews.Comment(
                        Rows.toLong(row.get("id"), 0L),
                        Rows.toLong(row.get("user_id")),
                        Rows.toStr(row.get("user_name")),
                        Rows.toStr(row.get("content")),
                        Rows.toDateTime(row.get("created_at"))))
                .toList();
    }

    @Transactional
    public WikiViews.Comment addComment(long pageId, WikiViews.CommentCmd cmd) {
        visibility.assertVisiblePage(pageId);
        if (cmd == null || cmd.content() == null || cmd.content().isBlank()) {
            throw new ApiException(400, "WIKI_COMMENT_EMPTY", "评论不能为空");
        }
        if (cmd.content().length() > 2000) {
            throw new ApiException(400, "WIKI_COMMENT_TOO_LONG", "评论不能超过 2000 字");
        }
        long tenantId = TenantContext.require().tenantId();
        long userId = TenantContext.require().userId();
        jdbc.update("""
                INSERT INTO wiki_comment (tenant_id, page_id, user_id, content)
                VALUES (:tenantId, :pageId, :userId, :content)
                """, Map.of("tenantId", tenantId, "pageId", pageId,
                "userId", userId, "content", cmd.content().trim()));
        return new WikiViews.Comment(lastId(), userId, TenantContext.username(),
                cmd.content().trim(), LocalDateTime.now());
    }

    // ---- internals ----

    private void apply(long tenantId, long pageId, int versionNo, String title, String content) {
        insertVersion(tenantId, pageId, versionNo, title, content, TenantContext.require().userId());
        jdbc.update("""
                UPDATE wiki_page
                   SET title = :title, content_md = :content, current_version = :versionNo,
                       editor_id = :userId, updated_at = NOW(3)
                 WHERE id = :id AND tenant_id = :tenantId
                """, new MapSqlParameterSource()
                .addValue("title", title)
                .addValue("content", content)
                .addValue("versionNo", versionNo)
                .addValue("userId", TenantContext.require().userId())
                .addValue("id", pageId)
                .addValue("tenantId", tenantId));
    }

    private void insertVersion(long tenantId, long pageId, int versionNo, String title,
                               String content, long editorId) {
        jdbc.update("""
                INSERT INTO wiki_page_version (tenant_id, page_id, version_no, title, content_md,
                                               editor_id, content_hash)
                VALUES (:tenantId, :pageId, :versionNo, :title, :content, :editorId, :hash)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("pageId", pageId)
                .addValue("versionNo", versionNo)
                .addValue("title", title)
                .addValue("content", content)
                .addValue("editorId", editorId)
                .addValue("hash", sha256(title + content)));
    }

    private Map<String, Object> editablePage(long pageId) {
        visibility.assertVisiblePage(pageId);
        Map<String, Object> page = requirePageRow(pageId, TenantContext.require().tenantId(), false);
        if (page == null) {
            throw new ApiException(404, "WIKI_PAGE_NOT_FOUND", "页面不存在");
        }
        spaceService.requireEditable(Rows.toLong(page.get("space_id"), 0L));
        return page;
    }

    private Map<String, Object> lockPage(long pageId, long tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, space_id, parent_id, title, current_version, status
                  FROM wiki_page
                 WHERE id = :id AND tenant_id = :tenantId AND status <> 'DELETED'
                 FOR UPDATE
                """, Map.of("id", pageId, "tenantId", tenantId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> requirePageRow(long pageId, long tenantId, boolean includeDeleted) {
        String status = includeDeleted ? "" : " AND p.status <> 'DELETED'";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.*, u.name AS editor_name
                  FROM wiki_page p LEFT JOIN sys_user u ON u.id = p.editor_id
                 WHERE p.id = :id AND p.tenant_id = :tenantId%s
                """.formatted(status), Map.of("id", pageId, "tenantId", tenantId));
        if (rows.isEmpty()) {
            throw new ApiException(404, "WIKI_PAGE_NOT_FOUND", "页面不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> findPageRow(long pageId, long tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.*, u.name AS editor_name
                  FROM wiki_page p LEFT JOIN sys_user u ON u.id = p.editor_id
                 WHERE p.id = :id AND p.tenant_id = :tenantId
                """, Map.of("id", pageId, "tenantId", tenantId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<WikiViews.Breadcrumb> breadcrumb(Map<String, Object> page) {
        long tenantId = TenantContext.require().tenantId();
        List<WikiViews.Breadcrumb> out = new ArrayList<>();
        Long cursor = Rows.toLong(page.get("id"));
        int guard = 0;
        while (cursor != null && guard++ < 8) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, parent_id, title FROM wiki_page WHERE id = :id AND tenant_id = :tenantId",
                    Map.of("id", cursor, "tenantId", tenantId));
            if (rows.isEmpty()) {
                break;
            }
            Map<String, Object> row = rows.get(0);
            out.add(0, new WikiViews.Breadcrumb(Rows.toLong(row.get("id"), 0L), Rows.toStr(row.get("title"))));
            cursor = Rows.toLong(row.get("parent_id"));
        }
        return out;
    }

    private List<WikiViews.TreeNode> buildNodes(Map<Long, List<Map<String, Object>>> byParent, Long parentId) {
        List<Map<String, Object>> rows = byParent.getOrDefault(parentId, List.of());
        List<WikiViews.TreeNode> nodes = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            long id = Rows.toLong(row.get("id"), 0L);
            List<WikiViews.TreeNode> children = buildNodes(byParent, id);
            nodes.add(new WikiViews.TreeNode(
                    id,
                    Rows.toLong(row.get("parent_id")),
                    Rows.toStr(row.get("title")),
                    (int) Rows.toLong(row.get("current_version"), 1L),
                    (int) Rows.toLong(row.get("depth"), 1L),
                    !children.isEmpty(),
                    children));
        }
        return nodes;
    }

    /** 含自身的子树（BFS，最多 4 层）；{@code includeDeleted} 控制是否跨过已删子节点。 */
    private List<Map<String, Object>> subtree(long rootId, long tenantId, boolean includeDeleted) {
        List<Map<String, Object>> out = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootId);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 2000) {
            List<Long> level = new ArrayList<>();
            while (!queue.isEmpty()) {
                level.add(queue.poll());
            }
            for (Long id : level) {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT id, parent_id, depth, status FROM wiki_page
                         WHERE id = :id AND tenant_id = :tenantId
                        """, Map.of("id", id, "tenantId", tenantId));
                if (rows.isEmpty()) {
                    continue;
                }
                Map<String, Object> row = rows.get(0);
                out.add(row);
                String status = Rows.toStr(row.get("status"));
                if (!includeDeleted && "DELETED".equals(status)) {
                    continue;
                }
                String childFilter = includeDeleted ? "" : " AND status <> 'DELETED'";
                List<Long> children = jdbc.queryForList("""
                        SELECT id FROM wiki_page
                         WHERE parent_id = :id AND tenant_id = :tenantId%s
                        """.formatted(childFilter),
                        Map.of("id", id, "tenantId", tenantId), Long.class);
                queue.addAll(children);
            }
        }
        return out;
    }

    private boolean isDescendant(long ancestorId, long candidateId, long tenantId) {
        Long cursor = candidateId;
        int guard = 0;
        while (cursor != null && guard++ < 8) {
            if (cursor == ancestorId) {
                return true;
            }
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT parent_id FROM wiki_page WHERE id = :id AND tenant_id = :tenantId",
                    Map.of("id", cursor, "tenantId", tenantId));
            cursor = rows.isEmpty() ? null : Rows.toLong(rows.get(0).get("parent_id"));
        }
        return false;
    }

    private void assertSiblingFree(long spaceId, Long parentId, String title,
                                   Long excludeId, long tenantId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("spaceId", spaceId)
                .addValue("parentId", parentId)
                .addValue("title", title)
                .addValue("tenantId", tenantId);
        String exclude = "";
        if (excludeId != null) {
            exclude = " AND id <> :excludeId";
            params.addValue("excludeId", excludeId);
        }
        Long found = jdbc.query("""
                SELECT id FROM wiki_page
                 WHERE space_id = :spaceId AND tenant_id = :tenantId
                   AND status <> 'DELETED' AND title = :title
                   AND ((parent_id IS NULL AND :parentId IS NULL) OR parent_id = :parentId)%s
                 LIMIT 1
                """.formatted(exclude), params, rs -> rs.next() ? rs.getLong(1) : null);
        if (found != null) {
            throw new ApiException(409, "WIKI_PAGE_EXISTS", "同级已有同名页面");
        }
    }

    private int nextSortNo(long spaceId, Long parentId, long tenantId) {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sort_no), 0) FROM wiki_page
                 WHERE space_id = :spaceId AND tenant_id = :tenantId
                   AND ((parent_id IS NULL AND :parentId IS NULL) OR parent_id = :parentId)
                """, new MapSqlParameterSource()
                .addValue("spaceId", spaceId)
                .addValue("tenantId", tenantId)
                .addValue("parentId", parentId), Integer.class);
        return (max == null ? 0 : max) + 10;
    }

    private String currentHash(long pageId, long tenantId, int versionNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT content_hash FROM wiki_page_version
                 WHERE page_id = :pageId AND tenant_id = :tenantId AND version_no = :versionNo
                """, Map.of("pageId", pageId, "tenantId", tenantId, "versionNo", versionNo));
        return rows.isEmpty() ? null : Rows.toStr(rows.get(0).get("content_hash"));
    }

    private List<WikiViews.Attachment> attachmentsOf(long pageId) {
        List<AttachmentViews.AttachmentView> rows =
                attachments.list(AttachmentService.BIZ_WIKI_PAGE, pageId);
        List<WikiViews.Attachment> out = new ArrayList<>(rows.size());
        for (AttachmentViews.AttachmentView row : rows) {
            out.add(new WikiViews.Attachment(row.id(), row.fileName(), row.size(),
                    "/attachments/" + row.id() + "/download"));
        }
        return out;
    }

    private Map<Long, String> titles(String bizType, List<Long> ids, long tenantId) {
        String table;
        String column;
        switch (bizType) {
            case "REQUIREMENT" -> {
                table = "requirement";
                column = "title";
            }
            case "DEFECT" -> {
                table = "defect";
                column = "title";
            }
            case "RELEASE" -> {
                table = "`release`";
                column = "name";
            }
            default -> {
                return Map.of();
            }
        }
        Map<Long, String> out = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, " + column + " AS title FROM " + table
                        + " WHERE id IN (:ids) AND tenant_id = :tenantId",
                Map.of("ids", ids, "tenantId", tenantId))) {
            out.put(Rows.toLong(row.get("id"), 0L), Rows.toStr(row.get("title")));
        }
        return out;
    }

    private String bizTitle(String bizType, long bizId, long tenantId) {
        return titles(bizType, List.of(bizId), tenantId).get(bizId);
    }

    private long lastId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
        return id == null ? 0L : id;
    }

    static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
