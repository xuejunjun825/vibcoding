package com.example.req.integration.gitlab;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import com.example.platform.common.error.ApiException;
import com.example.req.integration.adapter.AdapterResult;
import com.example.req.integration.adapter.IntegrationAdapter;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationCrypto;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.outbound.OutboundHttpClient;
import com.example.req.integration.outbound.OutboundRequest;
import com.example.req.integration.outbound.OutboundResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitLab 适配器：只做「配置 + project/iid → GitLab REST → {@link MrViews.MrPayload}」的映射。
 *
 * <p>不建 RestClient/RestTemplate，也不自己判 SSRF/重试/脱敏——那些全在
 * {@link OutboundHttpClient}。MR 读取是交互读，用单次尝试（{@code exchange}）：
 * 页面等不起 1+2+4 秒的退避，读不到就返回降级载荷，让卡片显示「暂不可用」。</p>
 *
 * <p>认证按 {@code auth_type} 分流：{@code TOKEN}/{@code PRIVATE_TOKEN} 走
 * {@code PRIVATE-TOKEN} 头（GitLab 的惯例），{@code BASIC} 走 Basic，
 * {@code NONE} 不带凭据（本地桩默认无鉴权也能读）。</p>
 */
@Component
public class GitLabAdapter implements IntegrationAdapter {

    /** 出站日志里的操作名（≤24 字符，与 SyncLogWriter 的列宽一致）。 */
    public static final String OP_MR = "MR_SYNC";
    public static final String OP_MR_LIST = "MR_FALLBACK_POLL";
    public static final String OP_TEST = "TEST_CONNECTION";

    private static final int DEFAULT_RECENT_DAYS = 7;
    private static final int MAX_RECENT = 50;

    private final OutboundHttpClient httpClient;
    private final IntegrationCrypto crypto;
    private final ObjectMapper objectMapper;

    public GitLabAdapter(OutboundHttpClient httpClient,
                         IntegrationCrypto crypto,
                         ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    @Override
    public IntegrationSystem system() {
        return IntegrationSystem.GITLAB;
    }

    @Override
    public AdapterResult testConnection(IntegrationConfigEntity config) {
        long started = System.nanoTime();
        try {
            OutboundRequest request = baseRequest(config, OP_TEST, "/api/v4/version", null)
                    .build();
            OutboundResponse response = httpClient.exchange(request);
            int latency = (int) ((System.nanoTime() - started) / 1_000_000L);
            if (response.successful()) {
                return AdapterResult.success(latency);
            }
            return AdapterResult.failure(latency, codeOf(response),
                    response.hasHttpStatus() ? "HTTP " + response.httpStatus() : "GitLab 不可达");
        } catch (RuntimeException e) {
            int latency = (int) ((System.nanoTime() - started) / 1_000_000L);
            return AdapterResult.failure(latency, "GITLAB_UNREACHABLE", e.getMessage());
        }
    }

    /**
     * 读取一条 MR。GitLab 没答上来时返回降级载荷（{@code state=null} + {@code errorCode}），
     * 不抛异常：调用方仍要保存用户填的链接（AC-4.1）。
     */
    public MrViews.MrPayload fetchMergeRequest(IntegrationConfigEntity config, String project, long iid,
                                               Long requirementId) {
        String path = "/api/v4/projects/" + MrUrlParser.encodeProject(project) + "/merge_requests/" + iid;
        OutboundRequest request = baseRequest(config, OP_MR, path, null)
                .biz("MR", requirementId)
                .build();
        OutboundResponse response = httpClient.exchange(request);
        if (!response.successful()) {
            return MrViews.MrPayload.degraded(project, iid, mrUrl(config, project, iid), codeOf(response));
        }
        JsonNode node = readJson(response.body());
        if (node == null || !node.isObject()) {
            return MrViews.MrPayload.degraded(project, iid, mrUrl(config, project, iid), "INVALID_RESPONSE");
        }
        return toPayload(project, iid, node, response.body());
    }

    /**
     * 兜底轮询：列出项目最近 {@code days} 天更新过的 MR。webhook 丢失时靠它收敛状态。
     * 失败返回空列表（不抛）——轮询是后台任务，一次失败不该让整轮 tick 崩掉。
     */
    public List<MrViews.MrPayload> recentMergeRequests(IntegrationConfigEntity config, String project, int days) {
        int window = days <= 0 ? DEFAULT_RECENT_DAYS : days;
        String updatedAfter = Instant.now().minus(window, ChronoUnit.DAYS).toString();
        String path = "/api/v4/projects/" + MrUrlParser.encodeProject(project)
                + "/merge_requests?state=all&per_page=" + MAX_RECENT + "&updated_after=" + updatedAfter;
        OutboundRequest request = baseRequest(config, OP_MR_LIST, path, null)
                .biz("MR", null)
                .build();
        OutboundResponse response = httpClient.exchange(request);
        if (!response.successful()) {
            return List.of();
        }
        JsonNode node = readJson(response.body());
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<MrViews.MrPayload> payloads = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            long iid = item.path("iid").asLong(0L);
            if (iid <= 0) {
                continue;
            }
            payloads.add(toPayload(project, iid, item, item.toString()));
        }
        return payloads;
    }

