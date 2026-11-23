package com.example.req.automation.rule;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * {@code automation_rule} 的读写口；规则页的筛选与分页走 {@link JpaSpecificationExecutor}。
 *
 * <p>三个按触发类型取启用规则的查询是执行侧（T5）与定时扫描（T5 {@code AutomationScheduleScanner}）
 * 的入口，顺序固定为「优先级小者先、同优先级按 id」——乱序执行会让 PARTIAL 的语义不可复现。</p>
 *
 * <p>为什么不在这里写「按作用域过滤」：作用域隔离只走 {@code ScopedJdbc} + {@code ScopeSql}，
 * 规则表本身登记为 {@code TableSpec.NONE}（自动化自己的配置表，按 tenant 隔离），
 * 用 JPA 手拼 domain_id 会绕开那唯一一条路径。</p>
 */
public interface AutomationRuleRepository
        extends JpaRepository<AutomationRuleEntity, Long>, JpaSpecificationExecutor<AutomationRuleEntity> {

    Optional<AutomationRuleEntity> findByIdAndTenantId(long id, long tenantId);

    Optional<AutomationRuleEntity> findFirstByTenantIdAndName(long tenantId, String name);

    boolean existsByTenantIdAndName(long tenantId, String name);

    boolean existsByTenantIdAndNameAndIdNot(long tenantId, String name, long id);

    /** 执行侧：某租户某触发类型的启用规则，按优先级升序。 */
    List<AutomationRuleEntity> findByTenantIdAndEnabledAndTriggerTypeOrderByPriorityAscIdAsc(
            long tenantId, boolean enabled, String triggerType);

    /** 定时扫描：所有租户的定时规则（按租户分组扫描，租户内仍按优先级）。 */
    List<AutomationRuleEntity> findByEnabledAndTriggerTypeOrderByTenantIdAscPriorityAscIdAsc(
            boolean enabled, String triggerType);
}
