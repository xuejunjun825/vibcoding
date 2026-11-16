package com.example.gateway;

import java.time.Duration;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * Platform routes (20261108-r2). Kept in a separate configuration class on purpose:
 * {@link ApiGatewayApplication#demoRoutes} is the r1 baseline and stays byte-identical,
 * so a regression in the platform layer can never be mistaken for a baseline change.
 *
 * <p>Ordering: {@code /api/req/**} and {@code /api/deliver/**} must win over the r1
 * {@code /api/**} catch-all (order 0), hence negative orders. {@code /openapi/**} is
 * stripped by one segment only, so the public path {@code /openapi/v1/test-executions}
 * reaches deliver-service as {@code /v1/test-executions}.</p>
 *
 * <p>{@code /api/integration/**} (order -95) strips exactly the {@code /api} segment and goes
 * to req-service as {@code /integration/**}. It sits between the req route and the deliver
 * route so a future {@code /api/req/integration/**} alias cannot shadow it; CI webhooks must
 * enter through the same edge as everything else (one place to observe, one place to rate
 * limit), even though that one prefix is anonymous — see {@link JwtGatewayFilter}.</p>
 */
@Configuration
public class PlatformRoutes {

    @Bean("platformRouteLocator")
    public RouteLocator platformRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("req-service", r -> r.order(-100).path("/api/req/**")
                        .filters(f -> idempotent(f.stripPrefix(2)))
                        .uri("lb://req-service"))
                .route("req-integration", r -> r.order(-95).path("/api/integration/**")
                        .filters(f -> idempotent(f.stripPrefix(1)))
                        .uri("lb://req-service"))
                .route("deliver-service", r -> r.order(-90).path("/api/deliver/**")
                        .filters(f -> idempotent(f.stripPrefix(2)))
                        .uri("lb://deliver-service"))
                .route("deliver-openapi", r -> r.order(-80).path("/openapi/**")
                        .filters(f -> idempotent(f.stripPrefix(1)))
                        .uri("lb://deliver-service"))
                .build();
    }

    /**
     * 20261114-r20 (BUG-3)：只对<b>幂等 GET</b>的<b>传输层</b>失败重试一次。
     *
     * <p>观测到 {@code GET /api/deliver/test-executors} 偶发 500，根因是网关与 deliver
     * 之间 {@code SocketException: Connection reset}（deliver JVM 停顿/连接被复用池关闭），
     * 不是接口逻辑——此时重放同一个 GET 在语义上等价，而 4xx/5xx 业务响应是确定性结论，
     * 重试只会把「快速失败」拖成「慢失败」，所以 {@link #idempotent(GatewayFilterSpec)}
     * 明确三件事：</p>
     *
     * <ul>
     *   <li>{@code setRetries(1)} + backoff 200ms→1s：一次、最多等 1s，不放大下游；</li>
     *   <li>{@code setMethods(HttpMethod.GET)}：POST/PUT/DELETE 永不重放（重复提交风险）；</li>
     *   <li>{@code setExceptions(IOException.class)}：只接传输层异常，不接业务状态码。</li>
     * </ul>
     *
     * <p>重试过滤器在内层，网络错误先到它；{@link ApiGatewayApplication#connectRefusedIs503()}
     * 作为最外层全局过滤器仍负责把「重试后依旧拒连」翻译成 503，两者顺序不冲突。</p>
     */
    private static GatewayFilterSpec idempotent(GatewayFilterSpec spec) {
        return spec.retry(config -> config
                .setRetries(1)
                .setMethods(HttpMethod.GET)
                .setExceptions(java.io.IOException.class)
                .setBackoff(Duration.ofMillis(200), Duration.ofSeconds(1), 2, false));
    }
}
