package com.example.req.sys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Organization tree for the current tenant (materialized {@code path}). */
@RestController
@RequestMapping("/admin/orgs")
@Transactional
public class AdminOrgController {

    private final SysDb db;
    private final AuditService audit;

    public AdminOrgController(SysDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    @GetMapping
    public List<SysViews.OrgRow> list() {
        long tenantId = TenantContext.require().tenantId();
        List<Object[]> rows = db.rows("SELECT id, name, parent_id, tenant_id, code, type"
                + " FROM sys_org WHERE tenant_id = ? ORDER BY sort_no, id", tenantId);
        Map<Long, List<SysViews.OrgRow>> childrenOf = new LinkedHashMap<>();
        Map<Long, Object[]> byId = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byId.put(SysDb.asLong(row[0]), row);
        }
        List<SysViews.OrgRow> roots = new ArrayList<>();
        for (Object[] row : rows) {
            long id = SysDb.asLong(row[0]);
            Long parentId = SysDb.asNullableLong(row[2]);
            SysViews.OrgRow node = new SysViews.OrgRow(id, SysDb.asString(row[1]), parentId,
                    SysDb.asLong(row[3]), SysDb.asString(row[4]), SysDb.asString(row[5]),
                    new ArrayList<>());
            if (parentId == null || !byId.containsKey(parentId)) {
                roots.add(node);
            } else {
                childrenOf.computeIfAbsent(parentId, key -> new ArrayList<>()).add(node);
            }
        }
        // attach children in a second pass so a parent always exists
        List<SysViews.OrgRow> flat = new ArrayList<>(roots);
        for (int i = 0; i < flat.size(); i++) {
            SysViews.OrgRow node = flat.get(i);
            List<SysViews.OrgRow> children = childrenOf.getOrDefault(node.id(), List.of());
            node.children().addAll(children);
            flat.addAll(children);
        }
        return roots;
    }

    @PostMapping
    public SysViews.OrgRow create(@RequestBody Map<String, Object> body) {
        long tenantId = TenantContext.require().tenantId();
        String name = required(body, "name");
        Long parentId = longOrNull(body.get("parentId"));
        String code = body.get("code") == null ? null : body.get("code").toString();
        String type = body.get("type") == null ? (parentId == null ? "ROOT" : "DEPT")
                : body.get("type").toString();
        String parentPath = "/";
        if (parentId != null) {
            Object[] parent = db.row("SELECT path FROM sys_org WHERE tenant_id = ? AND id = ?",
                    tenantId, parentId).orElseThrow(() -> new ApiException(404, "NOT_FOUND",
                    "上级组织不存在", Map.of("parentId", parentId)));
            parentPath = SysDb.asString(parent[0]);
        }
        long id = db.insert("INSERT INTO sys_org (tenant_id, parent_id, type, code, name, path,"
                        + " sort_no, status, created_by, updated_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0, 'ACTIVE', ?, ?)",
                tenantId, parentId, type, code, name, parentPath + code, currentUserId(),
                currentUserId());
        db.update("UPDATE sys_org SET path = ? WHERE id = ?", parentPath + id + "/", id);
        return new SysViews.OrgRow(id, name, parentId, tenantId, code, type, List.of());
    }

