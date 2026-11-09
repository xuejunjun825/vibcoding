package com.example.req.integration.store;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code integration_sync_log} 查询。
 *
 * <p>管理页的过滤条件都是可选的，用一个 JPQL 覆盖而不是拼派生查询：
 * {@code :param is null} 在 Hibernate 6/7 下按「不限」处理。</p>
 *
 * <p>{@code :requirementId} 是需求详情「集成」tab 的数据作用域（DESIGN §I4「该需求，最近 10 条」）：
 * 同步日志没有 requirement_id 列，作用域由写入方落的业务锚点派生 —— MR/Sonar 的
 * {@code biz_id} 就是 {@code requirement.id}，构建的 {@code biz_id} 是 {@code build_record.id}
 * （按 {@code requirement_id} 反查），另用该需求发布单的 {@code build_record.correlation_id}
 * 兜住提交阶段的日志。派生规则只在这里实现一次，管理页传 {@code null} 时语义不变。</p>
 */
public interface IntegrationSyncLogRepository extends JpaRepository<IntegrationSyncLogEntity, Long> {

    @Query("""
            select l from IntegrationSyncLogEntity l
            where l.tenantId = :tenantId
              and (:system is null or l.system = :system)
              and (:result is null or l.result = :result)
              and (:correlationId is null or l.correlationId = :correlationId)
              and (:since is null or l.createdAt >= :since)
              and (:until is null or l.createdAt <= :until)
              and (:keyword is null
                   or l.operation like concat('%', :keyword, '%')
                   or l.targetUrl like concat('%', :keyword, '%')
                   or l.requestSummary like concat('%', :keyword, '%')
                   or l.errorCode like concat('%', :keyword, '%'))
              and (:requirementId is null
                   or (l.bizType in ('MR', 'SONAR') and l.bizId = :requirementId)
                   or (l.bizType = 'BUILD' and l.bizId in
                        (select b.id from BuildRecordEntity b
                          where b.tenantId = :tenantId and b.requirementId = :requirementId))
                   or (l.correlationId in
                        (select b2.correlationId from BuildRecordEntity b2
                          where b2.tenantId = :tenantId
                            and b2.requirementId = :requirementId
                            and b2.correlationId is not null)))
            order by l.id desc
            """)
    Page<IntegrationSyncLogEntity> search(@Param("tenantId") Long tenantId,
                                          @Param("system") String system,
                                          @Param("result") String result,
                                          @Param("correlationId") String correlationId,
                                          @Param("since") LocalDateTime since,
                                          @Param("until") LocalDateTime until,
                                          @Param("keyword") String keyword,
                                          @Param("requirementId") Long requirementId,
                                          Pageable pageable);

    /** 明细与分页共用租户条件：猜 id 也读不到别的租户的行。 */
    Optional<IntegrationSyncLogEntity> findByIdAndTenantId(Long id, Long tenantId);

    long countByTenantIdAndCorrelationId(Long tenantId, String correlationId);
}
