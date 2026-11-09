package com.example.req.integration.jenkins;

import com.example.req.integration.store.BuildRecordEntity;
import com.example.req.integration.store.BuildRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 活动构建轮询（AC-3.4，默认 10s，可配 5–60s）+ 2h 超时扫描（AC-3.5）。
 *
 * <p>为什么用 {@code fixedDelay} 而不是 {@code fixedRate}：一轮里要读 N 个构建，慢的时候
 * 可能超过一个周期。{@code fixedRate} 会让 tick 在慢轮询时叠加，把线程池打爆；
 * {@code fixedDelay} 保证两轮之间一定隔一个周期，天然不重叠。</p>
 *
 * <p>连续读失败 5 次的构建降到每 6 个 tick 读一次（{@link BuildService#POLL_FAIL_THRESHOLD}）：
 * Jenkins 挂了的时候，没必要每 10 秒对每个活动构建都敲一次，但也<b>不能</b>停止轮询——
 * 那会让构建永远停在 RUNNING，直到 2h 超时兜底。</p>
 */
@Component
public class BuildPollingJob {

    private static final Logger log = LoggerFactory.getLogger(BuildPollingJob.class);

    private final BuildRecordRepository builds;
    private final BuildService buildService;

    /** 单实例内的 tick 计数（无状态可持久化：重启后降频从头算，不影响正确性）。 */
    private int tick;

    public BuildPollingJob(BuildRecordRepository builds, BuildService buildService) {
        this.builds = builds;
        this.buildService = buildService;
    }

    @Scheduled(fixedDelayString = "#{${platform.integration.build-poll-interval-seconds:10} * 1000}")
    public void poll() {
        tick++;
        try {
            // 先扫超时再轮询：已经超时的构建不该再花一次外呼去问状态。
            int swept = buildService.sweepTimeouts();
            if (swept > 0) {
                log.info("build timeout sweep: {} build(s) marked FAILURE (BUILD_TIMEOUT)", swept);
            }
            List<BuildRecordEntity> active = builds.findByStatusInOrderByIdAsc(List.of(
                    BuildStateMachine.BuildStatus.QUEUED.name(),
                    BuildStateMachine.BuildStatus.RUNNING.name()));
            for (BuildRecordEntity build : active) {
                if (degraded(build) && tick % BuildService.DEGRADED_TICK_DIVISOR != 0) {
                    continue;
                }
                try {
                    buildService.pollOne(build.getId());
                } catch (RuntimeException e) {
                    // 一条读失败不能中断整轮：其他构建还在等状态。
                    log.warn("poll build {} failed: {}", build.getId(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            // @Scheduled 里抛出的异常会让后续 tick 消失（Spring 只记一次日志），这里必须吃掉。
            log.warn("build polling tick failed: {}", e.toString());
        }
    }

    private static boolean degraded(BuildRecordEntity build) {
        int fails = build.getPollFailCount() == null ? 0 : build.getPollFailCount();
        return fails >= BuildService.POLL_FAIL_THRESHOLD;
    }
}
