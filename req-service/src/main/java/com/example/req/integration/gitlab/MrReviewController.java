package com.example.req.integration.gitlab;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 需求详情「集成」tab 的 MR 评审面（AC-12 / TECHNICAL §2.6）。
 *
 * <p>只做两件事：唯一的刷新入口（外呼）与行级评论分页（纯本地读）。
 * 判定、幂等、脱敏都在 {@link MrSyncService} 与 {@link MrCommentService}，
 * 控制器保持薄——这样「打开页面零外呼」只需检查这里没有 GET 触发同步。</p>
 */
@RestController
@RequestMapping("/requirements")
public class MrReviewController {

    private final MrSyncService syncService;
    private final MrCommentService commentService;

    public MrReviewController(MrSyncService syncService, MrCommentService commentService) {
        this.syncService = syncService;
        this.commentService = commentService;
    }

    /**
     * 刷新 MR 详情与行级评论：整个协作域里唯一会主动外呼 GitLab 的按钮。
     * 返回 {@code {card, added, updated}}——added/updated 是本次净变化，不是总条数。
     */
    @PostMapping("/{id}/integration/mr/refresh")
    public MrViews.RefreshResult refresh(@PathVariable long id) {
        return syncService.refresh(id);
    }

    /** 行级评论分页（键集，默认 50）。可见性跟随需求：跨域需求 404。 */
    @GetMapping("/{id}/integration/mr-comments")
    public MrCommentViews.CommentPage comments(@PathVariable long id,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(defaultValue = "50") int limit) {
        return commentService.page(id, cursor, limit);
    }

    /** 卡片本身走既有 {@code GET /requirements/{id}/integration}，这里只补一个轻量只读口。 */
    @GetMapping("/{id}/integration/mr")
    public Map<String, Object> card(@PathVariable long id) {
        MrViews.MrCard card = syncService.card(id);
        return card == null ? Map.of() : Map.of("card", card);
    }
}
