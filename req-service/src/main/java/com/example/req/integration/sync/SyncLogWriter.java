package com.example.req.integration.sync;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationConfigRepository;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.outbound.OutboundRequest;
import com.example.req.integration.outbound.OutboundResponse;
import com.example.req.integration.security.SensitiveMasker;
import com.example.req.integration.store.IntegrationSyncLogEntity;
import com.example.req.integration.store.IntegrationSyncLogRepository;

/**
 * {@code integration_sync_log} 的唯一写入口。
 *
 * <p>每次 HTTP 尝试写一行（{@code retry_count} 从 0 递增），字段长度与枚举在这里收口，
 * 摘要一律先过 {@link SensitiveMasker}。日志用 REQUIRES_NEW：业务事务回滚不应带走
 * 「调用失败」这条证据，反过来日志写失败也不能让业务失败。</p>
 */
@Component
public class SyncLogWriter {

    private static final Logger log = LoggerFactory.getLogger(SyncLogWriter.class);

    private static final int MAX_OPERATION = 24;
    private static final int MAX_METHOD = 8;
    private static final int MAX_URL = 500;
    private static final int MAX_ERROR = 40;
    private static final int MAX_BIZ_TYPE = 24;
    private static final int MAX_SUMMARY = 1000;

    private final IntegrationSyncLogRepository repository;
    private final IntegrationConfigRepository configRepository;

    public SyncLogWriter(IntegrationSyncLogRepository repository,
                         IntegrationConfigRepository configRepository) {
        this.repository = repository;
        this.configRepository = configRepository;
    }

