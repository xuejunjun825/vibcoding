package com.example.req.collab.gantt;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 临期 / 逾期 / 超载的唯一计算实现（AC-3：原因由服务端给，前端不推断、不重算）。
 *
 * <p>纯函数：只吃行、工作量和窗口，不碰数据库、不读时钟（{@code today} 由调用方传入），
 * 可以用固定日期把 4.9 vs 5.1 人天、第 3 天 / 第 4 天这类边界钉在单测里。</p>
 */
public final class GanttWarnings {

    /** 距计划完成 ≤3 天算临期（含今天）。 */
    public static final int DUE_SOON_DAYS = 3;
    /** 默认周容量 5.0 人天（配置缺失时的兜底）。 */
    public static final int DEFAULT_CAPACITY_TENTHS = 50;
    /** tooltip 里最多列出的超载条目数。 */
    public static final int MAX_OVERLOAD_ITEMS = 5;

    private static final Comparator<GanttViews.Warning> ORDER =
            Comparator.comparingInt((GanttViews.Warning warning) ->
                            GanttViews.LEVEL_RED.equals(warning.level()) ? 0 : 1)
                    .thenComparing(GanttViews.Warning::code)
                    .thenComparing(GanttViews.Warning::message);

    private GanttWarnings() {
    }

    /** 一条工作量切片：某人在某一天投入的十分之一人天。 */
    public record Workload(long ownerId, LocalDate date, int tenths, String type, long id, String label) {
    }

    /** 蓝图接口：只按行算临期/逾期（超载需要工作量，见 {@link #compute(List, List, Map, LocalDate, int)}）。 */
    public static Map<Long, List<GanttViews.Warning>> compute(List<GanttViews.Row> rows,
                                                             LocalDate today,
                                                             int capacityTenths) {
        Map<String, List<GanttViews.Warning>> byKey =
                compute(rows, List.of(), Map.of(), today, capacityTenths);
        Map<Long, List<GanttViews.Warning>> byId = new LinkedHashMap<>();
        for (GanttViews.Row row : rows) {
            byId.putIfAbsent(row.id(), byKey.getOrDefault(GanttQuery.key(row.type(), row.id()), List.of()));
        }
        return byId;
    }

    /**
     * 全量计算：临期/逾期看行自己的计划期，越窗看父级窗口，超载看 owner+ISO 周的工作量合计。
     * 返回按 {@code TYPE:id} 分组，同一行内 RED 排在 ORANGE 之前（红 > 橙）。
     */
    public static Map<String, List<GanttViews.Warning>> compute(List<GanttViews.Row> rows,
                                                                List<Workload> workloads,
                                                                Map<String, GanttQuery.Window> parentWindows,
                                                                LocalDate today,
                                                                int capacityTenths) {
        Map<String, List<GanttViews.Warning>> warnings = new LinkedHashMap<>();
        for (GanttViews.Row row : rows) {
            warnings.put(GanttQuery.key(row.type(), row.id()), new ArrayList<>(timeWarnings(row, today)));
        }

        if (parentWindows != null && !parentWindows.isEmpty()) {
            for (GanttViews.Row row : rows) {
                GanttQuery.Window window = parentWindows.get(GanttQuery.key(row.type(), row.id()));
                if (window == null || window.contains(row.planStart(), row.planEnd())) {
                    continue;
                }
                int days = window.violatingDays(row.planStart(), row.planEnd());
                warnings.get(GanttQuery.key(row.type(), row.id())).add(new GanttViews.Warning(
                        GanttViews.WARN_OUT_OF_PARENT_WINDOW,
                        GanttViews.LEVEL_ORANGE,
                        "计划期超出父级窗口 " + window.start() + " ~ " + window.end() + "，越界 " + days + " 天",
                        days, null, List.of()));
            }
        }

        if (workloads != null && !workloads.isEmpty() && capacityTenths > 0) {
            applyOverload(rows, workloads, capacityTenths, warnings);
        }

        warnings.values().forEach(list -> list.sort(ORDER));
        return warnings;
    }

