package com.example.deliver.openapi;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the call log in its own transaction.
 *
 * <p>A rejected call is exactly the call an operator needs in the log, and the request that
 * carried it is failing (401/403/422/429). {@code REQUIRES_NEW} keeps that evidence when the
 * business transaction rolls back — and a logging failure must never turn a 200 into a 500.</p>
 */
@Service
public class ApiCallLogService {

    private final ApiCallLogRepository repository;

    public ApiCallLogService(ApiCallLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long tenantId, Long clientId, String path, String method, int statusCode,
                       String resultCode, long durationMs, String ip, String requestId) {
        try {
            ApiCallLogEntity row = new ApiCallLogEntity();
            row.setTenantId(tenantId);
            row.setClientId(clientId);
            row.setPath(truncate(path, 200));
            row.setMethod(truncate(method, 8));
            row.setStatusCode(statusCode);
            row.setResultCode(truncate(resultCode, 48));
            row.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
            row.setIp(truncate(ip, 45));
            row.setRequestId(truncate(requestId, 64));
            row.setCreatedAt(LocalDateTime.now());
            repository.save(row);
        } catch (RuntimeException ignored) {
            // Auditing must not break the API; the call's own result is already decided.
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
