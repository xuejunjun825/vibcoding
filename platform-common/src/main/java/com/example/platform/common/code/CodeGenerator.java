package com.example.platform.common.code;

import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant document numbers ({@code BUS-0001}, {@code DEP-0003}).
 *
 * <p>A row lock on {@code code_sequence} rather than {@code MAX(code)+1}: the latter repeats
 * numbers as soon as two requests interleave or a row is deleted, and a repeated document number
 * is not a cosmetic problem — it is an audit failure. {@code next_val} stores the last issued
 * value (the seed inserts values that match documents it created itself).</p>
 */
@Service
public class CodeGenerator {

    private final JdbcTemplate jdbc;

    public CodeGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public String next(String prefix, long tenantId) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix is required");
        }
        String type = prefix.trim().toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO code_sequence (tenant_id, code_type, next_val) VALUES (?, ?, 0)"
                + " ON DUPLICATE KEY UPDATE next_val = next_val", tenantId, type);
        Long current = jdbc.queryForObject(
                "SELECT next_val FROM code_sequence WHERE tenant_id = ? AND code_type = ? FOR UPDATE",
                Long.class, tenantId, type);
        long next = (current == null ? 0L : current) + 1;
        jdbc.update("UPDATE code_sequence SET next_val = ? WHERE tenant_id = ? AND code_type = ?",
                next, tenantId, type);
        return type + "-" + String.format(Locale.ROOT, "%04d", next);
    }
}
