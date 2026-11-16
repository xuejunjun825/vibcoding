package com.example.req.sys;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for roles and their grants. Thin on purpose: the transaction, the diff, the guards
 * and the audit all live in {@link RoleAdminService}, so the 409/400 semantics have one home.
 */
@RestController
@RequestMapping("/admin/roles")
public class AdminRoleController {

    private final RoleAdminService roles;

    public AdminRoleController(RoleAdminService roles) {
        this.roles = roles;
    }

    @GetMapping
    public List<SysViews.RoleRow> list() {
        return roles.list();
    }

    @PostMapping
    public SysViews.RoleRow create(@RequestBody Map<String, Object> body) {
        return roles.create(text(body, "code"), text(body, "name"), text(body, "description"));
    }

    @PutMapping("/{id}")
    public SysViews.RoleRow update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return roles.update(id, text(body, "code"), text(body, "name"), text(body, "description"));
    }

    @PatchMapping("/{id}/status")
    public SysViews.RoleRow setStatus(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return roles.setStatus(id, text(body, "status"));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        roles.delete(id);
        return Map.of("deleted", true, "id", id);
    }

    @GetMapping("/{id}/permissions")
    public SysViews.RoleGrantView grants(@PathVariable long id) {
        return roles.grants(id);
    }

    @PutMapping("/{id}/permissions")
    public SysViews.GrantSaveResult saveGrants(@PathVariable long id,
                                               @RequestBody Map<String, Object> body) {
        return roles.saveGrants(id, grants(body), Boolean.TRUE.equals(body.get("confirmHighRisk")));
    }

    /** Accepts both {@code {type,code,access}} and {@code {subjectType,subjectCode,access}}. */
    static List<SysViews.GrantInput> grants(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("grants");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<SysViews.GrantInput> grants = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String type = first(map, "type", "subjectType", "layer");
            String code = first(map, "code", "subjectCode", "subject");
            String access = first(map, "access", "level");
            grants.add(new SysViews.GrantInput(
                    type == null ? null : type.trim().toUpperCase(Locale.ROOT),
                    code == null ? null : code.trim(),
                    access == null ? null : access.trim().toUpperCase(Locale.ROOT)));
        }
        return grants;
    }

    private static String first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
