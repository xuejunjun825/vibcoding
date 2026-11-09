package com.example.deliver.quality.report;

import java.util.List;
import java.util.Map;

import com.example.platform.common.scope.PageResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test report endpoints (TECH §5 "报告").
 *
 * <p>The controller maps parameters and nothing else: generation, signoff order and the export
 * permission filter all live one layer down, so a second entry point (the report centre, the gate
 * dialog) cannot get a different answer.</p>
 */
@RestController
@RequestMapping("/test-reports")
public class ReportController {

    private final ReportService reports;
    private final SignoffService signoffs;
    private final ReportExportService exports;

    public ReportController(ReportService reports, SignoffService signoffs, ReportExportService exports) {
        this.reports = reports;
        this.signoffs = signoffs;
        this.exports = exports;
    }

    /** Generate a version; regenerating the same scope/plan/release appends v2, v3… (AC-4.3). */
    @PostMapping
    public ResponseEntity<ReportViews.ReportDetail> generate(@RequestBody ReportViews.Generate cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reports.generate(cmd));
    }

    /** Report centre list. */
    @GetMapping
    public PageResponse<ReportViews.ReportRow> page(
            @RequestParam(required = false) Long releaseId,
            @RequestParam(required = false) Long planId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return reports.page(releaseId, planId, scope, status, keyword, page, size);
    }

    /** Header + version selector + live signoff progress. */
    @GetMapping("/{id}")
    public ReportViews.ReportDetail detail(@PathVariable long id) {
        return reports.detail(id);
    }

    @GetMapping("/{id}/versions")
    public List<ReportViews.VersionRow> versions(@PathVariable long id) {
        return reports.versions(id);
    }

    /** One version with its frozen 8-section snapshot (Q7 reader). */
    @GetMapping("/{id}/versions/{versionNo}")
    public ReportViews.ReportVersionDetail version(@PathVariable long id, @PathVariable int versionNo) {
        return reports.version(id, versionNo);
    }

    /** Frozen case list behind section 02/03 — the numbers on screen must be recomputable from here. */
    @GetMapping("/{id}/versions/{versionNo}/cases")
    public PageResponse<ReportViews.CaseDetailRow> cases(
            @PathVariable long id,
            @PathVariable int versionNo,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reports.caseItems(id, versionNo, result, page, size);
    }

    /** Frozen defect list behind sections 04/05. */
    @GetMapping("/{id}/versions/{versionNo}/defects")
    public PageResponse<ReportViews.DefectDetailRow> defects(
            @PathVariable long id,
            @PathVariable int versionNo,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reports.defectItems(id, versionNo, severity, page, size);
    }

    /** Sign one rung of the ladder; 409 {@code SIGNOFF_ORDER_VIOLATION} when out of order. */
    @PostMapping("/{id}/versions/{versionNo}/signoff")
    public ReportViews.SignoffProgress sign(@PathVariable long id,
                                            @PathVariable int versionNo,
                                            @RequestBody ReportViews.SignCommand cmd) {
        return signoffs.sign(id, versionNo, cmd);
    }

    /** Signoff progress of one version (defaults to the current one on the UI side). */
    @GetMapping("/{id}/versions/{versionNo}/signoff")
    public ReportViews.SignoffProgress signoff(@PathVariable long id, @PathVariable int versionNo) {
        return signoffs.progress(id, versionNo);
    }

    /**
     * Download. {@code produces = ALL} because the client may send {@code Accept: application/json}
     * (the format is chosen by the query parameter, not by content negotiation).
     */
    @GetMapping(value = "/{id}/export", produces = MediaType.ALL_VALUE)
    public ResponseEntity<byte[]> export(@PathVariable long id,
                                         @RequestParam(required = false) String format,
                                         @RequestParam(required = false) Integer version) {
        ReportExportService.Export result = exports.export(id, version, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.fileName() + "\"")
                .body(result.bytes());
    }

    /** Version diff for the reader's "对比上一版". */
    @GetMapping("/{id}/diff")
    public ReportViews.ReportDiff diff(@PathVariable long id,
                                       @RequestParam int from,
                                       @RequestParam int to) {
        return reports.diff(id, from, to);
    }

    /** Report centre counters (total / signing / signed), cheap enough for a badge. */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) Long releaseId,
                                       @RequestParam(required = false) Long planId) {
        long total = reports.page(releaseId, planId, null, null, null, 1, 1).total();
        long signing = reports.page(releaseId, planId, null, "SIGNING", null, 1, 1).total();
        long signed = reports.page(releaseId, planId, null, "SIGNED", null, 1, 1).total();
        long rejected = reports.page(releaseId, planId, null, "REJECTED", null, 1, 1).total();
        return Map.of("total", total, "signing", signing, "signed", signed, "rejected", rejected);
    }
}
