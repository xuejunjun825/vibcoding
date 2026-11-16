package com.example.req.requirement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /** 父状态门错误码（409）：BUS 未入池 / PDR 未排期。 */
    public static final String PARENT_NOT_POOLED = "PARENT_NOT_POOLED";
    public static final String PARENT_NOT_SCHEDULED = "PARENT_NOT_SCHEDULED";
    /** 业务需求入池状态（拆 PDR 的唯一合法父状态）。 */
    public static final String REQUIRED_POOLED = "POOLED";
    /** RD 的父 PDR 允许状态；与 StateTables 的 PDR 状态表由单测对齐，防状态改名漂移。 */
    public static final List<String> RD_PARENT_ALLOWED = List.of("SCHEDULED", "IN_DEV");

    private RequirementRules() {
    }

    /** 一条规则违规：HTTP 状态 + 稳定错误码 + 可读消息 + 结构化 detail。 */
    public record Violation(int status, String code, String message, Map<String, Object> detail) {
        public Violation {
            // 保持插入顺序（detail 的 key 顺序是接口契约的一部分：parentId/parentStatus/required）。
            detail = detail == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
        }
    }

    /** 父状态门判定结果：allowed=false 时 code/message 必填；required 为「需要什么状态」（信息性）。 */
    public record ParentGate(boolean allowed, String code, String message, Object required) {
    }

    /**
     * 唯一状态门判定：BUS 父要求已入池（POOLED）；PDR 父要求已排期（SCHEDULED/IN_DEV）。
     *
     * <p>纯函数，不查库：服务层取到父实体后调用一次即可，三个写入口（decompose / 直建 / PUT 改父）
     * 因此共用同一套状态语义与同一段中文。</p>
     */
    public static ParentGate parentGate(String parentType, String parentStatus) {
        if (TYPE_BUS.equals(parentType)) {
            boolean allowed = REQUIRED_POOLED.equals(parentStatus);
            return new ParentGate(allowed, allowed ? null : PARENT_NOT_POOLED,
                    allowed ? null
                            : "父需求尚未入池（当前 " + parentStatus + "，需 POOLED），入池后才能创建下级需求",
                    REQUIRED_POOLED);
        }
        if (TYPE_PDR.equals(parentType)) {
            boolean allowed = RD_PARENT_ALLOWED.contains(parentStatus);
            return new ParentGate(allowed, allowed ? null : PARENT_NOT_SCHEDULED,
                    allowed ? null
                            : "父产品需求需先排期（当前 " + parentStatus
                                    + "，需 SCHEDULED 或 IN_DEV），排期后才能创建研发需求",
                    RD_PARENT_ALLOWED);
        }
        // 只可能来自脏数据；BUS/PDR/RD 之外的父类型在 checkParentCombination 就被拦下。
        throw new IllegalArgumentException("未知父需求类型：" + parentType);
    }

    /** 把「未通过」翻译成 409 Violation；通过时返回 null（供 Service.applyRule 使用）。 */
    public static Violation parentGateViolation(long parentId, String parentStatus, ParentGate gate) {
        if (gate == null || gate.allowed()) {
            return null;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("parentId", parentId);
        detail.put("parentStatus", parentStatus);
        if (PARENT_NOT_POOLED.equals(gate.code())) {
            detail.put("required", gate.required());
        } else {
            detail.put("allowed", gate.required());
        }
        return new Violation(409, gate.code(), gate.message(), detail);
    }

    /**
     * 详情 {@code view.decomposeGate} 的同一来源：RD 不可再拆 → allowed=false；BUS/PDR 委托
     * {@link #parentGate}，因此详情里的原因与 409 的 message 逐字相同。
     */
    public static ParentGate decomposeGate(String type, String status) {
        if (TYPE_BUS.equals(type) || TYPE_PDR.equals(type)) {
            return parentGate(type, status);
        }
        return new ParentGate(false, null, "该类型不能再拆解：" + type, null);
    }

    /**
     * 提报入口 domainIds 必填校验：缺失/空/null → 400 {field:"domainIds"}；
     * 含 null/非正整数 → 400 {field:"domainIds", invalidIds:[…]}。
     */
    public static Violation checkDomainIds(List<Long> domainIds) {
        if (domainIds == null || domainIds.isEmpty()) {
            return new Violation(400, VALIDATION_FAILED, "请选择业务域", Map.of("field", "domainIds"));
        }
        List<Long> invalid = new ArrayList<>();
        for (Long id : domainIds) {
            if (id == null || id <= 0) {
                invalid.add(id);
            }
        }
        if (!invalid.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("field", "domainIds");
            detail.put("invalidIds", invalid);
            return new Violation(400, VALIDATION_FAILED, "业务域包含无效值", detail);
        }
        return null;
    }

    /** 去重保序（null 元素已被 {@link #checkDomainIds} 拦下）。 */
    public static List<Long> distinctDomainIds(List<Long> domainIds) {
        if (domainIds == null || domainIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(domainIds));
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
