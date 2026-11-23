package com.example.req.requirement.change;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 变更证据行（apply 成功后才有数据）。 */
public interface RequirementChangeItemRepository
        extends JpaRepository<RequirementChangeItemEntity, RequirementChangeItemEntity.Key> {

    List<RequirementChangeItemEntity> findByChangeIdOrderByFieldAsc(long changeId);

    long countByChangeId(long changeId);
}
