package com.example.gateway;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.netty.http.client.PrematureCloseException;
import reactor.core.publisher.Mono;

/**
 * Single entry point of the environment.
 *
 * <p>Route is declared in Java instead of YAML on purpose: the gateway property
 * namespace has moved between Spring Cloud Gateway majors (2025.1 / Gateway 5),
 * while the {@link RouteLocatorBuilder} API is stable and therefore a safer
 * contract for a version-pinned skeleton.</p>
 */
@SpringBootApplication
public class ApiGatewayApplication {

    /**
     * Fixed 503 payload. Kept byte-stable so scripts and docs can match on it.
     */
    private static final byte[] UNAVAILABLE_BODY = ("{\"status\":503,"
            + "\"error\":\"Service Unavailable\","
            + "\"message\":\"downstream instance unreachable: no healthy instance in Nacos, "
            + "or connection refused by the instance\"}").getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    @Bean
    RouteLocator demoRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("demo-service", route -> route
                        .path("/api/**")
                        .filters(filters -> filters.stripPrefix(1))
                        .uri("lb://demo-service"))
                .build();
    }

    /**
     * Maps "instance still cached, but nothing is listening" to 503.
     *
     * <p>Spring Cloud LoadBalancer keeps the last instance list for
     * {@code spring.cloud.loadbalancer.cache.ttl} (35s by default), so a stopped
     * {@code demo-service} is first reached through a stale instance; Netty then
     * raises {@link ConnectException} (connection refused) or
     * {@link PrematureCloseException} (the connection is reset while a pooled
     * one is in flight). Gateway's default mapping for both is 500. AC-4 ①
     * requires 503, so translate them here instead of waiting for the cache to
     * expire. 503 is already what Gateway returns when Nacos has no healthy
     * instance, so all downstream-unavailable paths now agree.</p>
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    GlobalFilter connectRefusedIs503() {
        return (exchange, chain) -> chain.filter(exchange)
                .onErrorResume(ApiGatewayApplication::isConnectionRefused,
                        failure -> serviceUnavailable(exchange, failure));
    }

    private static boolean isConnectionRefused(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof PrematureCloseException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static Mono<Void> serviceUnavailable(ServerWebExchange exchange, Throwable failure) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(failure);
        }
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.fromSupplier(() -> response.bufferFactory().wrap(UNAVAILABLE_BODY)));
    }
}
