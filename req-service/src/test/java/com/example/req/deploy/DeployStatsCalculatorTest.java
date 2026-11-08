package com.example.req.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AC-12「发布成功率/平均耗时可由单据明细复算」：同一组明细算出的数字必须逐位稳定。 */
class DeployStatsCalculatorTest {

    private static DeployStatsCalculator.Sample sample(String status, Integer minutes, String reason) {
        return new DeployStatsCalculator.Sample(status, minutes, reason);
    }

    @Test
    @DisplayName("没有已结束单据：成功率 0.0000、均值 0.0，不出现除零")
    void emptySamples() {
        DeployViews.DeployStats zero = DeployStatsCalculator.calculate(List.of());
        assertEquals(new BigDecimal("0.0000"), zero.successRate());
        assertEquals(new BigDecimal("0.0"), zero.avgDurationMinutes());
        assertTrue(zero.failReasons().isEmpty());
        assertEquals(0, zero.total());

        DeployViews.DeployStats draftOnly = DeployStatsCalculator.calculate(List.of(
                sample(DeployOrderStates.DRAFT, null, null),
                sample(DeployOrderStates.PENDING_APPROVAL, null, null)));
        assertEquals(new BigDecimal("0.0000"), draftOnly.successRate(), "草稿/待审批不算分母");
        assertEquals(0, draftOnly.total());
    }

    @Test
    @DisplayName("成功率 = 成功 / (成功+失败+回滚)，四位小数四舍五入")
    void successRate() {
        DeployViews.DeployStats stats = DeployStatsCalculator.calculate(List.of(
                sample(DeployOrderStates.SUCCESS, 30, null),
                sample(DeployOrderStates.SUCCESS, 90, null),
                sample(DeployOrderStates.FAILED, 60, "镜像拉取失败"),
                sample(DeployOrderStates.ROLLED_BACK, 120, "接口 500 持续 5 分钟")));

        assertEquals(new BigDecimal("0.5000"), stats.successRate());
        assertEquals(4, stats.total());
        assertEquals(2, stats.succeeded());
        assertEquals(1, stats.failed());
        assertEquals(1, stats.rolledBack());
    }

    @Test
    @DisplayName("平均耗时按已登记耗时的单据算，一位小数；未登记的不拉低均值")
    void averageDuration() {
        DeployViews.DeployStats stats = DeployStatsCalculator.calculate(List.of(
                sample(DeployOrderStates.SUCCESS, 30, null),
                sample(DeployOrderStates.SUCCESS, 45, null),
                sample(DeployOrderStates.SUCCESS, null, null)));

        assertEquals(new BigDecimal("37.5"), stats.avgDurationMinutes());
        assertEquals(new BigDecimal("1.0000"), stats.successRate());
    }

    @Test
    @DisplayName("失败原因分布：备注优先、按次数倒序，没备注按结果归类不丢单")
    void failReasons() {
        DeployViews.DeployStats stats = DeployStatsCalculator.calculate(List.of(
                sample(DeployOrderStates.FAILED, 10, "镜像拉取失败"),
                sample(DeployOrderStates.FAILED, 12, "镜像拉取失败"),
                sample(DeployOrderStates.FAILED, 15, "   "),
                sample(DeployOrderStates.ROLLED_BACK, 40, null),
                sample(DeployOrderStates.SUCCESS, 20, null)));

        assertEquals(3, stats.failReasons().size(), "2 条同因 + 1 条未填备注的失败 + 1 条未填备注的回滚");
        assertEquals("镜像拉取失败", stats.failReasons().get(0).reason());
        assertEquals(2, stats.failReasons().get(0).count());
        assertEquals(4, stats.failReasons().stream().mapToLong(DeployViews.FailReason::count).sum(),
                "失败+回滚共 4 单，分桶必须覆盖全部");
        assertTrue(stats.failReasons().stream().noneMatch(reason -> reason.reason().isBlank()));
    }

    @Test
    @DisplayName("同一组明细两次计算结果完全一致（页面数字可复算）")
    void deterministic() {
        List<DeployStatsCalculator.Sample> samples = List.of(
                sample(DeployOrderStates.SUCCESS, 61, null),
                sample(DeployOrderStates.FAILED, 8, "健康检查未通过"));

        DeployViews.DeployStats first = DeployStatsCalculator.calculate(samples);
        DeployViews.DeployStats second = DeployStatsCalculator.calculate(samples);

        assertEquals(first.successRate(), second.successRate());
        assertEquals(first.avgDurationMinutes(), second.avgDurationMinutes());
        assertEquals(first.failReasons(), second.failReasons());
        assertEquals(new BigDecimal("0.5000"), first.successRate());
        assertEquals(new BigDecimal("34.5"), first.avgDurationMinutes());
    }
}
