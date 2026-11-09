package com.example.req.sys;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.platform.common.audit.ActualPeriodRecorder;
import com.example.platform.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The actual period is the light bar under the gantt's plan bar, and it is written by exactly one
 * class. These assertions pin the caliber that {@code V9__ccode_cteam_ctest_p0.sql} backfills with,
 * so the seeded rows and the live transitions cannot disagree.
 *
 * <p>No database: the status → boundary decision is pure, and the recorder must never throw when it
 * cannot write (a missing date must not fail a delivery).</p>
 */
class ActualPeriodRecorderTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("opening statuses: requirement IN_DEV, release IN_PROGRESS|PUBLISHED, sprint ACTIVE, task IN_PROGRESS")
    void openingStatuses() {
        assertTrue(ActualPeriodRecorder.startsActualStart("REQUIREMENT", "IN_DEV"));
        assertFalse(ActualPeriodRecorder.startsActualStart("REQUIREMENT", "PENDING_TEST"));
        assertFalse(ActualPeriodRecorder.startsActualStart("REQUIREMENT", "POOLED"));
        // A release that jumps straight to PUBLISHED still began its actual period that day.
        assertTrue(ActualPeriodRecorder.startsActualStart("RELEASE", "IN_PROGRESS"));
        assertTrue(ActualPeriodRecorder.startsActualStart("RELEASE", "PUBLISHED"));
        assertFalse(ActualPeriodRecorder.startsActualStart("RELEASE", "PLAN"));
        assertTrue(ActualPeriodRecorder.startsActualStart("SPRINT", "ACTIVE"));
        assertFalse(ActualPeriodRecorder.startsActualStart("SPRINT", "PLANNING"));
        assertTrue(ActualPeriodRecorder.startsActualStart("TASK", "IN_PROGRESS"));
        assertFalse(ActualPeriodRecorder.startsActualStart("TASK", "TODO"));
    }

    @Test
    @DisplayName("closing statuses match the V9 backfill: DELIVERED/DONE/ACCEPTED_CLOSED/CLOSED and friends")
    void closingStatuses() {
        for (String status : new String[] {"DELIVERED", "DONE", "ACCEPTED_CLOSED", "CLOSED"}) {
            assertTrue(ActualPeriodRecorder.closesActualEnd("REQUIREMENT", status), status);
        }
        assertFalse(ActualPeriodRecorder.closesActualEnd("REQUIREMENT", "IN_DEV"));
        assertFalse(ActualPeriodRecorder.closesActualEnd("REQUIREMENT", "PENDING_TEST"));
        assertTrue(ActualPeriodRecorder.closesActualEnd("RELEASE", "RELEASED"));
        assertTrue(ActualPeriodRecorder.closesActualEnd("RELEASE", "CLOSED"));
        assertFalse(ActualPeriodRecorder.closesActualEnd("RELEASE", "PUBLISHED"));
        assertTrue(ActualPeriodRecorder.closesActualEnd("SPRINT", "CLOSED"));
        assertFalse(ActualPeriodRecorder.closesActualEnd("SPRINT", "ACTIVE"));
        assertTrue(ActualPeriodRecorder.closesActualEnd("TASK", "DONE"));
        assertTrue(ActualPeriodRecorder.closesActualEnd("TASK", "CANCELLED"));
        assertFalse(ActualPeriodRecorder.closesActualEnd("TASK", "IN_PROGRESS"));
    }

    @Test
    @DisplayName("case-insensitive and null-tolerant: statuses arrive from controllers as typed text")
    void tolerantInput() {
        assertTrue(ActualPeriodRecorder.startsActualStart("requirement", "in_dev"));
        assertTrue(ActualPeriodRecorder.startsActualStart(" TASK ", " in_progress "));
        assertFalse(ActualPeriodRecorder.startsActualStart("REQUIREMENT", null));
        assertFalse(ActualPeriodRecorder.closesActualEnd(null, "DONE"));
        assertFalse(ActualPeriodRecorder.startsActualStart("EPIC", "IN_DEV"),
                "an unknown biz type has no table, so it must not open anything");
        assertFalse(ActualPeriodRecorder.closesActualEnd("EPIC", "CLOSED"));
    }

    @Test
    @DisplayName("recording is best-effort: no tenant, no-op transition or unknown type never throws")
    void recordingNeverThrows() {
        // No tenant scope (e.g. a background path that forgot to set one): warn and skip.
        assertDoesNotThrow(() -> ActualPeriodRecorder.record("REQUIREMENT", 5L, "POOLED", "IN_DEV"));
        TenantContext.set(com.example.platform.contract.DataScope.system(1L));
        // Same-status re-issue and a non-boundary status are both no-ops.
        assertDoesNotThrow(() -> ActualPeriodRecorder.record("REQUIREMENT", 5L, "IN_DEV", "IN_DEV"));
        assertDoesNotThrow(() -> ActualPeriodRecorder.record("REQUIREMENT", 5L, "POOLED", "PENDING_TEST"));
        // No JDBC wired in this unit test: the call must still return quietly.
        assertDoesNotThrow(() -> ActualPeriodRecorder.record("REQUIREMENT", 5L, "POOLED", "IN_DEV"));
        assertDoesNotThrow(() -> ActualPeriodRecorder.record("EPIC", 5L, "A", "B"));
        assertDoesNotThrow(() -> ActualPeriodRecorder.record("REQUIREMENT", 0L, "POOLED", "IN_DEV"));
    }
}
