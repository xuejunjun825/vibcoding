package com.example.deliver.flow;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.example.deliver.metric.MetricQuery;
import com.example.platform.common.scope.PageResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Value-stream endpoints (AC-8 / AC-9). Paths are declared in full rather than under a class-level
 * {@code /flow} prefix because the metric preview lives beside the zone's other metrics
 * ({@code /metrics/{code}/preview}) and must share the same delegation.
 */
@RestController
public class FlowController {

    private final FlowMetricService flow;
    private final FlowStageService stages;
    private final MetricQuery metrics;

    public FlowController(FlowMetricService flow, FlowStageService stages, MetricQuery metrics) {
        this.flow = flow;
        this.stages = stages;
        this.metrics = metrics;
    }

    @GetMapping("/flow/stages")
    public List<FlowViews.StageView> stageList() {
        return stages.list();
    }

    /** Full replacement: an id absent from the body is deleted (AC-8 「保存即全量」). */
    @PutMapping("/flow/stages")
    public List<FlowViews.StageView> saveStages(@RequestBody FlowViews.StageSaveCmd cmd) {
        return stages.save(cmd == null ? null : cmd.stages());
    }

    @GetMapping("/flow/board")
    public FlowViews.Board board(
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) String reqType) {
        return flow.board(new FlowViews.Query(domainId, vendorId, from, to, reqType));
    }

    @GetMapping("/flow/drill")
    public PageResponse<FlowViews.DrillRow> drill(
            @RequestParam String stageCode,
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) String reqType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return flow.drill(new FlowViews.DrillQuery(stageCode, domainId, vendorId, from, to, reqType,
                page, size));
    }

    @GetMapping("/flow/requirements/{id}/timeline")
    public List<FlowViews.TimelineRow> timeline(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return flow.timeline(id, from, to);
    }

    /** Metric dictionary preview: every {@code FLOW_*} is answered by the board itself. */
    @GetMapping("/metrics/{code}/preview")
    public Map<String, Object> preview(@PathVariable String code,
                                       @RequestParam Map<String, String> params) {
        return metrics.preview(code, params);
    }
}
