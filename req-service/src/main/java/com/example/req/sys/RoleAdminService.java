package com.example.req.sys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.security.PermissionService;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.ActionRegistry;
import com.example.platform.contract.MenuRegistry;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.PermissionMatrix.Access;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles and their grants: the only place that writes {@code sys_role_permission}.
 *
 * <p>The save is a full overwrite on purpose. A partial write ("set these codes") makes the stored
 * grid depend on what a previous version of the software thought the subject universe was: a page
 * added later would silently inherit whatever row happened to be missing. Writing all
 * {@code 660 + 396 + 792 = 1848} subjects every time means the row set is a function of the
 * registries, which is what s2's hash equality relies on.</p>
 *
 * <p>Order of operations in {@link #saveGrants}: validate everything, then diff, then the lockout
 * and high-risk gates, then delete + bulk insert, then one audit row, then invalidate. Nothing is
 * written before every rule has passed — a 409 must leave the database exactly as it was.</p>
 */
@Service
public class RoleAdminService {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,31}$");
    private static final int CHUNK = 200;

    private final SysDb db;
    private final PermissionService permissions;
    private final AuditService audit;

    public RoleAdminService(SysDb db, PermissionService permissions, AuditService audit) {
        this.db = db;
        this.permissions = permissions;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ list / read

    @Transactional(readOnly = true)
    public List<SysViews.RoleRow> list() {
        long tenantId = tenantId();
        List<SysViews.RoleRow> rows = new ArrayList<>();
        for (Object[] row : db.rows("""
                SELECT r.id, r.code, r.name, r.description, r.builtin, r.status,
                       (SELECT COUNT(*) FROM sys_user_role ur
                          JOIN sys_user u ON u.id = ur.user_id AND u.tenant_id = ur.tenant_id
                         WHERE ur.tenant_id = r.tenant_id AND ur.role_id = r.id
                           AND u.status = 'ACTIVE') AS user_count,
                       r.updated_at
                  FROM sys_role r
                 WHERE r.tenant_id = ?
                 ORDER BY r.builtin DESC, r.id""", tenantId)) {
            rows.add(toRow(row));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public SysViews.RoleGrantView grants(long roleId) {
        long tenantId = tenantId();
        Role role = requireRole(tenantId, roleId);
        Map<String, Map<String, Access>> stored = stored(tenantId, roleId);
        List<SysViews.ModuleGrant> modules = new ArrayList<>();
        List<SysViews.Warning> warnings = new ArrayList<>();
        for (String module : MenuRegistry.modules()) {
            List<SysViews.PageGrant> pages = new ArrayList<>();
            for (MenuRegistry.MenuPage page : MenuRegistry.of(module)) {
                Access pageAccess = accessOf(stored, "MENU", page.code());
                List<SysViews.FunctionGrant> functions = page.functionCodes().stream()
                        .map(code -> new SysViews.FunctionGrant(code,
                                accessOf(stored, "FUNCTION", code).name()))
                        .toList();
                List<SysViews.ActionGrant> actions = ActionRegistry.of(page.code()).stream()
                        .map(action -> new SysViews.ActionGrant(action.code(), action.name(),
                                accessOf(stored, "ACTION", action.code()).name(),
                                action.highRisk(), action.functionCodes()))
                        .toList();
                pages.add(new SysViews.PageGrant(page.code(), page.name(), page.routeName(),
                        pageAccess.name(), functions, actions));
                List<String> grantedActions = actions.stream()
                        .filter(action -> !"DENY".equals(action.access()))
                        .map(SysViews.ActionGrant::code)
                        .toList();
                if (pageAccess == Access.DENY && !grantedActions.isEmpty()) {
                    warnings.add(new SysViews.Warning("PAGE_DENY_ACTION_GRANTED", page.code(),
                            grantedActions));
                }
            }
            modules.add(new SysViews.ModuleGrant(module, module, pages));
        }
        return new SysViews.RoleGrantView(toRow(role.row()), permissions.epoch(), modules,
                unboundGroup(stored), warnings);
    }

    /**
     * The 30 function codes no page is bound to, level taken from the same stored snapshot as the
     * modules — one read, one source of truth (r20 BUG-1 read half).
     */
    private SysViews.UnboundGroup unboundGroup(Map<String, Map<String, Access>> stored) {
        List<SysViews.FunctionGrant> unbound = MenuRegistry.unboundFunctionCodes().stream()
                .map(code -> new SysViews.FunctionGrant(code,
                        accessOf(stored, "FUNCTION", code).name()))
                .toList();
        return new SysViews.UnboundGroup("UNBOUND", "未绑定页面的功能码", unbound);
    }

    // ------------------------------------------------------------------ role writes

    @Transactional
    public SysViews.RoleRow create(String code, String name, String description) {
        long tenantId = tenantId();
        String roleCode = normalizeCode(code);
        String roleName = required(name, "角色名称不能为空");
        if (db.count("SELECT COUNT(*) FROM sys_role WHERE tenant_id = ? AND code = ?", tenantId,
                roleCode) > 0) {
            throw new ApiException(409, "ROLE_CODE_TAKEN", "角色编码已存在：" + roleCode);
        }
        long id = db.insert("""
                INSERT INTO sys_role (tenant_id, code, name, description, builtin, status,
                                      created_by, updated_by)
                VALUES (?, ?, ?, ?, 0, 'ACTIVE', ?, ?)""",
                tenantId, roleCode, roleName, blankToNull(description), currentUserId(),
                currentUserId());
        audit.record("ROLE_CREATE", "role", id,
                Map.of("code", roleCode, "name", roleName));
        return toRow(requireRole(tenantId, id).row());
    }

    @Transactional
    public SysViews.RoleRow update(long roleId, String code, String name, String description) {
        long tenantId = tenantId();
        Role role = requireRole(tenantId, roleId);
        if (code != null && !code.isBlank() && !code.equals(role.code())) {
            throw new ApiException(400, "CODE_IMMUTABLE", "角色编码创建后不可修改");
        }
        String roleName = name == null || name.isBlank() ? role.name()
                : required(name, "角色名称不能为空");
        db.update("""
                UPDATE sys_role SET name = ?, description = ?, updated_by = ?
                 WHERE tenant_id = ? AND id = ?""",
                roleName, blankToNull(description), currentUserId(), tenantId, roleId);
        audit.record("ROLE_UPDATE", "role", roleId,
                Map.of("code", role.code(), "before", Map.of("name", role.name()),
                        "after", Map.of("name", roleName)));
        return toRow(requireRole(tenantId, roleId).row());
    }

    @Transactional
    public SysViews.RoleRow setStatus(long roleId, String status) {
        return setStatus(roleId, status, null);
    }

    /**
     * Status change carrying the operator's reason.
     *
     * <p>DESIGN §3.1 makes the reason part of the 停用 confirmation, and s8 asserts the audit row
     * keeps what changed; without it the trail answers "disabled" but not "why", which is the
     * question an incident review actually asks. The reason is optional so that
     * {@link #setStatus(long, String)} keeps its exact pre-r19 behaviour (no extra key in the
     * audit detail); a caller that supplies one gets it recorded, capped at the 100 chars the
     * dialog allows.</p>
     */
    @Transactional
    public SysViews.RoleRow setStatus(long roleId, String status, String reason) {
        long tenantId = tenantId();
        Role role = requireRole(tenantId, roleId);
        String target = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!target.equals("ACTIVE") && !target.equals("DISABLED")) {
            throw new ApiException(400, "INVALID_STATUS", "状态只能是 ACTIVE 或 DISABLED");
        }
        String note = reason == null ? "" : reason.trim();
        if (note.length() > 100) {
            throw new ApiException(400, "REASON_TOO_LONG", "原因不超过 100 字");
        }
        if (role.builtin() && target.equals("DISABLED")) {
            throw new ApiException(409, "BUILTIN_ROLE_IMMUTABLE", "内置角色不能停用");
        }
        if (target.equals(role.status())) {
            return toRow(role.row());
        }
        db.update("UPDATE sys_role SET status = ?, updated_by = ? WHERE tenant_id = ? AND id = ?",
                target, currentUserId(), tenantId, roleId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", role.code());
        detail.put("before", role.status());
        detail.put("after", target);
        if (!note.isEmpty()) {
            detail.put("reason", note);
        }
        audit.record("ROLE_STATUS", "role", roleId, detail);
        permissions.invalidate(tenantId);
        return toRow(requireRole(tenantId, roleId).row());
    }

    @Transactional
    public void delete(long roleId) {
        long tenantId = tenantId();
        Role role = requireRole(tenantId, roleId);
        if (role.builtin()) {
            throw new ApiException(409, "BUILTIN_ROLE_IMMUTABLE", "内置角色不能删除");
        }
        long users = db.count("SELECT COUNT(*) FROM sys_user_role WHERE tenant_id = ? AND role_id = ?",
                tenantId, roleId);
        if (users > 0) {
            throw new ApiException(409, "ROLE_IN_USE", "该角色仍有 " + users + " 个用户，请先改派",
                    Map.of("users", users));
        }
        db.update("DELETE FROM sys_role_permission WHERE tenant_id = ? AND role_id = ?",
                tenantId, roleId);
        db.update("DELETE FROM sys_role WHERE tenant_id = ? AND id = ?", tenantId, roleId);
        audit.record("ROLE_DELETE", "role", roleId, Map.of("code", role.code()));
        permissions.invalidate(tenantId);
    }

    // ------------------------------------------------------------------ grants write

    @Transactional
    public SysViews.GrantSaveResult saveGrants(long roleId, List<SysViews.GrantInput> grants,
                                               boolean confirmHighRisk) {
        long tenantId = tenantId();
        Role role = requireRole(tenantId, roleId);
        Map<String, Map<String, Access>> before = stored(tenantId, roleId);
        Map<String, Map<String, Access>> after = stored(tenantId, roleId);

        for (SysViews.GrantInput grant : grants == null ? List.<SysViews.GrantInput>of() : grants) {
            RoleGuard.validateSubject(grant.type(), grant.code());
            Access access = parseAccess(grant.access());
            after.get(grant.type().trim().toUpperCase(Locale.ROOT)).put(grant.code(), access);
        }

        Diff diff = diff(before, after);
        Map<String, Access> draftFunctions = after.get("FUNCTION");
        if (role.code().equals(RoleGuard.adminRoleCode())) {
            RoleGuard.assertNoLockout(draftFunctions, (int) activeAdmins(tenantId));
        }
        List<String> highRisk = RoleGuard.highRiskGrants(role.code(),
                grants == null ? List.of() : grants, storedStrings(before));
        if (!highRisk.isEmpty() && !confirmHighRisk) {
            throw new ApiException(409, "HIGH_RISK_REQUIRES_CONFIRM",
                    "本次新增了高危动作授权，需要再次确认",
                    Map.of("actions", highRisk));
        }

        db.update("DELETE FROM sys_role_permission WHERE tenant_id = ? AND role_id = ?",
                tenantId, roleId);
        List<Object[]> rows = new ArrayList<>(1848);
        for (String layer : List.of("FUNCTION", "MENU", "ACTION")) {
            for (Map.Entry<String, Access> entry : after.get(layer).entrySet()) {
                rows.add(new Object[]{tenantId, roleId, layer, entry.getKey(),
                        entry.getValue().name()});
            }
        }
        for (int start = 0; start < rows.size(); start += CHUNK) {
            List<Object[]> chunk = rows.subList(start, Math.min(start + CHUNK, rows.size()));
            StringBuilder sql = new StringBuilder("INSERT INTO sys_role_permission"
                    + " (tenant_id, role_id, subject_type, subject_code, access) VALUES ");
            List<Object> params = new ArrayList<>(chunk.size() * 5);
            for (int i = 0; i < chunk.size(); i++) {
                sql.append(i == 0 ? "(?, ?, ?, ?, ?)" : ", (?, ?, ?, ?, ?)");
                for (Object value : chunk.get(i)) {
                    params.add(value);
                }
            }
            db.update(sql.toString(), params.toArray());
        }

        if (diff.total() > 0) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("roleCode", role.code());
            detail.put("added", diff.added());
            detail.put("removed", diff.removed());
            detail.put("changed", diff.changed());
            detail.put("highRiskGranted", highRisk);
            detail.put("before", storedStrings(before));
            detail.put("after", storedStrings(after));
            audit.record("ROLE_PERMISSION_UPDATE", "role", roleId, detail);
        }
        permissions.invalidate(tenantId);

        List<SysViews.Warning> warnings = warnings(after);
        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("menus", nonDeny(after.get("MENU")).keySet());
        effective.put("actions", nonDeny(after.get("ACTION")).keySet());
        return new SysViews.GrantSaveResult(permissions.epoch(), diff.added(), diff.removed(),
                diff.changed(), highRisk, warnings, effective);
    }

    // ------------------------------------------------------------------ helpers

    private record Role(long id, String code, String name, String description, boolean builtin,
                        String status, Object[] row) {
    }

    private record Diff(int added, int removed, int changed) {
        int total() {
            return added + removed + changed;
        }
    }

    private Role requireRole(long tenantId, long roleId) {
        Optional<Object[]> row = db.row("""
                SELECT r.id, r.code, r.name, r.description, r.builtin, r.status,
                       (SELECT COUNT(*) FROM sys_user_role ur
                          JOIN sys_user u ON u.id = ur.user_id AND u.tenant_id = ur.tenant_id
                         WHERE ur.tenant_id = r.tenant_id AND ur.role_id = r.id
                           AND u.status = 'ACTIVE') AS user_count,
                       r.updated_at
                  FROM sys_role r WHERE r.tenant_id = ? AND r.id = ?""", tenantId, roleId);
        if (row.isEmpty()) {
            throw new ApiException(404, "ROLE_NOT_FOUND", "角色不存在");
        }
        Object[] values = row.get();
        return new Role(roleId, SysDb.asString(values[1]), SysDb.asString(values[2]),
                SysDb.asString(values[3]), SysDb.asBoolean(values[4]), SysDb.asString(values[5]),
                values);
    }

    private SysViews.RoleRow toRow(Object[] values) {
        return new SysViews.RoleRow(SysDb.asLong(values[0]), SysDb.asString(values[1]),
                SysDb.asString(values[2]), SysDb.asString(values[3]), SysDb.asBoolean(values[4]),
                SysDb.asString(values[5]), SysDb.asInt(values[6]), SysDb.asIso(values[7]));
    }

    /** Every subject the registries know, at DENY. */
    private Map<String, Map<String, Access>> fullGrid() {
        Map<String, Map<String, Access>> grid = new LinkedHashMap<>();
        Map<String, Access> functions = new LinkedHashMap<>();
        PermissionMatrix.functions().forEach(code -> functions.put(code, Access.DENY));
        Map<String, Access> menus = new LinkedHashMap<>();
        MenuRegistry.codes().forEach(code -> menus.put(code, Access.DENY));
        Map<String, Access> actions = new LinkedHashMap<>();
        ActionRegistry.codes().forEach(code -> actions.put(code, Access.DENY));
        grid.put("FUNCTION", functions);
        grid.put("MENU", menus);
        grid.put("ACTION", actions);
        return grid;
    }

    private Map<String, Map<String, Access>> stored(long tenantId, long roleId) {
        Map<String, Map<String, Access>> grid = fullGrid();
        for (Object[] row : db.rows("""
                SELECT subject_type, subject_code, access FROM sys_role_permission
                 WHERE tenant_id = ? AND role_id = ?""", tenantId, roleId)) {
            String layer = SysDb.asString(row[0]);
            Map<String, Access> values = grid.get(layer);
            if (values != null) {
                values.put(SysDb.asString(row[1]), RoleGuard.parse(SysDb.asString(row[2])));
            }
        }
        return grid;
    }

    private static Access accessOf(Map<String, Map<String, Access>> grid, String layer, String code) {
        Map<String, Access> values = grid.get(layer);
        Access access = values == null ? null : values.get(code);
        return access == null ? Access.DENY : access;
    }

    private static Diff diff(Map<String, Map<String, Access>> before,
                             Map<String, Map<String, Access>> after) {
        int added = 0;
        int removed = 0;
        int changed = 0;
        for (String layer : List.of("FUNCTION", "MENU", "ACTION")) {
            for (Map.Entry<String, Access> entry : after.get(layer).entrySet()) {
                Access was = accessOf(before, layer, entry.getKey());
                Access now = entry.getValue();
                if (was == now) {
                    continue;
                }
                if (was == Access.DENY) {
                    added++;
                } else if (now == Access.DENY) {
                    removed++;
                } else {
                    changed++;
                }
            }
        }
        return new Diff(added, removed, changed);
    }

    /** Non-DENY subjects only: this is what a human reads in the audit row. */
    private static Map<String, String> storedStrings(Map<String, Map<String, Access>> grid) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String layer : List.of("FUNCTION", "MENU", "ACTION")) {
            for (Map.Entry<String, Access> entry : nonDeny(grid.get(layer)).entrySet()) {
                values.put(layer + ":" + entry.getKey(), entry.getValue().name());
            }
        }
        return values;
    }

    private static Map<String, Access> nonDeny(Map<String, Access> values) {
        Map<String, Access> result = new LinkedHashMap<>();
        values.forEach((code, access) -> {
            if (access != null && access != Access.DENY) {
                result.put(code, access);
            }
        });
        return result;
    }

    private static List<SysViews.Warning> warnings(Map<String, Map<String, Access>> grid) {
        List<SysViews.Warning> warnings = new ArrayList<>();
        for (MenuRegistry.MenuPage page : MenuRegistry.pages()) {
            if (accessOf(grid, "MENU", page.code()) != Access.DENY) {
                continue;
            }
            List<String> granted = ActionRegistry.of(page.code()).stream()
                    .map(ActionRegistry.ActionDef::code)
                    .filter(code -> accessOf(grid, "ACTION", code) != Access.DENY)
                    .toList();
            if (!granted.isEmpty()) {
                warnings.add(new SysViews.Warning("PAGE_DENY_ACTION_GRANTED", page.code(), granted));
            }
        }
        return warnings;
    }

    private long activeAdmins(long tenantId) {
        return db.count("""
                SELECT COUNT(*) FROM sys_user_role ur
                  JOIN sys_user u ON u.id = ur.user_id AND u.tenant_id = ur.tenant_id
                  JOIN sys_role r ON r.id = ur.role_id AND r.tenant_id = ur.tenant_id
                 WHERE ur.tenant_id = ? AND r.code = ? AND u.status = 'ACTIVE'
                   AND r.status = 'ACTIVE'""", tenantId, RoleGuard.adminRoleCode());
    }

    private static Access parseAccess(String value) {
        String raw = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return Access.DENY;
        }
        try {
            return Access.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, "INVALID_ACCESS", "未知授权档位：" + value);
        }
    }

    private static String normalizeCode(String code) {
        String value = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(value).matches()) {
            throw new ApiException(400, "INVALID_ROLE_CODE",
                    "角色编码需为 3-32 位大写字母、数字或下划线，且以字母开头");
        }
        return value;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, "INVALID_ROLE_NAME", message);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long tenantId() {
        return TenantContext.require().tenantId();
    }

    private static Long currentUserId() {
        return TenantContext.require().userId();
    }
}
