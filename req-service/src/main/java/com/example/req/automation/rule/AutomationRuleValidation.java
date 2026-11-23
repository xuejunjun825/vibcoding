package com.example.req.automation.rule;

import com.example.req.automation.spec.AutomationErrors;
import com.example.req.automation.spec.AutomationSpecs.ConditionSpec;
import com.example.req.automation.spec.AutomationTriggerType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 规则定义的校验：名称 / 体积 / 作用域 / 条件是否收窄 / 触发类型。
 *
 * <p>校验全部收在这一处，service 入口调一次就够，controller 不重复判——重复判必然漂移
 * （POST 过了 PUT 没过），而 {@code CONDITION_WIDENS_SCOPE} 更是「规则定义」的约束，
 * 不是运行期求值能发现的东西：运行时只能看到「这条规则读到了更宽的数据」，
 * 却无法区分这是用户本来就想这样，还是写错了。</p>
 */
@Component
public final class AutomationRuleValidation {

    /** 作用域类型：域级 / 厂商级。 */
    public static final String SCOPE_DOMAIN = "DOMAIN";
    public static final String SCOPE_VENDOR = "VENDOR";
    public static final List<String> SCOPE_TYPES = List.of(SCOPE_DOMAIN, SCOPE_VENDOR);

    /** 默认优先级（数值越小越先执行）。 */
    public static final int DEFAULT_PRIORITY = 100;
    public static final int MIN_PRIORITY = 0;
    public static final int MAX_PRIORITY = 9999;

    /** 名称：trim 后非空且 ≤100 字符；返回 trim 后的值，service 直接用它入库与查重。 */
    public String checkName(String name) {
        if (name == null || name.isBlank()) {
            throw AutomationErrors.nameRequired();
        }
        String trimmed = name.trim();
        if (trimmed.length() > AutomationErrors.MAX_NAME_LENGTH) {
            throw AutomationErrors.nameTooLong(trimmed.length());
        }
        return trimmed;
    }

    /**
     * 体积：condition + actions 的 UTF-8 字节数合计 ≤ 8KB。
     *
     * <p>按「用户提交的原始 JSON」算，而不是按解析后的对象算——限制的目的是防止有人往规则里
     * 塞几 MB 的垃圾，原始字节才是真实的存储与传输成本。</p>
     */
    public void checkSize(JsonNode condition, JsonNode actions) {
        int bytes = bytes(condition) + bytes(actions);
        if (bytes > AutomationErrors.MAX_PAYLOAD_BYTES) {
            throw AutomationErrors.payloadTooLarge(bytes);
        }
    }

    /** 作用域形状：DOMAIN / VENDOR + 正整数 id；存在性与可见性由 service 用 SQL 复核。 */
    public void checkScope(String scopeType, Long scopeId) {
        if (scopeType == null || !SCOPE_TYPES.contains(scopeType)) {
            throw AutomationErrors.invalidScope(scopeType, scopeId);
        }
        if (scopeId == null || scopeId <= 0) {
            throw AutomationErrors.invalidScope(scopeType, scopeId);
        }
    }

    /** 优先级：缺省 100，允许 0..9999。 */
    public int checkPriority(Integer priority) {
        if (priority == null) {
            return DEFAULT_PRIORITY;
        }
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw AutomationErrors.schema("priority",
                    "priority 必须在 " + MIN_PRIORITY + " 到 " + MAX_PRIORITY + " 之间");
        }
        return priority;
    }

    /** 触发类型：未知值 400 INVALID_TRIGGER。 */
    public AutomationTriggerType checkTrigger(String triggerType) {
        return AutomationTriggerType.of(triggerType);
    }

    /**
     * 条件不得比作用域更宽（§4.4）：
     * <ul>
     *   <li>DOMAIN 规则：domainIds 只能为空或恰好 [scopeId]；出现任何 vendorIds 即放宽；</li>
     *   <li>VENDOR 规则：vendorIds 只能为空或恰好 [scopeId]；出现任何 domainIds 即放宽。</li>
     * </ul>
     * 收窄（例如域规则里再按优先级筛）永远是允许的——那不是越权，只是更严格。
     */
    public void checkConditionScope(String scopeType, Long scopeId, ConditionSpec spec) {
        if (spec == null) {
            return;
        }
        if (SCOPE_DOMAIN.equals(scopeType)) {
            List<Long> domainIds = spec.domainIds();
            if (domainIds != null && (domainIds.size() != 1 || !domainIds.get(0).equals(scopeId))) {
                throw AutomationErrors.conditionWidensScope("domainIds");
            }
            if (spec.vendorIds() != null) {
                throw AutomationErrors.conditionWidensScope("vendorIds");
            }
        } else if (SCOPE_VENDOR.equals(scopeType)) {
            List<Long> vendorIds = spec.vendorIds();
            if (vendorIds != null && (vendorIds.size() != 1 || !vendorIds.get(0).equals(scopeId))) {
                throw AutomationErrors.conditionWidensScope("vendorIds");
            }
            if (spec.domainIds() != null) {
                throw AutomationErrors.conditionWidensScope("domainIds");
            }
        }
    }

    /** 作用域类型判定辅助（meta 与前端句式共用）。 */
    public static boolean isDomainScope(String scopeType) {
        return SCOPE_DOMAIN.equals(scopeType);
    }

    private static int bytes(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        return node.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
