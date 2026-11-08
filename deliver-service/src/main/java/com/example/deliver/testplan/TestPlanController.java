package com.example.deliver.testplan;

import com.example.platform.common.scope.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Plan endpoints. Execution answers 201 with the execution id, as the UI expects. */
@RestController
@RequestMapping("/test-plans")
public class TestPlanController {

    private final TestPlanService service;

    public TestPlanController(TestPlanService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<TestPlanViews.PlanHeader> page(
            @RequestParam(required = false) Long releaseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.page(releaseId, status, keyword, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestPlanViews.PlanDetail create(@RequestBody TestPlanViews.CreatePlan cmd) {
        return service.create(cmd);
    }

    @GetMapping("/{id}")
    public TestPlanViews.PlanDetail detail(@PathVariable long id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/summary")
    public TestPlanViews.PlanSummary summary(@PathVariable long id) {
        return service.summary(id);
    }

    @PostMapping("/{id}/cases")
    public TestPlanViews.PlanDetail addCases(@PathVariable long id,
                                             @RequestBody TestPlanViews.AddCases cmd) {
        return service.addCases(id, cmd == null ? java.util.List.of() : cmd.caseIds());
    }

    @DeleteMapping("/{id}/cases/{caseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCase(@PathVariable long id, @PathVariable long caseId) {
        service.removeCase(id, caseId);
    }

    @PostMapping("/{id}/execute")
    @ResponseStatus(HttpStatus.CREATED)
    public TestPlanViews.ExecutionResult execute(@PathVariable long id,
                                                 @RequestBody TestPlanViews.ExecuteCommand cmd) {
        return service.execute(id, cmd);
    }
}
