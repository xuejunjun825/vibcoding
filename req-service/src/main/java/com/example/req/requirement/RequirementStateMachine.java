package com.example.req.requirement;

import com.example.platform.contract.StateTables;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 需求状态机的领域门面：目标状态与允许动作**只从 contract 的 {@link StateTables} 读取**，
 * 本类不复制任何迁移表，只负责把"非法动作"翻译成 409 与允许目标。
 */
@Component
public class RequirementStateMachine {

    /** 三类需求的初始状态。状态表本身只回答迁移，初始态在创建时唯一确定。 */
    public static final String INITIAL_BUS = "SUBMITTED";
    public static final String INITIAL_PDR = "PENDING_ANALYSIS";
    public static final String INITIAL_RD = "TODO";

    public String initialStatus(String type) {
        return switch (type) {
            case RequirementRules.TYPE_BUS -> INITIAL_BUS;
            case RequirementRules.TYPE_PDR -> INITIAL_PDR;
            case RequirementRules.TYPE_RD -> INITIAL_RD;
            default -> throw RequirementErrors.validation("未知需求类型：" + type);
        };
    }

    public Set<String> allowedActions(String type, String status) {
        try {
            Set<String> actions = StateTables.actions(type, status);
            return actions == null ? Set.of() : new LinkedHashSet<>(actions);
        } catch (RuntimeException ex) {
            return Set.of();
        }
    }

    public boolean isTerminal(String type, String status) {
        try {
            return StateTables.isTerminal(type, status);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * 一行可能混着 BUS/PDR/RD 的子需求（例如父需求详情、聚合列表、版本统计），
     * 此时只有状态名、没有类型，终态要按三类状态表取并集——仍然不复制任何终态字面量。
     */
    public boolean isTerminalAcrossTypes(String status) {
        return isTerminal(RequirementRules.TYPE_BUS, status)
                || isTerminal(RequirementRules.TYPE_PDR, status)
                || isTerminal(RequirementRules.TYPE_RD, status);
    }

    /** 目标状态；非法动作抛 409 ILLEGAL_TRANSITION，detail 带 allowedTargets。 */    public String target(String type, String status, String action) {
        Set<String> allowed = allowedActions(type, status);
        if (action == null || !allowed.contains(action)) {
            throw RequirementErrors.illegalTransition(type, status, action, allowedTargets(type, status, allowed));
        }
        String target;
        try {
            target = StateTables.target(type, status, action);
        } catch (RuntimeException ex) {
            throw RequirementErrors.illegalTransition(type, status, action, allowedTargets(type, status, allowed));
        }
        if (target == null) {
            throw RequirementErrors.illegalTransition(type, status, action, allowedTargets(type, status, allowed));
        }
        return target;
    }

    public List<String> allowedTargets(String type, String status) {
        return allowedTargets(type, status, allowedActions(type, status));
    }

    private List<String> allowedTargets(String type, String status, Set<String> actions) {
        return actions.stream()
                .map(action -> {
                    try {
                        return StateTables.target(type, status, action);
                    } catch (RuntimeException ex) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }
}
