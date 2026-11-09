package com.example.req.release;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 版本（Release）：需求排期的容器，PLAN → LOCKED → PUBLISHED。 */
@Entity
@Table(name = "`release`")
public class ReleaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "code", nullable = false, length = 24)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "domain_id", nullable = false)
    private Long domainId;

    /** PLAN / LOCKED / PUBLISHED / CANCELLED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "planned_release_date", nullable = false)
    private LocalDate plannedReleaseDate;

    /** STRICT / LOOSE */
    @Column(name = "gate_mode", nullable = false, length = 8)
    private String gateMode;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    /**
     * 发布后观察窗小时数（24–168，默认 72）。缺陷逃逸判定以"最早成功发布结束时间 + 本值"为窗口终点；
     * 只影响之后新建的缺陷，历史缺陷的 {@code escape_window_end} 一经落库不再随配置变化（AC-8.3）。
     */
    @Column(name = "observation_hours", nullable = false)
    private Integer observationHours = 72;

    /** 测试报告有效期天数（1–30，默认 7）：报告新鲜度与门禁 QG-02 读取，超期即视为无有效报告。 */
    @Column(name = "report_validity_days", nullable = false)
    private Integer reportValidityDays = 7;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getDomainId() { return domainId; }
    public void setDomainId(Long domainId) { this.domainId = domainId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public LocalDate getPlannedReleaseDate() { return plannedReleaseDate; }
    public void setPlannedReleaseDate(LocalDate plannedReleaseDate) { this.plannedReleaseDate = plannedReleaseDate; }
    public String getGateMode() { return gateMode; }
    public void setGateMode(String gateMode) { this.gateMode = gateMode; }
    public LocalDateTime getLockedAt() { return lockedAt; }
    public void setLockedAt(LocalDateTime lockedAt) { this.lockedAt = lockedAt; }
    public LocalDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(LocalDateTime releasedAt) { this.releasedAt = releasedAt; }
    public Integer getObservationHours() { return observationHours; }
    public void setObservationHours(Integer observationHours) { this.observationHours = observationHours; }
    public Integer getReportValidityDays() { return reportValidityDays; }
    public void setReportValidityDays(Integer reportValidityDays) { this.reportValidityDays = reportValidityDays; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
