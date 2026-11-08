package com.example.req.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.example.platform.common.tenant.TenantContext;

import java.time.Duration;

/**
 * 服务间调用的唯一出口：统一带 {@code X-Internal-Token}，并按调用点的预算限时。
 *
 * <p>超时是<b>预算的一部分</b>而不是保险丝：准入整体 500ms，批量门禁 250ms、缺陷计数 200ms，
 * 超时一律抛 {@link DeliverGateClient.DeliverUnavailable}，由上层判 FAIL（fail-closed）。</p>
 */
@Component
public class InternalTokenClient {

    public static final String TOKEN_HEADER = "X-Internal-Token";
    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final RestClient.Builder builder;
    private final String token;
    private final String deliverBaseUrl;

    public InternalTokenClient(RestClient.Builder builder,
                               @Value("${platform.internal.token:${INTERNAL_TOKEN:dev-internal-token}}") String token,
                               @Value("${platform.deliver.base-url:${DELIVER_BASE_URL:http://deliver-service}}")
                               String deliverBaseUrl) {
        this.builder = builder;
        this.token = token;
        this.deliverBaseUrl = deliverBaseUrl;
    }

    /** 面向 deliver-service 的客户端；{@code readTimeout} 由调用点按预算给出。 */
    public RestClient deliver(Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(120));
        factory.setReadTimeout(readTimeout);
        RestClient.Builder client = builder.clone()
                .baseUrl(deliverBaseUrl)
                .requestFactory(factory)
                .defaultHeader(TOKEN_HEADER, token);
        // 内部端口在 deliver 侧也要建立 TenantContext：带上当前请求的租户，
        // 否则 ScopedJdbc 会 fail-closed（TenantContext is not set）。
        if (TenantContext.isSet()) {
            client.defaultHeader(TENANT_HEADER, String.valueOf(TenantContext.require().tenantId()));
        }
        return client.build();
    }
}
