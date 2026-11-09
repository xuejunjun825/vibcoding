package com.example.deliver.quality.gate;

import com.example.platform.common.scope.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quality gate and waiver endpoints (AC-5, AC-6, AC-7).
 *
 * <p>Paths are declared absolutely rather than under a class prefix because the release page and
 * the Go/No-Go page read the same gate through {@code /releases/{id}/quality-gate} — the route
 * table in {@code platform-common} grants that alias to {@code gate:view}, and the frontend
 * depends on it. The literal segments also carry a {@code \d+} constraint so
 * {@code /quality-gates/waivers} and {@code /quality-gates/snapshots/7} can never be parsed as a
 * release id.</p>
 */
@RestController
public class QualityGateController {

    private final QualityGateService gates;
    private final WaiverService waivers;

    public QualityGateController(QualityGateService gates, WaiverService waivers) {
        this.gates = gates;
        this.waivers = waivers;
    }

    @GetMapping("/quality-gates")
    public PageResponse<QualityGateViews.GateRow> page(
            @RequestParam(required = false) Long releaseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return gates.page(releaseId, status, page, size);
    }

    @GetMapping("/quality-gates/{releaseId:\\d+}")
    public QualityGateViews.GateDetail detail(@PathVariable long releaseId) {
        return gates.detail(releaseId);
    }

    /** Release-page alias of {@code GET /quality-gates/{releaseId}}. */
    @GetMapping("/releases/{releaseId:\\d+}/quality-gate")
    public QualityGateViews.GateDetail detailByRelease(@PathVariable long releaseId) {
        return gates.detail(releaseId);
    }

    @PutMapping("/quality-gates/{releaseId:\\d+}")
    public QualityGateViews.GateDetail configure(@PathVariable long releaseId,
                                                 @RequestBody QualityGateViews.ConfigCmd cmd) {
        return gates.configure(releaseId, cmd);
    }

    /** Pre-evaluation from the SPA: {@code deployOrderId = 0} means "do not freeze a snapshot". */
    @PostMapping("/quality-gates/{releaseId:\\d+}/evaluate")
    public QualityGateViews.Snapshot evaluate(@PathVariable long releaseId,
                                              @RequestBody(required = false) QualityGateViews.EvaluateCmd cmd) {
        QualityGateViews.EvaluateCmd safe = cmd == null
                ? new QualityGateViews.EvaluateCmd(null, null) : cmd;
        return gates.evaluateView(releaseId, safe.deployOrderId(), safe.requirementIds());
    }

    @GetMapping("/quality-gates/snapshots/{snapshotId:\\d+}")
    public QualityGateViews.Snapshot snapshot(@PathVariable long snapshotId) {
        return gates.viewOf(snapshotId);
    }

    @PostMapping("/quality-gates/snapshots/{snapshotId:\\d+}/replay")
    public QualityGateViews.Replay replay(@PathVariable long snapshotId) {
        return gates.replay(snapshotId);
    }

    // ------------------------------------------------------------------ waivers

    @GetMapping("/quality-gates/waivers")
    public PageResponse<QualityGateViews.WaiverRow> waiverPage(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long releaseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return waivers.page(status, releaseId, page, size);
    }

    @PostMapping("/quality-gates/waivers")
    @ResponseStatus(HttpStatus.CREATED)
    public QualityGateViews.WaiverRow applyWaiver(
            @RequestBody(required = false) QualityGateViews.WaiverApplyCmd cmd) {
        return waivers.apply(cmd);
    }

    @PostMapping("/quality-gates/waivers/{id:\\d+}/approve")
    public QualityGateViews.WaiverRow approveWaiver(@PathVariable long id,
                                                    @RequestBody(required = false) QualityGateViews.DecisionCmd cmd) {
        return waivers.approve(id, cmd == null ? null : cmd.comment(),
                cmd != null && Boolean.TRUE.equals(cmd.confirmSelfApproval()));
    }

    @PostMapping("/quality-gates/waivers/{id:\\d+}/reject")
    public QualityGateViews.WaiverRow rejectWaiver(@PathVariable long id,
                                                   @RequestBody(required = false) QualityGateViews.DecisionCmd cmd) {
        return waivers.reject(id, cmd == null ? null : cmd.comment());
    }

    @PostMapping("/quality-gates/waivers/{id:\\d+}/revoke")
    public QualityGateViews.WaiverRow revokeWaiver(@PathVariable long id,
                                                   @RequestBody(required = false) QualityGateViews.DecisionCmd cmd) {
        return waivers.revoke(id, cmd == null ? null : cmd.reason());
    }
}
