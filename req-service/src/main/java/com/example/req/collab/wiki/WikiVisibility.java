package com.example.req.collab.wiki;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import org.springframework.stereotype.Component;

/**
 * Wiki 可见性的唯一谓词（TECHNICAL §3.2）。
 *
 * <p>列表、树、详情、搜索、回收站、关联六个出口都从这里取 SQL 片段；任一出口自己写
 * {@code domain_id = ...} 都可能与另一出口不一致，最终表现为「列表看不到、详情能打开」的越权。</p>
 *
 * <p>三态语义：</p>
 * <ul>
 *   <li>{@code INHERIT} → 与 {@code ScopeSql} 对 {@code wiki_space} 的标准谓词同口径
 *       （域 + 厂商）；租户级调用方不过滤。</li>
 *   <li>{@code DOMAIN} → 空间域在调用方域集合内。</li>
 *   <li>{@code VENDOR} → 空间厂商等于调用方厂商且非空。</li>
 * </ul>
 *
 * <p>片段里用字面量 id 而不是 {@code :__scopeDomains}：谓词会被拼进没有
 * {@link ScopedJdbc} 注入参数的 count/子查询，绑定参数缺失会在运行时炸；id 来自
 * {@link DataScope}（Long 集合），拼字符串无注入面。</p>
 */
@Component
public class WikiVisibility {

    private final ScopedJdbc scoped;

    public WikiVisibility(ScopedJdbc scoped) {
        this.scoped = scoped;
    }

    /** 返回 {@code ""} 或 {@code " AND (...)"}，可直接拼在 WHERE 子句尾部。 */
    public static String predicate(DataScope scope, String spaceAlias) {
        if (scope == null || scope.tier() == ScopeTier.TENANT) {
            return "";
        }
        String alias = safeAlias(spaceAlias);
        List<String> branches = new ArrayList<>();

        List<String> inherit = new ArrayList<>();
        Set<Long> domains = scope.domainIds();
        if (!domains.isEmpty()) {
            inherit.add(alias + ".domain_id IN (" + joinIds(domains) + ")");
        } else if (scope.tier() == ScopeTier.DOMAIN) {
            // 域级调用方没有任何域：沿用 ScopeSql 的 fail-closed（1=0），绝不放大成「全部」。
            inherit.add("1 = 0");
        }
        if (scope.tier() == ScopeTier.VENDOR && scope.vendorId() != null) {
            inherit.add(alias + ".vendor_id = " + scope.vendorId());
        }
        branches.add("(" + (inherit.isEmpty() ? "1 = 1" : String.join(" AND ", inherit)) + ")");
        // INHERIT 分支本身已含域/厂商条件，但还要限定空间的确声明为 INHERIT。
        branches.set(0, "(" + alias + ".visibility = 'INHERIT' AND " + branches.get(0) + ")");

        if (!domains.isEmpty()) {
            branches.add("(" + alias + ".visibility = 'DOMAIN' AND " + alias
                    + ".domain_id IN (" + joinIds(domains) + "))");
        }
        if (scope.vendorId() != null) {
            branches.add("(" + alias + ".visibility = 'VENDOR' AND " + alias
                    + ".vendor_id IS NOT NULL AND " + alias + ".vendor_id = " + scope.vendorId() + ")");
        }
        return " AND (" + String.join(" OR ", branches) + ")";
    }

    /** 空间存在且可见（空间本身必须 ACTIVE）。 */
    public boolean visibleSpace(long spaceId) {
        DataScope scope = TenantContext.require();
        Map<String, Object> params = Map.of("id", spaceId);
        String sql = "SELECT s.id FROM wiki_space s WHERE s.id = :id AND s.status = 'ACTIVE'"
                + " AND /*scope:s*/" + predicate(scope, "s");
        return scoped.one(sql, params) != null;
    }

    public boolean visiblePage(long pageId) {
        return visiblePage(pageId, false);
    }

    /** {@code includeDeleted=true} 供回收站/详情读取；写路径一律 false。 */
    public boolean visiblePage(long pageId, boolean includeDeleted) {
        DataScope scope = TenantContext.require();
        Map<String, Object> params = Map.of("id", pageId);
        String status = includeDeleted ? "" : " AND p.status <> 'DELETED'";
        String sql = "SELECT p.id FROM wiki_page p JOIN wiki_space s ON s.id = p.space_id"
                + " WHERE p.id = :id" + status + " AND /*scope:p*/" + predicate(scope, "s");
        return scoped.one(sql, params) != null;
    }

    /** 不可见与不存在都返回 404：不泄露「这个 id 存在」这一位信息。 */
    public void assertVisiblePage(long pageId) {
        if (!visiblePage(pageId)) {
            throw new ApiException(404, "WIKI_PAGE_NOT_FOUND", "页面不存在或不可见");
        }
    }

    public void assertVisiblePageIncludingDeleted(long pageId) {
        if (!visiblePage(pageId, true)) {
            throw new ApiException(404, "WIKI_PAGE_NOT_FOUND", "页面不存在或不可见");
        }
    }

    private static String safeAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return "s";
        }
        if (!alias.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("bad space alias: " + alias);
        }
        return alias;
    }

    private static String joinIds(java.util.Set<Long> ids) {
        StringBuilder out = new StringBuilder();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(id.longValue());
        }
        return out.length() == 0 ? "NULL" : out.toString();
    }
}
