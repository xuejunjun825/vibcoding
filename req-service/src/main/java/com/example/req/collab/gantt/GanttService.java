package com.example.req.collab.gantt;

import com.example.platform.common.error.ApiException;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 甘特看板的读路径：三视图取行 → 组装 → 挂告警。只读，不写库、不调外部集成。
 *
 * <p>容量（周人天）从配置读：{@code collab.gantt.capacity.week} 默认 5.0，
 * 可按域覆盖 {@code collab.gantt.capacity.by-domain.<domainId>}；没有 sys_param 表。</p>
 */
@Service
public class GanttService {

    private static final int DEFAULT_RANGE_DAYS = 45;

    private final GanttQuery query;
    private final Environment environment;

    public GanttService(GanttQuery query, Environment environment) {
        this.query = query;
        this.environment = environment;
    }

    public GanttViews.Board board(GanttViews.Query request) {
        GanttViews.Query query = normalize(request);
        GanttQuery.Snapshot snapshot = this.query.load(query);

        List<GanttViews.Row> rows = new ArrayList<>(snapshot.items().size());
        for (GanttQuery.Item item : snapshot.items()) {
            rows.add(toRow(item, List.of()));
        }

        int capacityTenths = capacityTenths(query.domainId());
        Map<String, List<GanttViews.Warning>> warnings = GanttWarnings.compute(rows,
                workloads(query.view(), snapshot.items()),
                snapshot.parentWindows(),
                LocalDate.now(),
                capacityTenths);

        List<GanttViews.Row> withWarnings = new ArrayList<>(rows.size());
        for (GanttViews.Row row : rows) {
            withWarnings.add(toRow(row, warnings.getOrDefault(GanttQuery.key(row.type(), row.id()), List.of())));
        }

        LocalDate[] range = range(query, withWarnings);
        List<GanttViews.Warning> boardWarnings = snapshot.truncated()
                ? List.of(new GanttViews.Warning(GanttViews.WARN_TRUNCATED, GanttViews.LEVEL_ORANGE,
                "行数超过 " + GanttViews.MAX_ROWS + " 行上限，已截断；缩小范围或折叠后再看",
                null, null, List.of()))
                : List.of();
        return new GanttViews.Board(query.view(), query.zoom(), range[0], range[1], withWarnings,
                snapshot.total(), snapshot.truncated(), capacityTenths, boardWarnings);
    }

    // ------------------------------------------------------------------ normalise

