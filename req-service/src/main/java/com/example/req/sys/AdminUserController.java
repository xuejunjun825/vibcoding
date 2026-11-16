package com.example.req.sys;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.platform.common.audit.AuditService;
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

/**
 * Accounts of the current tenant: list, create, edit, enable/disable, reset password.
 *
 * <p>r18 makes every assignment "all or nothing": roles, organisation, vendor and domains are
 * resolved against the database <em>before</em> the first write, so an unknown role can no longer
 * leave a half-built account behind (a user with the old roles gone and the new ones missing). The
 * writes themselves are diffs — only what actually changed is deleted, inserted and audited.</p>
 */
@RestController
@RequestMapping("/admin/users")
@Transactional
public class AdminUserController {

    private static final String ADMIN_ROLE = "PLATFORM_ADMIN";

    private final SysDb db;
    private final PasswordService passwords;
    private final AuditService audit;

    public AdminUserController(SysDb db, PasswordService passwords, AuditService audit) {
        this.db = db;
        this.passwords = passwords;
        this.audit = audit;
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
        // Validate everything first: a rejected role must not leave an account with no roles.
        Map<String, Long> roleIds = resolveRoles(tenantId, roleCodes(body));
        validateVendorBinding(roleCodes(body), vendorId);
        requireOrg(tenantId, orgId);
        requireVendor(tenantId, vendorId);
        List<Long> domainIds = resolveDomains(tenantId, domainIds(body));
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
        replaceRoles(tenantId, id, roleIds);
        replaceDomains(tenantId, id, domainIds);
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
        Long previousOrgId = SysDb.asNullableLong(existing[2]);
        Long vendorId = body.containsKey("vendorId")
                ? AdminOrgController.longOrNull(body.get("vendorId"))
                : SysDb.asNullableLong(existing[3]);
        String status = body.get("status") == null ? SysDb.asString(existing[4])
                : body.get("status").toString().toUpperCase();
        boolean touchesRoles = body.containsKey("roles") || body.containsKey("roleCodes");
        boolean touchesDomains = body.containsKey("domainIds");
        Map<String, Long> roleIds = touchesRoles ? resolveRoles(tenantId, roleCodes(body)) : null;
        List<Long> domainIds = touchesDomains ? resolveDomains(tenantId, domainIds(body)) : null;
        // The roles that will be in force once this update lands: a body that omits them keeps the
        // stored ones, so validating `roleCodes(body)` alone would let "clear the vendor, keep
        // VENDOR_PM" through — and that account then sees nothing and can write nothing.
        // Checked before the UPDATE below, so a rejected edit leaves the row untouched.
        validateVendorBinding(touchesRoles ? roleCodes(body) : roles(tenantId, id), vendorId);
        requireOrg(tenantId, orgId);
        requireVendor(tenantId, vendorId);
        if (!"ACTIVE".equals(status)) {
            assertNotLastAdmin(tenantId, id);
        }
        db.update("UPDATE sys_user SET name = ?, org_id = ?, vendor_id = ?, status = ?,"
                + " updated_by = ? WHERE tenant_id = ? AND id = ?", name, orgId, vendorId, status,
                AdminOrgController.currentUserId(), tenantId, id);
        if (!java.util.Objects.equals(previousOrgId, orgId)) {
            audit.record("USER_ORG", "user", id, Map.of(
                    "fromOrgId", previousOrgId == null ? 0L : previousOrgId,
                    "toOrgId", orgId == null ? 0L : orgId));
        }
        if (body.get("password") != null && !body.get("password").toString().isBlank()) {
            String password = body.get("password").toString();
            if (password.length() < 8) {
                throw new ApiException(400, "WEAK_PASSWORD", "密码至少 8 位", null);
            }
            db.update("UPDATE sys_user SET password_hash = ? WHERE tenant_id = ? AND id = ?",
                    passwords.encode(password), tenantId, id);
        }
        if (roleIds != null) {
            replaceRoles(tenantId, id, roleIds);
        }
        if (domainIds != null) {
            replaceDomains(tenantId, id, domainIds);
        }
        return row(tenantId, id);
    }

