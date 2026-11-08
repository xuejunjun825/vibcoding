package com.example.platform.common.notify;

import java.util.Map;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * In-app notification writer — the only place either service inserts into {@code notification}.
 *
 * <p>Notifications are best-effort by design: a failed insert must never abort the approval or
 * admission that triggered it. That is why the row is written in its own transaction and the
 * failure is logged instead of rethrown.</p>
 */
@Service
public class NotificationWriter {

    private static final Logger log = LoggerFactory.getLogger(NotificationWriter.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    public NotificationWriter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void send(long userId, String type, String title, String content,
                     String bizType, Long bizId) {
        if (userId <= 0) {
            log.debug("notification skipped: no recipient ({})", type);
            return;
        }
        if (!TenantContext.isSet()) {
            log.warn("notification skipped: no tenant context ({} -> {})", type, userId);
            return;
        }
        try {
            DataScope scope = TenantContext.require();
            requiresNew.executeWithoutResult(status -> jdbc.update(
                    "INSERT INTO notification (tenant_id, user_id, type, title, content,"
                            + " biz_type, biz_id, read_flag, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 0, NOW(3))",
                    scope.tenantId(), userId, type, title, content, bizType, bizId));
        } catch (RuntimeException ex) {
            log.warn("notification write failed ({} -> {}): {}", type, userId, ex.getMessage());
        }
    }

    /** Unread count for a user, used by the header badge. */
    public long unreadCount(long userId) {
        DataScope scope = TenantContext.require();
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE tenant_id = ? AND user_id = ? AND read_flag = 0",
                Long.class, scope.tenantId(), userId);
        return count == null ? 0L : count;
    }

    /** Detail payload kept small on purpose: the notification table is not an audit log. */
    public void send(long userId, String type, String title, String content, String bizType,
                     Long bizId, Map<String, Object> ignored) {
        send(userId, type, title, content, bizType, bizId);
    }
}
