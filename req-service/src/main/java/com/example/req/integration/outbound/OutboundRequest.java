package com.example.req.integration.outbound;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpMethod;

import com.example.req.integration.config.IntegrationSystem;

/**
 * 一次出站调用的不可变描述。
 *
 * <p>四适配器只负责把「系统协议」翻译成本记录（URL / 头 / body），SSRF、超时、重试、
 * 脱敏与日志全部由 {@link OutboundHttpClient} 统一实现。{@code connectTimeout}/
 * {@code readTimeout} 为 {@code null} 时取 {@code platform.integration.*} 默认值；
 * {@code retry} 只被 {@link OutboundHttpClient#exchangeWithRetry(OutboundRequest)} 使用。</p>
 *
 * @param tenantId       租户；日志行的 tenant_id 与配置解析都靠它
 * @param system         目标系统（JENKINS/GITLAB/SONAR/NEXUS），日志行的 system 列
 * @param operation      操作名（如 BUILD_TRIGGER / MR_SYNC / SONAR_SCAN），≤24 字符
 * @param method         HTTP 方法
 * @param url            绝对 URL；query 中的 token 会在写日志前被剔除
 * @param headers        请求头；敏感头的值只在日志里被脱敏，实际发送不受影响
 * @param body           请求体；String 原样发送，其余用 Jackson 序列化成 JSON
 * @param connectTimeout 连接超时；客户端会夹到 ≤2s（AC-2.4）
 * @param readTimeout    读超时；默认取配置的 read-timeout-ms
 * @param retry          后台写的重试策略；交互读由 exchange() 强制单次
 * @param correlationId  同一次业务动作的关联 id；为空时客户端生成一个并贯穿全部尝试
 * @param bizType        业务类型（BUILD / MR / SONAR / ARTIFACT …），仅供日志筛选
 * @param bizId          业务主键（build_record.id / requirement.id …）
 */
public record OutboundRequest(long tenantId,
                              IntegrationSystem system,
                              String operation,
                              HttpMethod method,
                              String url,
                              Map<String, String> headers,
                              Object body,
                              Duration connectTimeout,
                              Duration readTimeout,
                              RetryPolicy retry,
                              String correlationId,
                              String bizType,
                              Long bizId) {

    public OutboundRequest {
        if (system == null) {
            throw new IllegalArgumentException("system is required");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation is required");
        }
        if (method == null) {
            throw new IllegalArgumentException("method is required");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** 32 位十六进制关联 id（列宽 40，UUID 去横线正好 32）。 */
    public static String newCorrelationId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public OutboundRequest withCorrelationId(String id) {
        return new OutboundRequest(tenantId, system, operation, method, url, headers, body,
                connectTimeout, readTimeout, retry, id, bizType, bizId);
    }

    public OutboundRequest withRetry(RetryPolicy policy) {
        return new OutboundRequest(tenantId, system, operation, method, url, headers, body,
                connectTimeout, readTimeout, policy, correlationId, bizType, bizId);
    }

    public OutboundRequest withBody(Object newBody) {
        return new OutboundRequest(tenantId, system, operation, method, url, headers, newBody,
                connectTimeout, readTimeout, retry, correlationId, bizType, bizId);
    }

    public static Builder builder(long tenantId, IntegrationSystem system, String operation,
                                  HttpMethod method, String url) {
        return new Builder(tenantId, system, operation, method, url);
    }

    /** 便捷构造：GET 交互读（单次）。 */
    public static OutboundRequest get(long tenantId, IntegrationSystem system, String operation,
                                      String url, String correlationId) {
        return builder(tenantId, system, operation, HttpMethod.GET, url)
                .correlationId(correlationId)
                .build();
    }

    /** 便捷构造：POST 后台写（标准重试）。 */
    public static OutboundRequest post(long tenantId, IntegrationSystem system, String operation,
                                       String url, Object body, String correlationId) {
        return builder(tenantId, system, operation, HttpMethod.POST, url)
                .body(body)
                .correlationId(correlationId)
                .retry(RetryPolicy.standard())
                .build();
    }

    /** 可变构建器；适配器用它拼协议字段，避免 13 参数位置错配。 */
    public static final class Builder {
        private final long tenantId;
        private final IntegrationSystem system;
        private final String operation;
        private final HttpMethod method;
        private final String url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Object body;
        private Duration connectTimeout;
        private Duration readTimeout;
        private RetryPolicy retry;
        private String correlationId;
        private String bizType;
        private Long bizId;

        private Builder(long tenantId, IntegrationSystem system, String operation,
                        HttpMethod method, String url) {
            this.tenantId = tenantId;
            this.system = system;
            this.operation = operation;
            this.method = method;
            this.url = url;
        }

        public Builder header(String name, String value) {
            if (name != null && value != null) {
                headers.put(name, value);
            }
            return this;
        }

        public Builder headers(Map<String, String> values) {
            if (values != null) {
                values.forEach(this::header);
            }
            return this;
        }

        public Builder body(Object value) {
            this.body = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            this.connectTimeout = value;
            return this;
        }

        public Builder readTimeout(Duration value) {
            this.readTimeout = value;
            return this;
        }

        public Builder readTimeoutMillis(Integer millis) {
            this.readTimeout = millis == null || millis <= 0 ? null : Duration.ofMillis(millis);
            return this;
        }

        public Builder retry(RetryPolicy value) {
            this.retry = value;
            return this;
        }

        public Builder correlationId(String value) {
            this.correlationId = value;
            return this;
        }

        public Builder biz(String type, Long id) {
            this.bizType = type;
            this.bizId = id;
            return this;
        }

        public OutboundRequest build() {
            return new OutboundRequest(tenantId, system, operation, method, url,
                    Map.copyOf(headers), body, connectTimeout, readTimeout, retry,
                    correlationId, bizType, bizId);
        }
    }
}
