package com.example.deliver.defect;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A defect row.
 *
 * <p>{@code domainId} and {@code vendorId} are <b>derived</b> from the requirement the defect
 * belongs to — never taken from the request body. Accepting them from the client would let a
 * vendor file a defect into another vendor's queue by editing JSON (AC-13), so the service
 * overwrites both on every create and the fields here are only ever written by the service.</p>
 */
@Entity
@Table(name = "defect")
public class DefectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private long tenantId;

    @Column(name = "code", nullable = false, length = 24)
    private String code;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "release_id")
    private Long releaseId;

    @Column(name = "test_case_id")
    private Long testCaseId;

    @Column(name = "test_case_version")
    private Integer testCaseVersion;

    @Column(name = "test_execution_id")
    private Long testExecutionId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "repro_steps", columnDefinition = "TEXT")
    private String reproSteps;

    @Column(name = "severity", nullable = false, length = 4)
    private String severity;

    @Column(name = "priority", nullable = false, length = 4)
    private String priority;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "reporter_id", nullable = false)
    private long reporterId;

    @Column(name = "handler_id")
    private Long handlerId;

    @Column(name = "domain_id", nullable = false)
    private long domainId;

    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;

    @Column(name = "expected_fix_date")
    private LocalDate expectedFixDate;

    @Column(name = "found_at", nullable = false)
    private LocalDateTime foundAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /**
     * 缺陷来源：{@code MANUAL}（人工提报）/ {@code TEST}（执行失败转入）/ {@code PROD}（生产逃逸提报）。
     * 只有 PROD 参与逃逸判定；缺省 MANUAL 与迁移回填一致。
     */
    @Column(name = "source", nullable = false, length = 8)
    private String source;

    /** 发现阶段（自由文本，≤16 字）：DEV/TEST/UAT/PROD 等，用于版本质量按阶段切片。 */
    @Column(name = "found_phase", length = 16)
    private String foundPhase;

    /**
     * 是否发生在发布后观察窗内（0/1）。<b>只在 INSERT 时判定一次</b>：窗口终点同时固化到
     * {@link #escapeWindowEnd}，之后改 {@code release.observation_hours} 不会改写历史（AC-8.3）。
     */
    @Column(name = "is_escape", nullable = false)
    private Integer isEscape = 0;

    /** 判定时使用的观察窗终点；未判为逃逸或没有成功发布时为空。 */
    @Column(name = "escape_window_end")
    private LocalDateTime escapeWindowEnd;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Version
    @Column(name = "version", nullable = false)
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(Long requirementId) {
        this.requirementId = requirementId;
    }

    public Long getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(Long releaseId) {
        this.releaseId = releaseId;
    }

    public Long getTestCaseId() {
        return testCaseId;
    }

    public void setTestCaseId(Long testCaseId) {
        this.testCaseId = testCaseId;
    }

    public Integer getTestCaseVersion() {
        return testCaseVersion;
    }

    public void setTestCaseVersion(Integer testCaseVersion) {
        this.testCaseVersion = testCaseVersion;
    }

    public Long getTestExecutionId() {
        return testExecutionId;
    }

    public void setTestExecutionId(Long testExecutionId) {
        this.testExecutionId = testExecutionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getReproSteps() {
        return reproSteps;
    }

    public void setReproSteps(String reproSteps) {
        this.reproSteps = reproSteps;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getReporterId() {
        return reporterId;
    }

    public void setReporterId(long reporterId) {
        this.reporterId = reporterId;
    }

    public Long getHandlerId() {
        return handlerId;
    }

    public void setHandlerId(Long handlerId) {
        this.handlerId = handlerId;
    }

    public long getDomainId() {
        return domainId;
    }

    public void setDomainId(long domainId) {
        this.domainId = domainId;
    }

    public Long getVendorId() {
        return vendorId;
    }

    public void setVendorId(Long vendorId) {
        this.vendorId = vendorId;
    }

    public int getReopenCount() {
        return reopenCount;
    }

    public void setReopenCount(int reopenCount) {
        this.reopenCount = reopenCount;
    }

    public LocalDate getExpectedFixDate() {
        return expectedFixDate;
    }

    public void setExpectedFixDate(LocalDate expectedFixDate) {
        this.expectedFixDate = expectedFixDate;
    }

    public LocalDateTime getFoundAt() {
        return foundAt;
    }

    public void setFoundAt(LocalDateTime foundAt) {
        this.foundAt = foundAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getFoundPhase() {
        return foundPhase;
    }

    public void setFoundPhase(String foundPhase) {
        this.foundPhase = foundPhase;
    }

    public Integer getIsEscape() {
        return isEscape;
    }

    public void setIsEscape(Integer isEscape) {
        this.isEscape = isEscape;
    }

    public LocalDateTime getEscapeWindowEnd() {
        return escapeWindowEnd;
    }

    public void setEscapeWindowEnd(LocalDateTime escapeWindowEnd) {
        this.escapeWindowEnd = escapeWindowEnd;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
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
}
