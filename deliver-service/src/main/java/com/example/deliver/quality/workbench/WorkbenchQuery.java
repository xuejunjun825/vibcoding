package com.example.deliver.quality.workbench;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.contract.MetricFormulas;
import org.springframework.stereotype.Component;

/**
 * Read-only "my work" view (AC-9): the plans I own or execute, and the cases waiting on me.
 * Nothing here writes — the workbench must stay safe to poll while other people execute.
 */
@Component
public class WorkbenchQuery {

    private final ScopedJdbc jdbcRead;

    public WorkbenchQuery(ScopedJdbc jdbcRead) {
        this.jdbcRead = jdbcRead;
    }

    public record Workbench(List<PlanCard> plans, List<CaseCard> cases, Totals totals) {
    }

    public record PlanCard(long planId, String planName, long releaseId, String releaseName,
                           String status, Long roundId, Integer roundNo, String dueDate,
                           int planned, int executed, java.math.BigDecimal passRate) {
    }

    public record CaseCard(long caseId, String caseCode, String title, long planId,
                           String planName, Long roundId, Integer roundNo, String result,
                           String dueDate, boolean overdue) {
    }

    public record Totals(int plans, int pendingCases, int overdueCases, int executedCases) {
    }

    /**
     * Row of {@code GET /workbench/my-cases} — mirrors {@code MyCaseRow} in web/src/api/types.ts.
     * {@code unassigned} marks cases of a plan I own that nobody has picked up yet.
     */
    public record MyCaseRow(long caseId, String caseCode, String title, long planId,
                            String planName, Long roundId, Integer roundNo, Long moduleId,
                            String moduleName, Long executorId, String executorName,
                            String dueDate, String result, boolean overdue, boolean unassigned) {
    }

