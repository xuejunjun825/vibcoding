package com.example.req.integration.store;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 一次 Jenkins 构建（表 {@code build_record}，见 V8）。
 *
 * <p>{@code active_key} 只在 QUEUED/RUNNING 时等于 {@code deploy_order_id}，终态置 NULL；
 * 唯一键 {@code uk_build_active(tenant_id, active_key)} 让「同一发布单不允许两个活动构建」
 * 由数据库保证，不需要内存锁，也不会因为多实例部署而失效。</p>
 *
 * <p>{@code status} 存 String：状态机在 Java（{@code BuildStateMachine}）表达，数据库只存结果。</p>
 */
@Entity
@Table(name = "build_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_build_active",
                columnNames = {"tenant_id", "active_key"}))
public class BuildRecordEntity {

    /** 终态：不再参与轮询，也不占用 active_key。 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILURE = "FAILURE";
    public static final String STATUS_ABORTED = "ABORTED";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "deploy_order_id", nullable = false)
    private Long deployOrderId;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "config_id", nullable = false)
    private Long configId;

    @Column(name = "job_name", nullable = false, length = 200)
    private String jobName;

    @Column(name = "build_number")
    private Long buildNumber;

    @Column(name = "queue_id")
    private Long queueId;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "active_key", length = 24)
    private String activeKey;

    @Column(name = "stages_json", columnDefinition = "TEXT")
    private String stagesJson;

    @Column(name = "console_url", length = 500)
    private String consoleUrl;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "timeout_at", nullable = false)
    private LocalDateTime timeoutAt;

    @Column(name = "poll_fail_count", nullable = false)
    private Integer pollFailCount = 0;

    @Column(name = "correlation_id", length = 40)
    private String correlationId;

    @Column(name = "trigger_by")
    private Long triggerBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (pollFailCount == null) {
            pollFailCount = 0;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** 是否仍在进行中（决定是否占用 active_key / 参与轮询）。 */
    public boolean isActive() {
        return STATUS_QUEUED.equals(status) || STATUS_RUNNING.equals(status);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getDeployOrderId() { return deployOrderId; }
    public void setDeployOrderId(Long deployOrderId) { this.deployOrderId = deployOrderId; }
    public Long getRequirementId() { return requirementId; }
    public void setRequirementId(Long requirementId) { this.requirementId = requirementId; }
    public Long getConfigId() { return configId; }
    public void setConfigId(Long configId) { this.configId = configId; }
    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }
    public Long getBuildNumber() { return buildNumber; }
    public void setBuildNumber(Long buildNumber) { this.buildNumber = buildNumber; }
    public Long getQueueId() { return queueId; }
    public void setQueueId(Long queueId) { this.queueId = queueId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getActiveKey() { return activeKey; }
    public void setActiveKey(String activeKey) { this.activeKey = activeKey; }
    public String getStagesJson() { return stagesJson; }
    public void setStagesJson(String stagesJson) { this.stagesJson = stagesJson; }
    public String getConsoleUrl() { return consoleUrl; }
    public void setConsoleUrl(String consoleUrl) { this.consoleUrl = consoleUrl; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public LocalDateTime getTimeoutAt() { return timeoutAt; }
    public void setTimeoutAt(LocalDateTime timeoutAt) { this.timeoutAt = timeoutAt; }
    public Integer getPollFailCount() { return pollFailCount; }
    public void setPollFailCount(Integer pollFailCount) { this.pollFailCount = pollFailCount; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public Long getTriggerBy() { return triggerBy; }
    public void setTriggerBy(Long triggerBy) { this.triggerBy = triggerBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
