package com.example.req.release;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/** release_requirement：版本与需求的关联行（含三类需求）。 */
@Entity
@Table(name = "release_requirement")
@IdClass(ReleaseRequirementEntity.Key.class)
public class ReleaseRequirementEntity {

    @Id
    @Column(name = "release_id")
    private Long releaseId;

    @Id
    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "added_by")
    private Long addedBy;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    @PrePersist
    void prePersist() {
        if (addedAt == null) {
            addedAt = LocalDateTime.now();
        }
    }

    public Long getReleaseId() { return releaseId; }
    public void setReleaseId(Long releaseId) { this.releaseId = releaseId; }
    public Long getRequirementId() { return requirementId; }
    public void setRequirementId(Long requirementId) { this.requirementId = requirementId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getAddedBy() { return addedBy; }
    public void setAddedBy(Long addedBy) { this.addedBy = addedBy; }
    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }

    public static class Key implements Serializable {
        private Long releaseId;
        private Long requirementId;

        public Key() {
        }

        public Key(Long releaseId, Long requirementId) {
            this.releaseId = releaseId;
            this.requirementId = requirementId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(releaseId, key.releaseId)
                    && Objects.equals(requirementId, key.requirementId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(releaseId, requirementId);
        }
    }
}
