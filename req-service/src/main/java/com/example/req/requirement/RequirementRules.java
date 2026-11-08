package com.example.req.requirement;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 三类需求的纯规则（不依赖 Spring / DB / 共享异常类），便于逐条单测 AC-2。
 *
 * <p>校验结果用 {@link Violation} 表达，由 Service 统一翻译成 HTTP 错误；这样规则本身可以在
 * 没有任何基础设施的情况下被测试，也避免"规则散落在 Controller 与 Service"。</p>
 */
public final class RequirementRules {

    public static final String TYPE_BUS = "BUS";
    public static final String TYPE_PDR = "PDR";
    public static final String TYPE_RD = "RD";

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String INVALID_PARENT = "INVALID_PARENT";
    public static final String PARENT_DAYS_READONLY = "PARENT_DAYS_READONLY";
    public static final String INVALID_DAY_PRECISION = "INVALID_DAY_PRECISION";

    private RequirementRules() {
    }

    /** 一条规则违规：HTTP 状态 + 稳定错误码 + 可读消息 + 结构化 detail。 */
    public record Violation(int status, String code, String message, Map<String, Object> detail) {
        public Violation {
            detail = detail == null ? Map.of() : Map.copyOf(detail);
        }
    }

    /**
     * 创建/拆解时校验父子类型组合：只允许 BUS → PDR → RD。
     *
     * @param childType  子需求类型
     * @param parentType 父需求类型；无父传 {@code null}
     */
    public static Violation checkParentCombination(String childType, String parentType) {
        if (!isKnownType(childType)) {
            return new Violation(400, VALIDATION_FAILED,
                    "未知需求类型：" + childType, Map.of("field", "type"));
        }
        if (TYPE_BUS.equals(childType)) {
            if (parentType != null) {
                return new Violation(400, INVALID_PARENT,
                        "业务需求必须是顶层，不能挂父需求", Map.of("parentType", parentType));
            }
            return null;
        }
        if (parentType == null) {
            return new Violation(400, INVALID_PARENT,
                    ("PDR".equals(childType) ? "产品需求" : "研发需求") + "必须指定父需求",
                    Map.of("childType", childType));
        }
        if (!((TYPE_PDR.equals(childType) && TYPE_BUS.equals(parentType))
                || (TYPE_RD.equals(childType) && TYPE_PDR.equals(parentType)))) {
            return new Violation(400, INVALID_PARENT,
                    "不允许的父子组合：" + parentType + " → " + childType + "（仅 BUS → PDR → RD）",
                    Map.of("parentType", parentType, "childType", childType));
        }
        return null;
    }

    /**
     * 祖先环检测：从 {@code parentId} 向上走，若回到 {@code childId} 则拒绝。
     * 正常情况下类型分层已经排除环，这里仍显式校验，防止脏数据/绕过接口写入形成的环。
     */
    public static Violation checkAncestorCycle(Long childId, Long parentId,
                                               java.util.function.Function<Long, Long> parentOf) {
        if (childId == null || parentId == null) {
            return null;
        }
        Long cursor = parentId;
        int guard = 0;
        while (cursor != null && guard++ < 64) {
            if (cursor.equals(childId)) {
                return new Violation(400, INVALID_PARENT,
                        "父需求形成祖先环，已拒绝", Map.of("parentId", parentId));
            }
            cursor = parentOf.apply(cursor);
        }
        return null;
    }

    /**
     * 人天规则：父需求（BUS/PDR）只读汇总，不接受手填；RD 允许 0.5 天精度。
     */
    public static Violation checkDays(String type, BigDecimal estimateDays, BigDecimal actualDays) {
        if (TYPE_BUS.equals(type) || TYPE_PDR.equals(type)) {
            if (estimateDays != null || actualDays != null) {
                return new Violation(400, PARENT_DAYS_READONLY,
                        "父需求人天由子需求汇总，不可手工填写",
                        Map.of("type", type));
            }
            return null;
        }
        Violation estimate = checkHalfDay(estimateDays, "estimateDays");
        if (estimate != null) {
            return estimate;
        }
        return checkHalfDay(actualDays, "actualDays");
    }

    /** 单个天数字段：非负且是 0.5 的整数倍。 */
    public static Violation checkHalfDay(BigDecimal days, String field) {
        if (days == null) {
            return null;
        }
        if (days.signum() < 0) {
            return new Violation(400, INVALID_DAY_PRECISION,
                    "人天不能为负数", Map.of("field", field, "value", days));
        }
        if (days.remainder(new BigDecimal("0.5")).compareTo(BigDecimal.ZERO) != 0) {
            return new Violation(400, INVALID_DAY_PRECISION,
                    "人天必须为 0.5 的整数倍", Map.of("field", field, "value", days));
        }
        return null;
    }

    /** 拆解时推导子类型：BUS → PDR → RD；RD 不可再拆。 */
    public static String childTypeOf(String parentType) {
        if (TYPE_BUS.equals(parentType)) {
            return TYPE_PDR;
        }
        if (TYPE_PDR.equals(parentType)) {
            return TYPE_RD;
        }
        return null;
    }

    /** 完成态判定交给状态表；这里只做"这批子需求完成几个"的纯计算。 */
    public static long doneCount(List<String> childStatuses, java.util.function.Predicate<String> terminal) {
        return childStatuses.stream().filter(terminal).count();
    }

    public static boolean isKnownType(String type) {
        return TYPE_BUS.equals(type) || TYPE_PDR.equals(type) || TYPE_RD.equals(type);
    }
}
