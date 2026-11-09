package com.example.req.integration.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.platform.common.error.ApiException;
import com.example.req.integration.config.IntegrationProperties;

/**
 * 出站目标白名单：在建立连接之前拒绝 file:/gopher:/jar:、带 userinfo 的 URL，
 * 以及解析到回环/私网/链路本地的目标；重定向只允许同主机、最多 3 跳（AC-7.6）。
 *
 * <p>本地测试桩的回环地址必须显式登记在 {@code platform.integration.egress-allow-hosts}
 * 才放行——白名单是「例外清单」，不是开关，所以只放宽私网/回环判定，不放过协议与
 * userinfo 检查。DNS 解析失败不在本类拦截：连接阶段会以传输失败处理并按策略重试。</p>
 */
@Component
public class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    public static final String CODE_BLOCKED = "SSRF_BLOCKED";
    public static final String CODE_REDIRECT = "REDIRECT_BLOCKED";
    public static final String CODE_INVALID_URL = "INVALID_URL";

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final Set<String> allowHosts;

    public SsrfGuard(IntegrationProperties properties) {
        List<String> configured = properties == null ? List.of() : properties.getEgressAllowHosts();
        this.allowHosts = configured == null ? Set.of() : configured.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 校验出站目标；不合法时抛 {@link ApiException}（400 + 稳定错误码）。 */
    public void check(String url) {
        assertAllowed(parse(url));
    }

    /**
     * 解析重定向目标：必须同 host、不得从 https 降到 http，然后重新走一遍 {@link #check(String)}。
     *
     * @throws ApiException {@link #CODE_REDIRECT} 跨主机/非法目标，{@link #CODE_BLOCKED} 目标地址被拒
     */
    public URI resolveRedirect(URI from, String location) {
        if (from == null || location == null || location.isBlank()) {
            throw denied(CODE_REDIRECT, "重定向缺少目标地址");
        }
        URI target;
        try {
            target = from.resolve(location.trim());
        } catch (IllegalArgumentException ex) {
            throw denied(CODE_REDIRECT, "重定向地址非法");
        }
        String fromHost = from.getHost();
        String targetHost = target.getHost();
        if (fromHost == null || targetHost == null || !fromHost.equalsIgnoreCase(targetHost)) {
            throw denied(CODE_REDIRECT, "重定向不得跨主机（" + fromHost + " → " + targetHost + "）");
        }
        if ("https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(target.getScheme())) {
            throw denied(CODE_REDIRECT, "禁止从 https 降级到 http");
        }
        assertAllowed(target);
        return target;
    }

    public boolean allowlisted(String host, int port) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = normalizeHost(host);
        return allowHosts.contains(normalized) || allowHosts.contains(normalized + ":" + port);
    }

    private void assertAllowed(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw denied(CODE_BLOCKED, "只允许 http/https 出站（拒绝 " + scheme + "）");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw denied(CODE_INVALID_URL, "URL 缺少主机名");
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw denied(CODE_BLOCKED, "URL 不得携带 userinfo");
        }
        int port = effectivePort(uri);
        if (allowlisted(host, port)) {
            log.debug("ssrf allowlist hit: {}:{}", host, port);
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            // 解析失败交给连接阶段，以传输失败进入重试策略；不在这里伪装成 SSRF 拦截。
            log.debug("ssrf: dns resolution failed for {}: {}", host, ex.getMessage());
            return;
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw denied(CODE_BLOCKED, "目标解析到受限地址（" + host + " → "
                        + address.getHostAddress() + "）");
            }
        }
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw denied(CODE_INVALID_URL, "URL 为空");
        }
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            throw denied(CODE_INVALID_URL, "URL 无法解析");
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String normalizeHost(String host) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            // 100.64.0.0/10 CGNAT 与 0.0.0.0/8 同样不可作为外网目标。
            if (first == 0) {
                return true;
            }
            return first == 100 && (second & 0xc0) == 64;
        }
        if (bytes.length == 16) {
            // fc00::/7 unique local。
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    private static ApiException denied(String code, String message) {
        return new ApiException(400, code, message);
    }
}
