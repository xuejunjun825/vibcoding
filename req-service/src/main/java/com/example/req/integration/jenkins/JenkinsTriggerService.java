package com.example.req.integration.jenkins;

import com.example.platform.common.audit.AuditService;
import com.example.platform.contract.DataScope;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.integration.IntegrationAsyncConfig;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationConfigRepository;
import com.example.req.integration.notify.IntegrationNotifier;
import com.example.req.integration.store.BuildRecordEntity;
import com.example.req.integration.store.BuildRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台提交 Jenkins 构建（AC-3.2 的 202）。
 *
 * <p>为什么异步：Jenkins 提交在冷启动/插件繁忙时要 10s+，同步做会让 HTTP 线程陪着等，
 * 而用户要的只是「已排队」。所以控制器先落 QUEUED 行并立刻 202，提交在
 * {@code integrationTaskExecutor} 上跑；失败不是丢掉，而是把这一行改成 FAILURE
 * + 审计 + 通知，用户看得到「触发失败」而不是一条永远 QUEUED 的幽灵构建。</p>
 *
 * <p>重试只有一次实现：{@link JenkinsAdapter#submit} 走
 * {@link com.example.req.integration.outbound.OutboundHttpClient#exchangeWithRetry}，
 * 1/2/4/8/16s 退避由 RetryPolicy 决定，耗尽的 RETRY_EXHAUSTED 日志也由出站层写。</p>
 */
@Component
public class JenkinsTriggerService {

    private static final Logger log = LoggerFactory.getLogger(JenkinsTriggerService.class);

    /** 审计动作：触发（含后台提交失败）。 */
    public static final String ACTION_TRIGGERED = "BUILD_TRIGGERED";
    /** 审计动作：状态迁移。 */
    public static final String ACTION_STATUS = "BUILD_STATUS";
    /** 审计/日志错误码：提交阶段就失败。 */
    public static final String CODE_SUBMIT_FAILED = "BUILD_SUBMIT_FAILED";

    private final BuildRecordRepository builds;
    private final IntegrationConfigRepository configs;
    private final JenkinsAdapter adapter;
    private final IntegrationNotifier notifier;
    private final AuditService audit;

    public JenkinsTriggerService(BuildRecordRepository builds,
                                 IntegrationConfigRepository configs,
                                 JenkinsAdapter adapter,
                                 IntegrationNotifier notifier,
                                 AuditService audit) {
        this.builds = builds;
        this.configs = configs;
        this.adapter = adapter;
        this.notifier = notifier;
        this.audit = audit;
    }

    /**
     * 异步提交。调用方（{@link BuildService}）在事务提交后调用，确保后台线程读得到这一行。
     *
     * @param buildId       已落库的 build_record.id
     * @param params        Jenkins 参数（branch 等）
     * @param correlationId 同一个 id 贯穿触发、提交、日志，便于串起来看
     */
    @Async(IntegrationAsyncConfig.TASK_EXECUTOR)
    public void submitAsync(long buildId, Map<String, Object> params, String correlationId) {
        BuildRecordEntity build = builds.findById(buildId).orElse(null);
        if (build == null) {
            log.warn("jenkins submit skipped: build {} not found", buildId);
            return;
        }
        TenantContext.set(DataScope.system(build.getTenantId()));
        try {
            if (!BuildStateMachine.BuildStatus.QUEUED.name().equals(build.getStatus())) {
                // 触发后立刻被超时/回调改成终态：不要再往 Jenkins 提交一次（否则会多出一个构建）
                log.info("jenkins submit skipped: build {} is already {}", buildId, build.getStatus());
                return;
            }
            IntegrationConfigEntity config = configs.findById(build.getConfigId()).orElse(null);
            if (config == null || !config.isActive()) {
                markFailed(build, "INTEGRATION_DISABLED", "集成配置已删除或停用，构建未提交");
                return;
            }
            BuildViews.JenkinsSubmit submit =
                    adapter.submit(config, build.getJobName(), params, correlationId, buildId);
            if (!submit.ok()) {
                markFailed(build, submit.errorCode() == null ? CODE_SUBMIT_FAILED : submit.errorCode(),
                        submit.message() == null ? "Jenkins 提交失败" : submit.message());
                return;
            }
            if (submit.queueId() != null) {
                build.setQueueId(submit.queueId());
            }
            if (submit.buildNumber() != null) {
                build.setBuildNumber(submit.buildNumber());
            }
            builds.save(build);
            audit.record(ACTION_TRIGGERED, "BUILD", build.getId(), detail(build,
                    Map.of("phase", "submitted")));
        } catch (RuntimeException e) {
            log.warn("jenkins submit failed for build {}: {}", buildId, e.toString());
            markFailed(build, CODE_SUBMIT_FAILED, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 提交失败：直接进终态 FAILURE（QUEUED→FAILURE 是状态机允许的迁移），
     * 并释放 active_key，否则这张发布单会被一个不存在的构建永久占住。
     */
    private void markFailed(BuildRecordEntity build, String errorCode, String message) {
        LocalDateTime now = LocalDateTime.now();
        build.setStatus(BuildStateMachine.BuildStatus.FAILURE.name());
        build.setFinishedAt(now);
        build.setDurationMs(0L);
        build.setActiveKey(null);
        // error_code 没有列：build_record 不存错误码，码只进 integration_sync_log 与审计
        // （AC-3.5 的 BUILD_TIMEOUT 同理）——事实表只记状态，原因留在证据链里。
        builds.save(build);
        audit.record(ACTION_STATUS, "BUILD", build.getId(), detail(build,
                Map.of("errorCode", errorCode, "reason", message == null ? "" : truncate(message))));
        notifier.warn(build.getTenantId(),
                "Jenkins 构建提交失败：" + build.getJobName(),
                errorCode + " · " + (message == null ? "提交未成功" : truncate(message)),
                build.getId());
    }

    private static Map<String, Object> detail(BuildRecordEntity build, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deployOrderId", build.getDeployOrderId());
        detail.put("jobName", build.getJobName());
        detail.put("status", build.getStatus());
        if (build.getBuildNumber() != null) {
            detail.put("buildNumber", build.getBuildNumber());
        }
        detail.putAll(extra);
        return detail;
    }

    private static String truncate(String value) {
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
}
