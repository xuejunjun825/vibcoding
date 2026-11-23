package com.example.req.automation.api;

import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 自动化规则的线上数据契约（请求 / 视图 / meta 三组，全是 record）。
 *
 * <p>为什么视图里回显用户输入的 condition / actions 原样 JSON 而不是解析后的结构：编辑页要
 * 「打开即所见」，如果服务端在这里翻译成别的结构，前端就得再翻译回来一次——两次翻译之间
 * 任何一次缺字段都会让用户存盘时丢掉自己没看到的条件。</p>
 *
 * <p>为什么 {@code WebhookOption} 只有 host 没有完整 URL：AC-2 明确要求接口不回完整 webhook
 * 地址与任何密钥；host 足够让用户辨认「打到哪个系统」，路径属于规则定义、仍是用户自己的输入。</p>
 */
public final class AutomationDtos {

    private AutomationDtos() {
    }

    /** 新增 / 编辑规则的请求体；{@code version} 在 PUT 上必填（乐观锁）。 */
    public record RuleCmd(
            String name,
            Boolean enabled,
            String triggerType,
            String scopeType,
            Long scopeId,
            Integer priority,
            JsonNode condition,
            JsonNode actions,
            Integer version) {
    }

    /** 启停请求：停用也要带 version，避免「停用覆盖别人刚改的条件」。 */
    public record EnableCmd(Boolean enabled, Integer version) {
    }

    /** 规则列表 / 详情视图。 */
    public record RuleView(
            long id,
            String name,
            boolean enabled,
            String triggerType,
            JsonNode condition,
            JsonNode actions,
            String scopeType,
            Long scopeId,
            String scopeLabel,
            int priority,
            int version,
            long createdBy,
            String createdByName,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            RunBrief lastRun) {
    }

    /** 规则页「上次执行」列。 */
    public record RunBrief(LocalDateTime at, String status, String skipReason) {
    }

    /** 执行日志一行（T5 的 runs 端点复用；定义放这里是因为它引用 {@link ActionOutcome}）。 */
    public record RunView(
            long id,
            long ruleId,
            String ruleName,
            int ruleVersion,
            String eventType,
            String eventId,
            Long requirementId,
            String requirementCode,
            String status,
            String skipReason,
            List<ActionOutcome> actions,
            long durationMs,
            LocalDateTime createdAt) {
    }

    /** 单个动作的执行结论。 */
    public record ActionOutcome(String type, String status, String message, String target) {
    }

    /** {@code GET /automation/meta}：枚举、候选与可用的 webhook 配置，让前端不硬编码。 */
    public record MetaView(
            List<CodeLabel> triggers,
            List<CodeLabel> actions,
            List<String> conditionKeys,
            List<String> estimateOps,
            List<String> fields,
            List<String> statuses,
            List<WebhookOption> webhookConfigs) {
    }

    public record CodeLabel(String code, String label) {
    }

    /** webhook 目标系统：只给 host，不回完整 URL 与凭据。 */
    public record WebhookOption(long id, String system, String name, String host) {
    }
}
