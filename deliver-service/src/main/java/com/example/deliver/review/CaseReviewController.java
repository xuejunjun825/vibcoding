package com.example.deliver.review;

import java.util.List;

import com.example.platform.common.scope.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Case-review endpoints: case-scoped submission/list plus a cross-case read-only list.
 *
 * <p>The approval centre consumes the list endpoint and nothing else — reviews never become
 * workflow items, so there is no "my todos" query here on purpose.</p>
 */
@RestController
public class CaseReviewController {

    private final CaseReviewService service;

    public CaseReviewController(CaseReviewService service) {
        this.service = service;
    }

    @GetMapping("/testcases/{id}/reviews")
    public List<CaseReviewService.ReviewRow> listByCase(@PathVariable long id) {
        return service.listByCase(id);
    }

    /**
     * Submitting is idempotent-ish: a re-submission for a new case version is the normal second
     * round of the same case, so it answers 200 like the decision endpoint rather than 201 — the
     * acceptance script (s6) asserts 200 on the re-submit path and 200/201 on the first one.
     */
    @PostMapping("/testcases/{id}/reviews")
    public CaseReviewService.ReviewRow submit(@PathVariable long id,
                                              @RequestBody(required = false)
                                              CaseReviewService.SubmitCmd cmd) {
        return service.submit(id, cmd);
    }

    @PostMapping("/testcase-reviews/{id}/decide")
    public CaseReviewService.ReviewRow decide(@PathVariable long id,
                                              @RequestBody CaseReviewService.DecideCmd cmd) {
        return service.decide(id, cmd);
    }

    @GetMapping("/testcase-reviews")
    public PageResponse<CaseReviewService.ReviewRow> page(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.page(status, page, size);
    }
}