    private static List<GanttViews.Warning> timeWarnings(GanttViews.Row row, LocalDate today) {
        List<GanttViews.Warning> warnings = new ArrayList<>(2);
        if (row.done() || row.planEnd() == null) {
            return warnings;
        }
        long days = ChronoUnit.DAYS.between(today, row.planEnd());
        if (days < 0) {
            warnings.add(new GanttViews.Warning(GanttViews.WARN_OVERDUE, GanttViews.LEVEL_RED,
                    "已逾期 " + (-days) + " 天（计划完成 " + row.planEnd() + "）",
                    (int) -days, null, List.of()));
        } else if (days <= DUE_SOON_DAYS) {
            warnings.add(new GanttViews.Warning(GanttViews.WARN_DUE_SOON, GanttViews.LEVEL_ORANGE,
                    days == 0 ? "今天到期" : "还有 " + days + " 天到期",
                    (int) days, null, List.of()));
        }
        return warnings;
    }

    private static void applyOverload(List<GanttViews.Row> rows, List<Workload> workloads, int capacityTenths,
                                      Map<String, List<GanttViews.Warning>> warnings) {
        Map<String, List<Workload>> byOwnerWeek = new LinkedHashMap<>();
        Map<String, LocalDate> weekStartByKey = new LinkedHashMap<>();
        for (Workload workload : workloads) {
            if (workload.tenths() <= 0 || workload.date() == null) {
                continue;
            }
            LocalDate weekStart = workload.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            String key = workload.ownerId() + "|" + weekStart;
            byOwnerWeek.computeIfAbsent(key, k -> new ArrayList<>()).add(workload);
            weekStartByKey.put(key, weekStart);
        }

        for (Map.Entry<String, List<Workload>> entry : byOwnerWeek.entrySet()) {
            List<Workload> slices = entry.getValue();
            int total = 0;
            for (Workload slice : slices) {
                total += slice.tenths();
            }
            if (total <= capacityTenths) {
                continue;
            }
            LocalDate weekStart = weekStartByKey.get(entry.getKey());
            LocalDate weekEnd = weekStart.plusDays(6);
            double over = (total - capacityTenths) / 10.0;
            Set<String> items = new LinkedHashSet<>();
            for (Workload slice : slices) {
                if (items.size() >= MAX_OVERLOAD_ITEMS) {
                    break;
                }
                items.add(slice.label());
            }
            long ownerId = slices.get(0).ownerId();
            String message = weekStart + " ~ " + weekEnd + " 当周 " + decimal(total / 10.0) + " 人天，超出 "
                    + decimal(over) + " 人天（容量 " + decimal(capacityTenths / 10.0) + "）";
            for (GanttViews.Row row : rows) {
                if (row.ownerId() == null || row.ownerId() != ownerId) {
                    continue;
                }
                if (row.planStart() == null || row.planEnd() == null
                        || row.planEnd().isBefore(weekStart) || row.planStart().isAfter(weekEnd)) {
                    continue;
                }
                warnings.get(GanttQuery.key(row.type(), row.id())).add(new GanttViews.Warning(
                        GanttViews.WARN_OVERLOAD, GanttViews.LEVEL_ORANGE, message,
                        null, over, List.copyOf(items)));
            }
        }
    }

    /**
     * 把一条计划期的工作量按工作日摊开：合计十分之一人天在分片里不丢不重（4.9 人天 → 49，
     * 不会被摊成 50）。周末没有工作日时全部落在开始日。
     */
    public static List<Workload> spread(long ownerId, String type, long id, String label,
                                        LocalDate start, LocalDate end, int totalTenths) {
        if (start == null || end == null || end.isBefore(start) || totalTenths <= 0) {
            return List.of();
        }
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end) && days.size() < 366; day = day.plusDays(1)) {
            DayOfWeek dow = day.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                days.add(day);
            }
        }
        if (days.isEmpty()) {
            days.add(start);
        }
        int base = totalTenths / days.size();
        int remainder = totalTenths % days.size();
        List<Workload> slices = new ArrayList<>(days.size());
        for (int index = 0; index < days.size(); index++) {
            int tenths = base + (index < remainder ? 1 : 0);
            if (tenths > 0) {
                slices.add(new Workload(ownerId, days.get(index), tenths, type, id, label));
            }
        }
        return slices;
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
