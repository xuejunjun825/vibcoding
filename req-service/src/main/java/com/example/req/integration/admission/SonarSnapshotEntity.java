package com.example.req.integration.admission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code quality_gate_snapshot} 的只读投影：只看 {@code source='SONAR'} 的行（TECHNICAL §7.2 ⑧）。
 *
 * <p>为什么不复用 M6 的快照实体：req-service 里从来没有这张表的映射——M6 的快照由 deliver 写、
 * req 只存 {@code deploy_order.quality_gate_snapshot_id}。这里新增一个"只读、只取 Sonar 需要的列"
 * 的投影，避免为了读 5 个字段把整张宽表（含 {@code snapshot_json}）映射进来。</p>
 *
 * <p>租户过滤写在仓储方法名里（{@code findFirstByTenantIdAnd...}），与 TECHNICAL §9
 * "所有仓储方法首参 tenantId" 一致；准入门禁因此不需要在 SQL 里手拼隔离条件。</p>
 */
@Entity
@Table(name = "quality_gate_snapshot")
public class SonarSnapshotEntity {

    @Id
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "source")
    private String source;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "project_key")
    private String projectKey;

    @Column(name = "result")
    private String result;

    @Column(name = "metric_status")
    private String metricStatus;

    @Column(name = "metrics_json")
    private String metricsJson;

    @Column(name = "hash")
    private String hash;

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getSource() {
        return source;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getResult() {
        return result;
    }

    public String getMetricStatus() {
        return metricStatus;
    }

    public String getMetricsJson() {
        return metricsJson;
    }

    public String getHash() {
        return hash;
    }
}
