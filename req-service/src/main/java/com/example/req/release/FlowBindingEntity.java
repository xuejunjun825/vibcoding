package com.example.req.release;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * {@code wf_flow_binding} 的一行 = <b>一个等级槽当前指向哪条审批链</b>（r30 / AC-1 / AC-6）。
 *
 * <p>为什么绑定不写进 {@code wf_definition}：定义是「一条链的 JSON 事实」，同一条链可以被多个槽共用
 * （REQ_DEPLOY 三个等级共用「发布单审批-两级」）；把等级写进定义行就会把「链的内容」和
 * 「哪个槽用它」焊死，紧急单想换链只能复制一份 JSON，副本一多就出现两套门/两套签名口径。</p>
 *
 * <p>为什么删除行 = 恢复默认：默认值（{@link FlowChainSeedService#DEFAULTS}）是种子里的常量，
 * 而不是「抄一份默认 id 存进来」——后者会让默认值存在两处，种子升级后必然漂移。
 * 因此 {@code reset} 是 DELETE，解析时没有行就回退到默认名 → 任一 PUBLISHED → 409。</p>
 */
@Entity
@Table(name = "wf_flow_binding")
public class FlowBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "biz_type", nullable = false, length = 32)
    private String bizType;

    @Column(name = "release_level", nullable = false, length = 16)
    private String releaseLevel;

    @Column(name = "definition_id", nullable = false)
    private Long definitionId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getReleaseLevel() {
        return releaseLevel;
    }

    public void setReleaseLevel(String releaseLevel) {
        this.releaseLevel = releaseLevel;
    }

    public Long getDefinitionId() {
        return definitionId;
    }

    public void setDefinitionId(Long definitionId) {
        this.definitionId = definitionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
