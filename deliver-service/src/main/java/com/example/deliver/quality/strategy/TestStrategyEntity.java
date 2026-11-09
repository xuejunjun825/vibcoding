package com.example.deliver.quality.strategy;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A test strategy is versioned per release: at most one row is ACTIVE, older rows stay readable so
 * "which strategy was in force" survives an edit. Scope/risk/round-plan are opaque JSON snapshots —
 * the strategy never re-reads live case data, the plan does that.
 */
@Entity
@Table(name = "test_strategy")
public class TestStrategyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "scope_json", columnDefinition = "TEXT")
    private String scopeJson;

    @Column(name = "risk_json", columnDefinition = "TEXT")
    private String riskJson;

    @Column(name = "env_ids_json", length = 500)
    private String envIdsJson;

    @Column(name = "round_plan_json", columnDefinition = "TEXT")
    private String roundPlanJson;

    @Column(name = "resource_note", length = 500)
    private String resourceNote;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 12)
    private String status = "DRAFT";

    @Column(name = "domain_id", nullable = false)
    private Long domainId;

    @Column(name = "vendor_id")
    private Long vendorId;

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

    public Long getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(Long releaseId) {
        this.releaseId = releaseId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getScopeJson() {
        return scopeJson;
    }

    public void setScopeJson(String scopeJson) {
        this.scopeJson = scopeJson;
    }

    public String getRiskJson() {
        return riskJson;
    }

    public void setRiskJson(String riskJson) {
        this.riskJson = riskJson;
    }

    public String getEnvIdsJson() {
        return envIdsJson;
    }

    public void setEnvIdsJson(String envIdsJson) {
        this.envIdsJson = envIdsJson;
    }

    public String getRoundPlanJson() {
        return roundPlanJson;
    }

    public void setRoundPlanJson(String roundPlanJson) {
        this.roundPlanJson = roundPlanJson;
    }

    public String getResourceNote() {
        return resourceNote;
    }

    public void setResourceNote(String resourceNote) {
        this.resourceNote = resourceNote;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getDomainId() {
        return domainId;
    }

    public void setDomainId(Long domainId) {
        this.domainId = domainId;
    }

    public Long getVendorId() {
        return vendorId;
    }

    public void setVendorId(Long vendorId) {
        this.vendorId = vendorId;
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
