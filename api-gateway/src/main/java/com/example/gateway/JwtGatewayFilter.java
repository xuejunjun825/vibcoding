package com.example.gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.platform.contract.JwtCodec;
import com.example.platform.contract.JwtPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Fast rejection at the edge: a request without a valid token never reaches a service.
 *
 * <p>This is deliberately <em>not</em> the only authorization point. Services re-verify the
 * token in {@code AuthFilter} and resolve permissions from the database per request, which
 * is what makes a permission change take effect within seconds. The gateway only saves the
 * hop and keeps an inconsistent {@code X-Tenant-Id} from ever entering the platform.</p>
 *
 * <p>Anonymous paths are a contract, not a convenience: r1's verify script calls
 * {@code /api/demo/hello} without a token, and the open API authenticates with HMAC
 * ({@code X-App-Key}/{@code X-Timestamp}/{@code X-Signature}) instead of a JWT.</p>
 */
@Component
public class JwtGatewayFilter implements GlobalFilter, Ordered {

    /** Runs before the routing filters so a rejected request is never forwarded. */
    public static final int ORDER = -200;

    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final List<String> ANONYMOUS_EXACT = List.of("/api/req/auth/login");
    private static final List<String> ANONYMOUS_PREFIXES = List.of(
            "/openapi/", "/actuator/", "/api/demo/");

    private final JwtCodec codec;

    public JwtGatewayFilter(
            @Value("${platform.jwt.secret:dev-only-jwt-secret-please-change-32bytes-min}") String secret) {
        this.codec = new JwtCodec(secret);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (isAnonymous(path)) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        String token = bearer(authorization);
        if (token == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "缺少 Authorization: Bearer <token>");
        }

        JwtPayload payload;
        try {
            payload = codec.parse(token);
        } catch (IllegalArgumentException ex) {
            // Same status and code for malformed / forged / expired — the reason is logged
            // by the service, not handed back to an unauthenticated caller.
            return reject(exchange, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                    "令牌无效或已过期，请重新登录");
        }

        String tenantHeader = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);
        if (tenantHeader == null || tenantHeader.isBlank()) {
            return reject(exchange, HttpStatus.FORBIDDEN, "TENANT_REQUIRED",
                    "缺少 " + TENANT_HEADER + " 请求头");
        }
        long claimed;
        try {
            claimed = Long.parseLong(tenantHeader.trim());
        } catch (NumberFormatException ex) {
            return reject(exchange, HttpStatus.FORBIDDEN, "TENANT_MISMATCH",
                    TENANT_HEADER + " 必须是数字");
        }
        if (claimed != payload.tenantId()) {
            return reject(exchange, HttpStatus.FORBIDDEN, "TENANT_MISMATCH",
                    TENANT_HEADER + " 与令牌所属租户不一致");
        }

        // Identity headers are overwritten from the verified token: a client cannot
        // smuggle a different user id past the services by setting them itself.
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder
                        .header("X-User-Id", Long.toString(payload.userId()))
                        .header("X-Tenant-Id", Long.toString(payload.tenantId())))
                .build();
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private static boolean isAnonymous(String path) {
        if (ANONYMOUS_EXACT.contains(path)) {
            return true;
        }
        for (String prefix : ANONYMOUS_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status,
                                     String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"status\":" + status.value() + ",\"code\":\"" + code
                + "\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
