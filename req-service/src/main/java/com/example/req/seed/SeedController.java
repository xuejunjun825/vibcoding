package com.example.req.seed;

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
 * 内部种子入口：{@code POST /internal/seed?scale=N}。
 *
 * <p>两条门：{@code X-Internal-Token} 必须匹配（网关不透传该路径之外的内部调用），
 * 且 {@code PLATFORM_SEED_ENABLED=true}——关闭时返回 404 而不是 403，
 * 避免把"生产环境没有这个接口"暴露成"有个接口但你无权访问"。</p>
 *
 * <p>幂等：第二次调用返回 {@code skipped=true} 且 counts 与第一次逐键相等（AC-19）。</p>
 */
@RestController
@RequestMapping("/internal")
public class SeedController {

    private final SeedService seedService;
    private final String internalToken;

    public SeedController(SeedService seedService,
                          @Value("${platform.internal-token:}") String internalToken) {
        this.seedService = seedService;
        this.internalToken = internalToken;
    }

    @PostMapping("/seed")
    public ResponseEntity<?> seed(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                  @RequestParam(value = "scale", defaultValue = "0") int scale) {
        if (!seedService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", 404, "code", "SEED_DISABLED",
                            "message", "种子接口未启用（PLATFORM_SEED_ENABLED=false）"));
        }
        if (internalToken != null && !internalToken.isBlank()
                && !internalToken.equals(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", 403, "code", "FORBIDDEN",
                            "message", "X-Internal-Token 不匹配"));
        }
        SeedResult result = seedService.seed(scale);
        return ResponseEntity.ok(Map.of(
                "seeded", result.isSeeded(),
                "skipped", result.isSkipped(),
                "counts", result.getCounts()));
    }
}
