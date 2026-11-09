package com.example.deliver.quality.aggregate;

import java.math.BigDecimal;

/**
 * One plan's execution rollup. Every number comes from {@code test_plan_case}, so the denominator
 * is "cases the plan contains", never "cases somebody ran".
 */
public record PlanAggregate(long planId, String name, String status, int revision, long total,
                            long executed, long passed, long failed, long blocked,
                            BigDecimal passRate, BigDecimal coverage) {

    /** SKIP = 已执行 - PASS - FAIL - BLOCKED；只在这里派生，报告与计划进度共用。 */
    public long skipped() {
        return Math.max(0L, executed - passed - failed - blocked);
    }

    /** NOT_RUN = 计划总数 - 已执行（executed 定义为 result != NOT_RUN）。 */
    public long notRun() {
        return Math.max(0L, total - executed);
    }
}
