package com.example.deliver.quality.env;

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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test environment ledger (AC-9). Environments are shared by plans; a name is unique per
 * tenant + domain, and an environment that a live plan points at refuses deletion — the plan would
 * otherwise execute against a dangling id.
 */
@Service
public class TestEnvService {

    /** PRD §4.6 / DESIGN §6.9：环境类型与状态是前后端共用的字典，值必须逐字一致。 */
    private static final Set<String> ENV_TYPES = Set.of("DEV", "TEST", "STAGING", "PROD");
    private static final Set<String> ENV_STATUSES = Set.of("AVAILABLE", "MAINTENANCE", "DISABLED");
    private static final List<String> LIVE_PLAN_STATUSES =
            List.of("DRAFT", "PENDING_REVIEW", "APPROVED", "IN_PROGRESS");

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public TestEnvService(ScopedJdbc jdbcRead, NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record EnvRow(long id, String name, String envType, String baseUrl, Long ownerId,
                         String ownerName, String status, Long domainId, String note,
                         String createdAt) {
    }

    public record EnvBody(String name, String envType, String baseUrl, Long ownerId,
                          String status, Long domainId, String note) {
    }

    @Transactional(readOnly = true)
    public PageResponse<EnvRow> page(String status, String keyword, int page, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.name, e.env_type, e.base_url, e.owner_id, e.status, e.domain_id,
                       e.note, e.created_at, u.name AS owner_name
                FROM test_env e
                LEFT JOIN sys_user u ON u.id = e.owner_id
                WHERE /*scope:e*/
                """);
        if (status != null && !status.isBlank()) {
            sql.append(" AND e.status = :status");
            params.put("status", status.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (e.name LIKE :keyword OR e.base_url LIKE :keyword)");
            params.put("keyword", "%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY e.id DESC");
        PageResponse<Map<String, Object>> rows =
                jdbcRead.page(sql.toString(), params, Math.max(1, page), Math.min(100, Math.max(1, size)));
        List<EnvRow> items = new ArrayList<>(rows.items().size());
        for (Map<String, Object> row : rows.items()) {
            items.add(toRow(row));
        }
        return new PageResponse<>(items, rows.total(), rows.page(), rows.size());
    }

    @Transactional(readOnly = true)
    public EnvRow detail(long envId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT e.id, e.name, e.env_type, e.base_url, e.owner_id, e.status, e.domain_id,
                       e.note, e.created_at, u.name AS owner_name
                FROM test_env e
                LEFT JOIN sys_user u ON u.id = e.owner_id
                WHERE /*scope:e*/ AND e.id = :envId
                """, Map.of("envId", envId));
        if (row == null) {
            throw new ApiException(404, "ENV_NOT_FOUND", "测试环境不存在或不在你的数据范围内",
                    Map.of("envId", envId));
        }
        return toRow(row);
    }

