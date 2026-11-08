package com.example.req.requirement;

import com.example.req.requirement.RequirementRollup.ChildAggregate;
import com.example.req.requirement.RequirementViews.Rollup;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementRollupTest {

    private static final Set<String> TERMINAL = Set.of("DONE", "CLOSED", "ACCEPTED_CLOSED");

    @Test
    void sumsChildDaysAndCountsTerminal() {
        RequirementEntity a = child("DONE", "2.0", "1.5");
        RequirementEntity b = child("DONE", "1.5", "2.0");
        RequirementEntity c = child("IN_DEV", "0.5", null);

        Rollup rollup = RequirementRollup.of(List.of(a, b, c), TERMINAL::contains);

        assertThat(rollup.childCount()).isEqualTo(3);
        assertThat(rollup.doneCount()).isEqualTo(2);
        assertThat(rollup.estimateDays()).isEqualByComparingTo("4.0");
        assertThat(rollup.actualDays()).isEqualByComparingTo("3.5");
        assertThat(rollup.progressPercent()).isEqualTo(67);
    }

    @Test
    void emptyChildrenGiveZeroRollup() {
        Rollup rollup = RequirementRollup.of(List.of(), TERMINAL::contains);
        assertThat(rollup.childCount()).isZero();
        assertThat(rollup.estimateDays()).isEqualByComparingTo("0");
    }

    @Test
    void nullDaysTreatedAsZero() {
        RequirementEntity a = child("TODO", null, null);
        Rollup rollup = RequirementRollup.of(List.of(a), TERMINAL::contains);
        assertThat(rollup.estimateDays()).isEqualByComparingTo("0.0");
        assertThat(rollup.doneCount()).isZero();
    }

    @Test
    void groupedAggregatesMatchEntityBasedRollup() {
        List<ChildAggregate> rows = List.of(
                new ChildAggregate("DONE", 2, new BigDecimal("3.5"), new BigDecimal("3.5")),
                new ChildAggregate("IN_DEV", 1, new BigDecimal("0.5"), BigDecimal.ZERO));
        Rollup rollup = RequirementRollup.fromGroups(rows, TERMINAL::contains);
        assertThat(rollup.childCount()).isEqualTo(3);
        assertThat(rollup.doneCount()).isEqualTo(2);
        assertThat(rollup.estimateDays()).isEqualByComparingTo("4.0");
        assertThat(rollup.progressPercent()).isEqualTo(67);
    }

    @Test
    void scaleRoundsToHalfDayPrecision() {
        assertThat(RequirementRollup.scale(new BigDecimal("1.25"))).isEqualByComparingTo("1.3");
        assertThat(RequirementRollup.scale(new BigDecimal("1.24"))).isEqualByComparingTo("1.2");
    }

    private RequirementEntity child(String status, String estimate, String actual) {
        RequirementEntity entity = new RequirementEntity();
        entity.setStatus(status);
        entity.setEstimateDays(estimate == null ? null : new BigDecimal(estimate));
        entity.setActualDays(actual == null ? null : new BigDecimal(actual));
        return entity;
    }
}
