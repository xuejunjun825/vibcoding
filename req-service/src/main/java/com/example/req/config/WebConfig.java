package com.example.req.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the Vite dev server.
 *
 * <p>The gateway is the normal entry point, but the frontend also talks to req-service directly
 * during development (and the acceptance scripts call it directly to separate a gateway problem
 * from a service problem). Origins are configured, not wildcarded, because credentials are sent.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] origins;

    public WebConfig(@Value("${platform.web.allowed-origins:"
            + "http://localhost:5173,http://127.0.0.1:5173}") String origins) {
        this.origins = origins.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Request-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