    @Transactional
    public EnvRow create(EnvBody body) {
        DataScope scope = TenantContext.require();
        EnvBody cmd = validate(body);
        Long domainId = cmd.domainId() != null ? cmd.domainId()
                : (scope.domainIds().isEmpty() ? Long.valueOf(0L)
                        : scope.domainIds().iterator().next());
        try {
            jdbc.update("""
                    INSERT INTO test_env (tenant_id, name, env_type, base_url, owner_id, status,
                        domain_id, vendor_id, note, created_at, updated_at)
                    VALUES (:tenantId, :name, :envType, :baseUrl, :ownerId, :status, :domainId,
                        :vendorId, :note, NOW(3), NOW(3))
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", scope.tenantId())
                    .addValue("name", cmd.name())
                    .addValue("envType", cmd.envType())
                    .addValue("baseUrl", cmd.baseUrl())
                    .addValue("ownerId", cmd.ownerId() == null ? scope.userId() : cmd.ownerId())
                    .addValue("status", cmd.status() == null ? "AVAILABLE" : cmd.status())
                    .addValue("domainId", domainId)
                    .addValue("vendorId", scope.vendorId())
                    .addValue("note", cmd.note()));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "ENV_IN_USE", "同一域下环境名称已存在",
                    Map.of("reason", "NAME_TAKEN", "name", cmd.name()));
        }
        long envId = lastId(scope.tenantId(), cmd.name());
        audit.record("ENV_CREATE", "TEST_ENV", envId, Map.of("name", cmd.name(),
                "envType", cmd.envType()));
        return detail(envId);
    }

    @Transactional
    public EnvRow update(long envId, EnvBody body) {
        DataScope scope = TenantContext.require();
        Map<String, Object> existing = jdbcRead.one("""
                SELECT e.id, e.status FROM test_env e WHERE /*scope:e*/ AND e.id = :envId
                """, Map.of("envId", envId));
        if (existing == null) {
            throw new ApiException(404, "ENV_NOT_FOUND", "测试环境不存在或不在你的数据范围内",
                    Map.of("envId", envId));
        }
        EnvBody cmd = validate(body);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("envId", envId)
                .addValue("tenantId", scope.tenantId())
                .addValue("updatedBy", scope.userId());
        StringBuilder sql = new StringBuilder("UPDATE test_env SET updated_at = NOW(3), "
                + "updated_by = :updatedBy");
        if (cmd.name() != null) {
            sql.append(", name = :name");
            params.addValue("name", cmd.name());
        }
        if (cmd.envType() != null) {
            sql.append(", env_type = :envType");
            params.addValue("envType", cmd.envType());
        }
        if (cmd.baseUrl() != null) {
            sql.append(", base_url = :baseUrl");
            params.addValue("baseUrl", cmd.baseUrl());
        }
        if (cmd.ownerId() != null) {
            sql.append(", owner_id = :ownerId");
            params.addValue("ownerId", cmd.ownerId());
        }
        if (cmd.status() != null) {
            sql.append(", status = :status");
            params.addValue("status", cmd.status());
        }
        if (cmd.note() != null) {
            sql.append(", note = :note");
            params.addValue("note", cmd.note());
        }
        sql.append(" WHERE id = :envId AND tenant_id = :tenantId");
        try {
            jdbc.update(sql.toString(), params);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "ENV_IN_USE", "同一域下环境名称已存在",
                    Map.of("reason", "NAME_TAKEN", "name", cmd.name()));
        }
        audit.record("ENV_UPDATE", "TEST_ENV", envId, Map.of("fields", cmd.toString()));
        return detail(envId);
    }

    @Transactional
    public void delete(long envId) {
        DataScope scope = TenantContext.require();
        detail(envId);
        List<Map<String, Object>> plans = jdbcRead.list("""
                SELECT p.id FROM test_plan p
                WHERE /*scope:p*/ AND p.env_id = :envId AND p.status IN (:statuses)
                ORDER BY p.id
                """, Map.of("envId", envId, "statuses", LIVE_PLAN_STATUSES));
        if (!plans.isEmpty()) {
            List<Long> planIds = plans.stream()
                    .map(row -> Rows.toLong(Rows.object(row, "id"), 0L)).toList();
            throw new ApiException(409, "ENV_IN_USE", "环境仍被未结束的测试计划引用",
                    Map.of("reason", "PLAN_REFERENCES", "planIds", planIds));
        }
        jdbc.update("DELETE FROM test_env WHERE tenant_id = :tenantId AND id = :envId",
                new MapSqlParameterSource().addValue("tenantId", scope.tenantId())
                        .addValue("envId", envId));
        audit.record("ENV_DELETE", "TEST_ENV", envId, Map.of());
    }

    private EnvBody validate(EnvBody body) {
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "环境名称必填",
                    Map.of("field", "name"));
        }
        String envType = body.envType() == null || body.envType().isBlank()
                ? "TEST" : body.envType().trim().toUpperCase();
        if (!ENV_TYPES.contains(envType)) {
            throw new ApiException(400, "VALIDATION_FAILED", "环境类型不合法",
                    Map.of("field", "envType", "allowed", ENV_TYPES));
        }
        String status = body.status() == null || body.status().isBlank()
                ? null : body.status().trim().toUpperCase();
        if (status != null && !ENV_STATUSES.contains(status)) {
            throw new ApiException(400, "VALIDATION_FAILED", "环境状态不合法",
                    Map.of("field", "status", "allowed", ENV_STATUSES));
        }
        if (body.baseUrl() != null && !body.baseUrl().isBlank()
                && !body.baseUrl().startsWith("http")) {
            throw new ApiException(400, "VALIDATION_FAILED", "环境地址必须以 http(s) 开头",
                    Map.of("field", "baseUrl"));
        }
        return new EnvBody(body.name().trim(), envType,
                body.baseUrl() == null || body.baseUrl().isBlank() ? null : body.baseUrl().trim(),
                body.ownerId(), status, body.domainId(),
                body.note() == null || body.note().isBlank() ? null : body.note().trim());
    }

    private long lastId(long tenantId, String name) {
        Map<String, Object> row = jdbc.queryForObject("""
                SELECT id FROM test_env WHERE tenant_id = :tenantId AND name = :name
                ORDER BY id DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("name", name), (rs, index) -> Map.<String, Object>of("id", rs.getLong("id")));
        return row == null ? 0 : Rows.toLong(Rows.object(row, "id"), 0L);
    }

    private EnvRow toRow(Map<String, Object> row) {
        LocalDateTime createdAt = Rows.toDateTime(Rows.object(row, "created_at"));
        return new EnvRow(
                Rows.toLong(Rows.object(row, "id"), 0L),
                Rows.str(row, "name"),
                Rows.str(row, "env_type"),
                Rows.str(row, "base_url"),
                Rows.lng(row, "owner_id"),
                Rows.str(row, "owner_name"),
                Rows.str(row, "status", "AVAILABLE"),
                Rows.lng(row, "domain_id"),
                Rows.str(row, "note"),
                createdAt == null ? null : createdAt.toString());
    }
}
