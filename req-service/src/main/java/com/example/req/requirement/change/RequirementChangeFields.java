package com.example.req.requirement.change;

import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementErrors;
import com.example.req.requirement.RequirementRollup;
import com.example.req.requirement.RequirementRules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 变更白名单、7 字段快照、字段归一化与逐类型早期态——这些规则只允许存在这一份。
 *
 * <p>为什么快照不复用 {@code RequirementService.update} 的 auditSnapshot：后者故意不含
 * description/parentId，而变更单的 before/after 必须覆盖全部可变更字段，否则 apply 无法
 * 证明「改了什么」。两者形状相似但语义不同，合并会让审计口径互相污染。</p>
 *
 * <p>为什么前端不复制早期态表：前端只读服务端下发的 {@code permissions.editableFields}，
 * 状态表分支只在这里维护，避免「后端放行、前端仍锁」这类双源漂移。</p>
 */
public final class RequirementChangeFields {

    public static final String TYPE_CONTENT = "CONTENT";
    public static final String TYPE_DAYS = "DAYS";
    public static final String TYPE_PARENT = "PARENT";
    public static final String TYPE_RELEASE = "RELEASE";

    public static final List<String> TYPES =
            List.of(TYPE_CONTENT, TYPE_DAYS, TYPE_PARENT, TYPE_RELEASE);

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    public static final String STATUS_APPLIED = "APPLIED";

    public static final String F_TITLE = "title";
    public static final String F_DESCRIPTION = "description";
    public static final String F_PRIORITY = "priority";
    public static final String F_EXPECTED_DELIVERY_DATE = "expectedDeliveryDate";
    public static final String F_ESTIMATE_DAYS = "estimateDays";
    public static final String F_RELEASE_ID = "releaseId";
    public static final String F_PARENT_ID = "parentId";

    /** 快照键的固定顺序，也是前后端字段顺序与对照表行序的唯一来源。 */
    public static final List<String> FIELDS = List.of(
            F_TITLE, F_DESCRIPTION, F_PRIORITY, F_EXPECTED_DELIVERY_DATE,
            F_ESTIMATE_DAYS, F_RELEASE_ID, F_PARENT_ID);

    public static final List<String> CONTENT_FIELDS = List.of(
            F_TITLE, F_DESCRIPTION, F_PRIORITY, F_EXPECTED_DELIVERY_DATE);

    public static final List<String> GUARDED_FIELDS = FIELDS;

    private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2", "P3");

    private static final Map<String, List<String>> EARLY_STATUSES = Map.of(
            RequirementRules.TYPE_BUS, List.of("SUBMITTED", "APPROVING", "PENDING_REVIEW"),
            RequirementRules.TYPE_PDR, List.of("PENDING_ANALYSIS"),
            RequirementRules.TYPE_RD, List.of("TODO"));

    private RequirementChangeFields() {
    }

    /** 某变更类型可以携带的字段；未知类型返回空列表（由 Validator 先判类型）。 */
    public static List<String> allowedFields(String changeType) {
        if (TYPE_CONTENT.equals(changeType)) {
            return CONTENT_FIELDS;
        }
        if (TYPE_DAYS.equals(changeType)) {
            return List.of(F_ESTIMATE_DAYS);
        }
        if (TYPE_PARENT.equals(changeType)) {
            return List.of(F_PARENT_ID);
        }
        if (TYPE_RELEASE.equals(changeType)) {
            return List.of(F_RELEASE_ID);
        }
        return List.of();
    }

    public static boolean knownType(String changeType) {
        return TYPES.contains(changeType);
    }

    /** 需求当前 7 字段快照（LinkedHashMap → 落库 JSON 的键序稳定，对照表不用再排序）。 */
    public static Map<String, Object> snapshot(RequirementEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(F_TITLE, entity.getTitle());
        map.put(F_DESCRIPTION, entity.getDescription());
        map.put(F_PRIORITY, entity.getPriority());
        map.put(F_EXPECTED_DELIVERY_DATE, entity.getExpectedDeliveryDate());
        map.put(F_ESTIMATE_DAYS, entity.getEstimateDays());
        map.put(F_RELEASE_ID, entity.getReleaseId());
        map.put(F_PARENT_ID, entity.getParentId());
        return map;
    }

