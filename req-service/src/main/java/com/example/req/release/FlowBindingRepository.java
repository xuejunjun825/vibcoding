package com.example.req.release;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 绑定槽读取（r30 / AC-1 / AC-6）：按 {@code (tenant, bizType, level)} 唯一键取一行，或一次取全租户。
 *
 * <p>查询条件就是唯一键，所以「一个槽同时指向两条链」在仓储层就不可能表达；
 * 6 条槽的完整视图由 {@link FlowBindingService#slots(long)} 拼接默认值得到。</p>
 */
public interface FlowBindingRepository extends JpaRepository<FlowBindingEntity, Long> {

    Optional<FlowBindingEntity> findByTenantIdAndBizTypeAndReleaseLevel(long tenantId, String bizType,
                                                                       String releaseLevel);

    List<FlowBindingEntity> findByTenantIdOrderByBizTypeAscReleaseLevelAsc(long tenantId);

    void deleteByTenantIdAndBizTypeAndReleaseLevel(long tenantId, String bizType, String releaseLevel);
}
