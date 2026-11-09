package com.example.req.collab.wiki;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.requirement.Rows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收站超期清理（TECHNICAL §3.2 / AC-12）。
 *
 * <p>30 天内可恢复，超期由本任务物理删除；删除动作必须留审计（{@code WIKI_PURGE}），
 * 否则「谁在什么时候彻底删了这篇文档」永远查不到。</p>
 *
 * <p>按租户切换 {@link TenantContext}：审计写入依赖租户上下文，跨租户一把梭会记错租户。
 * 单个租户失败不拖垮整轮；{@code @Scheduled} 里抛出的异常会让后续 tick 消失，必须吃掉。</p>
 */
@Component
public class WikiPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(WikiPurgeJob.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    @Value("${collab.wiki.purge.enabled:true}")
    private boolean enabled;

    @Value("${collab.wiki.purge.retention-days:30}")
    private int retentionDays;

    public WikiPurgeJob(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Scheduled(cron = "${collab.wiki.purge.cron:0 10 3 * * *}")
    public void purgeExpired() {
        if (!enabled) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, retentionDays));
        List<Long> tenants;
        try {
            tenants = jdbc.queryForList("""
                    SELECT DISTINCT tenant_id FROM wiki_page
                     WHERE status = 'DELETED' AND deleted_at IS NOT NULL AND deleted_at < :cutoff
                    """, Map.of("cutoff", cutoff), Long.class);
        } catch (RuntimeException ex) {
            log.warn("Wiki 清理读取租户失败：{}", ex.toString());
            return;
        }
        for (Long tenantId : tenants) {
            TenantContext.set(DataScope.system(tenantId));
            try {
                purgeTenant(tenantId, cutoff);
            } catch (RuntimeException ex) {
                log.warn("Wiki 清理 tenant={} 失败：{}", tenantId, ex.toString());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void purgeTenant(long tenantId, LocalDateTime cutoff) {
        List<Long> roots = jdbc.queryForList("""
                SELECT DISTINCT COALESCE(deleted_root_id, id) AS root_id
                  FROM wiki_page
                 WHERE tenant_id = :tenantId AND status = 'DELETED'
                   AND deleted_at IS NOT NULL AND deleted_at < :cutoff
                """, Map.of("tenantId", tenantId, "cutoff", cutoff), Long.class);
        for (Long root : roots) {
            List<Long> ids = jdbc.queryForList("""
                    SELECT id FROM wiki_page
                     WHERE tenant_id = :tenantId AND status = 'DELETED'
                       AND (deleted_root_id = :root OR id = :root)
                    """, Map.of("tenantId", tenantId, "root", root), Long.class);
            if (ids.isEmpty()) {
                continue;
            }
            List<Long> all = new ArrayList<>(ids);
            Map<String, Object> params = Map.of("ids", all, "tenantId", tenantId);
            audit.record("WIKI_PURGE", "WIKI_PAGE", root,
                    Map.of("pages", all.size(), "reason", "expired", "retentionDays", retentionDays));
            jdbc.update("DELETE FROM wiki_page_version WHERE page_id IN (:ids) AND tenant_id = :tenantId", params);
            jdbc.update("DELETE FROM wiki_comment WHERE page_id IN (:ids) AND tenant_id = :tenantId", params);
            jdbc.update("DELETE FROM wiki_page_link WHERE page_id IN (:ids) AND tenant_id = :tenantId", params);
            jdbc.update("DELETE FROM wiki_page WHERE id IN (:ids) AND tenant_id = :tenantId", params);
            log.info("Wiki 清理 tenant={} root={} pages={}", tenantId, root, all.size());
        }
    }

    /** 供测试/运维查看配置（不触发删除）。 */
    Map<String, Object> status() {
        return Map.of("enabled", enabled, "retentionDays", retentionDays);
    }
}
