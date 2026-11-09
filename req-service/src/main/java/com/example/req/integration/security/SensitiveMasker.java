package com.example.req.integration.security;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 出站/入站摘要的唯一脱敏点（AC-10.1 凭据 0 命中）。
 *
 * <p>规则：凡是 Authorization / X-Gitlab-Token / X-Jenkins-Signature / private-token /
 * password / token / secret 这类字段，值一律替换为 {@code ***}；URL query 里的
 * token 类参数直接剔除（不是打码），这样日志表的 target_url 永远不会带凭据。
 * 摘要长度夹到 500，与 PRD 的 request_digest 契约一致。</p>
 */
public final class SensitiveMasker {

    public static final String MASK = "***";
    public static final int MAX_DIGEST = 500;

    /** JSON 字段值：{@code "password":"xxx"} → {@code "password":"***"}。 */
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|token|access_token|refresh_token|private_token"
                    + "|secret|client_secret|clientSecret|api_token|apiToken|authorization"
                    + "|credential|credentialEnc|secretEnc)\"\\s*:\\s*)"
                    + "\"(?:\\\\.|[^\"\\\\])*\"");

    /** 头文本行：{@code Authorization: Basic xxx} → {@code Authorization: ***}。 */
    private static final Pattern HEADER_LINE = Pattern.compile(
            "(?im)^(\\s*(?:authorization|proxy-authorization|x-gitlab-token|x-jenkins-signature"
                    + "|private-token|x-api-key|x-auth-token)\\s*:\\s*)\\S.*$");

    /** 摘要里内联的 query/form 参数。 */
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:token|access_token|refresh_token|private_token|secret|password"
                    + "|api_token|client_secret)=)[^&#\\s\"']*");

    /** Bearer / Basic 认证串。 */
    private static final Pattern AUTH_SCHEME = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{4,}");

    private static final Pattern URL_SECRET_PARAM = Pattern.compile(
            "(?i)([?&])(?:token|access_token|refresh_token|private_token|secret|password"
                    + "|api_token|client_secret)=[^&#]*");

    private static final Pattern USER_INFO = Pattern.compile(
            "(?i)^([a-z][a-z0-9+.-]*://)[^/@]*@");

    private SensitiveMasker() {
    }

    /** 摘要脱敏 + 截断；null 原样返回。 */
    public static String mask(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        String masked = JSON_SECRET.matcher(body).replaceAll("$1\"" + MASK + "\"");
        masked = HEADER_LINE.matcher(masked).replaceAll("$1" + MASK);
        masked = AUTH_SCHEME.matcher(masked).replaceAll("$1 " + MASK);
        masked = QUERY_SECRET.matcher(masked).replaceAll("$1" + MASK);
        return truncate(masked, MAX_DIGEST);
    }

    /** 请求头摘要：敏感头打码，其余保留，便于排障。 */
    public static String headers(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        headers.forEach((name, value) -> {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append(name).append(':').append(sensitiveHeader(name) ? MASK : value);
        });
        return truncate(out.toString(), MAX_DIGEST);
    }

    /** URL 脱敏：剔除 query 中的凭据参数、去掉 userinfo，并夹到 500。 */
    public static String url(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String masked = USER_INFO.matcher(url).replaceAll("$1");
        masked = URL_SECRET_PARAM.matcher(masked).replaceAll("$1");
        masked = masked.replace("?&", "?").replace("&&", "&");
        while (masked.endsWith("?") || masked.endsWith("&")) {
            masked = masked.substring(0, masked.length() - 1);
        }
        return truncate(masked, MAX_DIGEST);
    }

    public static boolean sensitiveHeader(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("authorization")
                || lower.equals("proxy-authorization")
                || lower.equals("x-gitlab-token")
                || lower.equals("x-jenkins-signature")
                || lower.equals("private-token")
                || lower.equals("x-api-key")
                || lower.equals("x-auth-token")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("password");
    }

    /** 截断到 {@code max} 字符（含省略号），不把长度契约留给调用方。 */
    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        if (max <= 1) {
            return value.substring(0, Math.max(0, max));
        }
        return value.substring(0, max - 1) + "…";
    }
}