    private MrViews.MrPayload toPayload(String project, long iid, JsonNode node, String rawJson) {
        String state = MrViews.normalizeState(text(node, "state"));
        String url = text(node, "web_url");
        if (url == null || url.isBlank()) {
            url = mrUrl(null, project, iid);
        }
        return new MrViews.MrPayload(
                project,
                iid,
                url,
                text(node, "title"),
                state,
                text(node, "source_branch"),
                text(node, "target_branch"),
                approvals(node),
                integer(node, "additions"),
                integer(node, "deletions"),
                MrViews.parseIso(text(node, "updated_at")),
                MrViews.parseIso(text(node, "merged_at")),
                truncate(rawJson, 8000),
                null);
    }

    /** GitLab 的 MR 应答里带 {@code approved_by} 数组；没有该字段时按 0 处理而不是猜。 */
    private static Integer approvals(JsonNode node) {
        JsonNode approvedBy = node.get("approved_by");
        if (approvedBy != null && approvedBy.isArray()) {
            return approvedBy.size();
        }
        JsonNode count = node.get("approvals_count");
        return count == null || count.isNull() ? 0 : count.asInt(0);
    }

    private OutboundRequest.Builder baseRequest(IntegrationConfigEntity config, String operation,
                                                String path, Object body) {
        String url = trimTrailingSlash(config.getBaseUrl()) + path;
        OutboundRequest.Builder builder = OutboundRequest.builder(
                        config.getTenantId(), IntegrationSystem.GITLAB, operation,
                        body == null ? HttpMethod.GET : HttpMethod.POST, url)
                .headers(authHeaders(config))
                .readTimeoutMillis(config.getTimeoutMs())
                .body(body);
        return builder;
    }

    /**
     * 认证头。token 只在这里解一次密：{@code credential_enc} 是 AES-GCM 密文，
     * 解不开就是配置坏了，直接 500 {@code INTEGRATION_CREDENTIAL_UNREADABLE}，
     * 不要静默降级成匿名请求（那会把「凭据错」伪装成「GitLab 拒绝」）。
     */
    private Map<String, String> authHeaders(IntegrationConfigEntity config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        String authType = config.getAuthType() == null ? "TOKEN" : config.getAuthType().trim().toUpperCase();
        if ("NONE".equals(authType)) {
            return headers;
        }
        String credential;
        try {
            credential = crypto.decode(config.getCredentialEnc());
        } catch (RuntimeException e) {
            throw new ApiException(500, "INTEGRATION_CREDENTIAL_UNREADABLE",
                    "GitLab 凭据无法解密，请重新录入", Map.of("configId", config.getId()));
        }
        if (credential == null || credential.isBlank()) {
            return headers;
        }
        if ("BASIC".equals(authType)) {
            String user = config.getUsername() == null ? "" : config.getUsername();
            String raw = user + ":" + credential;
            headers.put("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        } else {
            headers.put("PRIVATE-TOKEN", credential);
        }
        return headers;
    }

    private String mrUrl(IntegrationConfigEntity config, String project, long iid) {
        String base = config == null ? "" : trimTrailingSlash(config.getBaseUrl());
        return base + "/" + project + "/-/merge_requests/" + iid;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
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
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String codeOf(OutboundResponse response) {
        if (response.errorCode() != null && !response.errorCode().isBlank()) {
            return response.errorCode();
        }
        return response.hasHttpStatus() ? "HTTP_" + response.httpStatus() : "TRANSPORT_ERROR";
    }

    /** 单次出站读的显式超时（页面预算）；为 null 时由 OutboundHttpClient 取配置默认值。 */
    static Duration pageTimeout() {
        return Duration.ofSeconds(5);
    }
}
