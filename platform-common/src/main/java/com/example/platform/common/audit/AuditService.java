package com.example.platform.common.audit;

import java.util.Map;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public AuditService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Same transaction as the caller: audit and business row succeed or fail together. */
    public void record(String action, String bizType, Long bizId, Map<String, Object> detail) {
        insert(action, bizType, bizId, detail);
    }

    /** Independent transaction: used by 403 / rate-limit paths that have no business tx. */
    public void recordInNewTx(String action, String bizType, Long bizId, Map<String, Object> detail) {
        if (!TenantContext.isSet()) {
            log.warn("audit skipped (no tenant context): {} {} {}", action, bizType, bizId);
            return;
        }
        try {
            requiresNew.executeWithoutResult(status -> insert(action, bizType, bizId, detail));
        } catch (RuntimeException ex) {
            // Never let evidence-keeping turn a deliberate 403 into a 500.
            log.warn("audit write failed: {} {} {}", action, bizType, bizId, ex);
        }
    }

    private void insert(String action, String bizType, Long bizId, Map<String, Object> detail) {
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
        jdbc.update("INSERT INTO audit_log (tenant_id, user_id, action, biz_type, biz_id,"
                        + " detail_json, ip, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(3))",
                scope.tenantId(), userId, action, bizType, bizId, json(detail), ip());
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
