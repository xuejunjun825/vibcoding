package com.example.req.integration.sonar;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationConfigRepository;
import com.example.req.integration.config.IntegrationSystem;

/**
 * 30 分钟定时把 Sonar 快照拉到本地（AC-6.2）。
 *
 * <p>存在的理由不是"顺手同步"，而是准入的 500ms 预算：准入路径零外呼，所以证据必须提前
 * 落在本地。没有这个 job，"看准入的人"就得等 Sonar。</p>
 *
 * <p>扫描范围为「版本或需求任一设置了 projectKey」的需求——继承来的 key 也要刷新，否则
 * 只配了版本默认 key 的需求永远没有快照。单条失败只记日志，不拖垮整轮（与
 * {@code MrFallbackPollingJob} 同一纪律）。</p>
 */
@Component
public class SonarRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(SonarRefreshJob.class);

    /** 单轮上限：一轮就要把租户全部需求拉一遍会把 Sonar 打爆，也让 tick 叠起来。 */
    private static final int BATCH_LIMIT = 500;

    private static final String CANDIDATES_SQL = """
            SELECT r.id
              FROM requirement r
              LEFT JOIN `release` rel ON rel.id = r.release_id
             WHERE r.tenant_id = ?
               AND (
                     (r.sonar_project_key IS NOT NULL AND TRIM(r.sonar_project_key) <> '')
                  OR (rel.sonar_project_key IS NOT NULL AND TRIM(rel.sonar_project_key) <> '')
               )
             ORDER BY r.id DESC
             LIMIT %d
            """.formatted(BATCH_LIMIT);

    private final IntegrationConfigRepository configRepository;
    private final SonarSnapshotService snapshotService;
    private final JdbcTemplate jdbc;

    public SonarRefreshJob(IntegrationConfigRepository configRepository,
                           SonarSnapshotService snapshotService,
                           JdbcTemplate jdbc) {
        this.configRepository = configRepository;
        this.snapshotService = snapshotService;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${platform.integration.sonarRefreshMs:1800000}",
            initialDelayString = "${platform.integration.sonarRefreshInitialDelayMs:120000}")
    public void refresh() {
        List<IntegrationConfigEntity> configs = configRepository.findAll().stream()
                .filter(IntegrationConfigEntity::isActive)
                .filter(config -> IntegrationSystem.SONAR.name().equalsIgnoreCase(config.getSystem()))
                .toList();
        for (IntegrationConfigEntity config : configs) {
            try {
                refreshTenant(config.getTenantId());
            } catch (Exception e) {
                log.warn("Sonar 定时刷新租户失败 tenant={} cause={}", config.getTenantId(),
                        e.toString());
            }
        }
    }

    private void refreshTenant(long tenantId) {
        TenantContext.set(DataScope.system(tenantId));
        try {
            List<Long> requirementIds = jdbc.queryForList(CANDIDATES_SQL, Long.class, tenantId);
            if (requirementIds.isEmpty()) {
                return;
            }
            int ok = 0;
            int failed = 0;
            for (Long requirementId : requirementIds) {
                try {
                    snapshotService.refresh(requirementId);
                    ok++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Sonar 刷新失败 tenant={} requirement={} cause={}", tenantId,
                            requirementId, e.toString());
                }
            }
            log.info("Sonar 定时刷新完成 tenant={} total={} ok={} failed={}", tenantId,
                    requirementIds.size(), ok, failed);
        } finally {
            TenantContext.clear();
        }
    }
}
