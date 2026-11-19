package com.example.req.release;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.platform.common.scope.ScopedJdbc;
import com.example.req.deploy.DeployOrderStates;
import com.example.req.requirement.RequirementErrors;

/**
 * 分级必填项的服务端校验（r30 / AC-5 / AC-7）：等级枚举、紧急原因、回滚来源单号。
 *
 * <p>为什么三处都要调它（创建、改等级、发起审批）：AC-7 要求“创建/修改版本与发起审批两处都校验”，
 * 而这三处是<b>唯一</b>能把等级写进库的入口。校验逻辑只在这里一份，任何一处漏调都会让
 * 「非法等级 + 空原因」从别的口子进来，所以调用点是刻意重复的，判定不重复。</p>
 *
 * <p>回滚来源单号的可见性直接复用 {@link ScopedJdbc} 的租户过滤
 * （{@code deploy_order} 只按租户过滤，无域维度），与列表页看到的发布单集合<b>同一口径</b>——
 * 不会出现“校验说可见、列表里看不到”的分歧。找不到与不可见返回同一个 400，
 * 避免用错误差异探测他人单据是否存在。</p>
 */
@Component
public class ReleaseLevelValidator {

    /** 可以作为回滚来源的发布单状态：已放行或已执行成功（驳回/执行中/失败都不算）。 */
    private static final List<String> ROLLBACK_SOURCES =
            List.of(DeployOrderStates.APPROVED, DeployOrderStates.SUCCESS);

    /** 紧急原因长度上限，与 {@code release.emergency_reason VARCHAR(500)} 一致。 */
    private static final int REASON_MAX = 500;

    private final ScopedJdbc jdbc;

    public ReleaseLevelValidator(ScopedJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 校验结果：<b>已归一化</b>的三元组，调用方直接落库，不要再自己 trim/转大写。
     *
     * <p>{@code NORMAL} 时两个字段恒为 {@code null}：换回常规发布必须把上一次的紧急原因/回滚单号清掉，
     * 否则详情页会显示一条与当前等级无关的历史说明（前端按等级决定是否展示，但库里的脏值会污染导出）。</p>
     */
    public record Validated(String level, String emergencyReason, String previousDeployOrderCode) {

        /** 审计用快照（before/after 同形）；{@code Map.of} 不收 null，所以走 LinkedHashMap。 */
        public Map<String, Object> asMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("releaseLevel", level);
            map.put("emergencyReason", emergencyReason);
            map.put("previousDeployOrderCode", previousDeployOrderCode);
            return map;
        }
    }

    public Validated validate(long tenantId, String level, String emergencyReason,
                              String previousDeployOrderCode) {
        String normalised = ReleaseLevel.normalise(level);
        if (!ReleaseLevel.isValid(normalised)) {
            throw RequirementErrors.validation("非法的发布等级：" + level + "（仅支持 NORMAL / EMERGENCY / ROLLBACK）",
                    Map.of("field", "releaseLevel", "value", level == null ? "" : level));
        }
        if (ReleaseLevel.EMERGENCY.equals(normalised)) {
            String reason = emergencyReason == null ? null : emergencyReason.trim();
            if (reason == null || reason.isEmpty() || reason.length() > REASON_MAX) {
                throw RequirementErrors.validation("紧急修复必须填写原因，且不超过 " + REASON_MAX + " 字",
                        Map.of("field", "emergencyReason", "maxLength", REASON_MAX,
                                "length", reason == null ? 0 : reason.length()));
            }
            return new Validated(ReleaseLevel.EMERGENCY, reason, null);
        }
        if (ReleaseLevel.ROLLBACK.equals(normalised)) {
            String code = previousDeployOrderCode == null ? null : previousDeployOrderCode.trim();
            if (code == null || code.isEmpty()) {
                throw RequirementErrors.validation("回滚必须填写上一张发布单号",
                        Map.of("field", "previousDeployOrderCode"));
            }
            Map<String, Object> order = jdbc.one(
                    "SELECT o.id, o.code, o.status FROM deploy_order o "
                            + "WHERE o.code = :code AND o.tenant_id = :tenantId AND /*scope:o*/ LIMIT 1",
                    Map.of("code", code, "tenantId", tenantId));
            if (order == null) {
                throw RequirementErrors.validation("回滚来源发布单不存在或不可见：" + code,
                        Map.of("field", "previousDeployOrderCode", "code", code));
            }
            String status = order.get("status") == null ? "" : String.valueOf(order.get("status"));
            if (!ROLLBACK_SOURCES.contains(status)) {
                throw RequirementErrors.validation(
                        "回滚来源发布单状态必须是 APPROVED 或 SUCCESS，当前为：" + status,
                        Map.of("field", "previousDeployOrderCode", "code", code, "status", status));
            }
            return new Validated(ReleaseLevel.ROLLBACK, null, code);
        }
        return new Validated(ReleaseLevel.NORMAL, null, null);
    }
}
