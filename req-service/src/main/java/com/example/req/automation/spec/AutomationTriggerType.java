package com.example.req.automation.spec;

import java.util.ArrayList;
import java.util.List;

/**
 * 触发类型：规则由哪一类事件唤醒。
 *
 * <p>字面量与 {@code automation_rule.trigger_type} 的取值、{@code automation_event.event_type}、
 * PRD 3.1 的枚举表三者一一对应；前端不再硬编码，全部经 {@code GET /automation/meta} 下发。</p>
 *
 * <p>为什么用枚举而不是字符串常量：未知触发类型必须在「规则保存」时就被拒绝
 * （400 {@code INVALID_TRIGGER}），枚举让未知值在编译期不可写出，运行期只在反序列化入口判一次。</p>
 */
public enum AutomationTriggerType {

    /** 需求创建（审计动作 REQUIREMENT_CREATE）。 */
    REQUIREMENT_CREATED("需求创建"),

    /** 需求状态流转（审计动作 REQUIREMENT_STATUS / 带 from-to 的流转）。 */
    STATUS_CHANGE("状态变更"),

    /** 需求字段变更（负责人 / 优先级 / 人天 等白名单字段）。 */
    FIELD_CHANGE("字段变更"),

    /** 定时扫描（每日 08:30 Asia/Shanghai，按规则作用域生成事件）。 */
    SCHEDULE("定时扫描");

    private final String label;

    AutomationTriggerType(String label) {
        this.label = label;
    }

    /** 与 {@code trigger_type} 列一致的字面量（= 枚举名）。 */
    public String code() {
        return name();
    }

    /** 中文标签，只用于 {@code /automation/meta} 与前端下拉。 */
    public String label() {
        return label;
    }

    /** 严格解析：空白 / null / 未知值一律抛 {@code INVALID_TRIGGER}，不做大小写宽容。 */
    public static AutomationTriggerType of(String code) {
        if (code == null || code.isBlank()) {
            throw AutomationErrors.invalidTrigger(null);
        }
        for (AutomationTriggerType type : values()) {
            if (type.name().equals(code.trim())) {
                return type;
            }
        }
        throw AutomationErrors.invalidTrigger(code);
    }

    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        for (AutomationTriggerType type : values()) {
            if (type.name().equals(code.trim())) {
                return true;
            }
        }
        return false;
    }

    public static List<String> codes() {
        List<String> codes = new ArrayList<>(values().length);
        for (AutomationTriggerType type : values()) {
            codes.add(type.name());
        }
        return List.copyOf(codes);
    }
}
