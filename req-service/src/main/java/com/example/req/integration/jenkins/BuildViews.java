package com.example.req.integration.jenkins;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 构建域的响应/载荷契约（与 web/src/api/integration.ts 的 BuildView / BuildTriggerResult 对齐）。
 *
 * <p>这里是唯一的一份形状定义：控制器只决定状态码，服务只填字段，前端只按字段渲染。
 * {@code triggeredBy} 是展示用的姓名（由 trigger_by 的用户 id 在读取时解析），
 * 存储层仍是 BIGINT {@code trigger_by}。</p>
 */
public final class BuildViews {

    private BuildViews() {
    }

    /** 触发载荷：{@code {"branch":"main"}}，可空（空则用配置里的默认分支）。 */
    public record TriggerCmd(String branch) {
        public String branchOrNull() {
            return branch == null || branch.isBlank() ? null : branch.trim();
        }
    }

    /** 202 的响应体（AC-3.2）：立即返回，不等 Jenkins。 */
    public record TriggerResult(long buildId, String status, String correlationId) {
    }

    /** 阶段：compile / unit-test 至少两项（AC-3.3）。 */
    public record BuildStage(String name, String status, Long durationMs) {
    }

    /** 构建卡视图（AC-3.7 最近 5 条 + console 链接）。 */
    public record BuildView(long id,
                            long deployOrderId,
                            String status,
                            String jobName,
                            Long buildNumber,
                            Long queueId,
                            LocalDateTime queuedAt,
                            LocalDateTime startedAt,
                            LocalDateTime finishedAt,
                            Long durationMs,
                            List<BuildStage> stages,
                            String consoleUrl,
                            LocalDateTime timeoutAt,
                            int retries,
                            boolean timedOut,
                            String triggeredBy,
                            String correlationId) {
    }

    /** 列表响应：{@code {"items":[...]}}。 */
    public record BuildList(List<BuildView> items) {
    }

    /** 适配器提交结果。{@code errorCode} 非空即失败（RETRY_EXHAUSTED / SSRF_BLOCKED / ...）。 */
    public record JenkinsSubmit(Long queueId,
                                Long buildNumber,
                                String location,
                                int httpStatus,
                                String errorCode,
                                String message) {

        public boolean ok() {
            return errorCode == null
                    && (httpStatus == 200 || httpStatus == 201 || httpStatus == 202);
        }

        public static JenkinsSubmit failed(int httpStatus, String errorCode, String message) {
            return new JenkinsSubmit(null, null, null, httpStatus, errorCode, message);
        }
    }

    /**
     * 适配器查询结果。{@code available=false} 表示这次读没拿到事实（超时/5xx），
     * 调用方保留现有状态（AC-3.4：读失败不改状态）。
     */
    public record JenkinsStatus(boolean available,
                                Long buildNumber,
                                Long queueId,
                                boolean building,
                                String result,
                                Long durationMs,
                                String consoleUrl,
                                List<BuildStage> stages,
                                String errorCode,
                                String message) {

        public static JenkinsStatus unavailable(int httpStatus, String errorCode, String message) {
            return new JenkinsStatus(false, null, null, false, null, null, null, List.of(),
                    errorCode, message);
        }

        /** 队列项是否已经分配了构建号（还在排队时为 {@code null}）。 */
        public boolean hasExecutable() {
            return buildNumber != null;
        }
    }
}
