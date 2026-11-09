package com.example.req.integration.store;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 制品登记（表 {@code artifact_record}，见 V8）。
 *
 * <p>{@code sha256} 参与唯一键：同一坐标 + 同一摘要重复登记是幂等的，而摘要不同则是
 * 另一个制品（晋级校验据此判定 {@code CHECKSUM_MISMATCH}）。晋级产生的新行用
 * {@code promoted_from_id} 指回源制品，唯一键 {@code uk_promoted} 保证同一次晋级不会
 * 被并发执行两次。</p>
 */
@Entity
@Table(name = "artifact_record",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_artifact",
                        columnNames = {"tenant_id", "repo", "group_id", "artifact_name",
                                "version", "packaging", "sha256"}),
                @UniqueConstraint(name = "uk_promoted", columnNames = {"promoted_from_id"})
        })
public class ArtifactRecordEntity {

    public static final String SOURCE_NEXUS = "NEXUS";
    public static final String SOURCE_MANUAL = "MANUAL";

    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_PROMOTED = "PROMOTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "config_id")
    private Long configId;

    @Column(name = "repo", nullable = false, length = 64)
    private String repo;

    @Column(name = "group_id", nullable = false, length = 128)
    private String groupId;

    @Column(name = "artifact_name", nullable = false, length = 128)
    private String artifactName;

    @Column(name = "version", nullable = false, length = 64)
    private String version;

    @Column(name = "packaging", nullable = false, length = 16)
    private String packaging = "jar";

    @Column(name = "classifier", length = 32)
    private String classifier;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "download_url", length = 500)
    private String downloadUrl;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "source", nullable = false, length = 8)
    private String source;

    @Column(name = "status", nullable = false, length = 12)
    private String status = STATUS_REGISTERED;

    @Column(name = "promoted_from_id")
    private Long promotedFromId;

    @Column(name = "promoted_at")
    private LocalDateTime promotedAt;

    @Column(name = "promoted_by")
    private Long promotedBy;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "deploy_order_id")
    private Long deployOrderId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (packaging == null || packaging.isBlank()) {
            packaging = "jar";
        }
        if (status == null || status.isBlank()) {
            status = STATUS_REGISTERED;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getConfigId() { return configId; }
    public void setConfigId(Long configId) { this.configId = configId; }
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getArtifactName() { return artifactName; }
    public void setArtifactName(String artifactName) { this.artifactName = artifactName; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getPackaging() { return packaging; }
    public void setPackaging(String packaging) { this.packaging = packaging; }
    public String getClassifier() { return classifier; }
    public void setClassifier(String classifier) { this.classifier = classifier; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getPromotedFromId() { return promotedFromId; }
    public void setPromotedFromId(Long promotedFromId) { this.promotedFromId = promotedFromId; }
    public LocalDateTime getPromotedAt() { return promotedAt; }
    public void setPromotedAt(LocalDateTime promotedAt) { this.promotedAt = promotedAt; }
    public Long getPromotedBy() { return promotedBy; }
    public void setPromotedBy(Long promotedBy) { this.promotedBy = promotedBy; }
    public Long getRequirementId() { return requirementId; }
    public void setRequirementId(Long requirementId) { this.requirementId = requirementId; }
    public Long getDeployOrderId() { return deployOrderId; }
    public void setDeployOrderId(Long deployOrderId) { this.deployOrderId = deployOrderId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}
