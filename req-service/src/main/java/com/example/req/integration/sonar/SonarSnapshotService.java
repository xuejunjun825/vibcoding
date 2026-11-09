package com.example.req.integration.sonar;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.integration.adapter.AdapterRegistry;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationConfigRepository;
import com.example.req.integration.config.IntegrationProperties;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.release.ReleaseIntegrationSettingsService;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sonar 快照的取数与读取（TECHNICAL §7.2 ⑧ / AC-6）。
 *
 * <p>快照复用 M6 的 {@code quality_gate_snapshot}，靠 V8 加的 {@code source} 维度区分：
 * {@code source='SONAR'} 的行 {@code gate_id}/{@code deploy_order_id} 为 NULL，
 * M6 所有带这两列的查询因此结构上排除它们，第 6 项语义零改动。写入只增不改——
 * 历史回放必须看到"当时判定的是哪一份快照"（AC-6.6）。</p>
 *
 * <p>幂等靠 hash：同一 projectKey + 同一门禁结论 + 同一组五度量 = 同一事实，只复用最新一行
 * 的 id，不新增。比较只针对<b>最新</b>一行：若 Sonar 从 ERROR 回到 OK，即使 OK 曾在更早的
 * 历史里出现过，也要落一条新事实（不能把结论"回滚"成旧行）。</p>
 *
 * <p>准入路径零外呼：本类只在「手动刷新 / 30 分钟定时刷新」时访问 Sonar，准入门禁读的是
 * 这里落下的本地行（{@code IntegrationEvidenceReader}）。</p>
 */
