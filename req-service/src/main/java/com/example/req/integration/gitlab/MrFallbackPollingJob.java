package com.example.req.integration.gitlab;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.integration.adapter.AdapterRegistry;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationConfigRepository;
import com.example.req.integration.config.IntegrationSystem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MR 兜底轮询：每 10 分钟把已配项目的近期 MR 拉一遍（AC-4.5）。
 *
 * <p>webhook 会丢（改网络、GitLab 升级、服务重启窗口），丢一次状态就永久卡在 OPENED。
 * 这里用 {@code state=all&updated_after=最近 7 天} 把事实重新收敛——所有写入都走
 * {@link MrSyncService#applyPayload}，因此单调、唯一键、幂等与 webhook 完全同一套。</p>
 *
 * <p>{@code fixedDelay} 而不是 {@code fixedRate}：一次轮询没跑完就不该叠下一次，
 * 否则慢 GitLab 会把 tick 排队堆成雪崩。</p>
 */
@Component
public class MrFallbackPollingJob {

    private static final Logger log = LoggerFactory.getLogger(MrFallbackPollingJob.class);

    private static final int POLL_DAYS = 7;

    private final IntegrationConfigRepository configRepository;
    private final AdapterRegistry adapterRegistry;
    private final GitLabAdapter adapter;
    private final MrSyncService mrSyncService;
    private final ObjectMapper objectMapper;

    public MrFallbackPollingJob(IntegrationConfigRepository configRepository,
                                AdapterRegistry adapterRegistry,
                                GitLabAdapter adapter,
                                MrSyncService mrSyncService,
                                ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.adapterRegistry = adapterRegistry;
        this.adapter = adapter;
        this.mrSyncService = mrSyncService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${platform.integration.mrFallbackPollMs:600000}",
            initialDelayString = "${platform.integration.mrFallbackInitialDelayMs:120000}")
    public void poll() {
        List<IntegrationConfigEntity> configs;
        try {
            configs = configRepository.findAll().stream()
                    .filter(IntegrationConfigEntity::isActive)
                    .filter(c -> IntegrationSystem.GITLAB.name().equals(c.getSystem()))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("MR 兜底轮询读取配置失败：{}", e.toString());
            return;
        }
        for (IntegrationConfigEntity config : configs) {
            try {
                pollOne(config);
            } catch (RuntimeException e) {
                // 单个租户/项目失败不能拖垮整轮
                log.warn("MR 兜底轮询 config={} 失败：{}", config.getId(), e.toString());
            }
        }
    }

    private void pollOne(IntegrationConfigEntity config) {
        String project = projectPath(config);
        if (project == null || project.isBlank()) {
            return;
        }
        if (adapterRegistry.find(IntegrationSystem.GITLAB).isEmpty()) {
            return;
        }
        TenantContext.set(DataScope.system(config.getTenantId()));
        try {
            List<MrViews.MrPayload> payloads = adapter.recentMergeRequests(config, project, POLL_DAYS);
            for (MrViews.MrPayload payload : payloads) {
                try {
                    mrSyncService.applyPayload(config.getTenantId(), config.getId(), payload);
                } catch (RuntimeException e) {
                    log.debug("MR {}/{} 兜底写入失败：{}", project, payload.mrIid(), e.toString());
                }
            }
        } finally {
            TenantContext.clear();
        }
    }

    /** 兜底轮询的项目来自配置的 {@code extra.projectPath}（与连通性测试同源）。 */
    private String projectPath(IntegrationConfigEntity config) {
        String extra = config.getExtraJson();
        if (extra == null || extra.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(extra);
            JsonNode project = node == null ? null : node.get("projectPath");
            return project == null || project.isNull() ? null : project.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** 供运维手动触发一轮（同包测试用）。 */
    Map<String, Object> status() {
        return Map.of("pollDays", POLL_DAYS);
    }
}
