package com.example.req.requirement;

import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「我也需要」跟单的读写入口（r47）。
 *
 * <p>为什么热度统计不写在 {@code RequirementPoolQuery}/{@code RequirementService} 里各来一份：
 * 列表与详情的 {@code followCount}/{@code followedByMe} 必须是同一个来源（r27 的列表-详情分叉
 * 教训）。本类同时给两边用：列表 {@code stats(ids)} 走一次分组查询，详情 {@code stats(id)} 复用
 * 同一段 SQL（{@code IN (:ids)} 单元素），没有第二份计数公式。</p>
 *
 * <p>为什么写入用 {@link JdbcTemplate} 而不是 {@link ScopedJdbc}：后者是只读入口（拒绝非 SELECT）。
 * 写语句一律显式带 {@code tenant_id}（取自 {@link TenantContext}），不依赖 SQL 拼接。</p>
 */
@Component
public class RequirementFollowQuery {

    private final ScopedJdbc jdbc;
    private final JdbcTemplate template;

    public RequirementFollowQuery(ScopedJdbc jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /** 一个需求的热度：人数 + 当前视角是否已跟单（系统作用域 userId=0，恒 false）。 */
    public record Stats(long followCount, boolean followedByMe) {

        public static final Stats EMPTY = new Stats(0L, false);
    }

    /** 跟单人（跟单详情页/合并通知用；name 取自 sys_user，org 取自 sys_org）。 */
    public record Follower(long userId, String userName, Long orgId, String orgName,
                           String note, LocalDateTime createdAt) {
    }

    /** 「哪些组织在等」：按 org 分组的人数，前端热度卡的第二行。 */
    public record OrgCount(Long orgId, String orgName, long count) {
    }

    public record FollowerPage(List<Follower> items, long total, int page, int size,
                               boolean mine, List<OrgCount> orgs) {
    }

    // ------------------------------------------------------------------ reads

    /**
     * 批量热度：一次分组查询覆盖整页列表（避免 N+1）。
     *
     * <p>{@code MAX(CASE WHEN user_id = :viewer ...)} 算 {@code followedByMe}：调用者视角来自
     * {@link TenantContext}，不是请求参数——前端不能借参数冒充别人。</p>
     */
    /**
     * 跟单可见性与列表同源：拿 requirement 的 SQL scope 再判一次。
     *
     * <p>requireVisible 的 Java 侧只拦「有厂商列且不是自己厂商」的需求；厂商账号碰到
     * owner_vendor_id/assignee_vendor_id 都为空的需求会被判可见，而列表 SQL scope 的 VENDOR 层
     * 要求 own/assignee = 本厂商，于是「列表看不到、跟单却能写」——门禁 P2-9 的越权行正是这条缝。
     * 这里复用 scope 标记，让写入权限与读取权限用同一个谓词。</p>
     */
    public boolean visible(long requirementId) {
        return jdbc.count("SELECT COUNT(*) FROM requirement r WHERE r.id = :id AND /*scope:r*/",
                Map.of("id", requirementId)) > 0;
    }

    public Map<Long, Stats> stats(List<Long> requirementIds) {
        if (requirementIds == null || requirementIds.isEmpty()) {
            return Map.of();
        }
        long viewer = TenantContext.require().userId();
        List<Map<String, Object>> rows = jdbc.list(
                "SELECT f.requirement_id AS requirement_id, COUNT(*) AS follow_count,"
                        + " MAX(CASE WHEN f.user_id = :viewer THEN 1 ELSE 0 END) AS mine"
                        + " FROM requirement_follow f"
                        + " WHERE f.requirement_id IN (:ids) AND /*scope:f*/"
                        + " GROUP BY f.requirement_id",
                Map.of("ids", requirementIds, "viewer", viewer));
        Map<Long, Stats> stats = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long id = longOf(row.get("requirement_id"));
            long count = row.get("follow_count") == null ? 0L : ((Number) row.get("follow_count")).longValue();
            boolean mine = row.get("mine") != null && ((Number) row.get("mine")).intValue() == 1;
            if (id != null) {
                stats.put(id, new Stats(count, mine));
            }
        }
        return stats;
    }

