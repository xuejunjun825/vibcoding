package com.example.req.attachment;

import java.time.LocalDateTime;

import com.example.platform.common.scope.ScopeAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An uploaded file attached to a business object.
 *
 * <p>{@code domainId}/{@code vendorId} are <b>derived from the business object</b> at upload
 * time, never taken from the multipart form: otherwise a vendor could park a file in another
 * vendor's queue by editing a field. The list query goes through {@code ScopedJdbc} (the
 * {@code attachment} scope template carries both columns) and the by-id download through
 * {@code ScopeGuard}, so the fifth isolation entry of AC-4 is covered in both directions.</p>
 */
@Entity
@Table(name = "attachment")
public class AttachmentEntity implements ScopeAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "biz_type", nullable = false, length = 24)
    private String bizType;

    @Column(name = "biz_id", nullable = false)
    private Long bizId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "path", nullable = false, length = 500)
    private String path;

    @Column(name = "size")
    private Long size;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "uploader_id")
    private Long uploaderId;

    @Column(name = "domain_id")
    private Long domainId;

    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

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
        return uploaderId;
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

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public Long getBizId() {
        return bizId;
    }

    public void setBizId(Long bizId) {
        this.bizId = bizId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public Long getUploaderId() {
        return uploaderId;
    }

    public void setUploaderId(Long uploaderId) {
        this.uploaderId = uploaderId;
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
