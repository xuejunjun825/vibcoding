package com.example.req.integration.artifact;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.example.platform.common.error.ApiException;

/**
 * Maven 坐标与摘要的规范化/校验（制品域唯一入口）。
 *
 * <p>三件事只在这里定义，避免适配器、服务、控制器各写一份：</p>
 * <ol>
 *   <li><b>坐标</b>：{@code repo:groupId:artifactId:version:packaging} 五段查找键，
 *       用于「拿目标仓库的校验和」与唯一键幂等；长度与字符集与 V8 的列宽一致。</li>
 *   <li><b>摘要</b>：64 位十六进制、统一小写；比较用 {@link #sameSha(String, String)}
 *       （大小写不敏感，但不做「前 12 位也算一致」这种危险放宽）。</li>
 *   <li><b>晋级 URL</b>：把制品直链里的 {@code /repository/<repo>/} 段替换成目标仓库，
 *       只做这一种形态的改写；不认识的 URL 返回 {@code null}，由调用方 fail-closed。</li>
 * </ol>
 */
public final class ArtifactCoordinates {

    /** 坐标/字段不合法（PRD AC-7.2）。 */
    public static final String CODE_INVALID = "ARTIFACT_COORDINATES_INVALID";
    /** sha256 不是 64 位十六进制。 */
    public static final String CODE_SHA256_INVALID = "SHA256_INVALID";
    /** 目标地址未通过 SSRF 白名单（验收脚本优先匹配这个码）。 */
    public static final String CODE_URL_NOT_ALLOWED = "URL_NOT_ALLOWED";
    /** 提供值与制品仓元数据不一致。 */
    public static final String CODE_CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH";
    /** 制品仓没有返回可核对的摘要（不代表一致）。 */
    public static final String CODE_CHECKSUM_UNAVAILABLE = "CHECKSUM_UNAVAILABLE";
    /** 制品已绑定到另一张发布单。 */
    public static final String CODE_ALREADY_BOUND = "ARTIFACT_ALREADY_BOUND";

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern GROUP = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._+-]{0,63}$");
    private static final Pattern PACKAGING = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,15}$");

    private ArtifactCoordinates() {
    }

    /** 规范化后的五段坐标（classifier 只用于展示，不参与查找键）。 */
    public record Coordinates(String repo,
                              String groupId,
                              String artifactName,
                              String version,
                              String packaging,
                              String classifier) {

        /** {@code repo:groupId:artifactId:version:packaging}，Nexus 精确查找的键。 */
        public String lookupKey() {
            return String.join(":", repo, groupId, artifactName, version, packaging);
        }
    }

    /**
     * 校验并规范化坐标。{@code packaging} 为空时按 Maven 默认 {@code jar}。
     *
     * @throws ApiException 400 {@link #CODE_INVALID}，detail 指明哪个字段
     */
    public static Coordinates normalize(String repo, String groupId, String artifactName,
                                        String version, String packaging, String classifier) {
        String r = require("repository", repo, REPO);
        String g = require("groupId", groupId, GROUP);
        String a = require("artifactName", artifactName, NAME);
        String v = require("version", version, VERSION);
        String p = isBlank(packaging) ? "jar" : require("packaging", packaging, PACKAGING);
        String c = isBlank(classifier) ? null : limit("classifier", classifier.trim(), 32);
        return new Coordinates(r, g, a, v, p, c);
    }

    /** 只校验仓库名（晋级目标）。 */
    public static String requireRepo(String repo) {
        return require("repository", repo, REPO);
    }

    /**
     * 校验并返回小写 sha256。
     *
     * @throws ApiException 400 {@link #CODE_SHA256_INVALID}
     */
    public static String sha256(String value) {
        if (value == null || !SHA256.matcher(value.trim()).matches()) {
            throw new ApiException(400, CODE_SHA256_INVALID,
                    "sha256 必须是 64 位十六进制", Map.of());
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** 大小写不敏感的摘要比较；null 一律不相等。 */
    public static boolean sameSha(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    /** 展示/错误 detail 里的前 12 位；不用于比较。 */
    public static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return null;
        }
        String trimmed = sha.trim().toLowerCase(Locale.ROOT);
        return trimmed.length() <= 12 ? trimmed : trimmed.substring(0, 12);
    }

    /** 解析 {@link Coordinates#lookupKey()}；不合法返回空（不抛，供降级路径使用）。 */
    public static Optional<Coordinates> parseLookupKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String[] parts = key.split(":", 5);
        if (parts.length != 5) {
            return Optional.empty();
        }
        try {
            return Optional.of(normalize(parts[0], parts[1], parts[2], parts[3], parts[4], null));
        } catch (ApiException ex) {
            return Optional.empty();
        }
    }

    /**
     * 把制品直链的仓库段替换为目标仓库：
     * {@code https://nexus/repository/releases/a/b/1.0/b-1.0.jar} →
     * {@code https://nexus/repository/prod/a/b/1.0/b-1.0.jar}。
     *
     * @return 改写后的 URL；URL 不含 {@code /repository/<name>/} 时返回 {@code null}
     *         （调用方据此判定「无法核对」，绝不猜一个目标地址）
     */
    public static String retarget(String downloadUrl, String targetRepo) {
        if (isBlank(downloadUrl) || isBlank(targetRepo)) {
            return null;
        }
        String repo = requireRepo(targetRepo);
        String url = downloadUrl.trim();
        String marker = "/repository/";
        int markerIndex = url.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        int repoStart = markerIndex + marker.length();
        int repoEnd = url.indexOf('/', repoStart);
        if (repoEnd <= repoStart) {
            return null;
        }
        return url.substring(0, repoStart) + repo + url.substring(repoEnd);
    }

    /** URL 的主机名（错误 detail 展示用，绝不回显 userinfo/query）。 */
    public static String hostOf(String url) {
        if (isBlank(url)) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null || host.isBlank() ? null : host;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 供错误 detail 使用：不带 userinfo 与 query 的 URL。 */
    public static String sanitizeUrl(String url) {
        if (isBlank(url)) {
            return null;
        }
        try {
            URI parsed = URI.create(url.trim());
            return new URI(parsed.getScheme(), null, parsed.getHost(), parsed.getPort(),
                    parsed.getPath(), null, null).toString();
        } catch (Exception ex) {
            return hostOf(url);
        }
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String require(String field, String value, Pattern pattern) {
        if (isBlank(value)) {
            throw invalid(field, "不能为空");
        }
        String trimmed = value.trim();
        if (!pattern.matcher(trimmed).matches()) {
            throw invalid(field, "包含不允许的字符或超长");
        }
        return trimmed;
    }

    private static String limit(String field, String value, int max) {
        if (value.length() > max) {
            throw invalid(field, "不能超过 " + max + " 字符");
        }
        return value;
    }

    private static ApiException invalid(String field, String reason) {
        return new ApiException(400, CODE_INVALID, field + " " + reason,
                java.util.Map.of("field", field, "reason", reason));
    }
}
