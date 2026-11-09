package com.example.req.integration.store;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** {@code mr_sync} 查询：webhook upsert 依据唯一键，卡片读取按需求取最新一条。 */
public interface MrSyncRepository extends JpaRepository<MrSyncEntity, Long> {

    Optional<MrSyncEntity> findByIdAndTenantId(Long id, Long tenantId);

    Optional<MrSyncEntity> findByTenantIdAndGitlabProjectAndMrIid(Long tenantId, String gitlabProject, Long mrIid);

    /** 需求详情「集成」tab 的代码卡：只展示最近一条 MR。 */
    Optional<MrSyncEntity> findFirstByTenantIdAndRequirementIdOrderByLastEventAtDesc(
            Long tenantId, Long requirementId);

    List<MrSyncEntity> findByTenantIdAndRequirementIdOrderByLastEventAtDesc(Long tenantId, Long requirementId);

    /** 兜底轮询：近 N 小时内有事件的 MR（webhook 丢失时状态仍能收敛）。 */
    List<MrSyncEntity> findByLastEventAtAfterOrderByLastEventAtAsc(LocalDateTime after);

    /** 需求侧回写后按需求批量取，供追溯/列表页避免 N+1。 */
    List<MrSyncEntity> findByTenantIdAndRequirementIdIn(Long tenantId, List<Long> requirementIds);
}
