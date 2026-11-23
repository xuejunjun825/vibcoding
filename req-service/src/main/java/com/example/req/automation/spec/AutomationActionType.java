package com.example.req.automation.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动作白名单：自动化规则能做的四件事，以及它永远不能做的五件事。
 *
 * <p>白名单是「结构上不可绕过」的那一半：只有这四型有解析分支、只有这四型有执行器，
 * 其余 {@code type} 在保存规则时就 400，因此库里不可能存在第五种动作。</p>
 *
 * <p>黑名单（{@link #FORBIDDEN}）不是为了运行期拦截，而是为了让错误信息可解释：
 * 用户写了 {@code SET_STATUS}，要明确告诉他是「状态」越权，而不是一句 unknown type。
 * 真正的门禁在 lint L21：automation 包内除 {@code AutomationOwnerAssigner} 外
 * 禁止出现 {@code .transition(} / {@code ApprovalEngine}，禁改状态是静态红灯，不是口头约定。</p>
 */
public enum AutomationActionType {

    /** 发通知：收件人由表达式解析（USER / ROLE / DOMAIN_OWNER），再按作用域过滤可见性。 */
    NOTIFY("发送通知"),

    /** 指派负责人：唯一调用 {@code RequirementService.assignOwner} 的动作（不改状态）。 */
    ASSIGN_OWNER("指派负责人"),

    /** 打标签：走 {@code requirement_tag} 唯一键，重复即成功。 */
    ADD_TAG("添加标签"),

    /** 外呼 webhook：地址只由 integration_config.base_url + path 组成，规则里没有可填 URL 的地方。 */
    WEBHOOK("调用 Webhook");

    private final String label;

    AutomationActionType(String label) {
        this.label = label;
    }

    public String code() {
        return name();
    }

    public String label() {
        return label;
    }

    /** 越权动作 → 一句人话原因；键即被拒绝的 {@code type} 字面量。 */
    private static final Map<String, String> FORBIDDEN = forbiddenTable();

    private static Map<String, String> forbiddenTable() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("SET_STATUS", "自动化规则不允许改变需求状态（状态只能由人经状态机流转）");
        table.put("APPROVE", "自动化规则不允许审批（审批只能由审批人在审批单上完成）");
        table.put("SKIP_APPROVAL", "自动化规则不允许跳过审批（审批门禁不可被规则绕过）");
        table.put("CREATE_RELEASE", "自动化规则不允许创建发布单（发布单只能由发布流程创建）");
        table.put("EDIT_ROLLUP", "自动化规则不允许修改人天汇总（汇总由子项推导）");
        return Map.copyOf(table);
    }

    public static AutomationActionType of(String code) {
        if (code == null || code.isBlank()) {
            throw AutomationErrors.actionNotAllowed(null);
        }
        for (AutomationActionType type : values()) {
            if (type.name().equals(code.trim())) {
                return type;
            }
        }
        throw AutomationErrors.actionNotAllowed(code.trim());
    }

    /** 是否为已知的越权动作（用于给出更准确的原因）。 */
    public static boolean isForbidden(String code) {
        return code != null && FORBIDDEN.containsKey(code.trim());
    }

    /** 越权动作的人话原因；未知动作返回 null。 */
    public static String forbiddenReason(String code) {
        return code == null ? null : FORBIDDEN.get(code.trim());
    }

    public static List<String> codes() {
        List<String> codes = new ArrayList<>(values().length);
        for (AutomationActionType type : values()) {
            codes.add(type.name());
        }
        return List.copyOf(codes);
    }
}
