package com.example.req.integration.gitlab;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 把用户粘贴的 GitLab MR 链接解析成 {@code project + iid}。
 *
 * <p>GitLab 的 MR 链接有两种常见形态，必须都认：</p>
 * <pre>
 *   https://gitlab.example.com/demo-group/demo-project/-/merge_requests/1
 *   https://gitlab.example.com/demo-group/sub/demo-project/merge_requests/7
 * </pre>
 * <p>两者的区别只在于可选的分隔段 {@code /-/}；嵌套 group 意味着 project 是
 * 「{@code merge_requests} 之前的所有段」而不是「倒数第二段」。同时接受
 * URL-encoded 的 project（{@code demo-group%2Fdemo-project}）和尾部多余段
 * （{@code /diffs}、{@code ?tab=...}）。</p>
 */
public final class MrUrlParser {

    /** 结构化结果：GitLab 项目全路径（含父 group）+ MR iid。 */
    public record MrRef(String project, long iid) {
    }

    /** project 全路径长度上限（GitLab 自己限制 255，这里给列宽留余量）。 */
    private static final int MAX_PROJECT_LENGTH = 200;

    private static final String MR_SEGMENT = "merge_requests";

    private MrUrlParser() {
    }

    /**
     * 解析 MR 链接；无法结构化时返回 {@link Optional#empty()}（调用方保留原样 URL，
     * 不编造 project/iid）。
     */
    public static Optional<MrRef> parse(String url) {
        if (url == null) {
            return Optional.empty();
        }
        String value = url.trim();
        if (value.isEmpty() || value.length() > 500) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return Optional.empty();
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return Optional.empty();
        }
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        List<String> segments = Arrays.stream(path.split("/"))
                .filter(segment -> !segment.isEmpty())
                .toList();
        int marker = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (MR_SEGMENT.equalsIgnoreCase(segments.get(i))) {
                marker = i;
            }
        }
        // marker 必须前面还有 project、后面还有 iid
        if (marker <= 0 || marker + 1 >= segments.size()) {
            return Optional.empty();
        }
        List<String> projectSegments = new ArrayList<>(segments.subList(0, marker));
        // 去掉 /-/ 里的分隔段
        if (!projectSegments.isEmpty() && "-".equals(projectSegments.get(projectSegments.size() - 1))) {
            projectSegments.remove(projectSegments.size() - 1);
        }
        if (projectSegments.isEmpty()) {
            return Optional.empty();
        }
        String project = decodeSegments(projectSegments);
        if (project.isBlank() || project.length() > MAX_PROJECT_LENGTH) {
            return Optional.empty();
        }
        long iid;
        try {
            iid = Long.parseLong(segments.get(marker + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (iid <= 0) {
            return Optional.empty();
        }
        return Optional.of(new MrRef(project, iid));
    }

    /** project 路径里 {@code %} 编码的段还原成可读的 {@code group/project}。 */
    private static String decodeSegments(List<String> segments) {
        List<String> decoded = new ArrayList<>(segments.size());
        for (String segment : segments) {
            decoded.add(decode(segment));
        }
        return String.join("/", decoded);
    }

    private static String decode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        try {
            return URLDecoder.decode(segment, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }

    /**
     * GitLab REST 路径需要 URL-encoded 的 project（{@code group%2Fproject}）。
     * 与 {@link #parse(String)} 互逆：解析进来的两种形态都能拼回一个合法 API 路径。
     */
    public static String encodeProject(String project) {
        if (project == null) {
            return "";
        }
        StringBuilder encoded = new StringBuilder(project.length() + 8);
        for (byte b : project.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return encoded.toString();
    }
}
