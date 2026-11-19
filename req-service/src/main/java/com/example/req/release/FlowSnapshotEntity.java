package com.example.req.release;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * {@code deploy_flow_snapshot} 的一行 = <b>某张发布单某一轮提交时固化下来的链路事实</b>
 * （r30 / AC-10 / AC-11）。
 *
 * <p>为什么这张表<b>没有 {@code updated_at} / {@code updated_by}</b>：快照是「提交那一刻的回执」，
 * 它的价值全部来自不可变性——一旦允许更新，"提交后 hash 逐字节不变"就退化成一句口头承诺。
 * 把可变性从 schema 层否掉之后，{@link FlowSnapshotRepository} 也就不需要、不允许提供 update/delete 方法
 * （AC-11 的静态审计点：本类与仓库里不存在任何改行路径）。</p>
 *
 * <p>轮次连续性不靠应用层加锁：{@code uk_flow_snapshot_round (tenant_id, deploy_order_id, round_no)}
 * 是物理保证，并发双提交时第二条插入直接撞键回滚整个 submit，而不是悄悄覆盖第 N 轮。</p>
 */
@Entity
@Table(name = "deploy_flow_snapshot")
public class FlowSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "deploy_order_id", nullable = false)
    private Long deployOrderId;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    /** 同一张单据内从 1 开始连续递增（唯一键兜底，不在应用层猜）。 */
    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "release_level", nullable = false, length = 16)
    private String releaseLevel;

    @Column(name = "definition_id", nullable = false)
    private Long definitionId;

    @Column(name = "definition_version", nullable = false)
    private Integer definitionVersion;

    /** 当轮实际会走的节点 key 序列（逗号分隔）——让"这轮走的是哪条链"能被 SQL 直接看到。 */
    @Column(name = "node_keys", nullable = false, length = 512)
    private String nodeKeys;

    /** 四段链路 + 元数据的规范化 JSON（回放内容就是它，不做二次序列化回写）。 */
    @Column(name = "payload_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String payloadJson;

    /** {@code payload_json} 的 SHA-256 全小写 hex（AC-10 逐字节不变的可比较指纹）。 */
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

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

    public Long getDeployOrderId() {
        return deployOrderId;
    }

    public void setDeployOrderId(Long deployOrderId) {
        this.deployOrderId = deployOrderId;
    }

    public Long getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(Long releaseId) {
        this.releaseId = releaseId;
    }

    public Integer getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Integer roundNo) {
        this.roundNo = roundNo;
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

    public Integer getDefinitionVersion() {
        return definitionVersion;
    }

    public void setDefinitionVersion(Integer definitionVersion) {
        this.definitionVersion = definitionVersion;
    }

    public String getNodeKeys() {
        return nodeKeys;
    }

    public void setNodeKeys(String nodeKeys) {
        this.nodeKeys = nodeKeys;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public LocalDateTime getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(LocalDateTime capturedAt) {
        this.capturedAt = capturedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