    /** 在 before 上合并 patch（逐字段归一化）；未知键直接 400，不静默丢弃。 */
    public static Map<String, Object> merge(Map<String, Object> before, Map<String, Object> patch) {
        Map<String, Object> after = new LinkedHashMap<>(before);
        if (patch == null) {
            return after;
        }
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String field = entry.getKey();
            if (!FIELDS.contains(field)) {
                throw RequirementErrors.validation("未知的变更字段: " + field);
            }
            after.put(field, normalize(field, entry.getValue()));
        }
        return after;
    }

    /** 按固定字段顺序比较；null 与空串视为同值（展示文本相同）。 */
    public static List<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
        List<String> changed = new ArrayList<>();
        for (String field : FIELDS) {
            Object left = before == null ? null : before.get(field);
            Object right = after == null ? null : after.get(field);
            if (!sameValue(left, right)) {
                changed.add(field);
            }
        }
        return changed;
    }

    public static boolean sameValue(Object a, Object b) {
        return asText(a).equals(asText(b));
    }

    /** 归一化单个字段；类型/范围非法一律 400（与 PUT 同错误结构）。 */
    public static Object normalize(String field, Object raw) {
        if (F_TITLE.equals(field)) {
            return normalizeText(raw, F_TITLE, 200, false);
        }
        if (F_DESCRIPTION.equals(field)) {
            return normalizeText(raw, F_DESCRIPTION, 0, true);
        }
        if (F_PRIORITY.equals(field)) {
            return normalizePriority(raw);
        }
        if (F_EXPECTED_DELIVERY_DATE.equals(field)) {
            return normalizeDate(raw);
        }
        if (F_ESTIMATE_DAYS.equals(field)) {
            return normalizeDays(raw);
        }
        if (F_RELEASE_ID.equals(field) || F_PARENT_ID.equals(field)) {
            return normalizeId(field, raw);
        }
        throw RequirementErrors.validation("未知的变更字段: " + field);
    }

    /** 展示文本：null→空串；数值用 toPlainString（5.0 不变成 5）；日期用 ISO。 */
    public static String asText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).toPlainString();
        }
        return String.valueOf(value);
    }

    /**
     * 逐类型早期态：只有列表中的状态允许直接 PUT 这些字段，其余状态必须走变更单。
     * 返回全部 7 字段表示「当前可直改」，空列表表示「全部需变更单」。
     */
    public static List<String> editableFields(String type, String status) {
        if (type == null || status == null) {
            return List.of();
        }
        List<String> early = EARLY_STATUSES.get(type);
        return early != null && early.contains(status) ? FIELDS : List.of();
    }

    /** 给定状态是否处于该类型的早期态。 */
    public static boolean earlyStage(String type, String status) {
        return !editableFields(type, status).isEmpty();
    }

    // ------------------------------------------------------------------ 归一化

    private static String normalizeText(Object raw, String field, int maxLength, boolean allowEmpty) {
        if (raw == null) {
            if (allowEmpty) {
                return null;
            }
            throw RequirementErrors.validation(field + " 不能为空");
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            if (allowEmpty) {
                return "";
            }
            throw RequirementErrors.validation(field + " 不能为空");
        }
        if (maxLength > 0 && text.length() > maxLength) {
            throw RequirementErrors.validation(field + " 不能超过 " + maxLength + " 字");
        }
        return text;
    }

    private static String normalizePriority(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return "P2";
        }
        String priority = String.valueOf(raw).trim().toUpperCase();
        if (!PRIORITIES.contains(priority)) {
            throw RequirementErrors.validation("priority 只能是 P0/P1/P2/P3");
        }
        return priority;
    }

    private static LocalDate normalizeDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate date) {
            return date;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw RequirementErrors.validation("expectedDeliveryDate 不是合法日期: " + text);
        }
    }

    private static BigDecimal normalizeDays(Object raw) {
        if (raw == null) {
            throw RequirementErrors.validation("estimateDays 不能为空");
        }
        BigDecimal parsed;
        if (raw instanceof BigDecimal decimal) {
            parsed = decimal;
        } else {
            try {
                parsed = new BigDecimal(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw RequirementErrors.validation("estimateDays 必须是数字");
            }
        }
        // 精度必须在 scale 之前判：RequirementRollup.scale 会把 12.01 静默舍成 12.0，
        // 于是 20.1% 的偏离变成 20.0%（不转 STALE），前端看到的 after 也与申请值不同。
        RequirementRules.Violation violation =
                RequirementRules.checkHalfDay(parsed, F_ESTIMATE_DAYS);
        if (violation != null) {
            throw RequirementErrors.rule(violation);
        }
        return RequirementRollup.scale(parsed);
    }

    private static Long normalizeId(String field, Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw RequirementErrors.validation(field + " 必须是数字 ID");
        }
    }
}
