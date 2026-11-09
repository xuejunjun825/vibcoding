package com.example.deliver.quality.plan;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One round of a plan. "Only one round may be in progress per plan" is a database fact, not a Java
 * one: {@code active} is 1 while open and NULL once closed, and the unique key (plan_id, active) lets
 * MySQL reject the second concurrent open instead of letting two rows race past a pre-check.
 *
 * <p>{@code caseScopeJson} freezes the case set of the round; exec counters are a close-time digest of
 * the executions whose {@code round_id} points here (round history is derived from test_execution,
 * which is why there is no 14th table).
 */
@Entity
@Table(name = "test_plan_round")
public class TestPlanRoundEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "round_type", nullable = false, length = 16)
    private String roundType;

    @Column(length = 200)
    private String name;

    @Column(name = "case_scope_json", columnDefinition = "MEDIUMTEXT")
    private String caseScopeJson;

    @Column(name = "executor_ids_json", length = 500)
    private String executorIdsJson;

    @Column(name = "start_at")
    private LocalDateTime startAt;

    @Column(name = "end_at")
    private LocalDateTime endAt;

    @Column(nullable = false, length = 12)
    private String status = "PLANNING";

    @Column(length = 12)
    private String conclusion;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "defect_ids_json", length = 500)
    private String defectIdsJson;

    @Column(name = "exec_total", nullable = false)
    private Integer execTotal = 0;

    @Column(name = "exec_passed", nullable = false)
    private Integer execPassed = 0;

    @Column(name = "exec_failed", nullable = false)
    private Integer execFailed = 0;

    @Column(name = "exec_blocked", nullable = false)
    private Integer execBlocked = 0;

    /** 1 while the round is open, NULL once closed — see the class comment. */
    @Column
    private Integer active;

    @Column(length = 1000)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

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

    public Integer getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Integer roundNo) {
        this.roundNo = roundNo;
    }

    public String getRoundType() {
        return roundType;
    }

    public void setRoundType(String roundType) {
        this.roundType = roundType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCaseScopeJson() {
        return caseScopeJson;
    }

    public void setCaseScopeJson(String caseScopeJson) {
        this.caseScopeJson = caseScopeJson;
    }

    public String getExecutorIdsJson() {
        return executorIdsJson;
    }

    public void setExecutorIdsJson(String executorIdsJson) {
        this.executorIdsJson = executorIdsJson;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public void setStartAt(LocalDateTime startAt) {
        this.startAt = startAt;
    }

    public LocalDateTime getEndAt() {
        return endAt;
    }

    public void setEndAt(LocalDateTime endAt) {
        this.endAt = endAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public Long getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(Long closedBy) {
        this.closedBy = closedBy;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public String getDefectIdsJson() {
        return defectIdsJson;
    }

    public void setDefectIdsJson(String defectIdsJson) {
        this.defectIdsJson = defectIdsJson;
    }

    public Integer getExecTotal() {
        return execTotal;
    }

    public void setExecTotal(Integer execTotal) {
        this.execTotal = execTotal;
    }

    public Integer getExecPassed() {
        return execPassed;
    }

    public void setExecPassed(Integer execPassed) {
        this.execPassed = execPassed;
    }

    public Integer getExecFailed() {
        return execFailed;
    }

    public void setExecFailed(Integer execFailed) {
        this.execFailed = execFailed;
    }

    public Integer getExecBlocked() {
        return execBlocked;
    }

    public void setExecBlocked(Integer execBlocked) {
        this.execBlocked = execBlocked;
    }

    public Integer getActive() {
        return active;
    }

    public void setActive(Integer active) {
        this.active = active;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