    private GanttViews.Query normalize(GanttViews.Query request) {
        GanttViews.Query source = request == null
                ? new GanttViews.Query(null, null, null, null, null, null, null, null, null, null)
                : request;
        String view = source.view() == null || source.view().isBlank()
                ? GanttViews.VIEW_RELEASE
                : source.view().trim().toUpperCase();
        if (!GanttViews.VIEWS.contains(view)) {
            throw new ApiException(400, "GANTT_INVALID_VIEW", "不支持的甘特视图：" + source.view(), Map.of());
        }
        String zoom = source.zoom() == null || source.zoom().isBlank()
                ? GanttViews.ZOOM_MONTH
                : source.zoom().trim().toUpperCase();
        if (!GanttViews.ZOOMS.contains(zoom)) {
            throw new ApiException(400, "GANTT_INVALID_ZOOM", "不支持的甘特缩放：" + source.zoom(), Map.of());
        }
        LocalDate from = source.from();
        LocalDate to = source.to();
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(400, "GANTT_INVALID_RANGE", "起始日期不能晚于结束日期",
                    Map.of("from", from.toString(), "to", to.toString()));
        }
        return new GanttViews.Query(view, zoom, source.domainId(), source.vendorId(), source.releaseId(),
                source.sprintId(), from, to, source.expanded(), source.limit());
    }

    // ------------------------------------------------------------------ assembly

    private GanttViews.Row toRow(GanttQuery.Item item, List<GanttViews.Warning> warnings) {
        return new GanttViews.Row(item.type(), item.id(), item.parentId(), item.level(), item.code(),
                item.title(), item.ownerId(), item.ownerName(), item.planStart(), item.planEnd(),
                item.actualStart(), item.actualEnd(), item.status(), item.done(), item.milestone(),
                item.plannedDate(), item.version(), warnings);
    }

    private GanttViews.Row toRow(GanttViews.Row row, List<GanttViews.Warning> warnings) {
        return new GanttViews.Row(row.type(), row.id(), row.parentId(), row.level(), row.code(), row.title(),
                row.ownerId(), row.ownerName(), row.planStart(), row.planEnd(), row.actualStart(),
                row.actualEnd(), row.status(), row.done(), row.milestone(), row.plannedDate(),
                row.version(), warnings);
    }

    /**
     * 超载只在需求视图算：版本/迭代行没有负责人，需求视图才有"同一负责人同一周"的口径。
     * 需求有任务时按任务工时算，没有任务时才回到需求的 estimate_days，避免同一份工作量算两遍。
     */
    private List<GanttWarnings.Workload> workloads(String view, List<GanttQuery.Item> items) {
        if (!GanttViews.VIEW_REQUIREMENT.equals(view) || items.isEmpty()) {
            return List.of();
        }
        Set<Long> requirementsWithTasks = new HashSet<>();
        for (GanttQuery.Item item : items) {
            if (GanttViews.TYPE_TASK.equals(item.type()) && item.parentId() != null) {
                requirementsWithTasks.add(item.parentId());
            }
        }
        List<GanttWarnings.Workload> workloads = new ArrayList<>();
        for (GanttQuery.Item item : items) {
            if (item.ownerId() == null || item.planStart() == null || item.planEnd() == null) {
                continue;
            }
            if (GanttViews.TYPE_TASK.equals(item.type())) {
                if (item.estimateHours() == null || item.estimateHours() <= 0) {
                    continue;
                }
                int tenths = (int) Math.round(item.estimateHours() / 8.0 * 10.0);
                workloads.addAll(GanttWarnings.spread(item.ownerId(), item.type(), item.id(),
                        label(item), item.planStart(), item.planEnd(), tenths));
            } else if (GanttViews.TYPE_REQUIREMENT.equals(item.type())
                    && !requirementsWithTasks.contains(item.id())
                    && item.estimateDays() != null && item.estimateDays() > 0) {
                workloads.addAll(GanttWarnings.spread(item.ownerId(), item.type(), item.id(),
                        label(item), item.planStart(), item.planEnd(),
                        (int) Math.round(item.estimateDays() * 10.0)));
            }
        }
        return workloads;
    }

    private static String label(GanttQuery.Item item) {
        String text = item.code() == null ? item.title() : item.code() + " " + item.title();
        if (text == null) {
            return item.type() + "-" + item.id();
        }
        return text.length() > 40 ? text.substring(0, 40) : text;
    }

    // ------------------------------------------------------------------ capacity & range

    private int capacityTenths(Long domainId) {
        Double perDomain = null;
        if (domainId != null) {
            perDomain = environment.getProperty("collab.gantt.capacity.by-domain." + domainId, Double.class);
        }
        Double week = environment.getProperty("collab.gantt.capacity.week", Double.class);
        double days = perDomain != null ? perDomain : (week == null ? 5.0 : week);
        int tenths = (int) Math.round(days * 10.0);
        return tenths <= 0 ? GanttWarnings.DEFAULT_CAPACITY_TENTHS : tenths;
    }

    private LocalDate[] range(GanttViews.Query query, List<GanttViews.Row> rows) {
        LocalDate start = query.from();
        LocalDate end = query.to();
        if (start != null && end != null) {
            return new LocalDate[]{start, end};
        }
        LocalDate[] derived = derivedRange(query.view(), rows);
        return new LocalDate[]{start == null ? derived[0] : start, end == null ? derived[1] : end};
    }

    private LocalDate[] derivedRange(String view, List<GanttViews.Row> rows) {
        LocalDate today = LocalDate.now();
        if (GanttViews.VIEW_REQUIREMENT.equals(view)) {
            return new LocalDate[]{today.minusDays(DEFAULT_RANGE_DAYS), today.plusDays(DEFAULT_RANGE_DAYS)};
        }
        LocalDate min = null;
        LocalDate max = null;
        for (GanttViews.Row row : rows) {
            if (row.planStart() != null && (min == null || row.planStart().isBefore(min))) {
                min = row.planStart();
            }
            if (row.planEnd() != null && (max == null || row.planEnd().isAfter(max))) {
                max = row.planEnd();
            }
        }
        if (min == null || max == null) {
            return new LocalDate[]{today.minusDays(DEFAULT_RANGE_DAYS), today.plusDays(DEFAULT_RANGE_DAYS)};
        }
        return new LocalDate[]{min, max};
    }
}
