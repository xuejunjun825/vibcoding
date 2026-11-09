package com.example.req.collab.wiki;

import java.util.List;
import java.util.Map;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.requirement.Rows;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 不可变版本、行级 diff 与回滚（TECHNICAL §3.2）。
 *
 * <p>版本表只追加：本类没有任何 update/delete 语句，回滚也是「把旧内容当新内容再保存一次」，
 * 因此历史版本的 {@code content_hash} 永远不变——AC-4 的断言依赖这一点。</p>
 *
 * <p>回滚复用 {@link WikiPageService} 的保存路径（同一把行锁、同一套 hash/noChange 判定），
 * 只是审计动作换成 {@code WIKI_ROLLBACK}。</p>
 */
@Service
public class WikiVersionService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ScopedJdbc scoped;
    private final WikiVisibility visibility;
    private final WikiPageService pageService;

    public WikiVersionService(NamedParameterJdbcTemplate jdbc,
                              ScopedJdbc scoped,
                              WikiVisibility visibility,
                              WikiPageService pageService) {
        this.jdbc = jdbc;
        this.scoped = scoped;
        this.visibility = visibility;
        this.pageService = pageService;
    }

    public List<WikiViews.Version> versions(long pageId) {
        visibility.assertVisiblePageIncludingDeleted(pageId);
        DataScope scope = TenantContext.require();
        String sql = """
                SELECT v.version_no, v.title, v.editor_id, v.content_hash, v.created_at, u.name AS editor_name,
                       p.current_version
                  FROM wiki_page_version v
                  JOIN wiki_page p ON p.id = v.page_id
                  LEFT JOIN sys_user u ON u.id = v.editor_id
                 WHERE v.page_id = :pageId AND v.tenant_id = :tenantId
                   AND /*scope:p*/%s
                 ORDER BY v.version_no DESC
                """.formatted(WikiVisibility.predicate(scope, "s"));
        // 版本行本身没有空间列，可见性由页面所属空间决定；上面的谓词需要 s，补一个 JOIN。
        String withSpace = """
                SELECT v.version_no, v.title, v.editor_id, v.content_hash, v.created_at, u.name AS editor_name,
                       p.current_version
                  FROM wiki_page_version v
                  JOIN wiki_page p ON p.id = v.page_id
                  JOIN wiki_space s ON s.id = p.space_id
                  LEFT JOIN sys_user u ON u.id = v.editor_id
                 WHERE v.page_id = :pageId AND v.tenant_id = :tenantId
                   AND /*scope:p*/%s
                 ORDER BY v.version_no DESC
                """.formatted(WikiVisibility.predicate(scope, "s"));
        List<Map<String, Object>> rows = scoped.list(withSpace, Map.of(
                "pageId", pageId,
                "tenantId", TenantContext.require().tenantId()));
        long current = rows.isEmpty() ? 0L : Rows.toLong(rows.get(0).get("current_version"), 0L);
        return rows.stream().map(row -> new WikiViews.Version(
                (int) Rows.toLong(row.get("version_no"), 0L),
                Rows.toStr(row.get("title")),
                Rows.toDateTime(row.get("created_at")),
                Rows.toLong(row.get("editor_id")),
                Rows.toStr(row.get("editor_name")),
                null,
                Rows.toStr(row.get("content_hash")),
                Rows.toLong(row.get("version_no"), 0L) == current)).toList();
    }

    public WikiViews.Diff diff(long pageId, int from, int to) {
        visibility.assertVisiblePageIncludingDeleted(pageId);
        long tenantId = TenantContext.require().tenantId();
        String a = content(pageId, tenantId, from);
        String b = content(pageId, tenantId, to);
        return WikiDiff.compute(a, b);
    }

    public WikiViews.SaveResult rollback(long pageId, int versionNo) {
        visibility.assertVisiblePage(pageId);
        Map<String, Object> version = version(pageId, TenantContext.require().tenantId(), versionNo);
        if (version == null) {
            throw new ApiException(404, "WIKI_VERSION_NOT_FOUND", "版本不存在");
        }
        return pageService.rollbackTo(pageId, versionNo,
                Rows.toStr(version.get("title")), contentOf(version));
    }

    private String content(long pageId, long tenantId, int versionNo) {
        Map<String, Object> row = version(pageId, tenantId, versionNo);
        if (row == null) {
            throw new ApiException(404, "WIKI_VERSION_NOT_FOUND", "版本不存在");
        }
        return contentOf(row);
    }

    private Map<String, Object> version(long pageId, long tenantId, int versionNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT version_no, title, content_md
                  FROM wiki_page_version
                 WHERE page_id = :pageId AND tenant_id = :tenantId AND version_no = :versionNo
                """, Map.of("pageId", pageId, "tenantId", tenantId, "versionNo", versionNo));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String contentOf(Map<String, Object> row) {
        String content = Rows.toStr(row.get("content_md"));
        return content == null ? "" : content;
    }
}
