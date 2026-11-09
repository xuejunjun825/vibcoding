package com.example.req.integration.jenkins;

import com.example.platform.common.error.ApiException;
import com.example.req.integration.adapter.AdapterResult;
import com.example.req.integration.adapter.IntegrationAdapter;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationCrypto;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.outbound.OutboundHttpClient;
import com.example.req.integration.outbound.OutboundRequest;
import com.example.req.integration.outbound.OutboundResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jenkins 适配器：只做「Jenkins 的 JSON 长什么样 → 我们的 BuildViews 长什么样」的映射。
 *
 * <p>不建 RestClient/RestTemplate，不自己判 SSRF/重试/脱敏——那些都在
 * {@link OutboundHttpClient}。提交构建用 {@code exchangeWithRetry}（后台写，值得退避），
 * 查询构建用 {@code exchange}（交互读，页面等不起 1+2+4 秒）。
 *
 * <p>凭据只在这里解一次密：{@code credential_enc} 是 AES-GCM 密文，解不开就是配置坏了，
 * 直接 500 {@code INTEGRATION_CREDENTIAL_UNREADABLE}，绝不静默降级成匿名请求
 * （那会把「凭据错」伪装成「Jenkins 拒绝」，排障会绕远路）。</p>
 */
@Component
public class JenkinsAdapter implements IntegrationAdapter {

    /** 出站日志里的操作名（≤24 字符，与 integration_sync_log.operation 列宽一致）。 */
    public static final String OP_SUBMIT = "BUILD_SUBMIT";
    public static final String OP_STATUS = "BUILD_STATUS";
    public static final String OP_QUEUE = "BUILD_QUEUE";
    public static final String OP_TEST = "TEST_CONNECTION";

    /** 错误码：配置里没有 jobName（AC-3.2 的 400 BUILD_NOT_CONFIGURED）。 */
    public static final String CODE_NOT_CONFIGURED = "BUILD_NOT_CONFIGURED";
    /** 提交成功但 Jenkins 没有告诉我们任何构建/队列标识。 */
    public static final String CODE_NO_HANDLE = "BUILD_NO_HANDLE";

    private final OutboundHttpClient httpClient;
    private final IntegrationCrypto crypto;
    private final ObjectMapper objectMapper;

