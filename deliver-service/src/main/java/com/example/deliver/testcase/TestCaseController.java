package com.example.deliver.testcase;

import java.util.List;

import com.example.platform.common.scope.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Case library endpoints. Paths are exactly the ones {@code web/src/api/deliver.ts} already calls;
 * mutating calls answer with the full view so the UI never needs a second round-trip.
 */
@RestController
@RequestMapping("/testcases")
public class TestCaseController {

    private final TestCaseService service;
    private final TestCaseQuery query;

    public TestCaseController(TestCaseService service, TestCaseQuery query) {
        this.service = service;
        this.query = query;
    }

    @GetMapping
    public PageResponse<TestCaseViews.CaseRow> page(
            @RequestParam(required = false) Long moduleId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<Long> moduleIds = moduleId == null ? List.of() : service.moduleSubtreeIds(moduleId);
        String storeType = type == null || type.isBlank() ? null : CaseType.store(type);
        return query.page(moduleIds, keyword, priority, storeType, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestCaseViews.CaseView create(@RequestBody TestCaseViews.CreateCase cmd) {
        return service.create(cmd);
    }

    @GetMapping("/{id}")
    public TestCaseViews.CaseView detail(@PathVariable long id,
                                         @RequestParam(required = false) Integer version) {
        return service.detail(id, version);
    }

    @PutMapping("/{id}")
    public TestCaseViews.CaseView update(@PathVariable long id,
                                         @RequestBody TestCaseViews.CreateCase cmd) {
        return service.update(id, cmd);
    }

    @GetMapping("/{id}/versions")
    public List<TestCaseViews.CaseVersionRow> versions(@PathVariable long id) {
        return service.versions(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public TestCaseViews.CaseView copy(@PathVariable long id,
                                       @RequestBody(required = false) CopyCommand cmd) {
        return service.copy(id, cmd == null ? null : cmd.title());
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TestCaseViews.ImportResult importCsv(@RequestParam("file") MultipartFile file) {
        return service.importCsv(file);
    }

    // 与 req 的导出同因：只声明 text/csv 时 Accept: application/json 会把请求打到 /{id}。
    @GetMapping(value = "/export.csv", produces = MediaType.ALL_VALUE)
    public ResponseEntity<String> exportCsv(
            @RequestParam(required = false) Long moduleId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String type) {
        List<TestCaseViews.CaseRow> rows = service.exportRows(moduleId, keyword, priority, type);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(service.toCsv(rows));
    }

    /** Copy keeps the case title optional: a blank title means "原标题 + 副本". */
    public record CopyCommand(String title) {
    }
}
