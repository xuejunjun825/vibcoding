package com.example.req.sys;

import java.util.List;
import java.util.regex.Pattern;

import com.example.platform.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a tenant and everything a tenant needs to be usable: its organizations, the ten built-in
 * roles, the seven business domains, the dictionaries and one platform administrator.
 *
 * <p>Provisioning is one transaction: a half-created tenant (roles but no admin, or domains but no
 * roles) would be worse than no tenant at all, because the failure only surfaces when the first
 * person tries to log in.</p>
 */
@Service
public class TenantProvisioner {

    public static final String DEFAULT_ADMIN_USERNAME = "admin";
    public static final String DEFAULT_ADMIN_PASSWORD = "Admin@12345";

    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9_-]{1,31}");

    private final SysDb db;
    private final PasswordService passwords;

    public TenantProvisioner(SysDb db, PasswordService passwords) {
        this.db = db;
        this.passwords = passwords;
    }

    @Transactional
    public SysViews.CreatedTenant provision(String rawCode, String rawName, String adminUsername,
                                            String adminPassword) {
        String code = rawCode == null ? "" : rawCode.trim().toLowerCase();
        if (!CODE.matcher(code).matches()) {
            throw new ApiException(400, "INVALID_TENANT_CODE",
                    "租户编码只能包含小写字母、数字、- 和 _，长度 2-32", null);
        }
        String name = rawName == null || rawName.isBlank() ? code : rawName.trim();
        long existing = db.count("SELECT COUNT(*) FROM sys_tenant WHERE code = ?", code);
        if (existing > 0) {
            throw new ApiException(409, "TENANT_CODE_TAKEN", "租户编码已存在: " + code, null);
        }

        long tenantId = db.insert("INSERT INTO sys_tenant (code, name, isolation_mode,"
                        + " datasource_key, status, created_by, updated_by)"
                        + " VALUES (?, ?, 'ROW', NULL, 'ACTIVE', 0, 0)", code, name);

        int orgs = createOrganizations(tenantId);
        int roles = createRoles(tenantId);
        int domains = createDomains(tenantId);
        int dicts = createDictionaries(tenantId);
        String admin = createAdmin(tenantId, adminUsername, adminPassword);

        return new SysViews.CreatedTenant(tenantId, code, name,
                new SysViews.Provisioned(roles, domains, dicts, orgs, admin));
    }

    private int createOrganizations(long tenantId) {
        long root = db.insert("INSERT INTO sys_org (tenant_id, parent_id, type, code, name, path,"
                        + " sort_no, status, created_by, updated_by) VALUES (?, NULL, 'ROOT', ?, ?, ?,"
                        + " 0, 'ACTIVE', 0, 0)", tenantId, "HQ", "总部", "/");
        // materialized path needs the id, so it is written once the id exists
        db.update("UPDATE sys_org SET path = ? WHERE id = ?", "/" + root + "/", root);
        String rootPath = "/" + root + "/";
        String[] departments = {"SUPPLY", "IT", "QA"};
        String[] labels = {"供应链部", "信息技术部", "质量保障部"};
        for (int i = 0; i < departments.length; i++) {
            long child = db.insert("INSERT INTO sys_org (tenant_id, parent_id, type, code, name,"
                            + " path, sort_no, status, created_by, updated_by)"
                            + " VALUES (?, ?, 'DEPT', ?, ?, ?, ?, 'ACTIVE', 0, 0)",
                    tenantId, root, departments[i], labels[i], rootPath + departments[i] + "/", i);
            db.update("UPDATE sys_org SET path = ? WHERE id = ?", rootPath + child + "/", child);
        }
        return 1 + departments.length;
    }

    private int createRoles(long tenantId) {
        for (RoleCatalog.RoleDef role : RoleCatalog.ROLES) {
            db.update("INSERT INTO sys_role (tenant_id, code, name, builtin, created_by, updated_by)"
                    + " VALUES (?, ?, ?, 1, 0, 0)", tenantId, role.code(), role.name());
        }
        return RoleCatalog.ROLES.size();
    }

    private int createDomains(long tenantId) {
        int sort = 0;
        for (RoleCatalog.DomainDef domain : RoleCatalog.DOMAINS) {
            db.update("INSERT INTO biz_domain (tenant_id, code, name, owner_user_id, enabled,"
                            + " sort_no, created_by, updated_by) VALUES (?, ?, ?, NULL, 1, ?, 0, 0)",
                    tenantId, domain.code(), domain.name(), sort++);
        }
        return RoleCatalog.DOMAINS.size();
    }

    private int createDictionaries(long tenantId) {
        for (RoleCatalog.DictDef dict : RoleCatalog.DICTS) {
            db.update("INSERT INTO sys_dict (tenant_id, type, code, label, sort_no, created_by,"
                            + " updated_by) VALUES (?, ?, ?, ?, ?, 0, 0)",
                    tenantId, dict.type(), dict.code(), dict.label(), dict.sort());
        }
        return RoleCatalog.DICTS.size();
    }

    private String createAdmin(long tenantId, String adminUsername, String adminPassword) {
        String username = adminUsername == null || adminUsername.isBlank()
                ? DEFAULT_ADMIN_USERNAME : adminUsername.trim();
        String password = adminPassword == null || adminPassword.isBlank()
                ? DEFAULT_ADMIN_PASSWORD : adminPassword;
        Long rootOrg = db.row("SELECT id FROM sys_org WHERE tenant_id = ? AND parent_id IS NULL"
                + " ORDER BY id LIMIT 1", tenantId).map(row -> SysDb.asLong(row[0])).orElse(null);
        long userId = db.insert("INSERT INTO sys_user (tenant_id, username, name, password_hash,"
                        + " org_id, vendor_id, status, created_by, updated_by)"
                        + " VALUES (?, ?, ?, ?, ?, NULL, 'ACTIVE', 0, 0)",
                tenantId, username, "平台管理员", passwords.encode(password), rootOrg);
        Long adminRole = db.row("SELECT id FROM sys_role WHERE tenant_id = ? AND code = ?",
                tenantId, "PLATFORM_ADMIN").map(row -> SysDb.asLong(row[0])).orElse(null);
        if (adminRole != null) {
            db.update("INSERT INTO sys_user_role (tenant_id, user_id, role_id) VALUES (?, ?, ?)",
                    tenantId, userId, adminRole);
        }
        List<Object[]> domains = db.rows("SELECT id FROM biz_domain WHERE tenant_id = ?", tenantId);
        for (Object[] domain : domains) {
            db.update("INSERT INTO sys_user_domain (tenant_id, user_id, domain_id) VALUES (?, ?, ?)",
                    tenantId, userId, SysDb.asLong(domain[0]));
        }
        return username;
    }
}
