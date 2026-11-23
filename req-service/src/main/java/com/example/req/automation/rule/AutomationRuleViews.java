package com.example.req.automation.rule;

import com.example.req.automation.api.AutomationDtos.CodeLabel;
import com.example.req.automation.api.AutomationDtos.RuleView;
import com.example.req.automation.api.AutomationDtos.RunBrief;
import com.example.req.automation.spec.AutomationActionType;
import com.example.req.automation.spec.AutomationSpecs;
import com.example.req.automation.spec.AutomationTriggerType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * entity → {@link RuleView} 的组装（唯一一处）。
 *
 * <p>为什么单独一个类：视图里三个「非行内字段」——作用域标签、创建人、最近一次执行——都要
 * <b>批量</b>补（列表页 N 条规则只允许常数条 SQL）。把组装与批量查询放在 service 里会让
 * 「一条规则长什么样」散在多个方法里，回显与句式的唯一性就没了。</p>
 */
public final class AutomationRuleViews {

    private AutomationRuleViews() {
    }

    /**
     * @param condition 已解析的条件（回显用户输入）
     * @param actions   已解析的动作（回显用户输入）
     * @param scopeLabel 域 / 厂商名称（作用域不可见时给占位）
     * @param createdByName 创建人姓名（用户已删时给空串）
     * @param lastRun   最近一次执行摘要，可为 null
     */
    public static RuleView toView(AutomationRuleEntity entity,
                                  JsonNode condition,
                                  JsonNode actions,
                                  String scopeLabel,
                                  String createdByName,
                                  RunBrief lastRun) {
        return new RuleView(
                entity.getId(),
                entity.getName(),
                Boolean.TRUE.equals(entity.getEnabled()),
                entity.getTriggerType(),
                condition,
                actions,
                entity.getScopeType(),
                entity.getScopeId(),
                scopeLabel == null ? "" : scopeLabel,
                entity.getPriority() == null ? AutomationRuleValidation.DEFAULT_PRIORITY : entity.getPriority(),
                entity.getVersion() == null ? 1 : entity.getVersion(),
                entity.getCreatedBy() == null ? 0L : entity.getCreatedBy(),
                createdByName == null ? "" : createdByName,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                lastRun);
    }

    /** 触发类型 -> 中文标签（列表句式与下拉共用，未知值原样回显不抛）。 */
    public static String triggerLabel(String triggerType) {
        for (AutomationTriggerType type : AutomationTriggerType.values()) {
            if (type.name().equals(triggerType)) {
                return type.label();
            }
        }
        return triggerType == null ? "" : triggerType;
    }

    /** 动作类型 -> 中文标签。 */
    public static String actionLabel(String actionType) {
        for (AutomationActionType type : AutomationActionType.values()) {
            if (type.name().equals(actionType)) {
                return type.label();
            }
        }
        return actionType == null ? "" : actionType;
    }

    /** 下拉选项（meta 与前端共用，顺序即枚举声明顺序）。 */
    public static List<CodeLabel> triggerOptions() {
        List<CodeLabel> options = new ArrayList<>(AutomationTriggerType.values().length);
        for (AutomationTriggerType type : AutomationTriggerType.values()) {
            options.add(new CodeLabel(type.name(), type.label()));
        }
        return List.copyOf(options);
    }

    public static List<CodeLabel> actionOptions() {
        List<CodeLabel> options = new ArrayList<>(AutomationActionType.values().length);
        for (AutomationActionType type : AutomationActionType.values()) {
            options.add(new CodeLabel(type.name(), type.label()));
        }
        return List.copyOf(options);
    }

    /**
     * 数据库里的 JSON 文本 → JsonNode。
     *
     * <p>列是 JSON 类型，理论上不可能非法；但读路径一旦抛异常，整个列表页 500，
     * 所以这里退化成 {@code {}} / {@code []} 让页面仍可打开、问题可被看见。</p>
     */
    public static JsonNode parseJson(ObjectMapper objectMapper, String json, boolean array) {
        if (json == null || json.isBlank()) {
            return emptyNode(objectMapper, array);
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.isNull()) {
                return emptyNode(objectMapper, array);
            }
            return node;
        } catch (RuntimeException ex) {
            return emptyNode(objectMapper, array);
        }
    }

    private static JsonNode emptyNode(ObjectMapper objectMapper, boolean array) {
        return array ? objectMapper.createArrayNode() : objectMapper.createObjectNode();
    }

    /** 最近一次执行摘要（规则页「上次执行」列）。 */
    public static RunBrief brief(LocalDateTime at, String status, String skipReason) {
        return new RunBrief(at, status, skipReason);
    }

    /** 条件是否为空（{}），列表句式据此显示「无条件」。 */
    public static boolean isEmptyCondition(JsonNode condition) {
        return condition == null || condition.isNull()
                || (condition.isObject() && condition.isEmpty());
    }

    /** meta 的条件键（顺序固定，前端不再硬编码）。 */
    public static List<String> conditionKeys() {
        return AutomationSpecs.CONDITION_KEYS;
    }
}
