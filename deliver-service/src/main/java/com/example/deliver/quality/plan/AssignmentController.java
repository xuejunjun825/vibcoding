package com.example.deliver.quality.plan;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Execution allocation: read the split, replace it as a whole (never row-by-row edits). */
@RestController
@RequestMapping("/test-plans")
public class AssignmentController {

    private final AssignmentService assignments;

    public AssignmentController(AssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/{id}/assignments")
    public List<PlanViews.AssignmentView> list(@PathVariable long id,
                                               @RequestParam(required = false) Long roundId) {
        return assignments.list(id, roundId);
    }

    @PutMapping("/{id}/assignments")
    public List<PlanViews.AssignmentView> replace(@PathVariable long id,
                                                  @RequestParam(required = false) Long roundId,
                                                  @RequestBody(required = false)
                                                  PlanViews.AssignmentBody body) {
        return assignments.replace(id, roundId, body);
    }
}
