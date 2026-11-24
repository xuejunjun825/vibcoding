package com.example.platform.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 需求字段可编辑性矩阵：3 类型 × 全部状态 × 11 字段 → {@code EDIT}/{@code CHANGE_REQUIRED}/{@code LOCKED}
 * 与 9 个原因码。
 *
 * <p>可编辑性是<b>协议</b>而不是服务内部实现：详情响应用它投影三数组，PUT 用它决定
 * {@code FIELD_LOCKED}/{@code CHANGE_REQUIRED}，变更单与门禁读同一张表。任何第二份
 * 「状态 → 字段」判定都会让「接口说能改、PUT 却拒绝」这类漂移重新出现，所以表只存在于这里，
 * 且纯静态、无 Spring、无 IO、无时间/随机输入。</p>
 *
 * <p>判定顺序是契约的一部分（见 {@link #rule}）：未知状态 fail-closed 最先，其次结构字段
 * {@code type}，再次终态；顺序一旦交换，终态改 {@code type} 会报 {@code TERMINAL} 而不是
 * {@code IMMUTABLE}，前端文案与门禁断言都会跟着错。</p>
 */
public final class RequirementEditability {

    /** 11 个可判定字段的规范序（输出顺序、三数组顺序、变更单 fields 顺序都取此序）。 */
    public static final String F_TITLE = "title";
    public static final String F_DESCRIPTION = "description";
    public static final String F_PRIORITY = "priority";
    public static final String F_EXPECTED_DELIVERY_DATE = "expectedDeliveryDate";
    public static final String F_ESTIMATE_DAYS = "estimateDays";
    public static final String F_PARENT_ID = "parentId";
    public static final String F_RELEASE_ID = "releaseId";
    public static final String F_DOMAIN_IDS = "domainIds";
    public static final String F_SONAR_PROJECT_KEY = "sonarProjectKey";
    public static final String F_OWNER_USER_ID = "ownerUserId";
    public static final String F_TYPE = "type";

    public static final List<String> FIELDS = List.of(
            F_TITLE, F_DESCRIPTION, F_PRIORITY, F_EXPECTED_DELIVERY_DATE,
            F_ESTIMATE_DAYS, F_PARENT_ID, F_RELEASE_ID,
            F_DOMAIN_IDS, F_SONAR_PROJECT_KEY, F_OWNER_USER_ID, F_TYPE);

    // 原因码：与 web/src/views/req/editability.ts 的 REASON_TEXT 键集合一一对应。
    public static final String EARLY_STAGE = "EARLY_STAGE";
    public static final String OPEN_STATE = "OPEN_STATE";
    public static final String CHANGE_REQUIRED = "CHANGE_REQUIRED";
    public static final String TERMINAL = "TERMINAL";
    public static final String PARENT_ROLLUP = "PARENT_ROLLUP";
    public static final String NO_PARENT = "NO_PARENT";
    public static final String ASSIGN_FLOW = "ASSIGN_FLOW";
    public static final String IMMUTABLE = "IMMUTABLE";
    public static final String UNKNOWN_STATUS = "UNKNOWN_STATUS";

    /**
     * 逐类型早期态（r40 口径）：只有这些状态允许直接改内容字段，其余状态必须走变更单。
     * 这张表只在这里维护；{@code RequirementChangeFields} 已改为委托。
     */
    private static final Map<String, Set<String>> EARLY_STATUSES = earlyStatuses();

    /** 矩阵只覆盖需求三种类型；DEFECT 等其它 StateTables 机器一律 fail-closed 成 UNKNOWN_STATUS。 */
    private static final Set<String> TYPES = EARLY_STATUSES.keySet();

    public enum Decision {
        EDIT,
        CHANGE_REQUIRED,
        LOCKED
    }

    public record Rule(String field, Decision decision, String reasonCode) {
        public Rule {
            if (field == null || decision == null || reasonCode == null) {
                throw new IllegalArgumentException("rule 的 field/decision/reasonCode 不能为 null");
            }
        }
    }

    private RequirementEditability() {
    }

    /** 按规范序给出全部 11 条判定；未知 type/status 时 11 条都是 {@code LOCKED/UNKNOWN_STATUS}。 */
    public static List<Rule> rules(String type, String status) {
        List<Rule> rules = new ArrayList<>(FIELDS.size());
        for (String field : FIELDS) {
            rules.add(rule(type, status, field));
        }
        return List.copyOf(rules);
    }

    /**
     * 单字段判定，命中即返回，顺序不可换：
     * <ol>
     *   <li>未知 type 或 status → {@code LOCKED/UNKNOWN_STATUS}（fail-closed 最先）</li>
     *   <li>{@code type} → {@code LOCKED/IMMUTABLE}</li>
     *   <li>终态 → {@code LOCKED/TERMINAL}</li>
     *   <li>BUS 的 {@code parentId} → {@code LOCKED/NO_PARENT}</li>
     *   <li>非 RD 的 {@code estimateDays} → {@code LOCKED/PARENT_ROLLUP}</li>
     *   <li>{@code ownerUserId} → {@code LOCKED/ASSIGN_FLOW}</li>
     *   <li>{@code domainIds}/{@code sonarProjectKey} → {@code EDIT/OPEN_STATE}</li>
     *   <li>早期态 → {@code EDIT/EARLY_STAGE}</li>
     *   <li>其余 → {@code CHANGE_REQUIRED/CHANGE_REQUIRED}</li>
     * </ol>
     */
    public static Rule rule(String type, String status, String field) {
        String normalizedType = normalize(type);
        String normalizedStatus = normalize(status);
        if (!knownState(normalizedType, normalizedStatus) || field == null || !FIELDS.contains(field)) {
            // 未知字段也不抛异常：PUT 带着未知键进来时应得到明确 409，而不是 500。
            return new Rule(field == null ? "" : field, Decision.LOCKED, UNKNOWN_STATUS);
        }
        if (F_TYPE.equals(field)) {
            return new Rule(field, Decision.LOCKED, IMMUTABLE);
        }
        if (StateTables.isTerminal(normalizedType, normalizedStatus)) {
            return new Rule(field, Decision.LOCKED, TERMINAL);
        }
        if (F_PARENT_ID.equals(field) && StateTables.BUS.equals(normalizedType)) {
            return new Rule(field, Decision.LOCKED, NO_PARENT);
        }
        if (F_ESTIMATE_DAYS.equals(field) && !StateTables.RD.equals(normalizedType)) {
            return new Rule(field, Decision.LOCKED, PARENT_ROLLUP);
        }
        if (F_OWNER_USER_ID.equals(field)) {
            return new Rule(field, Decision.LOCKED, ASSIGN_FLOW);
        }
        if (F_DOMAIN_IDS.equals(field) || F_SONAR_PROJECT_KEY.equals(field)) {
            return new Rule(field, Decision.EDIT, OPEN_STATE);
        }
        if (earlyStage(normalizedType, normalizedStatus)) {
            return new Rule(field, Decision.EDIT, EARLY_STAGE);
        }
        return new Rule(field, Decision.CHANGE_REQUIRED, CHANGE_REQUIRED);
    }

    public static Decision decision(String type, String status, String field) {
        return rule(type, status, field).decision();
    }

    public static String reasonCode(String type, String status, String field) {
        return rule(type, status, field).reasonCode();
    }

    /** 当前状态可直接修改的字段（规范序子序列）。 */
    public static List<String> editableFields(String type, String status) {
        return fieldsWith(type, status, Decision.EDIT);
    }

    /** 当前状态必须走变更单的字段（规范序子序列）。 */
    public static List<String> changeRequiredFields(String type, String status) {
        return fieldsWith(type, status, Decision.CHANGE_REQUIRED);
    }

    /** 当前状态完全不可修改的字段（规范序子序列）。 */
    public static List<String> lockedFields(String type, String status) {
        return fieldsWith(type, status, Decision.LOCKED);
    }

    /** 给定状态是否处于该类型的早期态；未知 type/status 一律 false（fail-closed 由 {@link #rule} 负责）。 */
    public static boolean earlyStage(String type, String status) {
        String normalizedType = normalize(type);
        String normalizedStatus = normalize(status);
        if (!knownState(normalizedType, normalizedStatus)) {
            return false;
        }
        Set<String> early = EARLY_STATUSES.get(normalizedType);
        return early != null && early.contains(normalizedStatus);
    }

    // ------------------------------------------------------------------ 内部

    private static List<String> fieldsWith(String type, String status, Decision decision) {
        List<String> fields = new ArrayList<>();
        for (Rule rule : rules(type, status)) {
            if (rule.decision() == decision) {
                fields.add(rule.field());
            }
        }
        return List.copyOf(fields);
    }

    private static boolean knownState(String type, String status) {
        if (type == null || status == null) {
            return false;
        }
        if (!TYPES.contains(type)) {
            return false;
        }
        // 终态没有出边，因此不在 StateTables.statuses(type) 的行里（只有 TERMINAL 表登记）；
        // 只按 statuses 判会把 CLOSED/ACCEPTED_CLOSED/DONE 误判成 UNKNOWN_STATUS。
        return StateTables.statuses(type).contains(status)
                || StateTables.isTerminal(type, status);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static Map<String, Set<String>> earlyStatuses() {
        Map<String, Set<String>> table = new LinkedHashMap<>();
        table.put(StateTables.BUS, new LinkedHashSet<>(List.of("SUBMITTED", "APPROVING", "PENDING_REVIEW")));
        table.put(StateTables.PDR, new LinkedHashSet<>(List.of("PENDING_ANALYSIS")));
        table.put(StateTables.RD, new LinkedHashSet<>(List.of("TODO")));
        return Map.copyOf(table);
    }
}
