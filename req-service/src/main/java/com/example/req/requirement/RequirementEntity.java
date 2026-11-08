package com.example.req.requirement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 需求（BUS/PDR/RD 三类共用一张表）。
 *
 * <p>字段与 V1__platform_schema.sql 的 requirement 表逐列对应；审计列由
 * {@link #prePersist()} / {@link #preUpdate()} 维护，避免依赖具体 BaseEntity 实现。</p>
 */
@Entity
@Table(name = "requirement")
public class RequirementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "code", nullable = false, length = 24)
    private String code;

    /** BUS / PDR / RD */
    @Column(name = "type", nullable = false, length = 8)
    private String type;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "domain_id", nullable = false)
    private Long domainId;

    @Column(name = "origin_org_id", nullable = false)
    private Long originOrgId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "owner_vendor_id")
    private Long ownerVendorId;

    @Column(name = "assignee_vendor_id")
    private Long assigneeVendorId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "priority", nullable = false, length = 4)
    private String priority;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "estimate_days", precision = 6, scale = 1)
    private BigDecimal estimateDays;

    @Column(name = "actual_days", precision = 6, scale = 1)
    private BigDecimal actualDays;

    @Column(name = "mr_url", length = 500)
    private String mrUrl;

    @Column(name = "release_id")
    private Long releaseId;

    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @Column(name = "plan_start_date")
    private LocalDate planStartDate;

    @Column(name = "plan_end_date")
    private LocalDate planEndDate;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "test_passed_execution_id")
    private Long testPassedExecutionId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (version == null) {
            version = 0;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Long getDomainId() { return domainId; }
    public void setDomainId(Long domainId) { this.domainId = domainId; }
    public Long getOriginOrgId() { return originOrgId; }
    public void setOriginOrgId(Long originOrgId) { this.originOrgId = originOrgId; }
    public Long getReporterId() { return reporterId; }
    public void setReporterId(Long reporterId) { this.reporterId = reporterId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public Long getOwnerVendorId() { return ownerVendorId; }
    public void setOwnerVendorId(Long ownerVendorId) { this.ownerVendorId = ownerVendorId; }
    public Long getAssigneeVendorId() { return assigneeVendorId; }
    public void setAssigneeVendorId(Long assigneeVendorId) { this.assigneeVendorId = assigneeVendorId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getEstimateDays() { return estimateDays; }
    public void setEstimateDays(BigDecimal estimateDays) { this.estimateDays = estimateDays; }
    public BigDecimal getActualDays() { return actualDays; }
    public void setActualDays(BigDecimal actualDays) { this.actualDays = actualDays; }
    public String getMrUrl() { return mrUrl; }
    public void setMrUrl(String mrUrl) { this.mrUrl = mrUrl; }
    public Long getReleaseId() { return releaseId; }
    public void setReleaseId(Long releaseId) { this.releaseId = releaseId; }
    public LocalDate getExpectedDeliveryDate() { return expectedDeliveryDate; }
    public void setExpectedDeliveryDate(LocalDate expectedDeliveryDate) { this.expectedDeliveryDate = expectedDeliveryDate; }
    public LocalDate getPlanStartDate() { return planStartDate; }
    public void setPlanStartDate(LocalDate planStartDate) { this.planStartDate = planStartDate; }
    public LocalDate getPlanEndDate() { return planEndDate; }
    public void setPlanEndDate(LocalDate planEndDate) { this.planEndDate = planEndDate; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public Long getTestPassedExecutionId() { return testPassedExecutionId; }
    public void setTestPassedExecutionId(Long testPassedExecutionId) { this.testPassedExecutionId = testPassedExecutionId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
