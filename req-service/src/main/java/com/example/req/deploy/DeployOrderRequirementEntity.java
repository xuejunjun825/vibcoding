package com.example.req.deploy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * 发布单与需求的关联（N:M）。
 *
 * <p>关联清单在创建时落库（默认由版本带出，也允许创建时指定子集），
 * 之后准入逐项判定与"随单展示需求清单"都读这张表，避免运行期再按版本反查导致口径漂移。</p>
 */
@Entity
@Table(name = "deploy_order_requirement")
@IdClass(DeployOrderRequirementEntity.Key.class)
public class DeployOrderRequirementEntity {

    @Id
    @Column(name = "deploy_order_id", nullable = false)
    private Long deployOrderId;

    @Id
    @Column(name = "requirement_id", nullable = false)
    private Long requirementId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    public DeployOrderRequirementEntity() {
    }

    public DeployOrderRequirementEntity(Long tenantId, Long deployOrderId, Long requirementId) {
        this.tenantId = tenantId;
        this.deployOrderId = deployOrderId;
        this.requirementId = requirementId;
    }

    public Long getDeployOrderId() {
        return deployOrderId;
    }

    public void setDeployOrderId(Long deployOrderId) {
        this.deployOrderId = deployOrderId;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(Long requirementId) {
        this.requirementId = requirementId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    /** 复合主键。 */
    public static class Key implements Serializable {

        private Long deployOrderId;
        private Long requirementId;

        public Key() {
        }

        public Key(Long deployOrderId, Long requirementId) {
            this.deployOrderId = deployOrderId;
            this.requirementId = requirementId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(deployOrderId, key.deployOrderId)
                    && Objects.equals(requirementId, key.requirementId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(deployOrderId, requirementId);
        }
    }
}
