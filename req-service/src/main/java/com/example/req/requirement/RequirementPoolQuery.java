package com.example.req.requirement;

import com.example.platform.common.scope.ScopedJdbc;
import com.example.req.requirement.RequirementViews.Page;
import com.example.req.requirement.RequirementViews.Rollup;
import com.example.req.requirement.RequirementViews.View;
import com.example.req.requirement.RequirementRollup.ChildAggregate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求池查询：8 类筛选 + 聚合/平铺两种视图。
 *
 * <p>聚合与平铺共用同一个 WHERE 与同一个 ORDER BY，因此同一筛选条件下两者行数必然一致；
 * 聚合视图只是额外挂上子需求人天/完成度汇总（与详情页同一 {@link RequirementRollup} 算法）。</p>
 *
 * <p>所有 SQL 都带 {@code /*scope:r*}{@code /} 标记，缺失会被 ScopedJdbc fail-closed 拒绝。</p>
 */
@Component
public class RequirementPoolQuery {

    private static final String COLUMNS = """
            r.id AS id, r.code AS code, r.type AS type, r.status AS status, r.priority AS priority,
            r.title AS title, r.description AS description, r.parent_id AS parent_id,
            r.domain_id AS domain_id, r.origin_org_id AS origin_org_id,
            r.owner_user_id AS owner_user_id, r.owner_vendor_id AS owner_vendor_id,
            r.assignee_vendor_id AS assignee_vendor_id, r.release_id AS release_id,
            r.estimate_days AS estimate_days, r.actual_days AS actual_days, r.mr_url AS mr_url,
            r.expected_delivery_date AS expected_delivery_date, r.plan_start_date AS plan_start_date,
            r.plan_end_date AS plan_end_date, r.closed_at AS closed_at, r.updated_at AS updated_at
            """;

    private final ScopedJdbc jdbc;
    private final RequirementStateMachine stateMachine;

    public RequirementPoolQuery(ScopedJdbc jdbc, RequirementStateMachine stateMachine) {
        this.jdbc = jdbc;
        this.stateMachine = stateMachine;
    }

    public Page<View> page(PoolFilter filter) {
        Map<String, Object> params = params(filter);
        String where = where();
        long total = jdbc.count("SELECT COUNT(*) FROM requirement r WHERE " + where, params);

        if (total == 0 || filter.offset() >= total) {
            return new Page<>(List.of(), total, filter.page(), filter.size());
        }

        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("limit", filter.size());
        pageParams.put("offset", filter.offset());
        List<Map<String, Object>> rows = jdbc.list(
                "SELECT " + COLUMNS + " FROM requirement r WHERE " + where
                        + " ORDER BY r.updated_at DESC, r.id DESC LIMIT :limit OFFSET :offset",
                pageParams);

        Map<Long, Rollup> rollups = filter.aggregate() ? rollups(rows) : Map.of();
        List<View> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Long id = Rows.toLong(Rows.object(row, "id"));
            items.add(toView(row, rollups.get(id)));
        }
        return new Page<>(items, total, filter.page(), filter.size());
    }

    /** 导出用：沿用同一 page() 逐页取，顺序稳定，行数严格等于 count。 */
    public List<View> all(PoolFilter filter, int maxRows) {
        List<View> out = new ArrayList<>();
        int pageNo = 1;
        int size = PoolFilter.MAX_SIZE;
        while (out.size() < maxRows) {
            PoolFilter slice = new PoolFilter(filter.type(), filter.keyword(), filter.parentId(),
                    filter.releaseId(), filter.ownerId(), filter.priority(), filter.status(),
                    filter.domainId(), filter.vendorId(), filter.orgId(), filter.dateFrom(),
                    filter.dateTo(), filter.view(), filter.overdue(), pageNo, size);
            Page<View> page = page(slice);
            out.addAll(page.items());
            if (page.items().size() < size || out.size() >= page.total()) {
                break;
            }
            pageNo++;
        }
        return out.size() > maxRows ? out.subList(0, maxRows) : out;
    }

    /** 导出行数校验用：与 page() 完全同一 WHERE，避免两次口径漂移。 */
    public long count(PoolFilter filter) {
        return jdbc.count("SELECT COUNT(*) FROM requirement r WHERE " + where(), params(filter));
    }

    /**
     * 导出用：按主键 keyset 分批，而不是 deep OFFSET。
     *
     * <p>10 万行时 {@code LIMIT 200 OFFSET 99800} 每批都要扫过前面所有行（O(n²)，实测 25s 超时）；
     * keyset 每批从 {@code afterId} 之后继续，整表只扫一遍。顺序按 id 升序，与列表的
     * {@code updated_at DESC} 不同——导出不承诺与屏幕同序，只承诺行数等于 total。</p>
     */
    public List<View> exportBatch(PoolFilter filter, long afterId, int limit) {
        Map<String, Object> params = params(filter);
        params.put("afterId", afterId);
        params.put("limit", Math.min(Math.max(limit, 1), 5000));
        List<Map<String, Object>> rows = jdbc.list(
                "SELECT " + COLUMNS + " FROM requirement r WHERE " + where()
                        + " AND r.id > :afterId ORDER BY r.id LIMIT :limit",
                params);
        List<View> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(toView(row, null));
        }
        return items;
    }

    private Map<Long, Rollup> rollups(List<Map<String, Object>> rows) {
        List<Long> ids = rows.stream().map(row -> Rows.toLong(Rows.object(row, "id")))
                .filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> grouped = jdbc.list("""
                SELECT g.parent_id AS parent_id, g.status AS status, COUNT(*) AS cnt,
                       SUM(COALESCE(g.estimate_days, 0)) AS est, SUM(COALESCE(g.actual_days, 0)) AS act
                FROM requirement g
                WHERE g.parent_id IN (:ids) AND /*scope:g*/
                GROUP BY g.parent_id, g.status
                """, Map.of("ids", ids));

        Map<Long, List<ChildAggregate>> byParent = new LinkedHashMap<>();
        for (Map<String, Object> row : grouped) {
            Long parentId = Rows.toLong(Rows.object(row, "parent_id"));
            if (parentId == null) {
                continue;
            }
            byParent.computeIfAbsent(parentId, key -> new ArrayList<>()).add(new ChildAggregate(
                    Rows.str(row, "status", "STATUS"),
                    Rows.toLong(Rows.object(row, "cnt", "CNT"), 0),
                    Rows.toDecimal(Rows.object(row, "est", "EST")),
                    Rows.toDecimal(Rows.object(row, "act", "ACT"))));
        }

        Map<Long, Rollup> result = new HashMap<>();
        byParent.forEach((parentId, groups) ->
                result.put(parentId, RequirementRollup.fromGroups(groups,
                        stateMachine::isTerminalAcrossTypes)));
        return result;
    }

    private String where() {
        return """
                1 = 1
                AND /*scope:r*/
                AND (:type IS NULL OR r.type = :type)
                AND (:status IS NULL OR r.status = :status)
                AND (:priority IS NULL OR r.priority = :priority)
                AND (:parentId IS NULL OR r.parent_id = :parentId)
                AND (:releaseId IS NULL OR r.release_id = :releaseId)
                AND (:ownerId IS NULL OR r.owner_user_id = :ownerId)
                AND (:vendorId IS NULL OR r.assignee_vendor_id = :vendorId)
                AND (:orgId IS NULL OR r.origin_org_id = :orgId)
                AND (:domainId IS NULL OR r.domain_id = :domainId
                     OR EXISTS (SELECT 1 FROM requirement_domain_rel d
                                WHERE d.requirement_id = r.id AND d.domain_id = :domainId))
                AND (:keyword IS NULL OR r.title LIKE :keyword OR r.code LIKE :keyword)
                AND (:dateFrom IS NULL OR r.updated_at >= :dateFrom)
                AND (:dateTo IS NULL OR r.updated_at < :dateTo)
                AND (:overdue = FALSE OR (r.closed_at IS NULL AND r.plan_end_date IS NOT NULL
                     AND r.plan_end_date < CURDATE()))
                """;
    }

    private Map<String, Object> params(PoolFilter filter) {
        Map<String, Object> params = new HashMap<>();
        params.put("type", filter.type());
        params.put("status", filter.status());
        params.put("priority", filter.priority());
        params.put("parentId", filter.parentId());
        params.put("releaseId", filter.releaseId());
        params.put("ownerId", filter.ownerId());
        params.put("vendorId", filter.vendorId());
        params.put("orgId", filter.orgId());
        params.put("domainId", filter.domainId());
        params.put("keyword", filter.keyword() == null ? null : "%" + filter.keyword() + "%");
        params.put("dateFrom", filter.dateFrom());
        params.put("dateTo", filter.dateTo() == null ? null : filter.dateTo().plusDays(1));
        params.put("overdue", Boolean.TRUE.equals(filter.overdue()));
        return params;
    }

    View toView(Map<String, Object> row, Rollup rollup) {
        Long id = Rows.toLong(Rows.object(row, "id"));
        String type = Rows.str(row, "type");
        String status = Rows.str(row, "status");
        return new View(id, Rows.str(row, "code"), type, status, Rows.str(row, "priority"),
                Rows.str(row, "title"), Rows.str(row, "description"),
                Rows.toLong(Rows.object(row, "parent_id")), null,
                Rows.toLong(Rows.object(row, "domain_id")),
                Rows.toLong(Rows.object(row, "origin_org_id")),
                Rows.toLong(Rows.object(row, "owner_user_id")),
                Rows.toLong(Rows.object(row, "owner_vendor_id")),
                Rows.toLong(Rows.object(row, "assignee_vendor_id")),
                Rows.toLong(Rows.object(row, "release_id")),
                Rows.toDecimal(Rows.object(row, "estimate_days")),
                Rows.toDecimal(Rows.object(row, "actual_days")),
                Rows.str(row, "mr_url"),
                Rows.toDate(Rows.object(row, "expected_delivery_date")),
                Rows.toDate(Rows.object(row, "plan_start_date")),
                Rows.toDate(Rows.object(row, "plan_end_date")),
                Rows.toDateTime(Rows.object(row, "closed_at")),
                Rows.toDateTime(Rows.object(row, "updated_at")),
                rollup, stateMachine.allowedActions(type, status).stream().toList(), Map.of());
    }
}