    public Stats stats(long requirementId) {
        return stats(List.of(requirementId)).getOrDefault(requirementId, Stats.EMPTY);
    }

    public long count(long requirementId) {
        return jdbc.count("SELECT COUNT(*) FROM requirement_follow f"
                + " WHERE f.requirement_id = :id AND /*scope:f*/", Map.of("id", requirementId));
    }

    public boolean exists(long requirementId, long userId) {
        return jdbc.count("SELECT COUNT(*) FROM requirement_follow f"
                        + " WHERE f.requirement_id = :id AND f.user_id = :userId AND /*scope:f*/",
                Map.of("id", requirementId, "userId", userId)) > 0;
    }

    /** 跟单明细分页 + 组织分布；{@code mine} 是当前视角是否已跟单。 */
    /** page 从 1 开始（与 HTTP 参数同口径，响应原样回显）；offset 在这里换算，调用方不再 -1。 */
    public FollowerPage page(long requirementId, int page, int size) {
        long viewer = TenantContext.require().userId();
        Map<String, Object> params = Map.of("id", requirementId);
        long total = jdbc.count("SELECT COUNT(*) FROM requirement_follow f"
                + " WHERE f.requirement_id = :id AND /*scope:f*/", params);
        List<Map<String, Object>> rows = jdbc.list(
                "SELECT f.user_id AS user_id, u.name AS user_name, f.org_id AS org_id,"
                        + " o.name AS org_name, f.note AS note, f.created_at AS created_at"
                        + " FROM requirement_follow f"
                        + " LEFT JOIN sys_user u ON u.id = f.user_id AND u.tenant_id = f.tenant_id"
                        + " LEFT JOIN sys_org o ON o.id = f.org_id AND o.tenant_id = f.tenant_id"
                        + " WHERE f.requirement_id = :id AND /*scope:f*/"
                        + " ORDER BY f.created_at ASC, f.id ASC"
                        + " LIMIT :limit OFFSET :offset",
                Map.of("id", requirementId, "limit", size, "offset", (long) (page - 1) * size));
        List<Follower> items = rows.stream().map(RequirementFollowQuery::follower).toList();
        List<Map<String, Object>> orgRows = jdbc.list(
                "SELECT f.org_id AS org_id, o.name AS org_name, COUNT(*) AS cnt"
                        + " FROM requirement_follow f"
                        + " LEFT JOIN sys_org o ON o.id = f.org_id AND o.tenant_id = f.tenant_id"
                        + " WHERE f.requirement_id = :id AND f.org_id IS NOT NULL AND /*scope:f*/"
                        + " GROUP BY f.org_id, o.name"
                        + " ORDER BY cnt DESC, f.org_id ASC",
                params);
        List<OrgCount> orgs = orgRows.stream()
                .map(row -> new OrgCount(longOf(row.get("org_id")),
                        row.get("org_name") == null ? null : row.get("org_name").toString(),
                        row.get("cnt") == null ? 0L : ((Number) row.get("cnt")).longValue()))
                .toList();
        return new FollowerPage(items, total, page, size, exists(requirementId, viewer), orgs);
    }

    /** 合并通知用：去重后的跟单人 id（不含 0/空）。 */
    public List<Long> userIds(long requirementId) {
        return jdbc.list("SELECT DISTINCT f.user_id AS user_id FROM requirement_follow f"
                        + " WHERE f.requirement_id = :id AND /*scope:f*/", Map.of("id", requirementId))
                .stream()
                .map(row -> longOf(row.get("user_id")))
                .filter(id -> id != null && id > 0)
                .toList();
    }

    // ----------------------------------------------------------------- writes

