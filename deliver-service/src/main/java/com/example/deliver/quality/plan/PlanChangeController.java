package com.example.deliver.quality.plan;

import com.example.platform.common.scope.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Content changes of an approved plan: PATCH bumps {@code revision} and appends a
 * {@code plan_change_log} row, so the history is auditable and never silently rewritten.
 */
@RestController
@RequestMapping("/test-plans")
public class PlanChangeController {

    private final PlanLifecycleService lifecycle;

    public PlanChangeController(PlanLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PatchMapping("/{id}")
    public PlanViews.PlanDetail update(@PathVariable long id,
                                       @RequestBody PlanViews.UpdatePlan cmd) {
        return lifecycle.update(id, cmd);
    }

    @GetMapping("/{id}/change-logs")
    public PageResponse<PlanViews.ChangeLogRow> changeLogs(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return lifecycle.changeLogs(id, Math.max(1, page), Math.min(100, Math.max(1, size)));
    }
}
