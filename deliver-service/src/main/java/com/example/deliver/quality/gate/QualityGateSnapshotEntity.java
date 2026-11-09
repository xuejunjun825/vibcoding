package com.example.deliver.quality.gate;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Frozen gate evaluation: the full rule set, the values it saw and the resulting verdict, all inside
 * {@code snapshotJson} plus a sha256 {@code hash} over that payload. Rows are only ever inserted — replay
 * re-reads the JSON instead of re-evaluating, which is what makes "the gate said GO at that moment"
 * reproducible after the data moved on.
 */
@Entity
@Table(name = "quality_gate_snapshot")
public class QualityGateSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "gate_id", nullable = false)
    private Long gateId;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    @Column(name = "deploy_order_id", nullable = false)
    private Long deployOrderId;

    @Column(name = "report_version_id")
    private Long reportVersionId;

    @Column(nullable = false, length = 20)
    private String result;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String snapshotJson;

    @Column(nullable = false, length = 64)
    private String hash;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @Column(name = "created_by")
    private Long createdBy;

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

    public Long getGateId() {
        return gateId;
    }

    public void setGateId(Long gateId) {
        this.gateId = gateId;
    }

    public Long getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(Long releaseId) {
        this.releaseId = releaseId;
    }

    public Long getDeployOrderId() {
        return deployOrderId;
    }

    public void setDeployOrderId(Long deployOrderId) {
        this.deployOrderId = deployOrderId;
    }

    public Long getReportVersionId() {
        return reportVersionId;
    }

    public void setReportVersionId(Long reportVersionId) {
        this.reportVersionId = reportVersionId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public LocalDateTime getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(LocalDateTime computedAt) {
        this.computedAt = computedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
