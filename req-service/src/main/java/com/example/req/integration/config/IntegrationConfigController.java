package com.example.req.integration.config;

import com.example.platform.common.scope.PageResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 集成配置与同步日志的 HTTP 面（TECHNICAL §4.1）。
 *
 * <p>网关路由：{@code /api/req/integration/**} → stripPrefix(2) → 本控制器。走 JWT 通道，
 * 配置接口不允许匿名（{@code JwtGatewayFilter} 只放行 webhook 那一条无 JWT 通道）。</p>
 *
 * <p>控制器只做参数与状态码：凭据语义、唯一启用、校验与审计全在
 * {@link IntegrationConfigService}——同一份语义若在这里再写一遍，两处迟早分叉。</p>
 */
@RestController
@RequestMapping("/integration")
public class IntegrationConfigController {

    private final IntegrationConfigService service;

    public IntegrationConfigController(IntegrationConfigService service) {
        this.service = service;
    }

    /** 配置清单：{@code system} 可省略（返回四个系统全部配置）。 */
    @GetMapping("/configs")
    public List<IntegrationConfigViews.ConfigView> list(
            @RequestParam(required = false) String system) {
        return service.list(system);
    }

    /** 新建配置：201 + 完整视图（前端直接覆盖该系统的槽位，不再整页 reload）。 */
    @PostMapping("/configs")
    public ResponseEntity<IntegrationConfigViews.ConfigView> create(
            @RequestBody(required = false) IntegrationConfigViews.ConfigCmd cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(cmd));
    }

    /**
     * 部分更新：{@code PATCH /integration/configs/{id}}。
     *
     * <p>与 {@code PUT} 的差别是语义而不是风格：只改给出的字段。前端的"启停开关"就发
     * {@code {"enabled":true}} 这一条最小请求，服务端不要求它把整份配置回传。</p>
     */
    @PatchMapping("/configs/{id}")
    public IntegrationConfigViews.ConfigView patch(
            @PathVariable long id,
            @RequestBody(required = false) IntegrationConfigViews.ConfigCmd cmd) {
        return service.update(id, cmd);
    }

    /** 删除配置：204；同步日志保留（历史证据不随配置删除消失）。 */
    @DeleteMapping("/configs/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 连通性测试：恒 200，失败也是结果（{@code ok=false} + code + message）。 */
    @PostMapping("/configs/{id}/test")
    public IntegrationConfigViews.TestResult test(@PathVariable long id) {
        return service.test(id);
    }

    /** 同步日志分页：{@code system/result/correlationId/from/to/keyword} 六个筛选都可省略。 */
    @GetMapping("/logs")
    public PageResponse<IntegrationConfigViews.LogRow> logs(
            @RequestParam(required = false) String system,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.logs(system, result, correlationId, from, to, keyword, page, size);
    }

    /** 单条同步日志明细：404 时返回 {@code INTEGRATION_SYNC_LOG_NOT_FOUND}。 */
    @GetMapping("/logs/{id}")
    public IntegrationConfigViews.LogRow log(@PathVariable long id) {
        return service.log(id);
    }
}
