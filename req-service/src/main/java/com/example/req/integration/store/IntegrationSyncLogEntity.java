package com.example.req.integration.store;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 一次出站/入站交互的审计行（表 {@code integration_sync_log}，见 V8）。
 *
 * <p>写入只经 {@code SyncLogWriter}，字段格式（摘要脱敏、结果枚举、重试次数）也只在那里
 * 决定；这张表是 AC-2.1「每次尝试都有记录」与 AC-10「凭据 0 命中」的唯一证据来源。</p>
 */
@Entity
@Table(name = "integration_sync_log")
public class IntegrationSyncLogEntity {

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_DEGRADED = "DEGRADED";
    public static final String RESULT_FAILED = "FAILED";
    public static final String RESULT_RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    public static final String RESULT_IGNORED = "IGNORED";

    public static final String DIRECTION_OUT = "OUT";
    public static final String DIRECTION_IN = "IN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "correlation_id", nullable = false, length = 40)
    private String correlationId;

    @Column(name = "config_id")
    private Long configId;

    // `system` 是 MySQL 保留字，列名必须带反引号（与 V8 的 DDL 一致）。
    @Column(name = "`system`", nullable = false, length = 16)
    private String system;

    @Column(name = "direction", nullable = false, length = 4)
    private String direction;

    @Column(name = "operation", nullable = false, length = 24)
    private String operation;

    @Column(name = "method", length = 8)
    private String method;

    @Column(name = "target_url", length = 500)
    private String targetUrl;

    @Column(name = "request_summary", length = 1000)
    private String requestSummary;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_summary", length = 1000)
    private String responseSummary;

    @Column(name = "result", nullable = false, length = 16)
    private String result;

    @Column(name = "error_code", length = 40)
    private String errorCode;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "duration_ms", nullable = false)
    private Integer durationMs = 0;

    @Column(name = "biz_type", length = 24)
    private String bizType;

    @Column(name = "biz_id")
    private Long bizId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (retryCount == null) {
            retryCount = 0;
        }
        if (durationMs == null) {
            durationMs = 0;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public Long getConfigId() { return configId; }
    public void setConfigId(Long configId) { this.configId = configId; }
    public String getSystem() { return system; }
    public void setSystem(String system) { this.system = system; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
    public String getRequestSummary() { return requestSummary; }
    public void setRequestSummary(String requestSummary) { this.requestSummary = requestSummary; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public Integer getResponseStatus() { return responseStatus; }
    public void setResponseStatus(Integer responseStatus) { this.responseStatus = responseStatus; }
    public String getResponseSummary() { return responseSummary; }
    public void setResponseSummary(String responseSummary) { this.responseSummary = responseSummary; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
