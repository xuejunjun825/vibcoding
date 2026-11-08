package com.example.req.approval;

/**
 * 站内通知（SPI）。审批域所有通知都经此接口，生产实现只允许落到
 * {@code NotificationWriter}（团队硬约束：服务内不得自己 INSERT notification）。
 *
 * <p>通知是「尽力而为」：调用方在事务内发出，通知实现异常不应阻断审批动作。
 */
public interface ApprovalNotifier {

    void notify(long userId, String type, String title, String content, String bizType, Long bizId);
}
