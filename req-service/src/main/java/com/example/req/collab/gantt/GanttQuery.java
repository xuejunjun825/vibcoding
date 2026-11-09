package com.example.req.collab.gantt;

import com.example.platform.common.scope.ScopedJdbc;
import com.example.req.requirement.RequirementStateMachine;
import com.example.req.requirement.Rows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 甘特三视图的读取与行装载：视图各一条 SELECT，行映射与折叠/截断都在这里完成。
 *
 * <p>只读：{@link #load} 不写库（AC 要求看板打开零写、零外呼）。唯一写语句是改期用的
 * {@link #updatePlan}，由 {@link GanttRescheduleService} 调用，保证写入口唯一。</p>
 *
 * <p>行映射不复制状态字面量：需求是否完成走 {@link RequirementStateMachine} 的终态判定，
 * release / sprint / task 的状态集合在本类的 {@code done*} 方法里集中声明。</p>
 */
@Component
public class GanttQuery {

    /** 一次视图最多 300 行（AC-1）。 */
    private static final int TASK_ROW_CAP = 1000;
    private static final int MAX_TREE_DEPTH = 4;

    private static final String RELEASE_CORE = """
            SELECT r.id AS id,
                   r.code AS code,
                   r.name AS title,
                   r.status AS status,
                   r.start_date AS plan_start,
                   r.end_date AS plan_end,
                   r.actual_start_date AS actual_start,
                   r.actual_end_date AS actual_end,
                   r.planned_release_date AS planned_release_date,
                   (SELECT MIN(m.planned_date) FROM milestone m
                     WHERE m.release_id = r.id AND m.tenant_id = r.tenant_id) AS milestone_date,
                   r.updated_at AS updated_at
              FROM `release` r
             WHERE /*scope:r*/
               AND (:domainId IS NULL OR r.domain_id = :domainId)
               AND (:releaseId IS NULL OR r.id = :releaseId)
               AND (:from IS NULL OR r.end_date >= :from)
               AND (:to IS NULL OR r.start_date <= :to)
            """;

    private static final String SPRINT_CORE = """
            SELECT s.id AS id,
                   s.release_id AS release_id,
                   s.name AS title,
                   s.status AS status,
                   s.start_date AS plan_start,
                   s.end_date AS plan_end,
                   s.actual_start_date AS actual_start,
                   s.actual_end_date AS actual_end,
                   s.updated_at AS updated_at
              FROM sprint s
             WHERE /*scope:s*/
               AND (:sprintId IS NULL OR s.id = :sprintId)
               AND (:releaseId IS NULL OR s.release_id = :releaseId)
               AND (:domainId IS NULL OR EXISTS (
                        SELECT 1 FROM `release` r
                         WHERE r.id = s.release_id
                           AND r.tenant_id = s.tenant_id
                           AND r.domain_id = :domainId))
               AND (:from IS NULL OR s.end_date >= :from)
               AND (:to IS NULL OR s.start_date <= :to)
            """;

    private static final String REQUIREMENT_CORE = """
            SELECT q.id AS id,
                   q.code AS code,
                   q.parent_id AS parent_id,
                   q.release_id AS release_id,
                   q.title AS title,
                   q.owner_user_id AS owner_id,
                   u.name AS owner_name,
                   q.status AS status,
                   q.estimate_days AS estimate_days,
                   q.plan_start_date AS plan_start,
                   q.plan_end_date AS plan_end,
                   q.actual_start_date AS actual_start,
                   q.actual_end_date AS actual_end,
                   q.updated_at AS updated_at
              FROM requirement q
              LEFT JOIN sys_user u ON u.id = q.owner_user_id AND u.tenant_id = q.tenant_id
             WHERE /*scope:q*/
               AND (:domainId IS NULL OR q.domain_id = :domainId)
               AND (:releaseId IS NULL OR q.release_id = :releaseId)
               AND (:vendorId IS NULL OR q.owner_vendor_id = :vendorId OR q.assignee_vendor_id = :vendorId)
               AND (:from IS NULL OR q.plan_end_date >= :from)
               AND (:to IS NULL OR q.plan_start_date <= :to)
            """;

    private static final String TASK_CORE = """
            SELECT t.id AS id,
                   t.requirement_id AS parent_id,
                   t.title AS title,
                   t.assignee_id AS owner_id,
                   u.name AS owner_name,
                   t.status AS status,
                   t.estimate_hours AS estimate_hours,
                   t.start_date AS plan_start,
                   t.due_date AS plan_end,
                   t.actual_start_date AS actual_start,
                   t.actual_end_date AS actual_end,
                   t.updated_at AS updated_at
              FROM task t
              LEFT JOIN sys_user u ON u.id = t.assignee_id AND u.tenant_id = t.tenant_id
             WHERE /*scope:t*/
               AND t.requirement_id IN (:reqIds)
            """;

    private final ScopedJdbc scoped;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final RequirementStateMachine requirementStates;

    public GanttQuery(ScopedJdbc scoped,
                      JdbcTemplate jdbc,
                      NamedParameterJdbcTemplate namedJdbc,
                      RequirementStateMachine requirementStates) {
        this.scoped = scoped;
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.requirementStates = requirementStates;
    }

    // ------------------------------------------------------------------ model

    /** 内部行：比对外 {@link GanttViews.Row} 多带工作量字段，供 {@link GanttWarnings} 汇总超载。 */
    public record Item(String type, long id, Long parentId, int level, String code, String title,
                       Long ownerId, String ownerName,
                       LocalDate planStart, LocalDate planEnd,
                       LocalDate actualStart, LocalDate actualEnd,
                       String status, boolean done, boolean milestone, LocalDate plannedDate,
                       String version, Double estimateDays, Double estimateHours, Long releaseId) {

        public Item atLevel(int newLevel) {
            return new Item(type, id, parentId, newLevel, code, title, ownerId, ownerName, planStart,
                    planEnd, actualStart, actualEnd, status, done, milestone, plannedDate, version,
                    estimateDays, estimateHours, releaseId);
        }
    }

    /** 父级允许窗口；两端都可为空（父级自身没有计划期时视为不限制）。 */
    public record Window(LocalDate start, LocalDate end) {

        public boolean bounded() {
            return start != null || end != null;
        }

        public boolean contains(LocalDate s, LocalDate e) {
            if (s == null || e == null) {
                return true;
            }
            return (start == null || !s.isBefore(start)) && (end == null || !e.isAfter(end));
        }

        public int violatingDays(LocalDate s, LocalDate e) {
            int days = 0;
            if (start != null && s != null && s.isBefore(start)) {
                days += (int) ChronoUnit.DAYS.between(s, start);
            }
            if (end != null && e != null && e.isAfter(end)) {
                days += (int) ChronoUnit.DAYS.between(end, e);
            }
            return days;
        }
    }

    public record Snapshot(List<Item> items, int total, boolean truncated, Map<String, Window> parentWindows) {
        public Snapshot {
            items = List.copyOf(items);
            parentWindows = Map.copyOf(parentWindows);
        }
    }

    // ------------------------------------------------------------------ reads

    public Snapshot load(GanttViews.Query q) {
        int limit = clampLimit(q.limit());
        return switch (q.view()) {
            case GanttViews.VIEW_RELEASE -> loadSimple(GanttViews.TYPE_RELEASE, RELEASE_CORE,
                    " ORDER BY r.start_date ASC, r.id ASC LIMIT :limit", simpleParams(q, limit));
            case GanttViews.VIEW_SPRINT -> loadSimple(GanttViews.TYPE_SPRINT, SPRINT_CORE,
                    " ORDER BY s.start_date ASC, s.id ASC LIMIT :limit", simpleParams(q, limit));
            default -> loadRequirementTree(q, limit);
        };
    }

    /** 单行读取（改期写入口用）：返回 null 表示当前数据范围不可见或不存在。 */
    public Item loadOne(String type, long id) {
        String core = switch (type) {
            case GanttViews.TYPE_RELEASE -> RELEASE_CORE;
            case GanttViews.TYPE_SPRINT -> SPRINT_CORE;
            case GanttViews.TYPE_REQUIREMENT -> REQUIREMENT_CORE;
            case GanttViews.TYPE_TASK -> TASK_CORE.replace("t.requirement_id IN (:reqIds)", "t.id = :id");
            default -> null;
        };
        if (core == null) {
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        // 单行读复用列表 SQL 的 SELECT/WHERE 骨架：过滤条件全部置空，只留主键——
        // 这样列别名与 scope 谓词不会出现第二份定义。
        params.put("domainId", null);
        params.put("releaseId", null);
        params.put("vendorId", null);
        params.put("from", null);
        params.put("to", null);
        params.put("sprintId", null);
        if (GanttViews.TYPE_TASK.equals(type)) {
            // task 的单行读按主键：TASK_CORE 的需求集合谓词已被替换成 t.id = :id。
            params.put("reqIds", List.of(-1L));
        } else {
            core = core + " AND " + idColumn(type) + " = :id";
        }
        params.put("id", id);
        Map<String, Object> row = scoped.one(core, params);
        return row == null ? null : toItem(type, row);
    }

    /**
     * 判断"这条记录在租户内是否存在"——仅用于区分 403（越权改别人的域）与 404（真的不存在）。
     * 故意不走 ScopedJdbc：就是要绕过域/厂商谓词，但显式带上 tenant_id，租户之外仍然不可见。
     */
    public boolean existsInTenant(String type, long id) {
        String table = switch (type) {
            case GanttViews.TYPE_RELEASE -> "`release`";
            case GanttViews.TYPE_SPRINT -> "sprint";
            case GanttViews.TYPE_REQUIREMENT -> "requirement";
            case GanttViews.TYPE_TASK -> "task";
            default -> null;
        };
        if (table == null) {
            return false;
        }
        Long tenantId = com.example.platform.common.tenant.TenantContext.require().tenantId();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ? AND tenant_id = ?",
                Integer.class, id, tenantId);
        return count != null && count > 0;
    }

    /** 改期时的父级窗口；无父级/父级没有计划期时返回 null（不限制）。 */
    public Window parentWindow(String type, Item item) {
        return switch (type) {
            case GanttViews.TYPE_RELEASE -> null;
            case GanttViews.TYPE_SPRINT -> item == null ? null : releaseWindow(item.releaseId());
            case GanttViews.TYPE_REQUIREMENT -> item == null ? null : releaseWindow(item.releaseId());
            case GanttViews.TYPE_TASK -> taskParentWindow(item);
            default -> null;
        };
    }

    private Window taskParentWindow(Item task) {
        if (task == null || task.parentId() == null) {
            return null;
        }
        Map<String, Object> row = scoped.one("""
                SELECT q.plan_start_date AS plan_start, q.plan_end_date AS plan_end
                  FROM requirement q
                 WHERE /*scope:q*/ AND q.id = :id
                """, Map.of("id", task.parentId()));
        if (row == null) {
            return null;
        }
        return bound(Rows.toDate(Rows.object(row, "plan_start")), Rows.toDate(Rows.object(row, "plan_end")));
    }

    private Window releaseWindow(Long releaseId) {
        if (releaseId == null) {
            return null;
        }
        Map<String, Object> row = scoped.one("""
                SELECT r.start_date AS plan_start, r.end_date AS plan_end
                  FROM `release` r
                 WHERE /*scope:r*/ AND r.id = :id
                """, Map.of("id", releaseId));
        return row == null ? null
                : bound(Rows.toDate(Rows.object(row, "plan_start")), Rows.toDate(Rows.object(row, "plan_end")));
    }

    private static Window bound(LocalDate start, LocalDate end) {
        if (start == null && end == null) {
            return null;
        }
        return new Window(start, end);
    }

    private Snapshot loadSimple(String type, String core, String tail, Map<String, Object> params) {
        long total = scoped.count("SELECT COUNT(*) FROM (" + core + "\n) gantt_count", params);
        List<Map<String, Object>> rows = scoped.list(core + tail, params);
        List<Item> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(toItem(type, row));
        }
        Map<String, Window> windows = Map.of();
        if (GanttViews.TYPE_SPRINT.equals(type) && !items.isEmpty()) {
            Set<Long> releaseIds = new LinkedHashSet<>();
            for (Item item : items) {
                if (item.releaseId() != null) {
                    releaseIds.add(item.releaseId());
                }
            }
            Map<Long, Window> releases = releaseWindows(releaseIds);
            Map<String, Window> built = new LinkedHashMap<>();
            for (Item item : items) {
                Window window = releases.get(item.releaseId());
                if (window != null) {
                    built.put(key(type, item.id()), window);
                }
            }
            windows = built;
        }
        return new Snapshot(items, clampTotal(total), total > items.size() || items.size() > GanttViews.MAX_ROWS, windows);
    }

    private Snapshot loadRequirementTree(GanttViews.Query q, int limit) {
        Map<String, Object> params = requirementParams(q);
        long total = scoped.count("SELECT COUNT(*) FROM (" + REQUIREMENT_CORE + "\n) gantt_count", params);
        params = new HashMap<>(params);
        params.put("limit", limit);
        List<Map<String, Object>> rows = scoped.list(REQUIREMENT_CORE
                + " ORDER BY q.plan_start_date IS NULL ASC, q.plan_start_date ASC, q.id ASC LIMIT :limit", params);

        List<Item> base = new ArrayList<>(rows.size());
        Map<Long, Item> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Item item = toItem(GanttViews.TYPE_REQUIREMENT, row);
            base.add(item);
            byId.put(item.id(), item);
        }
        Set<Long> visibleIds = new LinkedHashSet<>(byId.keySet());
        Map<Long, List<Item>> children = new LinkedHashMap<>();
        List<Item> roots = new ArrayList<>();
        for (Item item : base) {
            if (item.parentId() == null || !visibleIds.contains(item.parentId())) {
                roots.add(item);
            } else {
                children.computeIfAbsent(item.parentId(), k -> new ArrayList<>()).add(item);
            }
        }
        children.values().forEach(list -> list.sort(ROW_ORDER));

        Set<Long> expanded = parseExpanded(q.expanded());
        Set<Long> openedRequirements = new LinkedHashSet<>();
        List<Item> ordered = new ArrayList<>();
        for (Item root : roots) {
            walkRequirement(root, 0, expanded, children, openedRequirements, ordered);
        }

        Map<Long, List<Item>> tasks = loadTasks(openedRequirements);
        if (!tasks.isEmpty()) {
            List<Item> withTasks = new ArrayList<>(ordered.size() + 32);
            for (Item item : ordered) {
                withTasks.add(item);
                List<Item> ofRequirement = tasks.get(item.id());
                if (ofRequirement == null) {
                    continue;
                }
                for (Item task : ofRequirement) {
                    withTasks.add(task.atLevel(item.level() + 1));
                }
            }
            ordered = withTasks;
        }

        // AC-16：超过 300 行不再往下截断半棵树，而是只保留 level<=1 的骨架（父需求 + 子需求），
        // 让"被截断"这件事本身可读；total 仍是截断前的可见总数。
        boolean truncated = total > base.size() || ordered.size() > GanttViews.MAX_ROWS;
        if (truncated) {
            List<Item> skeleton = new ArrayList<>(Math.min(ordered.size(), GanttViews.MAX_ROWS));
            for (Item item : ordered) {
                if (item.level() <= 1) {
                    skeleton.add(item);
                }
                if (skeleton.size() >= GanttViews.MAX_ROWS) {
                    break;
                }
            }
            ordered = skeleton;
        }
        return new Snapshot(ordered, clampTotal(total), truncated, requirementWindows(ordered));
    }

    private void walkRequirement(Item item, int level, Set<Long> expanded, Map<Long, List<Item>> children,
                                 Set<Long> opened, List<Item> out) {
        Item placed = item.atLevel(level);
        out.add(placed);
        if (out.size() >= GanttViews.MAX_ROWS) {
            return;
        }
        boolean expand = expanded == null || expanded.contains(item.id());
        if (!expand) {
            return;
        }
        opened.add(item.id());
        if (level >= MAX_TREE_DEPTH) {
            return;
        }
        for (Item child : children.getOrDefault(item.id(), List.of())) {
            walkRequirement(child, level + 1, expanded, children, opened, out);
            if (out.size() >= GanttViews.MAX_ROWS) {
                return;
            }
        }
    }

    private Map<Long, List<Item>> loadTasks(Set<Long> requirementIds) {
        if (requirementIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("reqIds", requirementIds);
        List<Map<String, Object>> rows = scoped.list(TASK_CORE
                + " ORDER BY t.start_date IS NULL ASC, t.start_date ASC, t.id ASC LIMIT " + TASK_ROW_CAP, params);
        Map<Long, List<Item>> byRequirement = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Item task = toItem(GanttViews.TYPE_TASK, row);
            byRequirement.computeIfAbsent(task.parentId(), k -> new ArrayList<>()).add(task);
        }
        return byRequirement;
    }

    private Map<String, Window> requirementWindows(List<Item> items) {
        Map<Long, Window> releaseWindows = releaseWindows(releaseIds(items));
        Map<Long, Item> requirements = new LinkedHashMap<>();
        for (Item item : items) {
            if (GanttViews.TYPE_REQUIREMENT.equals(item.type())) {
                requirements.put(item.id(), item);
            }
        }
        Map<String, Window> windows = new LinkedHashMap<>();
        for (Item item : items) {
            if (GanttViews.TYPE_REQUIREMENT.equals(item.type())) {
                Window window = releaseWindows.get(item.releaseId());
                if (window != null) {
                    windows.put(key(item.type(), item.id()), window);
                }
            } else if (GanttViews.TYPE_TASK.equals(item.type())) {
                Item parent = requirements.get(item.parentId());
                if (parent != null) {
                    Window window = bound(parent.planStart(), parent.planEnd());
                    if (window != null) {
                        windows.put(key(item.type(), item.id()), window);
                    }
                }
            }
        }
        return windows;
    }

    private Set<Long> releaseIds(List<Item> items) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Item item : items) {
            if (item.releaseId() != null) {
                ids.add(item.releaseId());
            }
        }
        return ids;
    }

    private Map<Long, Window> releaseWindows(Set<Long> releaseIds) {
        if (releaseIds == null || releaseIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("ids", releaseIds);
        List<Map<String, Object>> rows = scoped.list("""
                SELECT r.id AS id, r.start_date AS plan_start, r.end_date AS plan_end
                  FROM `release` r
                 WHERE /*scope:r*/ AND r.id IN (:ids)
                """, params);
        Map<Long, Window> windows = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long id = Rows.toLong(Rows.object(row, "id"));
            LocalDate start = Rows.toDate(Rows.object(row, "plan_start"));
            LocalDate end = Rows.toDate(Rows.object(row, "plan_end"));
            if (id != null && (start != null || end != null)) {
                windows.put(id, new Window(start, end));
            }
        }
        return windows;
    }

    // ------------------------------------------------------------------ writes

    /**
     * 唯一一条甘特写语句：带 {@code updated_at} 乐观锁，返回 0 表示版本已变。
     * 实际期（actual_*）不在这里写：它由状态流转的 {@code ActualPeriodRecorder} 维护（单一来源）。
     */
    public int updatePlan(String type, long id, LocalDate start, LocalDate end, LocalDateTime expected, Long userId) {
        String sql = switch (type) {
            case GanttViews.TYPE_RELEASE -> """
                    UPDATE `release`
                       SET start_date = :start, end_date = :end, updated_at = NOW(3), updated_by = :userId
                     WHERE id = :id AND tenant_id = :tenantId AND updated_at = :expected
                    """;
            case GanttViews.TYPE_SPRINT -> """
                    UPDATE sprint
                       SET start_date = :start, end_date = :end, updated_at = NOW(3), updated_by = :userId
                     WHERE id = :id AND tenant_id = :tenantId AND updated_at = :expected
                    """;
            case GanttViews.TYPE_REQUIREMENT -> """
                    UPDATE requirement
                       SET plan_start_date = :start, plan_end_date = :end, updated_at = NOW(3), updated_by = :userId
                     WHERE id = :id AND tenant_id = :tenantId AND updated_at = :expected
                    """;
            case GanttViews.TYPE_TASK -> """
                    UPDATE task
                       SET start_date = :start, due_date = :end, updated_at = NOW(3)
                     WHERE id = :id AND tenant_id = :tenantId AND updated_at = :expected
                    """;
            default -> null;
        };
        if (sql == null) {
            return 0;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("start", start);
        params.put("end", end);
        params.put("id", id);
        params.put("tenantId", com.example.platform.common.tenant.TenantContext.require().tenantId());
        params.put("expected", expected);
        params.put("userId", userId);
        return namedJdbc.update(sql, params);
    }

    /** 乐观锁冲突时回给前端的当前版本；行已被删除时返回 null。 */
    public String currentVersion(String type, long id) {
        Item item = loadOne(type, id);
        return item == null ? null : item.version();
    }

    // ------------------------------------------------------------------ mapping

    private Item toItem(String type, Map<String, Object> row) {
        long id = Rows.toLong(Rows.object(row, "id"), 0L);
        String status = Rows.str(row, "status");
        String version = versionOf(Rows.object(row, "updated_at"));
        return switch (type) {
            case GanttViews.TYPE_RELEASE -> {
                LocalDate milestoneDate = Rows.toDate(Rows.object(row, "milestone_date"));
                yield new Item(type, id, null, 0,
                        Rows.str(row, "code"), Rows.str(row, "title"), null, null,
                        Rows.toDate(Rows.object(row, "plan_start")), Rows.toDate(Rows.object(row, "plan_end")),
                        Rows.toDate(Rows.object(row, "actual_start")), Rows.toDate(Rows.object(row, "actual_end")),
                        status, doneRelease(status), milestoneDate != null, milestoneDate, version,
                        null, null, null);
            }
            case GanttViews.TYPE_SPRINT -> new Item(type, id, Rows.toLong(Rows.object(row, "release_id")), 0,
                    null, Rows.str(row, "title"), null, null,
                    Rows.toDate(Rows.object(row, "plan_start")), Rows.toDate(Rows.object(row, "plan_end")),
                    Rows.toDate(Rows.object(row, "actual_start")), Rows.toDate(Rows.object(row, "actual_end")),
                    status, doneSprint(status), false, null, version,
                    null, null, Rows.toLong(Rows.object(row, "release_id")));
            case GanttViews.TYPE_REQUIREMENT -> new Item(type, id, Rows.toLong(Rows.object(row, "parent_id")), 0,
                    Rows.str(row, "code"), Rows.str(row, "title"),
                    Rows.toLong(Rows.object(row, "owner_id")), Rows.str(row, "owner_name"),
                    Rows.toDate(Rows.object(row, "plan_start")), Rows.toDate(Rows.object(row, "plan_end")),
                    Rows.toDate(Rows.object(row, "actual_start")), Rows.toDate(Rows.object(row, "actual_end")),
                    status, requirementStates.isTerminalAcrossTypes(status), false, null, version,
                    decimal(Rows.object(row, "estimate_days")), null,
                    Rows.toLong(Rows.object(row, "release_id")));
            case GanttViews.TYPE_TASK -> new Item(type, id, Rows.toLong(Rows.object(row, "parent_id")), 0,
                    null, Rows.str(row, "title"),
                    Rows.toLong(Rows.object(row, "owner_id")), Rows.str(row, "owner_name"),
                    Rows.toDate(Rows.object(row, "plan_start")), Rows.toDate(Rows.object(row, "plan_end")),
                    Rows.toDate(Rows.object(row, "actual_start")), Rows.toDate(Rows.object(row, "actual_end")),
                    status, doneTask(status), false, null, version,
                    null, decimal(Rows.object(row, "estimate_hours")), null);
            default -> throw new IllegalArgumentException("unknown gantt row type: " + type);
        };
    }

    private static Double decimal(Object value) {
        java.math.BigDecimal decimal = Rows.toDecimal(value);
        return decimal == null ? null : decimal.doubleValue();
    }

    private static boolean doneRelease(String status) {
        return "RELEASED".equals(status) || "CLOSED".equals(status);
    }

    private static boolean doneSprint(String status) {
        return "CLOSED".equals(status) || "DONE".equals(status);
    }

    private static boolean doneTask(String status) {
        return "DONE".equals(status) || "CANCELLED".equals(status);
    }

    // ------------------------------------------------------------------ params

    private static final Comparator<Item> ROW_ORDER = Comparator
            .comparing(Item::planStart, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(Item::id);

    private static Map<String, Object> simpleParams(GanttViews.Query q, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("domainId", q.domainId());
        params.put("releaseId", q.releaseId());
        params.put("sprintId", q.sprintId());
        params.put("from", q.from());
        params.put("to", q.to());
        params.put("limit", limit);
        return params;
    }

    private static Map<String, Object> requirementParams(GanttViews.Query q) {
        Map<String, Object> params = new HashMap<>();
        params.put("domainId", q.domainId());
        params.put("releaseId", q.releaseId());
        params.put("vendorId", q.vendorId());
        params.put("from", q.from());
        params.put("to", q.to());
        return params;
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return GanttViews.MAX_ROWS;
        }
        return Math.min(GanttViews.MAX_ROWS, limit);
    }

    private static int clampTotal(long total) {
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static String idColumn(String type) {
        return switch (type) {
            case GanttViews.TYPE_RELEASE -> "r.id";
            case GanttViews.TYPE_SPRINT -> "s.id";
            case GanttViews.TYPE_REQUIREMENT -> "q.id";
            default -> "t.id";
        };
    }

    /** 展开集合解析：支持 {@code REQ:12,REL:3} 与裸 id（裸 id 视为需求）。null 表示展开全部。 */
    private static Set<Long> parseExpanded(String expanded) {
        if (expanded == null) {
            return null;
        }
        String text = expanded.trim();
        if (text.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String token : text.split(",")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            int colon = value.indexOf(':');
            String type = colon < 0 ? GanttViews.TYPE_REQUIREMENT : value.substring(0, colon).trim();
            String idText = colon < 0 ? value : value.substring(colon + 1).trim();
            if (!GanttViews.TYPE_REQUIREMENT.equals(type) && !"REQ".equalsIgnoreCase(type)) {
                continue;
            }
            try {
                ids.add(Long.parseLong(idText));
            } catch (NumberFormatException ignored) {
                // 非法片段忽略：折叠状态是用户侧记忆，坏值不应把整块看板打 400。
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ versions

    /** {@code updated_at} → 毫秒时间戳字符串（对外乐观锁 token）。 */
    public static String versionOf(Object updatedAt) {
        LocalDateTime time = Rows.toDateTime(updatedAt);
        if (time == null) {
            return "0";
        }
        return Long.toString(time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    /** 毫秒时间戳字符串 → 数据库比较用的 {@link LocalDateTime}。 */
    public static LocalDateTime updatedAtOf(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(version.trim())),
                    ZoneId.systemDefault());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static String key(String type, long id) {
        return type + ":" + id;
    }

    /** 供测试与服务的容量换算：人天 → 十分之一人天。 */
    public static int tenths(double days) {
        return (int) Math.round(days * 10.0);
    }
}
