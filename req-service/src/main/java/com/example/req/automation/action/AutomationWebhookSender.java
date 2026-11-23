package com.example.req.automation.action;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.platform.contract.HmacSigner;
import com.example.req.automation.api.AutomationDtos.ActionOutcome;
import com.example.req.automation.event.AutomationEventRow;
import com.example.req.automation.rule.AutomationRuleEntity;
import com.example.req.automation.spec.AutomationActionType;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationConfigRepository;
import com.example.req.integration.config.IntegrationCrypto;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.outbound.OutboundHttpClient;
import com.example.req.integration.outbound.OutboundRequest;
import com.example.req.integration.outbound.OutboundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Signed webhook call.
 *
 * <p>Anti-SSRF is structural, not a check: the rule's JSON has no field that could hold a URL. The
 * only address is {@code integration_config.base_url} (published by an admin) plus a relative path,
 * and the secret never leaves the server. The request therefore cannot be pointed at an arbitrary
 * host by whoever writes the rule.</p>
 *
 * <p>Nothing in the outcome may contain the URL: {@code automation_run.actions_json} is rendered on
 * the log page and AC-12 forbids leaking endpoints or credentials there. The target is the host
 * only, the message is a status code.</p>
 */
@Component
public class AutomationWebhookSender {

    private static final Logger log = LoggerFactory.getLogger(AutomationWebhookSender.class);

    public static final String OPERATION = "AUTOMATION_WEBHOOK";

    private final IntegrationConfigRepository configs;
    private final IntegrationCrypto crypto;
    private final OutboundHttpClient client;
    private final ObjectMapper objectMapper;

    public AutomationWebhookSender(IntegrationConfigRepository configs, IntegrationCrypto crypto,
                                   OutboundHttpClient client, ObjectMapper objectMapper) {
        this.configs = configs;
        this.crypto = crypto;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public ActionOutcome send(long configId, String path, AutomationEventRow event,
                              Map<String, Object> snapshot) {
        return send(null, configId, path, event, snapshot);
    }

    public ActionOutcome send(AutomationRuleEntity rule, long configId, String path,
                              AutomationEventRow event, Map<String, Object> snapshot) {
        if (path == null || !path.startsWith("/") || path.contains("://")) {
            return ActionOutcomes.skipped(AutomationActionType.WEBHOOK.name(),
                    "TARGET_INVALID", null);
        }
        IntegrationConfigEntity config = configs.findByIdAndTenantId(configId, event.tenantId())
                .orElse(null);
        if (config == null || !config.isActive()) {
            return ActionOutcomes.skipped(AutomationActionType.WEBHOOK.name(),
                    "TARGET_INVALID", String.valueOf(configId));
        }
        String url = stripTrailingSlash(config.getBaseUrl()) + path;
        String host;
        String requestPath;
        try {
            URI target = URI.create(url);
            host = target.getHost();
            requestPath = target.getRawPath();
        } catch (RuntimeException ex) {
            return ActionOutcomes.failed(AutomationActionType.WEBHOOK.name(), "SSRF_BLOCKED",
                    null);
        }
        String bodyJson;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("eventId", event.eventId());
            body.put("eventType", event.eventType());
            body.put("ruleId", rule == null ? null : rule.getId());
            body.put("ruleVersion", rule == null ? null : rule.getVersion());
            body.put("requirementId", event.requirementId());
            body.put("requirementCode", string(snapshot, "code"));
            body.put("occurredAt", Instant.now().toString());
            bodyJson = objectMapper.writeValueAsString(body);
        } catch (RuntimeException ex) {
            return ActionOutcomes.failed(AutomationActionType.WEBHOOK.name(), "SCHEMA_INVALID",
                    null);
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        String appKey = config.getUsername() == null ? "" : config.getUsername();
        String secret = secretOf(config);
        byte[] bodyBytes = bodyJson.getBytes(StandardCharsets.UTF_8);
        String signature = HmacSigner.sign(secret, HmacSigner.canonical("POST", requestPath,
                appKey, timestamp, bodyBytes));

        OutboundRequest request = OutboundRequest.builder(event.tenantId(),
                        IntegrationSystem.require(config.getSystem()), OPERATION, HttpMethod.POST,
                        url)
                .body(bodyJson)
                .header("Content-Type", "application/json")
                .header("X-App-Key", appKey)
                .header("X-Timestamp", timestamp)
                .header("X-Signature", signature)
                .header("X-Event-Id", event.eventId())
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .correlationId(event.eventId())
                .biz("AUTOMATION", event.requirementId())
                .build();
        try {
            OutboundResponse response = client.exchangeWithRetry(request);
            if (response.successful()) {
                return ActionOutcomes.success(AutomationActionType.WEBHOOK.name(),
                        "HTTP " + response.httpStatus(), host);
            }
            String code = response.errorCode() == null ? "HTTP " + response.httpStatus()
                    : response.errorCode();
            return ActionOutcomes.failed(AutomationActionType.WEBHOOK.name(), code, host);
        } catch (RuntimeException ex) {
            log.warn("automation webhook failed: rule={} event={} error={}",
                    rule == null ? null : rule.getId(), event.eventId(), ex.getMessage());
            return ActionOutcomes.failed(AutomationActionType.WEBHOOK.name(), "TRANSPORT_ERROR",
                    host);
        }
    }

    private String secretOf(IntegrationConfigEntity config) {
        try {
            if (config.getSecretEnc() != null && config.getSecretEnc().length > 0) {
                return crypto.decode(config.getSecretEnc());
            }
            if (config.getCredentialEnc() != null && config.getCredentialEnc().length > 0) {
                return crypto.decode(config.getCredentialEnc());
            }
        } catch (RuntimeException ex) {
            log.warn("automation webhook secret undecodable: config={} error={}",
                    config.getId(), ex.getMessage());
        }
        return "";
    }

    private static String stripTrailingSlash(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String string(Map<String, Object> snapshot, String key) {
        Object value = snapshot == null ? null : snapshot.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
