package com.example.deliver.defect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service-to-service endpoints. They carry no user token, so they authenticate with
 * {@code X-Internal-Token} and run under an explicit system scope — never under a caller-supplied
 * user scope, and never unscoped (that would be a cross-tenant read).
 */
@RestController
@RequestMapping("/internal")
public class DefectInternalController {

    private final ScopedJdbc jdbc;
    private final String token;

    @PersistenceContext
    private EntityManager em;

    public DefectInternalController(ScopedJdbc jdbc,
                                    @Value("${platform.internal.token:${INTERNAL_TOKEN:dev-internal-token}}")
                                    String token) {
        this.jdbc = jdbc;
        this.token = token;
    }

    /**
     * Readiness probe for {@code scripts/platform-up.sh}. The start script requires a semantic
     * answer ({@code status=UP} AND {@code db=up}), so a port that accepts connections but whose
     * datasource is down can never be reported ready. Before 2026-11-09 T11 this returned only
     * {@code status/service}; platform-up then polled it for 120s and failed a service that had
     * booted cleanly.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "deliver-service");
        boolean dbUp = false;
        try {
            em.createNativeQuery("SELECT 1").getSingleResult();
            dbUp = true;
        } catch (RuntimeException ex) {
            body.put("dbError", ex.getClass().getSimpleName());
        }
        body.put("db", dbUp ? "up" : "down");
        body.put("flyway", flywayVersion());
        return body;
    }

    /** Latest successful migration version; {@code null} while the schema owner has not migrated yet. */
    private String flywayVersion() {
        try {
            List<?> rows = em.createNativeQuery("SELECT version FROM flyway_schema_history"
                    + " WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1").getResultList();
            return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Open P0/P1 per requirement — the release admission's "related defects" item.
     *
     * @return {@code {requirementId: count}}; requirements with zero open high defects are absent
     *         (the caller treats a missing key as zero)
     */
    @GetMapping("/defects/open-high")
    public Map<Long, Integer> openHigh(
            @RequestHeader(value = "X-Internal-Token", required = false) String provided,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantHeader,
            @RequestParam(value = "requirementIds", required = false) String requirementIds) {
        if (provided == null || !token.equals(provided)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid internal token");
        }
        Map<Long, Integer> counts = new LinkedHashMap<>();
        if (requirementIds == null || requirementIds.isBlank()) {
            return counts;
        }
        List<Long> ids = java.util.Arrays.stream(requirementIds.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).toList();
        if (ids.isEmpty()) {
            return counts;
        }
        long tenantId = tenantHeader == null ? 1L : tenantHeader;
        TenantContext.set(DataScope.system(tenantId));
        try {
            String sql = "SELECT d.requirement_id AS requirement_id, COUNT(*) AS cnt "
                    + "FROM defect d "
                    + "WHERE /*scope:d*/ AND d.requirement_id IN (:ids) "
                    + "AND d.severity IN ('P0','P1') AND d.status <> 'CLOSED' "
                    + "GROUP BY d.requirement_id";
            for (Map<String, Object> row : jdbc.list(sql, Map.of("ids", ids))) {
                Long requirementId = Rows.toLong(Rows.object(row, "requirement_id"));
                if (requirementId != null) {
                    counts.put(requirementId, Rows.toInt(Rows.object(row, "cnt"), 0));
                }
            }
        } finally {
            TenantContext.clear();
        }
        return counts;
    }
}
