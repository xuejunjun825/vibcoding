package com.example.req.requirement;

import com.example.req.requirement.RequirementViews.Rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * 父需求汇总：人天 = 子需求之和（0.5 天精度），完成数 = 终态子需求数。
 *
 * <p>聚合视图与详情页共用此实现，避免"列表显示 3/5、详情显示 2/5"。</p>
 */
public final class RequirementRollup {

    private RequirementRollup() {
    }

    public static Rollup of(Collection<RequirementEntity> children, Predicate<String> terminal) {
        if (children == null || children.isEmpty()) {
            return Rollup.EMPTY;
        }
        long done = 0;
        BigDecimal estimate = BigDecimal.ZERO;
        BigDecimal actual = BigDecimal.ZERO;
        for (RequirementEntity child : children) {
            if (terminal.test(child.getStatus())) {
                done++;
            }
            estimate = estimate.add(child.getEstimateDays() == null ? BigDecimal.ZERO : child.getEstimateDays());
            actual = actual.add(child.getActualDays() == null ? BigDecimal.ZERO : child.getActualDays());
        }
        int progress = (int) Math.round(done * 100.0 / children.size());
        return new Rollup(children.size(), done, scale(estimate), scale(actual), progress);
    }

    /** 用一个父 ID 分组的行（状态, 数量, 人天和）计算汇总，供列表批量使用。 */
    public static Rollup fromGroups(List<ChildAggregate> rows, Predicate<String> terminal) {
        if (rows == null || rows.isEmpty()) {
            return Rollup.EMPTY;
        }
        long count = 0;
        long done = 0;
        BigDecimal estimate = BigDecimal.ZERO;
        BigDecimal actual = BigDecimal.ZERO;
        for (ChildAggregate row : rows) {
            count += row.count();
            if (terminal.test(row.status())) {
                done += row.count();
            }
            estimate = estimate.add(row.estimateDays() == null ? BigDecimal.ZERO : row.estimateDays());
            actual = actual.add(row.actualDays() == null ? BigDecimal.ZERO : row.actualDays());
        }
        int progress = count == 0 ? 0 : (int) Math.round(done * 100.0 / count);
        return new Rollup(count, done, scale(estimate), scale(actual), progress);
    }

    public static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }

    /** 子需求按状态分组后的聚合行。 */
    public record ChildAggregate(String status, long count, BigDecimal estimateDays, BigDecimal actualDays) {
    }
}
