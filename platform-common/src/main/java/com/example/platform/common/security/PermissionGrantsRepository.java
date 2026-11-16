package com.example.platform.common.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.contract.PermissionMatrix.Access;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads {@code sys_role_permission} into a {@link PermissionService.Snapshot}.
 *
 * <p>The SQL lives here and nowhere else: it is the only place that knows the row shape
 * ({@code subject_type} + {@code subject_code} + {@code access}) and the only place that filters
 * disabled roles. Filtering in SQL, not in Java, is deliberate — a role that was disabled after the
 * snapshot was built must not survive in the cache, and the cheapest way to guarantee that is to
 * never load it.</p>
 *
 * <p>Only {@code ACTIVE} roles contribute. A role with no rows contributes nothing, which makes a
 * freshly created custom role neutral instead of a blanket deny (PRD §AC-6: adding an empty role
 * must not clear the permissions the user already had).</p>
 */
@Component
public class PermissionGrantsRepository {

    private static final String SQL = """
            SELECT r.code AS role_code,
                   p.subject_type AS subject_type,
                   p.subject_code AS subject_code,
                   p.access AS access
              FROM sys_role_permission p
              JOIN sys_role r
                ON r.id = p.role_id
               AND r.tenant_id = p.tenant_id
             WHERE p.tenant_id = ?
               AND r.status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbc;

    public PermissionGrantsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All grants of one tenant, keyed layer → role → subject. */
    public PermissionService.Snapshot load(long tenantId, long epoch) {
        Map<PermissionService.Layer, Map<String, Map<String, Access>>> byRole =
                new LinkedHashMap<>();
        for (PermissionService.Layer layer : PermissionService.Layer.values()) {
            byRole.put(layer, new LinkedHashMap<>());
        }
        List<Map<String, Object>> rows = jdbc.queryForList(SQL, tenantId);
        for (Map<String, Object> row : rows) {
            String role = text(row.get("role_code"));
            PermissionService.Layer layer = layer(text(row.get("subject_type")));
            String subject = text(row.get("subject_code"));
            Access access = access(text(row.get("access")));
            if (role == null || layer == null || subject == null || access == null) {
                continue;
            }
            byRole.get(layer).computeIfAbsent(role, key -> new LinkedHashMap<>())
                    .put(subject, access);
        }
        Map<PermissionService.Layer, Map<String, Map<String, Access>>> frozen =
                new LinkedHashMap<>();
        byRole.forEach((layer, roles) -> {
            Map<String, Map<String, Access>> copy = new LinkedHashMap<>();
            roles.forEach((role, grants) -> copy.put(role, Map.copyOf(grants)));
            frozen.put(layer, Map.copyOf(copy));
        });
        return new PermissionService.Snapshot(tenantId, epoch, System.currentTimeMillis(),
                Map.copyOf(frozen));
    }

    /** Count of grant rows of one tenant — used by the scripts and the reset verification. */
    public long count(long tenantId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_permission WHERE tenant_id = ?", Long.class,
                tenantId);
        return count == null ? 0L : count;
    }

    private static PermissionService.Layer layer(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "FUNCTION" -> PermissionService.Layer.FUNCTION;
            case "MENU" -> PermissionService.Layer.MENU;
            case "ACTION" -> PermissionService.Layer.ACTION;
            default -> null;
        };
    }

    private static Access access(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "ALLOW" -> Access.ALLOW;
            case "RESTRICTED" -> Access.RESTRICTED;
            case "DENY" -> Access.DENY;
            default -> null;
        };
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
