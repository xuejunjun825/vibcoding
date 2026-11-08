package com.example.req.config;

import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Applies the database migrations before any JPA bean is created.
 *
 * <p>Spring Boot 4 moved Flyway's auto-configuration into the optional {@code spring-boot-flyway}
 * module, which is not on this project's classpath (it is also absent from the offline repository),
 * so the migration would never run and {@code ddl-auto=validate} would fail on an empty schema.
 * This initializer drives the same Flyway API the auto-configuration would, with the same property
 * names, and is skipped when {@code spring.flyway.enabled=false}.</p>
 */
public final class DatabaseMigrations {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseMigrations.class);

    private DatabaseMigrations() {
    }

    /** Called from {@code ReqServiceApplication.main} before the context is refreshed. */
    public static void run(ConfigurableEnvironment environment) {
        if (!Boolean.parseBoolean(property(environment, "spring.flyway.enabled", "true"))) {
            LOG.info("flyway disabled by configuration; schema is expected to exist already");
            return;
        }
        String url = property(environment, "spring.datasource.url", null);
        if (url == null || url.isBlank()) {
            LOG.warn("no spring.datasource.url configured; skipping database migration");
            return;
        }
        String user = property(environment, "spring.datasource.username", "");
        String password = property(environment, "spring.datasource.password", "");
        String locations = property(environment, "spring.flyway.locations", "classpath:db/migration");
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations(locations.split("\\s*,\\s*"))
                .baselineOnMigrate(Boolean.parseBoolean(
                        property(environment, "spring.flyway.baseline-on-migrate", "false")))
                .validateOnMigrate(Boolean.parseBoolean(
                        property(environment, "spring.flyway.validate-on-migrate", "true")))
                .load();
        var result = flyway.migrate();
        LOG.info("flyway migrated {} migration(s) to version {}",
                result.migrationsExecuted, result.targetSchemaVersion);
    }

    private static String property(Environment environment, String key, String fallback) {
        String value = Binder.get(environment).bind(key, String.class).orElse(null);
        if (value == null) {
            value = environment.getProperty(key, fallback);
        }
        return value == null ? fallback : value;
    }

    /** Diagnostics used by the verify script when a migration fails. */
    static String describe(String[] locations) {
        return "locations=" + Arrays.toString(locations);
    }
}
