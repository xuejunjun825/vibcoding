package com.example.platform.common.audit;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Audit trail writer.
 *
 * <p>Two transaction modes because the callers differ: a business action rolls back together with
 * its audit row ({@link #record}), while a rejected request (403 / permission denial) has no
 * business transaction yet must still leave evidence — that one runs in {@code REQUIRES_NEW}
 * ({@link #recordInNewTx}) so it survives the caller's rollback.</p>
 *
 * <p>r39 起审计同时是自动化事件 outbox 的**唯一来源**：{@code INSERT} 之后在同一事务、同一线程里
 * 回调 {@link AuditListener}（见 {@link #fireListeners}）。因此业务回滚则事件不存在，前端也没有
 * 任何上报入口。</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final List<AuditListener> listeners;
    private final TransactionTemplate requiresNew;

    public AuditService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager,
                        List<AuditListener> listeners) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Spring wiring: {@link ObjectProvider} resolves to an empty stream when no module contributes
     * a listener (deliver-service has none), where a {@code List} injection point would have to
     * rely on empty-collection resolution.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AuditService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager,
                        ObjectProvider<AuditListener> listeners) {
        this(jdbc, objectMapper, transactionManager, listeners.orderedStream().toList());
    }

    /** Same transaction as the caller: audit and business row succeed or fail together. */
    public void record(String action, String bizType, Long bizId, Map<String, Object> detail) {
        Long auditId = insert(action, bizType, bizId, detail);
        fireListeners(auditId, action, bizType, bizId, detail);
    }

    /** Independent transaction: used by 403 / rate-limit paths that have no business tx. */
    public void recordInNewTx(String action, String bizType, Long bizId, Map<String, Object> detail) {
        if (!TenantContext.isSet()) {
            log.warn("audit skipped (no tenant context): {} {} {}", action, bizType, bizId);
            return;
        }
        Long[] auditId = new Long[1];
        try {
            requiresNew.executeWithoutResult(status -> {
                auditId[0] = insert(action, bizType, bizId, detail);
                // Same callback inside the REQUIRES_NEW tx: a denied request produces no
                // automation event either, because the mapper has no PERMISSION_DENY row.
                fireListeners(auditId[0], action, bizType, bizId, detail);
            });
        } catch (RuntimeException ex) {
            // Never let evidence-keeping turn a deliberate 403 into a 500.
            log.warn("audit write failed: {} {} {}", action, bizType, bizId, ex);
        }
    }

    /**
     * Publishes the audit action to every listener. The payload is a copy with {@code auditId}
     * added: event ids are {@code ...:<audit_log.id>}, so two identical business actions must not
     * collapse into one event. Best-effort by design — a listener failure is logged, never thrown,
     * because audit rides on the business transaction.
     */
    private void fireListeners(Long auditId, String action, String bizType, Long bizId,
                               Map<String, Object> detail) {
        if (listeners.isEmpty()) {
            return;
        }
        Map<String, Object> payload = detail == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(detail);
        if (auditId != null) {
            payload.put("auditId", auditId);
        }
        for (AuditListener listener : listeners) {
            try {
                listener.onAudit(action, bizType, bizId, payload);
            } catch (RuntimeException ex) {
                log.warn("audit listener failed: {} {} {} ({})", action, bizType, bizId,
                        listener.getClass().getSimpleName(), ex);
            }
        }
    }

    /** @return the new {@code audit_log.id}, or {@code null} when the driver returned no key. */
    private Long insert(String action, String bizType, Long bizId, Map<String, Object> detail) {
        if (!TenantContext.isSet()) {
            throw new IllegalStateException("audit requires a tenant context");
        }
        DataScope scope = TenantContext.require();
        TenantContext.Principal principal = TenantContext.principal();
        // Never mix Long and long in one ternary: the conditional's result type becomes long and
        // a null branch is unboxed -> NPE on internal (no JWT) requests. Branch explicitly.
        Long userId;
        if (principal != null) {
            userId = principal.userId();
        } else if (scope.userId() > 0) {
            userId = scope.userId();
        } else {
            userId = null;
        }
        String detailJson = json(detail);
        String ip = ip();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO audit_log (tenant_id, user_id, action, biz_type, biz_id,"
                            + " detail_json, ip, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, NOW(3))",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, scope.tenantId());
            if (userId == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, userId);
            }
            ps.setString(3, action);
            ps.setString(4, bizType);
            if (bizId == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, bizId);
            }
            ps.setString(6, detailJson);
            ps.setString(7, ip);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            // Audit id is the event-id suffix; without it two identical actions would dedupe into
            // one automation event. Keep the business path alive, but make the loss visible.
            log.warn("audit id unavailable: {} {} {}", action, bizType, bizId);
            return null;
        }
        return key.longValue();
    }

    private String json(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (RuntimeException ex) {
            return "{\"detail\":\"unserialisable\"}";
        }
    }

    private String ip() {
        try {
            if (RequestContextHolder.getRequestAttributes()
                    instanceof ServletRequestAttributes attributes) {
                HttpServletRequest request = attributes.getRequest();
                String forwarded = request.getHeader("X-Forwarded-For");
                if (forwarded != null && !forwarded.isBlank()) {
                    return forwarded.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (RuntimeException ex) {
            log.debug("ip unavailable: {}", ex.getMessage());
        }
        return null;
    }
}
