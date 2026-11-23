package com.example.req.requirement.change;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 变更单行（V16 的 req_change）。
 *
 * <p>状态用普通 String 而不是枚举：值来自服务层的稳定常量（PENDING/APPROVED/REJECTED/
 * WITHDRAWN/APPLIED），与 wf_instance 的状态机解耦——审批引擎改状态不会牵动本表 schema。
 * before/after/impact 三份 JSON 以 String 保存，反序列化/序列化只在服务层做（实体不依赖
 * ObjectMapper，避免测试里要造 Spring 上下文）。</p>
 *
 * <p>不加 {@code @Version}：并发闸是 Repository 的条件更新（expected status → next），
 * 它比乐观锁更贴业务语义——「只有 PENDING 才能通过」「只有 APPROVED 才能应用」。</p>
 */
@Entity
@Table(name = "req_change")
public class RequirementChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "requirement_id", nullable = false)
    private Long requirementId;

    @Column(name = "change_type", nullable = false, length = 16)
    private String changeType;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "before_json", nullable = false, columnDefinition = "JSON")
    private String beforeJson;

    @Column(name = "after_json", nullable = false, columnDefinition = "JSON")
    private String afterJson;

    @Column(name = "impact_json", nullable = false, columnDefinition = "JSON")
    private String impactJson;

    @Column(name = "instance_id")
    private Long instanceId;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "applied_by")
    private Long appliedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

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

    public Long getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(Long requirementId) {
        this.requirementId = requirementId;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public void setBeforeJson(String beforeJson) {
        this.beforeJson = beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public void setAfterJson(String afterJson) {
        this.afterJson = afterJson;
    }

    public String getImpactJson() {
        return impactJson;
    }

    public void setImpactJson(String impactJson) {
        this.impactJson = impactJson;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public LocalDateTime getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(LocalDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }

    public Long getAppliedBy() {
        return appliedBy;
    }

    public void setAppliedBy(Long appliedBy) {
        this.appliedBy = appliedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
