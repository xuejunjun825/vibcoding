package com.example.req.automation.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.automation.api.AutomationDtos.ActionOutcome;
import com.example.req.automation.api.AutomationDtos.RunView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code /automation/runs}: the execution log.
 *
 * <p>Read-only by construction — there is no endpoint that re-runs, deletes or edits a run. A run is
 * evidence: it says what a rule did to a requirement at a moment in time, and evidence that can be
 * rewritten is not evidence.</p>
 *
 * <p>What the log may contain is also structural: the rule name comes from {@code LEFT JOIN}
 * (renaming a rule intentionally renames its history — PRD's known trade-off), the requirement code
 * from {@code LEFT JOIN requirement}, and per-action results from {@code actions_json}. No request
 * body, no requirement description, no webhook URL — AC-12 forbids leaking those onto a page read by
 * anyone with the page permission.</p>
 */
@RestController
@RequestMapping("/automation")
public class AutomationRunController {

    private static final String SELECT = """
            SELECT ar.id AS id, ar.rule_id AS rule_id, rl.name AS rule_name,
                   ar.rule_version AS rule_version, ar.event_type AS event_type,
                   ar.event_id AS event_id, ar.requirement_id AS requirement_id,
                   rq.code AS requirement_code, ar.status AS status, ar.skip_reason AS skip_reason,
                   ar.actions_json AS actions_json, ar.duration_ms AS duration_ms,
                   ar.created_at AS created_at
              FROM automation_run ar
              LEFT JOIN automation_rule rl ON rl.id = ar.rule_id AND rl.tenant_id = ar.tenant_id
              LEFT JOIN requirement rq ON rq.id = ar.requirement_id AND rq.tenant_id = ar.tenant_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AutomationRunController(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/runs")
    public PageResponse<RunView> list(@RequestParam(required = false) Long ruleId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String requirementCode,
                                      @RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        long tenantId = TenantContext.require().tenantId();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        StringBuilder where = new StringBuilder(" WHERE ar.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (ruleId != null) {
            where.append(" AND ar.rule_id = ?");
            args.add(ruleId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND ar.status = ?");
            args.add(status.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (requirementCode != null && !requirementCode.isBlank()) {
            where.append(" AND rq.code LIKE ?");
            args.add("%" + requirementCode.trim() + "%");
        }
        if (from != null) {
            where.append(" AND ar.created_at >= ?");
            args.add(from.atStartOfDay());
        }
        if (to != null) {
            where.append(" AND ar.created_at < ?");
            args.add(to.plusDays(1).atStartOfDay());
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + SELECT + where + ") t",
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) (safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT + where + " ORDER BY ar.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        List<RunView> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(toView(row));
        }
        return new PageResponse<>(items, total == null ? 0L : total, safePage, safeSize);
    }

    @GetMapping("/runs/{id}")
    public RunView detail(@PathVariable long id) {
        long tenantId = TenantContext.require().tenantId();
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " WHERE ar.id = ? AND ar.tenant_id = ?",
                id, tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "RUN_NOT_FOUND", "执行记录不存在");
        }
        return toView(rows.get(0));
    }

    private RunView toView(Map<String, Object> row) {
        return new RunView(
                number(row.get("id")),
                number(row.get("rule_id")),
                string(row.get("rule_name")),
                (int) number(row.get("rule_version")),
                string(row.get("event_type")),
                string(row.get("event_id")),
                row.get("requirement_id") == null ? null : number(row.get("requirement_id")),
                string(row.get("requirement_code")),
                string(row.get("status")),
                string(row.get("skip_reason")),
                actions(row.get("actions_json")),
                number(row.get("duration_ms")),
                row.get("created_at") instanceof java.sql.Timestamp ts
                        ? ts.toLocalDateTime()
                        : (java.time.LocalDateTime) row.get("created_at"));
    }

    private List<ActionOutcome> actions(Object raw) {
        if (raw == null) {
            return List.of();
        }
        String json = String.valueOf(raw);
        if (json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ActionOutcome>>() {
            });
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static Map<String, Object> ordered() {
        return new LinkedHashMap<>();
    }
}
