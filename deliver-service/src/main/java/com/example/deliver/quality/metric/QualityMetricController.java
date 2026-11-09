package com.example.deliver.quality.metric;

import java.time.LocalDate;

import com.example.deliver.quality.quality.ReleaseQualityService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quality metric endpoints (AC-8): the five-metric board and the release quality pane.
 *
 * <p>Both are thin: no arithmetic happens in this class, because a controller that divides is a
 * second source of truth for the same number.</p>
 */
@RestController
public class QualityMetricController {

    private final QualityMetricService metrics;
    private final ReleaseQualityService releaseQuality;

    public QualityMetricController(QualityMetricService metrics, ReleaseQualityService releaseQuality) {
        this.metrics = metrics;
        this.releaseQuality = releaseQuality;
    }

    @GetMapping("/metrics/quality")
    public QualityMetricService.QualityMetrics quality(
            @RequestParam(required = false) Long releaseId,
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return metrics.quality(new QualityMetricService.Query(releaseId, domainId, vendorId, from, to));
    }

    @GetMapping("/releases/{releaseId:\\d+}/quality")
    public ReleaseQualityService.ReleaseQuality releaseQuality(@PathVariable long releaseId) {
        return releaseQuality.quality(releaseId);
    }
}
