package com.example.deliver.quality.internal;

import java.util.List;
import java.util.Map;

import com.example.deliver.quality.gate.QualityGateService;
import com.example.deliver.quality.gate.QualityGateViews;
import com.example.deliver.quality.quality.ReleaseQualityService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal quality endpoints called by {@code req-service} at admission (AC-5, AC-7).
 *
 * <p>Guarded by {@code InternalTokenFilter} on {@code /internal/**}. These requests carry no JWT,
 * so the tenant scope is built here from {@code X-Tenant-Id} — falling back to the release row —
 * and cleared in a {@code finally}. Never derive a tenant from the request body: a caller could
 * then read another tenant's gate.</p>
 *
 * <p>The responses are view records with ISO timestamps, not the platform's
 * {@code GateSnapshot}/{@code QualitySummary} records: those carry {@code Instant}, and this
 * service serialises with Jackson 2 without the JavaTime module.</p>
 */
@RestController
public class QualityInternalController {

    private final QualityGateService gates;
    private final ReleaseQualityService releaseQuality;
    private final InternalTenantResolver tenantResolver;
    private final NamedParameterJdbcTemplate jdbc;

    public QualityInternalController(QualityGateService gates,
                                     ReleaseQualityService releaseQuality,
                                     InternalTenantResolver tenantResolver,
                                     NamedParameterJdbcTemplate jdbc) {
        this.gates = gates;
        this.releaseQuality = releaseQuality;
        this.tenantResolver = tenantResolver;
        this.jdbc = jdbc;
    }

    /** Freeze an evaluation for a deploy order; the same order + same content reuses its snapshot. */
    @PostMapping("/internal/quality-gates/{releaseId}/evaluate")
    public QualityGateViews.Snapshot evaluate(@PathVariable long releaseId,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              @RequestHeader(value = "X-Tenant-Id", required = false)
                                              String tenantHeader) {
        long tenantId = tenantResolver.of(tenantHeader, releaseId);
        long deployOrderId = asLong(body == null ? null : body.get("deployOrderId"), 0L);
        if (deployOrderId <= 0) {
            throw new ApiException(400, "DEPLOY_ORDER_REQUIRED", "内部质量门求值必须携带 deployOrderId");
        }
        List<Long> requirementIds = asLongs(body == null ? null : body.get("requirementIds"));
        TenantContext.set(DataScope.system(tenantId));
        try {
            return gates.freeze(releaseId, deployOrderId, requirementIds);
        } finally {
            TenantContext.clear();
        }
    }

    /** The latest frozen gate for a release; 404 when it was never evaluated. */
    @GetMapping("/internal/quality-gates/{releaseId}/latest")
    public QualityGateViews.Snapshot latest(@PathVariable long releaseId,
                                            @RequestHeader(value = "X-Tenant-Id", required = false)
                                            String tenantHeader) {
        long tenantId = tenantResolver.of(tenantHeader, releaseId);
        TenantContext.set(DataScope.system(tenantId));
        try {
            List<Long> ids = jdbc.queryForList("""
                    SELECT id FROM quality_gate_snapshot
                    WHERE tenant_id = :tenantId AND release_id = :releaseId
                    ORDER BY id DESC
                    LIMIT 1
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("releaseId", releaseId), Long.class);
            if (ids.isEmpty() || ids.get(0) == null) {
                throw new ApiException(404, "GATE_NOT_EVALUATED", "该发布版本尚未执行质量门");
            }
            return gates.viewOf(ids.get(0));
        } finally {
            TenantContext.clear();
        }
    }

    /** Counts for the admission dialog and the release list: no rows, no page, just numbers. */
    @GetMapping("/internal/quality/summary/{releaseId}")
    public ReleaseQualityService.Summary summary(@PathVariable long releaseId,
                                                 @RequestHeader(value = "X-Tenant-Id", required = false)
                                                 String tenantHeader) {
        long tenantId = tenantResolver.of(tenantHeader, releaseId);
        TenantContext.set(DataScope.system(tenantId));
        try {
            return releaseQuality.summary(releaseId);
        } finally {
            TenantContext.clear();
        }
    }

    private static long asLong(Object raw, long fallback) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<Long> asLongs(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .map(item -> asLong(item, 0L))
                .filter(id -> id > 0)
                .toList();
    }
}
