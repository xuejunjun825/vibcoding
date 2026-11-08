package com.example.deliver.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.stereotype.Component;

/**
 * The six-ring matrix: requirement → task → MR → execution → defect → release/version.
 *
 * <p>Every flag is computed by the database in one statement over the same row, so "complete"
 * can never disagree with the cells it is displayed next to. The gap filter is the same
 * conditions spelled in SQL, which means the filtered page and the per-row gap list agree.</p>
 *
 * <p>Scope: the anchor is {@code requirement}, so a vendor sees only the requirements it carries
 * — the downstream counts are subqueries of that already-scoped row, never a second scoped
 * query that could widen the result set.</p>
 */
@Component
public class TraceabilityQuery {

    /** Gap codes, in the order the detail tab lists them. */
    private static final List<String> GAP_ORDER =
            List.of("TASK", "MR", "CASE", "EXECUTION", "DEFECT", "RELEASE", "VERSION");

    private final ScopedJdbc jdbc;

    public TraceabilityQuery(ScopedJdbc jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<TraceViews.TraceRow> page(String type, Long domainId, Long vendorId,
                                                  boolean gapOnly, int page, int size) {
        TenantContext.require();
        Map<String, Object> params = new LinkedHashMap<>();
        String fromWhere = fromWhere(PAGE_FROM_WHERE, type, domainId, vendorId, gapOnly, params);
        long total = jdbc.count("SELECT COUNT(*)" + fromWhere, params);

        int safeSize = size <= 0 ? 20 : Math.min(size, 200);
        int safePage = page <= 0 ? 1 : page;
        long offset = (long) (safePage - 1) * safeSize;

        // LIMIT/OFFSET 必须是字面量：绑定参数时 MySQL 无法在优化期利用页大小，
        // 会对 10 万需求整表排序并为每条候选行计算 8 个相关子查询（实测 1.8s / 870 万行）。
        // 两个值都是上面校验过的 int，内联不引入注入面。
        List<Map<String, Object>> rows = jdbc.list(SELECT + fromWhere
                + " ORDER BY r.id DESC LIMIT " + safeSize + " OFFSET " + offset, params);
        List<TraceViews.TraceRow> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(map(row));
        }
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    public TraceViews.TraceRow detail(long requirementId) {
        TenantContext.require();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requirementId", requirementId);
        List<Map<String, Object>> rows = jdbc.list(SELECT + FROM_WHERE
                + " AND r.id = :requirementId", params);
        if (rows.isEmpty()) {
            throw new ApiException(404, "REQUIREMENT_NOT_FOUND", "需求不存在或不在你的数据范围内",
                    Map.of("requirementId", requirementId));
        }
        return map(rows.get(0));
    }

    // ------------------------------------------------------------------ SQL

    private static final String SELECT = """
            SELECT r.id AS requirement_id, r.code AS requirement_code, r.title AS title,
                   r.type AS type, r.status AS status, r.domain_id AS domain_id,
                   r.release_id AS release_id, r.mr_url AS mr_url,
                   d.name AS domain_name, v.name AS vendor_name,
                   (SELECT COUNT(*) FROM task tk WHERE tk.requirement_id = r.id) AS task_count,
                   (SELECT COUNT(*) FROM test_case tc WHERE tc.requirement_id = r.id
                       AND tc.deleted_at IS NULL) AS case_count,
                   (SELECT COUNT(*) FROM test_execution te WHERE te.requirement_id = r.id) AS execution_count,
                   (SELECT COUNT(*) FROM defect df WHERE df.requirement_id = r.id) AS defect_count,
                   (SELECT COUNT(*) FROM defect df WHERE df.requirement_id = r.id
                       AND df.status <> 'CLOSED' AND df.severity IN ('P0','P1')) AS open_high_count,
                   (SELECT COUNT(*) FROM deploy_order_requirement dor
                       WHERE dor.requirement_id = r.id) AS deploy_count,
                   (SELECT COUNT(*) FROM `release` rel WHERE rel.id = r.release_id) AS release_exists
            """;

    private static final String FROM_WHERE = """
             FROM requirement r
             LEFT JOIN biz_domain d ON d.id = r.domain_id
             LEFT JOIN sys_vendor v ON v.id = COALESCE(r.assignee_vendor_id, r.owner_vendor_id)
             WHERE /*scope:r*/
            """;

    /**
     * 列表页专用的 FROM：把 r 钉在 (tenant_id, id) 索引上。
     *
     * <p>优化器对这条查询估错基数时会选 {@code uk_req_code(tenant_id)} + Sort，先扫完本租户
     * 全部需求、再逐行计算 8 个相关子查询后才 LIMIT（10 万行实测扫 1300 万行 / 2.5s）。
     * 该索引的列序正好等于「租户过滤 + id 排序」，逆序扫描可以扫满一页即停；索引由
     * {@code V4__requirement_trace_index.sql} 提供。详情查询按主键取单行，仍走 {@link #FROM_WHERE}。</p>
     */
    private static final String PAGE_FROM_WHERE = FROM_WHERE.replace(
            "FROM requirement r", "FROM requirement r FORCE INDEX (idx_req_trace_tenant_id)");

    /**
     * The gap predicate, spelled once for SQL. It must stay in step with
     * {@link #gapsOf(long, String, long, long, long, long, Long, Long)} — the harness asserts the
     * two agree on every seeded row.
     */
    private static String gapPredicate() {
        return """
                 (
                   (SELECT COUNT(*) FROM task tk WHERE tk.requirement_id = r.id) = 0
                   OR r.mr_url IS NULL OR TRIM(r.mr_url) = ''
                   OR (SELECT COUNT(*) FROM test_case tc WHERE tc.requirement_id = r.id
                       AND tc.deleted_at IS NULL) = 0
                   OR (SELECT COUNT(*) FROM test_execution te WHERE te.requirement_id = r.id) = 0
                   OR (SELECT COUNT(*) FROM defect df WHERE df.requirement_id = r.id
                       AND df.status <> 'CLOSED' AND df.severity IN ('P0','P1')) > 0
                   OR r.release_id IS NULL
                   OR (SELECT COUNT(*) FROM deploy_order_requirement dor
                       WHERE dor.requirement_id = r.id) = 0
                 )
                """;
    }

    private String fromWhere(String base, String type, Long domainId, Long vendorId, boolean gapOnly,
                             Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(base);
        if (type != null && !type.isBlank()) {
            sql.append(" AND r.type = :type");
            params.put("type", type.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (domainId != null) {
            sql.append(" AND r.domain_id = :domainId");
            params.put("domainId", domainId);
        }
        if (vendorId != null) {
            sql.append(" AND (r.assignee_vendor_id = :vendorId OR r.owner_vendor_id = :vendorId)");
            params.put("vendorId", vendorId);
        }
        if (gapOnly) {
            sql.append(" AND ").append(gapPredicate());
        }
        return sql.toString();
    }

    // ------------------------------------------------------------------ mapping

    private TraceViews.TraceRow map(Map<String, Object> row) {
        long requirementId = Rows.toLong(Rows.object(row, "requirement_id"), 0L);
        String code = Rows.str(row, "requirement_code");
        String mrUrl = Rows.str(row, "mr_url");
        long taskCount = Rows.toLong(Rows.object(row, "task_count"), 0L);
        long caseCount = Rows.toLong(Rows.object(row, "case_count"), 0L);
        long executionCount = Rows.toLong(Rows.object(row, "execution_count"), 0L);
        long defectCount = Rows.toLong(Rows.object(row, "defect_count"), 0L);
        long openHigh = Rows.toLong(Rows.object(row, "open_high_count"), 0L);
        long deployCount = Rows.toLong(Rows.object(row, "deploy_count"), 0L);
        Long releaseId = Rows.object(row, "release_id") == null
                ? null : Rows.toLong(Rows.object(row, "release_id"), 0L);

        boolean hasMr = mrUrl != null && !mrUrl.isBlank();
        boolean hasRelease = releaseId != null;

        Map<String, TraceViews.TraceCell> cells = new LinkedHashMap<>();
        cells.put("task", taskCount > 0
                ? TraceViews.TraceCell.complete(taskCount + " 个任务", (int) taskCount,
                        "已拆解 " + taskCount + " 个研发任务")
                : TraceViews.TraceCell.gap("缺任务", 0, "需求还没有拆解出研发任务"));
        cells.put("mr", hasMr
                ? TraceViews.TraceCell.complete("已关联", 1, mrUrl)
                : TraceViews.TraceCell.gap("缺 MR", 0, "需求未填写代码合并请求地址"));
        TraceViews.TraceCell executionCell = executionCount > 0
                ? TraceViews.TraceCell.complete(executionCount + " 次执行", (int) executionCount,
                        "已产生 " + executionCount + " 条测试执行记录")
                : TraceViews.TraceCell.gap("缺执行", 0, "该需求还没有任何测试执行记录");
        cells.put("test", executionCell);
        cells.put("execution", executionCell);
        cells.put("defect", openHigh > 0
                ? TraceViews.TraceCell.gap("未关闭 " + openHigh, (int) openHigh,
                        openHigh + " 个未关闭的 P0/P1 缺陷阻塞发布")
                : TraceViews.TraceCell.complete("无阻塞", (int) defectCount,
                        defectCount == 0 ? "暂无缺陷" : "共 " + defectCount + " 个缺陷，无未关闭 P0/P1"));
        cells.put("release", deployCount > 0
                ? TraceViews.TraceCell.complete(deployCount + " 个发布单", (int) deployCount,
                        "已关联 " + deployCount + " 个发布单")
                : TraceViews.TraceCell.gap("未发布", 0, "该需求还没有发布单"));
        cells.put("version", hasRelease
                ? TraceViews.TraceCell.complete("已在版本", 1, "已纳入版本计划 " + releaseId)
                : TraceViews.TraceCell.gap("无版本", 0, "需求未关联版本计划"));

        List<String> gaps = gapsOf(taskCount, mrUrl, caseCount, executionCount, openHigh,
                deployCount, releaseId);
        return new TraceViews.TraceRow(requirementId, code, Rows.str(row, "title"),
                Rows.str(row, "type"), Rows.str(row, "status"),
                Rows.str(row, "domain_name"), Rows.str(row, "vendor_name"),
                cells, gaps, gaps.isEmpty());
    }

    /** Java mirror of {@link #gapPredicate()}; keep the two in step. */
    private static List<String> gapsOf(long taskCount, String mrUrl, long caseCount,
                                       long executionCount, long openHigh, long deployCount,
                                       Long releaseId) {
        Map<String, Boolean> missing = new LinkedHashMap<>();
        missing.put("TASK", taskCount == 0);
        missing.put("MR", mrUrl == null || mrUrl.isBlank());
        missing.put("CASE", caseCount == 0);
        missing.put("EXECUTION", executionCount == 0);
        missing.put("DEFECT", openHigh > 0);
        missing.put("RELEASE", deployCount == 0);
        missing.put("VERSION", releaseId == null);
        List<String> gaps = new ArrayList<>();
        for (String code : GAP_ORDER) {
            if (Boolean.TRUE.equals(missing.get(code))) {
                gaps.add(code);
            }
        }
        return gaps;
    }
}
