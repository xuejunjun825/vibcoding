package com.example.deliver.testplan;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A test plan is always anchored to a release; {@code gateMode} null means "inherit the release". */
@Entity
@Table(name = "test_plan")
public class TestPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "window_start")
    private LocalDateTime windowStart;

    @Column(name = "window_end")
    private LocalDateTime windowEnd;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 12)
    private String status = "DRAFT";

    @Column(name = "gate_mode", length = 8)
    private String gateMode;

    @Column(name = "strategy_id")
    private Long strategyId;

    @Column(name = "env_id")
    private Long envId;

    @Column(name = "domain_id")
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

    /** AC-15: 测试数据说明正文。锁定由服务层按状态判定，不靠前端置灰。 */
    @Column(name = "data_note", columnDefinition = "TEXT")
    private String dataNote;

    /** 说明正文的乐观锁版本；默认 0，避免 JPA 插入往 NOT NULL 列写 null。 */
    @Column(name = "revision", nullable = false)
    private Integer revision = 0;

    public String getDataNote() {
        return dataNote;
    }

    public void setDataNote(String dataNote) {
        this.dataNote = dataNote;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
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

    public Long getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(Long releaseId) {
        this.releaseId = releaseId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getGateMode() {
        return gateMode;
    }

    public void setGateMode(String gateMode) {
        this.gateMode = gateMode;
    }

    /** AC-9: 计划绑定的测试策略（V5 增加 strategy_id，null = 未绑定）。 */
    public Long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(Long strategyId) {
        this.strategyId = strategyId;
    }

    /** AC-9: 计划执行环境（V5 增加 env_id）；执行登记不必再旁路查该列。 */
    public Long getEnvId() {
        return envId;
    }

    public void setEnvId(Long envId) {
        this.envId = envId;
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