    @PatchMapping("/{id}/status")
    public SysViews.UserRow changeStatus(@PathVariable long id,
                                         @RequestBody Map<String, Object> body) {
        long tenantId = TenantContext.require().tenantId();
        String status = required(body, "status").toUpperCase();
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new ApiException(400, "INVALID_STATUS", "状态只能是 ACTIVE 或 DISABLED", null);
        }
        String previous = db.row("SELECT status FROM sys_user WHERE tenant_id = ? AND id = ?",
                        tenantId, id)
                .map(row -> SysDb.asString(row[0]))
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "用户不存在",
                        Map.of("id", id)));
        if (!"ACTIVE".equals(status)) {
            assertNotLastAdmin(tenantId, id);
        }
        db.update("UPDATE sys_user SET status = ?, updated_by = ?"
                        + " WHERE tenant_id = ? AND id = ?", status,
                AdminOrgController.currentUserId(), tenantId, id);
        if (!previous.equals(status)) {
            audit.record("USER_STATUS", "user", id, Map.of("from", previous, "to", status));
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
        audit.record("USER_PASSWORD_RESET", "user", id, Map.of());
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

    /** Resolves every requested code or rejects the whole assignment — nothing is written. */
    private Map<String, Long> resolveRoles(long tenantId, List<String> codes) {
        Map<String, Long> resolved = new java.util.LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String code : new LinkedHashSet<>(codes)) {
            Long roleId = db.row("SELECT id FROM sys_role WHERE tenant_id = ? AND code = ?",
                    tenantId, code).map(row -> SysDb.asLong(row[0])).orElse(null);
            if (roleId == null) {
                unknown.add(code);
            } else {
                resolved.put(code, roleId);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ApiException(400, "UNKNOWN_ROLE", "未知角色: " + String.join(",", unknown),
                    Map.of("codes", unknown));
        }
        return resolved;
    }

    private List<Long> resolveDomains(long tenantId, List<Long> ids) {
        List<Long> resolved = new ArrayList<>();
        List<Long> unknown = new ArrayList<>();
        for (Long domainId : new LinkedHashSet<>(ids)) {
            if (domainId == null) {
                continue;
            }
            Long exists = db.row("SELECT id FROM biz_domain WHERE tenant_id = ? AND id = ?",
                    tenantId, domainId).map(row -> SysDb.asLong(row[0])).orElse(null);
            if (exists == null) {
                unknown.add(domainId);
            } else {
                resolved.add(domainId);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ApiException(400, "UNKNOWN_DOMAIN", "未知业务域", Map.of("ids", unknown));
        }
        return resolved;
    }

    private void requireOrg(long tenantId, Long orgId) {
        if (orgId == null) {
            return;
        }
        boolean exists = !db.rows("SELECT id FROM sys_org WHERE tenant_id = ? AND id = ?",
                tenantId, orgId).isEmpty();
        if (!exists) {
            throw new ApiException(404, "ORG_NOT_FOUND", "组织不存在", Map.of("orgId", orgId));
        }
    }

    private void requireVendor(long tenantId, Long vendorId) {
        if (vendorId == null) {
            return;
        }
        boolean exists = !db.rows("SELECT id FROM sys_vendor WHERE tenant_id = ? AND id = ?",
                tenantId, vendorId).isEmpty();
        if (!exists) {
            throw new ApiException(404, "VENDOR_NOT_FOUND", "厂商不存在",
                    Map.of("vendorId", vendorId));
        }
    }

    /**
     * A VENDOR_PM account without a vendor is not a usable account: its data scope resolves to no
     * vendor, so every list is empty and every write 403s. Reject the write instead of storing an
     * account that looks fine in the user list and does nothing anywhere else.
     *
     * <p>Both write entries (create and update) come through here, and update passes the roles that
     * will be <em>in force</em> — omitting {@code roles} keeps the stored ones.</p>
     */
    private void validateVendorBinding(List<String> effectiveRoles, Long vendorId) {
        if (vendorId == null && RoleCatalog.requiresVendor(effectiveRoles)) {
            throw new ApiException(400, "VALIDATION_FAILED", "VENDOR_PM 用户必须绑定厂商（vendorId）",
                    Map.of("field", "vendorId"));
        }
    }

    /** Disabling the last active platform administrator would lock the tenant out for good. */
    private void assertNotLastAdmin(long tenantId, long userId) {
        boolean isAdmin = !db.rows("SELECT 1 FROM sys_user_role ur"
                        + " JOIN sys_role r ON r.id = ur.role_id AND r.tenant_id = ur.tenant_id"
                        + " WHERE ur.tenant_id = ? AND ur.user_id = ? AND r.code = ?"
                        + " AND r.status = 'ACTIVE'", tenantId, userId, ADMIN_ROLE).isEmpty();
        if (!isAdmin) {
            return;
        }
        long others = db.count("SELECT COUNT(DISTINCT u.id) FROM sys_user u"
                + " JOIN sys_user_role ur ON ur.user_id = u.id AND ur.tenant_id = u.tenant_id"
                + " JOIN sys_role r ON r.id = ur.role_id AND r.tenant_id = ur.tenant_id"
                + " WHERE u.tenant_id = ? AND u.id <> ? AND u.status = 'ACTIVE'"
                + " AND r.code = ? AND r.status = 'ACTIVE'", tenantId, userId, ADMIN_ROLE);
        if (others <= 0) {
            throw new ApiException(409, "ADMIN_LOCKOUT_RISK",
                    "不能停用最后一个平台管理员", Map.of("reason", "LAST_ADMIN"));
        }
    }

    private void replaceRoles(long tenantId, long userId, Map<String, Long> roleIds) {
        Map<String, Long> before = new java.util.LinkedHashMap<>();
        for (Object[] row : db.rows("SELECT r.code, r.id FROM sys_user_role ur JOIN sys_role r"
                + " ON r.id = ur.role_id AND r.tenant_id = ur.tenant_id"
                + " WHERE ur.tenant_id = ? AND ur.user_id = ?", tenantId, userId)) {
            before.put(SysDb.asString(row[0]), SysDb.asLong(row[1]));
        }
        Set<String> added = new LinkedHashSet<>(roleIds.keySet());
        added.removeAll(before.keySet());
        Set<String> removed = new LinkedHashSet<>(before.keySet());
        removed.removeAll(roleIds.keySet());
        for (String code : removed) {
            db.update("DELETE FROM sys_user_role WHERE tenant_id = ? AND user_id = ?"
                    + " AND role_id = ?", tenantId, userId, before.get(code));
        }
        for (String code : added) {
            db.update("INSERT INTO sys_user_role (tenant_id, user_id, role_id) VALUES (?, ?, ?)",
                    tenantId, userId, roleIds.get(code));
        }
        if (!added.isEmpty() || !removed.isEmpty()) {
            audit.record("USER_ROLES", "user", userId, Map.of(
                    "added", List.copyOf(added), "removed", List.copyOf(removed)));
        }
    }

    private void replaceDomains(long tenantId, long userId, List<Long> domainIds) {
        Set<Long> before = new LinkedHashSet<>();
        for (Object value : db.scalars("SELECT domain_id FROM sys_user_domain"
                + " WHERE tenant_id = ? AND user_id = ?", tenantId, userId)) {
            before.add(SysDb.asLong(value));
        }
        Set<Long> after = new LinkedHashSet<>(domainIds);
        List<Long> added = new ArrayList<>(after);
        added.removeAll(before);
        List<Long> removed = new ArrayList<>(before);
        removed.removeAll(after);
        for (Long domainId : removed) {
            db.update("DELETE FROM sys_user_domain WHERE tenant_id = ? AND user_id = ?"
                    + " AND domain_id = ?", tenantId, userId, domainId);
        }
        for (Long domainId : added) {
            db.update("INSERT INTO sys_user_domain (tenant_id, user_id, domain_id)"
                    + " VALUES (?, ?, ?)", tenantId, userId, domainId);
        }
        if (!added.isEmpty() || !removed.isEmpty()) {
            audit.record("USER_DOMAINS", "user", userId, Map.of(
                    "added", added, "removed", removed));
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
