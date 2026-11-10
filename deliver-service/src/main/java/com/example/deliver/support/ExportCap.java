package com.example.deliver.support;

import com.example.deliver.analytics.AnalyticsErrors;
import com.example.deliver.analytics.AnalyticsQueryService;

/**
 * The export ceiling, as one pure function.
 *
 * <p>The page reads at most {@code MAX_GROUPS} (5 000) groups, the export at most
 * {@link AnalyticsQueryService#EXPORT_MAX_ROWS} (10 000). The check runs on the count
 * <b>before</b> a single row is streamed, so an over-broad definition fails with the
 * export-limit error from {@link AnalyticsErrors} and the actual number instead of
 * exhausting the heap or leaving a half-written file behind.</p>
 */
public final class ExportCap {

    private ExportCap() {
    }

    /**
     * Reject an export whose group count exceeds the ceiling.
     *
     * @throws com.example.platform.common.error.ApiException 400 the export-limit error
     *         raised by {@link AnalyticsErrors#exportLimit(long)}
     */
    public static void check(long groups) {
        if (groups > AnalyticsQueryService.EXPORT_MAX_ROWS) {
            throw AnalyticsErrors.exportLimit(groups);
        }
    }
}
