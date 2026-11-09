package com.example.req.integration.gitlab;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 需求详情「集成」页的 MR 三个动作（TECHNICAL §4.4）。
 *
 * <p>放在集成包里而不是 {@code RequirementController}：需求控制器属于需求域，
 * 这里只加三条只依赖 {@link MrSyncService} 的路由，前端契约与 API 文档同源于
 * {@link MrViews}。</p>
 */
@RestController
@RequestMapping("/requirements")
public class MrController {

    private final MrSyncService mrSyncService;
    private final MrAdvanceService advanceService;

    public MrController(MrSyncService mrSyncService, MrAdvanceService advanceService) {
        this.mrSyncService = mrSyncService;
        this.advanceService = advanceService;
    }

    /** 手工绑定/刷新 MR：解析 URL、拉一次 GitLab、落库。 */
    @PostMapping("/{id}/mr-sync")
    public MrViews.MrCard sync(@PathVariable long id, @RequestBody MrViews.SyncCmd cmd) {
        return mrSyncService.sync(id, cmd == null ? null : cmd.mrUrl());
    }

    /** 需求当前绑定的 MR 卡；没有则 200 + 空体（前端显示「未绑定」空态）。 */
    @GetMapping("/{id}/mr")
    public ResponseEntity<MrViews.MrCard> card(@PathVariable long id) {
        return ResponseEntity.ok(mrSyncService.card(id));
    }

    /**
     * 人工确认合并（{@code mrAutoAdvance=false} 时的唯一推进入口）。
     * 状态机不允许 → 409 {@code STATE_TRANSITION_REJECTED}，并已写 {@code MR_ADVANCE_REJECTED} 审计。
     */
    @PostMapping("/{id}/mr/confirm-merge")
    public MrViews.ConfirmResult confirmMerge(@PathVariable long id,
                                              @RequestBody(required = false) MrViews.ConfirmMergeCmd cmd) {
        return advanceService.confirmMerge(id, cmd == null ? null : cmd.note());
    }
}
