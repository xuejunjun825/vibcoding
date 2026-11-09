package com.example.deliver.quality.internal;

import java.util.List;

import com.example.platform.common.error.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tenant resolution for {@code /internal/**} callers, which carry no JWT: the {@code X-Tenant-Id}
 * header wins, otherwise the release row's own tenant decides.
 *
 * <p>Never derive a tenant from the request body — a caller could then read another tenant's
 * data. Extracted from {@code QualityInternalController} so every internal controller resolves
 * the scope the same way instead of copying the fallback (and the 404) again.</p>
 */
@Component
public class InternalTenantResolver {

    private final NamedParameterJdbcTemplate jdbc;

    public InternalTenantResolver(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Header first; otherwise the release's own tenant; 404 when the release does not exist. */
    public long of(String tenantHeader, long releaseId) {
        Long header = parseTenant(tenantHeader);
        if (header != null) {
            return header;
        }
        List<Long> found = jdbc.queryForList(
                "SELECT tenant_id FROM `release` WHERE id = :id",
                new MapSqlParameterSource("id", releaseId), Long.class);
        if (!found.isEmpty() && found.get(0) != null) {
            return found.get(0);
        }
        throw new ApiException(404, "RELEASE_NOT_FOUND", "发布版本不存在");
    }

    private static Long parseTenant(String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(tenantHeader.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