@Service
public class SonarSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SonarSnapshotService.class);

    static final String SOURCE = "SONAR";
    static final String ACTION_PULLED = "SONAR_PULLED";
    static final String BIZ_TYPE = "REQUIREMENT";

    /**
     * 同一需求的并发刷新用分段锁串行化：hash 幂等是「先读最新再插入」，两个线程同时读到
     * 同一旧行就会各插一条相同 hash 的事实（表上没有唯一键可挡）。分段锁足够单实例部署，
     * 与 TECHNICAL「不做分布式锁」的前提一致，且不把不同需求互相阻塞。
     */
    private static final int LOCK_STRIPES = 64;

    private static final String LATEST_SQL = """
            SELECT id, project_key, result, metric_status, metrics_json, snapshot_json,
                   hash, computed_at
              FROM quality_gate_snapshot
             WHERE tenant_id = ? AND source = 'SONAR' AND requirement_id = ?
             ORDER BY id DESC
             LIMIT 1
            """;

    private static final String INSERT_SQL = """
            INSERT INTO quality_gate_snapshot
              (tenant_id, source, gate_id, release_id, deploy_order_id, report_version_id,
               requirement_id, project_key, metrics_json, metric_status, result, snapshot_json,
               hash, computed_at, created_by)
            VALUES (?, 'SONAR', NULL, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final SonarAdapter adapter;
    private final AdapterRegistry adapterRegistry;
    private final IntegrationConfigRepository configRepository;
    private final RequirementService requirementService;
    private final ReleaseIntegrationSettingsService settingsService;
    private final AuditService auditService;
    private final IntegrationProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Object[] locks = new Object[LOCK_STRIPES];

    public SonarSnapshotService(SonarAdapter adapter,
                                AdapterRegistry adapterRegistry,
                                IntegrationConfigRepository configRepository,
                                RequirementService requirementService,
                                ReleaseIntegrationSettingsService settingsService,
                                AuditService auditService,
                                IntegrationProperties properties,
                                JdbcTemplate jdbc,
                                ObjectMapper objectMapper) {
        this.adapter = adapter;
        this.adapterRegistry = adapterRegistry;
        this.configRepository = configRepository;
        this.requirementService = requirementService;
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.properties = properties;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    // ------------------------------------------------------------------ refresh

    /**
     * 拉一次 Sonar 并落快照（AC-6.2）。Sonar 未启用 → 409；未绑定 key → 200 + 具体 SKIP 卡，
     * 不把"没配"当成错误弹窗；外呼失败 → 200 + UNAVAILABLE 卡（fail-closed 由准入读取判定）。
     */
    public SonarViews.SonarCard refresh(long requirementId) {
        DataScope scope = TenantContext.require();
        RequirementEntity requirement = requirementService.requireVisible(requirementId);
        long tenantId = tenantOf(requirement, scope);

        IntegrationConfigEntity config = enabledConfig(tenantId);
        if (config == null) {
            throw disabled(tenantId);
        }
        String projectKey = projectKey(requirement);
        if (projectKey == null) {
            return SonarViews.SonarCardFactory.skip(null, SonarViews.REASON_NOT_CONFIGURED,
                    SonarViews.SKIP_NO_KEY);
        }

        SonarViews.SonarMeasures measures = adapter.measures(config, projectKey, requirementId);
        if (measures.degraded()) {
            log.warn("Sonar 刷新失败 tenant={} requirement={} key={} code={}", tenantId,
                    requirementId, projectKey, measures.errorCode());
            return degraded(tenantId, requirementId, projectKey, measures);
        }
        List<String> missing = SonarThresholds.missing(measures.metrics());
        if (!missing.isEmpty()) {
            log.warn("Sonar 度量不完整 tenant={} requirement={} missing={}", tenantId,
                    requirementId, missing);
            return SonarViews.SonarCardFactory.unavailable(projectKey,
                    SonarViews.REASON_METRIC_MISSING,
                    "Sonar 只返回了部分度量，缺少 " + String.join("、", missing) + "，按失败处理",
                    null, null, null, measures.metrics(), List.of(), true, true);
        }

        String status = SonarThresholds.upper(measures.gateStatus());
        String metricStatus = metricStatus(measures.metrics(), status);
        String hash = hash(projectKey, status, measures.metrics());
        Map<String, String> metrics = sorted(measures.metrics());
        List<SonarViews.ConditionView> conditions = conditionsFor(status, measures.conditions(), metrics);

        long snapshotId;
        LocalDateTime fetchedAt;
        synchronized (lockFor(tenantId, requirementId)) {
            Snapshot latest = readLatest(tenantId, requirementId);
            if (latest != null && hash.equals(latest.hash())) {
                snapshotId = latest.id();
                fetchedAt = latest.computedAt();
            } else {
                fetchedAt = LocalDateTime.now();
                snapshotId = insert(tenantId, requirement, projectKey, status, metricStatus,
                        metrics, conditions, hash, fetchedAt);
            }
        }
        auditService.record(ACTION_PULLED, BIZ_TYPE, requirementId, Map.of(
                "projectKey", projectKey, "status", status, "snapshotId", snapshotId, "hash", hash));
        return SonarViews.SonarCardFactory.snapshot(projectKey, status, metrics, conditions,
                snapshotId, hash, fetchedAt, false, false, null, null);
    }

    // ------------------------------------------------------------------ read

    /** 需求详情卡：只读本地事实，绝不外呼（页面首屏预算）。 */
    public SonarViews.SonarCard latest(long requirementId) {
        DataScope scope = TenantContext.require();
        RequirementEntity requirement = requirementService.requireVisible(requirementId);
        long tenantId = tenantOf(requirement, scope);

        IntegrationConfigEntity config = enabledConfig(tenantId);
        if (config == null) {
            return anySonarConfig(tenantId)
                    ? SonarViews.SonarCardFactory.skip(null, SonarViews.REASON_DISABLED,
                            SonarViews.SKIP_DISABLED)
                    : SonarViews.SonarCardFactory.skip(null, SonarViews.REASON_NOT_CONFIGURED,
                            SonarViews.SKIP_NOT_CONFIGURED);
        }
        String projectKey = projectKey(requirement);
        if (projectKey == null) {
            return SonarViews.SonarCardFactory.skip(null, SonarViews.REASON_NOT_CONFIGURED,
                    SonarViews.SKIP_NO_KEY);
        }

        Snapshot snapshot = readLatest(tenantId, requirementId);
        if (snapshot == null) {
            return SonarViews.SonarCardFactory.unavailable(projectKey,
                    SonarViews.REASON_SNAPSHOT_MISSING,
                    "Sonar 已启用且已绑定 projectKey=" + projectKey + "，但还没有快照；"
                            + "等待后台刷新或手动刷新，准入第 8 项在快照出现前计为失败",
                    null, null, null, Map.of(), List.of(), true, false);
        }
        Map<String, String> metrics = parseMetrics(snapshot.metricsJson());
        List<SonarViews.ConditionView> conditions =
                conditionsFor(snapshot.result(), parseConditions(snapshot.snapshotJson()), metrics);
        boolean stale = isStale(snapshot.computedAt());
        return SonarViews.SonarCardFactory.snapshot(
                snapshot.projectKey() == null ? projectKey : snapshot.projectKey(),
                SonarThresholds.upper(snapshot.result()), metrics, conditions,
                snapshot.id(), snapshot.hash(), snapshot.computedAt(), stale, false, null, null);
    }

    /** 准入第 ⑧ 项的只读摘要；判定仍由 {@code IntegrationEvidenceReader} 在准入装配层完成。 */
    public SonarViews.SonarGate gate(long requirementId) {
        SonarViews.SonarCard card = latest(requirementId);
        return new SonarViews.SonarGate(card.status(), card.reasonCode(), card.stale(),
                card.snapshotId());
    }

    // ------------------------------------------------------------------ snapshot rows

    private record Snapshot(long id,
                            String projectKey,
                            String result,
                            String metricStatus,
                            String metricsJson,
                            String snapshotJson,
                            String hash,
                            LocalDateTime computedAt) {
    }

    private Snapshot readLatest(long tenantId, long requirementId) {
        List<Map<String, Object>> rows = jdbc.queryForList(LATEST_SQL, tenantId, requirementId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        return new Snapshot(longOf(row.get("id")), string(row.get("project_key")),
                string(row.get("result")), string(row.get("metric_status")),
                string(row.get("metrics_json")), string(row.get("snapshot_json")),
                string(row.get("hash")), dateTime(row.get("computed_at")));
    }

    private long insert(long tenantId,
                        RequirementEntity requirement,
                        String projectKey,
                        String status,
                        String metricStatus,
                        Map<String, String> metrics,
                        List<SonarViews.ConditionView> conditions,
                        String hash,
                        LocalDateTime fetchedAt) {
        String metricsJson = objectMapper.writeValueAsString(metrics);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectKey", projectKey);
        payload.put("status", status);
        payload.put("metricStatus", metricStatus);
        payload.put("metrics", metrics);
        payload.put("conditions", conditions);
        String snapshotJson = objectMapper.writeValueAsString(payload);

        Long releaseId = requirement.getReleaseId();
        Long createdBy = TenantContext.principal() == null ? null : TenantContext.principal().userId();

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_SQL, new String[]{"id"});
            ps.setLong(1, tenantId);
            // release_id 在 V5 是 NOT NULL；需求尚未纳入版本时写 0——
            // 该行的 source='SONAR'，M6 的版本查询按 (tenant_id, release_id) 永远看不到 0。
            ps.setLong(2, releaseId == null ? 0L : releaseId);
            ps.setLong(3, requirement.getId());
            ps.setString(4, projectKey);
            ps.setString(5, metricsJson);
            ps.setString(6, metricStatus);
            ps.setString(7, status);
            ps.setString(8, snapshotJson);
            ps.setString(9, hash);
            ps.setObject(10, fetchedAt);
            if (createdBy == null) {
                ps.setNull(11, Types.BIGINT);
            } else {
                ps.setLong(11, createdBy);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    // ------------------------------------------------------------------ card assembly

    private SonarViews.SonarCard degraded(long tenantId, long requirementId, String projectKey,
                                          SonarViews.SonarMeasures measures) {
        Snapshot latest = readLatest(tenantId, requirementId);
        Map<String, String> metrics = latest == null
                ? Map.of() : parseMetrics(latest.metricsJson());
        List<SonarViews.ConditionView> conditions = latest == null
                ? List.of() : parseConditions(latest.snapshotJson());
        return SonarViews.SonarCardFactory.unavailable(projectKey,
                SonarViews.REASON_ADAPTER_UNAVAILABLE,
                "拉取 Sonar 失败（" + nullSafe(measures.message()) + "），保留上次快照；"
                        + "准入第 8 项在下一次成功刷新前按失败处理",
                latest == null ? null : latest.id(),
                latest == null ? null : latest.hash(),
                latest == null ? null : latest.computedAt(),
                metrics, conditions, true, true);
    }

    /** 服务端 ERROR 时必须至少给出一条可展示的未达标项（AC-6.3）。 */
    private List<SonarViews.ConditionView> conditionsFor(String status,
                                                         List<SonarViews.ConditionView> conditions,
                                                         Map<String, String> metrics) {
        if (!SonarViews.STATUS_ERROR.equals(SonarThresholds.upper(status))) {
            return conditions == null ? List.of() : conditions;
        }
        if (conditions != null && !conditions.isEmpty()) {
            return conditions;
        }
        List<SonarViews.ConditionView> derived = SonarThresholds.unmet(metrics);
        if (!derived.isEmpty()) {
            return derived;
        }
        return List.of(new SonarViews.ConditionView("sonar_gate", "质量门", "ERROR", "OK", "=",
                SonarThresholds.ERROR));
    }

    /** 聚合三态：五度量取最差；服务端已判 ERROR 时不允许出现「度量全 OK」的自相矛盾。 */
    private String metricStatus(Map<String, String> metrics, String gateStatus) {
        String aggregate = SonarThresholds.aggregate(metrics);
        if (SonarViews.STATUS_ERROR.equals(gateStatus)
                && !SonarThresholds.ERROR.equals(aggregate)) {
            return SonarThresholds.ERROR;
        }
        return aggregate == null ? SonarThresholds.OK : aggregate;
    }

    // ------------------------------------------------------------------ key / config

    private long tenantOf(RequirementEntity requirement, DataScope scope) {
        return requirement.getTenantId() == null ? scope.tenantId() : requirement.getTenantId();
    }

    private IntegrationConfigEntity enabledConfig(long tenantId) {
        return adapterRegistry.findEnabled(tenantId, IntegrationSystem.SONAR).orElse(null);
    }

    private boolean anySonarConfig(long tenantId) {
        return configRepository.findByTenantIdOrderBySystemAscIdAsc(tenantId).stream()
                .anyMatch(config -> IntegrationSystem.SONAR.name().equalsIgnoreCase(config.getSystem()));
    }

    private ApiException disabled(long tenantId) {
        boolean configured = anySonarConfig(tenantId);
        return new ApiException(409, "INTEGRATION_DISABLED",
                configured ? "Sonar 集成已关闭，请先在集成配置中启用"
                        : "Sonar 集成未配置，请先在集成配置中添加",
                Map.of("system", IntegrationSystem.SONAR.name(), "configured", configured));
    }

    /**
     * 生效的 projectKey：需求自带优先，其次版本默认（继承规则只在
     * {@link ReleaseIntegrationSettingsService#effectiveSonarKey(long, String)} 一处实现）。
     * 非法字符按 AC-6.1 返回 400 {@code SONAR_KEY_INVALID}，不拿坏 key 去外呼
     * ——校验走 {@link SonarProjectKeys}，与需求编辑口、版本默认值口同一份规则。
     */
    private String projectKey(RequirementEntity requirement) {
        String key = trimToNull(requirement.getSonarProjectKey());
        if (key == null && requirement.getReleaseId() != null) {
            key = trimToNull(settingsService.effectiveSonarKey(requirement.getReleaseId(), null));
        }
        return key == null ? null : SonarProjectKeys.normalize(key, "sonarProjectKey");
    }

    // ------------------------------------------------------------------ hashing

    /**
     * hash = SHA-256("SONAR|projectKey|gateStatus|coverage=..|bugs=..|...")，
     * 度量值先规范化（82.5 与 82.50 同 hash）。同一结论重复刷新因此不新增行（AC-6.2）。
     */
    static String hash(String projectKey, String gateStatus, Map<String, String> metrics) {
        StringBuilder canonical = new StringBuilder("SONAR|")
                .append(projectKey).append('|').append(gateStatus);
        for (String key : SonarThresholds.KEYS) {
            canonical.append('|').append(key).append('=')
                    .append(SonarThresholds.canonical(metrics == null ? null : metrics.get(key)));
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ------------------------------------------------------------------ parsing helpers

    private Map<String, String> sorted(Map<String, String> metrics) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String key : SonarThresholds.KEYS) {
            String value = metrics == null ? null : metrics.get(key);
            if (value != null) {
                ordered.put(key, value);
            }
        }
        return ordered;
    }

    private Map<String, String> parseMetrics(String json) {
        JsonNode node = readJson(json);
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> metrics = new LinkedHashMap<>();
        for (String key : SonarThresholds.KEYS) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                metrics.put(key, value.asText());
            }
        }
        return metrics;
    }

    /** 历史脏快照一律降级为空列表：卡片不能因为一条坏 JSON 打不开。 */
    private List<SonarViews.ConditionView> parseConditions(String json) {
        JsonNode node = readJson(json);
        JsonNode array = node == null ? null : node.get("conditions");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<SonarViews.ConditionView> conditions = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            conditions.add(new SonarViews.ConditionView(
                    text(item, "metric"), text(item, "label"), text(item, "actualValue"),
                    text(item, "errorThreshold"), text(item, "comparator"),
                    text(item, "status")));
        }
        return conditions;
    }

    private JsonNode readJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private boolean isStale(LocalDateTime computedAt) {
        if (computedAt == null) {
            return true;
        }
        int refresh = properties.getSonarRefreshMinutes() <= 0 ? 30 : properties.getSonarRefreshMinutes();
        long minutes = Math.max(60L, refresh * 2L);
        return computedAt.isBefore(LocalDateTime.now().minusMinutes(minutes));
    }

    private Object lockFor(long tenantId, long requirementId) {
        int index = (int) Math.floorMod(tenantId * 31L + requirementId, LOCK_STRIPES);
        return locks[index];
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "无应答" : value;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    /** BIGINT UNSIGNED 在不同驱动下可能映射成 BigInteger，统一走 Number。 */
    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : Long.valueOf(text);
    }

    private static LocalDateTime dateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : LocalDateTime.parse(text.replace(' ', 'T'));
    }

    static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