    public JenkinsAdapter(OutboundHttpClient httpClient,
                          IntegrationCrypto crypto,
                          ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    @Override
    public IntegrationSystem system() {
        return IntegrationSystem.JENKINS;
    }

    @Override
    public AdapterResult testConnection(IntegrationConfigEntity config) {
        long started = System.nanoTime();
        try {
            OutboundRequest request = OutboundRequest.builder(
                            config.getTenantId(), IntegrationSystem.JENKINS, OP_TEST,
                            HttpMethod.GET, trimTrailingSlash(config.getBaseUrl()) + "/api/json")
                    .headers(authHeaders(config))
                    .readTimeoutMillis(config.getTimeoutMs())
                    .build();
            OutboundResponse response = httpClient.exchange(request);
            int latency = (int) ((System.nanoTime() - started) / 1_000_000L);
            if (response.successful()) {
                return AdapterResult.success(latency);
            }
            return AdapterResult.failure(latency, codeOf(response),
                    response.hasHttpStatus() ? "HTTP " + response.httpStatus() : "Jenkins 不可达");
        } catch (RuntimeException e) {
            int latency = (int) ((System.nanoTime() - started) / 1_000_000L);
            return AdapterResult.failure(latency, "JENKINS_UNREACHABLE", e.getMessage());
        }
    }

    /**
     * 提交一次构建（AC-3.2）。返回 {@code queueId}（排队中）或 {@code buildNumber}
     * （Jenkins 直接给了构建号），两者都可能为空——真实 Jenkins 只回 201 + Location，
     * 而 {@link OutboundResponse} 不带响应头，所以由轮询用 lastBuild 兜底认领。
     */
    public BuildViews.JenkinsSubmit submit(IntegrationConfigEntity config,
                                           String jobName,
                                           Map<String, Object> params,
                                           String correlationId,
                                           Long buildId) {
        String path = "/job/" + jobPath(jobName) + "/buildWithParameters" + query(params);
        OutboundRequest request = OutboundRequest.builder(
                        config.getTenantId(), IntegrationSystem.JENKINS, OP_SUBMIT,
                        HttpMethod.POST, trimTrailingSlash(config.getBaseUrl()) + path)
                .headers(authHeaders(config))
                .readTimeoutMillis(config.getTimeoutMs())
                .correlationId(correlationId)
                .biz("BUILD", buildId)
                .build();
        OutboundResponse response = httpClient.exchangeWithRetry(request);
        if (!response.successful()) {
            return BuildViews.JenkinsSubmit.failed(response.httpStatus(), codeOf(response),
                    messageOf(response));
        }
        JsonNode node = readJson(response.body());
        Long queueId = longOrNull(node, "queueId");
        if (queueId == null) {
            queueId = longOrNull(node, "id");
        }
        Long buildNumber = longOrNull(node, "buildNumber");
        if (buildNumber == null) {
            buildNumber = longOrNull(node, "number");
        }
        String location = text(node, "url");
        return new BuildViews.JenkinsSubmit(queueId, buildNumber, location,
                response.httpStatus(), null, null);
    }

    /** 查询一次构建（交互读：单次尝试，失败返回 available=false，由调用方保留现有状态）。 */
    public BuildViews.JenkinsStatus status(IntegrationConfigEntity config, long buildNumber,
                                           Long buildId) {
        String path = "/job/" + jobPath(jobNameOf(config)) + "/" + buildNumber + "/api/json";
        OutboundResponse response = httpClient.exchange(OutboundRequest.builder(
                        config.getTenantId(), IntegrationSystem.JENKINS, OP_STATUS,
                        HttpMethod.GET, trimTrailingSlash(config.getBaseUrl()) + path)
                .headers(authHeaders(config))
                .readTimeoutMillis(config.getTimeoutMs())
                .biz("BUILD", buildId)
                .build());
        if (!response.successful()) {
            return BuildViews.JenkinsStatus.unavailable(response.httpStatus(), codeOf(response),
                    messageOf(response));
        }
        JsonNode node = readJson(response.body());
        if (node == null || !node.isObject()) {
            return BuildViews.JenkinsStatus.unavailable(response.httpStatus(), "INVALID_RESPONSE",
                    "Jenkins 返回的不是 JSON 对象");
        }
        boolean building = node.path("building").asBoolean(false);
        String result = text(node, "result");
        Long duration = longOrNull(node, "duration");
        String consoleUrl = text(node, "consoleUrl");
        if (consoleUrl == null) {
            String url = text(node, "url");
            if (url != null && !url.isBlank()) {
                consoleUrl = trimTrailingSlash(url) + "console";
            }
        }
        return new BuildViews.JenkinsStatus(true, buildNumber, longOrNull(node, "queueId"),
                building, result, duration, consoleUrl, stages(node), null, null);
    }

    /** 查询一次队列项：已分配构建号即「开始跑了」。 */
    public BuildViews.JenkinsStatus queueStatus(IntegrationConfigEntity config, long queueId,
                                                Long buildId) {
        String path = "/queue/item/" + queueId + "/api/json";
        OutboundResponse response = httpClient.exchange(OutboundRequest.builder(
                        config.getTenantId(), IntegrationSystem.JENKINS, OP_QUEUE,
                        HttpMethod.GET, trimTrailingSlash(config.getBaseUrl()) + path)
                .headers(authHeaders(config))
                .readTimeoutMillis(config.getTimeoutMs())
                .biz("BUILD", buildId)
                .build());
        if (!response.successful()) {
            return BuildViews.JenkinsStatus.unavailable(response.httpStatus(), codeOf(response),
                    messageOf(response));
        }
        JsonNode node = readJson(response.body());
        if (node == null || !node.isObject()) {
            return BuildViews.JenkinsStatus.unavailable(response.httpStatus(), "INVALID_RESPONSE",
                    "Jenkins 队列项不是 JSON 对象");
        }
        JsonNode executable = node.get("executable");
        Long buildNumber = executable != null && executable.isObject()
                ? longOrNull(executable, "number") : null;
        String consoleUrl = executable != null && executable.isObject()
                ? text(executable, "url") : null;
        return new BuildViews.JenkinsStatus(true, buildNumber, queueId, buildNumber == null,
                null, null, consoleUrl, List.of(), null, null);
    }

    /**
     * 认领兜底：提交没拿到任何标识时，用 lastBuild 把「刚提交的那个构建」认回来。
     * 只在没有 buildNumber 时调用一次。
     */
    public Long lastBuildNumber(IntegrationConfigEntity config, String jobName, Long buildId) {
        String path = "/job/" + jobPath(jobName) + "/lastBuild/api/json";
        OutboundResponse response = httpClient.exchange(OutboundRequest.builder(
                        config.getTenantId(), IntegrationSystem.JENKINS, OP_QUEUE,
                        HttpMethod.GET, trimTrailingSlash(config.getBaseUrl()) + path)
                .headers(authHeaders(config))
                .readTimeoutMillis(config.getTimeoutMs())
                .biz("BUILD", buildId)
                .build());
        if (!response.successful()) {
            return null;
        }
        return longOrNull(readJson(response.body()), "number");
    }

    /**
     * 从配置的 {@code extra.jobName} 取作业名；没有就 400（不是 500——这是配置不全，
     * 用户能自己补）。
     */
    public String jobNameOf(IntegrationConfigEntity config) {
        String job = extra(config, "jobName");
        if (job == null || job.isBlank()) {
            throw new ApiException(400, CODE_NOT_CONFIGURED,
                    "Jenkins 配置缺少 jobName，请先在集成配置里补全",
                    Map.of("configId", config.getId()));
        }
        return job.trim();
    }

    /** 配置 {@code extra} 里的字符串值（extra 是 JSON 文本，坏了就当没有）。 */
    public String extra(IntegrationConfigEntity config, String key) {
        String raw = config.getExtraJson();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonNode node = readJson(raw);
        if (node == null || !node.isObject()) {
            return null;
        }
        return text(node, key);
    }

    private Map<String, String> authHeaders(IntegrationConfigEntity config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        String authType = config.getAuthType() == null
                ? "TOKEN" : config.getAuthType().trim().toUpperCase();
        if ("NONE".equals(authType)) {
            return headers;
        }
        String credential;
        try {
            credential = crypto.decode(config.getCredentialEnc());
        } catch (RuntimeException e) {
            throw new ApiException(500, "INTEGRATION_CREDENTIAL_UNREADABLE",
                    "Jenkins 凭据无法解密，请重新录入", Map.of("configId", config.getId()));
        }
        if (credential == null || credential.isBlank()) {
            return headers;
        }
        // Jenkins 的 API Token 就是 Basic 认证里的密码；TOKEN 与 BASIC 同一条路。
        String user = config.getUsername() == null ? "" : config.getUsername();
        String raw = user + ":" + credential;
        headers.put("Authorization", "Basic "
                + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        return headers;
    }

    private static String jobPath(String jobName) {
        StringBuilder out = new StringBuilder(jobName.length());
        String[] segments = jobName.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            // URLEncoder 会把空格编成 '+'，路径里必须是 %20
            out.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    private static String query(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (!first) {
                out.append('&');
            }
            first = false;
            out.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return first ? "" : out.toString();
    }

    private static List<BuildViews.BuildStage> stages(JsonNode node) {
        JsonNode array = node.get("stages");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<BuildViews.BuildStage> stages = new ArrayList<>(array.size());
        for (JsonNode stage : array) {
            String name = text(stage, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            stages.add(new BuildViews.BuildStage(name, text(stage, "status"),
                    longOrNull(stage, "durationMs")));
        }
        return stages;
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

    private static String codeOf(OutboundResponse response) {
        if (response.errorCode() != null && !response.errorCode().isBlank()) {
            return response.errorCode();
        }
        return response.hasHttpStatus() ? "HTTP_" + response.httpStatus() : "JENKINS_UNREACHABLE";
    }

    private static String messageOf(OutboundResponse response) {
        if (response.hasHttpStatus()) {
            String body = response.body();
            if (body == null || body.isBlank()) {
                return "HTTP " + response.httpStatus();
            }
            return "HTTP " + response.httpStatus() + ": "
                    + (body.length() > 300 ? body.substring(0, 300) : body);
        }
        return response.errorCode() == null ? "Jenkins 不可达" : response.errorCode();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