    /**
     * Paginated "my cases" list (AC-9). The overdue filter runs in SQL so {@code total} matches
     * the filtered rows; due dates fall back to the plan window for unassigned cases.
     */
    public PageResponse<MyCaseRow> myCases(int page, int size, boolean overdueOnly) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", com.example.platform.common.tenant.TenantContext.require().userId());
        params.put("overdueOnly", overdueOnly);
        PageResponse<Map<String, Object>> rows = jdbcRead.page("""
                SELECT t.* FROM (
                    SELECT pc.case_id AS case_id, c.code AS case_code, c.title AS title,
                           pc.plan_id AS plan_id, p.name AS plan_name,
                           rr.id AS round_id, rr.round_no AS round_no,
                           c.module_id AS module_id, m.name AS module_name,
                           pc.executor_id AS executor_id, u.name AS executor_name,
                           pc.result AS result,
                           COALESCE((SELECT MIN(a.due_date) FROM plan_assignment a
                                      WHERE a.plan_id = pc.plan_id
                                        AND a.executor_id = :userId),
                                    DATE(p.window_end)) AS due_date,
                           CASE WHEN pc.executor_id IS NULL THEN 1 ELSE 0 END AS unassigned
                    FROM test_plan_case pc
                    JOIN test_plan p ON p.id = pc.plan_id
                    LEFT JOIN test_case c ON c.id = pc.case_id
                    LEFT JOIN test_module m ON m.id = c.module_id
                    LEFT JOIN test_plan_round rr ON rr.plan_id = p.id AND rr.active = 1
                    LEFT JOIN sys_user u ON u.id = pc.executor_id
                    WHERE /*scope:p*/
                      AND p.status = 'IN_PROGRESS'
                      AND (pc.executor_id = :userId
                           OR (pc.executor_id IS NULL AND p.owner_id = :userId))
                      AND pc.result <> 'PASS'
                ) t
                WHERE (:overdueOnly = FALSE OR t.due_date < CURDATE())
                ORDER BY t.due_date ASC, t.case_id ASC
                """, params, page, size);
        LocalDate today = LocalDate.now();
        List<MyCaseRow> items = new ArrayList<>(rows.items().size());
        for (Map<String, Object> row : rows.items()) {
            LocalDate due = Rows.toDate(Rows.object(row, "due_date"));
            items.add(new MyCaseRow(
                    Rows.toLong(Rows.object(row, "case_id"), 0L),
                    Rows.str(row, "case_code"),
                    Rows.str(row, "title"),
                    Rows.toLong(Rows.object(row, "plan_id"), 0L),
                    Rows.str(row, "plan_name"),
                    Rows.lng(row, "round_id"),
                    row.get("round_no") == null ? null : Rows.toInt(row.get("round_no"), 0),
                    Rows.lng(row, "module_id"),
                    Rows.str(row, "module_name"),
                    Rows.lng(row, "executor_id"),
                    Rows.str(row, "executor_name"),
                    due == null ? null : due.toString(),
                    Rows.str(row, "result", "NOT_RUN"),
                    due != null && due.isBefore(today),
                    Rows.toLong(Rows.object(row, "unassigned"), 0L) > 0));
        }
        return new PageResponse<>(items, rows.total(), rows.page(), rows.size());
    }

    public Workbench load(String keyword, int limit) {
        String filter = keyword == null || keyword.isBlank() ? "" : " AND p.name LIKE :keyword";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", com.example.platform.common.tenant.TenantContext.require().userId());
        if (!filter.isEmpty()) {
            params.put("keyword", "%" + keyword.trim() + "%");
        }
        List<Map<String, Object>> planRows = jdbcRead.list("""
                SELECT p.id, p.name, p.release_id, r.name AS release_name, p.status,
                       rr.id AS round_id, rr.round_no AS round_no, p.window_end AS window_end,
                       COUNT(pc.id) AS planned,
                       COALESCE(SUM(CASE WHEN pc.result <> 'NOT_RUN' THEN 1 ELSE 0 END), 0) AS executed
                FROM test_plan p
                JOIN `release` r ON r.id = p.release_id
                LEFT JOIN test_plan_round rr ON rr.plan_id = p.id AND rr.active = 1
                LEFT JOIN test_plan_case pc ON pc.plan_id = p.id
                WHERE /*scope:p*/
                  AND p.status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'IN_PROGRESS')
                  AND (p.owner_id = :userId OR EXISTS (
                        SELECT 1 FROM plan_assignment a
                         WHERE a.plan_id = p.id AND a.executor_id = :userId))%s
                GROUP BY p.id, p.name, p.release_id, r.name, p.status, rr.id, rr.round_no,
                         p.window_end
                ORDER BY p.window_end ASC, p.id DESC
                LIMIT %d
                """.formatted(filter, limit), params);
        List<PlanCard> plans = new ArrayList<>(planRows.size());
        for (Map<String, Object> row : planRows) {
            long planned = Rows.toLong(Rows.object(row, "planned"), 0L);
            long executed = Rows.toLong(Rows.object(row, "executed"), 0L);
            plans.add(new PlanCard(
                    Rows.toLong(Rows.object(row, "id"), 0L),
                    Rows.str(row, "name"),
                    Rows.toLong(Rows.object(row, "release_id"), 0L),
                    Rows.str(row, "release_name"),
                    Rows.str(row, "status"),
                    Rows.lng(row, "round_id"),
                    row.get("round_no") == null ? null : Rows.toInt(row.get("round_no"), 0),
                    dateText(Rows.object(row, "window_end")),
                    (int) planned,
                    (int) executed,
                    MetricFormulas.coverage(executed, planned)));
        }
        List<Map<String, Object>> caseRows = jdbcRead.list("""
                SELECT pc.case_id, c.code AS case_code, c.title AS title, pc.plan_id,
                       p.name AS plan_name, rr.id AS round_id, rr.round_no AS round_no,
                       pc.result, MIN(a.due_date) AS due_date
                FROM test_plan_case pc
                JOIN test_plan p ON p.id = pc.plan_id
                LEFT JOIN test_case c ON c.id = pc.case_id
                LEFT JOIN test_plan_round rr ON rr.plan_id = p.id AND rr.active = 1
                LEFT JOIN plan_assignment a ON a.plan_id = pc.plan_id
                     AND a.executor_id = pc.executor_id
                WHERE /*scope:p*/ AND pc.executor_id = :userId
                  AND pc.result = 'NOT_RUN'
                  AND p.status = 'IN_PROGRESS'%s
                GROUP BY pc.case_id, c.code, c.title, pc.plan_id, p.name, rr.id, rr.round_no,
                         pc.result
                ORDER BY due_date ASC, pc.case_id ASC
                LIMIT %d
                """.formatted(filter, limit), params);
        LocalDate today = LocalDate.now();
        List<CaseCard> cases = new ArrayList<>(caseRows.size());
        int overdue = 0;
        for (Map<String, Object> row : caseRows) {
            LocalDate due = Rows.toDate(Rows.object(row, "due_date"));
            boolean late = due != null && due.isBefore(today);
            if (late) {
                overdue++;
            }
            cases.add(new CaseCard(
                    Rows.toLong(Rows.object(row, "case_id"), 0L),
                    Rows.str(row, "case_code"),
                    Rows.str(row, "title"),
                    Rows.toLong(Rows.object(row, "plan_id"), 0L),
                    Rows.str(row, "plan_name"),
                    Rows.lng(row, "round_id"),
                    row.get("round_no") == null ? null : Rows.toInt(row.get("round_no"), 0),
                    Rows.str(row, "result", "NOT_RUN"),
                    due == null ? null : due.toString(),
                    late));
        }
        int executed = plans.stream().mapToInt(PlanCard::executed).sum();
        return new Workbench(plans, cases,
                new Totals(plans.size(), cases.size(), overdue, executed));
    }

    private static String dateText(Object value) {
        LocalDate date = Rows.toDate(value);
        return date == null ? null : date.toString();
    }
}
