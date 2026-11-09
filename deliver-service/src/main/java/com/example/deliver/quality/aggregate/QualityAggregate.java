package com.example.deliver.quality.aggregate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One release's execution rollup, summed from {@link PlanAggregate} — never re-queried with a
 * second set of denominators. Report, gate and board all read this object, which is why the
 * integers and the two-decimal rates can never disagree (AC-3.6).
 */
public record QualityAggregate(long releaseId, long totalPlanned, long executed, long passed,
                               long failed, long blocked, long notRun, BigDecimal passRate,
                               BigDecimal coverage, List<PlanAggregate> plans,
                               Map<Long, Integer> planRevisions) {

    public boolean hasPlans() {
        return plans != null && !plans.isEmpty();
    }
}
