package com.example.req.collab.gantt;

import java.time.LocalDate;
import java.util.List;

/**
 * 甘特三视图的对外契约：查询参数、行、告警、改期命令与结果。
 *
 * <p>DTO 集中在这里，controller / service / query 不再各拼一份；前端只渲染 {@link Row#warnings()}，
 * 告警的计算全在 {@link GanttWarnings}（AC-3：原因由服务端给，前端不推断）。</p>
 */
public final class GanttViews {

    public static final String VIEW_RELEASE = "RELEASE";
    public static final String VIEW_SPRINT = "SPRINT";
    public static final String VIEW_REQUIREMENT = "REQUIREMENT";
    public static final List<String> VIEWS = List.of(VIEW_RELEASE, VIEW_SPRINT, VIEW_REQUIREMENT);

    public static final String ZOOM_DAY = "DAY";
    public static final String ZOOM_WEEK = "WEEK";
    public static final String ZOOM_MONTH = "MONTH";
    public static final List<String> ZOOMS = List.of(ZOOM_DAY, ZOOM_WEEK, ZOOM_MONTH);

    public static final String TYPE_RELEASE = "RELEASE";
    public static final String TYPE_SPRINT = "SPRINT";
    public static final String TYPE_REQUIREMENT = "REQUIREMENT";
    public static final String TYPE_TASK = "TASK";
    public static final List<String> TYPES = List.of(TYPE_RELEASE, TYPE_SPRINT, TYPE_REQUIREMENT, TYPE_TASK);

    /** 三码来自 PRD AC-3；越窗提示是改期后的信息性告警，不阻断保存。 */
    public static final String WARN_DUE_SOON = "DUE_SOON";
    public static final String WARN_OVERDUE = "OVERDUE";
    public static final String WARN_OVERLOAD = "OVERLOAD";
    public static final String WARN_OUT_OF_PARENT_WINDOW = "OUT_OF_PARENT_WINDOW";
    public static final String WARN_TRUNCATED = "TRUNCATED";

    public static final String LEVEL_RED = "RED";
    public static final String LEVEL_ORANGE = "ORANGE";

    public static final int MAX_ROWS = 300;

    private GanttViews() {
    }

    /**
     * 一次读取的查询条件。{@code expanded} 形如 {@code REQ:12,REL:3}；为空表示按当前视图展开全部
     * （保证默认请求就能看到「父需求→子需求」「需求→任务」的缩进），非空时只展开列出的节点。
     */
    public record Query(String view,
                        String zoom,
                        Long domainId,
                        Long vendorId,
                        Long releaseId,
                        Long sprintId,
                        LocalDate from,
                        LocalDate to,
                        String expanded,
                        Integer limit) {
    }

    /**
     * 看板：{@code total} 是可见行总数（>300 时 {@code truncated=true} 且只回 level≤1 的行）；
     * {@code capacityTenths} 是当周容量（人天×10），前端只按它画负载，不自己定阈值。
     */
    public record Board(String view,
                        String zoom,
                        LocalDate rangeStart,
                        LocalDate rangeEnd,
                        List<Row> rows,
                        int total,
                        boolean truncated,
                        int capacityTenths,
                        List<Warning> boardWarnings) {
        public Board {
            rows = rows == null ? List.of() : List.copyOf(rows);
            boardWarnings = boardWarnings == null ? List.of() : List.copyOf(boardWarnings);
        }
    }

    /**
     * 一行。{@code version} 是该行 {@code updated_at} 的毫秒时间戳字符串，改期时原样回传做乐观锁；
     * 不读表里的 {@code version} 列，因为 release / sprint 没有该列。
     */
    public record Row(String type,
                      long id,
                      Long parentId,
                      int level,
                      String code,
                      String title,
                      Long ownerId,
                      String ownerName,
                      LocalDate planStart,
                      LocalDate planEnd,
                      LocalDate actualStart,
                      LocalDate actualEnd,
                      String status,
                      boolean done,
                      boolean milestone,
                      LocalDate plannedDate,
                      String version,
                      List<Warning> warnings) {
        public Row {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** {@code items} 只用于超载告警：列出该周超出容量的条目（最多 5 条），供 tooltip 说明「涉及哪几条」。 */
    public record Warning(String code,
                          String level,
                          String message,
                          Integer days,
                          Double overDays,
                          List<String> items) {
        public Warning {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record RescheduleCmd(LocalDate planStart,
                                LocalDate planEnd,
                                String version,
                                String confirmToken) {
    }

    public record RescheduleResult(long id,
                                   String type,
                                   LocalDate planStart,
                                   LocalDate planEnd,
                                   long auditId,
                                   String version) {
    }
}
