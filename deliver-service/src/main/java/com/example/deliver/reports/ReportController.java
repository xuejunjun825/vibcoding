package com.example.deliver.reports;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.deliver.analytics.AnalyticsRequest;
import com.example.deliver.analytics.AnalyticsViews;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.security.RequiresPermission;

/**
 * The report HTTP surface: {@code /reports} and everything under one report id.
 *
 * <p>The controller only moves shapes around — it never decides visibility or ownership, those
 * live in {@link ReportService} so every verb follows one rule. Two details are HTTP-level and
 * therefore stay here: a create or copy answers <b>201</b> with the created resource, and both
 * share verbs are annotated with {@code REPORT_SHARE} — the route table only knows the POST form,
 * and the method annotation is what extends the same guard to PUT without touching a shared
 * table (AC-1 / AC-9).</p>
 *
 * <p>The bean name is explicit because the quality module has its own {@code ReportController}
 * and {@code ReportService}; two classes with the same simple name would otherwise collide in
 * the component scan before either could serve a request.</p>
 */
@RestController("reportDefinitionController")
public class ReportController {

    /** Same header the analytics endpoints stamp, so the client can read the cache verdict. */
    private static final String CACHE_HEADER = "X-Report-Cache";

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/reports")
    public PageResponse<ReportViews.ReportSummary> list(
            @RequestParam(required = false) String segment,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var found = reports.list(segment, keyword, page, size);
        // Echo the page the query actually ran (ReportService clamps page/size), not the raw
        // request: otherwise ?size=100000 reports size=100000 while only 100 rows came back.
        return new PageResponse<>(found.getContent(), found.getTotalElements(),
                found.getNumber() + 1, found.getSize());
    }

    @PostMapping("/reports")
    public ResponseEntity<ReportViews.ReportDetail> create(
            @RequestBody ReportViews.ReportCmd cmd) {
        return ResponseEntity.status(201).body(reports.create(cmd));
    }

    @GetMapping("/reports/{id}")
    public ReportViews.ReportDetail get(@PathVariable long id) {
        return reports.get(id);
    }

    @PutMapping("/reports/{id}")
    public ReportViews.ReportDetail update(@PathVariable long id,
                                           @RequestBody ReportViews.ReportCmd cmd) {
        return reports.update(id, cmd);
    }

    @DeleteMapping("/reports/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        reports.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reports/{id}/copy")
    public ResponseEntity<ReportViews.ReportDetail> copy(@PathVariable long id) {
        return ResponseEntity.status(201).body(reports.copy(id));
    }

    /** The editor's share dialog may PUT or POST; both carry the same permission. */
    @PutMapping("/reports/{id}/share")
    @RequiresPermission("REPORT_SHARE")
    public ReportViews.ReportDetail sharePut(@PathVariable long id,
                                             @RequestBody ReportViews.ShareCmd cmd) {
        return reports.share(id, cmd);
    }

    @PostMapping("/reports/{id}/share")
    @RequiresPermission("REPORT_SHARE")
    public ReportViews.ReportDetail sharePost(@PathVariable long id,
                                              @RequestBody ReportViews.ShareCmd cmd) {
        return reports.share(id, cmd);
    }

    @PostMapping("/reports/{id}/run")
    public AnalyticsViews.CrossTab run(@PathVariable long id,
                                       @RequestBody(required = false) ReportViews.RunCmd cmd,
                                       @RequestParam(required = false) Long domainId,
                                       @RequestParam(required = false) Long vendorId,
                                       @RequestParam(required = false) String preset,
                                       @RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) String reqType,
                                       jakarta.servlet.http.HttpServletResponse response) {
        String[] header = new String[1];
        AnalyticsViews.CrossTab tab = reports.run(id, cmd,
                new AnalyticsRequest(domainId, vendorId, from, to, preset, reqType), header);
        if (header[0] != null) {
            response.setHeader(CACHE_HEADER, header[0]);
        }
        return tab;
    }

    @GetMapping(value = "/reports/{id}/export", produces = MediaType.ALL_VALUE)
    public ResponseEntity<byte[]> export(
            @PathVariable long id,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) String preset,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String reqType) {
        ReportService.Export export = reports.export(id, format,
                new AnalyticsRequest(domainId, vendorId, from, to, preset, reqType));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, export.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition(export.fileName()))
                .body(export.bytes());
    }

    /**
     * {@code attachment} plus the RFC 5987 form, so a Chinese report name survives a browser
     * download without turning into percent noise in the visible filename.
     */
    static String disposition(String fileName) {
        String ascii = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded + "; filename=\"" + ascii + "\"";
    }
}