    @PutMapping
    public SysViews.OrgRow update(@RequestBody Map<String, Object> body) {
        long tenantId = TenantContext.require().tenantId();
        long id = requireId(body);
        Object[] existing = db.row("SELECT name, parent_id, code, type FROM sys_org"
                        + " WHERE tenant_id = ? AND id = ?", tenantId, id)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "组织不存在",
                        Map.of("id", id)));
        String name = body.get("name") == null ? SysDb.asString(existing[0])
                : body.get("name").toString();
        Long parentId = body.containsKey("parentId") ? longOrNull(body.get("parentId"))
                : SysDb.asNullableLong(existing[1]);
        db.update("UPDATE sys_org SET name = ?, parent_id = ?, updated_by = ?"
                + " WHERE tenant_id = ? AND id = ?", name, parentId, currentUserId(), tenantId, id);
        return new SysViews.OrgRow(id, name, parentId, tenantId, SysDb.asString(existing[2]),
                SysDb.asString(existing[3]), List.of());
    }

    /**
     * Deletes a leaf, empty organisation.
     *
     * <p>Both refusals report the number that made them refuse: a child count, or a user count the
     * operator can act on by moving those people first. The next step is reachable from the same
     * screen ({@code POST /admin/orgs/{id}/move-users}), so a 409 is never a dead end.</p>
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        long tenantId = TenantContext.require().tenantId();
        String name = db.row("SELECT name FROM sys_org WHERE tenant_id = ? AND id = ?",
                        tenantId, id).map(row -> SysDb.asString(row[0]))
                .orElseThrow(() -> new ApiException(404, "ORG_NOT_FOUND", "组织不存在",
                        Map.of("id", id)));
        long children = db.count("SELECT COUNT(*) FROM sys_org WHERE tenant_id = ? AND parent_id = ?",
                tenantId, id);
        if (children > 0) {
            throw new ApiException(409, "ORG_HAS_CHILDREN", "组织下还有 " + children + " 个子组织",
                    Map.of("children", children));
        }
        long users = db.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id = ? AND org_id = ?",
                tenantId, id);
        if (users > 0) {
            throw new ApiException(409, "ORG_HAS_USERS", "组织下还有 " + users + " 名人员，请先迁移",
                    Map.of("users", users, "moveEndpoint", "/admin/orgs/" + id + "/move-users"));
        }
        db.update("DELETE FROM sys_org WHERE tenant_id = ? AND id = ?", tenantId, id);
        audit.record("ORG_DELETE", "org", id, Map.of("name", name));
        return Map.of("deleted", true, "id", id);
    }

    /**
     * Moves the selected people out of an organisation in one transaction.
     *
     * <p>All-or-nothing on purpose: a partially moved selection is worse than a refused one,
     * because the operator cannot tell which half landed. One audit entry records the pair of
     * organisations and the exact people who moved.</p>
     */
    @PostMapping("/{id}/move-users")
    public Map<String, Object> moveUsers(@PathVariable long id,
                                         @RequestBody Map<String, Object> body) {
        long tenantId = TenantContext.require().tenantId();
        db.row("SELECT id FROM sys_org WHERE tenant_id = ? AND id = ?", tenantId, id)
                .orElseThrow(() -> new ApiException(404, "ORG_NOT_FOUND", "组织不存在",
                        Map.of("id", id)));
        Long targetId = longOrNull(body.get("targetOrgId"));
        if (targetId == null) {
            throw new ApiException(400, "INVALID_TARGET", "缺少目标组织", null);
        }
        db.row("SELECT id FROM sys_org WHERE tenant_id = ? AND id = ?", tenantId, targetId)
                .orElseThrow(() -> new ApiException(404, "ORG_NOT_FOUND", "目标组织不存在",
                        Map.of("targetOrgId", targetId)));
        if (targetId == id) {
            throw new ApiException(400, "INVALID_TARGET", "目标组织与来源组织相同", null);
        }
        List<Long> userIds = userIds(body);
        if (userIds.isEmpty()) {
            throw new ApiException(400, "EMPTY_SELECTION", "请选择要迁移的人员", null);
        }
        List<Long> missing = new ArrayList<>();
        for (Long userId : userIds) {
            boolean exists = db.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id = ?"
                    + " AND id = ? AND org_id = ?", tenantId, userId, id) > 0;
            if (!exists) {
                missing.add(userId);
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(404, "USER_NOT_FOUND", "人员不在该组织内",
                    Map.of("userIds", missing));
        }
        int moved = db.update("UPDATE sys_user SET org_id = ?, updated_by = ?"
                + " WHERE tenant_id = ? AND org_id = ? AND id IN (" + placeholders(userIds) + ")",
                concat(new Object[]{targetId, currentUserId(), tenantId, id}, userIds));
        audit.record("ORG_MOVE_USERS", "org", id, Map.of(
                "fromOrgId", id, "toOrgId", targetId, "userIds", userIds, "count", moved));
        return Map.of("moved", moved, "fromOrgId", id, "toOrgId", targetId);
    }

    private static List<Long> userIds(Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        if (body.get("userIds") instanceof List<?> list) {
            for (Object value : list) {
                Long id = longOrNull(value);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static String placeholders(List<Long> values) {
        return String.join(",", java.util.Collections.nCopies(values.size(), "?"));
    }

    private static Object[] concat(Object[] head, List<Long> tail) {
        Object[] merged = new Object[head.length + tail.size()];
        System.arraycopy(head, 0, merged, 0, head.length);
        for (int i = 0; i < tail.size(); i++) {
            merged[head.length + i] = tail.get(i);
        }
        return merged;
    }

    private static long requireId(Map<String, Object> body) {
        Long id = longOrNull(body.get("id"));
        if (id == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "缺少组织 id", null);
        }
        return id;
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "缺少字段 " + key, null);
        }
        return value.toString().trim();
    }

    static Long longOrNull(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString().trim());
    }

    static long currentUserId() {
        var principal = TenantContext.principal();
        return principal == null ? 0L : principal.userId();
    }
}
