package com.example.deliver.testcase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.scope.ScopedJdbc;
import org.springframework.stereotype.Component;

/**
 * Read side of the case library: one WHERE builder feeding both the page and the CSV export, so
 * "table rows = exported rows" cannot drift. The scope marker sits on {@code test_case} — the only
 * table here that carries isolation columns (modules are read through JPA with an explicit
 * tenant predicate in {@link TestCaseService}).
 */
@Component
public class TestCaseQuery {

    private static final String SELECT = """
            SELECT c.id AS id, c.code AS code, c.title AS title, c.module_id AS module_id,
                   m.name AS module_name, c.type AS type, c.priority AS priority,
                   c.current_version AS current_version, c.automated AS automated,
                   c.requirement_id AS requirement_id, c.updated_at AS updated_at
            FROM test_case c
            LEFT JOIN test_module m ON m.id = c.module_id
            WHERE /*scope:c*/ AND c.deleted_at IS NULL
            """;

    private final ScopedJdbc jdbc;

    public TestCaseQuery(ScopedJdbc jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<TestCaseViews.CaseRow> page(List<Long> moduleIds, String keyword,
                                                    String priority, String type, int page, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = SELECT + filter(moduleIds, keyword, priority, type, params)
                + " ORDER BY c.updated_at DESC, c.id DESC";
        PageResponse<Map<String, Object>> rows = jdbc.page(sql, params, page, size);
        List<TestCaseViews.CaseRow> items = new ArrayList<>();
        for (Map<String, Object> row : rows.items()) {
            items.add(map(row));
        }
        return new PageResponse<>(items, rows.total(), rows.page(), rows.size());
    }

    public List<TestCaseViews.CaseRow> export(List<Long> moduleIds, String keyword,
                                              String priority, String type) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = SELECT + filter(moduleIds, keyword, priority, type, params)
                + " ORDER BY c.id ASC";
        List<TestCaseViews.CaseRow> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.list(sql, params)) {
            out.add(map(row));
        }
        return out;
    }

    private String filter(List<Long> moduleIds, String keyword, String priority, String type,
                          Map<String, Object> params) {
        StringBuilder where = new StringBuilder();
        if (moduleIds != null && !moduleIds.isEmpty()) {
            where.append(" AND c.module_id IN (:moduleIds)");
            params.put("moduleIds", moduleIds);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (c.title LIKE :keyword OR c.code LIKE :keyword)");
            params.put("keyword", "%" + keyword.trim() + "%");
        }
        if (priority != null && !priority.isBlank()) {
            where.append(" AND c.priority = :priority");
            params.put("priority", priority.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND c.type = :type");
            params.put("type", type);
        }
        return where.toString();
    }

    private TestCaseViews.CaseRow map(Map<String, Object> row) {
        return new TestCaseViews.CaseRow(
                Rows.toLong(Rows.object(row, "id"), 0L),
                Rows.str(row, "code"),
                Rows.str(row, "title"),
                Rows.toLong(Rows.object(row, "module_id")),
                Rows.str(row, "module_name"),
                CaseType.wire(Rows.str(row, "type")),
                Rows.str(row, "priority"),
                Rows.toInt(Rows.object(row, "current_version"), 1),
                Rows.toInt(Rows.object(row, "automated"), 0) == 1,
                Rows.toLong(Rows.object(row, "requirement_id")),
                Rows.toDateTime(Rows.object(row, "updated_at")) == null
                        ? null : Rows.toDateTime(Rows.object(row, "updated_at")).toString());
    }
}
