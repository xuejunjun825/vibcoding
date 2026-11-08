package com.example.deliver.openapi;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads API clients by {@code app_key}.
 *
 * <p>Deliberately not a scoped query: the call arrives before any tenant is known, and
 * {@code app_key} is globally unique in the schema ({@code uk_app_key}), so the row itself
 * decides which tenant the call belongs to. Everything after this lookup runs inside that
 * tenant's {@code DataScope}.</p>
 */
@Component
public class ApiClientStore {

    private final JdbcTemplate jdbc;

    public ApiClientStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One client row; {@code secretEnc} may be null when only a hash was stored. */
    public record ApiClientRow(long id,
                               long tenantId,
                               String name,
                               String appKey,
                               String secretHash,
                               byte[] secretEnc,
                               String scopes,
                               String ipWhitelist,
                               int rateLimitQps,
                               String status) {

        public boolean active() {
            return "ACTIVE".equalsIgnoreCase(status);
        }

        public List<String> scopeList() {
            if (scopes == null || scopes.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(scopes.split("[,\\s]+"))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList();
        }

        public List<String> ipList() {
            if (ipWhitelist == null || ipWhitelist.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(ipWhitelist.split("[,\\s]+"))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList();
        }
    }

    public Optional<ApiClientRow> byAppKey(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return Optional.empty();
        }
        List<ApiClientRow> rows = jdbc.query("""
                SELECT id, tenant_id, name, app_key, secret_hash, secret_enc,
                       scopes, ip_whitelist, rate_limit_qps, status
                FROM api_client
                WHERE app_key = ?
                """, (rs, rowNum) -> new ApiClientRow(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("name"),
                rs.getString("app_key"),
                rs.getString("secret_hash"),
                rs.getBytes("secret_enc"),
                rs.getString("scopes"),
                rs.getString("ip_whitelist"),
                rs.getInt("rate_limit_qps"),
                rs.getString("status")), appKey.trim());
        return rows.stream().findFirst();
    }
}
