package com.example.req.sys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Business domains of the current tenant: the unit every scope predicate filters on. */
@RestController
@RequestMapping("/admin/domains")
@Transactional
public class AdminDomainController {

    private final SysDb db;

    public AdminDomainController(SysDb db) {
        this.db = db;
    }

    @GetMapping
    public List<SysViews.DomainRow> list() {
        long tenantId = TenantContext.require().tenantId();
        List<SysViews.DomainRow> rows = new ArrayList<>();
        for (Object[] row : db.rows("SELECT d.id, d.code, d.name, d.owner_user_id, u.name,"
                + " d.enabled FROM biz_domain d LEFT JOIN sys_user u"
                + " ON u.id = d.owner_user_id AND u.tenant_id = d.tenant_id"
                + " WHERE d.tenant_id = ? ORDER BY d.sort_no, d.id", tenantId)) {
            rows.add(new SysViews.DomainRow(SysDb.asLong(row[0]), SysDb.asString(row[1]),
                    SysDb.asString(row[2]), SysDb.asNullableLong(row[3]), SysDb.asString(row[4]),
                    SysDb.asBoolean(row[5])));
        }
        return rows;
    }

    @PostMapping
    public SysViews.DomainRow create(@RequestBody Map<String, Object> body) {
        long tenantId = TenantContext.require().tenantId();
        String code = required(body, "code").toUpperCase();
        String name = required(body, "name");
        Long ownerId = AdminOrgController.longOrNull(body.get("ownerId"));
        long id;
        try {
            id = db.insert("INSERT INTO biz_domain (tenant_id, code, name, owner_user_id, enabled,"
                            + " sort_no, created_by, updated_by) VALUES (?, ?, ?, ?, 1, 0, ?, ?)",
                    tenantId, code, name, ownerId, AdminOrgController.currentUserId(),
                    AdminOrgController.currentUserId());
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "DOMAIN_CODE_TAKEN", "域编码已存在: " + code, null);
        }
        return new SysViews.DomainRow(id, code, name, ownerId, ownerName(tenantId, ownerId), true);
    }

    @PutMapping
    public SysViews.DomainRow update(@RequestBody Map<String, Object> body) {
        long tenantId = TenantContext.require().tenantId();
        Long id = AdminOrgController.longOrNull(body.get("id"));
        if (id == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "缺少域 id", null);
        }
        Object[] existing = db.row("SELECT code, name, owner_user_id, enabled FROM biz_domain"
                        + " WHERE tenant_id = ? AND id = ?", tenantId, id)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "业务域不存在",
                        Map.of("id", id)));
        String name = body.get("name") == null ? SysDb.asString(existing[1])
                : body.get("name").toString();
        Long ownerId = body.containsKey("ownerId") ? AdminOrgController.longOrNull(body.get("ownerId"))
                : SysDb.asNullableLong(existing[2]);
        boolean enabled = body.containsKey("enabled") ? Boolean.parseBoolean(
                body.get("enabled").toString()) : SysDb.asBoolean(existing[3]);
        db.update("UPDATE biz_domain SET name = ?, owner_user_id = ?, enabled = ?, updated_by = ?"
                + " WHERE tenant_id = ? AND id = ?", name, ownerId, enabled,
                AdminOrgController.currentUserId(), tenantId, id);
        return new SysViews.DomainRow(id, SysDb.asString(existing[0]), name, ownerId,
                ownerName(tenantId, ownerId), enabled);
    }

    private String ownerName(long tenantId, Long ownerId) {
        if (ownerId == null) {
            return null;
        }
        return db.row("SELECT name FROM sys_user WHERE tenant_id = ? AND id = ?", tenantId, ownerId)
                .map(row -> SysDb.asString(row[0])).orElse(null);
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "缺少字段 " + key, null);
        }
        return value.toString().trim();
    }
}
