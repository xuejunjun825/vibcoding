package com.example.deliver.quality.strategy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.deliver.support.Rows;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned test strategy (AC-9): a release keeps a history of strategies, exactly one ACTIVE.
 * An ACTIVE version is immutable — editing it in place would invalidate every plan that adopted
 * it, so the API forces a new version instead (409 ILLEGAL_PLAN_TRANSITION).
 */
@Service
public class TestStrategyService {

    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "RETIRED");

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;

    public TestStrategyService(ScopedJdbc jdbcRead, NamedParameterJdbcTemplate jdbc,
                               ObjectMapper objectMapper, AuditService audit) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    public record StrategyRow(long id, long releaseId, int versionNo, String status,
                              Object scope, Object risk, List<Long> envIds,
                              Object roundPlan, String resourceNote, Long ownerId,
                              String ownerName, String createdAt) {
    }

    /** JSON payloads stay {@code Object} so Spring's Jackson 3 converter can bind them. */
    public record StrategyBody(Object scope, Object risk, List<Long> envIds,
                               Object roundPlan, String resourceNote, Long ownerId) {
    }

    @Transactional(readOnly = true)
    public PageResponse<StrategyRow> page(Long releaseId, int page, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.release_id, s.version_no, s.status, s.scope_json, s.risk_json,
                       s.env_ids_json, s.round_plan_json, s.resource_note, s.owner_id,
                       s.created_at, u.name AS owner_name
                FROM test_strategy s
                LEFT JOIN sys_user u ON u.id = s.owner_id
                WHERE /*scope:s*/
                """);
        if (releaseId != null) {
            sql.append(" AND s.release_id = :releaseId");
            params.put("releaseId", releaseId);
        }
        sql.append(" ORDER BY s.release_id DESC, s.version_no DESC");
        PageResponse<Map<String, Object>> rows = jdbcRead.page(sql.toString(), params,
                Math.max(1, page), Math.min(100, Math.max(1, size)));
        List<StrategyRow> items = new ArrayList<>(rows.items().size());
        for (Map<String, Object> row : rows.items()) {
            items.add(toRow(row));
        }
        return new PageResponse<>(items, rows.total(), rows.page(), rows.size());
    }

    @Transactional(readOnly = true)
    public StrategyRow detail(long strategyId) {
        return toRow(requireRow(strategyId));
    }

    @Transactional
    public StrategyRow create(long releaseId, StrategyBody body) {
        DataScope scope = TenantContext.require();
        Map<String, Object> release = jdbcRead.one("""
                SELECT r.domain_id AS domain_id FROM `release` r
                WHERE /*scope:r*/ AND r.id = :releaseId
                """, Map.of("releaseId", releaseId));
        if (release == null) {
            throw new ApiException(404, "RELEASE_NOT_FOUND", "发布不存在或不在你的数据范围内",
                    Map.of("releaseId", releaseId));
        }
        Long domainId = Rows.lng(release, "domain_id");
        long versionNo = nextVersion(releaseId);
        boolean first = versionNo == 1;
        try {
            jdbc.update("""
                    INSERT INTO test_strategy (tenant_id, release_id, version_no, scope_json,
                        risk_json, env_ids_json, round_plan_json, resource_note, owner_id, status,
                        domain_id, vendor_id, created_at, updated_at, created_by, updated_by)
                    VALUES (:tenantId, :releaseId, :versionNo, :scopeJson, :riskJson, :envIds,
                        :roundPlan, :resourceNote, :ownerId, :status, :domainId, :vendorId,
                        NOW(3), NOW(3), :userId, :userId)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", scope.tenantId())
                    .addValue("releaseId", releaseId)
                    .addValue("versionNo", versionNo)
                    .addValue("scopeJson", jsonOf(body == null ? null : body.scope(), "{}"))
                    .addValue("riskJson", jsonOf(body == null ? null : body.risk(), "[]"))
                    .addValue("envIds", jsonOf(body == null ? null : body.envIds(), "[]"))
                    .addValue("roundPlan", jsonOf(body == null ? null : body.roundPlan(), "[]"))
                    .addValue("resourceNote", body == null ? null : body.resourceNote())
                    .addValue("ownerId", body == null || body.ownerId() == null
                            ? scope.userId() : body.ownerId())
                    // the first version of a release is what plans can adopt immediately
                    .addValue("status", first ? "ACTIVE" : "DRAFT")
                    .addValue("domainId", domainId == null ? 0L : domainId)
                    .addValue("vendorId", scope.vendorId())
                    .addValue("userId", scope.userId()));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "ILLEGAL_PLAN_TRANSITION",
                    "策略版本号已被占用，请重试", Map.of("releaseId", releaseId));
        }
        long strategyId = lastId(scope.tenantId(), releaseId, versionNo);
        audit.record("STRATEGY_CREATE", "TEST_STRATEGY", strategyId,
                Map.of("releaseId", releaseId, "versionNo", versionNo));
        return detail(strategyId);
    }

    @Transactional
    public StrategyRow update(long strategyId, StrategyBody body) {
        DataScope scope = TenantContext.require();
        Map<String, Object> row = requireRow(strategyId);
        String status = Rows.str(row, "status", "DRAFT");
        if ("ACTIVE".equals(status)) {
            throw new ApiException(409, "ILLEGAL_PLAN_TRANSITION",
                    "生效中的策略不可修改，请创建新版本", Map.of("strategyId", strategyId,
                            "status", status));
        }
        if ("RETIRED".equals(status)) {
            throw new ApiException(409, "ILLEGAL_PLAN_TRANSITION",
                    "已退役的策略不可修改", Map.of("strategyId", strategyId, "status", status));
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("strategyId", strategyId)
                .addValue("tenantId", scope.tenantId())
                .addValue("userId", scope.userId());
        StringBuilder sql = new StringBuilder("UPDATE test_strategy SET updated_at = NOW(3), "
                + "updated_by = :userId");
        if (body != null && body.scope() != null) {
            sql.append(", scope_json = :scopeJson");
            params.addValue("scopeJson", jsonOf(body.scope(), "{}"));
        }
        if (body != null && body.risk() != null) {
            sql.append(", risk_json = :riskJson");
            params.addValue("riskJson", jsonOf(body.risk(), "[]"));
        }
        if (body != null && body.envIds() != null) {
            sql.append(", env_ids_json = :envIds");
            params.addValue("envIds", jsonOf(body.envIds(), "[]"));
        }
        if (body != null && body.roundPlan() != null) {
            sql.append(", round_plan_json = :roundPlan");
            params.addValue("roundPlan", jsonOf(body.roundPlan(), "[]"));
        }
        if (body != null && body.resourceNote() != null) {
            sql.append(", resource_note = :resourceNote");
            params.addValue("resourceNote", body.resourceNote());
        }
        if (body != null && body.ownerId() != null) {
            sql.append(", owner_id = :ownerId");
            params.addValue("ownerId", body.ownerId());
        }
        sql.append(" WHERE id = :strategyId AND tenant_id = :tenantId");
        jdbc.update(sql.toString(), params);
        audit.record("STRATEGY_UPDATE", "TEST_STRATEGY", strategyId, Map.of());
        return detail(strategyId);
    }

    @Transactional
    public StrategyRow activate(long strategyId) {
        DataScope scope = TenantContext.require();
        Map<String, Object> row = requireRow(strategyId);
        if ("RETIRED".equals(Rows.str(row, "status"))) {
            throw new ApiException(409, "ILLEGAL_PLAN_TRANSITION", "已退役的策略不能重新激活",
                    Map.of("strategyId", strategyId));
        }
        long releaseId = Rows.toLong(Rows.object(row, "release_id"), 0L);
        jdbc.update("""
                UPDATE test_strategy SET status = 'RETIRED', updated_at = NOW(3), updated_by = :userId
                WHERE tenant_id = :tenantId AND release_id = :releaseId AND status = 'ACTIVE'
                """, new MapSqlParameterSource().addValue("userId", scope.userId())
                .addValue("tenantId", scope.tenantId()).addValue("releaseId", releaseId));
        jdbc.update("""
                UPDATE test_strategy SET status = 'ACTIVE', updated_at = NOW(3), updated_by = :userId
                WHERE tenant_id = :tenantId AND id = :strategyId
                """, new MapSqlParameterSource().addValue("userId", scope.userId())
                .addValue("tenantId", scope.tenantId()).addValue("strategyId", strategyId));
        audit.record("STRATEGY_ACTIVATE", "TEST_STRATEGY", strategyId,
                Map.of("releaseId", releaseId));
        return detail(strategyId);
    }

    private long nextVersion(long releaseId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT COALESCE(MAX(s.version_no), 0) AS max_no FROM test_strategy s
                WHERE /*scope:s*/ AND s.release_id = :releaseId
                """, Map.of("releaseId", releaseId));
        return (row == null ? 0 : Rows.toLong(Rows.object(row, "max_no"), 0L)) + 1;
    }

    private Map<String, Object> requireRow(long strategyId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT s.id, s.release_id, s.version_no, s.status, s.scope_json, s.risk_json,
                       s.env_ids_json, s.round_plan_json, s.resource_note, s.owner_id,
                       s.created_at, u.name AS owner_name
                FROM test_strategy s
                LEFT JOIN sys_user u ON u.id = s.owner_id
                WHERE /*scope:s*/ AND s.id = :strategyId
                """, Map.of("strategyId", strategyId));
        if (row == null) {
            throw new ApiException(404, "STRATEGY_NOT_FOUND", "测试策略不存在或不在你的数据范围内",
                    Map.of("strategyId", strategyId));
        }
        return row;
    }

    private long lastId(long tenantId, long releaseId, long versionNo) {
        Map<String, Object> row = jdbc.queryForObject("""
                SELECT id FROM test_strategy
                WHERE tenant_id = :tenantId AND release_id = :releaseId AND version_no = :versionNo
                """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("releaseId", releaseId).addValue("versionNo", versionNo),
                (rs, index) -> Map.<String, Object>of("id", rs.getLong("id")));
        return row == null ? 0 : Rows.toLong(Rows.object(row, "id"), 0L);
    }

    private StrategyRow toRow(Map<String, Object> row) {
        LocalDateTime createdAt = Rows.toDateTime(Rows.object(row, "created_at"));
        return new StrategyRow(
                Rows.toLong(Rows.object(row, "id"), 0L),
                Rows.toLong(Rows.object(row, "release_id"), 0L),
                Rows.toInt(Rows.object(row, "version_no"), 1),
                Rows.str(row, "status", "DRAFT"),
                json(Rows.str(row, "scope_json")),
                json(Rows.str(row, "risk_json")),
                longList(Rows.str(row, "env_ids_json")),
                json(Rows.str(row, "round_plan_json")),
                Rows.str(row, "resource_note"),
                Rows.lng(row, "owner_id"),
                Rows.str(row, "owner_name"),
                createdAt == null ? null : createdAt.toString());
    }

    private Object json(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> longList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = objectMapper.readValue(value, List.class);
            List<Long> out = new ArrayList<>(raw.size());
            for (Object element : raw) {
                if (element instanceof Number number) {
                    out.add(number.longValue());
                }
            }
            return out;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String jsonOf(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(400, "VALIDATION_FAILED", "策略 JSON 不合法",
                    Map.of("error", ex.getOriginalMessage()));
        }
    }

    static Set<String> statuses() {
        return STATUSES;
    }
}
