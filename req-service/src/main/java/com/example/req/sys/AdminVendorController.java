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

/**
 * Software vendors. A vendor has no domain column of its own: its reach is derived from the
 * requirements it owns or is assigned, which keeps one fact in one place.
 */
@RestController
@RequestMapping("/admin/vendors")
@Transactional
public class AdminVendorController {

    private final SysDb db;

    public AdminVendorController(SysDb db) {
        this.db = db;
    }

    @GetMapping
    public List<SysViews.VendorRow> list() {
        long tenantId = TenantContext.require().tenantId();
        List<SysViews.VendorRow> rows = new ArrayList<>();
        for (Object[] row : db.rows("SELECT id, code, name, contact, status FROM sys_vendor"
                + " WHERE tenant_id = ? ORDER BY id", tenantId)) {
            long id = SysDb.asLong(row[0]);
            rows.add(new SysViews.VendorRow(id, SysDb.asString(row[1]), SysDb.asString(row[2]),
                    SysDb.asString(row[3]), SysDb.asString(row[4]), domainsOf(tenantId, id)));
        }
        return rows;
    }

    @PostMapping
    public SysViews.VendorRow create(@RequestBody Map<String, Object> body) {
        long tenantId = TenantContext.require().tenantId();
        String name = required(body, "name");
        String code = body.get("code") == null || body.get("code").toString().isBlank()
                ? autoCode(tenantId) : body.get("code").toString().toUpperCase();
        String contact = body.get("contact") == null ? null : body.get("contact").toString();
        long id;
        try {
            id = db.insert("INSERT INTO sys_vendor (tenant_id, code, name, contact, status,"
                            + " created_by, updated_by) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                    tenantId, code, name, contact, AdminOrgController.currentUserId(),
                    AdminOrgController.currentUserId());
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "VENDOR_CODE_TAKEN", "厂商编码已存在: " + code, null);
        }
        return new SysViews.VendorRow(id, code, name, contact, "ACTIVE", List.of());
    }

    @PutMapping
    public SysViews.VendorRow update(@RequestBody Map<String, Object> body) {
        long tenantId = TenantContext.require().tenantId();
        Long id = AdminOrgController.longOrNull(body.get("id"));
        if (id == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "缺少厂商 id", null);
        }
        Object[] existing = db.row("SELECT code, name, contact, status FROM sys_vendor"
                        + " WHERE tenant_id = ? AND id = ?", tenantId, id)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "厂商不存在",
                        Map.of("id", id)));
        String name = body.get("name") == null ? SysDb.asString(existing[1])
                : body.get("name").toString();
        String contact = body.containsKey("contact")
                ? (body.get("contact") == null ? null : body.get("contact").toString())
                : SysDb.asString(existing[2]);
        String status = body.get("status") == null ? SysDb.asString(existing[3])
                : body.get("status").toString().toUpperCase();
        db.update("UPDATE sys_vendor SET name = ?, contact = ?, status = ?, updated_by = ?"
                + " WHERE tenant_id = ? AND id = ?", name, contact, status,
                AdminOrgController.currentUserId(), tenantId, id);
        return new SysViews.VendorRow(id, SysDb.asString(existing[0]), name, contact, status,
                domainsOf(tenantId, id));
    }

    private List<Long> domainsOf(long tenantId, long vendorId) {
        List<Long> ids = new ArrayList<>();
        for (Object value : db.scalars("SELECT DISTINCT domain_id FROM requirement"
                + " WHERE tenant_id = ? AND (owner_vendor_id = ? OR assignee_vendor_id = ?)",
                tenantId, vendorId, vendorId)) {
            ids.add(SysDb.asLong(value));
        }
        return ids;
    }

    private String autoCode(long tenantId) {
        long count = db.count("SELECT COUNT(*) FROM sys_vendor WHERE tenant_id = ?", tenantId);
        return "VEND-" + (count + 1);
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "缺少字段 " + key, null);
        }
        return value.toString().trim();
    }
}
