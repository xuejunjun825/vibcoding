package com.example.req.integration.gitlab;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationConfigRepository;
import com.example.req.integration.config.IntegrationCrypto;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.security.WebhookRateLimiter;
import com.example.req.integration.security.WebhookSignatureVerifier;
import com.example.req.integration.sync.SyncLogWriter;
import com.example.req.integration.sync.SyncResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitLab webhook 入口：{@code POST /integration/webhooks/gitlab/{configId}}。
 *
 * <p>这个端点没有 JWT（GitLab 不会带令牌），因此它自己的安全边界必须完整：
 * ① 按 configId 找到配置并解出 secret；② {@code X-Gitlab-Token} 常量时间比较；
 * ③ 按 configId 限流 20 req/s。三步都在**任何业务写之前**完成——验签失败只写一条
 * {@code IN/FAILED} 日志（不含 body），不碰 mr_sync（AC-4.4）。</p>
 *
 * <p>返回码语义：2xx 表示「收到并处理了（或有意忽略）」，GitLab 不会重投；
 * 401 只给验签失败；429 只给超频。未知事件一律 200 + {@code IGNORED}，
 * 否则 GitLab 会对一个它自己加的新事件类型重投到天荒地老。</p>
 */
@RestController
@RequestMapping("/integration/webhooks/gitlab")
public class GitlabWebhookController {

    public static final String EVENT_MERGE_REQUEST = "Merge Request Hook";
    public static final String EVENT_PUSH = "Push Hook";
    public static final String EVENT_PIPELINE = "Pipeline Hook";
    public static final String EVENT_SYSTEM = "System Hook";

    public static final String CODE_CONFIG_NOT_FOUND = "INTEGRATION_CONFIG_NOT_FOUND";
    public static final String CODE_PAYLOAD_INVALID = "WEBHOOK_PAYLOAD_INVALID";

    private static final String BIZ_TYPE = "MR";
    private static final String OP_MR_EVENT = "MR_WEBHOOK";
    private static final int MAX_RAW = 8000;

    private final IntegrationConfigRepository configRepository;
    private final IntegrationCrypto crypto;
    private final WebhookSignatureVerifier verifier;
    private final WebhookRateLimiter rateLimiter;
    private final MrSyncService mrSyncService;
    private final SyncLogWriter syncLogWriter;
    private final ObjectMapper objectMapper;

