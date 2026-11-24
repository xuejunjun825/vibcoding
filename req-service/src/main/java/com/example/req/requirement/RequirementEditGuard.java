package com.example.req.requirement;

import com.example.platform.contract.RequirementEditability;
import com.example.req.requirement.RequirementDtos.UpdateRequest;
import com.example.req.requirement.change.RequirementChangeFields;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PUT 收口（r43）：判定「这一次请求到底动了哪些字段」，再按
 * {@link RequirementEditability} 的矩阵分成两类 409 抛出。
 *
 * <p>为什么独立成类：判定需要覆盖全部 11 个字段（含 {@code domainIds/type/ownerUserId/
 * sonarProjectKey} 这些不在 r40 七字段里的键），而且必须一次分出 LOCKED 与 CHANGE_REQUIRED——
 * 混在 Service 里既会拉长写入路径，也会让「先校验后判定」的顺序再次漂移。</p>
 *
 * <p>本类不写库、不读库：只做归一化比较与抛错，因此可以脱离 Spring 单测。</p>
 */
public final class RequirementEditGuard {

    /** 变更单端点：两类 409 共用，前端据此拼「发起变更」的跳转（r40 迁移而来）。 */
    public static final String CHANGE_ENDPOINT_TEMPLATE = "/api/req/requirements/%d/changes";

    /** 只能经 {@code extra}（JSON any-setter）到达的字段；其余四个是 {@link UpdateRequest} 的具名字段。 */
    private static final List<String> EXTRA_FIELDS = List.of(
            RequirementEditability.F_ESTIMATE_DAYS,
            RequirementEditability.F_PARENT_ID,
            RequirementEditability.F_RELEASE_ID,
            RequirementEditability.F_TYPE,
            RequirementEditability.F_OWNER_USER_ID,
            RequirementEditability.F_SONAR_PROJECT_KEY);

    private static final String ACTUAL_DAYS = "actualDays";

    private RequirementEditGuard() {
    }

