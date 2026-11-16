package com.example.req.sys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.PermissionMatrix;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only overview of the 600-cell function grid.
 *
 * <p>The r17 screen rendered the compile-time matrix and offered no way to change anything; r18
 * keeps it a read-only overview but reads the <em>stored</em> FUNCTION rows, so what an operator
 * sees here is what the runtime will decide. Editing happens on the role-permission page — the one
 * place that owns the transaction and the lockout guard. There is deliberately no write mapping on
 * this path, and s9 asserts that it stays that way.</p>
 */
@RestController
@RequestMapping("/admin/permissions")
public class AdminPermissionController {

    private final SysDb db;

    public AdminPermissionController(SysDb db) {
        this.db = db;
    }

    @GetMapping
    public SysViews.PermissionMatrixView matrix() {
        long tenantId = TenantContext.require().tenantId();
        List<SysViews.RoleRow> roles = new ArrayList<>();
        Map<String, Map<String, String>> byRole = new LinkedHashMap<>();
        for (Object[] row : db.rows("""
                SELECT r.code, r.name
                  FROM sys_role r
                 WHERE r.tenant_id = ? AND r.status = 'ACTIVE'
                 ORDER BY r.builtin DESC, r.id""", tenantId)) {
            String code = SysDb.asString(row[0]);
            roles.add(new SysViews.RoleRow(0L, code, SysDb.asString(row[1]), null, true,
                    "ACTIVE", 0, null));
            byRole.put(code, new LinkedHashMap<>());
        }
        for (Object[] row : db.rows("""
                SELECT r.code, p.subject_code, p.access
                  FROM sys_role_permission p
                  JOIN sys_role r ON r.id = p.role_id AND r.tenant_id = p.tenant_id
                 WHERE p.tenant_id = ? AND p.subject_type = 'FUNCTION'
                   AND r.status = 'ACTIVE'""", tenantId)) {
            byRole.computeIfAbsent(SysDb.asString(row[0]), key -> new LinkedHashMap<>())
                    .put(SysDb.asString(row[1]), SysDb.asString(row[2]));
        }

        List<SysViews.PermissionFunction> functions = new ArrayList<>();
        for (String function : PermissionMatrix.functions()) {
            List<String> allow = new ArrayList<>();
            List<String> restricted = new ArrayList<>();
            List<String> deny = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> role : byRole.entrySet()) {
                String access = role.getValue().get(function);
                switch (access == null ? "DENY" : access) {
                    case "ALLOW" -> allow.add(role.getKey());
                    case "RESTRICTED" -> restricted.add(role.getKey());
                    default -> deny.add(role.getKey());
                }
            }
            functions.add(new SysViews.PermissionFunction(function, allow, restricted, deny));
        }
        return new SysViews.PermissionMatrixView(functions, roles);
    }
}
