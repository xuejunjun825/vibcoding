package com.example.req.integration.jenkins;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.audit.AuditService;
import com.example.platform.contract.DataScope;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.deploy.DeployOrderEntity;
import com.example.req.deploy.DeployOrderRepository;
import com.example.req.deploy.DeployOrderRequirementEntity;
import com.example.req.deploy.DeployOrderRequirementRepository;
import com.example.req.integration.InternalTokenClient;
import com.example.req.integration.adapter.AdapterRegistry;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationConfigRepository;
import com.example.req.integration.config.IntegrationCrypto;
import com.example.req.integration.config.IntegrationProperties;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.notify.IntegrationNotifier;
import com.example.req.integration.outbound.OutboundRequest;
import com.example.req.integration.security.WebhookRateLimiter;
import com.example.req.integration.security.WebhookSignatureVerifier;
import com.example.req.integration.store.BuildRecordEntity;
import com.example.req.integration.store.BuildRecordRepository;
import com.example.req.integration.sync.SyncLogWriter;
import com.example.req.integration.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 构建的<b>唯一</b>事实写入点：触发、刷新、回调、超时扫描都在这里改 {@code build_record}。
 *
 * <p>三条不肯妥协的规则：</p>
 * <ol>
 *   <li><b>并发靠唯一键，不靠内存锁</b>：{@code uk_build_active(tenant_id, active_key)} 挡住
 *       同一发布单的第二个活动构建（AC-3.2 的 409 BUILD_IN_PROGRESS）。内存锁在多实例下
 *       就是没锁，而且它挡不住「两个请求同时通过预检」的竞态。</li>
 *   <li><b>状态只经状态机</b>：任何来源（刷新/回调/超时）都先算
 *       {@link BuildStateMachine#next}，OUT_OF_ORDER 一律不落库（AC-3.4）。</li>
 *   <li><b>终态与占用同一行</b>：进终态时把 {@code active_key} 置空，和状态写在同一次
 *       save 里——拆开就会出现「已结束但仍占着锁」的幽灵行。</li>
 * </ol>
 *
 * <p>没有类级 {@code @Transactional}：这里的写都是「一行一次 save」，本身就是原子的；
 * 而触发流程要能在唯一键冲突后<b>重新查</b>现有构建（被标记 rollback-only 的事务里查不了）。
 * 转缺陷是 HTTP 调用，更不能包在数据库事务里。</p>
 */
@Service
public class BuildService {

    private static final Logger log = LoggerFactory.getLogger(BuildService.class);

    public static final String CODE_BUILD_IN_PROGRESS = "BUILD_IN_PROGRESS";
    public static final String CODE_INVALID_TRANSITION = "INVALID_BUILD_TRANSITION";
    public static final String CODE_BUILD_NOT_FOUND = "BUILD_NOT_FOUND";
    public static final String CODE_BUILD_TIMEOUT = "BUILD_TIMEOUT";
    public static final String CODE_CONSOLE_URL_REJECTED = "CONSOLE_URL_REJECTED";
    public static final String CODE_RATE_LIMITED = "WEBHOOK_RATE_LIMITED";
    public static final String CODE_CONFIG_NOT_FOUND = "INTEGRATION_CONFIG_NOT_FOUND";
    public static final String CODE_ORDER_NOT_FOUND = "BIZ_NOT_FOUND";

    public static final String STATUS_DUPLICATE = "DUPLICATE";
    public static final String STATUS_OUT_OF_ORDER = "OUT_OF_ORDER";
    public static final String STATUS_IGNORED = "IGNORED";
    public static final String STATUS_UPDATED = "UPDATED";

    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 20;
    /** 连续读失败多少次后写一条 RETRY_EXHAUSTED 并把该构建降频。 */
    public static final int POLL_FAIL_THRESHOLD = 5;
    /** 降频：每 N 个 tick 读一次。 */
    public static final int DEGRADED_TICK_DIVISOR = 6;

    private static final String EVENT_BUILD = "build";

    private final BuildRecordRepository builds;
    private final DeployOrderRepository orders;
    private final DeployOrderRequirementRepository orderRequirements;
    private final IntegrationConfigRepository configs;
    private final AdapterRegistry adapterRegistry;
    private final JenkinsAdapter adapter;
    private final JenkinsTriggerService triggerService;
    private final ConsoleUrlGuard consoleGuard;
    private final AuditService audit;
    private final IntegrationNotifier notifier;
    private final IntegrationCrypto crypto;
    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookRateLimiter rateLimiter;
    private final SyncLogWriter syncLogWriter;
    private final IntegrationProperties properties;
    private final InternalTokenClient internalTokenClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public BuildService(BuildRecordRepository builds,
                        DeployOrderRepository orders,
                        DeployOrderRequirementRepository orderRequirements,
                        IntegrationConfigRepository configs,
                        AdapterRegistry adapterRegistry,
                        JenkinsAdapter adapter,
                        JenkinsTriggerService triggerService,
                        ConsoleUrlGuard consoleGuard,
                        AuditService audit,
                        IntegrationNotifier notifier,
                        IntegrationCrypto crypto,
                        WebhookSignatureVerifier signatureVerifier,
                        WebhookRateLimiter rateLimiter,
                        SyncLogWriter syncLogWriter,
                        IntegrationProperties properties,
                        InternalTokenClient internalTokenClient,
                        ObjectMapper objectMapper,
                        JdbcTemplate jdbc) {
        this.builds = builds;
        this.orders = orders;
        this.orderRequirements = orderRequirements;
        this.configs = configs;
        this.adapterRegistry = adapterRegistry;
        this.adapter = adapter;
        this.triggerService = triggerService;
        this.consoleGuard = consoleGuard;
        this.audit = audit;
        this.notifier = notifier;
        this.crypto = crypto;
        this.signatureVerifier = signatureVerifier;
        this.rateLimiter = rateLimiter;
        this.syncLogWriter = syncLogWriter;
        this.properties = properties;
        this.internalTokenClient = internalTokenClient;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 触发

    /**
     * 触发一次构建（AC-3.1 / 3.2）：先落 QUEUED 行，再异步提交，<b>立刻</b>返回。
     *
     * <p>注意顺序：先校验集成启用与 jobName（此时还没有任何写入，AC-3.1 要求
     * 「enabled=false 时 build_record 行数不变」），再抢唯一键。</p>
     */
    public BuildViews.TriggerResult trigger(long deployOrderId, BuildViews.TriggerCmd cmd) {
        DataScope scope = TenantContext.require();
        long tenantId = scope.tenantId();
        DeployOrderEntity order = orders.findByIdAndTenantId(deployOrderId, tenantId)
                .orElseThrow(() -> new ApiException(404, CODE_ORDER_NOT_FOUND, "发布单不存在"));

        // 未启用 → 409 INTEGRATION_DISABLED（AdapterRegistry.require 抛），此时一行都没写。
        IntegrationConfigEntity config = adapterRegistry.require(IntegrationSystem.JENKINS);
        String jobName = adapter.jobNameOf(config); // 缺 jobName → 400 BUILD_NOT_CONFIGURED

        BuildRecordEntity existing = builds
                .findFirstByTenantIdAndDeployOrderIdAndActiveKeyIsNotNull(tenantId, deployOrderId)
                .orElse(null);
        if (existing != null) {
            throw inProgress(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        BuildRecordEntity build = new BuildRecordEntity();
        build.setTenantId(tenantId);
        build.setDeployOrderId(order.getId());
        build.setRequirementId(firstRequirement(tenantId, order.getId()));
        build.setConfigId(config.getId());
        build.setJobName(jobName);
        build.setStatus(BuildStateMachine.BuildStatus.QUEUED.name());
        build.setActiveKey(String.valueOf(order.getId()));
        build.setTimeoutAt(now.plusMinutes(timeoutMinutes()));
        build.setPollFailCount(0);
        build.setCorrelationId(OutboundRequest.newCorrelationId());
        build.setTriggerBy(scope.userId() > 0 ? scope.userId() : null);
        try {
            build = builds.save(build);
        } catch (DataIntegrityViolationException e) {
            // 竞态：预检时还没有活动构建，两个请求同时插入。唯一键会拦下第二个，
            // 这里把「赢家」查出来告诉调用方——不新增行，这正是 AC-3.2 要的。
            BuildRecordEntity winner = builds
                    .findFirstByTenantIdAndDeployOrderIdAndActiveKeyIsNotNull(tenantId, deployOrderId)
                    .orElse(null);
            if (winner != null) {
                throw inProgress(winner);
            }
            throw e;
        }

        // 准入第 7 项与证据读都看 deploy_order.build_id：不写这一列，构建就「不存在」。
        order.setBuildId(build.getId());
        orders.save(order);

        audit.record(JenkinsTriggerService.ACTION_TRIGGERED, "BUILD", build.getId(), Map.of(
                "deployOrderId", order.getId(),
                "jobName", jobName,
                "status", build.getStatus(),
                "correlationId", build.getCorrelationId()));

        Map<String, Object> params = new LinkedHashMap<>();
        if (cmd != null && cmd.branchOrNull() != null) {
            params.put("branch", cmd.branchOrNull());
        }
        triggerService.submitAsync(build.getId(), params, build.getCorrelationId());
        return new BuildViews.TriggerResult(build.getId(), build.getStatus(),
                build.getCorrelationId());
    }

    private ApiException inProgress(BuildRecordEntity existing) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("buildId", existing.getId());
        detail.put("status", existing.getStatus());
        if (existing.getBuildNumber() != null) {
            detail.put("buildNumber", existing.getBuildNumber());
        }
        return new ApiException(409, CODE_BUILD_IN_PROGRESS,
                "该发布单已有进行中的构建，请等待其结束或先取消", detail);
    }

    // ------------------------------------------------------------------ 读取

    public BuildViews.BuildList list(long deployOrderId, int limit) {
        DataScope scope = TenantContext.require();
        orders.findByIdAndTenantId(deployOrderId, scope.tenantId())
                .orElseThrow(() -> new ApiException(404, CODE_ORDER_NOT_FOUND, "发布单不存在"));
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<BuildRecordEntity> rows = builds
                .findByTenantIdAndDeployOrderIdOrderByIdDesc(scope.tenantId(), deployOrderId);
        Map<Long, String> names = userNames(rows.stream()
                .map(BuildRecordEntity::getTriggerBy).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));
        List<BuildViews.BuildView> items = rows.stream()
                .limit(size)
                .map(row -> view(row, names))
                .toList();
        return new BuildViews.BuildList(items);
    }

    public BuildViews.BuildView get(long buildId) {
        DataScope scope = TenantContext.require();
        BuildRecordEntity build = builds.findByIdAndTenantId(buildId, scope.tenantId())
                .orElseThrow(() -> new ApiException(404, CODE_BUILD_NOT_FOUND, "构建不存在"));
        return view(build, userNames(ids(build)));
    }

    /**
     * 手动刷新一次（AC-3.4 的按钮 + 兜底）。终态直接返回当前值（不再外呼），
     * 读失败也只是「这次没更新」——绝不 5xx，否则页面会因为第三方抖动而整块报错。
     */
    public BuildViews.BuildView refresh(long buildId) {
        DataScope scope = TenantContext.require();
        BuildRecordEntity build = builds.findByIdAndTenantId(buildId, scope.tenantId())
                .orElseThrow(() -> new ApiException(404, CODE_BUILD_NOT_FOUND, "构建不存在"));
        if (!isActive(build)) {
            return view(build, userNames(ids(build)));
        }
        IntegrationConfigEntity config = configs.findById(build.getConfigId()).orElse(null);
        if (config == null || !config.isActive()) {
            return view(build, userNames(ids(build)));
        }
        poll(build, config, "REFRESH");
        return view(build, userNames(ids(build)));
    }

    // ------------------------------------------------------------------ 轮询

    /** 单条构建的一次尝试（供 {@link BuildPollingJob} 与手动刷新共用）。 */
    public void pollOne(long buildId) {
        BuildRecordEntity build = builds.findById(buildId).orElse(null);
        if (build == null || !isActive(build)) {
            return;
        }
        TenantContext.set(DataScope.system(build.getTenantId()));
        try {
            IntegrationConfigEntity config = configs.findById(build.getConfigId()).orElse(null);
            if (config == null || !config.isActive()) {
                return;
            }
            poll(build, config, "POLL");
        } finally {
            TenantContext.clear();
        }
    }

    private void poll(BuildRecordEntity build, IntegrationConfigEntity config, String operation) {
        BuildViews.JenkinsStatus status;
        if (build.getBuildNumber() != null) {
            status = adapter.status(config, build.getBuildNumber(), build.getId());
        } else if (build.getQueueId() != null) {
            status = adapter.queueStatus(config, build.getQueueId(), build.getId());
        } else {
            status = adoptByLastBuild(build, config);
            if (status == null) {
                return;
            }
        }
        if (!status.available()) {
            countPollFailure(build, config, status);
            return;
        }
        if (build.getPollFailCount() != null && build.getPollFailCount() > 0) {
            build.setPollFailCount(0);
        }
        if (status.buildNumber() != null && build.getBuildNumber() == null) {
            build.setBuildNumber(status.buildNumber());
        }
        if (status.queueId() != null && build.getQueueId() == null) {
            build.setQueueId(status.queueId());
        }
        BuildStateMachine.BuildStatus incoming = observed(status);
        apply(build, config, incoming, status, operation);
    }

    /**
     * 提交没拿到任何标识时的兜底：用 {@code lastBuild} 认领。
     * 只在连续两次读空之后才认领，避免刚提交就把「上一次构建」认成这一次。
     */
    private BuildViews.JenkinsStatus adoptByLastBuild(BuildRecordEntity build,
                                                      IntegrationConfigEntity config) {
        int fails = build.getPollFailCount() == null ? 0 : build.getPollFailCount();
        build.setPollFailCount(fails + 1);
        builds.save(build);
        if (fails + 1 < 2) {
            return null;
        }
        Long number = adapter.lastBuildNumber(config, build.getJobName(), build.getId());
        if (number == null) {
            return null;
        }
        build.setBuildNumber(number);
        build.setPollFailCount(0);
        builds.save(build);
        return adapter.status(config, number, build.getId());
    }

    private void countPollFailure(BuildRecordEntity build, IntegrationConfigEntity config,
                                  BuildViews.JenkinsStatus status) {
        int fails = (build.getPollFailCount() == null ? 0 : build.getPollFailCount()) + 1;
        build.setPollFailCount(fails);
        builds.save(build);
        if (fails == POLL_FAIL_THRESHOLD) {
            // 连续 5 次读不到：写一条证据 + 通知，然后把该构建降频到每 6 个 tick 一次。
            syncLogWriter.writeInbound(build.getTenantId(), config.getId(), IntegrationSystem.JENKINS,
                    JenkinsAdapter.OP_STATUS, "BUILD", build.getId(),
                    "build#" + build.getId() + " poll failed " + fails + "x",
                    status != null && status.errorCode() != null ? 500 : 0,
                    SyncResult.RETRY_EXHAUSTED, status == null ? "POLL_FAILED" : status.errorCode());
            notifier.warn(build.getTenantId(), "构建状态读取连续失败：" + build.getJobName(),
                    "已连续 " + fails + " 次读不到构建状态，将降低轮询频率；构建本身仍在 Jenkins 上运行",
                    build.getId());
        }
    }

    // ------------------------------------------------------------------ 超时

    /**
     * 2h 超时扫描（AC-3.5）：活动构建超过 {@code timeout_at} 即判 FAILURE + BUILD_TIMEOUT。
     * fail-closed：读不到状态不能一直挂着，否则发布单会被一个死构建永久占住。
     */
    public int sweepTimeouts() {
        List<BuildRecordEntity> overdue = builds.findByStatusInAndTimeoutAtBefore(
                List.of(BuildStateMachine.BuildStatus.QUEUED.name(),
                        BuildStateMachine.BuildStatus.RUNNING.name()),
                LocalDateTime.now());
        int swept = 0;
        for (BuildRecordEntity build : overdue) {
            TenantContext.set(DataScope.system(build.getTenantId()));
            try {
                if (!isActive(build)) {
                    continue;
                }
                IntegrationConfigEntity config = configs.findById(build.getConfigId()).orElse(null);
                applyTimeout(build, config);
                swept++;
            } catch (RuntimeException e) {
                log.warn("build timeout sweep failed for {}: {}", build.getId(), e.toString());
            } finally {
                TenantContext.clear();
            }
        }
        return swept;
    }

    private void applyTimeout(BuildRecordEntity build, IntegrationConfigEntity config) {
        LocalDateTime now = LocalDateTime.now();
        build.setStatus(BuildStateMachine.BuildStatus.FAILURE.name());
        build.setFinishedAt(now);
        build.setActiveKey(null);
        build.setDurationMs(ageMillis(build, now));
        builds.save(build);
        audit.record(JenkinsTriggerService.ACTION_STATUS, "BUILD", build.getId(), Map.of(
                "status", build.getStatus(),
                "errorCode", CODE_BUILD_TIMEOUT,
                "timeoutAt", String.valueOf(build.getTimeoutAt())));
        syncLogWriter.writeInbound(build.getTenantId(), build.getConfigId(), IntegrationSystem.JENKINS,
                JenkinsAdapter.OP_STATUS, "BUILD", build.getId(),
                "build#" + build.getId() + " timed out",
                0, SyncResult.FAILED, CODE_BUILD_TIMEOUT);
        notifier.warn(build.getTenantId(), "构建超时未回写：" + build.getJobName(),
                "超过 " + timeoutMinutes() + " 分钟仍未收到终态，已判为 FAILURE（BUILD_TIMEOUT）", build.getId());
        if (config != null) {
            fileDefect(build, config, "构建超时未回写");
        }
    }

    // ------------------------------------------------------------------ 回调

    /** webhook 的判定结果：{@code status} 直接进响应体（DUPLICATE / OUT_OF_ORDER / IGNORED / UPDATED）。 */
    public record WebhookOutcome(String status, String reason, Long buildId) {
    }

    /**
     * Jenkins 回调入口（AC-3.4）。顺序是刻意的：限流 → 验签 → 事件过滤 → 解析 → 状态机。
     * 验签失败在任何业务写之前返回 401，所以「坏签名改不了状态」是结构性成立的。
     */
    public WebhookOutcome onWebhook(long configId, byte[] rawBody, Map<String, String> headers) {
        IntegrationConfigEntity config = configs.findById(configId)
                .orElseThrow(() -> new ApiException(404, CODE_CONFIG_NOT_FOUND, "集成配置不存在"));
        TenantContext.set(DataScope.system(config.getTenantId()));
        try {
            if (!rateLimiter.allow(configId)) {
                throw new ApiException(429, CODE_RATE_LIMITED, "回调过于频繁，请稍后重试");
            }
            String event = header(headers, "X-Jenkins-Event");
            if (event == null || event.isBlank()) {
                event = EVENT_BUILD;
            }
            try {
                signatureVerifier.verifyJenkins(webhookSecret(config),
                        header(headers, "X-Jenkins-Signature"),
                        header(headers, "X-Jenkins-Timestamp"), rawBody);
            } catch (ApiException e) {
                // 不动业务行，但留一条入站证据：401 之后要能回答「谁试过、什么时候」。
                syncLogWriter.writeInbound(config.getTenantId(), configId, IntegrationSystem.JENKINS,
                        "WEBHOOK_IN", "jenkins webhook rejected", 401, SyncResult.FAILED, e.code());
                throw e;
            }
            if (!EVENT_BUILD.equalsIgnoreCase(event.trim())) {
                syncLogWriter.writeInbound(config.getTenantId(), configId, IntegrationSystem.JENKINS,
                        "WEBHOOK_IN", "event=" + event, 200, SyncResult.IGNORED, "EVENT_NOT_SUPPORTED");
                return new WebhookOutcome(STATUS_IGNORED, "EVENT_NOT_SUPPORTED", null);
            }
            JsonNode payload = readTree(rawBody);
            if (payload == null || !payload.isObject()) {
                throw new ApiException(400, "VALIDATION_FAILED", "回调载荷不是 JSON 对象");
            }
            BuildRecordEntity build = resolve(config, payload);
            if (build == null) {
                syncLogWriter.writeInbound(config.getTenantId(), configId, IntegrationSystem.JENKINS,
                        "WEBHOOK_IN", "unknown build payload", 200, SyncResult.IGNORED, "UNKNOWN_BUILD");
                return new WebhookOutcome(STATUS_IGNORED, "UNKNOWN_BUILD", null);
            }
            BuildStateMachine.BuildStatus incoming =
                    BuildStateMachine.BuildStatus.ofOrNull(text(payload, "status"));
            if (incoming == null || !incoming.persisted()) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "回调 status 非法：" + text(payload, "status"));
            }
            BuildStateMachine.BuildStatus current =
                    BuildStateMachine.BuildStatus.ofOrNull(build.getStatus());
            if (BuildStateMachine.isDuplicate(current, incoming)) {
                inbound(build, config, "duplicate " + incoming, SyncResult.IGNORED, STATUS_DUPLICATE);
                return new WebhookOutcome(STATUS_DUPLICATE, null, build.getId());
            }
            BuildStateMachine.BuildStatus target = BuildStateMachine.next(current, incoming);
            if (target == BuildStateMachine.BuildStatus.OUT_OF_ORDER) {
                inbound(build, config, "out of order " + current + "->" + incoming,
                        SyncResult.IGNORED, STATUS_OUT_OF_ORDER);
                audit.record(JenkinsTriggerService.ACTION_STATUS, "BUILD", build.getId(), Map.of(
                        "status", String.valueOf(current),
                        "rejected", incoming.name(),
                        "errorCode", STATUS_OUT_OF_ORDER));
                return new WebhookOutcome(STATUS_OUT_OF_ORDER, null, build.getId());
            }
            apply(build, config, incoming, fromPayload(payload), "WEBHOOK");
            inbound(build, config, "status " + incoming, SyncResult.SUCCESS, null);
            return new WebhookOutcome(STATUS_UPDATED, null, build.getId());
        } finally {
            TenantContext.clear();
        }
    }

    private void inbound(BuildRecordEntity build, IntegrationConfigEntity config, String summary,
                         SyncResult result, String errorCode) {
        syncLogWriter.writeInbound(build.getTenantId(), config.getId(), IntegrationSystem.JENKINS,
                "WEBHOOK_IN", "BUILD", build.getId(),
                "build#" + build.getId() + " " + summary, 200, result, errorCode);
    }

    private String webhookSecret(IntegrationConfigEntity config) {
        byte[] enc = config.getSecretEnc();
        if (enc == null || enc.length == 0) {
            throw new ApiException(401, "WEBHOOK_SIGNATURE_INVALID",
                    "该配置未设置 webhook secret，无法验签", Map.of("configId", config.getId()));
        }
        try {
            return crypto.decode(enc);
        } catch (RuntimeException e) {
            throw new ApiException(401, "WEBHOOK_SIGNATURE_INVALID",
                    "webhook secret 无法解密，请重新录入", Map.of("configId", config.getId()));
        }
    }

    private BuildRecordEntity resolve(IntegrationConfigEntity config, JsonNode payload) {
        Long buildId = longOrNull(payload, "buildId");
        if (buildId != null) {
            BuildRecordEntity byId = builds.findByIdAndTenantId(buildId, config.getTenantId())
                    .orElse(null);
            if (byId != null) {
                return byId;
            }
        }
        String jobName = text(payload, "jobName");
        Long buildNumber = longOrNull(payload, "buildNumber");
        if (buildNumber == null) {
            buildNumber = longOrNull(payload, "number");
        }
        if (jobName == null || buildNumber == null) {
            return null;
        }
        List<Long> ids = jdbc.query("""
                        SELECT id FROM build_record
                        WHERE tenant_id = ? AND job_name = ? AND build_number = ?
                        ORDER BY id DESC LIMIT 1
                        """,
                (rs, rowNum) -> rs.getLong(1), config.getTenantId(), jobName, buildNumber);
        if (ids.isEmpty()) {
            return null;
        }
        return builds.findByIdAndTenantId(ids.get(0), config.getTenantId()).orElse(null);
    }

    // ------------------------------------------------------------------ 状态落库

    /** 把一次观察落库：状态机判定 → 写状态/阶段/console/时长 → 终态释放 active_key → 审计 → 转缺陷。 */
    private void apply(BuildRecordEntity build, IntegrationConfigEntity config,
                       BuildStateMachine.BuildStatus incoming,
                       BuildViews.JenkinsStatus observation, String operation) {
        BuildStateMachine.BuildStatus current =
                BuildStateMachine.BuildStatus.ofOrNull(build.getStatus());
        if (incoming == null) {
            return;
        }
        if (BuildStateMachine.isDuplicate(current, incoming)) {
            return;
        }
        BuildStateMachine.BuildStatus target = BuildStateMachine.next(current, incoming);
        if (target == BuildStateMachine.BuildStatus.OUT_OF_ORDER) {
            audit.record(JenkinsTriggerService.ACTION_STATUS, "BUILD", build.getId(), Map.of(
                    "status", String.valueOf(current),
                    "rejected", incoming.name(),
                    "errorCode", STATUS_OUT_OF_ORDER,
                    "source", operation));
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        build.setStatus(target.name());
        if (target == BuildStateMachine.BuildStatus.RUNNING && build.getStartedAt() == null) {
            build.setStartedAt(now);
        }
        applyConsoleUrl(build, config, observation, operation);
        if (observation != null && observation.stages() != null && !observation.stages().isEmpty()) {
            build.setStagesJson(stagesJson(observation.stages()));
        }
        if (target.terminal()) {
            build.setFinishedAt(now);
            build.setActiveKey(null);
            Long duration = observation == null ? null : observation.durationMs();
            build.setDurationMs(duration != null && duration >= 0 ? duration : ageMillis(build, now));
        }
        builds.save(build);
        audit.record(JenkinsTriggerService.ACTION_STATUS, "BUILD", build.getId(), auditDetail(build, operation));
        if (target == BuildStateMachine.BuildStatus.FAILURE) {
            fileDefect(build, config, "构建失败");
        }
    }

    private void applyConsoleUrl(BuildRecordEntity build, IntegrationConfigEntity config,
                                 BuildViews.JenkinsStatus observation, String operation) {
        if (observation == null || observation.consoleUrl() == null || observation.consoleUrl().isBlank()) {
            return;
        }
        String safe = consoleGuard.sanitize(config.getBaseUrl(), observation.consoleUrl());
        if (safe == null) {
            // 外链由回调方给：不同源就不存（防钓鱼跳转），但这是「丢一个字段」，不是「丢一条构建」。
            build.setConsoleUrl(null);
            syncLogWriter.writeInbound(build.getTenantId(), config.getId(), IntegrationSystem.JENKINS,
                    JenkinsAdapter.OP_STATUS, "BUILD", build.getId(),
                    "build#" + build.getId() + " console url rejected",
                    200, SyncResult.IGNORED, CODE_CONSOLE_URL_REJECTED);
            log.warn("console url rejected for build {}: {}", build.getId(),
                    new SafeUrl(observation.consoleUrl()));
            return;
        }
        build.setConsoleUrl(safe);
    }

    /** 日志里只写 origin，别把可疑 URL 的 query 原样落盘（可能就是别人塞的凭据）。 */
    private record SafeUrl(String raw) {
        @Override
        public String toString() {
            try {
                java.net.URI uri = java.net.URI.create(raw);
                return uri.getScheme() + "://" + uri.getHost()
                        + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "/…";
            } catch (RuntimeException e) {
                return "<unparsable>";
            }
        }
    }

    private Map<String, Object> auditDetail(BuildRecordEntity build, String operation) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", build.getStatus());
        detail.put("source", operation);
        if (build.getBuildNumber() != null) {
            detail.put("buildNumber", build.getBuildNumber());
        }
        if (build.getDurationMs() != null) {
            detail.put("durationMs", build.getDurationMs());
        }
        return detail;
    }

    private static long ageMillis(BuildRecordEntity build, LocalDateTime now) {
        LocalDateTime from = build.getStartedAt() != null ? build.getStartedAt() : build.getCreatedAt();
        if (from == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(from, now).toMillis());
    }

    // ------------------------------------------------------------------ 转缺陷

    /**
     * 失败转缺陷（AC-3.6）：走 deliver 的内部端点，<b>不</b>直写缺陷表——缺陷的域/厂商归属
     * 与编码规则只在 deliver 一处实现。幂等键是 {@code job#buildNumber}，重复点击/重复回调
     * 都返回同一个缺陷。
     */
    private void fileDefect(BuildRecordEntity build, IntegrationConfigEntity config, String reason) {
        Long requirementId = build.getRequirementId();
        if (requirementId == null) {
            requirementId = firstRequirement(build.getTenantId(), build.getDeployOrderId());
        }
        if (requirementId == null) {
            log.warn("build {} has no requirement, skip defect filing", build.getId());
            return;
        }
        String key = build.getJobName() + "#" + (build.getBuildNumber() == null
                ? "build-" + build.getId() : build.getBuildNumber());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requirementId", requirementId);
        body.put("releaseId", releaseIdOf(build));
        body.put("title", "[" + build.getJobName() + " #"
                + (build.getBuildNumber() == null ? build.getId() : build.getBuildNumber())
                + "] 构建失败");
        body.put("description", defectDescription(build, reason, key));
        body.put("severity", "P1");
        body.put("idempotencyKey", key);
        try {
            Map<?, ?> response = internalTokenClient.deliver(Duration.ofMillis(4000))
                    .post()
                    .uri("/internal/defects/build-failure")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            Object code = response == null ? null : response.get("code");
            audit.record(JenkinsTriggerService.ACTION_STATUS, "BUILD", build.getId(), Map.of(
                    "status", build.getStatus(),
                    "defectCode", String.valueOf(code),
                    "idempotencyKey", key));
        } catch (RuntimeException e) {
            // 缺陷没建成不能把构建状态回滚掉：构建失败是事实，缺陷是后续动作，失败要留痕。
            log.warn("defect filing failed for build {}: {}", build.getId(), e.toString());
            notifier.warn(build.getTenantId(), "构建失败转缺陷未成功：" + build.getJobName(),
                    "构建已是 FAILURE，但缺陷未能创建（" + e.getClass().getSimpleName()
                            + "），可稍后在构建卡上重试", build.getId());
        }
    }

    private String defectDescription(BuildRecordEntity build, String reason, String key) {
        StringBuilder text = new StringBuilder();
        text.append("### 构建失败\n\n");
        text.append("- 作业：").append(build.getJobName()).append('\n');
        text.append("- 构建号：").append(build.getBuildNumber() == null ? "—" : build.getBuildNumber())
                .append('\n');
        text.append("- 原因：").append(reason).append('\n');
        if (build.getDurationMs() != null) {
            text.append("- 耗时：").append(build.getDurationMs()).append(" ms\n");
        }
        if (build.getConsoleUrl() != null) {
            text.append("- 控制台：").append(build.getConsoleUrl()).append('\n');
        } else {
            text.append("- 控制台：未通过同源校验，已丢弃外链\n");
        }
        List<BuildViews.BuildStage> stages = stages(build.getStagesJson());
        if (!stages.isEmpty()) {
            text.append("\n### 阶段\n\n");
            for (BuildViews.BuildStage stage : stages) {
                text.append("- ").append(stage.name()).append("：")
                        .append(stage.status() == null ? "UNKNOWN" : stage.status());
                if (stage.durationMs() != null) {
                    text.append("（").append(stage.durationMs()).append(" ms）");
                }
                text.append('\n');
            }
        }
        text.append("\nBUILD_KEY: ").append(key);
        return text.toString();
    }

    private Long releaseIdOf(BuildRecordEntity build) {
        return orders.findByIdAndTenantId(build.getDeployOrderId(), build.getTenantId())
                .map(DeployOrderEntity::getReleaseId)
                .orElse(null);
    }

    // ------------------------------------------------------------------ 视图

    private BuildViews.BuildView view(BuildRecordEntity build, Map<Long, String> names) {
        String triggeredBy = null;
        if (build.getTriggerBy() != null) {
            triggeredBy = names.getOrDefault(build.getTriggerBy(),
                    String.valueOf(build.getTriggerBy()));
        }
        return new BuildViews.BuildView(
                build.getId(),
                build.getDeployOrderId() == null ? 0L : build.getDeployOrderId(),
                build.getStatus(),
                build.getJobName(),
                build.getBuildNumber(),
                build.getQueueId(),
                build.getCreatedAt(),
                build.getStartedAt(),
                build.getFinishedAt(),
                build.getDurationMs(),
                stages(build.getStagesJson()),
                build.getConsoleUrl(),
                build.getTimeoutAt(),
                build.getPollFailCount() == null ? 0 : build.getPollFailCount(),
                timedOut(build),
                triggeredBy,
                build.getCorrelationId());
    }

    /**
     * 是否「超时未回写」。没有 error_code 列，所以由事实推导：终态 FAILURE 且结束时间
     * 不早于 timeout_at。正常失败的回调总在 2h 之内，因此这个推导不会误报。
     */
    private static boolean timedOut(BuildRecordEntity build) {
        return BuildStateMachine.BuildStatus.FAILURE.name().equals(build.getStatus())
                && build.getTimeoutAt() != null
                && build.getFinishedAt() != null
                && !build.getFinishedAt().isBefore(build.getTimeoutAt());
    }

    private List<BuildViews.BuildStage> stages(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode node = readTree(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<BuildViews.BuildStage> stages = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            String name = text(item, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            stages.add(new BuildViews.BuildStage(name, text(item, "status"),
                    longOrNull(item, "durationMs")));
        }
        return stages;
    }

    private String stagesJson(List<BuildViews.BuildStage> stages) {
        List<Map<String, Object>> payload = new ArrayList<>(stages.size());
        for (BuildViews.BuildStage stage : stages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", stage.name());
            item.put("status", stage.status());
            item.put("durationMs", stage.durationMs());
            payload.add(item);
        }
        return objectMapper.writeValueAsString(payload);
    }

    private Map<Long, String> userNames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT id, COALESCE(NULLIF(name, ''), username) AS display_name"
                + " FROM sys_user WHERE id IN (" + placeholders + ")";
        Map<Long, String> names = new LinkedHashMap<>();
        try {
            jdbc.query(sql, ps -> {
                int index = 1;
                for (Long id : ids) {
                    ps.setLong(index++, id);
                }
            }, rs -> {
                names.put(rs.getLong("id"), rs.getString("display_name"));
            });
        } catch (RuntimeException e) {
            log.debug("user name lookup failed: {}", e.toString());
        }
        return names;
    }

    private static Collection<Long> ids(BuildRecordEntity build) {
        return build.getTriggerBy() == null ? List.of() : List.of(build.getTriggerBy());
    }

    // ------------------------------------------------------------------ 工具

    private Long firstRequirement(long tenantId, Long deployOrderId) {
        List<DeployOrderRequirementEntity> links =
                orderRequirements.findByTenantIdAndDeployOrderIdOrderByRequirementIdAsc(
                        tenantId, deployOrderId);
        return links.isEmpty() ? null : links.get(0).getRequirementId();
    }

    private int timeoutMinutes() {
        int minutes = properties.getBuildTimeoutMinutes();
        return minutes <= 0 ? 120 : minutes;
    }

    private static boolean isActive(BuildRecordEntity build) {
        BuildStateMachine.BuildStatus status = BuildStateMachine.BuildStatus.ofOrNull(build.getStatus());
        return status != null && status.active();
    }

    /** 从 Jenkins 读回来的结果推断目标状态；读不到结论时返回 {@code null}（保持现状）。 */
    private static BuildStateMachine.BuildStatus observed(BuildViews.JenkinsStatus status) {
        if (status == null || !status.available()) {
            return null;
        }
        String result = status.result();
        if (result != null && !result.isBlank()) {
            return switch (result.trim().toUpperCase(Locale.ROOT)) {
                case "SUCCESS" -> BuildStateMachine.BuildStatus.SUCCESS;
                case "FAILURE", "UNSTABLE" -> BuildStateMachine.BuildStatus.FAILURE;
                case "ABORTED" -> BuildStateMachine.BuildStatus.ABORTED;
                default -> null;
            };
        }
        return status.building() ? BuildStateMachine.BuildStatus.RUNNING : null;
    }

    /** 回调载荷 → 观察结果（console/stages/duration 都带在载荷里，不需要再外呼一次）。 */
    private BuildViews.JenkinsStatus fromPayload(JsonNode payload) {
        Long buildNumber = longOrNull(payload, "buildNumber");
        if (buildNumber == null) {
            buildNumber = longOrNull(payload, "number");
        }
        return new BuildViews.JenkinsStatus(true, buildNumber, longOrNull(payload, "queueId"),
                false, text(payload, "status"), longOrNull(payload, "durationMs"),
                text(payload, "consoleUrl"), stages(payload.get("stages")), null, null);
    }

    private List<BuildViews.BuildStage> stages(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<BuildViews.BuildStage> stages = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            String name = text(item, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            stages.add(new BuildViews.BuildStage(name, text(item, "status"),
                    longOrNull(item, "durationMs")));
        }
        return stages;
    }

    private JsonNode readTree(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long longOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.asLong();
    }
}
