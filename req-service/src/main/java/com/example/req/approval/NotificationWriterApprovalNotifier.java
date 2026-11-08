package com.example.req.approval;

import com.example.platform.common.notify.NotificationWriter;
import org.springframework.stereotype.Component;

/**
 * 生产通知实现：委托 platform-common 的 {@code NotificationWriter}。
 *
 * <p>通知失败只记不抛：审批是主业务，站内信写失败不应该让『已通过的审批』回滚。
 */
@Component
public class NotificationWriterApprovalNotifier implements ApprovalNotifier {

    private final NotificationWriter notificationWriter;

    public NotificationWriterApprovalNotifier(NotificationWriter notificationWriter) {
        this.notificationWriter = notificationWriter;
    }

    @Override
    public void notify(long userId, String type, String title, String content, String bizType, Long bizId) {
        try {
            notificationWriter.send(userId, type, title, content, bizType, bizId);
        } catch (RuntimeException ignored) {
            // 通知是旁路：不阻断审批动作。
        }
    }
}
