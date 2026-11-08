package com.example.req.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * {@code wf_definition} 的一行 = 某流程的<b>一个版本</b>（不是一份可反复编辑的流程）。
 *
 * <p>发布即固化：发布后该行 {@code status=PUBLISHED} 且不可编辑，修改只能新建 {@code version+1} 的
 * DRAFT 行。实例钉 {@code id + version}，因此 v2 发布不影响在途 v1（AC-8）。
 */
@Entity
@Table(name = "wf_definition")
public class WorkflowVersion {

    /** 草稿。 */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 已发布：可被新单据解析到，且不可编辑。 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 已停用：在途实例继续走，新单据不再解析到。 */
    public static final String STATUS_DISABLED = "DISABLED";

    /** 四类单据。 */
    public static final String BIZ_REQ_SUBMIT = "REQ_SUBMIT";
    public static final String BIZ_REQ_REVIEW = "REQ_REVIEW";
    public static final String BIZ_RELEASE = "RELEASE";
    public static final String BIZ_REQ_DEPLOY = "REQ_DEPLOY";

    /** 不限类型/域的哨兵值（唯一键要用非 NULL 值，故不用 NULL）。 */
    public static final String ANY_REQ_TYPE = "ANY";
    public static final long ANY_DOMAIN = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "biz_type", nullable = false, length = 32)
    private String bizType;

    @Column(name = "req_type", nullable = false, length = 32)
    private String reqType = ANY_REQ_TYPE;

    @Column(name = "domain_id", nullable = false)
    private Long domainId = ANY_DOMAIN;

    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "definition_json", nullable = false, columnDefinition = "json")
    private String definitionJson;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_DRAFT;

    @Column(name = "is_default", nullable = false)
    private Integer isDefault = 0;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "published_by")
    private Long publishedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getReqType() {
        return reqType;
    }

    public void setReqType(String reqType) {
        this.reqType = reqType;
    }

    public Long getDomainId() {
        return domainId;
    }

    public void setDomainId(Long domainId) {
        this.domainId = domainId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getDefinitionJson() {
        return definitionJson;
    }

    public void setDefinitionJson(String definitionJson) {
        this.definitionJson = definitionJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Integer isDefault) {
        this.isDefault = isDefault;
    }

    public boolean isDefault() {
        return isDefault != null && isDefault == 1;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Long getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(Long publishedBy) {
        this.publishedBy = publishedBy;
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
