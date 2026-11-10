package com.example.deliver.reports;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.deliver.analytics.AnalyticsDatasets;

/**
 * The {@code /reports/datasets} alias the web client already calls.
 *
 * <p>It shares {@link AnalyticsDatasets#catalog()} with {@code /analytics/datasets}, so the two
 * responses are the same record instance serialised twice — they cannot drift by a field. The
 * alias lives in its own controller so it does not compete for the report resource file that the
 * CRUD endpoints own.</p>
 */
@RestController
public class ReportDatasetsController {

    @GetMapping("/reports/datasets")
    public AnalyticsDatasets.DatasetList datasets() {
        return AnalyticsDatasets.catalog();
    }
}
