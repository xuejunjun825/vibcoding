package com.example.req.integration.jenkins;

import com.example.req.integration.config.IntegrationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * console_url 的清洗（AC-3.7）：外链由 <b>回调方</b> 给，不是我们拼的，所以必须校验同源。
 *
 * <p>为什么不同源就存 NULL 而不是报错：console 链接是「便于人点开」的附加信息，不是证据。
 * 一个可疑链接不该让整条构建回调失败（那会让 Jenkins 反复重投），也不该原样存进库里
 * 变成一个钓鱼跳转——所以只丢弃这一个字段，并记 {@code CONSOLE_URL_REJECTED}。</p>
 */
@Component
public class ConsoleUrlGuard {

    /** console 地址没有必要很长；超长直接拒绝，避免把整段可疑载荷存进 VARCHAR(1000)。 */
    private static final int MAX_LENGTH = 1000;

    private final IntegrationProperties properties;

    public ConsoleUrlGuard(IntegrationProperties properties) {
        this.properties = properties;
    }

    /**
     * @return 同源（或命中部署白名单）时返回原地址，否则 {@code null}
     */
    public String sanitize(String baseUrl, String consoleUrl) {
        if (consoleUrl == null || consoleUrl.isBlank()) {
            return null;
        }
        String candidate = consoleUrl.trim();
        if (candidate.length() > MAX_LENGTH) {
            return null;
        }
        URI target = parse(candidate);
        if (target == null || !httpish(target) || target.getHost() == null) {
            return null;
        }
        URI base = parse(baseUrl);
        if (base != null && httpish(base) && base.getHost() != null && sameOrigin(base, target)) {
            return candidate;
        }
        return allowedHost(target.getHost()) ? candidate : null;
    }

    /** 判定「这次丢弃是否值得记一条 CONSOLE_URL_REJECTED」。 */
    public boolean rejected(String baseUrl, String consoleUrl) {
        return consoleUrl != null && !consoleUrl.isBlank() && sanitize(baseUrl, consoleUrl) == null;
    }

    private boolean sameOrigin(URI base, URI target) {
        return base.getScheme().equalsIgnoreCase(target.getScheme())
                && base.getHost().equalsIgnoreCase(target.getHost())
                && port(base) == port(target);
    }

    private int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean httpish(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equals("http") || scheme.equals("https");
    }

    private URI parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(raw.trim());
            return uri.isAbsolute() ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 部署白名单：{@code platform.integration.egress-allow-hosts} 里的主机。
     *
     * <p>复用出站白名单而不是新加一个配置项，是因为这两者的语义本来就相同——
     * 「这台部署信任哪些主机」。多一个配置项就多一处会漂移的真相。</p>
     */
    private boolean allowedHost(String host) {
        List<String> allowed = properties.getEgressAllowHosts();
        if (allowed == null || allowed.isEmpty() || host == null) {
            return false;
        }
        String target = host.toLowerCase(Locale.ROOT);
        for (String entry : allowed) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String value = entry.trim().toLowerCase(Locale.ROOT);
            int colon = value.lastIndexOf(':');
            if (colon > 0 && value.indexOf(']') < 0) {
                value = value.substring(0, colon);
            }
            if (value.equals(target)) {
                return true;
            }
        }
        return false;
    }
}
