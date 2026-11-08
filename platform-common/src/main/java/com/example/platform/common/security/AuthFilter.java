package com.example.platform.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import com.example.platform.common.error.ApiError;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.JwtCodec;
import com.example.platform.contract.JwtPayload;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Second line of defence: the gateway rejects a bad token early, this filter decides the
 * {@link DataScope} the business code is allowed to see.
 *
 * <p>Rules that matter for the acceptance criteria:</p>
 * <ul>
 *   <li>a missing {@code X-Tenant-Id} is 403 <em>before</em> any write — the request never reaches
 *       a controller, so nothing is persisted (AC-1);</li>
 *   <li>a header that names a different tenant is 403 as well, even with a valid token — the header
 *       is not a hint, it must agree with the token;</li>
 *   <li>{@code /internal/**} bypasses this filter (service-to-service calls carry the shared
 *       internal token instead) and so does the login endpoint.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String USER_HEADER = "X-User-Id";

    private final JwtCodec codec;
    private final UserScopeResolver resolver;
    private final ObjectMapper objectMapper;

    public AuthFilter(UserScopeResolver resolver, ObjectMapper objectMapper,
                      @Value("${platform.jwt.secret:dev-only-jwt-secret-please-change-32bytes-min}")
                      String secret) {
        this.resolver = resolver;
        this.objectMapper = objectMapper;
        this.codec = new JwtCodec(secret);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = path(request);
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }

        String token = bearer(request.getHeader("Authorization"));
        if (token == null) {
            reject(response, 401, "UNAUTHENTICATED", "未登录或缺少 Bearer token");
            return;
        }
        JwtPayload payload;
        try {
            payload = codec.parse(token);
        } catch (IllegalArgumentException ex) {
            reject(response, 401, "INVALID_TOKEN", "token 无效或已过期");
            return;
        }
        if (payload.isExpired(Instant.now().getEpochSecond())) {
            reject(response, 401, "TOKEN_EXPIRED", "登录已过期，请重新登录");
            return;
        }

        String tenantHeader = request.getHeader(TENANT_HEADER);
        if (tenantHeader == null || tenantHeader.isBlank()) {
            reject(response, 403, "TENANT_REQUIRED", "缺少 X-Tenant-Id 请求头");
            return;
        }
        long headerTenant = resolver.tenantIdOfHeader(tenantHeader);
        if (headerTenant != payload.tenantId()) {
            reject(response, 403, "TENANT_MISMATCH", "X-Tenant-Id 与登录租户不一致");
            return;
        }

        Optional<UserScopeResolver.TenantRow> tenant = resolver.tenantById(payload.tenantId());
        Optional<UserScopeResolver.UserRow> user =
                resolver.userById(payload.tenantId(), payload.userId());
        if (tenant.isEmpty() || user.isEmpty()) {
            reject(response, 401, "INVALID_TOKEN", "账号不存在或已被删除");
            return;
        }
        if (!"ACTIVE".equalsIgnoreCase(user.get().status())) {
            reject(response, 403, "USER_DISABLED", "账号已停用");
            return;
        }

        UserIdentity identity = resolver.identity(user.get(), tenant.get());
        DataScope scope = identity.scope();
        TenantContext.set(scope);
        TenantContext.setPrincipal(identity.principal());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static boolean isPublic(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (path.equals("/auth/login") || path.equals("/health") || path.equals("/error")
                || path.equals("/favicon.ico")) {
            return true;
        }
        return path.startsWith("/internal/") || path.startsWith("/actuator/")
                || path.startsWith("/openapi/")
                // 网关把 /openapi/** 去掉一段再转发，deliver 侧看到的是 /v1/test-executions；
                // 这条路径由 HmacAuthFilter 验签（X-App-Key/X-Signature），不能再走 JWT 分支。
                || path.equals("/v1/test-executions");
    }

    /** Gateway strips {@code /api/req} / {@code /api/deliver}; direct calls still carry it. */
    static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "/";
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        for (String prefix : new String[]{"/api/req", "/api/deliver"}) {
            if (uri.startsWith(prefix)) {
                return uri.substring(prefix.length());
            }
        }
        return uri;
    }

    private static String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = value.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    private void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        log.debug("auth rejected {} {}: {}", status, code, message);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(status, code, message));
    }
}
