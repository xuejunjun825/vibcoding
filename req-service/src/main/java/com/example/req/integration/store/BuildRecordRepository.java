package com.example.req.integration.store;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code build_record} 查询：全部带 tenantId，后台任务也必须显式传入，
 * 不做「无租户即全库」的隐式放宽。
 */
public interface BuildRecordRepository extends JpaRepository<BuildRecordEntity, Long> {

    Optional<BuildRecordEntity> findByIdAndTenantId(Long id, Long tenantId);

    /** 发布单详情「最近 5 条」；id DESC 即触发先后。 */
    List<BuildRecordEntity> findTop5ByTenantIdAndDeployOrderIdOrderByIdDesc(Long tenantId, Long deployOrderId);

    List<BuildRecordEntity> findByTenantIdAndDeployOrderIdOrderByIdDesc(Long tenantId, Long deployOrderId);

    /** 该发布单是否已有活动构建（唯一键之外的显式预检，用于给出 409 的清楚原因）。 */
    Optional<BuildRecordEntity> findFirstByTenantIdAndDeployOrderIdAndActiveKeyIsNotNull(
            Long tenantId, Long deployOrderId);

    boolean existsByTenantIdAndDeployOrderIdAndActiveKeyIsNotNull(Long tenantId, Long deployOrderId);

    /** 轮询用：跨租户扫描活动构建（后台任务无请求上下文，按行携带 tenantId）。 */
    List<BuildRecordEntity> findByStatusInOrderByIdAsc(Collection<String> statuses);

    /** 超时扫描：活动构建且已过 timeout_at。 */
    List<BuildRecordEntity> findByStatusInAndTimeoutAtBefore(Collection<String> statuses, LocalDateTime before);
}
