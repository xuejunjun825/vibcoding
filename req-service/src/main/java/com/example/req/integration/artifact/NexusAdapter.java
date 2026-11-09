package com.example.req.integration.artifact;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
 * Nexus 适配器：只做「配置 + 坐标 → Nexus REST → {@link ArtifactViews.ArtifactItem}」的映射。
 *
 * <p>不建 RestClient/RestTemplate，也不自己判 SSRF/超时/脱敏——那些全在
 * {@link OutboundHttpClient}。检索是交互读，用 {@code exchange} 单次尝试：页面等不起
 * 1+2+4 秒的退避，读不到就返回 {@code degraded} 让页面显示原因。</p>
 *
 * <p><b>校验和为什么走检索而不是 HEAD</b>：出站客户端只回传状态/正文，不透出响应头，
 * 而 HEAD 的 {@code X-Checksum-Sha256} 正好在头里。Nexus 的检索应答本来就带
 * {@code assets[].checksum.sha256}，所以按坐标精确回查是等价且更可测的路径；这也让
 * 「登记 / 晋级」两次核对共用同一个函数，不需要为头解析再开一条出站通道。</p>
 */
@Component
public class NexusAdapter implements IntegrationAdapter {

    /** 出站日志里的操作名（≤24 字符，与 SyncLogWriter 的列宽一致）。 */
    public static final String OP_SEARCH = "ARTIFACT_SEARCH";
    public static final String OP_TEST = "TEST_CONNECTION";

    public static final String CODE_CREDENTIAL_UNREADABLE = "INTEGRATION_CREDENTIAL_UNREADABLE";
    public static final String CODE_INVALID_RESPONSE = "INVALID_RESPONSE";

    /** PRD AC-7.1：一次检索最多 50 条。 */
    public static final int MAX_LIMIT = 50;
    private static final int DEFAULT_REPOSITORY_LIMIT = 50;

    private final OutboundHttpClient httpClient;
    private final IntegrationCrypto crypto;
    private final ObjectMapper objectMapper;

