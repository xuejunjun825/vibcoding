package com.example.req.integration;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 集成域唯一的调度/异步配置。
 *
 * <p>拆成两个池的原因：{@code @Scheduled} 的轮询（构建 10s、Sonar 30min、MR 兜底 10min）
 * 与用户动作触发的后台提交（Jenkins 构建）互不挤占；轮询用 fixedDelay 天然不重叠，
 * 线程池只是让多个 job 不互相等待。队列满时丢弃由调用方降级，不阻塞 HTTP 线程。</p>
 */
@Configuration
@EnableScheduling
@EnableAsync
public class IntegrationAsyncConfig {

    public static final String TASK_EXECUTOR = "integrationTaskExecutor";
    public static final String SCHEDULER = "integrationTaskScheduler";

    @Bean(name = TASK_EXECUTOR)
    public Executor integrationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("integration-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean(name = SCHEDULER)
    public ThreadPoolTaskScheduler integrationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("integration-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(t -> {
            // 单个 tick 失败不能让后续 tick 一起消失，交由各 job 自己记录。
            org.slf4j.LoggerFactory.getLogger(IntegrationAsyncConfig.class)
                    .warn("integration scheduled task failed: {}", t.toString());
        });
        scheduler.initialize();
        return scheduler;
    }
}
