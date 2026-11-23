package com.example.req.automation.action;

import com.example.platform.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作失败原因码（r39 Q-3）：执行日志必须能区分"为什么没做成"。
 *
 * <p>缺陷现场：{@code AutomationActionExecutor} 的两处 catch 都把异常压成裸 {@code FAILED}，
 * 于是「集成配置没配 secret」「外呼被 SSRF 策略拦」「对端超时」在日志页长得一模一样，
 * 用户只能去翻应用日志。这类漏信息不会让门禁变红——只有真去读日志才发现——所以这里把
 * 归一规则钉成单测，任何人新增 catch 分支都得给出可读的原因码。</p>
 */
class ActionOutcomesFailureCodeTest {

    @Test
    @DisplayName("业务错误码原样透出（ApiException.code 是契约）")
    void keepsApiExceptionCode() {
        assertThat(ActionOutcomes.failureCode(new ApiException(400, "TARGET_INVALID", "目标不可指派")))
                .isEqualTo("TARGET_INVALID");
    }

    @Test
    @DisplayName("签名密钥缺失 → SECRET_MISSING（原先只有裸 FAILED）")
    void detectsMissingSecret() {
        assertThat(ActionOutcomes.failureCode(new IllegalArgumentException("secret is required")))
                .isEqualTo("SECRET_MISSING");
    }

    @Test
    @DisplayName("外呼被 SSRF 策略拦 / 超时 / 连不上 各有稳定码")
    void mapsTransportFailures() {
        assertThat(ActionOutcomes.failureCode(new IllegalStateException("SSRF blocked: host")))
                .isEqualTo("SSRF_BLOCKED");
        assertThat(ActionOutcomes.failureCode(new RuntimeException("request timed out after 3000ms")))
                .isEqualTo("TIMEOUT");
        assertThat(ActionOutcomes.failureCode(new RuntimeException("UnknownHost: hook.example")))
                .isEqualTo("UNREACHABLE");
    }

    @Test
    @DisplayName("未知异常不吞：至少仍是 FAILED，且 null 不抛")
    void fallsBackToFailed() {
        assertThat(ActionOutcomes.failureCode(new RuntimeException("boom"))).isEqualTo(ActionOutcomes.FAILED);
        assertThat(ActionOutcomes.failureCode(null)).isEqualTo(ActionOutcomes.FAILED);
    }
}
