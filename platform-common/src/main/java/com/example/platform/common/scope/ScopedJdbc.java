package com.example.platform.common.scope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The only read entry point for SQL: every statement must carry a
 * {@code /*scope:<alias>*}{@code /} marker, which is replaced by {@link ScopeSql}'s predicate.
 *
 * <p>Why fail-closed rather than "default to the current tenant": a missing marker is a bug in
 * the query, not a runtime condition. Rejecting it turns "this list forgot the vendor filter"
 * into a test-time exception instead of a silent cross-tenant leak in production. The exact
 * message is asserted by the unit test and quoted in the technical note.</p>
 */
@Component
public class ScopedJdbc {

    public static final String MISSING_MARKER = "scope marker missing in SQL";

    private static final Pattern MARKER =
            Pattern.compile("/\\*\\s*scope\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\*/");

    private static final Pattern FROM_JOIN = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+(`?)([A-Za-z_][A-Za-z0-9_]*)\\1"
                    + "(?:\\s+(?:as\\s+)?([A-Za-z_][A-Za-z0-9_]*))?");

    private static final Set<String> RESERVED = Set.of(
            "WHERE", "ON", "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "JOIN", "GROUP",
            "ORDER", "LIMIT", "HAVING", "UNION", "SET", "USING", "FOR", "LOCK", "STRAIGHT_JOIN",
            "NATURAL", "AS", "AND", "OR", "SELECT", "VALUES", "WINDOW");

    private final NamedParameterJdbcTemplate jdbc;

    public ScopedJdbc(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ reads

    public List<Map<String, Object>> list(String sql, Map<String, Object> params) {
        Scoped scoped = scope(sql, params);
        return jdbc.queryForList(scoped.sql(), scoped.params());
    }

    public Map<String, Object> one(String sql, Map<String, Object> params) {
        List<Map<String, Object>> rows = list(sql, params);
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            throw new IllegalStateException("expected at most one row, got " + rows.size());
        }
        return rows.get(0);
    }

    public long count(String sql, Map<String, Object> params) {
        Scoped scoped = scope(sql, params);
        Long value = jdbc.queryForObject(scoped.sql(), scoped.params(), Long.class);
        return value == null ? 0L : value;
    }

    public PageResponse<Map<String, Object>> page(String sql, Map<String, Object> params,
                                                  int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(500, Math.max(1, size));
        Scoped scoped = scope(sql, params);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (" + scoped.sql() + ") __scope_page",
                scoped.params(), Long.class);
        Map<String, Object> pageParams = new LinkedHashMap<>(scoped.params());
        pageParams.put("__scopeLimit", safeSize);
        pageParams.put("__scopeOffset", (long) (safePage - 1) * safeSize);
        List<Map<String, Object>> items = jdbc.queryForList(
                scoped.sql() + " LIMIT :__scopeLimit OFFSET :__scopeOffset", pageParams);
        return new PageResponse<>(items, total == null ? 0L : total, safePage, safeSize);
    }

    public void stream(String sql, Map<String, Object> params, RowConsumer consumer) {
        Scoped scoped = scope(sql, params);
        ColumnMapRowMapper mapper = new ColumnMapRowMapper();
        jdbc.query(scoped.sql(), scoped.params(), (ResultSetExtractor<Void>) rs -> {
            int rowNum = 0;
            while (rs.next()) {
                consumer.accept(mapper.mapRow(rs, rowNum++));
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ scope resolution

    /** A statement whose marker(s) have been replaced and whose parameters are complete. */
    public record Scoped(String sql, Map<String, Object> params) {
    }

    /**
     * Replace every marker with its predicate and merge the scope parameters.
     *
     * <p>Package-visible for the unit test that asserts the two failure modes (no marker,
     * unknown alias) without a database.</p>
     */
    Scoped scope(String sql, Map<String, Object> params) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql is required");
        }
        String trimmed = sql.stripLeading().toUpperCase(Locale.ROOT);
        if (!trimmed.startsWith("SELECT") && !trimmed.startsWith("WITH")) {
            throw new IllegalArgumentException("ScopedJdbc is read-only: " + firstWord(sql));
        }
        Matcher marker = MARKER.matcher(sql);
        if (!marker.find()) {
            throw new IllegalStateException(MISSING_MARKER);
        }
        Map<String, String> aliases = aliases(sql);
        DataScope dataScope = TenantContext.require();

        Map<String, Object> merged = new LinkedHashMap<>();
        if (params != null) {
            merged.putAll(params);
        }

        StringBuilder out = new StringBuilder(sql.length() + 128);
        marker.reset();
        int last = 0;
        while (marker.find()) {
            String alias = marker.group(1);
            String table = aliases.get(alias);
            if (table == null) {
                throw new IllegalStateException(
                        "scope marker alias not bound to a table: " + alias
                                + " (markers only apply to FROM/JOIN aliases)");
            }
            SqlPredicate predicate = ScopeSql.forTable(table, alias, dataScope);
            merged.putAll(predicate.params());
            out.append(sql, last, marker.start());
            out.append('(').append(predicate.sql()).append(')');
            last = marker.end();
        }
        out.append(sql.substring(last));
        return new Scoped(out.toString(), merged);
    }

    /**
     * alias → table. Handles {@code FROM t a}, {@code JOIN `t` AS a} and the no-alias case
     * ({@code FROM t WHERE} — the regex would otherwise capture {@code WHERE} as the alias).
     */
    static Map<String, String> aliases(String sql) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher matcher = FROM_JOIN.matcher(sql);
        while (matcher.find()) {
            String table = ScopeSql.normalize(matcher.group(2));
            String alias = matcher.group(3);
            if (alias == null || RESERVED.contains(alias.toUpperCase(Locale.ROOT))) {
                alias = table;
            }
            map.putIfAbsent(alias, table);
        }
        return map;
    }

    private static String firstWord(String sql) {
        String stripped = sql.stripLeading();
        int space = 0;
        while (space < stripped.length() && !Character.isWhitespace(stripped.charAt(space))) {
            space++;
        }
        return stripped.substring(0, space).toUpperCase(Locale.ROOT);
    }

    /** Exposed for diagnostics/tests: the tables the generator knows. */
    public static List<String> scopedTables() {
        List<String> tables = new ArrayList<>();
        for (String table : List.of("requirement", "release", "defect", "task", "test_case",
                "test_plan", "test_execution", "attachment", "deploy_order")) {
            if (ScopeSql.knows(table)) {
                tables.add(table);
            }
        }
        return tables;
    }
}
