package com.example.req;

import com.example.req.config.DatabaseMigrations;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Requirement side of the platform: tenants, organizations, domains, vendors, users, permissions,
 * and the requirement/approval/release lifecycles.
 *
 * <p>Scanning is widened to {@code com.example} on purpose: {@code platform-common} contributes
 * the auth filter, tenant context, audit service and scoped JDBC, none of which are
 * auto-configured. A narrower scan compiles fine and then fails at the first authenticated
 * request.</p>
 */
@SpringBootApplication(scanBasePackages = "com.example")
@EnableJpaRepositories(basePackages = "com.example")
@EntityScan(basePackages = "com.example")
public class ReqServiceApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ReqServiceApplication.class);
        // Migrate before JPA validates the schema: the Flyway auto-configuration lives in the
        // optional spring-boot-flyway module, which this build does not have on its classpath.
        application.addInitializers(context ->
                DatabaseMigrations.run(context.getEnvironment()));
        application.run(args);
    }
}
