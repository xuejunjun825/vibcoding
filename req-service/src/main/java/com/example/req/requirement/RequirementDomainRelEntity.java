package com.example.req.requirement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/** requirement_domain_rel：需求与授权域的多对多关系。 */
@Entity
@Table(name = "requirement_domain_rel")
@IdClass(RequirementDomainRelEntity.Key.class)
public class RequirementDomainRelEntity {

    @Id
    @Column(name = "requirement_id")
    private Long requirementId;

    @Id
    @Column(name = "domain_id")
    private Long domainId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    public RequirementDomainRelEntity() {
    }

    public RequirementDomainRelEntity(Long tenantId, Long requirementId, Long domainId) {
        this.tenantId = tenantId;
        this.requirementId = requirementId;
        this.domainId = domainId;
    }

    public Long getRequirementId() { return requirementId; }
    public Long getDomainId() { return domainId; }
    public Long getTenantId() { return tenantId; }

    public void setRequirementId(Long requirementId) { this.requirementId = requirementId; }
    public void setDomainId(Long domainId) { this.domainId = domainId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public static class Key implements Serializable {
        private Long requirementId;
        private Long domainId;

        public Key() {
        }

        public Key(Long requirementId, Long domainId) {
            this.requirementId = requirementId;
            this.domainId = domainId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(requirementId, key.requirementId)
                    && Objects.equals(domainId, key.domainId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(requirementId, domainId);
        }
    }
}
