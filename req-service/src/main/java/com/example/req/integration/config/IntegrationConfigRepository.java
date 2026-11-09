package com.example.req.integration.config;

import java.util.List;
import java.util.Optional;

import com.example.platform.common.tenant.TenantContext;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code integration_config} 的读取入口。
 *
 * <p>{@link #enabled(IntegrationSystem)} 是「系统是否已接入」的唯一判定：未启用即
 * 409 {@code INTEGRATION_DISABLED}，四个适配器都从这里取配置，不允许各自写查询。</p>
 */
public interface IntegrationConfigRepository extends JpaRepository<IntegrationConfigEntity, Long> {

    List<IntegrationConfigEntity> findByTenantIdOrderBySystemAscIdAsc(Long tenantId);

    List<IntegrationConfigEntity> findByTenantIdAndSystemOrderByIdAsc(Long tenantId, String system);

    Optional<IntegrationConfigEntity> findByIdAndTenantId(Long id, Long tenantId);

    Optional<IntegrationConfigEntity> findFirstByTenantIdAndSystemAndEnabledOrderByIdAsc(
            Long tenantId, String system, Integer enabled);

    boolean existsByTenantIdAndSystemAndEnabled(Long tenantId, String system, Integer enabled);

    /**
     * 当前租户下该系统唯一启用的配置。
     *
     * <p>无租户上下文时抛异常（fail-closed）：后台任务必须显式带 tenantId 查询，不能借用
     * 「没有上下文就等于全库」的隐式行为。</p>
     */
    default Optional<IntegrationConfigEntity> enabled(IntegrationSystem system) {
        long tenantId = TenantContext.require().tenantId();
        return findFirstByTenantIdAndSystemAndEnabledOrderByIdAsc(tenantId, system.name(), 1);
    }
}
