package com.example.req.collab.wiki;

import java.util.List;
import java.util.Map;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.requirement.Rows;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wiki 空间（TECHNICAL §3.2）。可见范围三态是空间的属性，页面/附件/搜索都从空间投影。
 *
 * <p>读路径全部经 {@link ScopedJdbc} + {@link WikiVisibility}；建/改额外要求
 * {@code WIKI_ADMIN}（路由表已拦，服务层再判一次是为了避免以后有人换前缀时漏掉）。</p>
 */
@Service
public class WikiSpaceService {

    private static final List<String> VISIBILITIES = List.of("INHERIT", "DOMAIN", "VENDOR");
    private static final List<String> EDITOR_POLICIES = List.of("DOMAIN_EDITORS", "ADMIN_ONLY");

    private final NamedParameterJdbcTemplate jdbc;
    private final ScopedJdbc scoped;
    private final AuditService audit;
    private final WikiVisibility visibility;

    public WikiSpaceService(NamedParameterJdbcTemplate jdbc,
                            ScopedJdbc scoped,
                            AuditService audit,
                            WikiVisibility visibility) {
        this.jdbc = jdbc;
        this.scoped = scoped;
        this.audit = audit;
        this.visibility = visibility;
    }

    public List<WikiViews.SpaceView> list() {
        DataScope scope = TenantContext.require();
        String sql = """
                SELECT s.id, s.name, s.visibility, s.domain_id, s.vendor_id, s.editor_policy,
                       s.owner_id, u.name AS owner_name,
                       (SELECT COUNT(*) FROM wiki_page p2
                         WHERE p2.space_id = s.id AND p2.status <> 'DELETED') AS page_count
                  FROM wiki_space s
                  LEFT JOIN sys_user u ON u.id = s.owner_id
                 WHERE s.status = 'ACTIVE' AND /*scope:s*/%s
                 ORDER BY s.id
                """.formatted(WikiVisibility.predicate(scope, "s"));
        return scoped.list(sql, Map.of()).stream().map(this::toView).toList();
    }

    /** 供树/页面服务复用的「可见 + ACTIVE」空间行；不可见 → 404。 */
    public Map<String, Object> requireVisible(long spaceId) {
        DataScope scope = TenantContext.require();
        String sql = "SELECT s.* FROM wiki_space s WHERE s.id = :id AND s.status = 'ACTIVE'"
                + " AND /*scope:s*/" + WikiVisibility.predicate(scope, "s");
        Map<String, Object> row = scoped.one(sql, Map.of("id", spaceId));
        if (row == null) {
            throw new ApiException(404, "WIKI_SPACE_NOT_FOUND", "空间不存在或不可见");
        }
        return row;
    }

    /** 空间存在且可写；{@code ADMIN_ONLY} 空间只有管理员能写（服务层判定，不靠前端置灰）。 */
    public Map<String, Object> requireEditable(long spaceId) {
        Map<String, Object> row = requireVisible(spaceId);
        if (!canEdit(row)) {
            throw new ApiException(403, "WIKI_FORBIDDEN", "该空间仅管理员可编辑");
        }
        return row;
    }

    public boolean canEdit(Map<String, Object> space) {
        if (TenantContext.can("WIKI_ADMIN")) {
            return true;
        }
        if (!TenantContext.can("WIKI_EDIT")) {
            return false;
        }
        return !"ADMIN_ONLY".equals(Rows.toStr(space.get("editor_policy")));
    }

