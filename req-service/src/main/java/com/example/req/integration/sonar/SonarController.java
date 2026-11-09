package com.example.req.integration.sonar;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonar 集成的 HTTP 面（TECHNICAL §4.4 / AC-6.2）。
 *
 * <p>只做参数与状态码，判定全部在 {@link SonarSnapshotService}：刷新是"用户想马上看结论"
 * 的显式动作，读是"打开页面先看已有事实"。两者都不改准入结果——准入读的是快照本身。</p>
 */
@RestController
@RequestMapping("/requirements")
public class SonarController {

    private final SonarSnapshotService snapshotService;

    public SonarController(SonarSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    /** 手动刷新：拉 Sonar 并落快照。未启用 → 409；未绑定 key → 200 + 具体 SKIP 卡。 */
    @PostMapping("/{id}/sonar/refresh")
    public SonarViews.SonarCard refresh(@PathVariable long id) {
        return snapshotService.refresh(id);
    }

    /** 读最新快照：零外呼，永远 200（没结论也是一种结论，卡片自己带 reasonCode）。 */
    @GetMapping("/{id}/sonar")
    public ResponseEntity<SonarViews.SonarCard> latest(@PathVariable long id) {
        return ResponseEntity.ok(snapshotService.latest(id));
    }
}
