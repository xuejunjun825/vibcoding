package com.example.req.integration.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.integration.outbound.OutboundRequest;
import com.example.req.integration.outbound.OutboundResponse;
import com.example.req.integration.security.SensitiveMasker;

/**
 * 集成域的站内告警：重试预算耗尽（AC-2.2）与后续适配器可复用的通用告警。
 *
 * <p>通知是旁路：没有租户上下文 / 没有操作人时 {@link NotificationWriter} 自己会跳过，
 * 这里再兜一层异常，避免告警失败反噬调用方。内容只放系统名、操作与错误码，绝不带凭据。</p>
 */
@Component
public class IntegrationNotifier {

    private static final Logger log = LoggerFactory.getLogger(IntegrationNotifier.class);

    public static final String TYPE_SYNC_FAILED = "INTEGRATION_SYNC_FAILED";
    public static final String BIZ_TYPE = "INTEGRATION";

    private final NotificationWriter notificationWriter;

    public IntegrationNotifier(NotificationWriter notificationWriter) {
        this.notificationWriter = notificationWriter;
    }

    /** 后台写把重试预算用尽：一条告警足够，不带请求体。 */
    public void retryExhausted(OutboundRequest request, OutboundResponse response) {
        retryExhausted(request, response, 0L);
    }

    /**
     * 异步线程里重试耗尽：{@code @Async} 不继承 ThreadLocal，调用方把发起人 id 显式带进来，
     * 告警才不会因为「没有 principal」被静默跳过（AC-2.2）。
     */
    public void retryExhausted(OutboundRequest request, OutboundResponse response, long actorUserId) {
        if (request == null) {
            return;
        }
        String system = request.system() == null ? "集成" : request.system().label();
        String title = system + " 调用失败（重试已用尽）";
        String content = "操作 " + request.operation()
                + " 连续失败 " + (response == null ? 0 : response.attempts()) + " 次"
                + (response == null || response.errorCode() == null ? "" : "，错误码 " + response.errorCode())
                + "。请检查凭据、目标地址与对方服务状态。";
        if (actorUserId > 0) {
            sendTo(actorUserId, title, content, request.bizId());
        } else {
            send(request.tenantId(), title, content, request.bizId());
        }
    }

    /** 通用告警入口：构建失败、webhook 丢失等场景复用。 */
    public void warn(long tenantId, String title, String content, Long bizId) {
        send(tenantId, title, content, bizId);
    }

    private void send(long tenantId, String title, String content, Long bizId) {
        long userId = TenantContext.isSet() ? TenantContext.require().userId() : 0L;
        if (userId <= 0) {
            log.debug("integration alert skipped (no principal): {}", title);
            return;
        }
        sendTo(userId, title, content, bizId);
    }

    private void sendTo(long userId, String title, String content, Long bizId) {
        if (userId <= 0) {
            return;
        }
        try {
            notificationWriter.send(userId, TYPE_SYNC_FAILED,
                    SensitiveMasker.truncate(title, 120),
                    SensitiveMasker.truncate(SensitiveMasker.mask(content), 500),
                    BIZ_TYPE, bizId);
        } catch (RuntimeException ex) {
            log.warn("integration alert failed: {}", ex.toString());
        }
    }
}
