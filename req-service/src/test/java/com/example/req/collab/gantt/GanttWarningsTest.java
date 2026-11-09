package com.example.req.collab.gantt;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警只有一个实现（AC-3：原因由服务端给）。这里冻结"今天"验证三码边界与红>橙的排序，
 * 以及超载按 owner+ISO 周汇总、越窗按父级窗口判定。
 */
class GanttWarningsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9); // 周一

    @Test
    void overdueIsRedWithDayCount() {
        GanttViews.Row row = task(1L, 7L, TODAY.minusDays(10), TODAY.minusDays(5), false);
        List<GanttViews.Warning> warnings = compute(row).get(1L);

        assertEquals(1, warnings.size());
        assertEquals(GanttViews.WARN_OVERDUE, warnings.get(0).code());
        assertEquals(GanttViews.LEVEL_RED, warnings.get(0).level());
        assertEquals(5, warnings.get(0).days());
    }

    @Test
    void dueSoonWindowIsThreeDays() {
        assertTrue(due(GanttViews.WARN_DUE_SOON, TODAY.plusDays(3)));
        assertTrue(due(GanttViews.WARN_DUE_SOON, TODAY));
        assertFalse(due(GanttViews.WARN_DUE_SOON, TODAY.plusDays(4)));
        assertTrue(compute(task(4L, 1L, TODAY, TODAY.plusDays(3), false))
                .get(4L).get(0).message().contains("3 天"));
    }

    @Test
    void doneRowsHaveNoTimeWarnings() {
        GanttViews.Row finished = task(2L, 1L, TODAY.minusDays(30), TODAY.minusDays(20), true);
        assertTrue(compute(finished).get(2L).isEmpty());
    }

    @Test
    void redSortsBeforeOrangeOnTheSameRow() {
        GanttViews.Row overdue = task(3L, 9L, TODAY.minusDays(6), TODAY.minusDays(4), false);
        List<GanttWarnings.Workload> workload = List.of(
                new GanttWarnings.Workload(9L, TODAY.minusDays(5), 30, "TASK", 3L, "T-3"),
                new GanttWarnings.Workload(9L, TODAY.minusDays(4), 30, "TASK", 30L, "T-30"));

        List<GanttViews.Warning> warnings = GanttWarnings.compute(List.of(overdue), workload, Map.of(),
                TODAY, 50).get("TASK:3");

        assertEquals(2, warnings.size());
        assertEquals(GanttViews.LEVEL_RED, warnings.get(0).level());
        assertEquals(GanttViews.WARN_OVERLOAD, warnings.get(1).code());
    }

    @Test
    void overloadAggregatesPerOwnerWeekAndListsItems() {
        GanttViews.Row first = task(10L, 5L, TODAY, TODAY.plusDays(1), false);
        GanttViews.Row second = task(11L, 5L, TODAY.plusDays(2), TODAY.plusDays(3), false);
        GanttViews.Row nextWeek = task(12L, 5L, TODAY.plusDays(8), TODAY.plusDays(9), false);
        List<GanttWarnings.Workload> workload = List.of(
                new GanttWarnings.Workload(5L, TODAY, 30, "TASK", 10L, "A-10"),
                new GanttWarnings.Workload(5L, TODAY.plusDays(2), 30, "TASK", 11L, "A-11"),
                new GanttWarnings.Workload(5L, TODAY.plusDays(8), 30, "TASK", 12L, "A-12"));

        Map<String, List<GanttViews.Warning>> all = GanttWarnings.compute(
                List.of(first, second, nextWeek), workload, Map.of(), TODAY, 50);

        GanttViews.Warning overload = warning(all.get("TASK:10"), GanttViews.WARN_OVERLOAD);
        assertEquals(GanttViews.LEVEL_ORANGE, overload.level());
        assertEquals(1.0, overload.overDays(), 0.0001);
        assertTrue(overload.items().containsAll(List.of("A-10", "A-11")));
        assertTrue(overload.items().size() <= GanttWarnings.MAX_OVERLOAD_ITEMS);
        // 下一周只有 30 tenths，未超容量
        assertTrue(all.get("TASK:12").stream().noneMatch(w -> w.code().equals(GanttViews.WARN_OVERLOAD)));
    }

    @Test
    void outOfParentWindowIsReportedWithDays() {
        GanttViews.Row row = task(20L, 1L, TODAY.minusDays(2), TODAY.plusDays(8), false);
        GanttQuery.Window window = new GanttQuery.Window(TODAY, TODAY.plusDays(5));

        GanttViews.Warning warning = warning(GanttWarnings.compute(List.of(row), List.of(),
                Map.of("TASK:20", window), TODAY, 50).get("TASK:20"), GanttViews.WARN_OUT_OF_PARENT_WINDOW);

        // 越窗天数 = 左侧 2 天 + 右侧 3 天
        assertEquals(5, warning.days());
        assertEquals(GanttViews.LEVEL_ORANGE, warning.level());
    }

    @Test
    void spreadKeepsTotalTenthsAndSkipsWeekend() {
        LocalDate monday = TODAY;
        List<GanttWarnings.Workload> slices = GanttWarnings.spread(1L, "REQUIREMENT", 2L, "R-2",
                monday, monday.plusDays(6), 49);

        int total = slices.stream().mapToInt(GanttWarnings.Workload::tenths).sum();
        assertEquals(49, total);
        assertTrue(slices.stream().noneMatch(s -> s.date().getDayOfWeek() == DayOfWeek.SATURDAY
                || s.date().getDayOfWeek() == DayOfWeek.SUNDAY));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean due(String code, LocalDate planEnd) {
        GanttViews.Row row = task(100L, 1L, TODAY.minusDays(10), planEnd, false);
        return compute(row).get(100L).stream().anyMatch(w -> w.code().equals(code));
    }

    private static Map<Long, List<GanttViews.Warning>> compute(GanttViews.Row row) {
        return GanttWarnings.compute(List.of(row), row.planEnd() == null ? TODAY : TODAY, 50);
    }

    private static GanttViews.Warning warning(List<GanttViews.Warning> warnings, String code) {
        return warnings.stream().filter(w -> w.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少告警 " + code + "：" + warnings));
    }

    private static GanttViews.Row task(long id, Long ownerId, LocalDate start, LocalDate end, boolean done) {
        return new GanttViews.Row("TASK", id, 1L, 1, null, "任务 " + id, ownerId, "张三",
                start, end, null, null, done ? "DONE" : "IN_PROGRESS", done, false, null,
                "1762700000000", List.of());
    }
}
