package com.example.req.integration.jenkins;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 构建的 HTTP 面：只做「参数 → 服务调用 → 状态码」。
 *
 * <p>202 与 409 的区别故意<b>不</b>在这里判断：是否已排队、是否已有活动构建，都是业务事实，
 * 由 {@link BuildService} 决定，这里只负责把 202 这个「已接受、未完成」的语义如实返回。
 * 控制器里写 if，等于把同一份规则实现两次。</p>
 */
@RestController
public class BuildController {

    private final BuildService buildService;

    public BuildController(BuildService buildService) {
        this.buildService = buildService;
    }

    /** 触发构建（AC-3.2）：202 + QUEUED，立刻返回，不在 HTTP 线程上等 Jenkins。 */
    @PostMapping("/deploy-orders/{deployOrderId}/builds")
    public ResponseEntity<BuildViews.TriggerResult> trigger(
            @PathVariable long deployOrderId,
            @RequestBody(required = false) BuildViews.TriggerCmd cmd) {
        return ResponseEntity.accepted()
                .body(buildService.trigger(deployOrderId, cmd));
    }

    /** 最近 N 条构建（默认 5，最多 20），倒序：构建卡只关心「现在这条 + 刚过去的几条」。 */
    @GetMapping("/deploy-orders/{deployOrderId}/builds")
    public BuildViews.BuildList list(@PathVariable long deployOrderId,
                                     @RequestParam(defaultValue = "5") int limit) {
        return buildService.list(deployOrderId, limit);
    }

    @GetMapping("/builds/{buildId}")
    public BuildViews.BuildView get(@PathVariable long buildId) {
        return buildService.get(buildId);
    }

    /** 手动刷新：读不到新事实就返回现有状态（不 5xx），前端据此只提示「暂不可用」。 */
    @PostMapping("/builds/{buildId}/refresh")
    public BuildViews.BuildView refresh(@PathVariable long buildId) {
        return buildService.refresh(buildId);
    }
}
