package com.example.deliver.defect;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.defect.DefectViews.BoardView;
import com.example.deliver.defect.DefectViews.CreateDefect;
import com.example.deliver.defect.DefectViews.DefectFilter;
import com.example.deliver.defect.DefectViews.DefectView;
import com.example.deliver.defect.DefectViews.PageView;
import com.example.deliver.defect.DefectViews.TransitionCmd;
import com.example.deliver.metric.MetricQuery;
import com.example.deliver.support.Rows;
import org.springframework.format.annotation.DateTimeFormat;
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
 * Defect HTTP surface. Thin on purpose: filtering, state and derivation all live in the service
 * and query layers, so the controller cannot invent a second rule.
 */
@RestController
@RequestMapping("/defects")
public class DefectController {

    private final DefectService service;
    private final DefectBoardQuery board;
    private final MetricQuery metrics;

    public DefectController(DefectService service, DefectBoardQuery board, MetricQuery metrics) {
        this.service = service;
        this.board = board;
        this.metrics = metrics;
    }

    @GetMapping
    public PageView<Map<String, Object>> list(
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) Long requirementId,
            @RequestParam(required = false) Long releaseId,
            @RequestParam(required = false) Long handlerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        DefectFilter filter = new DefectFilter(domainId, vendorId, requirementId, releaseId,
                handlerId, status, severity, keyword, from, to);
        return board.items(filter, null, page, size);
    }

    @PostMapping
    public ResponseEntity<DefectView> create(@RequestBody CreateDefect cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(cmd));
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/actions")
    public DefectView action(@PathVariable long id, @RequestBody TransitionCmd cmd) {
        return service.transition(id, cmd);
    }

    @PostMapping("/from-execution/{executionId}")
    public ResponseEntity<DefectView> fromExecution(@PathVariable long executionId,
                                                    @RequestBody(required = false) CreateDefect cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createFromExecution(executionId, cmd));
    }

    /**
     * The board. {@code defs} are read through the metric dictionary; the cards themselves come
     * from the same query the drill-down uses.
     */
    @GetMapping("/board")
    public BoardView board(
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) Long requirementId,
            @RequestParam(required = false) Long releaseId,
            @RequestParam(required = false) Long handlerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        DefectFilter filter = new DefectFilter(domainId, vendorId, requirementId, releaseId,
                handlerId, status, severity, keyword, from, to);
        return new BoardView(board.metrics(filter), metrics.definitions(false),
                "GET /api/deliver/defects/board/items?metric=<openHigh|density|reopenRate|mttrMinutes>",
                filter);
    }

    /** Drill-down for one card: {@code total} equals the number the card showed. */
    @GetMapping("/board/items")
    public PageView<Map<String, Object>> boardItems(
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) Long requirementId,
            @RequestParam(required = false) Long releaseId,
            @RequestParam(required = false) Long handlerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "openHigh") String metric,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        DefectFilter filter = new DefectFilter(domainId, vendorId, requirementId, releaseId,
                handlerId, status, severity, keyword, from, to);
        return board.items(filter, metric, page, size);
    }

    /** CSV uses the same predicate as the cards — an export can never disagree with a number. */
    // 与 req 的导出同因：只声明 text/csv 时 Accept: application/json 会把请求打到 /{id}。
    @GetMapping(value = "/export.csv", produces = MediaType.ALL_VALUE)
    public ResponseEntity<String> export(
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) Long requirementId,
            @RequestParam(required = false) Long releaseId,
            @RequestParam(required = false) Long handlerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "") String metric) {
        DefectFilter filter = new DefectFilter(domainId, vendorId, requirementId, releaseId,
                handlerId, status, severity, keyword, from, to);
        List<Map<String, Object>> rows = board.export(filter, metric);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(String.join(",", DefectService.csvColumns())).append('\n');
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < DefectService.csvColumns().size(); i++) {
                if (i > 0) {
                    csv.append(',');
                }
                String column = DefectService.csvColumns().get(i);
                Object value = Rows.object(row, column);
                csv.append(escape(value == null ? "" : value.toString()));
            }
            csv.append('\n');
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.toString());
    }

    private String escape(String value) {
        String escaped = value.replace("\"", "\"\"");
        return escaped.indexOf(',') >= 0 || escaped.indexOf('\n') >= 0 || escaped.indexOf('"') >= 0
                ? '"' + escaped + '"' : escaped;
    }
}
