package com.example.deliver.testcase;

import java.time.LocalDateTime;

import com.example.platform.common.scope.ScopeAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A case always shows the newest content; the published history lives in
 * {@link TestCaseVersionEntity} + {@link TestCaseStepEntity} rows keyed by version, so an
 * execution detail can be re-rendered exactly as it was executed.
 */
@Entity
@Table(name = "test_case")
public class TestCaseEntity implements ScopeAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 24)
    private String code;

    @Column(name = "module_id")
    private Long moduleId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 8)
    private String type;

    @Column(nullable = false, length = 4)
    private String priority;

    @Column(name = "precondition", columnDefinition = "TEXT")
    private String precondition;

    @Column(length = 200)
    private String tags;

    /** Stored as TINYINT(1); Integer avoids a dialect-dependent boolean mapping under validate. */
    @Column(nullable = false)
    private Integer automated = 0;

    @Column(name = "script_ref", length = 300)
    private String scriptRef;

    @Column(nullable = false, length = 12)
    private String status = "ACTIVE";

    @Column(name = "current_version", nullable = false)
    private Integer currentVersion = 1;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "domain_id")
    private Long domainId;

    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Version
    @Column(nullable = false)
    private Integer version;

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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Long getModuleId() {
        return moduleId;
    }

    public void setModuleId(Long moduleId) {
        this.moduleId = moduleId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getPrecondition() {
        return precondition;
    }

    public void setPrecondition(String precondition) {
        this.precondition = precondition;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public Integer getAutomated() {
        return automated;
    }

    public void setAutomated(Integer automated) {
        this.automated = automated;
    }

    public String getScriptRef() {
        return scriptRef;
    }

    public void setScriptRef(String scriptRef) {
        this.scriptRef = scriptRef;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(Integer currentVersion) {
        this.currentVersion = currentVersion;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(Long requirementId) {
        this.requirementId = requirementId;
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

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    // ---- ScopeAware: the by-id read is guarded by the same dimensions as the list query ----

    @Override
    public long tenantId() {
        return tenantId == null ? 0L : tenantId;
    }

    @Override
    public Long domainId() {
        return domainId;
    }

    @Override
    public Long vendorId() {
        return vendorId;
    }

    @Override
    public Long ownerUserId() {
        return createdBy;
    }
}
