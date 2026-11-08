package com.example.deliver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Delivery side of the platform: defects, tasks, sprints, test assets, gates, traceability.
 *
 * <p>Entity and repository scanning is widened to {@code com.example} because the shared
 * runtime module ({@code platform-common}) contributes both: the tenant/auth filter beans and
 * the entities/repositories it reads per request. Narrowing it back to this package would
 * silently drop those beans and only show up as a missing-tenant failure at runtime.</p>
 */
@SpringBootApplication(scanBasePackages = "com.example")
@EnableJpaRepositories(basePackages = "com.example")
@EntityScan(basePackages = "com.example")
public class DeliverServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliverServiceApplication.class, args);
    }
}
