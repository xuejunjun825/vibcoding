package com.example.req.deploy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

/**
 * 发布单：一个版本在一个环境的一次发布。
 *
 * <p>状态机见 {@link DeployOrderStates}；准入结论以 {@code admission_result_json} 快照留档，
 * 强制放行的原因/操作者/时间独立成列，执行结果（含服务端算出的耗时）也落本行——
 * 发布统计因此可以从单据明细逐单复算，不需要另建汇总表。</p>
 */
@Entity
@Table(name = "deploy_order")
public class DeployOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "code", nullable = false, length = 24)
    private String code;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    /** TEST / UAT / PROD */
    @Column(name = "env", nullable = false, length = 16)
    private String env;

    @Column(name = "window_start")
    private LocalDateTime windowStart;

    @Column(name = "window_end")
    private LocalDateTime windowEnd;

    @Column(name = "artifact_version", length = 64)
    private String artifactVersion;

    /** 发布步骤 JSON：{@code [{"name":"停止应用","order":1}]}。 */
    @Column(name = "steps")
    private String steps;

    @Column(name = "rollback_plan")
    private String rollbackPlan;

    @Column(name = "impact")
    private String impact;

    @Column(name = "owner_id")
    private Long ownerId;

    /** 见 {@link DeployOrderStates} 的常量。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 准入快照：{@code {items:[],checkedAt:"",signature:"",gate:{...}}}（M6 起含第 6 项质量门证据）。 */
    @Column(name = "admission_result_json")
    private String admissionResultJson;

    /**
     * M6 追加的三列（V5 迁移，只增不改）：提交通过时固化的质量门定位信息。
     *
     * <p>{@code qualityGateSnapshotId} 指向不可变快照（AC-7 回放用），{@code qualityGateId}/{@code reportId}
     * 是当时那一份门禁配置与报告；M6 之前的历史单据三列恒为 NULL，视图按 legacy 展示，绝不伪造 PASS。</p>
     */
    @Column(name = "quality_gate_id")
    private Long qualityGateId;

    @Column(name = "quality_gate_snapshot_id")
    private Long qualityGateSnapshotId;

    @Column(name = "report_id")
    private Long reportId;

    /** 最近一次构建记录（build_record.id）；无构建时为空，准入第 7 项据此读取本地事实。 */
    @Column(name = "build_id")
    private Long buildId;

    /** 绑定的制品记录（artifact_record.id）；未登记时为空，准入第 9 项据此读取。 */
    @Column(name = "artifact_id")
    private Long artifactId;

    @Column(name = "forced_release", nullable = false)
    private boolean forcedRelease;

    @Column(name = "force_release_reason")
    private String forceReleaseReason;

    @Column(name = "force_by")
    private Long forceBy;

    @Column(name = "force_at")
    private LocalDateTime forceAt;

    /** SUCCESS / FAILED / ROLLED_BACK */
    @Column(name = "execution_result", length = 16)
    private String executionResult;

    @Column(name = "execution_note")
    private String executionNote;

    @Column(name = "defect_id")
    private Long defectId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /** 服务端按 startedAt/endedAt 计算（分钟，向上取整），不接受客户端传值。 */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

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
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = DeployOrderStates.DRAFT;
        }
        if (steps == null) {
            steps = "[]";
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Long getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(Long releaseId) {
        this.releaseId = releaseId;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public LocalDateTime getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(LocalDateTime windowStart) {
        this.windowStart = windowStart;
    }

    public LocalDateTime getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(LocalDateTime windowEnd) {
        this.windowEnd = windowEnd;
    }

    public String getArtifactVersion() {
        return artifactVersion;
    }

    public void setArtifactVersion(String artifactVersion) {
        this.artifactVersion = artifactVersion;
    }

    public String getSteps() {
        return steps;
    }

    public void setSteps(String steps) {
        this.steps = steps;
    }

    public String getRollbackPlan() {
        return rollbackPlan;
    }

    public void setRollbackPlan(String rollbackPlan) {
        this.rollbackPlan = rollbackPlan;
    }

    public String getImpact() {
        return impact;
    }

    public void setImpact(String impact) {
        this.impact = impact;
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

    public String getAdmissionResultJson() {
        return admissionResultJson;
    }

    public void setAdmissionResultJson(String admissionResultJson) {
        this.admissionResultJson = admissionResultJson;
    }

    public Long getQualityGateId() {
        return qualityGateId;
    }

    public void setQualityGateId(Long qualityGateId) {
        this.qualityGateId = qualityGateId;
    }

    public Long getQualityGateSnapshotId() {
        return qualityGateSnapshotId;
    }

    public void setQualityGateSnapshotId(Long qualityGateSnapshotId) {
        this.qualityGateSnapshotId = qualityGateSnapshotId;
    }

    public Long getReportId() {
        return reportId;
    }

    public void setReportId(Long reportId) {
        this.reportId = reportId;
    }

    public Long getBuildId() {
        return buildId;
    }

    public void setBuildId(Long buildId) {
        this.buildId = buildId;
    }

    public Long getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(Long artifactId) {
        this.artifactId = artifactId;
    }

    public boolean isForcedRelease() {
        return forcedRelease;
    }

    public void setForcedRelease(boolean forcedRelease) {
        this.forcedRelease = forcedRelease;
    }

    public String getForceReleaseReason() {
        return forceReleaseReason;
    }

    public void setForceReleaseReason(String forceReleaseReason) {
        this.forceReleaseReason = forceReleaseReason;
    }

    public Long getForceBy() {
        return forceBy;
    }

    public void setForceBy(Long forceBy) {
        this.forceBy = forceBy;
    }

    public LocalDateTime getForceAt() {
        return forceAt;
    }

    public void setForceAt(LocalDateTime forceAt) {
        this.forceAt = forceAt;
    }

    public String getExecutionResult() {
        return executionResult;
    }

    public void setExecutionResult(String executionResult) {
        this.executionResult = executionResult;
    }

    public String getExecutionNote() {
        return executionNote;
    }

    public void setExecutionNote(String executionNote) {
        this.executionNote = executionNote;
    }

    public Long getDefectId() {
        return defectId;
    }

    public void setDefectId(Long defectId) {
        this.defectId = defectId;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
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
}