    public NexusAdapter(OutboundHttpClient httpClient,
                        IntegrationCrypto crypto,
                        ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    @Override
    public IntegrationSystem system() {
        return IntegrationSystem.NEXUS;
    }

    /** 连通性测试：Nexus 的 {@code /service/rest/v1/status} 是只读探针（AC-1.3）。 */
    @Override
    public AdapterResult testConnection(IntegrationConfigEntity config) {
        long started = System.nanoTime();
        try {
            OutboundRequest request = OutboundRequest.builder(config.getTenantId(),
                            IntegrationSystem.NEXUS, OP_TEST, HttpMethod.GET,
                            trimTrailingSlash(config.getBaseUrl()) + "/service/rest/v1/status")
                    .headers(authHeaders(config))
                    .readTimeoutMillis(config.getTimeoutMs())
                    .build();
            OutboundResponse response = httpClient.exchange(request);
            int latency = (int) ((System.nanoTime() - started) / 1_000_000L);
            if (response.successful()) {
                return AdapterResult.success(latency);
            }
            return AdapterResult.failure(latency, codeOf(response),
                    response.hasHttpStatus() ? "HTTP " + response.httpStatus() : "Nexus 不可达");
        } catch (RuntimeException ex) {
            int latency = (int) ((System.nanoTime() - started) / 1_000_000L);
            return AdapterResult.failure(latency, "NEXUS_UNREACHABLE", ex.getMessage());
        }
    }

    /**
     * 按关键字检索。repository 缺省取配置里的 {@code extra.repository}（设计口径），
     * limit 夹到 1..50；失败返回空列表（不存在「抛异常把页面打白」的路径）。
     */
    public List<ArtifactViews.ArtifactItem> search(IntegrationConfigEntity config, String query, int limit) {
        return searchResult(config, query, defaultRepository(config), null, limit).items();
    }

    /**
     * 检索的完整结果（含降级原因）。{@link ArtifactSearchService} 用它回答「检索失败」
     * 与「检索成功但没有匹配」这两种截然不同的情况。
     */
    public ArtifactViews.SearchResult searchResult(IntegrationConfigEntity config, String query,
                                                    String repository, String version, int limit) {
        if (config == null) {
            return ArtifactViews.SearchResult.degraded("NEXUS_NOT_CONFIGURED");
        }
        String repo = repository == null || repository.isBlank()
                ? defaultRepository(config) : repository.trim();
        int effectiveLimit = clamp(limit);
        String url = trimTrailingSlash(config.getBaseUrl())
                + "/service/rest/v1/search?repository=" + encode(repo)
                + (query == null || query.isBlank() ? "" : "&q=" + encode(query.trim()))
                + (version == null || version.isBlank() ? "" : "&version=" + encode(version.trim()));
        OutboundRequest request = OutboundRequest.builder(config.getTenantId(),
                        IntegrationSystem.NEXUS, OP_SEARCH, HttpMethod.GET, url)
                .headers(authHeaders(config))
                .readTimeoutMillis(config.getTimeoutMs())
                .biz("ARTIFACT", null)
                .build();
        OutboundResponse response = httpClient.exchange(request);
        if (!response.successful()) {
            return ArtifactViews.SearchResult.degraded(codeOf(response));
        }
        JsonNode root = readJson(response.body());
        if (root == null || !root.isObject() || !root.path("items").isArray()) {
            return ArtifactViews.SearchResult.degraded(CODE_INVALID_RESPONSE);
        }
        List<ArtifactViews.ArtifactItem> items = new ArrayList<>();
        for (JsonNode node : root.path("items")) {
            if (items.size() >= effectiveLimit) {
                break;
            }
            ArtifactViews.ArtifactItem item = toItem(node, repo);
            if (item == null) {
                continue;
            }
            if (version != null && !version.isBlank() && !version.trim().equalsIgnoreCase(item.version())) {
                continue;
            }
            items.add(item);
        }
        return ArtifactViews.SearchResult.of(items);
    }

    /**
     * 按坐标回查制品仓的 sha256（{@code repo:groupId:artifactId:version:packaging}）。
     *
     * <p>只读一次检索，取不到就返回 {@code null}——**不**用请求方给的摘要兜底，
     * 否则「核对」会退化成「复述」。调用方对 null 必须 fail-closed。</p>
     */
    public String checksum(IntegrationConfigEntity config, String coordinates) {
        ArtifactCoordinates.Coordinates target = ArtifactCoordinates.parseLookupKey(coordinates).orElse(null);
        if (target == null) {
            return null;
        }
        ArtifactViews.SearchResult result = searchResult(config, target.artifactName(),
                target.repo(), target.version(), DEFAULT_REPOSITORY_LIMIT);
        if (result.degraded()) {
            return null;
        }
        for (ArtifactViews.ArtifactItem item : result.items()) {
            if (matches(item, target) && item.sha256() != null && !item.sha256().isBlank()) {
                return item.sha256();
            }
        }
        return null;
    }

    private static boolean matches(ArtifactViews.ArtifactItem item, ArtifactCoordinates.Coordinates target) {
        return equalsIgnoreCase(item.repo(), target.repo())
                && equalsIgnoreCase(item.groupId(), target.groupId())
                && equalsIgnoreCase(item.artifactName(), target.artifactName())
                && equalsIgnoreCase(item.version(), target.version())
                && equalsIgnoreCase(item.packaging(), target.packaging());
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    /** 解析一条检索项；缺名称/版本视为不可用（不猜），返回 null 由调用方跳过。 */
    private ArtifactViews.ArtifactItem toItem(JsonNode node, String fallbackRepo) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode asset = firstAsset(node);
        String repo = text(node, "repository");
        if (repo == null) {
            repo = fallbackRepo;
        }
        String groupId = text(node, "group");
        if (groupId == null) {
            groupId = text(node, "groupId");
        }
        if (groupId == null) {
            groupId = nestedText(node, "maven2", "groupId");
        }
        String artifactName = text(node, "name");
        if (artifactName == null) {
            artifactName = text(node, "artifactName");
        }
        if (artifactName == null) {
            artifactName = nestedText(node, "maven2", "artifactId");
        }
        String version = text(node, "version");
        if (version == null) {
            version = nestedText(node, "maven2", "version");
        }
        if (artifactName == null || version == null) {
            return null;
        }
        String downloadUrl = text(node, "downloadUrl");
        if (downloadUrl == null && asset != null) {
            downloadUrl = text(asset, "downloadUrl");
        }
        String packaging = text(node, "packaging");
        if (packaging == null) {
            packaging = text(node, "extension");
        }
        if (packaging == null) {
            packaging = nestedText(node, "maven2", "extension");
        }
        if (packaging == null) {
            packaging = extensionOf(downloadUrl);
        }
        String sha256 = text(node, "sha256");
        if (sha256 == null) {
            sha256 = nestedText(node, "checksum", "sha256");
        }
        if (sha256 == null && asset != null) {
            sha256 = nestedText(asset, "checksum", "sha256");
        }
        Long sizeBytes = longValue(node, "sizeBytes");
        if (sizeBytes == null) {
            sizeBytes = longValue(node, "contentLength");
        }
        if (sizeBytes == null && asset != null) {
            sizeBytes = longValue(asset, "contentLength");
        }
        String id = text(node, "id");
        if (id == null) {
            id = repo + ":" + groupId + ":" + artifactName + ":" + version + ":" + packaging;
        }
        return new ArtifactViews.ArtifactItem(id, repo, groupId, artifactName, version,
                packaging, text(node, "classifier"), sha256, sizeBytes, downloadUrl);
    }

    private static JsonNode firstAsset(JsonNode node) {
        JsonNode assets = node.path("assets");
        if (assets.isArray() && assets.size() > 0) {
            return assets.get(0);
        }
        return null;
    }

    /** 配置里的默认仓库（NEXUS extra_json 的具名字段），缺省 {@code releases}。 */
    private String defaultRepository(IntegrationConfigEntity config) {
        String extra = config == null ? null : config.getExtraJson();
        if (extra != null && !extra.isBlank()) {
            JsonNode node = readJson(extra);
            String repository = node == null ? null : text(node, "repository");
            if (repository != null && !repository.isBlank()) {
                return repository.trim();
            }
        }
        return "releases";
    }

    /**
     * 认证头：{@code NONE} 不带凭据；有用户名时走 Basic（Nexus 的账号 + token 惯例），
     * 只有 token 时走 Bearer。解密失败不静默降级成匿名请求——那会把配置错误伪装成
     * 「Nexus 拒绝」。
     */
    private Map<String, String> authHeaders(IntegrationConfigEntity config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        String authType = config.getAuthType() == null
                ? "NONE" : config.getAuthType().trim().toUpperCase(Locale.ROOT);
        if ("NONE".equals(authType)) {
            return headers;
        }
        String credential = crypto.decode(config.getCredentialEnc());
        if (credential == null && config.getCredentialEnc() != null) {
            throw new ApiException(500, CODE_CREDENTIAL_UNREADABLE,
                    "Nexus 凭据无法解密，请重新录入", Map.of("configId", config.getId()));
        }
        if (credential == null || credential.isBlank()) {
            return headers;
        }
        String username = config.getUsername();
        if ("BASIC".equals(authType) || (username != null && !username.isBlank())) {
            String user = username == null ? "" : username;
            headers.put("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((user + ":" + credential).getBytes(StandardCharsets.UTF_8)));
        } else {
            headers.put("Authorization", "Bearer " + credential);
        }
        return headers;
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
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String nestedText(JsonNode node, String parent, String field) {
        JsonNode container = node == null ? null : node.get(parent);
        return container == null || container.isNull() ? null : text(container, field);
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asLong();
    }

    private static String extensionOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return null;
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String codeOf(OutboundResponse response) {
        if (response.errorCode() != null && !response.errorCode().isBlank()) {
            return response.errorCode();
        }
        return response.hasHttpStatus() ? "HTTP_" + response.httpStatus() : "TRANSPORT_ERROR";
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, MAX_LIMIT);
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
}
