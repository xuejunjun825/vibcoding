package com.example.req.requirement;

import com.example.platform.common.error.ApiException;

import java.util.Set;

/**
 * 重复合并的前置规则（r47 契约 §3.3）：纯函数，不碰数据库，供服务层在锁内调用、供单测逐条打靶。
 *
 * <p>为什么把校验从 {@link RequirementMergeService} 里拆出来：六条前置里有四条只是「两个不可变
 * 快照 + 一个父子关系布尔」的判断，混在事务代码里就只能靠起库测；拆出来后每条错误码都能用一个
 * record 构造出来直接断言。</p>
 */
public final class RequirementMergeRules {

    /**
     * 目标必须「还能承接新内容」：已排期、研发中，或池子里待排。
     *
     * <p>为什么不含 SUBMITTED/APPROVING/PENDING_REVIEW：合并是运营动作，目标还没被评审/排期时
     * 合并会绕过审批语义；为什么不含终态：终态目标合进去等于「合进一个已关闭的需求」。</p>
     */
    public static final Set<String> TARGET_STATUSES =
            Set.of("POOLED", "SCHEDULED", "IN_DEV");

    private RequirementMergeRules() {
    }

    /** 合并源快照；{@code mergedIntoId} 非空即已合并过。 */
    public record Source(long id, String type, String status, Long mergedIntoId, boolean terminal) {
    }

    /** 合并目标快照。 */
    public record Target(long id, String type, String status, Long mergedIntoId) {
    }

    /**
     * 按契约顺序校验，不满足即抛 {@link ApiException}（错误码与状态码见 {@link RequirementErrors}）。
     *
     * <p>两点顺序说明：
     * <ol>
     *   <li>{@code ALREADY_MERGED}/{@code TARGET_MERGED} 放在终态检查之前。合并会把源置为
     *       CLOSED（终态），若先判「源已关闭」就会把「重复合并」误报成 REQUIREMENT_CLOSED，
     *       契约 §6 P3 要求重复合并必须是 409 ALREADY_MERGED。</li>
     *   <li>父子环（{@code MERGE_CYCLE}）放在类型/状态之后：类型不同时先报「只能同类型合并」
     *       信息量更大，环检查也要多一次祖先链查询。</li>
     * </ol>
     *
     * @param related 目标是否与源存在祖先/后代关系（含同一 id）；由服务层查父子链得出
     */
    public static void check(Source source, Target target, boolean related) {
        if (source.id() == target.id()) {
            throw RequirementErrors.mergeCycle(source.id(), target.id());
        }
        if (source.mergedIntoId() != null) {
            throw RequirementErrors.alreadyMerged(source.id(), source.mergedIntoId());
        }
        if (target.mergedIntoId() != null) {
            throw RequirementErrors.targetMerged(target.id(), target.mergedIntoId());
        }
        if (!source.type().equals(target.type())) {
            throw RequirementErrors.validation("只能合并同类型需求（" + source.type()
                    + " → " + target.type() + "）");
        }
        if (source.terminal()) {
            throw RequirementErrors.requirementClosed(source.id(), source.status());
        }
        if (!TARGET_STATUSES.contains(target.status())) {
            throw RequirementErrors.validation("目标需求当前状态不可承接合并（" + target.status() + "）",
                    java.util.Map.of("targetId", target.id(), "status", target.status(),
                            "allowedStatuses", TARGET_STATUSES));
        }
        if (related) {
            throw RequirementErrors.mergeCycle(source.id(), target.id());
        }
    }
}
