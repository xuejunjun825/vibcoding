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
    public static final String OP_POLICY = "BRANCH_POLICY_CHECK";
    public static final String OP_MR_NOTES = "MR_NOTES";

    /**
     * 出站失败的分类码。分支策略核验要把它映射成 {@code branch_policy_check.reason}
     * 的 5 值枚举，所以分类必须在这里（协议层）做完，判题层不再看 HTTP 状态。
     */
    public static final String CODE_AUTH_FAILED = "GITLAB_AUTH_FAILED";
    public static final String CODE_PERMISSION = "GITLAB_PERMISSION_DENIED";
    public static final String CODE_TIMEOUT = "GITLAB_TIMEOUT";
    public static final String CODE_UNREACHABLE = "GITLAB_UNREACHABLE";
    public static final String CODE_UNSUPPORTED = "GITLAB_API_UNSUPPORTED";

    private static final int MAX_PROTECTED = 100;
    private static final int MAX_BRANCHES = 100;
    private static final int MAX_NOTES = 100;

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
        return fetchMergeRequestDetails(config, project, iid, requirementId).payload();
    }

    /**
     * 读取一条 MR 的完整事实：载荷 + 评审人/评审态/变更文件数/评论数。
     *
     * <p>一次 GET 出两份事实，{@link #fetchMergeRequest} 只是它的薄包装。降级时
     * {@code review} 也返回空（不能拿空评审人覆盖库里已有的事实，见
     * {@code MrSyncService#merge})。</p>
     */
    public MrViews.MrDetails fetchMergeRequestDetails(IntegrationConfigEntity config, String project,
                                                      long iid, Long requirementId) {
        String path = "/api/v4/projects/" + MrUrlParser.encodeProject(project) + "/merge_requests/" + iid;
        OutboundRequest request = baseRequest(config, OP_MR, path, null)
                .biz("MR", requirementId)
                .build();
        OutboundResponse response = httpClient.exchange(request);
        if (!response.successful()) {
            return new MrViews.MrDetails(
                    MrViews.MrPayload.degraded(project, iid, mrUrl(config, project, iid), codeOf(response)),
                    new MrViews.ReviewInfo(List.of(), null, null, null));
        }
        JsonNode node = readJson(response.body());
        if (node == null || !node.isObject()) {
            return new MrViews.MrDetails(
                    MrViews.MrPayload.degraded(project, iid, mrUrl(config, project, iid), "INVALID_RESPONSE"),
                    new MrViews.ReviewInfo(List.of(), null, null, null));
        }
        return new MrViews.MrDetails(
                toPayload(project, iid, node, response.body()),
                toReview(node));
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

    // ---------------------------------------------------------------- 分支策略核验

    /**
     * 读项目设置（只读）。GitLab 的 {@code merge_method} 只有 merge/rebase_merge/ff 三值，
     * 这里归一到本系统的 MERGE/SQUASH/FF_ONLY；{@code squash_option=always} 才算 SQUASH，
     * 否则一次 squash-on-merge 的项目会被误判成恒 DRIFT。
     */
    public MrViews.ProjectSettings fetchProjectSettings(IntegrationConfigEntity config, String project) {
        JsonNode node = get(config, OP_POLICY, projectPath(project) + "?license=false",
                "读取 GitLab 项目设置");
        String squash = text(node, "squash_option");
        return new MrViews.ProjectSettings(
                canonicalMergeMethod(text(node, "merge_method"), squash),
                integer(node, "approvals_before_merge"),
                bool(node, "only_allow_merge_if_pipeline_succeeds"));
    }

    /**
     * 读保护分支。GitLab 用数字 {@code access_level} 表达角色，这里映射成角色名，
     * 好让规则里的 {@code push_roles_json} 与它对得上（40=Maintainer，30=Developer…）。
     */
    public List<MrViews.ProtectedBranch> protectedBranches(IntegrationConfigEntity config, String project) {
        JsonNode node = get(config, OP_POLICY,
                projectPath(project) + "/protected_branches?per_page=" + MAX_PROTECTED,
                "读取保护分支");
        if (!node.isArray()) {
            throw new ApiException(409, CODE_UNSUPPORTED, "读取保护分支失败：GitLab 应答不是数组",
                    Map.of("project", project));
        }
        List<MrViews.ProtectedBranch> branches = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            branches.add(new MrViews.ProtectedBranch(
                    text(item, "name"),
                    accessLevels(item.get("push_access_levels")),
                    accessLevels(item.get("merge_access_levels"))));
        }
        return branches;
    }

    /** 最近更新的分支（按 {@code updated} 倒序），用于「规则里的分支是否真的存在」抽样。 */
    public List<String> recentBranches(IntegrationConfigEntity config, String project, int limit) {
        int size = limit <= 0 ? MAX_BRANCHES : Math.min(limit, MAX_BRANCHES);
        JsonNode node = get(config, OP_POLICY,
                projectPath(project) + "/repository/branches?per_page=" + size
                        + "&order_by=updated&sort=desc",
                "读取分支列表");
        if (!node.isArray()) {
            throw new ApiException(409, CODE_UNSUPPORTED, "读取分支列表失败：GitLab 应答不是数组",
                    Map.of("project", project));
        }
        List<String> names = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            String name = text(item, "name");
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 读 MR 的 note 列表。只保留<b>行级</b>评论（{@code position != null}）：普通讨论
     * 没有文件/行号，落进 {@code mr_comment} 会让「文件:行号」列全是空，前端还得再筛一遍。
     * 系统 note（{@code system=true}）是状态变更流水，也不是人写的评论。
     */
    public List<MrViews.MrNote> fetchMrNotes(IntegrationConfigEntity config, String project, long iid) {
        JsonNode node = get(config, OP_MR_NOTES,
                projectPath(project) + "/merge_requests/" + iid + "/notes?per_page=" + MAX_NOTES
                        + "&sort=desc&order_by=updated_at",
                "读取 MR 评论");
        if (!node.isArray()) {
            throw new ApiException(409, CODE_UNSUPPORTED, "读取 MR 评论失败：GitLab 应答不是数组",
                    Map.of("project", project, "iid", iid));
        }
        List<MrViews.MrNote> notes = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item.path("system").asBoolean(false)) {
                continue;
            }
            JsonNode position = item.get("position");
            boolean lineLevel = position != null && position.isObject();
            String filePath = null;
            Integer lineNo = null;
            if (lineLevel) {
                filePath = firstText(position, "new_path", "old_path");
                Integer newLine = integer(position, "new_line");
                lineNo = newLine != null ? newLine : integer(position, "old_line");
            }
            notes.add(new MrViews.MrNote(
                    item.path("id").asLong(0L),
                    authorName(item),
                    text(item, "body"),
                    filePath,
                    lineNo,
                    item.path("resolved").asBoolean(false),
                    MrViews.parseIso(text(item, "created_at")),
                    lineLevel));
        }
        return notes;
    }

    /** 一次带分类失败的 GET。失败抛 409：核验接口对外也回 409 + 落 UNVERIFIED 记录。 */
    private JsonNode get(IntegrationConfigEntity config, String operation, String path, String what) {
        OutboundRequest request = baseRequest(config, operation, path, null).build();
        OutboundResponse response = httpClient.exchange(request);
        if (!response.successful()) {
            throw failure(what, response);
        }
        JsonNode node = readJson(response.body());
        if (node == null) {
            throw new ApiException(409, CODE_UNSUPPORTED, what + "失败：GitLab 应答无法解析",
                    Map.of("upstream", "INVALID_RESPONSE"));
        }
        return node;
    }

    private static ApiException failure(String what, OutboundResponse response) {
        int status = response.hasHttpStatus() ? response.httpStatus() : 0;
        String code;
        String detail;
        if (status == 401) {
            code = CODE_AUTH_FAILED;
            detail = "凭据无效或已过期";
        } else if (status == 403) {
            code = CODE_PERMISSION;
            detail = "令牌权限不足（需要 api 作用域与 Maintainer 及以上）";
        } else if (status == 404 || status == 405) {
            code = CODE_UNSUPPORTED;
            detail = "项目不存在，或该 GitLab 版本没有这个只读接口";
        } else if ("TIMEOUT".equals(response.errorCode())) {
            code = CODE_TIMEOUT;
            detail = "GitLab 在超时时间内没有应答";
        } else {
            code = CODE_UNREACHABLE;
            detail = codeOf(response);
        }
        return new ApiException(409, code, what + "失败：" + detail,
                Map.of("upstream", codeOf(response), "httpStatus", status));
    }

    private static String projectPath(String project) {
        return "/api/v4/projects/" + MrUrlParser.encodeProject(project);
    }

    private static List<String> accessLevels(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> roles = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            String description = text(item, "access_level_description");
            Integer level = integer(item, "access_level");
            String role = roleName(level);
            if (description != null && description.contains("Maintainer")) {
                role = "Maintainer";
            } else if (description != null && description.contains("Owner")) {
                role = "Owner";
            }
            if (role != null) {
                roles.add(role);
            }
        }
        return roles;
    }

    private static String roleName(Integer level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case 5 -> "Minimal";
            case 10 -> "Guest";
            case 20 -> "Reporter";
            case 30 -> "Developer";
            case 40 -> "Maintainer";
            case 50 -> "Owner";
            case 60 -> "Admin";
            default -> null;
        };
    }

    private static String canonicalMergeMethod(String mergeMethod, String squashOption) {
        if (mergeMethod == null) {
            return null;
        }
        String value = mergeMethod.trim().toLowerCase();
        if ("ff".equals(value)) {
            return MrViews.MERGE_METHOD_FF_ONLY;
        }
        if ("squash".equals(value) || "always".equalsIgnoreCase(squashOption)) {
            return MrViews.MERGE_METHOD_SQUASH;
        }
        if ("merge".equals(value) || "rebase_merge".equals(value)) {
            return MrViews.MERGE_METHOD_MERGE;
        }
        return value.toUpperCase();
    }

    private static String authorName(JsonNode note) {
        JsonNode author = note.get("author");
        if (author == null || author.isNull()) {
            return null;
        }
        String name = text(author, "name");
        return name != null ? name : text(author, "username");
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
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

    /**
     * 从同一条 MR 应答里抽评审事实。{@code changes_count} 是<b>字符串</b>（上限会写成
     * {@code "1000+"}），直接 {@code asInt()} 会得到 0——那正是「空态不补 0」要防的假数据。
     */
    private MrViews.ReviewInfo toReview(JsonNode node) {
        List<MrViews.Reviewer> reviewers = new ArrayList<>();
        JsonNode array = node.get("reviewers");
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                reviewers.add(new MrViews.Reviewer(
                        item.path("id").asLong(0L),
                        text(item, "name"),
                        text(item, "username"),
                        text(item, "state")));
            }
        }
        return new MrViews.ReviewInfo(
                reviewers,
                MrViews.normalizeReviewState(reviewers, approvals(node)),
                changedFiles(text(node, "changes_count")),
                integer(node, "user_notes_count"));
    }

    private static Integer changedFiles(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.trim();
        if (digits.endsWith("+")) {
            digits = digits.substring(0, digits.length() - 1);
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
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
