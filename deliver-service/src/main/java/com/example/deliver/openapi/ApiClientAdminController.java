package com.example.deliver.openapi;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.HmacSigner;

/**
 * 开放 API 客户端管理（AC-17 / TECHNICAL §4.3 的 {@code /admin/api-clients}）。
 *
 * <p>为什么落在这里而不是 req-service：{@code api_client} 表由 deliver-service 拥有，验签也在
 * {@link HmacAuthFilter} 内消费它。跨服务写别人的表会破坏「一表一写者」，也让密钥编码格式漂移
 * （{@link ClientSecretCodec} 的 v1 线格式必须与写者一致）。因此管理端点与读取端放在同一进程。
 * 权限不在本类重复判断：{@code PermissionRouteTable} 已把 {@code /admin/api-clients*} 映射到
 * {@code apiclient:manage}。</p>
 *
 * <p>明文 secret 只在创建/轮换的响应里出现一次（D1）；之后只能凭 {@code secret_hash} 审计。</p>
 */
@RestController
@RequestMapping("/admin/api-clients")
public class ApiClientAdminController {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ClientSecretCodec codec;

    public ApiClientAdminController(JdbcTemplate jdbc, ClientSecretCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public record CreateClient(String name, List<String> scopes, List<String> ipWhitelist, Integer rateLimitQps) {
    }

    public record StatusRequest(String status) {
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        long tenantId = tenant();
        return jdbc.query("""
                SELECT id, name, app_key, scopes, ip_whitelist, rate_limit_qps, status, created_at
                FROM api_client WHERE tenant_id = ? ORDER BY id DESC
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("name", rs.getString("name"));
            row.put("appKey", rs.getString("app_key"));
            row.put("scopes", split(rs.getString("scopes")));
            row.put("ipWhitelist", split(rs.getString("ip_whitelist")));
            row.put("rateLimitQps", rs.getInt("rate_limit_qps"));
            row.put("status", rs.getString("status"));
            row.put("createdAt", String.valueOf(rs.getTimestamp("created_at")));
            return row;
        }, tenantId);
    }

    /** 201 {@code {id, appKey, secret}}；secret 只此一次。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateClient request) {
        DataScope scope = TenantContext.require();
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new ApiException(400, "VALIDATION_FAILED", "客户端名称必填", Map.of("field", "name"));
        }
        String appKey = "AK" + random(12);
        String secret = random(24);
        int qps = request.rateLimitQps() == null ? 100 : Math.max(1, request.rateLimitQps());
        jdbc.update("""
                INSERT INTO api_client (tenant_id, name, app_key, secret_hash, secret_enc, scopes,
                                        ip_whitelist, rate_limit_qps, status, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, scope.tenantId(), name, appKey, HmacSigner.sha256Hex(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                codec.encode(secret), join(request.scopes()), join(request.ipWhitelist()), qps,
                STATUS_ACTIVE, scope.userId(), scope.userId());
        Long id = jdbc.queryForObject(
                "SELECT id FROM api_client WHERE tenant_id = ? AND app_key = ?", Long.class, scope.tenantId(), appKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("appKey", appKey);
        body.put("secret", secret);
        body.put("status", STATUS_ACTIVE);
        return ResponseEntity.status(201).body(body);
    }

    /** 轮换：新 secret 立即生效，旧 secret 失效（响应同样只回一次明文）。 */
    @PostMapping("/{id}/rotate")
    public Map<String, Object> rotate(@PathVariable long id) {
        DataScope scope = TenantContext.require();
        Map<String, Object> row = requireClient(scope.tenantId(), id);
        String secret = random(24);
        jdbc.update("UPDATE api_client SET secret_hash = ?, secret_enc = ?, updated_by = ? WHERE id = ? AND tenant_id = ?",
                HmacSigner.sha256Hex(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                codec.encode(secret), scope.userId(), id, scope.tenantId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("appKey", row.get("appKey"));
        body.put("secret", secret);
        return body;
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable long id, @RequestBody StatusRequest request) {
        DataScope scope = TenantContext.require();
        requireClient(scope.tenantId(), id);
        String status = request.status() == null ? "" : request.status().trim().toUpperCase(Locale.ROOT);
        if (!STATUS_ACTIVE.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new ApiException(400, "VALIDATION_FAILED", "status 只能是 ACTIVE/DISABLED",
                    Map.of("field", "status"));
        }
        jdbc.update("UPDATE api_client SET status = ?, updated_by = ? WHERE id = ? AND tenant_id = ?",
                status, scope.userId(), id, scope.tenantId());
        return Map.of("id", id, "status", status);
    }

    private Map<String, Object> requireClient(long tenantId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, app_key FROM api_client WHERE id = ? AND tenant_id = ?", id, tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "NOT_FOUND", "客户端不存在", Map.of("id", id));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rows.get(0).get("id"));
        row.put("appKey", rows.get(0).get("app_key"));
        return row;
    }

    private static long tenant() {
        return TenantContext.require().tenantId();
    }

    private static String random(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(","));
    }
}
