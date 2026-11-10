package com.example.deliver.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.deliver.analytics.AnalyticsErrors;
import com.example.deliver.analytics.AnalyticsQueryService;
import com.example.platform.common.error.ApiException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The export ceiling is a pure function, so the boundary is asserted here instead of by
 * materialising ten thousand rows through the database (AC-10).
 */
class ExportCapTest {

    @Test
    @DisplayName("10001 组 → 400 REPORT_EXPORT_LIMIT，detail 带实际行数与上限")
    void rejectsAboveTheCeiling() {
        ApiException error = assertThrows(ApiException.class, () -> ExportCap.check(10_001L));

        assertEquals(400, error.status());
        assertEquals(AnalyticsErrors.EXPORT_LIMIT, error.code());
        assertEquals(10_001L, ((Number) error.detail().get("rows")).longValue());
        assertEquals(AnalyticsQueryService.EXPORT_MAX_ROWS, error.detail().get("maxRows"));
    }

    @Test
    @DisplayName("10000 组正好放行，不差一")
    void allowsTheCeiling() {
        assertDoesNotThrow(() -> ExportCap.check(AnalyticsQueryService.EXPORT_MAX_ROWS));
        assertDoesNotThrow(() -> ExportCap.check(0L));
    }
}
