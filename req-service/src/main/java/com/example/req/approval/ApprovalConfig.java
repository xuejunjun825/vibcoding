package com.example.req.approval;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 审批模块的装配：只提供「时钟」这一个基础设施 bean。
 *
 * <p>时钟可注入是为了让催办 60s 冷却、实例时长这类断言在单测里可复现，
 * 而不是靠 {@code Thread.sleep} 掩盖时间依赖。
 */
@Configuration
public class ApprovalConfig {

    @Bean
    public Clock approvalClock() {
        return Clock.systemDefaultZone();
    }
}
