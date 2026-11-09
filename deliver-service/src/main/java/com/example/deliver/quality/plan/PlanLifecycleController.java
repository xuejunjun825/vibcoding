package com.example.deliver.quality.plan;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lifecycle endpoints of a test plan (AC-1 / AC-3). The controller only maps parameters and
 * errors — every legal transition comes from {@link PlanLifecycleService} and, underneath it,
 * {@code StateTables.PLAN}.
 */
@RestController
@RequestMapping("/test-plans")
public class PlanLifecycleController {

    private final PlanLifecycleService lifecycle;

    public PlanLifecycleController(PlanLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    /** Rich view used by the plan detail page: allowedNext, revision, criteria, rounds, assignment. */
    @GetMapping("/{id}/lifecycle")
    public PlanViews.PlanDetail lifecycle(@PathVariable long id) {
        return lifecycle.detail(id);
    }

    @GetMapping("/{id}/allowed-actions")
    public Map<String, Object> allowedActions(@PathVariable long id) {
        List<String> allowed = lifecycle.allowedNext(id);
        return Map.of("allowedNext", allowed);
    }

    @PostMapping("/{id}/submit-review")
    public PlanViews.PlanDetail submitReview(@PathVariable long id,
                                             @RequestBody(required = false) PlanViews.ActionBody body) {
        return lifecycle.action(id, "submit-review", body);
    }

    @PostMapping("/{id}/approve")
    public PlanViews.PlanDetail approve(@PathVariable long id,
                                        @RequestBody(required = false) PlanViews.ActionBody body) {
        return lifecycle.action(id, "approve", body);
    }

    @PostMapping("/{id}/reject")
    public PlanViews.PlanDetail reject(@PathVariable long id,
                                       @RequestBody(required = false) PlanViews.ActionBody body) {
        return lifecycle.action(id, "reject", body);
    }

    @PostMapping("/{id}/start")
    public PlanViews.PlanDetail start(@PathVariable long id,
                                      @RequestBody(required = false) PlanViews.ActionBody body) {
        return lifecycle.action(id, "start", body);
    }

    @PostMapping("/{id}/complete")
    public PlanViews.PlanDetail complete(@PathVariable long id,
                                         @RequestBody(required = false) PlanViews.ActionBody body) {
        return lifecycle.action(id, "complete", body);
    }

    @PostMapping("/{id}/close")
    public PlanViews.PlanDetail close(@PathVariable long id,
                                      @RequestBody(required = false) PlanViews.ActionBody body) {
        return lifecycle.action(id, "close", body);
    }

    @PostMapping("/{id}/archive")
    public PlanViews.PlanDetail archive(@PathVariable long id,
                                        @RequestBody(required = false) PlanViews.ActionBody body) {
        return lifecycle.action(id, "archive", body);
    }

    @GetMapping("/{id}/progress")
    public PlanViews.Progress progress(@PathVariable long id,
                                       @RequestParam(required = false) Long roundId) {
        return lifecycle.progress(id, roundId);
    }
}
