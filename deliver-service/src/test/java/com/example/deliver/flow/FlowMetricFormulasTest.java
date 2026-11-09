package com.example.deliver.flow;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The value-stream formulas are pure and frozen-clock free, so every AC-9 subtlety is asserted
 * here instead of through the database: nearest-rank P90 (not interpolated), "no sample is not
 * zero days", efficiency excluding a zero denominator, and the bottleneck sample floor.
 */
class FlowMetricFormulasTest {

    private static final long DAY = 86_400L;

    @Test
    @DisplayName("平均停留：无样本返回 null，不按 0 兜底")
    void averageDwellNeverFabricatesZero() {
        assertNull(FlowMetricFormulas.avgDwell(List.of()));
        assertNull(FlowMetricFormulas.avgDwell(null));
        assertEquals(1.5d, FlowMetricFormulas.avgDwell(List.of(DAY, 2 * DAY)));
        assertEquals(6.2d, FlowMetricFormulas.avgDwell(List.of(536_000L, 535_000L)));
    }

    @Test
    @DisplayName("P90 nearest-rank：n=10 取第 9 个，n=11 取第 10 个")
    void p90IsNearestRankNotInterpolated() {
        List<Long> ten = Arrays.asList(DAY, 2 * DAY, 3 * DAY, 4 * DAY, 5 * DAY,
                6 * DAY, 7 * DAY, 8 * DAY, 9 * DAY, 100 * DAY);
        assertEquals(9d, FlowMetricFormulas.p90Dwell(ten));
        List<Long> eleven = new java.util.ArrayList<>(ten);
        eleven.add(200 * DAY);
        assertEquals(100d, FlowMetricFormulas.p90Dwell(eleven));
        assertEquals(1d, FlowMetricFormulas.p90Dwell(List.of(DAY)));
        assertNull(FlowMetricFormulas.p90Dwell(List.of()));
    }

    @Test
    @DisplayName("流动效率：仅已交付、分母为正才参与，返回百分比")
    void efficiencyExcludesZeroDenominator() {
        assertNull(FlowMetricFormulas.efficiency(10, 0));
        assertNull(FlowMetricFormulas.efficiency(0, 100));
        assertEquals(50d, FlowMetricFormulas.efficiency(50, 100));
        assertEquals(62.5d, FlowMetricFormulas.efficiency(5, 8));
        assertEquals(100d, FlowMetricFormulas.efficiency(9, 8));
    }

    @Test
    @DisplayName("瓶颈：样本 <5 不参与，最大均值获胜，并列取先序")
    void bottleneckIgnoresThinSamples() {
        Map<String, Double> avg = new LinkedHashMap<>();
        avg.put("REVIEW", 3.0d);
        avg.put("TEST", 6.2d);
        Map<String, Integer> samples = new LinkedHashMap<>();
        samples.put("REVIEW", 20);
        samples.put("TEST", 4);
        assertEquals(Optional.of("REVIEW"), FlowMetricFormulas.bottleneck(avg, samples));

        samples.put("TEST", 5);
        assertEquals(Optional.of("TEST"), FlowMetricFormulas.bottleneck(avg, samples));

        Map<String, Integer> all = new LinkedHashMap<>();
        all.put("REVIEW", 4);
        all.put("TEST", 4);
        assertTrue(FlowMetricFormulas.bottleneck(avg, all).isEmpty());
    }

    @Test
    @DisplayName("阶段映射：状态/事件进入条件与按类型收窄")
    void stageMappingAndApplicability() {
        FlowViews.StageView review = new FlowViews.StageView(1L, "REVIEW", "评审", 2,
                "{\"enterStatuses\":[\"APPROVING\"],\"enterEvents\":[\"RELEASE_ATTACHED\"],"
                        + "\"leaveStatuses\":[\"POOLED\"]}", null, true);
        FlowStageService.Mapping mapping = FlowStageService.mapping(review);
        assertTrue(mapping.entersByStatus("APPROVING"));
        assertTrue(mapping.entersByEvent("RELEASE_ATTACHED"));
        assertEquals(java.util.Set.of("POOLED"), mapping.leaveStatuses());

        FlowViews.StageView narrowed = new FlowViews.StageView(2L, "TEST", "测试", 5, "{}",
                "BUS,RD", true);
        assertTrue(FlowStageService.applicable(narrowed, "RD"));
        assertTrue(!FlowStageService.applicable(narrowed, "PDR"));
        assertTrue(FlowStageService.applicable(narrowed, null));

        assertEquals("DEV", FlowStageService.roleOf(new FlowViews.StageView(3L, "DEV", "开发", 4,
                "{}", null, true)));
        assertEquals("DELIVERY", FlowStageService.roleOf(new FlowViews.StageView(4L, "X", "交付", 7,
                "{}", null, true)));
    }
}
