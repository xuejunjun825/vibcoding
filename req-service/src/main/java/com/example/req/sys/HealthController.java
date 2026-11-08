package com.example.req.sys;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Readiness probe polled by {@code scripts/platform-up.sh}: it must report the three things the
 * start script checks — the process, the database and the applied migration — instead of a plain
 * "UP" that hides a half-migrated schema.
 */
@RestController
@RequestMapping("/internal/health")
public class HealthController {

    @PersistenceContext
    private EntityManager em;

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "req-service");
        boolean dbUp = false;
        try {
            em.createNativeQuery("SELECT 1").getSingleResult();
            dbUp = true;
        } catch (RuntimeException ex) {
            body.put("dbError", ex.getClass().getSimpleName());
        }
        body.put("db", dbUp ? "up" : "down");
        body.put("flyway", flywayVersion());
        return body;
    }

    /** Latest successful migration version; {@code null} before the first migration ran. */
    private String flywayVersion() {
        try {
            List<?> rows = em.createNativeQuery("SELECT version FROM flyway_schema_history"
                    + " WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1").getResultList();
            return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
