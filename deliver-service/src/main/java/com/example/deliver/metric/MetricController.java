package com.example.deliver.metric;

import java.util.List;

import com.example.deliver.defect.DefectViews.DefectFilter;
import com.example.deliver.metric.MetricViews.DefinitionCmd;
import com.example.deliver.metric.MetricViews.DefinitionView;
import com.example.deliver.metric.MetricViews.DefectMetrics;
import com.example.deliver.metric.MetricViews.PlanMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metric dictionary and topic reads.
 *
 * <p>The dictionary endpoint is what the board's "口径" popover reads: every number in the UI
 * can name the formula and the version that produced it, which is the difference between a
 * metric and a guess.</p>
 */
@RestController
public class MetricController {

    private final MetricQuery metrics;

    public MetricController(MetricQuery metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/metrics/defects")
    public DefectMetrics defects(@RequestParam(required = false) Long domainId,
                                 @RequestParam(required = false) Long vendorId,
                                 @RequestParam(required = false) Long requirementId,
                                 @RequestParam(required = false) Long releaseId) {
        return metrics.defects(new DefectFilter(domainId, vendorId, requirementId, releaseId,
                null, null, null, null, null, null));
    }

    @GetMapping("/metrics/test-pass")
    public PlanMetrics testPass(@RequestParam long planId) {
        return metrics.plan(planId);
    }

    @GetMapping("/metrics/definitions")
    public Dictionary definitions(@RequestParam(defaultValue = "false") boolean allVersions) {
        return new Dictionary(metrics.definitions(allVersions), allVersions);
    }

    @PostMapping("/admin/metric-definitions")
    public ResponseEntity<DefinitionView> publish(@RequestBody DefinitionCmd cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(metrics.publish(cmd));
    }

    /** {@code items} is the array the board and the admin list both consume. */
    public record Dictionary(List<DefinitionView> items, boolean allVersions) {
    }
}