    @Transactional
    public WikiViews.SpaceView create(WikiViews.SpaceCmd cmd) {
        if (cmd == null || cmd.name() == null || cmd.name().isBlank()) {
            throw new ApiException(400, "WIKI_SPACE_NAME_REQUIRED", "空间名称不能为空");
        }
        DataScope scope = TenantContext.require();
        long domainId = resolveDomainId(cmd.domainId(), scope);
        String visibility = normalize(cmd.visibility(), "DOMAIN", VISIBILITIES, "WIKI_VISIBILITY_INVALID");
        String editorPolicy = normalize(cmd.editorPolicy(), "DOMAIN_EDITORS", EDITOR_POLICIES,
                "WIKI_EDITOR_POLICY_INVALID");
        Long vendorId = vendorFor(visibility, cmd.vendorId(), scope);
        long ownerId = cmd.ownerId() == null ? scope.userId() : cmd.ownerId();
        long userId = scope.userId();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", scope.tenantId())
                .addValue("domainId", domainId)
                .addValue("vendorId", vendorId)
                .addValue("name", cmd.name().trim())
                .addValue("visibility", visibility)
                .addValue("editorPolicy", editorPolicy)
                .addValue("ownerId", ownerId)
                .addValue("userId", userId);
        try {
            jdbc.update("""
                    INSERT INTO wiki_space (tenant_id, domain_id, vendor_id, name, visibility,
                                            editor_policy, owner_id, status, created_by, updated_by)
                    VALUES (:tenantId, :domainId, :vendorId, :name, :visibility,
                            :editorPolicy, :ownerId, 'ACTIVE', :userId, :userId)
                    """, params);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "WIKI_SPACE_EXISTS", "同域下空间名称已存在");
        }
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
        audit.record("WIKI_SPACE_CREATE", "WIKI_SPACE", id,
                Map.of("name", cmd.name().trim(), "visibility", visibility, "domainId", domainId));
        return toView(requireVisible(id.longValue()));
    }

    @Transactional
    public WikiViews.SpaceView update(long id, WikiViews.SpaceCmd cmd) {
        if (!TenantContext.can("WIKI_ADMIN")) {
            throw new ApiException(403, "WIKI_FORBIDDEN", "需要 Wiki 管理员权限");
        }
        Map<String, Object> current = requireVisible(id);
        DataScope scope = TenantContext.require();
        String name = cmd != null && cmd.name() != null ? cmd.name().trim() : Rows.toStr(current.get("name"));
        if (name == null || name.isBlank()) {
            throw new ApiException(400, "WIKI_SPACE_NAME_REQUIRED", "空间名称不能为空");
        }
        String visibility = normalize(
                cmd != null && cmd.visibility() != null ? cmd.visibility() : Rows.toStr(current.get("visibility")),
                "DOMAIN", VISIBILITIES, "WIKI_VISIBILITY_INVALID");
        String editorPolicy = normalize(
                cmd != null && cmd.editorPolicy() != null ? cmd.editorPolicy() : Rows.toStr(current.get("editor_policy")),
                "DOMAIN_EDITORS", EDITOR_POLICIES, "WIKI_EDITOR_POLICY_INVALID");
        long domainId = cmd != null && cmd.domainId() != null
                ? resolveDomainId(cmd.domainId(), scope)
                : Rows.toLong(current.get("domain_id"), 0L);
        Long vendorId = vendorFor(visibility,
                cmd != null && cmd.vendorId() != null ? cmd.vendorId() : Rows.toLong(current.get("vendor_id")),
                scope);
        Long ownerId = cmd != null && cmd.ownerId() != null
                ? cmd.ownerId() : Rows.toLong(current.get("owner_id"));

        try {
            jdbc.update("""
                    UPDATE wiki_space
                       SET name = :name, visibility = :visibility, editor_policy = :editorPolicy,
                           domain_id = :domainId, vendor_id = :vendorId, owner_id = :ownerId,
                           updated_by = :userId, updated_at = NOW(3)
                     WHERE id = :id AND tenant_id = :tenantId
                    """, new MapSqlParameterSource()
                    .addValue("name", name)
                    .addValue("visibility", visibility)
                    .addValue("editorPolicy", editorPolicy)
                    .addValue("domainId", domainId)
                    .addValue("vendorId", vendorId)
                    .addValue("ownerId", ownerId)
                    .addValue("userId", scope.userId())
                    .addValue("id", id)
                    .addValue("tenantId", scope.tenantId()));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "WIKI_SPACE_EXISTS", "同域下空间名称已存在");
        }
        audit.record("WIKI_SPACE_UPDATE", "WIKI_SPACE", id,
                Map.of("name", name, "visibility", visibility, "domainId", domainId));
        return toView(requireVisible(id));
    }

    private Long vendorFor(String visibility, Long requested, DataScope scope) {
        if (!"VENDOR".equals(visibility)) {
            return null;
        }
        Long vendorId = requested != null ? requested : scope.vendorId();
        if (vendorId == null) {
            throw new ApiException(400, "WIKI_VENDOR_REQUIRED", "VENDOR 空间必须指定厂商");
        }
        return vendorId;
    }

    private long resolveDomainId(Long requested, DataScope scope) {
        if (requested != null) {
            if (!scope.canSeeDomain(requested)) {
                throw new ApiException(403, "WIKI_FORBIDDEN", "无权在该域创建空间");
            }
            Long exists = jdbc.query("SELECT id FROM biz_domain WHERE id = :id AND tenant_id = :tenantId"
                            + " AND enabled = 1", Map.of("id", requested, "tenantId", scope.tenantId()),
                    rs -> rs.next() ? rs.getLong(1) : null);
            if (exists == null) {
                throw new ApiException(400, "WIKI_DOMAIN_INVALID", "域不存在或已停用");
            }
            return requested;
        }
        if (scope.domainIds().size() == 1) {
            return scope.domainIds().iterator().next();
        }
        if (scope.domainIds().size() > 1) {
            throw new ApiException(400, "WIKI_DOMAIN_REQUIRED", "请指定 domainId（当前账号可见多个域）");
        }
        List<Long> domains = jdbc.queryForList("SELECT id FROM biz_domain WHERE tenant_id = :tenantId"
                        + " AND enabled = 1 ORDER BY id", Map.of("tenantId", scope.tenantId()), Long.class);
        if (domains.size() == 1) {
            return domains.get(0);
        }
        throw new ApiException(400, "WIKI_DOMAIN_REQUIRED", "请指定 domainId");
    }

    private static String normalize(String value, String fallback, List<String> allowed, String code) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new ApiException(400, code, "非法取值：" + value);
        }
        return normalized;
    }

    private WikiViews.SpaceView toView(Map<String, Object> row) {
        String ownerName = Rows.toStr(row.get("owner_name"));
        return new WikiViews.SpaceView(
                Rows.toLong(row.get("id"), 0L),
                Rows.toStr(row.get("name")),
                Rows.toStr(row.get("visibility")),
                Rows.toLong(row.get("domain_id"), 0L),
                Rows.toLong(row.get("vendor_id")),
                Rows.toStr(row.get("editor_policy")),
                Rows.toLong(row.get("owner_id")),
                ownerName,
                Rows.toLong(row.get("page_count"), 0L),
                canEdit(row));
    }
}
