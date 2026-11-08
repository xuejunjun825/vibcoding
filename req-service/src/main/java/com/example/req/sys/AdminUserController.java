package com.example.req.sys;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Accounts of the current tenant: list, create, edit, enable/disable, reset password. */
@RestController
@RequestMapping("/admin/users")
@Transactional
public class AdminUserController {

    private final SysDb db;
    private final PasswordService passwords;

    public AdminUserController(SysDb db, PasswordService passwords) {
        this.db = db;
        this.passwords = passwords;
    }

    @GetMapping
    public PageResponse<SysViews.UserRow> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long tenantId = TenantContext.require().tenantId();
        int pageNo = SysDb.pageNo(page);
        int pageSize = SysDb.pageSize(size);
        String like = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        String where = " FROM sys_user u LEFT JOIN sys_org o ON o.id = u.org_id"
                + " AND o.tenant_id = u.tenant_id WHERE u.tenant_id = ?"
                + (like == null ? "" : " AND (u.username LIKE ? OR u.name LIKE ?)");
        Object[] params = like == null ? new Object[]{tenantId}
                : new Object[]{tenantId, like, like};
        long total = db.count("SELECT COUNT(*)" + where, params);
        List<SysViews.UserRow> items = new ArrayList<>();
        for (Object[] row : db.rows("SELECT u.id, u.username, u.name, u.org_id, o.name,"
                + " u.vendor_id, u.status" + where + " ORDER BY u.id LIMIT ? OFFSET ?",
                append(params, pageSize, (pageNo - 1) * pageSize))) {
            long id = SysDb.asLong(row[0]);
            items.add(new SysViews.UserRow(id, SysDb.asString(row[1]), SysDb.asString(row[2]),
                    roles(tenantId, id), SysDb.asNullableLong(row[3]), SysDb.asString(row[4]),
                    domains(tenantId, id), SysDb.asNullableLong(row[5]), SysDb.asString(row[6])));
        }
        return new PageResponse<>(items, total, pageNo, pageSize);
    }

    @PostMapping
    public SysViews.UserRow create(@RequestBody Map<String, Object> body) {
        long tenantId = TenantContext.require().tenantId();
        String username = required(body, "username");
        String password = body.get("password") == null ? "Passw0rd!" : body.get("password").toString();
        if (password.length() < 8) {
            throw new ApiException(400, "WEAK_PASSWORD", "密码至少 8 位", null);
        }
        String name = body.get("name") == null ? username : body.get("name").toString();
        Long orgId = AdminOrgController.longOrNull(body.get("orgId"));
        Long vendorId = AdminOrgController.longOrNull(body.get("vendorId"));
        long id;
        try {
            id = db.insert("INSERT INTO sys_user (tenant_id, username, name, password_hash, org_id,"
                            + " vendor_id, status, created_by, updated_by)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                    tenantId, username, name, passwords.encode(password), orgId, vendorId,
                    AdminOrgController.currentUserId(), AdminOrgController.currentUserId());
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "USERNAME_TAKEN", "用户名已存在: " + username, null);
        }
        replaceRoles(tenantId, id, roleCodes(body));
        replaceDomains(tenantId, id, domainIds(body));
        return row(tenantId, id);
    }

    @PutMapping
    public SysViews.UserRow update(@RequestBody Map<String, Object> body) {
        long tenantId = TenantContext.require().tenantId();
        Long id = AdminOrgController.longOrNull(body.get("id"));
        if (id == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "缺少用户 id", null);
        }
        Object[] existing = db.row("SELECT username, name, org_id, vendor_id, status FROM sys_user"
                        + " WHERE tenant_id = ? AND id = ?", tenantId, id)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "用户不存在",
                        Map.of("id", id)));
        String name = body.get("name") == null ? SysDb.asString(existing[1])
                : body.get("name").toString();
        Long orgId = body.containsKey("orgId") ? AdminOrgController.longOrNull(body.get("orgId"))
                : SysDb.asNullableLong(existing[2]);
        Long vendorId = body.containsKey("vendorId")
                ? AdminOrgController.longOrNull(body.get("vendorId"))
                : SysDb.asNullableLong(existing[3]);
        String status = body.get("status") == null ? SysDb.asString(existing[4])
                : body.get("status").toString().toUpperCase();
        db.update("UPDATE sys_user SET name = ?, org_id = ?, vendor_id = ?, status = ?,"
                + " updated_by = ? WHERE tenant_id = ? AND id = ?", name, orgId, vendorId, status,
                AdminOrgController.currentUserId(), tenantId, id);
        if (body.get("password") != null && !body.get("password").toString().isBlank()) {
            String password = body.get("password").toString();
            if (password.length() < 8) {
                throw new ApiException(400, "WEAK_PASSWORD", "密码至少 8 位", null);
            }
            db.update("UPDATE sys_user SET password_hash = ? WHERE tenant_id = ? AND id = ?",
                    passwords.encode(password), tenantId, id);
        }
        if (body.containsKey("roles") || body.containsKey("roleCodes")) {
            replaceRoles(tenantId, id, roleCodes(body));
        }
        if (body.containsKey("domainIds")) {
            replaceDomains(tenantId, id, domainIds(body));
        }
        return row(tenantId, id);
    }

    @PatchMapping("/{id}/status")
    public SysViews.UserRow changeStatus(@PathVariable long id,
                                         @RequestBody Map<String, Object> body) {
        long tenantId = TenantContext.require().tenantId();
        String status = required(body, "status").toUpperCase();
        int updated = db.update("UPDATE sys_user SET status = ?, updated_by = ?"
                + " WHERE tenant_id = ? AND id = ?", status,
                AdminOrgController.currentUserId(), tenantId, id);
        if (updated == 0) {
            throw new ApiException(404, "NOT_FOUND", "用户不存在", Map.of("id", id));
        }
        return row(tenantId, id);
    }

    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable long id,
                                             @RequestBody Map<String, Object> body) {
        long tenantId = TenantContext.require().tenantId();
        String password = required(body, "password");
        if (password.length() < 8) {
            throw new ApiException(400, "WEAK_PASSWORD", "密码至少 8 位", null);
        }
        int updated = db.update("UPDATE sys_user SET password_hash = ?, updated_by = ?"
                        + " WHERE tenant_id = ? AND id = ?", passwords.encode(password),
                AdminOrgController.currentUserId(), tenantId, id);
        if (updated == 0) {
            throw new ApiException(404, "NOT_FOUND", "用户不存在", Map.of("id", id));
        }
        return Map.of("userId", id, "reset", true);
    }

    private SysViews.UserRow row(long tenantId, long id) {
        Object[] row = db.row("SELECT u.id, u.username, u.name, u.org_id, o.name, u.vendor_id,"
                        + " u.status FROM sys_user u LEFT JOIN sys_org o ON o.id = u.org_id"
                        + " AND o.tenant_id = u.tenant_id WHERE u.tenant_id = ? AND u.id = ?",
                tenantId, id).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "用户不存在",
                Map.of("id", id)));
        return new SysViews.UserRow(id, SysDb.asString(row[1]), SysDb.asString(row[2]),
                roles(tenantId, id), SysDb.asNullableLong(row[3]), SysDb.asString(row[4]),
                domains(tenantId, id), SysDb.asNullableLong(row[5]), SysDb.asString(row[6]));
    }

    private List<String> roles(long tenantId, long userId) {
        return db.scalars("SELECT r.code FROM sys_user_role ur JOIN sys_role r"
                        + " ON r.id = ur.role_id AND r.tenant_id = ur.tenant_id"
                        + " WHERE ur.tenant_id = ? AND ur.user_id = ? ORDER BY r.id",
                tenantId, userId).stream().map(SysDb::asString).toList();
    }

    private List<Long> domains(long tenantId, long userId) {
        List<Long> ids = new ArrayList<>();
        for (Object value : db.scalars("SELECT domain_id FROM sys_user_domain"
                + " WHERE tenant_id = ? AND user_id = ? ORDER BY domain_id", tenantId, userId)) {
            ids.add(SysDb.asLong(value));
        }
        return ids;
    }

    private void replaceRoles(long tenantId, long userId, List<String> codes) {
        db.update("DELETE FROM sys_user_role WHERE tenant_id = ? AND user_id = ?", tenantId, userId);
        for (String code : codes) {
            Long roleId = db.row("SELECT id FROM sys_role WHERE tenant_id = ? AND code = ?",
                    tenantId, code).map(row -> SysDb.asLong(row[0])).orElse(null);
            if (roleId != null) {
                db.update("INSERT INTO sys_user_role (tenant_id, user_id, role_id)"
                        + " VALUES (?, ?, ?)", tenantId, userId, roleId);
            }
        }
    }

    private void replaceDomains(long tenantId, long userId, List<Long> domainIds) {
        db.update("DELETE FROM sys_user_domain WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId);
        for (Long domainId : new LinkedHashSet<>(domainIds)) {
            Long exists = db.row("SELECT id FROM biz_domain WHERE tenant_id = ? AND id = ?",
                    tenantId, domainId).map(row -> SysDb.asLong(row[0])).orElse(null);
            if (exists != null) {
                db.update("INSERT INTO sys_user_domain (tenant_id, user_id, domain_id)"
                        + " VALUES (?, ?, ?)", tenantId, userId, domainId);
            }
        }
    }

    private static List<String> roleCodes(Map<String, Object> body) {
        Object raw = body.containsKey("roles") ? body.get("roles") : body.get("roleCodes");
        List<String> codes = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object value : list) {
                if (value != null) {
                    codes.add(value.toString());
                }
            }
        }
        return codes;
    }

    private static List<Long> domainIds(Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        if (body.get("domainIds") instanceof List<?> list) {
            for (Object value : list) {
                Long id = AdminOrgController.longOrNull(value);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static Object[] append(Object[] params, Object... extra) {
        Object[] merged = new Object[params.length + extra.length];
        System.arraycopy(params, 0, merged, 0, params.length);
        System.arraycopy(extra, 0, merged, params.length, extra.length);
        return merged;
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "缺少字段 " + key, null);
        }
        return value.toString().trim();
    }

    /** Kept for symmetry with the other admin controllers; roles are replaced by code. */
    static Set<String> distinct(List<String> values) {
        return new LinkedHashSet<>(values);
    }
}
