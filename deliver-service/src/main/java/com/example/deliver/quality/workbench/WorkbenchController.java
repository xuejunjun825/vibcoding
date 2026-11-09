package com.example.deliver.quality.workbench;

import com.example.platform.common.scope.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** "My quality work" — plans I own or execute, and the cases waiting on me. Read-only. */
@RestController
@RequestMapping("/workbench")
public class WorkbenchController {

    private final WorkbenchQuery query;

    public WorkbenchController(WorkbenchQuery query) {
        this.query = query;
    }

    @GetMapping
    public WorkbenchQuery.Workbench load(@RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "100") int limit) {
        return query.load(keyword, Math.min(500, Math.max(1, limit)));
    }

    /** Paginated "my cases" list; the workbench page declares this contract in web/src/api/quality.ts. */
    @GetMapping("/my-cases")
    public PageResponse<WorkbenchQuery.MyCaseRow> myCases(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Boolean overdue) {
        return query.myCases(Math.max(1, page), Math.min(200, Math.max(1, size)),
                Boolean.TRUE.equals(overdue));
    }
}
