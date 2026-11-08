package com.example.deliver.testplan;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per (plan, case). {@code caseVersion} is pinned when the case is added — that pin is
 * what lets an execution stay explainable after the case is edited.
 */
@Entity
@Table(name = "test_plan_case")
public class TestPlanCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "case_version", nullable = false)
    private Integer caseVersion;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "executor_id")
    private Long executorId;

    @Column(nullable = false, length = 12)
    private String result = "NOT_RUN";

    @Column(name = "execution_id")
    private Long executionId;

    @Column(name = "last_executed_at")
    private LocalDateTime lastExecutedAt;

    @Column(length = 500)
    private String note;

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

    public Long getExecutorId() {
        return executorId;
    }

    public void setExecutorId(Long executorId) {
        this.executorId = executorId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Long getExecutionId() {
        return executionId;
    }

    public void setExecutionId(Long executionId) {
        this.executionId = executionId;
    }

    public LocalDateTime getLastExecutedAt() {
        return lastExecutedAt;
    }

    public void setLastExecutedAt(LocalDateTime lastExecutedAt) {
        this.lastExecutedAt = lastExecutedAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
