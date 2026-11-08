package com.example.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Platform routes (20261108-r2). Kept in a separate configuration class on purpose:
 * {@link ApiGatewayApplication#demoRoutes} is the r1 baseline and stays byte-identical,
 * so a regression in the platform layer can never be mistaken for a baseline change.
 *
 * <p>Ordering: {@code /api/req/**} and {@code /api/deliver/**} must win over the r1
 * {@code /api/**} catch-all (order 0), hence negative orders. {@code /openapi/**} is
 * stripped by one segment only, so the public path {@code /openapi/v1/test-executions}
 * reaches deliver-service as {@code /v1/test-executions}.</p>
 */
@Configuration
public class PlatformRoutes {

    @Bean("platformRouteLocator")
    public RouteLocator platformRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("req-service", r -> r.order(-100).path("/api/req/**")
                        .filters(f -> f.stripPrefix(2))
                        .uri("lb://req-service"))
                .route("deliver-service", r -> r.order(-90).path("/api/deliver/**")
                        .filters(f -> f.stripPrefix(2))
                        .uri("lb://deliver-service"))
                .route("deliver-openapi", r -> r.order(-80).path("/openapi/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://deliver-service"))
                .build();
    }
}
