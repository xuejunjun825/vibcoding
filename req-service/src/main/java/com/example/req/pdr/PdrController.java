package com.example.req.pdr;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * PDR 评审的 HTTP 边界（设计 §5.1）。
 *
 * <p>路径刻意分两组：需求维度的读写挂在 {@code /requirements/{id}/pdr-review}（前端从需求详情进），
 * 评审单维度的动作挂在 {@code /pdr-reviews/{reviewId}}（通知点进来时只有评审单 id）。两条路由规则的
 * 顺序在 {@code PermissionRouteTable} 里都排在 {@code ^/requirements.*$} 兜底之前。</p>
 */
@RestController
public class PdrController {

    private final PdrReviewService reviews;
    private final PdrStatsService stats;

    public PdrController(PdrReviewService reviews, PdrStatsService stats) {
        this.reviews = reviews;
        this.stats = stats;
    }

    /** 发起评审：201 + 评审单（{@code fallback=true} 表示评审人来自兜底规则）。 */
    @PostMapping("/requirements/{requirementId}/pdr-review")
    @ResponseStatus(HttpStatus.CREATED)
    public PdrViews.ReviewView start(@PathVariable long requirementId,
                                     @RequestBody(required = false) PdrViews.StartReview request) {
        return reviews.start(requirementId, request);
    }

    /** 需求当前的评审单（含全部意见）；从未发起过时 {@code review=null}。 */
    @GetMapping("/requirements/{requirementId}/pdr-review")
    public PdrViews.ReviewDetail detail(@PathVariable long requirementId) {
        return reviews.detailForRequirement(requirementId);
    }

    /** 意见分页：{@code reviewerId/category/status} 都可空。 */
    @GetMapping("/requirements/{requirementId}/pdr-review/opinions")
    public PdrViews.OpinionPage opinions(@PathVariable long requirementId,
                                         @RequestParam(required = false) Long reviewerId,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return reviews.opinions(requirementId, reviewerId, category, status, page, size);
    }

    /** 提交意见：一次可以多条（{@code opinions[]}），单条时用顶层三字段。 */
    @PostMapping("/pdr-reviews/{reviewId}/opinions")
    @ResponseStatus(HttpStatus.CREATED)
    public PdrViews.ReviewDetail submit(@PathVariable long reviewId,
                                        @RequestBody PdrViews.SubmitOpinions request) {
        return reviews.submitOpinions(reviewId, request);
    }

    /** 显式出结论（MODE_ALL 未评完 → 409 PDR_REVIEW_INCOMPLETE）。 */
    @PostMapping("/pdr-reviews/{reviewId}/conclude")
    public PdrViews.ReviewDetail conclude(@PathVariable long reviewId) {
        return reviews.conclude(reviewId);
    }

    /** 关闭评审：给人一个「评不下去了」的出口，结论记 REJECTED。 */
    @PostMapping("/pdr-reviews/{reviewId}/close")
    public PdrViews.ReviewDetail close(@PathVariable long reviewId,
                                       @RequestBody(required = false) CloseRequest request) {
        return reviews.closeReview(reviewId, request == null ? null : request.reason());
    }

    /** 意见转任务：201 + {taskId, taskCode, opinion}；跨服务失败 503 TASK_CREATE_UNAVAILABLE。 */
    @PostMapping("/pdr-reviews/{reviewId}/opinions/{opinionId}/to-task")
    @ResponseStatus(HttpStatus.CREATED)
    public PdrViews.ToTaskResult toTask(@PathVariable long reviewId, @PathVariable long opinionId,
                                        @RequestBody(required = false) PdrViews.ToTask request) {
        return reviews.toTask(reviewId, opinionId, request);
    }

    /** 评审统计：{@code groupBy=CATEGORY|REVIEWER|DECISION}，{@code from/to} 含当天。 */
    @GetMapping("/pdr-reviews/stats")
    public PdrViews.StatsView stats(@RequestParam(required = false) String groupBy,
                                    @RequestParam(required = false) Long domainId,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to) {
        return stats.stats(groupBy, domainId, from, to);
    }

    /** 报表数据集描述（给 /reports 的编辑器选维度与度量）。 */
    @GetMapping("/pdr-reviews/datasets")
    public List<PdrViews.DatasetInfo> datasets() {
        return stats.datasets();
    }

    /** 关闭评审的可选理由。 */
    public record CloseRequest(String reason) {
    }
}
