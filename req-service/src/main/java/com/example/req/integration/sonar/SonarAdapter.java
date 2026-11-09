package com.example.req.integration.sonar;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * Sonar 适配器：只做「配置 + projectKey → Sonar REST → {@link SonarViews.SonarMeasures}」
 * 的协议映射（TECHNICAL §4.4）。
 *
 * <p>与其他适配器同构：不建 RestClient/RestTemplate，不自己判 SSRF/超时/重试/脱敏——
 * 那些全在 {@link OutboundHttpClient}。刷新快照是交互动作，用单次尝试（{@code exchange}）：
 * 用户点「刷新」时页面等不起 1+2+4 秒的退避，拿不到就降级返回，由服务层给出
 * {@code UNAVAILABLE} + 具体原因（AC-6.3 的 fail-closed 只在服务层决定）。</p>
 *
 * <p>取数分两次 HTTP：质量门结论（{@code /api/qualitygates/project_status}）与五度量
 * （{@code /api/measures/component}）。Sonar 没有把两者合成一个稳定接口，服务层也不该
 * 假装有——任一失败即整次降级。</p>
 */
@Component
public class SonarAdapter implements IntegrationAdapter {

    /** 出站日志里的操作名（≤24 字符，与 SyncLogWriter 的列宽一致）。 */
    public static final String OP_GATE = "SONAR_GATE";
    public static final String OP_MEASURES = "SONAR_MEASURES";
    public static final String OP_TEST = "TEST_CONNECTION";

    private static final String GATE_PATH = "/api/qualitygates/project_status?projectKey=";
    private static final String MEASURES_PATH = "/api/measures/component?component=";
    private static final String MEASURE_KEYS = String.join(",", SonarThresholds.KEYS);

    private final OutboundHttpClient httpClient;
    private final IntegrationCrypto crypto;
    private final ObjectMapper objectMapper;