    /**
     * 11 字段只读快照，顺序 = {@link RequirementEditability#FIELDS}。
     *
     * <p>{@code domainIds} 做「空 → 主域」归一：库里没有域关系行时按 {@code requirement.domain_id}
     * 单域呈现，否则「清空域」会被误判成一次变化。</p>
     */
    public static Map<String, Object> snapshot(RequirementEntity entity, List<Long> domainIds) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String field : RequirementEditability.FIELDS) {
            snapshot.put(field, valueOf(entity, field, domainIds));
        }
        return snapshot;
    }

    /**
     * 请求实际携带的 11 字段：缺省键不放入（{@code containsKey} 语义），显式 {@code null} 要放入——
     * 「清空父需求/版本/负责人/Sonar 键」本身就是一次真实变化。
     */
    public static Map<String, Object> patch(UpdateRequest request, List<Long> currentDomains) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if (request == null) {
            return patch;
        }
        if (request.title != null) {
            patch.put(RequirementEditability.F_TITLE, request.title);
        }
        if (request.description != null) {
            patch.put(RequirementEditability.F_DESCRIPTION, request.description);
        }
        if (request.priority != null) {
            patch.put(RequirementEditability.F_PRIORITY, request.priority);
        }
        if (request.expectedDeliveryDate != null) {
            patch.put(RequirementEditability.F_EXPECTED_DELIVERY_DATE, request.expectedDeliveryDate);
        }
        if (request.domainIds != null) {
            patch.put(RequirementEditability.F_DOMAIN_IDS,
                    effectiveDomains(request.domainIds, primaryOf(currentDomains)));
        }
        Map<String, Object> extra = request.extra();
        if (extra != null) {
            for (String field : EXTRA_FIELDS) {
                if (extra.containsKey(field)) {
                    patch.put(field, extra.get(field));
                }
            }
        }
        return patch;
    }

    /**
     * 归一化后逐字段比较，返回按规范序排列的真实变化字段。
     *
     * <p>归一化只服务于比较，不做入参校验：非法值在这里记为「变化」，交给后面的矩阵与
     * 既有业务校验去报 400/409，避免守卫抢在校验前面抛错而改变错误码顺序。</p>
     */
    public static List<String> changed(Map<String, Object> before, Map<String, Object> patch) {
        List<String> changed = new ArrayList<>();
        if (patch == null || patch.isEmpty()) {
            return changed;
        }
        for (String field : RequirementEditability.FIELDS) {
            if (!patch.containsKey(field)) {
                continue;
            }
            Object previous = before == null ? null : before.get(field);
            if (!sameValue(field, previous, patch.get(field))) {
                changed.add(field);
            }
        }
        return List.copyOf(changed);
    }

    /**
     * 两类 409 的唯一样子：<b>LOCKED 优先</b>，一次只报一类。
     *
     * <p>混合请求（受控字段 + 锁定字段）若先报 CHANGE_REQUIRED，前端会把整组送进变更单，
     * 但锁定字段根本不可能被批准——所以只要命中 LOCKED，就只报 LOCKED。</p>
     */
    public static void reject(long requirementId, String type, String status,
                              List<String> changedFields) {
        if (changedFields == null || changedFields.isEmpty()) {
            return;
        }
        List<String> locked = new ArrayList<>();
        List<String> controlled = new ArrayList<>();
        for (String field : changedFields) {
            RequirementEditability.Decision decision =
                    RequirementEditability.decision(type, status, field);
            if (decision == RequirementEditability.Decision.LOCKED) {
                locked.add(field);
            } else if (decision == RequirementEditability.Decision.CHANGE_REQUIRED) {
                controlled.add(field);
            }
        }
        if (!locked.isEmpty()) {
            String first = locked.get(0);
            throw RequirementErrors.fieldLocked(requirementId, first,
                    RequirementEditability.reasonCode(type, status, first), locked);
        }
        if (!controlled.isEmpty()) {
            throw RequirementErrors.changeRequired(requirementId, controlled,
                    String.format(CHANGE_ENDPOINT_TEMPLATE, requirementId));
        }
    }

    /**
     * {@code actualDays} 不属于可编辑性矩阵（它没有「字段能不能改」的问题，只有父需求只读），
     * no-op 分支据此决定是否保留 r40 的 {@code requireWritable} 400。
     */
    public static boolean carriesActualDays(UpdateRequest request) {
        return request != null && request.extra() != null && request.extra().containsKey(ACTUAL_DAYS);
    }

    // ------------------------------------------------------------------ 字段取值与归一化

    private static Object valueOf(RequirementEntity entity, String field, List<Long> domainIds) {
        return switch (field) {
            case RequirementEditability.F_TITLE -> entity.getTitle();
            case RequirementEditability.F_DESCRIPTION -> entity.getDescription();
            case RequirementEditability.F_PRIORITY -> entity.getPriority();
            case RequirementEditability.F_EXPECTED_DELIVERY_DATE -> entity.getExpectedDeliveryDate();
            case RequirementEditability.F_ESTIMATE_DAYS -> entity.getEstimateDays();
            case RequirementEditability.F_PARENT_ID -> entity.getParentId();
            case RequirementEditability.F_RELEASE_ID -> entity.getReleaseId();
            case RequirementEditability.F_DOMAIN_IDS ->
                    effectiveDomains(domainIds, entity.getDomainId());
            case RequirementEditability.F_SONAR_PROJECT_KEY -> entity.getSonarProjectKey();
            case RequirementEditability.F_OWNER_USER_ID -> entity.getOwnerUserId();
            case RequirementEditability.F_TYPE -> entity.getType();
            default -> throw new IllegalArgumentException("未覆盖的可编辑字段: " + field);
        };
    }

    /** 「空 → 主域」：请求/库里都没有域集合时，用主域单元素表示，而不是空集合。 */
    private static List<Long> effectiveDomains(List<Long> domains, Long fallback) {
        if (domains == null || domains.isEmpty()) {
            return fallback == null ? List.of() : List.of(fallback);
        }
        return domains.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static Long primaryOf(List<Long> domains) {
        return domains == null || domains.isEmpty() ? null : domains.get(0);
    }

    private static boolean sameValue(String field, Object before, Object after) {
        return switch (field) {
            case RequirementEditability.F_ESTIMATE_DAYS -> sameDecimal(before, after);
            case RequirementEditability.F_DOMAIN_IDS -> domainKey(before).equals(domainKey(after));
            case RequirementEditability.F_PARENT_ID,
                 RequirementEditability.F_RELEASE_ID,
                 RequirementEditability.F_OWNER_USER_ID ->
                    Objects.equals(longKey(before), longKey(after));
            case RequirementEditability.F_SONAR_PROJECT_KEY ->
                    Objects.equals(textKey(before), textKey(after));
            default -> RequirementChangeFields.asText(before)
                    .equals(RequirementChangeFields.asText(after));
        };
    }

    /** 人天按数值比较：{@code 3} 与 {@code 3.0} 是同值（scale 不参与判定）。 */
    private static boolean sameDecimal(Object before, Object after) {
        BigDecimal left = decimalOf(before);
        BigDecimal right = decimalOf(after);
        if (left != null && right != null) {
            return left.compareTo(right) == 0;
        }
        return RequirementChangeFields.asText(before).equals(RequirementChangeFields.asText(after));
    }

    private static BigDecimal decimalOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 域集合按「去重 + 排序后的文本」比较，顺序与 {@code Integer}/{@code Long} 差异都不算变化。 */
    private static List<String> domainKey(Object value) {
        if (value == null) {
            return List.of();
        }
        Collection<?> items = value instanceof Collection<?> collection
                ? collection : List.of(value);
        return items.stream()
                .filter(Objects::nonNull)
                .map(RequirementChangeFields::asText)
                .distinct()
                .sorted()
                .toList();
    }

    /** id 类字段：JSON 的 Integer 与实体的 Long 必须等价比较；解析不了的值原样参与比较（记为变化）。 */
    private static Object longKey(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return text;
        }
    }

    /** Sonar 键：trim 后空串等同 null（清掉自有键、回落到版本默认）。 */
    private static String textKey(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
