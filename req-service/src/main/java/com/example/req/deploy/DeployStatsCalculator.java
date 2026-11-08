package com.example.req.deploy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布统计的唯一算式（纯函数）。
 *
 * <p>只吃单据明细：{@code status / durationMinutes / 失败备注}，所以页面上任何一个数字都能
 * 拿明细逐单复算到同一位小数；统计口径变更只改这里，不在 SQL 里另写一套。</p>
 */
public final class DeployStatsCalculator {

    /** 一条单据的统计输入；{@code durationMinutes} 可为空（未登记执行的单据不参与平均）。 */
    public record Sample(String status, Integer durationMinutes, String reason) {
    }

    private DeployStatsCalculator() {
    }

    public static DeployViews.DeployStats calculate(List<Sample> samples) {
        List<Sample> rows = samples == null ? List.of() : samples;
        long succeeded = 0;
        long failed = 0;
        long rolledBack = 0;
        long durationSum = 0;
        long durationCount = 0;
        Map<String, Long> reasons = new LinkedHashMap<>();
        for (Sample row : rows) {
            String status = row.status();
            if (DeployOrderStates.SUCCESS.equals(status)) {
                succeeded++;
            } else if (DeployOrderStates.FAILED.equals(status)) {
                failed++;
            } else if (DeployOrderStates.ROLLED_BACK.equals(status)) {
                rolledBack++;
            } else {
                continue;
            }
            if (row.durationMinutes() != null && row.durationMinutes() >= 0) {
                durationSum += row.durationMinutes();
                durationCount++;
            }
            if (!DeployOrderStates.SUCCESS.equals(status)) {
                reasons.merge(reasonKey(status, row.reason()), 1L, Long::sum);
            }
        }
        long finished = succeeded + failed + rolledBack;
        BigDecimal successRate = finished == 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(succeeded).divide(BigDecimal.valueOf(finished), 4, RoundingMode.HALF_UP);
        BigDecimal avg = durationCount == 0
                ? BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(durationSum).divide(BigDecimal.valueOf(durationCount), 1, RoundingMode.HALF_UP);
        List<DeployViews.FailReason> failReasons = new ArrayList<>();
        reasons.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(entry -> failReasons.add(new DeployViews.FailReason(entry.getKey(), entry.getValue())));
        return new DeployViews.DeployStats(successRate, avg, failReasons, finished, succeeded, failed, rolledBack);
    }

    /** 备注优先（PMO 真正想看的原因），没填备注时按执行结果归类，保证分桶不丢单。 */
    private static String reasonKey(String status, String reason) {
        if (reason != null && !reason.isBlank()) {
            String trimmed = reason.trim();
            return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
        }
        return DeployOrderStates.ROLLED_BACK.equals(status) ? "发布后回滚（未填备注）" : "发布失败（未填备注）";
    }
}