    public SonarAdapter(OutboundHttpClient httpClient,
                        IntegrationCrypto crypto,
                        ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    @Override
    public IntegrationSystem system() {
        return IntegrationSystem.SONAR;
    }

    /**
     * 连通性测试走 {@code /api/authentication/validate}：它同时验证「服务在」与「凭据有效」，
     * 而 {@code /api/system/status} 需要管理员权限、{@code /api/server/version} 不校验凭据，
     * 两者都会把"token 写错"误报成连通。
     */
    @Override
    public AdapterResult testConnection(IntegrationConfigEntity config) {
        long started = System.nanoTime();
        try {
            OutboundRequest request = baseRequest(config, OP_TEST, "/api/authentication/validate")
                    .build();
            OutboundResponse response = httpClient.exchange(request);
            int latency = (int) ((System.nanoTime() - started) / 1_000_000L);
            if (!response.successful()) {
                return AdapterResult.failure(latency, codeOf(response), messageOf(response));
            }
            JsonNode node = readJson(response.body());
            if (node != null && node.has("valid") && !node.get("valid").asBoolean(true)) {
                return AdapterResult.failure(latency, "SONAR_AUTH_INVALID",
                        "凭据未通过 Sonar 校验（valid=false）");
            }
            return AdapterResult.success(latency);
        } catch (RuntimeException e) {
            int latency = (int) ((System.nanoTime() - started) / 1_000_000L);
            return AdapterResult.failure(latency, "SONAR_UNREACHABLE", e.getMessage());
        }
    }

    /**
     * 拉一次质量门 + 五度量。任何一步失败都返回降级结果，不抛异常：页面要能显示
     * 「拉取失败，准入第 8 项计为失败」而不是白屏（DESIGN「Sonar 卡」错误态）。
     */
    public SonarViews.SonarMeasures measures(IntegrationConfigEntity config, String projectKey,
                                             Long requirementId) {
        String encodedKey = encode(projectKey);
        OutboundResponse gateResponse = httpClient.exchange(baseRequest(config, OP_GATE,
                GATE_PATH + encodedKey).biz("SONAR", requirementId).build());
        if (!gateResponse.successful()) {
            return SonarViews.SonarMeasures.degraded(codeOf(gateResponse), messageOf(gateResponse));
        }
        JsonNode gateNode = readJson(gateResponse.body());
        JsonNode projectStatus = gateNode == null ? null : gateNode.get("projectStatus");
        if (projectStatus == null || projectStatus.isNull() || !projectStatus.isObject()) {
            return SonarViews.SonarMeasures.degraded("INVALID_RESPONSE",
                    "Sonar 质量门应答缺少 projectStatus");
        }
        String gateStatus = normalizeGateStatus(text(projectStatus, "status"));
        if (gateStatus == null) {
            return SonarViews.SonarMeasures.degraded("INVALID_RESPONSE",
                    "Sonar 质量门状态无法识别：" + text(projectStatus, "status"));
        }
        List<SonarViews.ConditionView> conditions = conditions(projectStatus.get("conditions"));

        OutboundResponse measuresResponse = httpClient.exchange(baseRequest(config, OP_MEASURES,
                MEASURES_PATH + encodedKey + "&metricKeys=" + MEASURE_KEYS).biz("SONAR", requirementId).build());
        if (!measuresResponse.successful()) {
            return SonarViews.SonarMeasures.degraded(codeOf(measuresResponse), messageOf(measuresResponse));
        }
        JsonNode measuresNode = readJson(measuresResponse.body());
        JsonNode component = measuresNode == null ? null : measuresNode.get("component");
        if (component == null || component.isNull() || !component.isObject()) {
            return SonarViews.SonarMeasures.degraded("INVALID_RESPONSE",
                    "Sonar 度量应答缺少 component");
        }
        Map<String, String> metrics = new LinkedHashMap<>();
        JsonNode array = component.get("measures");
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                String metric = text(item, "metric");
                String value = text(item, "value");
                if (metric != null && value != null) {
                    metrics.put(metric, value);
                }
            }
        }
        return new SonarViews.SonarMeasures(gateStatus, metrics, conditions, false, null, null);
    }

    // ------------------------------------------------------------------ mapping

    private List<SonarViews.ConditionView> conditions(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<SonarViews.ConditionView> conditions = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            String status = normalizeConditionStatus(text(item, "status"));
            if (!SonarThresholds.ERROR.equals(status) && !SonarThresholds.WARN.equals(status)) {
                continue;
            }
            String metric = text(item, "metricKey");
            conditions.add(new SonarViews.ConditionView(metric,
                    SonarThresholds.labelOf(metric),
                    text(item, "actualValue"),
                    text(item, "errorThreshold"),
                    text(item, "comparator"),
                    status));
        }
        return conditions;
    }

    private static String normalizeGateStatus(String raw) {
        String value = SonarThresholds.upper(raw);
        if (SonarViews.STATUS_OK.equals(value) || SonarViews.STATUS_ERROR.equals(value)) {
            return value;
        }
        return null;
    }

    private static String normalizeConditionStatus(String raw) {
        String value = SonarThresholds.upper(raw);
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "OK" -> SonarThresholds.OK;
            case "WARN" -> SonarThresholds.WARN;
            default -> SonarThresholds.ERROR;
        };
    }

    // ------------------------------------------------------------------ outbound

    private OutboundRequest.Builder baseRequest(IntegrationConfigEntity config,
                                                String operation,
                                                String path) {
        String url = trimTrailingSlash(config.getBaseUrl()) + path;
        return OutboundRequest.builder(config.getTenantId(), IntegrationSystem.SONAR, operation,
                        HttpMethod.GET, url)
                .headers(authHeaders(config))
                .readTimeoutMillis(config.getTimeoutMs());
    }

    /**
     * 认证头：token 只在这里解一次密。解不开是配置坏了，直接 500
     * {@code INTEGRATION_CREDENTIAL_UNREADABLE}，绝不静默降级成匿名请求
     * （那会把「凭据错」伪装成「Sonar 拒绝」）。
     */
    private Map<String, String> authHeaders(IntegrationConfigEntity config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        String authType = config.getAuthType() == null
                ? "TOKEN" : config.getAuthType().trim().toUpperCase(Locale.ROOT);
        if ("NONE".equals(authType)) {
            return headers;
        }
        String credential;
        try {
            credential = crypto.decode(config.getCredentialEnc());
        } catch (RuntimeException e) {
            throw new ApiException(500, "INTEGRATION_CREDENTIAL_UNREADABLE",
                    "Sonar 凭据无法解密，请重新录入", Map.of("configId", config.getId()));
        }
        if (credential == null || credential.isBlank()) {
            return headers;
        }
        if ("BASIC".equals(authType)) {
            String user = config.getUsername() == null ? "" : config.getUsername();
            String raw = user + ":" + credential;
            headers.put("Authorization", "Basic "
                    + java.util.Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        } else {
            headers.put("Authorization", "Bearer " + credential);
        }
        return headers;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String codeOf(OutboundResponse response) {
        if (response.errorCode() != null && !response.errorCode().isBlank()) {
            return response.errorCode();
        }
        return response.hasHttpStatus() ? "HTTP_" + response.httpStatus() : "TRANSPORT_ERROR";
    }

    private static String messageOf(OutboundResponse response) {
        return response.hasHttpStatus() ? "Sonar 返回 HTTP " + response.httpStatus() : "Sonar 不可达";
    }
}
