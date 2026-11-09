package com.example.req.integration.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** {@code artifact_record} 查询：绑定取最新、晋级按 promoted_from_id 幂等判定。 */
public interface ArtifactRecordRepository extends JpaRepository<ArtifactRecordEntity, Long> {

    Optional<ArtifactRecordEntity> findByIdAndTenantId(Long id, Long tenantId);

    /** 发布单当前绑定制品（详情页与准入第 9 项读取）。 */
    Optional<ArtifactRecordEntity> findFirstByTenantIdAndDeployOrderIdOrderByIdDesc(Long tenantId, Long deployOrderId);

    /** 幂等依据：同坐标同摘要已登记则复用。 */
    Optional<ArtifactRecordEntity> findFirstByTenantIdAndRepoAndGroupIdAndArtifactNameAndVersionAndPackagingAndSha256(
            Long tenantId, String repo, String groupId, String artifactName, String version,
            String packaging, String sha256);

    /** 晋级幂等依据：同一源制品只允许晋级一次。 */
    Optional<ArtifactRecordEntity> findFirstByTenantIdAndPromotedFromId(Long tenantId, Long promotedFromId);

    List<ArtifactRecordEntity> findByTenantIdAndDeployOrderIdOrderByIdDesc(Long tenantId, Long deployOrderId);

    List<ArtifactRecordEntity> findByTenantIdAndSha256(Long tenantId, String sha256);
}
