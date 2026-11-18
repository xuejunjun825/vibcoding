package com.example.req.requirement;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存量回填的唯一 HTTP 入口：{@code POST /internal/wbs-estimate-backfill}。
 *
 * <p>为什么复用 {@code /internal} 而不是另开一个进程：只有容器内才有
 * {@link com.example.platform.common.tenant.TenantContext} 租户绑定、{@code @Transactional}
 * 与 {@link com.example.req.approval.ApprovalEngine} 的回调链。独立进程只能 JDBC 直插
 * {@code wf_*}，那正是本项目明令禁止的写法。</p>
 *
 * <p>两道门：开关 {@code PLATFORM_OPS_BACKFILL_ENABLED}（默认 false → 404，不用 403 暴露
 * 「有个接口但你无权访问」）与 {@code X-Internal-Token}。开关刻意不复用
 * {@code PLATFORM_SEED_ENABLED}：生产 seed 必须长期关闭，而回填是短窗口操作。</p>
 *
 * <p>协议：参数合法就 <b>始终 200</b>，body 是完整报告（含 FAILED 行）。
 * 「有行失败」不是 HTTP 错误——批处理已经把失败收敛进每一行，脚本按退出码判红比拆 HTTP 状态
 * 更清楚。</p>
 */
@RestController
@RequestMapping("/internal")
public class WbsBackfillController {

    private final WbsBackfillService service;
    private final boolean enabled;
    private final String internalToken;

    public WbsBackfillController(WbsBackfillService service,
                                 @Value("${platform.ops.backfill.enabled:false}") boolean enabled,
                                 @Value("${platform.internal-token:}") String internalToken) {
        this.service = service;
        this.enabled = enabled;
        this.internalToken = internalToken;
    }

    @PostMapping("/wbs-estimate-backfill")
    public ResponseEntity<?> backfill(
            @RequestParam("tenant") String tenant,
            @RequestParam(value = "batchId", required = false) String batchId,
            @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", 404, "code", "BACKFILL_DISABLED",
                            "message", "回填接口未启用（PLATFORM_OPS_BACKFILL_ENABLED=false）"));
        }
        if (tenant == null || tenant.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", 400, "code", "USAGE",
                    "message", "tenant 必填：?tenant=<租户编码>"));
        }
        // 与 SeedController 同构的兜底：真实链路里 InternalTokenFilter 先一步返回 401，
        // 这段只在过滤器未覆盖该路径时生效（保留是为了不把令牌校验变成单点失效）。
        if (internalToken != null && !internalToken.isBlank() && !internalToken.equals(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", 403, "code", "FORBIDDEN",
                            "message", "X-Internal-Token 不匹配"));
        }
        return ResponseEntity.ok(service.run(
                new WbsBackfillViews.Command(tenant.trim(), batchId, dryRun)));
    }
}
