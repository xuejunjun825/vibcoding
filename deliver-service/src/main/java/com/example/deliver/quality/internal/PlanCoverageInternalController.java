package com.example.deliver.quality.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import com.example.deliver.quality.plan.PlanLifecycle;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only plan-coverage lookup used by {@code req-service} before a deploy order is written
 * (R1, AC-1..AC-4).
 *
 * <p>The approval vocabulary belongs to the side that owns the plan rows: this endpoint answers
 * "which not-yet-approved plans cover these requirements in this release", so {@code req} never
 * reads {@code deliver}'s tables (AC-4). It performs no write, no snapshot and no audit — a
 * caller may ask as often as it likes without changing state.</p>
 *
 * <p>Guarded by {@code InternalTokenFilter} on {@code /internal/**}; the tenant scope comes from
 * {@link InternalTenantResolver} (header first, else the release row), never from the body.</p>
 */
@RestController
public class PlanCoverageInternalController {

    /**
     * {@code /*scope:p*} must stay: {@link ScopedJdbc} rejects a statement without it, and the
     * predicate is what keeps one tenant's plans out of another tenant's answer.
     */
    private static final String COVERAGE_SQL = """
            SELECT DISTINCT p.id AS plan_id, p.name AS plan_name, p.status AS status,
                   c.requirement_id AS requirement_id
              FROM test_plan p
              JOIN test_plan_case c ON c.plan_id = p.id AND c.tenant_id = p.tenant_id
             WHERE /*scope:p*/ AND p.release_id = :releaseId
               AND p.status IN (:pendingStatuses)
               AND c.requirement_id IN (:requirementIds)
             ORDER BY p.id, c.requirement_id
            """;

    private final ScopedJdbc jdbcRead;
    private final InternalTenantResolver tenantResolver;

    public PlanCoverageInternalController(ScopedJdbc jdbcRead,
                                          InternalTenantResolver tenantResolver) {
        this.jdbcRead = jdbcRead;
        this.tenantResolver = tenantResolver;
    }

    /** Distinct pending plans covering {@code requirementIds}; empty input short-circuits. */
    @GetMapping("/internal/test-plans/pending-coverage")
    public Coverage pendingCoverage(@RequestParam long releaseId,
                                    @RequestParam(required = false) List<Long> requirementIds,
                                    @RequestHeader(value = "X-Tenant-Id", required = false)
                                    String tenantHeader) {
        List<Long> normalised = normalise(requirementIds);
        if (normalised.isEmpty()) {
            return new Coverage(List.of(), List.of());
        }
        long tenantId = tenantResolver.of(tenantHeader, releaseId);
        TenantContext.set(DataScope.system(tenantId));
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("releaseId", releaseId);
            params.put("pendingStatuses", PlanLifecycle.pendingStatuses());
            params.put("requirementIds", normalised);
            return aggregate(jdbcRead.list(COVERAGE_SQL, params));
        } finally {
            TenantContext.clear();
        }
    }

    /** One plan and the subset of the asked requirements it covers. */
    public record PlanRef(long planId, String planName, String status, List<Long> requirementIds) {
    }

    /** {@code planIds} is the machine-readable form the deploy-order 409 carries. */
    public record Coverage(List<PlanRef> items, List<Long> planIds) {
    }

    private static Coverage aggregate(List<Map<String, Object>> rows) {
        Map<Long, String> names = new TreeMap<>();
        Map<Long, String> statuses = new TreeMap<>();
        Map<Long, TreeSet<Long>> covered = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            long planId = asLong(row.get("plan_id"));
            if (planId <= 0) {
                continue;
            }
            names.putIfAbsent(planId, asText(row.get("plan_name")));
            statuses.putIfAbsent(planId, asText(row.get("status")));
            long requirementId = asLong(row.get("requirement_id"));
            if (requirementId > 0) {
                covered.computeIfAbsent(planId, key -> new TreeSet<>()).add(requirementId);
            }
        }
        List<PlanRef> items = new ArrayList<>(names.size());
        for (Map.Entry<Long, String> entry : names.entrySet()) {
            long planId = entry.getKey();
            items.add(new PlanRef(planId, entry.getValue(), statuses.get(planId),
                    List.copyOf(covered.getOrDefault(planId, new TreeSet<>()))));
        }
        return new Coverage(items, List.copyOf(names.keySet()));
    }

    private static List<Long> normalise(List<Long> requirementIds) {
        if (requirementIds == null) {
            return List.of();
        }
        TreeSet<Long> unique = new TreeSet<>();
        for (Long id : requirementIds) {
            if (id != null && id > 0) {
                unique.add(id);
            }
        }
        return List.copyOf(unique);
    }

    private static long asLong(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String asText(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
