package com.example.deliver.executor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.example.deliver.support.Rows;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test executor ledger (AC-16).
 *
 * <p>The table stores facts only — {@code enabled} and {@code last_report_at}. Health is derived by
 * {@link ExecutorHealth} so the page, the list and the ingest path cannot disagree about what
 * "stale" means.</p>
 *
 * <p>Ingest resolves a {@code executorCode} through {@link #resolve} but the code never enters the
 * idempotency key: two reports that differ only by executor code stay the same report.</p>
 */
@Service
public class TestExecutorService {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9._-]{1,31}");
    private static final int MAX_REPORT_DAYS = 90;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public TestExecutorService(ScopedJdbc jdbcRead, NamedParameterJdbcTemplate jdbc,
                               AuditService audit) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Wire shape; field names are the frontend contract (web/src/api/deliver.ts). */
    public record ExecutorRow(long id,
                              String code,
                              String name,
                              String baseUrl,
                              Long apiClientId,
                              String apiClientName,
                              boolean enabled,
                              LocalDateTime lastReportAt,
                              int reportCount,
                              String health,
                              LocalDateTime createdAt) {
    }

    public record SaveCmd(Long id,
                          String code,
                          String name,
                          String baseUrl,
                          Long apiClientId,
                          Boolean enabled) {
    }

    public record ReportRow(String date, int count) {
    }

    public record ExecutorList(List<ExecutorRow> items, long total) {
    }

    /** Zero-filled daily buckets; {@code items} matches the list shape the other endpoints use. */
    public record ExecutorReports(List<ReportRow> items, int days) {
    }

    // ------------------------------------------------------------------ ledger

    @Transactional(readOnly = true)
    public ExecutorList list() {
        TenantContext.require();
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT e.id AS id, e.code AS code, e.name AS name, e.base_url AS base_url,
                       e.api_client_id AS api_client_id, a.name AS api_client_name,
                       e.enabled AS enabled, e.last_report_at AS last_report_at,
                       e.report_count AS report_count, e.created_at AS created_at
                FROM test_executor e
                LEFT JOIN api_client a ON a.id = e.api_client_id AND a.tenant_id = e.tenant_id
                WHERE /*scope:e*/
                ORDER BY e.id
                """, Map.of());
        List<ExecutorRow> items = rows.stream().map(TestExecutorService::toView).toList();
        return new ExecutorList(items, items.size());
    }

    /**
     * Create or update an executor. {@code code} is the ingest handle the CI side already ships, so
     * it is immutable once created — renaming it silently would turn every configured pipeline into
     * an {@code EXECUTOR_UNKNOWN}.
     */
    @Transactional
    public ExecutorRow save(SaveCmd cmd) {
        DataScope scope = TenantContext.require();
        requireManage();
        if (cmd == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "请求体不能为空", Map.of());
        }
        long tenantId = scope.tenantId();
        String name = trimToNull(cmd.name());
        if (name == null || name.length() > 100) {
            throw new ApiException(400, "VALIDATION_FAILED", "执行器名称不能为空且不超过 100 字",
                    Map.of("field", "name"));
        }
        String baseUrl = trimToNull(cmd.baseUrl());
        if (baseUrl != null && baseUrl.length() > 200) {
            throw new ApiException(400, "VALIDATION_FAILED", "地址不能超过 200 字",
                    Map.of("field", "baseUrl"));
        }
        Long apiClientId = cmd.apiClientId();
        if (apiClientId != null) {
            List<Map<String, Object>> client = jdbc.queryForList("""
                    SELECT id FROM api_client WHERE id = :id AND tenant_id = :tenantId
                    """, Map.of("id", apiClientId, "tenantId", tenantId));
            if (client.isEmpty()) {
                throw new ApiException(400, "VALIDATION_FAILED", "绑定的 API 客户端不存在",
                        Map.of("field", "apiClientId"));
            }
        }

        if (cmd.id() != null) {
            Map<String, Object> existing = requireExecutor(cmd.id(), tenantId);
            boolean enabled = cmd.enabled() == null
                    ? Rows.toInt(Rows.object(existing, "enabled"), 1) == 1 : cmd.enabled();
            // Map.of rejects null values, and api_client_id is nullable by design (an
            // executor may report without a bound open-API client) — build the map instead.
            Map<String, Object> params = new HashMap<>();
            params.put("name", name);
            params.put("baseUrl", baseUrl == null ? "" : baseUrl);
            params.put("apiClientId", apiClientId);
            params.put("enabled", enabled ? 1 : 0);
            params.put("id", cmd.id());
            params.put("tenantId", tenantId);
            jdbc.update("""
                    UPDATE test_executor
                    SET name = :name, base_url = :baseUrl, api_client_id = :apiClientId,
                        enabled = :enabled
                    WHERE id = :id AND tenant_id = :tenantId
                    """, params);
            audit.record("TEST_EXECUTOR_UPDATE", "TEST_EXECUTOR", cmd.id(),
                    Map.of("code", Rows.str(existing, "code"),
                            "enabled", enabled));
            return find(cmd.id(), tenantId);
        }

        String code = normaliseCode(cmd.code());
        List<Map<String, Object>> duplicate = jdbc.queryForList("""
                SELECT id FROM test_executor WHERE tenant_id = :tenantId AND code = :code
                """, Map.of("tenantId", tenantId, "code", code));
        if (!duplicate.isEmpty()) {
            throw new ApiException(409, "EXECUTOR_CODE_DUPLICATE",
                    "执行器编码已存在", Map.of("code", code));
        }
        boolean enabled = cmd.enabled() == null || cmd.enabled();
        Map<String, Object> insert = new HashMap<>();
        insert.put("tenantId", tenantId);
        insert.put("domainId", scope.domainIds().stream().findFirst().orElse(null));
        insert.put("code", code);
        insert.put("name", name);
        insert.put("baseUrl", baseUrl == null ? "" : baseUrl);
        insert.put("apiClientId", apiClientId);
        insert.put("enabled", enabled ? 1 : 0);
        jdbc.update("""
                INSERT INTO test_executor (tenant_id, domain_id, code, name, base_url, api_client_id,
                                           enabled, report_count)
                VALUES (:tenantId, :domainId, :code, :name, :baseUrl, :apiClientId, :enabled, 0)
                """, insert);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
        audit.record("TEST_EXECUTOR_CREATE", "TEST_EXECUTOR", id,
                Map.of("code", code, "enabled", enabled));
        return find(id == null ? 0L : id, tenantId);
    }

    /** Disabling keeps the row (history stays attributable) and makes the next report 422. */
    @Transactional
    public ExecutorRow disable(long id) {
        DataScope scope = TenantContext.require();
        requireManage();
        long tenantId = scope.tenantId();
        Map<String, Object> existing = requireExecutor(id, tenantId);
        jdbc.update("""
                UPDATE test_executor SET enabled = 0 WHERE id = :id AND tenant_id = :tenantId
                """, Map.of("id", id, "tenantId", tenantId));
        audit.record("TEST_EXECUTOR_DISABLE", "TEST_EXECUTOR", id,
                Map.of("code", Rows.str(existing, "code")));
        return find(id, tenantId);
    }

    /** Daily report buckets for the last {@code days} days, zero-filled so the chart has a shape. */
    @Transactional(readOnly = true)
    public ExecutorReports reports(long id, int days) {
        DataScope scope = TenantContext.require();
        long tenantId = scope.tenantId();
        requireExecutor(id, tenantId);
        int window = days <= 0 ? 7 : Math.min(days, MAX_REPORT_DAYS);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(window - 1L);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DATE(executed_at) AS day, COUNT(*) AS cnt
                FROM test_execution
                WHERE tenant_id = :tenantId AND executor_id = :id AND executed_at >= :from
                GROUP BY DATE(executed_at)
                """, Map.of("tenantId", tenantId, "id", id, "from", from.atStartOfDay()));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(String.valueOf(Rows.object(row, "day")),
                    Rows.toInt(Rows.object(row, "cnt"), 0));
        }
        List<ReportRow> result = new ArrayList<>(window);
        for (int i = 0; i < window; i++) {
            String day = DAY.format(from.plusDays(i));
            result.add(new ReportRow(day, counts.getOrDefault(day, 0)));
        }
        return new ExecutorReports(result, window);
    }

    // ------------------------------------------------------------------ ingest side

    /**
     * Resolve the executor a report belongs to, or {@code null} when the caller did not name one and
     * no ledger row is bound to the client (existing CI keeps working with a null executor).
     *
     * @throws ApiException 422 {@code EXECUTOR_UNKNOWN} / {@code EXECUTOR_DISABLED}
     */
    @Transactional(readOnly = true)
    public Long resolve(long tenantId, String executorCode, long clientId) {
        String code = trimToNull(executorCode);
        if (code == null) {
            List<Map<String, Object>> bound = jdbc.queryForList("""
                    SELECT id FROM test_executor
                    WHERE tenant_id = :tenantId AND api_client_id = :clientId AND enabled = 1
                    ORDER BY id LIMIT 1
                    """, Map.of("tenantId", tenantId, "clientId", clientId));
            return bound.isEmpty() ? null : Rows.lng(bound.get(0), "id");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, enabled FROM test_executor
                WHERE tenant_id = :tenantId AND code = :code
                """, Map.of("tenantId", tenantId, "code", code.toUpperCase(Locale.ROOT)));
        if (rows.isEmpty()) {
            throw new ApiException(422, "EXECUTOR_UNKNOWN",
                    "执行器编码未登记", Map.of("executorCode", code));
        }
        if (Rows.toInt(Rows.object(rows.get(0), "enabled"), 1) != 1) {
            throw new ApiException(422, "EXECUTOR_DISABLED",
                    "执行器已停用", Map.of("executorCode", code));
        }
        return Rows.lng(rows.get(0), "id");
    }

    /**
     * Count one report on the ledger. Called from the ingest transaction so the ledger and the
     * execution row commit together; {@code GREATEST} keeps a retried older report from moving
     * "last report" backwards.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void touch(long tenantId, long executorId, LocalDateTime at) {
        jdbc.update("""
                UPDATE test_executor
                SET last_report_at = GREATEST(COALESCE(last_report_at, :at), :at),
                    report_count = report_count + 1
                WHERE id = :id AND tenant_id = :tenantId
                """, Map.of("at", at, "id", executorId, "tenantId", tenantId));
    }

    /** Attach the executor to the freshly written execution row (writer keeps its own transaction). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markExecution(long tenantId, long executionId, Long executorId) {
        if (executorId == null) {
            return;
        }
        jdbc.update("""
                UPDATE test_execution SET executor_id = :executorId
                WHERE id = :id AND tenant_id = :tenantId
                """, Map.of("executorId", executorId, "id", executionId, "tenantId", tenantId));
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> requireExecutor(long id, long tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, code, enabled, name, base_url, api_client_id
                FROM test_executor WHERE id = :id AND tenant_id = :tenantId
                """, Map.of("id", id, "tenantId", tenantId));
        if (rows.isEmpty()) {
            throw new ApiException(404, "EXECUTOR_NOT_FOUND", "执行器不存在", Map.of("id", id));
        }
        return rows.get(0);
    }

    private ExecutorRow find(long id, long tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.id AS id, e.code AS code, e.name AS name, e.base_url AS base_url,
                       e.api_client_id AS api_client_id, a.name AS api_client_name,
                       e.enabled AS enabled, e.last_report_at AS last_report_at,
                       e.report_count AS report_count, e.created_at AS created_at
                FROM test_executor e
                LEFT JOIN api_client a ON a.id = e.api_client_id AND a.tenant_id = e.tenant_id
                WHERE e.id = :id AND e.tenant_id = :tenantId
                """, Map.of("id", id, "tenantId", tenantId));
        if (rows.isEmpty()) {
            throw new ApiException(404, "EXECUTOR_NOT_FOUND", "执行器不存在", Map.of("id", id));
        }
        return toView(rows.get(0));
    }

    private static ExecutorRow toView(Map<String, Object> row) {
        boolean enabled = Rows.toInt(Rows.object(row, "enabled"), 1) == 1;
        LocalDateTime lastReportAt = Rows.toDateTime(Rows.object(row, "last_report_at"));
        return new ExecutorRow(
                Rows.toLong(Rows.object(row, "id"), 0L),
                Rows.str(row, "code"),
                Rows.str(row, "name"),
                Rows.str(row, "base_url"),
                Rows.lng(row, "api_client_id"),
                Rows.str(row, "api_client_name"),
                enabled,
                lastReportAt,
                Rows.toInt(Rows.object(row, "report_count"), 0),
                ExecutorHealth.of(enabled, lastReportAt, LocalDateTime.now()),
                Rows.toDateTime(Rows.object(row, "created_at")));
    }

    private static String normaliseCode(String raw) {
        String code = trimToNull(raw);
        if (code == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "执行器编码不能为空",
                    Map.of("field", "code"));
        }
        String upper = code.toUpperCase(Locale.ROOT);
        if (!CODE.matcher(upper).matches()) {
            throw new ApiException(400, "VALIDATION_FAILED",
                    "编码需为 2–32 位大写字母/数字/.-_，且以字母或数字开头",
                    Map.of("field", "code", "value", raw));
        }
        return upper;
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void requireManage() {
        if (!TenantContext.can("TEST_EXECUTOR_MANAGE")) {
            throw new ApiException(403, "FORBIDDEN", "无权管理执行器",
                    Map.of("function", "TEST_EXECUTOR_MANAGE"));
        }
    }
}
