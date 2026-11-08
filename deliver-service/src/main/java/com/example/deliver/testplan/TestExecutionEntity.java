package com.example.deliver.testplan;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One executed result. Manual and automatic reports share the table and are told apart by
 * {@code source}; automatic rows additionally carry {@code clientId/runId} (the DB CHECK enforces
 * that combination, so a manual row can never pretend to be an automated one).
 */
@Entity
@Table(name = "test_execution")
public class TestExecutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "case_version", nullable = false)
    private Integer caseVersion;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(nullable = false, length = 8)
    private String source;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(nullable = false, length = 12)
    private String result;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "log_url", length = 500)
    private String logUrl;

    @Column(name = "evidence_file", length = 300)
    private String evidenceFile;

    @Column(name = "executor_id")
    private Long executorId;

    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;

    @Column(name = "client_id")
    private Long clientId;

    @Column(length = 45)
    private String ip;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long caseId) {
        this.caseId = caseId;
    }

    public Integer getCaseVersion() {
        return caseVersion;
    }

    public void setCaseVersion(Integer caseVersion) {
        this.caseVersion = caseVersion;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(Long requirementId) {
        this.requirementId = requirementId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public String getLogUrl() {
        return logUrl;
    }

    public void setLogUrl(String logUrl) {
        this.logUrl = logUrl;
    }

    public String getEvidenceFile() {
        return evidenceFile;
    }

    public void setEvidenceFile(String evidenceFile) {
        this.evidenceFile = evidenceFile;
    }

    public Long getExecutorId() {
        return executorId;
    }

    public void setExecutorId(Long executorId) {
        this.executorId = executorId;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }

    public Long getClientId() {
        return clientId;
    }

    public void setClientId(Long clientId) {
        this.clientId = clientId;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }
}
