package com.example.req.review;

import com.example.req.review.ReleaseReviewViews.ConfirmReview;
import com.example.req.review.ReleaseReviewViews.CreateReview;
import com.example.req.review.ReleaseReviewViews.ReviewDetail;
import com.example.req.review.ReleaseReviewViews.ReviewPage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 发布 Go/No-Go 评审接口（TECHNICAL §5.3，AC-10）。 */
@RestController
@RequestMapping("/releases/{releaseId}/reviews")
public class ReleaseReviewController {

    private final ReleaseReviewService reviewService;

    public ReleaseReviewController(ReleaseReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public ResponseEntity<ReviewDetail> create(@PathVariable long releaseId,
                                               @RequestBody(required = false) CreateReview request) {
        return ResponseEntity.status(201).body(reviewService.create(releaseId, request));
    }

    @GetMapping
    public ReviewPage list(@PathVariable long releaseId,
                           @RequestParam(required = false) String decision,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size) {
        return reviewService.list(releaseId, decision, page, size);
    }

    @GetMapping("/{reviewId}")
    public ReviewDetail detail(@PathVariable long releaseId, @PathVariable long reviewId) {
        return reviewService.get(releaseId, reviewId);
    }

    @PostMapping("/{reviewId}/confirm")
    public ReviewDetail confirm(@PathVariable long releaseId,
                                @PathVariable long reviewId,
                                @RequestBody(required = false) ConfirmReview request) {
        return reviewService.confirm(releaseId, reviewId, request);
    }
}
