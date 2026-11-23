package com.example.req.requirement.change;

import com.example.req.requirement.RequirementDtos;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementErrors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PUT 收口：内容类字段在早期态之外只能走变更单（AC-13、技术方案 §4.2）。
 *
 * <p>为什么必须在 {@code requireWritable} 之后、任何字段写入之前调用：PUT 是一条已经存在的
 * 写入口，收口不是靠前端少给入口，而是靠后端在「真实变化」发生时给 409。放在写之前，
 * 冲突失败时一行都没写（无部分写）。</p>
 *
 * <p>三条不拦的情形：① 请求里根本没带这些字段（例如只改 domainIds/sonarProjectKey）；
 * ② 归一化后与当前库值相同（幂等 PUT 不能因为「提交了整张表单」被拦）；
 * ③ 需求处于该类型的早期态（BUS 三态 / PDR PENDING_ANALYSIS / RD TODO）——早期态本来就
 * 允许直接编辑，这由 {@link RequirementChangeFields#editableFields} 单点判定。</p>
 */
public final class RequirementChangeGuard {

    /** 变更单集合端点（相对网关前缀），作为 409 detail 里的引导地址。 */
    static final String CHANGE_ENDPOINT_TEMPLATE = "/api/req/requirements/%d/changes";

    private RequirementChangeGuard() {
    }

    /**
     * @throws com.example.platform.common.error.ApiException 409 {@code CHANGE_REQUIRED}，
     *         detail 带受拦字段与变更端点（前端据此打开预填抽屉）
     */
    public static void rejectDirectEdit(RequirementEntity entity, RequirementDtos.UpdateRequest request) {
        if (entity == null) {
            return;
        }
        rejectDirectEdit(entity, RequirementChangeFields.snapshot(entity), request);
    }

    /**
     * 带「改前快照」的版本：PUT 会先跑完入参校验（父链/环/人天精度等）再收口，
     * 那些校验会就地写 entity，所以改前的值必须由调用方先冻结后传入。
     */
    public static void rejectDirectEdit(RequirementEntity entity, Map<String, Object> current,
                                        RequirementDtos.UpdateRequest request) {
        if (entity == null || request == null || current == null) {
            return;
        }
        Map<String, Object> patch = patchOf(request);
        if (patch.isEmpty()) {
            return;
        }
        List<String> changed = new ArrayList<>();
        for (String field : RequirementChangeFields.GUARDED_FIELDS) {
            if (!patch.containsKey(field)) {
                continue;
            }
            // 归一化与创建/预览共用同一个函数：非法值在这里就以 400 暴露，和 PUT 既有校验同码。
            Object normalized = RequirementChangeFields.normalize(field, patch.get(field));
            if (!unchanged(field, current.get(field), normalized)) {
                changed.add(field);
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        if (RequirementChangeFields.earlyStage(entity.getType(), entity.getStatus())) {
            return;
        }
        throw RequirementErrors.changeRequired(entity.getId(), changed,
                String.format(CHANGE_ENDPOINT_TEMPLATE, entity.getId()));
    }

    /**
     * 「值没变」判定。人天要按数值比而不是按文本比：PUT 传 {@code 3} 归一化成 {@code 3.0}，
     * 库里是 {@code 3}，{@code asText} 一个 "3.0" 一个 "3"——直接用它会把这单幂等保存
     * 误判成「改了人天」，弹一个不该有的 409。
     */
    private static boolean unchanged(String field, Object current, Object normalized) {
        if (RequirementChangeFields.F_ESTIMATE_DAYS.equals(field)
                && current instanceof java.math.BigDecimal left
                && normalized instanceof java.math.BigDecimal right) {
            return left.compareTo(right) == 0;
        }
        return RequirementChangeFields.sameValue(current, normalized);
    }

    /** 请求体里实际携带的受管字段（camelCase，与快照键同一命名）。 */
    private static Map<String, Object> patchOf(RequirementDtos.UpdateRequest request) {
        Map<String, Object> patch = new java.util.LinkedHashMap<>();
        if (request.title != null) {
            patch.put(RequirementChangeFields.F_TITLE, request.title);
        }
        if (request.description != null) {
            patch.put(RequirementChangeFields.F_DESCRIPTION, request.description);
        }
        if (request.priority != null) {
            patch.put(RequirementChangeFields.F_PRIORITY, request.priority);
        }
        if (request.expectedDeliveryDate != null) {
            patch.put(RequirementChangeFields.F_EXPECTED_DELIVERY_DATE, request.expectedDeliveryDate);
        }
        Map<String, Object> extra = request.extra();
        if (extra != null) {
            for (String field : List.of(RequirementChangeFields.F_ESTIMATE_DAYS,
                    RequirementChangeFields.F_PARENT_ID, RequirementChangeFields.F_RELEASE_ID)) {
                if (extra.containsKey(field)) {
                    patch.put(field, extra.get(field));
                }
            }
        }
        return patch;
    }
}
