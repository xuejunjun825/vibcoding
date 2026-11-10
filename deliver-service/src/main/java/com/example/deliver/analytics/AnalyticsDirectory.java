package com.example.deliver.analytics;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.platform.common.tenant.TenantContext;

/**
 * The read-only role directory: role code → {@code sys_role.id}.
 *
 * <p>The frontend sends role <b>codes</b> ({@code PMO}, {@code DEV}); the shared-scope checker
 * compares numeric ids ({@code scopeRef ∈ roleIds}). This is the one place that translation
 * happens, and it is deliberately not part of the报表 data path: it reads the authorization
 * directory, never a report, so it does not become a second analytics source.</p>
 *
 * <p>Tenant scoping is explicit ({@code tenant_id = :tenant}) rather than through
 * {@code ScopedJdbc}: {@code sys_role} is not a scope-tier table, it is the directory that
 * defines the tiers.</p>
 */
@Component
public class AnalyticsDirectory {

    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The ids of the given role codes that exist in this tenant. Unknown codes are skipped. */
    public Set<Long> roleIds(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Set.of();
        }
        List<String> wanted = codes.stream().filter(c -> c != null && !c.isBlank())
                .map(String::trim).distinct().toList();
        if (wanted.isEmpty()) {
            return Set.of();
        }
        long tenantId = TenantContext.require().tenantId();
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM sys_role WHERE tenant_id = :tenant AND code IN (:codes)",
                new MapSqlParameterSource("tenant", tenantId).addValue("codes", wanted),
                Long.class);
        return new LinkedHashSet<>(ids);
    }

    /** One role's id, when this tenant defines that code. */
    public Optional<Long> roleId(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        long tenantId = TenantContext.require().tenantId();
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM sys_role WHERE tenant_id = :tenant AND code = :code",
                new MapSqlParameterSource("tenant", tenantId).addValue("code", code.trim()),
                Long.class);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }
}
