package com.example.req.integration.config;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 集成配置与同步日志的读写契约（TECHNICAL §4.1）。
 *
 * <p>四条读模型纪律，页面与脚本都靠它们成立：</p>
 * <ol>
 *   <li><b>凭据只写不读</b>：{@link ConfigView} 只有 {@code hasCredential/credentialLast4}，
 *       没有 {@code credential}、{@code credentialEnc}、{@code secret} 任何形态的字段——
 *       连字段名都不出现，序列化就不可能泄漏（AC-1.2 / AC-10.1）。</li>
 *   <li>{@link ConfigCmd} 的 {@code credential/secret} 为空串或省略都表示「保持原值」，
 *       清除只有一条路：{@code clearCredential=true} + {@code confirmName} 等于配置名
 *       （400 {@code CONFIRM_NAME_MISMATCH}）。语义实现只在 service 一处。</li>
 *   <li>{@code extra} 是具名字段表而不是自由 JSON：按系统白名单校验，前端按系统取字段。</li>
 *   <li>{@link TestResult} 恒为 200 的读模型：失败也是一种结果，不是异常。</li>
 * </ol>
 */
public final class IntegrationConfigViews {

    private IntegrationConfigViews() {
    }

    /** 读模型：凭据只露末四位；{@code lastTest} 来自 {@code last_test_*} 三列。 */
    public record ConfigView(long id,
                             String system,
                             String name,
                             String baseUrl,
                             String authType,
                             String username,
                             boolean hasCredential,
                             String credentialLast4,
                             boolean hasSecret,
                             String secretLast4,
                             Map<String, Object> extra,
                             boolean enabled,
                             int timeoutMs,
                             TestResult lastTest) {
    }

    /**
     * 写模型：所有字段可省略（PATCH 只改给出的字段）。
     *
     * <p>{@code system} 在 PATCH 里给同值是无害的（前端新建/修改共用一个表单对象），
     * 改系统名则按新系统的白名单重新校验 {@code extra}。</p>
     */
    public record ConfigCmd(String system,
                            String name,
                            String baseUrl,
                            String authType,
                            String username,
                            String credential,
                            String secret,
                            Map<String, Object> extra,
                            Integer timeoutMs,
                            Boolean enabled,
                            Boolean clearCredential,
                            String confirmName) {
    }

    /** 连通性测试结果：{@code latencyMs} 是本次实测值，落库只保留 ok/at/message。 */
    public record TestResult(boolean ok,
                             Integer latencyMs,
                             String code,
                             String message,
                             LocalDateTime checkedAt) {
    }

    /**
     * 同步日志行：{@code targetUrl/requestSummary/responseSummary} 都是写库前已经脱敏的文本，
     * 读侧不再做二次处理（脱敏唯一出口在 {@code SensitiveMasker}）。
     */
    public record LogRow(long id,
                         String correlationId,
                         String system,
                         String operation,
                         String method,
                         String targetUrl,
                         String requestSummary,
                         String responseSummary,
                         Integer httpStatus,
                         String result,
                         String errorCode,
                         int retryCount,
                         Integer durationMs,
                         LocalDateTime createdAt) {
    }
}
