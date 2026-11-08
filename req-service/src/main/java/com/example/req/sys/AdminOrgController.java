package com.example.req.sys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
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

    public AdminOrgController(SysDb db) {
        this.db = db;
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
