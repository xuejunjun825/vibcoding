package com.example.req.integration.config;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.integration.adapter.AdapterRegistry;
import com.example.req.integration.adapter.AdapterResult;
import com.example.req.integration.adapter.IntegrationAdapter;
import com.example.req.integration.store.IntegrationSyncLogEntity;
import com.example.req.integration.store.IntegrationSyncLogRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 四类集成配置的读写与连通性测试（TECHNICAL §4.1）。
 *
 * <p>这一层承载三条只允许实现一次的语义：</p>
 * <ol>
 *   <li><b>凭据只写不读</b>：明文进来立即 {@link IntegrationCrypto#encode} 成 AES-GCM 密文，
 *       库里只多一列末四位；任何读路径都不解密（{@link #list} 连解码都不调用），
 *       因此"读接口泄漏凭据"在结构上不可能发生（AC-1.2 / AC-10.1）。</li>
 *   <li><b>空串 = 不改</b>：{@code credential/secret} 省略或 {@code ""} 一律保持原值，
 *       清除只有 {@code clearCredential=true} + {@code confirmName} 一条路
 *       （400 {@code CONFIRM_NAME_MISMATCH}）——前端不靠猜，服务端不靠约定。</li>
 *   <li><b>同系统唯一启用</b>：{@link #guardEnabled} 先做友好判定（409
 *       {@code CONFIG_ALREADY_ENABLED}），数据库 {@code uk_cfg_active} 再兜并发
 *       （{@link DataIntegrityViolationException} 同样映射成 409）。</li>
 * </ol>
 *
 * <p>连通性测试走 {@link AdapterRegistry} 的适配器 {@code testConnection}：与业务调用
 * 同一入口，因此"测试通过但业务调不通"这种双实现漂移不会出现（AC-1.4）。</p>
 */
@Service
public class IntegrationConfigService {

    /** 409：同系统已有另一条启用配置（{@code uk_cfg_active}）。 */
    public static final String CODE_ALREADY_ENABLED = "CONFIG_ALREADY_ENABLED";
    /** 404：配置不存在或不属于当前租户。 */
    public static final String CODE_NOT_FOUND = "INTEGRATION_CONFIG_NOT_FOUND";
    /** 404：同步日志不存在或不属于当前租户。 */
    public static final String CODE_LOG_NOT_FOUND = "INTEGRATION_SYNC_LOG_NOT_FOUND";
    /** 400：清除凭据的确认名不匹配。 */
    public static final String CODE_CONFIRM_MISMATCH = "CONFIRM_NAME_MISMATCH";
    /** 400：字段校验失败。 */
    public static final String CODE_VALIDATION = "VALIDATION_FAILED";

    private static final int MAX_NAME = 64;
    private static final int MAX_URL = 500;
    private static final int MAX_MESSAGE = 300;
    private static final int MIN_TIMEOUT_MS = 100;
    private static final int MAX_TIMEOUT_MS = 60_000;
    private static final int MAX_PAGE_SIZE = 200;

    private static final Set<String> AUTH_TYPES =
            Set.of("NONE", "BASIC", "TOKEN", "PRIVATE_TOKEN");

    private static final Set<String> LOG_RESULTS = Set.of(
            IntegrationSyncLogEntity.RESULT_SUCCESS,
            IntegrationSyncLogEntity.RESULT_DEGRADED,
            IntegrationSyncLogEntity.RESULT_FAILED,
            IntegrationSyncLogEntity.RESULT_RETRY_EXHAUSTED,
            IntegrationSyncLogEntity.RESULT_IGNORED);

    /**
     * {@code extra} 的具名字段按系统写死（DESIGN §5.1 明确禁止一个自由 JSON 框）。
     * 未知键一律 400：写错的键静默落库，适配器读不到就会变成"配置看起来对但构建不触发"。
     */
    private static final Map<IntegrationSystem, Set<String>> EXTRA_KEYS = Map.of(
            IntegrationSystem.JENKINS, Set.of("jobName"),
            IntegrationSystem.GITLAB, Set.of("projectPath"),
            IntegrationSystem.SONAR, Set.of("projectKey"),
            IntegrationSystem.NEXUS, Set.of("repository", "allowedHosts"));

    private final IntegrationConfigRepository repository;
    private final IntegrationSyncLogRepository logRepository;
    private final IntegrationCrypto crypto;
    private final AdapterRegistry adapterRegistry;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public IntegrationConfigService(IntegrationConfigRepository repository,
                                    IntegrationSyncLogRepository logRepository,
                                    IntegrationCrypto crypto,
                                    AdapterRegistry adapterRegistry,
                                    ObjectMapper objectMapper,
                                    AuditService auditService) {
        this.repository = repository;
        this.logRepository = logRepository;
        this.crypto = crypto;
        this.adapterRegistry = adapterRegistry;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    /** 管理页清单：{@code system} 为空返回四个系统全部配置，否则只回该系统。 */
    @Transactional(readOnly = true)
    public List<IntegrationConfigViews.ConfigView> list(String system) {
        long tenantId = TenantContext.require().tenantId();
        List<IntegrationConfigEntity> rows = (system == null || system.isBlank())
                ? repository.findByTenantIdOrderBySystemAscIdAsc(tenantId)
                : repository.findByTenantIdAndSystemOrderByIdAsc(tenantId, requireSystem(system).name());
        return rows.stream().map(this::view).toList();
    }

    /** 新建：{@code enabled} 缺省为停用（默认关闭是 M7 的安全默认）。 */
    @Transactional
    public IntegrationConfigViews.ConfigView create(IntegrationConfigViews.ConfigCmd cmd) {
        if (cmd == null) {
            throw validation("system", "请求体不能为空");
        }
        var scope = TenantContext.require();
        IntegrationSystem system = requireSystem(cmd.system());
        IntegrationConfigEntity entity = new IntegrationConfigEntity();
        entity.setTenantId(scope.tenantId());
        entity.setSystem(system.name());
        entity.setName(name(cmd.name()));
        entity.setBaseUrl(baseUrl(cmd.baseUrl()));
        entity.setAuthType(authType(cmd.authType()));
        entity.setUsername(trimToNull(cmd.username()));
        entity.setExtraJson(extraJson(system, cmd.extra()));
        entity.setTimeoutMs(timeout(cmd.timeoutMs()));
        entity.setEnabled(Boolean.TRUE.equals(cmd.enabled()) ? 1 : 0);
        entity.setCreatedBy(scope.userId());
        entity.setUpdatedBy(scope.userId());
        applySecrets(entity, cmd);
        if (isEnabled(entity)) {
            guardEnabled(scope.tenantId(), system.name(), null);
        }
        IntegrationConfigEntity saved = save(entity);
        audit("INTEGRATION_CONFIG_CREATE", saved);
        return view(saved);
    }

    /**
     * 部分更新：只有给出的字段才改（{@code null} = 不改），因此
     * {@code PATCH {"enabled":true}} 与 {@code PATCH {"baseUrl":"..."}} 都是合法的最小请求。
     */
    @Transactional
    public IntegrationConfigViews.ConfigView update(long id, IntegrationConfigViews.ConfigCmd cmd) {
        if (cmd == null) {
            throw validation("body", "请求体不能为空");
        }
        var scope = TenantContext.require();
        IntegrationConfigEntity entity = load(id, scope.tenantId());
        IntegrationSystem system = entity.systemEnum();
        if (cmd.system() != null && !cmd.system().isBlank()) {
            system = requireSystem(cmd.system());
            entity.setSystem(system.name());
        }
        if (system == null) {
            throw validation("system", "未知集成系统：" + entity.getSystem());
        }
        if (cmd.name() != null) {
            entity.setName(name(cmd.name()));
        }
        if (cmd.baseUrl() != null) {
            entity.setBaseUrl(baseUrl(cmd.baseUrl()));
        }
        if (cmd.authType() != null) {
            entity.setAuthType(authType(cmd.authType()));
        }
        if (cmd.username() != null) {
            entity.setUsername(trimToNull(cmd.username()));
        }
        if (cmd.extra() != null) {
            entity.setExtraJson(extraJson(system, cmd.extra()));
        }
        if (cmd.timeoutMs() != null) {
            entity.setTimeoutMs(timeout(cmd.timeoutMs()));
        }
        applySecrets(entity, cmd);
        // 顺序是有原因的：判唯一必须在"把 enabled=1 写进受管实体"之前。
        // 否则 guardEnabled 的查询会触发 Hibernate 自动 flush，唯一键冲突会从这条查询里
        // 冒出来（实测：500 + Duplicate entry '1-JENKINS-ENABLED'），而不是我们想要的 409。
        boolean enableAfter = cmd.enabled() != null ? cmd.enabled() : isEnabled(entity);
        if (enableAfter) {
            guardEnabled(scope.tenantId(), system.name(), entity.getId());
        }
        if (cmd.enabled() != null) {
            entity.setEnabled(cmd.enabled() ? 1 : 0);
        }
        entity.setUpdatedBy(scope.userId());
        IntegrationConfigEntity saved = save(entity);
        audit("INTEGRATION_CONFIG_UPDATE", saved);
        return view(saved);
    }

    /** 删除：物理删（{@code integration_sync_log} 保留历史证据，不随之删除）。 */
    @Transactional
    public void delete(long id) {
        long tenantId = TenantContext.require().tenantId();
        IntegrationConfigEntity entity = load(id, tenantId);
        repository.delete(entity);
        audit("INTEGRATION_CONFIG_DELETE", entity);
    }

    /**
     * 连通性测试：恒 200（失败也是一种结果），并把 {@code last_test_*} 回写。
     *
     * <p>刻意不加 {@code @Transactional}：外部系统慢的时候不该占着数据库事务与连接池；
     * 结果回写走一次独立的 save。</p>
     */
    public IntegrationConfigViews.TestResult test(long id) {
        long tenantId = TenantContext.require().tenantId();
        IntegrationConfigEntity entity = load(id, tenantId);
        AdapterResult result;
        try {
            IntegrationAdapter adapter = entity.systemEnum() == null
                    ? null
                    : adapterRegistry.find(entity.systemEnum()).orElse(null);
            result = adapter != null
                    ? adapter.testConnection(entity)
                    : AdapterResult.failure(0, AdapterRegistry.CODE_ADAPTER_MISSING,
                            "该系统没有注册适配器");
        } catch (RuntimeException ex) {
            // 凭据解不开等配置错误也要变成"测试失败"而不是 500：管理页要能就地看到原因。
            result = AdapterResult.failure(0, "TEST_CONNECTION_ERROR",
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        LocalDateTime checkedAt = LocalDateTime.now();
        entity.setLastTestAt(checkedAt);
        entity.setLastTestOk(result.ok() ? 1 : 0);
        entity.setLastTestMessage(truncate(result.message() != null
                ? result.message()
                : String.valueOf(result.code()), MAX_MESSAGE));
        repository.save(entity);
        audit("INTEGRATION_CONFIG_TEST", entity);
        return new IntegrationConfigViews.TestResult(result.ok(), result.latencyMs(),
                result.code(), result.message(), checkedAt);
    }

    /** 该系统当前启用的配置（{@code AdapterRegistry} 之外的第二读入口，语义同源）。 */
    @Transactional(readOnly = true)
    public Optional<IntegrationConfigEntity> enabled(IntegrationSystem system) {
        return repository.enabled(system);
    }

    /**
     * 同步日志分页：三个筛选条件都可省略。
     *
     * <p>摘要与 URL 是写库前已脱敏的文本，这里原样返回、不再二次处理——
     * 脱敏只有一个出口（{@code SensitiveMasker}），读侧再"洗一遍"只会掩盖写侧漏洞。</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<IntegrationConfigViews.LogRow> logs(String system, String result,
                                                            String correlationId,
                                                            LocalDateTime from, LocalDateTime to,
                                                            String keyword,
                                                            int page, int size) {
        long tenantId = TenantContext.require().tenantId();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        String systemFilter = (system == null || system.isBlank())
                ? null
                : requireSystem(system).name();
        String resultFilter = normalizeResult(result);
        String correlationFilter = (correlationId == null || correlationId.isBlank())
                ? null
                : correlationId.trim();
        String keywordFilter = (keyword == null || keyword.isBlank())
                ? null
                : keyword.trim();
        Page<IntegrationSyncLogEntity> rows = logRepository.search(tenantId, systemFilter,
                resultFilter, correlationFilter, from, to, keywordFilter, null,
                PageRequest.of(safePage - 1, safeSize));
        List<IntegrationConfigViews.LogRow> items = rows.getContent().stream()
                .map(IntegrationConfigService::logRow)
                .toList();
        return new PageResponse<>(items, rows.getTotalElements(), safePage, safeSize);
    }

    /**
     * 需求详情「集成」tab 的同步记录：同一张表、同一条 JPQL，只是把作用域收紧到该需求
     * （DESIGN §I4「该需求，最近 10 条」，§325「MR/构建/Sonar 三类合并按时间倒序 10 条」）。
     *
     * <p>数据作用域的派生规则在 {@code IntegrationSyncLogRepository.search} 内部实现一次；
     * 该方法不带任何筛选维度，避免调用方再各拼一套"这个需求的日志"的判据。</p>
     */
    @Transactional(readOnly = true)
    public List<IntegrationConfigViews.LogRow> logsForRequirement(long requirementId, int limit) {
        long tenantId = TenantContext.require().tenantId();
        int safeSize = Math.min(Math.max(1, limit), MAX_PAGE_SIZE);
        return logRepository.search(tenantId, null, null, null, null, null, null,
                        requirementId, PageRequest.of(0, safeSize))
                .getContent().stream()
                .map(IntegrationConfigService::logRow)
                .toList();
    }

    /** 单条同步日志明细：租户内可见，跨租户/不存在统一 404（不泄漏"这行存在"）。 */
    @Transactional(readOnly = true)
    public IntegrationConfigViews.LogRow log(long id) {
        long tenantId = TenantContext.require().tenantId();
        return logRepository.findByIdAndTenantId(id, tenantId)
                .map(IntegrationConfigService::logRow)
                .orElseThrow(() -> new ApiException(404, CODE_LOG_NOT_FOUND, "同步日志不存在"));
    }

    /* ------------------------------------------------------------ 视图与校验 */

    private IntegrationConfigViews.ConfigView view(IntegrationConfigEntity entity) {
        return new IntegrationConfigViews.ConfigView(
                entity.getId(),
                entity.getSystem(),
                entity.getName(),
                entity.getBaseUrl(),
                entity.getAuthType(),
                entity.getUsername(),
                hasBytes(entity.getCredentialEnc()),
                hasBytes(entity.getCredentialEnc()) ? entity.getCredentialLast4() : null,
                hasBytes(entity.getSecretEnc()),
                hasBytes(entity.getSecretEnc()) ? entity.getSecretLast4() : null,
                extraObject(entity.getExtraJson()),
                isEnabled(entity),
                entity.getTimeoutMs() == null ? 5000 : entity.getTimeoutMs(),
                testView(entity));
    }

    private static IntegrationConfigViews.TestResult testView(IntegrationConfigEntity entity) {
        if (entity.getLastTestAt() == null) {
            return null;
        }
        return new IntegrationConfigViews.TestResult(
                entity.getLastTestOk() != null && entity.getLastTestOk() == 1,
                null,
                entity.getLastTestOk() != null && entity.getLastTestOk() == 1 ? null : "LAST_TEST_FAILED",
                entity.getLastTestMessage(),
                entity.getLastTestAt());
    }

    private static IntegrationConfigViews.LogRow logRow(IntegrationSyncLogEntity row) {
        return new IntegrationConfigViews.LogRow(
                row.getId() == null ? 0L : row.getId(),
                row.getCorrelationId(),
                row.getSystem(),
                row.getOperation(),
                row.getMethod(),
                row.getTargetUrl(),
                row.getRequestSummary(),
                row.getResponseSummary(),
                row.getHttpStatus(),
                row.getResult(),
                row.getErrorCode(),
                row.getRetryCount() == null ? 0 : row.getRetryCount(),
                row.getDurationMs(),
                row.getCreatedAt());
    }

    private IntegrationConfigEntity load(long id, long tenantId) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException(404, CODE_NOT_FOUND, "集成配置不存在"));
    }

    /**
     * 凭据写入的唯一实现：先处理显式清除（要确认名），再写入新值。
     * 顺序很关键——同一个请求里既清除又给新值时，最终状态应是新值。
     */
    private void applySecrets(IntegrationConfigEntity entity, IntegrationConfigViews.ConfigCmd cmd) {
        if (Boolean.TRUE.equals(cmd.clearCredential())) {
            String confirm = trimToNull(cmd.confirmName());
            if (confirm == null || !confirm.equals(entity.getName())) {
                throw new ApiException(400, CODE_CONFIRM_MISMATCH,
                        "清除凭据必须输入配置名称确认", Map.of("field", "confirmName"));
            }
            entity.setCredentialEnc(null);
            entity.setCredentialLast4(null);
        }
        if (cmd.credential() != null && !cmd.credential().isEmpty()) {
            String plain = cmd.credential();
            entity.setCredentialEnc(crypto.encode(plain));
            entity.setCredentialLast4(crypto.last4(plain));
        }
        if (cmd.secret() != null && !cmd.secret().isEmpty()) {
            String plain = cmd.secret();
            entity.setSecretEnc(crypto.encode(plain));
            entity.setSecretLast4(crypto.last4(plain));
        }
    }

    private IntegrationConfigEntity save(IntegrationConfigEntity entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(409, CODE_ALREADY_ENABLED,
                    "同一系统只允许一条启用配置（uk_cfg_active）",
                    Map.of("system", entity.getSystem()));
        }
    }

    private void guardEnabled(long tenantId, String system, Long selfId) {
        try {
            repository.findFirstByTenantIdAndSystemAndEnabledOrderByIdAsc(tenantId, system, 1)
                    .filter(row -> selfId == null || !selfId.equals(row.getId()))
                    .ifPresent(row -> {
                        throw new ApiException(409, CODE_ALREADY_ENABLED,
                                "该系统已有启用配置，请先停用 id=" + row.getId(),
                                Map.of("system", system, "enabledId", row.getId()));
                    });
        } catch (DataIntegrityViolationException ex) {
            // 查询前的自动 flush 撞上唯一键时也归一到 409：对外只有一种"已启用"语义。
            throw new ApiException(409, CODE_ALREADY_ENABLED,
                    "同一系统只允许一条启用配置（uk_cfg_active）", Map.of("system", system));
        }
    }

    private void audit(String action, IntegrationConfigEntity entity) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("system", entity.getSystem());
        detail.put("name", entity.getName());
        detail.put("enabled", isEnabled(entity));
        // 审计里也只留末四位：审计表是另一个可读面，凭据不进任何列。
        detail.put("credentialLast4", entity.getCredentialLast4());
        auditService.record(action, "INTEGRATION_CONFIG", entity.getId(), detail);
    }

    private IntegrationSystem requireSystem(String code) {
        return IntegrationSystem.of(code)
                .orElseThrow(() -> validation("system", "未知集成系统：" + code));
    }

    private static boolean isEnabled(IntegrationConfigEntity entity) {
        return entity.getEnabled() != null && entity.getEnabled() == 1;
    }

    private static boolean hasBytes(byte[] value) {
        return value != null && value.length > 0;
    }

    private String name(String value) {
        String name = trimToNull(value);
        if (name == null) {
            throw validation("name", "配置名称不能为空");
        }
        if (name.length() > MAX_NAME) {
            throw validation("name", "配置名称不得超过 " + MAX_NAME + " 字符");
        }
        return name;
    }

    /**
     * 只校验形态，不做 SSRF 判定：白名单是出站那一刻的事（{@code SsrfGuard}），
     * 保存时把内网地址拒掉会让"先配后通网"的部署顺序无法表达。
     */
    private String baseUrl(String value) {
        String url = trimToNull(value);
        if (url == null) {
            throw validation("baseUrl", "base_url 不能为空");
        }
        if (url.length() > MAX_URL) {
            throw validation("baseUrl", "base_url 不得超过 " + MAX_URL + " 字符");
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw validation("baseUrl", "base_url 必须是 http/https：" + url);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw validation("baseUrl", "base_url 缺少主机名：" + url);
            }
        } catch (URISyntaxException ex) {
            throw validation("baseUrl", "base_url 不是合法 URL：" + url);
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String authType(String value) {
        if (value == null || value.isBlank()) {
            return "TOKEN";
        }
        String type = value.trim().toUpperCase(Locale.ROOT);
        if (!AUTH_TYPES.contains(type)) {
            throw validation("authType", "auth_type 只能是 " + AUTH_TYPES);
        }
        return type;
    }

    private static int timeout(Integer value) {
        if (value == null) {
            return 5000;
        }
        if (value < MIN_TIMEOUT_MS || value > MAX_TIMEOUT_MS) {
            throw validation("timeoutMs",
                    "timeoutMs 必须在 " + MIN_TIMEOUT_MS + ".." + MAX_TIMEOUT_MS + " 之间");
        }
        return value;
    }

    /**
     * {@code extra} 的具名白名单校验；返回规范化后的 JSON（空表存 NULL）。
     * 字符串值去空白，空白视为"未填"直接丢弃，避免适配器读到 "" 后拼出坏 URL。
     */
    private String extraJson(IntegrationSystem system, Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return null;
        }
        Set<String> allowed = EXTRA_KEYS.getOrDefault(system, Set.of());
        Map<String, Object> normalized = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : extra.entrySet()) {
            String key = entry.getKey();
            if (key == null || !allowed.contains(key)) {
                unknown.add(String.valueOf(key));
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof String text) {
                String trimmed = text.trim();
                if (!trimmed.isEmpty()) {
                    normalized.put(key, trimmed);
                }
            } else if (value != null) {
                normalized.put(key, value);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ApiException(400, CODE_VALIDATION,
                    system.label() + " 的 extra 只接受 " + allowed + "，收到未知键 " + unknown,
                    Map.of("field", "extra", "unknown", List.copyOf(unknown)));
        }
        if (normalized.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(normalized);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extraObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(json, Map.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String normalizeResult(String result) {
        if (result == null || result.isBlank()) {
            return null;
        }
        String value = result.trim().toUpperCase(Locale.ROOT);
        if (!LOG_RESULTS.contains(value)) {
            throw validation("result", "result 只能是 " + LOG_RESULTS);
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static ApiException validation(String field, String message) {
        return new ApiException(400, CODE_VALIDATION, message, Map.of("field", field));
    }
}
