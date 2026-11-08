package com.example.platform.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.example.platform.common.error.ApiError;
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
 * Service-to-service authentication for {@code /internal/**}.
 *
 * <p>Internal calls carry a shared token instead of a user JWT, so they must not pass through
 * {@link AuthFilter}. {@code /internal/health} stays anonymous: it is the readiness probe the
 * start scripts poll, and requiring a secret there would make a broken secret look like a dead
 * process.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);

    public static final String TOKEN_HEADER = "X-Internal-Token";

    private final String token;
    private final ObjectMapper objectMapper;

    public InternalTokenFilter(ObjectMapper objectMapper,
                               @Value("${platform.internal.token:"
                                       + "${platform.internal-token:dev-internal-token}}")
                               String token) {
        this.objectMapper = objectMapper;
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = AuthFilter.path(request);
        if (!path.startsWith("/internal/") || path.equals("/internal/health")) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null || !constantTimeEquals(provided, token)) {
            log.warn("internal call rejected: {} {}", request.getMethod(), path);
            response.setStatus(401);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    ApiError.of(401, "INTERNAL_TOKEN_INVALID", "内部调用令牌无效"));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (right == null || left.length() != right.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < left.length(); i++) {
            diff |= left.charAt(i) ^ right.charAt(i);
        }
        return diff == 0;
    }
}
