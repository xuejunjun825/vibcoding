package com.example.deliver.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two analytics reads: the metric strip and the dashboard.
 *
 * <p>The controller binds parameters, resolves the shared filter into an
 * {@link AnalyticsRequest} and copies the cache verdict into {@code X-Report-Cache}; it holds no
 * SQL and no formula. Both endpoints read the same store and the same viewer, so the strip and the
 * blocks cannot disagree about the time window — the window is resolved once, here, from the same
 * {@code preset}/{@code from}/{@code to} trio the frontend puts in the URL.</p>
 */
@RestController
public class AnalyticsController {

    private static final String CACHE_HEADER = "X-Report-Cache";

    private final AnalyticsQueryService analytics;

    public AnalyticsController(AnalyticsQueryService analytics) {
        this.analytics = analytics;
    }

    /**
     * {@code GET /analytics/metrics} — one tile row.
     *
     * @param keys comma separated metric keys; omitted means the workbench strip
     */
    @GetMapping("/analytics/metrics")
    public AnalyticsViews.Metrics metrics(
            @RequestParam(required = false) String keys,
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) String preset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String reqType,
            HttpServletResponse response) {
        String[] header = new String[1];
        AnalyticsViews.Metrics metrics = analytics.metrics(
                request(domainId, vendorId, from, to, preset, reqType), splitKeys(keys), header);
        response.setHeader(CACHE_HEADER, metrics.cache());
        return metrics;
    }

    /** {@code GET /analytics/dashboard} — the five blocks, one request. */
    @GetMapping("/analytics/dashboard")
    public AnalyticsViews.Dashboard dashboard(
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) String preset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String reqType,
            HttpServletResponse response) {
        String[] header = new String[1];
        AnalyticsViews.Dashboard dashboard = analytics.dashboard(
                request(domainId, vendorId, from, to, preset, reqType), bucket, header);
        response.setHeader(CACHE_HEADER, dashboard.cache());
        return dashboard;
    }

    private static AnalyticsRequest request(Long domainId, Long vendorId, LocalDate from,
                                            LocalDate to, String preset, String reqType) {
        return new AnalyticsRequest(domainId, vendorId, from, to, preset, reqType);
    }

    private static List<String> splitKeys(String keys) {
        if (keys == null || keys.isBlank()) {
            return AnalyticsQueryService.defaultKeys();
        }
        List<String> parsed = new ArrayList<>();
        for (String key : keys.split(",")) {
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(trimmed);
            }
        }
        return parsed.isEmpty() ? AnalyticsQueryService.defaultKeys() : parsed;
    }
}
