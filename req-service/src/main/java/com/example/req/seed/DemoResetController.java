package com.example.req.seed;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部演示数据重置入口：{@code POST /internal/demo-reset}。
 *
 * <p>两条门与 {@link SeedController} 完全一致：{@code X-Internal-Token} 必须匹配，
 * 且 {@code PLATFORM_SEED_ENABLED=true}——关闭时返回 404 而不是 403，避免把
 * 「生产环境没有这个接口」暴露成「有个接口但你无权访问」。演示能力因此不会出现在业务面，
 * 也不会被 {@code make test} 之类的自动门禁意外触发（AC-14）。</p>
 *
 * <p>失败语义：服务层抛出的 409（不变量不满足 / 种子缺失）由全局异常处理器渲染，
 * 事务已整体回滚——脚本据此非 0 退出并打印原因（AC-13）。</p>
 */
@RestController
@RequestMapping("/internal")
public class DemoResetController {

    private final DemoResetService service;
    private final String internalToken;

    public DemoResetController(DemoResetService service,
                               @Value("${platform.internal-token:}") String internalToken) {
        this.service = service;
        this.internalToken = internalToken;
    }

    @PostMapping("/demo-reset")
    public ResponseEntity<?> reset(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!service.isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", 404, "code", "SEED_DISABLED",
                            "message", "种子接口未启用（PLATFORM_SEED_ENABLED=false）"));
        }
        if (internalToken != null && !internalToken.isBlank() && !internalToken.equals(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", 403, "code", "FORBIDDEN",
                            "message", "X-Internal-Token 不匹配"));
        }
        Map<String, Long> counts = service.reset();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", service.tenantCode());
        body.put("counts", counts);
        body.put("resetAt", service.resetAt());
        return ResponseEntity.ok(body);
    }
}