    public GitlabWebhookController(IntegrationConfigRepository configRepository,
                                   IntegrationCrypto crypto,
                                   WebhookSignatureVerifier verifier,
                                   WebhookRateLimiter rateLimiter,
                                   MrSyncService mrSyncService,
                                   SyncLogWriter syncLogWriter,
                                   ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.crypto = crypto;
        this.verifier = verifier;
        this.rateLimiter = rateLimiter;
        this.mrSyncService = mrSyncService;
        this.syncLogWriter = syncLogWriter;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{configId}")
    public ResponseEntity<Map<String, Object>> onGitlab(
            @PathVariable long configId,
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String event) {

        IntegrationConfigEntity config = configRepository.findById(configId).orElseThrow(
                () -> new ApiException(404, CODE_CONFIG_NOT_FOUND, "集成配置不存在"));
        if (!config.isActive()) {
            throw new ApiException(409, "INTEGRATION_DISABLED", "GitLab 集成未启用");
        }
        if (!rateLimiter.allow(configId)) {
            logInbound(config.getTenantId(), configId, null, "webhook 超频", 429,
                    SyncResult.FAILED, WebhookRateLimiter.CODE_RATE_LIMITED);
            throw new ApiException(429, WebhookRateLimiter.CODE_RATE_LIMITED,
                    "webhook 请求过于频繁，已限流");
        }

        TenantContext.set(DataScope.system(config.getTenantId()));
        try {
            verify(config, token);
            return handle(config, rawBody, event);
        } finally {
            TenantContext.clear();
        }
    }

    private void verify(IntegrationConfigEntity config, String token) {
        String secret;
        try {
            secret = config.getSecretEnc() == null ? null : crypto.decode(config.getSecretEnc());
        } catch (RuntimeException e) {
            secret = null;
        }
        try {
            verifier.verifyGitlab(secret, token);
        } catch (ApiException e) {
            // 401 不落业务行：只记一条不含 body 的失败日志
            logInbound(config.getTenantId(), config.getId(), null, "webhook 验签失败", 401,
                    SyncResult.FAILED, e.code());
            throw e;
        }
    }

    private ResponseEntity<Map<String, Object>> handle(IntegrationConfigEntity config,
                                                       byte[] rawBody,
                                                       String event) {
        String eventName = event == null ? "" : event.trim();
        if (!EVENT_MERGE_REQUEST.equalsIgnoreCase(eventName)) {
            boolean known = EVENT_PUSH.equalsIgnoreCase(eventName)
                    || EVENT_PIPELINE.equalsIgnoreCase(eventName)
                    || EVENT_SYSTEM.equalsIgnoreCase(eventName);
            String reason = known ? "EVENT_NOT_TRACKED" : "UNKNOWN_EVENT";
            logInbound(config.getTenantId(), config.getId(), null,
                    "webhook 忽略：" + (eventName.isBlank() ? "NO_EVENT_HEADER" : eventName), 200,
                    SyncResult.IGNORED, reason);
            return body(200, "IGNORED", reason);
        }

        JsonNode root = readJson(rawBody);
        MrViews.MrPayload payload = toPayload(root, rawBody);
        MrViews.ApplyOutcome outcome = mrSyncService.applyPayload(
                config.getTenantId(), config.getId(), payload);

        SyncResult result = outcome == MrViews.ApplyOutcome.APPLIED
                ? SyncResult.SUCCESS : SyncResult.IGNORED;
        Long requirementId = mrSyncService.requirementIdOf(
                config.getTenantId(), payload.gitlabProject(), payload.mrIid());
        logInbound(config.getTenantId(), config.getId(), requirementId,
                "MR !" + payload.mrIid() + " " + payload.state(), 200, result,
                outcome == MrViews.ApplyOutcome.APPLIED ? null : outcome.name());
        return body(200, outcome.name(), outcome == MrViews.ApplyOutcome.APPLIED ? null : outcome.name());
    }

    /**
     * Merge Request Hook → 统一载荷。GitLab 用 {@code action} 表达「这次发生了什么」，
     * 用 {@code state} 表达「现在是什么状态」；两者都要看：重投的 open 事件
     * {@code state=opened}，而 merge 事件可能是 {@code action=merge, state=merged}。
     */
    private MrViews.MrPayload toPayload(JsonNode root, byte[] rawBody) {
        JsonNode project = root == null ? null : root.get("project");
        JsonNode attrs = root == null ? null : root.get("object_attributes");
        String projectPath = project == null ? null : text(project, "path_with_namespace");
        long iid = attrs == null ? 0L : attrs.path("iid").asLong(0L);
        if (projectPath == null || projectPath.isBlank() || iid <= 0) {
            throw new ApiException(400, CODE_PAYLOAD_INVALID, "GitLab 事件缺少 project/iid");
        }
        String action = attrs == null ? null : text(attrs, "action");
        String state = attrs == null ? null : text(attrs, "state");
        boolean merged = "merge".equalsIgnoreCase(action) || "merged".equalsIgnoreCase(state);
        String normalized = merged ? MrViews.STATE_MERGED : MrViews.normalizeState(state);
        String updatedAt = text(attrs, "updated_at");
        String mergedAt = text(attrs, "merged_at");
        String raw = rawBody == null ? null : new String(rawBody, StandardCharsets.UTF_8);

        return new MrViews.MrPayload(
                projectPath,
                iid,
                text(attrs, "url"),
                text(attrs, "title"),
                normalized,
                text(attrs, "source_branch"),
                text(attrs, "target_branch"),
                null,
                null,
                null,
                MrViews.parseIso(updatedAt),
                merged ? MrViews.parseIso(mergedAt == null ? updatedAt : mergedAt) : null,
                truncate(raw, MAX_RAW),
                null);
    }

    private JsonNode readJson(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new ApiException(400, CODE_PAYLOAD_INVALID, "GitLab 事件体为空");
        }
        try {
            return objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new ApiException(400, CODE_PAYLOAD_INVALID, "GitLab 事件体不是合法 JSON");
        }
    }

    /**
     * 入站日志。字段口径（direction=IN、摘要脱敏、不写 body、{@code biz_type='MR'}）全部由
     * {@link SyncLogWriter} 收口——这里只给业务锚点，不再自己拼一行 entity，否则
     * 「哪类集成」列会出现第二份实现，将来加字段就会漂移。
     *
     * <p>{@code bizId} 是该 MR 所属的 {@code requirement.id}（需求详情「集成」tab 的数据作用域，
     * DESIGN §I4）。webhook 载荷只有 project+iid，所以在 apply 之后用
     * {@link MrSyncService#requirementIdOf} 反查一次；反查不到（未绑定需求）就留 NULL，
     * 不猜、不写别的需求的 id。</p>
     */
    private void logInbound(long tenantId, long configId, Long requirementId, String summary,
                            int httpStatus, SyncResult result, String errorCode) {
        syncLogWriter.writeInbound(tenantId, configId, IntegrationSystem.GITLAB, OP_MR_EVENT,
                BIZ_TYPE, requirementId, summary, httpStatus, result, errorCode);
    }

    private static ResponseEntity<Map<String, Object>> body(int status, String outcome, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", outcome);
        if (reason != null) {
            payload.put("reason", reason);
        }
        return ResponseEntity.status(status).body(payload);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