    /**
     * 幂等插入：唯一键冲突返回 0（说明「已跟单」），新增返回 1。
     *
     * <p>{@code INSERT IGNORE} 而不是「先查再插」：并发下先查再插会两边都读到 0，随后一边撞唯一键
     * 抛 500；让数据库裁决，服务层只把 0 翻译成 200。</p>
     */
    public int insert(long requirementId, long userId, Long orgId, String note) {
        long tenantId = TenantContext.require().tenantId();
        return template.update(
                "INSERT IGNORE INTO requirement_follow"
                        + " (tenant_id, requirement_id, user_id, org_id, note, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, NOW(3))",
                tenantId, requirementId, userId, orgId, note);
    }

    /** 取消跟单：返回删除行数（0 = 本来就没跟，调用方给 404）。 */
    public int delete(long requirementId, long userId) {
        long tenantId = TenantContext.require().tenantId();
        return template.update("DELETE FROM requirement_follow"
                + " WHERE tenant_id = ? AND requirement_id = ? AND user_id = ?",
                tenantId, requirementId, userId);
    }

    /**
     * 合并时把源需求的跟单人搬到目标：唯一键 (tenant, requirement, user) 去重，冲突行丢弃。
     *
     * @return 实际搬过去的行数（{@code movedFollows}）；源行随后整批删除
     */
    public int moveAll(long sourceId, long targetId) {
        long tenantId = TenantContext.require().tenantId();
        int moved = template.update(
                "INSERT IGNORE INTO requirement_follow"
                        + " (tenant_id, requirement_id, user_id, org_id, note, created_at)"
                        + " SELECT tenant_id, ?, user_id, org_id, note, created_at"
                        + " FROM requirement_follow WHERE tenant_id = ? AND requirement_id = ?",
                targetId, tenantId, sourceId);
        template.update("DELETE FROM requirement_follow WHERE tenant_id = ? AND requirement_id = ?",
                tenantId, sourceId);
        return moved;
    }

    // ------------------------------------------------------- helper lookups

    /** 跟单人所属组织：用于把「等的人」按组织聚合（契约 §3.2 的 orgs）。 */
    public Long orgOf(long userId) {
        List<Map<String, Object>> rows = template.queryForList(
                "SELECT org_id FROM sys_user WHERE tenant_id = ? AND id = ?",
                TenantContext.require().tenantId(), userId);
        return rows.isEmpty() ? null : longOf(rows.get(0).get("org_id"));
    }

    /** 跟单人姓名：通知文案里要写「谁也需要」，避免通知只写 id。 */
    public String nameOf(long userId) {
        List<Map<String, Object>> rows = template.queryForList(
                "SELECT name FROM sys_user WHERE tenant_id = ? AND id = ?",
                TenantContext.require().tenantId(), userId);
        return rows.isEmpty() || rows.get(0).get("name") == null
                ? null : rows.get(0).get("name").toString();
    }

    /**
     * 今天是否已给某人发过该需求的跟单通知。
     *
     * <p>通知表没有 idempotency key 列（不能改契约外的表），所以用「同人 + 同需求 + 同一天 + 同类型」
     * 反查实现 {@code follow:{reqId}:{date}} 的幂等语义。选择按收件人而不是按需求全局去重，是因为
     * 契约同时要求通知 reporter 与 owner：全局去重会让第二个人永远收不到。</p>
     */
    public boolean notifiedToday(long requirementId, long userId) {
        Long count = template.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE tenant_id = ? AND user_id = ?"
                        + " AND type = 'REQUIREMENT_FOLLOWED' AND biz_id = ?"
                        + " AND created_at >= CURDATE()",
                Long.class, TenantContext.require().tenantId(), userId, requirementId);
        return count != null && count > 0;
    }

    // ----------------------------------------------------------------- mapping

    private static Follower follower(Map<String, Object> row) {
        return new Follower(
                longOf(row.get("user_id")),
                row.get("user_name") == null ? null : row.get("user_name").toString(),
                longOf(row.get("org_id")),
                row.get("org_name") == null ? null : row.get("org_name").toString(),
                row.get("note") == null ? null : row.get("note").toString(),
                row.get("created_at") instanceof LocalDateTime time ? time : null);
    }

    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }
}