    /** 出站尝试日志。{@code errorCode} 为空时取响应上的错误码。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(OutboundRequest request, OutboundResponse response, SyncResult result,
                      String errorCode) {
        if (request == null) {
            return;
        }
        try {
            int attempts = response == null ? 1 : Math.max(1, response.attempts());
            int retryCount = attempts - 1;
            String correlationId = normalizeCorrelation(request.correlationId());
            if (correlationId == null) {
                return;
            }
            if (alreadyLogged(request.tenantId(), correlationId, retryCount)) {
                return;
            }
            IntegrationSyncLogEntity row = new IntegrationSyncLogEntity();
            row.setTenantId(request.tenantId());
            row.setCorrelationId(SensitiveMasker.truncate(correlationId, 40));
            row.setConfigId(resolveConfigId(request));
            row.setSystem(request.system().name());
            row.setDirection(IntegrationSyncLogEntity.DIRECTION_OUT);
            row.setOperation(SensitiveMasker.truncate(request.operation(), MAX_OPERATION));
            row.setMethod(SensitiveMasker.truncate(
                    request.method() == null ? null : request.method().name(), MAX_METHOD));
            row.setTargetUrl(SensitiveMasker.truncate(SensitiveMasker.url(request.url()), MAX_URL));
            row.setRequestSummary(SensitiveMasker.truncate(
                    requestDigest(request), MAX_SUMMARY));
            if (response != null && response.httpStatus() > 0) {
                row.setHttpStatus(response.httpStatus());
            }
            row.setResponseSummary(response == null ? null
                    : SensitiveMasker.mask(response.body()));
            row.setResult((result == null ? SyncResult.FAILED : result).name());
            String code = errorCode != null ? errorCode : (response == null ? null : response.errorCode());
            row.setErrorCode(SensitiveMasker.truncate(code, MAX_ERROR));
            row.setRetryCount(retryCount);
            row.setDurationMs(response == null ? 0 : clampInt(response.durationMs()));
            row.setBizType(SensitiveMasker.truncate(request.bizType(), MAX_BIZ_TYPE));
            row.setBizId(request.bizId());
            repository.save(row);
        } catch (RuntimeException ex) {
            // 日志是证据链，不是主业务：写失败只记录，不吞掉调用方的真实结果。
            log.warn("integration_sync_log write failed: {}", ex.toString());
        }
    }

    /** 入站（webhook）日志：没有出站请求对象，摘要由调用方给。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeInbound(long tenantId, long configId, IntegrationSystem system,
                             String operation, String summary, int httpStatus,
                             SyncResult result, String errorCode) {
        writeInbound(tenantId, configId, system, operation, null, null, summary, httpStatus,
                result, errorCode);
    }

    /**
     * 入站日志（带业务锚点）。
     *
     * <p>{@code bizType}/{@code bizId} 是 AC-2.1「哪类集成、哪条业务对象」两列的唯一来源：
     * MR webhook 写 {@code MR}（载荷里只有 GitLab 的 mr_iid，没有本地行 id，故 bizId 为空），
     * 构建轮询失败写 {@code BUILD + build_record.id}，这样同步日志能从构建反查，而不是只有
     * 一行「某天某系统失败过」。业务锚点为空时两列就是 NULL（不写空串），与出站日志同一口径。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeInbound(long tenantId, long configId, IntegrationSystem system,
                             String operation, String bizType, Long bizId, String summary,
                             int httpStatus, SyncResult result, String errorCode) {
        try {
            IntegrationSyncLogEntity row = new IntegrationSyncLogEntity();
            row.setTenantId(tenantId);
            row.setCorrelationId(UUID.randomUUID().toString().replace("-", ""));
            row.setConfigId(configId > 0 ? configId : null);
            row.setSystem(system == null ? IntegrationSystem.GITLAB.name() : system.name());
            row.setDirection(IntegrationSyncLogEntity.DIRECTION_IN);
            row.setOperation(SensitiveMasker.truncate(operation, MAX_OPERATION));
            row.setTargetUrl(null);
            row.setRequestSummary(SensitiveMasker.truncate(
                    SensitiveMasker.mask(summary), MAX_SUMMARY));
            if (httpStatus > 0) {
                row.setHttpStatus(httpStatus);
            }
            row.setResult((result == null ? SyncResult.FAILED : result).name());
            row.setErrorCode(SensitiveMasker.truncate(errorCode, MAX_ERROR));
            row.setRetryCount(0);
            row.setDurationMs(0);
            row.setBizType(SensitiveMasker.truncate(bizType, MAX_BIZ_TYPE));
            row.setBizId(bizId);
            repository.save(row);
        } catch (RuntimeException ex) {
            log.warn("integration_sync_log inbound write failed: {}", ex.toString());
        }
    }

    private Long resolveConfigId(OutboundRequest request) {
        if (request.system() == null) {
            return null;
        }
        try {
            return configRepository
                    .findFirstByTenantIdAndSystemAndEnabledOrderByIdAsc(
                            request.tenantId(), request.system().name(), 1)
                    .map(IntegrationConfigEntity::getId)
                    .orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 同一 correlation_id + retry_count 只写一次（重放/重试编排重复调用时保持幂等）。 */
    private boolean alreadyLogged(long tenantId, String correlationId, int retryCount) {
        List<IntegrationSyncLogEntity> rows = repository.search(tenantId, null, null,
                correlationId, null, null, null, null, PageRequest.of(0, 50)).getContent();
        return rows.stream().anyMatch(row -> IntegrationSyncLogEntity.DIRECTION_OUT
                .equals(row.getDirection())
                && Integer.valueOf(retryCount).equals(row.getRetryCount()));
    }

    private static String normalizeCorrelation(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return null;
        }
        return correlationId.trim();
    }

    private static String requestDigest(OutboundRequest request) {
        String headers = SensitiveMasker.headers(request.headers());
        String body = request.body() == null ? "" : String.valueOf(request.body());
        String combined = headers.isEmpty() ? body : headers + " | " + body;
        return SensitiveMasker.mask(combined);
    }

    private static int clampInt(long value) {
        if (value <= 0) {
            return 0;
        }
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
