package com.example.platform.common.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Reads the caller's *current* authorization from MySQL.
 *
 * <p>Two indexed queries per request (roles, domains) instead of a cache: the requirement is that
 * a revoked role is effective within a second, and a 12-hour token must never freeze a permission
 * snapshot. The cost is negligible next to the business query that follows.</p>
 */
@Component
public class UserScopeResolver {

    public record TenantRow(long id, String code, String name, String status) {
    }

    public record UserRow(long id, long tenantId, String username, String name, String passwordHash,
                          Long orgId, Long vendorId, String status) {
    }

    private static final RowMapper<TenantRow> TENANT_MAPPER = (rs, rowNum) -> new TenantRow(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("status"));

    private static final RowMapper<UserRow> USER_MAPPER = (rs, rowNum) -> new UserRow(
            rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("username"),
            rs.getString("name"), rs.getString("password_hash"),
            (Long) rs.getObject("org_id", Long.class), (Long) rs.getObject("vendor_id", Long.class),
            rs.getString("status"));

    private final JdbcTemplate jdbc;

    public UserScopeResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantRow> tenantById(long id) {
        return jdbc.query("SELECT id, code, name, status FROM sys_tenant WHERE id = ?",
                TENANT_MAPPER, id).stream().findFirst();
    }

    public Optional<TenantRow> tenantByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT id, code, name, status FROM sys_tenant WHERE code = ?",
                TENANT_MAPPER, code.trim()).stream().findFirst();
    }

    public Optional<UserRow> userByUsername(long tenantId, String username) {
        return jdbc.query("SELECT id, tenant_id, username, name, password_hash, org_id, vendor_id,"
                        + " status FROM sys_user WHERE tenant_id = ? AND username = ?",
                USER_MAPPER, tenantId, username).stream().findFirst();
    }

    public Optional<UserRow> userById(long tenantId, long userId) {
        return jdbc.query("SELECT id, tenant_id, username, name, password_hash, org_id, vendor_id,"
                        + " status FROM sys_user WHERE tenant_id = ? AND id = ?",
                USER_MAPPER, tenantId, userId).stream().findFirst();
    }

    public List<String> roles(long tenantId, long userId) {
        return jdbc.queryForList("SELECT r.code FROM sys_user_role ur"
                        + " JOIN sys_role r ON r.id = ur.role_id AND r.tenant_id = ur.tenant_id"
                        + " WHERE ur.tenant_id = ? AND ur.user_id = ? ORDER BY r.id",
                String.class, tenantId, userId);
    }

    public Set<Long> domains(long tenantId, long userId) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT domain_id FROM sys_user_domain WHERE tenant_id = ? AND user_id = ?",
                Long.class, tenantId, userId));
    }

    /** The user's organization plus every descendant, from the materialized {@code path}. */
    public Set<Long> orgSubtree(long tenantId, Long orgId) {
        if (orgId == null) {
            return Set.of();
        }
        List<String> paths = jdbc.queryForList(
                "SELECT path FROM sys_org WHERE tenant_id = ? AND id = ?", String.class,
                tenantId, orgId);
        if (paths.isEmpty()) {
            return Set.of();
        }
        String path = paths.get(0);
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND path LIKE ?", Long.class,
                tenantId, path + "%"));
    }

    public UserIdentity identity(UserRow user, TenantRow tenant) {
        List<String> roles = roles(user.tenantId(), user.id());
        Set<Long> domains = domains(user.tenantId(), user.id());
        Set<Long> subtree = orgSubtree(user.tenantId(), user.orgId());
        return new UserIdentity(user.id(), user.tenantId(), user.username(), user.name(),
                tenant.code(), tenant.name(), roles, domains, user.vendorId(), subtree);
    }

    /**
     * Resolve the {@code X-Tenant-Id} header, which scripts send as a tenant <em>code</em> and the
     * gateway sends as a numeric id. Both are accepted; anything else resolves to 0.
     */
    public long tenantIdOfHeader(String header) {
        if (header == null || header.isBlank()) {
            return 0L;
        }
        String value = header.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(value);
        }
        return tenantByCode(value).map(TenantRow::id).orElse(0L);
    }
}
