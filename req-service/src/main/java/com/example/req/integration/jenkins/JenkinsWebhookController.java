package com.example.req.integration.jenkins;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jenkins 回调入口：{@code POST /integration/webhooks/jenkins/{configId}}（AC-3.4）。
 *
 * <p>这条路径在网关上是<b>唯一</b>免 JWT 的集成入口（{@code JwtGatewayFilter} 的匿名前缀），
 * 所以它的安全完全靠三件事，顺序不能换：限流（无 JWT 端点必须限流）→ HMAC 验签
 * （{@code X-Jenkins-Signature} + {@code X-Jenkins-Timestamp} ±300s）→ 状态机。
 * 验签失败在业务写之前 401，配置类接口仍然要 JWT。</p>
 *
 * <p>响应体的 {@code status} 是给<b>机器</b>看的（DUPLICATE / OUT_OF_ORDER / IGNORED / UPDATED）：
 * Jenkins 与验收脚本都靠它判断「这次投递有没有用」。因此绝不放 {@code ignored:true} 这类
 * 布尔捷径——jq 的 {@code .ignored // .status} 会取到 true，把语义吃掉。</p>
 */
@RestController
@RequestMapping("/integration/webhooks")
public class JenkinsWebhookController {

    private final BuildService buildService;

    public JenkinsWebhookController(BuildService buildService) {
        this.buildService = buildService;
    }

    @PostMapping(value = "/jenkins/{configId}", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Map<String, Object>> onJenkins(
            @PathVariable long configId,
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader Map<String, String> headers) {
        BuildService.WebhookOutcome outcome =
                buildService.onWebhook(configId, rawBody == null ? new byte[0] : rawBody, headers);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", outcome.status());
        if (outcome.reason() != null) {
            body.put("reason", outcome.reason());
        }
        if (outcome.buildId() != null) {
            body.put("buildId", outcome.buildId());
        }
        return ResponseEntity.ok(body);
    }
}
