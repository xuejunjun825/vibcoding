package com.example.deliver.reports;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One saved report definition.
 *
 * <p>{@code definition_json} keeps the <b>wire</b> shape the editor posts ({@code columns},
 * {@code value}, {@code IN}) — see {@link ReportDefinitionCodec}. Storing the engine shape instead
 * would make GET return something the editor cannot round-trip, and the AC-7 comparison (save then
 * read back, deep-equal) would fail on field names alone.</p>
 *
 * <p>{@code active_flag} is a MySQL generated column and must not be mapped: Hibernate would try to
 * insert it. Soft delete is {@code status = 'DELETED'} only — the {@code TOMBSTONE} value mentioned
 * in the migration comment does not fit {@code VARCHAR(8)}.</p>
 */
@Entity
@Table(name = "report_definition")
public class ReportDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private long tenantId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "dataset", nullable = false, length = 16)
    private String dataset;

    @Column(name = "definition_json", nullable = false, columnDefinition = "JSON")
    private String definitionJson;

    @Column(name = "chart_type", length = 8)
    private String chartType;

    @Column(name = "owner_id", nullable = false)
    private long ownerId;

    @Column(name = "owner_role", length = 32)
    private String ownerRole;

    @Column(name = "scope", length = 8)
    private String scope;

    @Column(name = "scope_ref")
    private Long scopeRef;

    @Column(name = "status", length = 8)
    private String status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "version")
    private Integer version;

    public Long getId() {
        return id;
    }

    public long getTenantId() {
        return tenantId;
    }

    public void setTenantId(long tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDataset() {
        return dataset;
    }

    public void setDataset(String dataset) {
        this.dataset = dataset;
    }

    public String getDefinitionJson() {
        return definitionJson;
    }

    public void setDefinitionJson(String definitionJson) {
        this.definitionJson = definitionJson;
    }

    public String getChartType() {
        return chartType;
    }

    public void setChartType(String chartType) {
        this.chartType = chartType;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(long ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerRole() {
        return ownerRole;
    }

    public void setOwnerRole(String ownerRole) {
        this.ownerRole = ownerRole;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Long getScopeRef() {
        return scopeRef;
    }

    public void setScopeRef(Long scopeRef) {
        this.scopeRef = scopeRef;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** The column is written explicitly on every save: MySQL's ON UPDATE only fires when a row
     *  actually changes, and an idempotent share/pin upsert must still move the timestamp. */
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
}
