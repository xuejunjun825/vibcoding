package com.example.deliver.gate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.GateDecision;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gate endpoints. {@code GET /test-gate} answers 200 with {@code passed:false} rather than 403 —
 * the checklist renders the blockers, so a "blocked" verdict is data, not an HTTP error.
 *
 * <p>Internal endpoints are called by {@code req-service}'s {@code GatePort} instead of through the
 * auth filter, so they must establish the tenant scope themselves: without it {@code ScopedJdbc}
 * fails closed ({@code TenantContext is not set}) and every cross-service gate call becomes a 500.
 * The tenant comes from {@code X-Tenant-Id} when the caller sent one (the normal path) and is
 * otherwise derived from the requirement row — an internal call that names only an id must still
 * resolve to that requirement's tenant, never to "all tenants".</p>
 */
@RestController
public class GateController {

    private final TestGateService gate;
    private final NamedParameterJdbcTemplate jdbc;

    public GateController(TestGateService gate, NamedParameterJdbcTemplate jdbc) {
        this.gate = gate;
        this.jdbc = jdbc;
    }

    @GetMapping("/test-gate")
    public Map<String, Object> gate(@RequestParam long requirementId) {
        return gate.evaluate(requirementId).toMap();
    }

    /** Internal: req-service's {@code GatePort} HTTP implementation calls this. */
    @GetMapping("/internal/gates/{requirementId}")
    public Map<String, Object> internalGate(@PathVariable long requirementId,
                                            @RequestHeader(value = "X-Tenant-Id", required = false)
                                            String tenantHeader) {
        long tenantId = tenantOf(tenantHeader, requirementId);
        TenantContext.set(DataScope.system(tenantId));
        try {
            return gate.evaluate(requirementId).toMap();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Internal batch: {@code {"requirementIds":[1,2]}} → {@code {"decisions":{"1":{...}}}}.
     * The nested shape is what {@code DeliverGateClient.parseBatch} accepts.
     */
    @PostMapping("/internal/gates/batch")
    public Map<String, Object> internalBatch(@RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Tenant-Id", required = false)
                                             String tenantHeader) {
        Collection<Long> ids = idsOf(body == null ? null : body.get("requirementIds"));
        Map<Long, GateDecision> decisions = new LinkedHashMap<>();
        Long headerTenant = parseTenant(tenantHeader);
        if (headerTenant != null) {
            decisions.putAll(inTenant(headerTenant, () -> gate.evaluateAll(ids)));
        } else {
            // No tenant header: group the ids by their requirement's tenant so a batch never
            // widens the scope to another tenant.
            Map<Long, List<Long>> byTenant = groupByTenant(ids);
            for (Map.Entry<Long, List<Long>> entry : byTenant.entrySet()) {
                List<Long> group = entry.getValue();
                decisions.putAll(inTenant(entry.getKey(), () -> gate.evaluateAll(group)));
            }
        }
        Map<String, Object> byId = new LinkedHashMap<>();
        decisions.forEach((id, decision) -> byId.put(String.valueOf(id), decision.toMap()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("decisions", byId);
        return response;
    }

    private Map<Long, GateDecision> inTenant(long tenantId,
                                             Supplier<Map<Long, GateDecision>> work) {
        TenantContext.set(DataScope.system(tenantId));
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    private long tenantOf(String tenantHeader, Long requirementId) {
        Long tenant = parseTenant(tenantHeader);
        if (tenant != null) {
            return tenant;
        }
        if (requirementId != null) {
            List<Long> found = jdbc.queryForList(
                    "SELECT tenant_id FROM requirement WHERE id = :id",
                    new MapSqlParameterSource("id", requirementId), Long.class);
            if (!found.isEmpty() && found.get(0) != null) {
                return found.get(0);
            }
        }
        return 1L;
    }

    private Map<Long, List<Long>> groupByTenant(Collection<Long> ids) {
        Map<Long, Long> tenants = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, tenant_id FROM requirement WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", ids));
            for (Map<String, Object> row : rows) {
                Number id = (Number) row.get("id");
                Number tenant = (Number) row.get("tenant_id");
                if (id != null && tenant != null) {
                    tenants.put(id.longValue(), tenant.longValue());
                }
            }
        }
        Map<Long, List<Long>> grouped = new LinkedHashMap<>();
        for (Long id : ids) {
            grouped.computeIfAbsent(tenants.getOrDefault(id, 1L), key -> new ArrayList<>()).add(id);
        }
        return grouped;
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

    private static Collection<Long> idsOf(Object raw) {
        if (!(raw instanceof Collection<?> values)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value instanceof Number number) {
                ids.add(number.longValue());
            } else if (value != null) {
                try {
                    ids.add(Long.parseLong(String.valueOf(value).trim()));
                } catch (NumberFormatException ignored) {
                    // A malformed id simply is not evaluated; the caller gets no entry for it.
                }
            }
        }
        return ids;
    }

    /** Exposed for the gate unit tests: the policy is reachable without Spring. */
    static List<String> modes() {
        return List.of(GatePolicy.STRICT, GatePolicy.LOOSE);
    }
}
