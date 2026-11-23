package com.example.req.automation.event;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Audit action -> 0..n automation event drafts (pure function, no JDBC).
 *
 * <p>This is <b>the</b> place where requirement-domain meaning is attached to an audit row: the
 * shared audit module only knows "something happened", the automation engine needs "requirement 7
 * entered PENDING_REVIEW". Keeping the table here also keeps it unit-testable — every branch below
 * is covered by {@code AutomationEventMapperTest} without a database.</p>
 *
 * <p>Dedupe discipline: every id ends with the {@code audit_log.id} of the causing action
 * ({@code payload.auditId}). The outbox unique key is {@code (tenant_id, event_id)}, so a retried
 * request that wrote a second audit row legitimately produces a second event, while a transaction
 * that rolled back wrote neither. Field-level events carry the field name as well, so one UPDATE
 * touching priority and estimate yields two independently matching events.</p>
 *
 * <p>Snapshot discipline (§4.2): the payload always carries the whole key set, missing keys as
 * explicit {@code null}. Conditions stay closed — a key absent from the snapshot simply cannot
 * match, it never falls back to "read the live row".</p>
 */
public final class AutomationEventMapper {

    public static final String EVENT_REQUIREMENT_CREATED = "REQUIREMENT_CREATED";
    public static final String EVENT_STATUS_CHANGE = "STATUS_CHANGE";
    public static final String EVENT_FIELD_CHANGE = "FIELD_CHANGE";
    public static final String EVENT_SCHEDULE = "SCHEDULE";
    public static final String EVENT_SCHEDULE_TRUNCATED = "SCHEDULE_TRUNCATED";

    public static final String ORIGIN_USER = "USER";
    public static final String ORIGIN_RULE = "RULE";

    /** Requirement fields a condition may name; snapshot key {@code field} uses the same spelling. */
    public static final String FIELD_PRIORITY = "priority";
    public static final String FIELD_OWNER_USER = "owner_user_id";
    public static final String FIELD_ESTIMATE_DAYS = "estimate_days";
    public static final String FIELD_RELEASE = "release_id";

    private static final String BIZ_REQUIREMENT = "REQUIREMENT";
    private static final String BIZ_RELEASE = "RELEASE";
    /** 变更单审计按单号收口；字段事件仍挂在被改的需求上（detail.requirementId）。 */
    private static final String BIZ_REQ_CHANGE = "REQ_CHANGE";
    private static final String ACTION_CHANGE_APPLIED = "REQUIREMENT_CHANGE_APPLIED";
    private static final String RELEASE_MEMBER_ADD = "RELEASE_MEMBER_ADD";
    private static final String RELEASE_MEMBER_REMOVE = "RELEASE_MEMBER_REMOVE";

    private static final int DEFAULT_CHAIN_DEPTH = 1;
    private static final int MAX_CHAIN_DEPTH = 9;

    /** One requirement column that can produce a FIELD_CHANGE, with its audit detail key. */
    private record Tracked(String detailKey, String field) {
    }

    /** ORDER 是事件顺序契约：同一 UPDATE 的多条事件按此顺序入库。 */
    private static final List<Tracked> UPDATE_FIELDS = List.of(
            new Tracked("priority", FIELD_PRIORITY),
            new Tracked("ownerUserId", FIELD_OWNER_USER),
            new Tracked("estimateDays", FIELD_ESTIMATE_DAYS),
            new Tracked("releaseId", FIELD_RELEASE));

    private static final List<Tracked> VENDOR_FIELDS = List.of(
            new Tracked("ownerVendorId", "owner_vendor_id"),
            new Tracked("assigneeVendorId", "assignee_vendor_id"),
            new Tracked("ownerUserId", FIELD_OWNER_USER));

    private static final List<Tracked> DELIVER_FIELDS = List.of(
            new Tracked("estimateDays", FIELD_ESTIMATE_DAYS));

    private static final List<Tracked> ESTIMATE_FIELDS = List.of(
            new Tracked("estimateDays", FIELD_ESTIMATE_DAYS));

    /**
     * r40 变更单应用后派生的字段事件（7 个可变更字段）。
     *
     * <p>detailKey 是变更快照的键（camelCase，与 {@code RequirementChangeFields.FIELDS} 同名），
     * field 是列名（下划线，与 {@code AutomationSpecs.FIELDS} 同一词表）——两者必须同时对齐，
     * 否则规则保存得进、命中不了。审计本身只写 1 条 {@code REQUIREMENT_CHANGE_APPLIED}，
     * 每字段 1 条事件由这里派生（§10）。</p>
     */
    private static final List<Tracked> CHANGE_FIELDS = List.of(
            new Tracked("title", "title"),
            new Tracked("description", "description"),
            new Tracked("priority", FIELD_PRIORITY),
            new Tracked("estimateDays", FIELD_ESTIMATE_DAYS),
            new Tracked("releaseId", FIELD_RELEASE),
            new Tracked("parentId", "parent_id"),
            new Tracked("expectedDeliveryDate", "expected_delivery_date"));

    private AutomationEventMapper() {
    }

    /**
     * @param detail audit detail; {@code auditId} (inserted by {@code AuditService}) is the dedupe
     *               suffix, {@code automationOrigin}/{@code automationChainDepth} override the
     *               defaults (an event caused by a rule run carries them through)
     * @return zero or more drafts in a deterministic order; empty when the action is not a
     *         trigger, or does not name a requirement
     */
    public static List<AutomationEventDraft> map(String action, String bizType, Long bizId,
                                                 Map<String, Object> detail) {
        if (action == null || bizType == null || bizId == null || detail == null) {
            return List.of();
        }
        String suffix = auditSuffix(detail);
        if (BIZ_REQUIREMENT.equals(bizType)) {
            return mapRequirement(action, bizId, detail, suffix);
        }
        if (BIZ_RELEASE.equals(bizType)) {
            return mapRelease(action, bizId, detail, suffix);
        }
        if (BIZ_REQ_CHANGE.equals(bizType)) {
            return mapChange(action, bizId, detail, suffix);
        }
        return List.of();
    }

    /**
     * 变更单审计：只有 apply 会产生字段事件，且事件必须挂在<b>需求</b>上。
     *
     * <p>审计行 {@code biz_type=REQ_CHANGE, biz_id=changeId} 是为了「按单号直查」；
     * 自动化规则的快照/条件仍是需求维度，所以需求 id 从 audit detail 的
     * {@code requirementId} 取。{@code CHANGE_AFTER_RELEASE_LOCK} 是留痕标记，
     * 不是内容变化，刻意不派事件。</p>
     */
    private static List<AutomationEventDraft> mapChange(String action, long changeId,
                                                        Map<String, Object> detail,
                                                        String suffix) {
        if (!ACTION_CHANGE_APPLIED.equals(action)) {
            return List.of();
        }
        Long requirementId = asLong(detail.get("requirementId"));
        if (requirementId == null) {
            return List.of();
        }
        return fieldChanges(requirementId, detail, suffix, CHANGE_FIELDS,
                mapAt(detail, "before"), mapAt(detail, "after"));
    }

    /**
     * Event id for a requirement-scoped event: {@code REQ:<requirementId>:<kind>:<suffix>}.
     *
     * <p>The requirement id is part of the id on purpose — the outbox unique key is
     * {@code (tenant_id, event_id)}, and one scheduled scan legitimately produces one event per
     * requirement per day.</p>
     */
    public static String eventId(long requirementId, String kind, String suffix) {
        return "REQ:" + requirementId + ":" + kind + ":" + (suffix == null ? "0" : suffix);
    }

    /** Daily scan event id: one per requirement per day (PRD 3.2 事实修正 D3). */
    public static String scheduleEventId(String date, long requirementId) {
        return "SCHEDULE:" + date + ":" + requirementId;
    }

    /**
     * Daily scan truncation row: the only event allowed to have {@code requirement_id = null}, so
     * it cannot be deduped by the requirement-scoped unique key and is written with an existence
     * check instead (see {@code AutomationRunWriter}).
     */
    public static String scheduleTruncatedEventId(String date) {
        return "SCHEDULE:" + date + ":TRUNCATED";
    }

    private static List<AutomationEventDraft> mapRequirement(String action, long requirementId,
                                                             Map<String, Object> detail,
                                                             String suffix) {
        return switch (action) {
            case "CREATE" -> List.of(new AutomationEventDraft(
                    eventId(requirementId, "CREATED", suffix), EVENT_REQUIREMENT_CREATED,
                    requirementId, origin(detail), chainDepth(detail), snapshot(detail)));
            case "STATE_CHANGE" -> List.of(new AutomationEventDraft(
                    eventId(requirementId, "STATUS", suffix), EVENT_STATUS_CHANGE,
                    requirementId, origin(detail), chainDepth(detail), statusSnapshot(detail)));
            case "UPDATE" -> fieldChanges(requirementId, detail, suffix, UPDATE_FIELDS,
                    mapAt(detail, "before"), mapAt(detail, "after"));
            case "ESTIMATE_APPLIED" -> {
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("estimateDays", first(detail, "estimateDays", "estimate_days"));
                yield fieldChanges(requirementId, detail, suffix, ESTIMATE_FIELDS,
                        mapAt(detail, "before"), after);
            }
            case "VENDOR_TRANSFER" -> fieldChanges(requirementId, detail, suffix, VENDOR_FIELDS,
                    mapAt(detail, "before"), mapAt(detail, "after"));
            case "DELIVER" -> fieldChanges(requirementId, detail, suffix, DELIVER_FIELDS,
                    mapAt(detail, "before"), mapAt(detail, "after"));
            case "OWNER_ASSIGN" -> List.of(ownerAssign(requirementId, detail, suffix));
            // r40：变更单应用=一次真实字段写入，但审计只有 1 条；按字段派生事件保持与 PUT 同口径。
            // CHANGE_AFTER_RELEASE_LOCK 刻意没有 case：它是留痕标记，不是内容变化，不触发自动化。
            case "REQUIREMENT_CHANGE_APPLIED" -> fieldChanges(requirementId, detail, suffix,
                    CHANGE_FIELDS, mapAt(detail, "before"), mapAt(detail, "after"));
            default -> List.of();
        };
    }

    private static List<AutomationEventDraft> mapRelease(String action, long releaseId,
                                                         Map<String, Object> detail,
                                                         String suffix) {
        if (!RELEASE_MEMBER_ADD.equals(action) && !RELEASE_MEMBER_REMOVE.equals(action)) {
            return List.of();
        }
        Object raw = detail.get("changed");
        if (!(raw instanceof List<?> changed)) {
            // No changed[] means the caller could not say which requirements actually moved; a
            // release-level event would be unattributable to any requirement.
            return List.of();
        }
        List<AutomationEventDraft> drafts = new ArrayList<>();
        for (Object item : changed) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            Long requirementId = asLong(entry.get("id"));
            if (requirementId == null) {
                continue;
            }
            Object before = entry.get("before");
            Object after = entry.get("after");
            Map<String, Object> base = snapshot(detail);
            base.put("releaseId", after);
            drafts.add(new AutomationEventDraft(
                    eventId(requirementId, "FIELD", FIELD_RELEASE + ":" + suffix),
                    EVENT_FIELD_CHANGE, requirementId, origin(detail), chainDepth(detail),
                    fieldChangePayload(base, FIELD_RELEASE, before, after)));
        }
        return drafts;
    }

    private static AutomationEventDraft ownerAssign(long requirementId, Map<String, Object> detail,
                                                    String suffix) {
        Object nested = first(detail, "ownerUserId", "owner_user_id");
        Object before;
        Object after;
        if (nested instanceof Map<?, ?> pair) {
            before = pair.get("before");
            after = pair.get("after");
        } else {
            before = mapValue(mapAt(detail, "before"), "ownerUserId");
            after = mapValue(mapAt(detail, "after"), "ownerUserId");
        }
        Map<String, Object> base = snapshot(detail);
        base.put("ownerUserId", after);
        return new AutomationEventDraft(
                eventId(requirementId, "FIELD", FIELD_OWNER_USER + ":" + suffix),
                EVENT_FIELD_CHANGE, requirementId, origin(detail), chainDepth(detail),
                fieldChangePayload(base, FIELD_OWNER_USER, before, after));
    }

    /**
     * One FIELD_CHANGE per actually changed field. Unchanged fields are skipped: "the request
     * carried the same value" must not fire a rule.
     */
    private static List<AutomationEventDraft> fieldChanges(long requirementId,
                                                           Map<String, Object> detail,
                                                           String suffix,
                                                           List<Tracked> tracked,
                                                           Map<?, ?> before,
                                                           Map<?, ?> after) {
        List<AutomationEventDraft> drafts = new ArrayList<>();
        Map<String, Object> base = snapshot(detail);
        overlay(base, after);
        for (Tracked entry : tracked) {
            Object oldValue = mapValue(before, entry.detailKey());
            Object newValue = mapValue(after, entry.detailKey());
            if (sameValue(oldValue, newValue)) {
                continue;
            }
            drafts.add(new AutomationEventDraft(
                    eventId(requirementId, "FIELD", entry.field() + ":" + suffix),
                    EVENT_FIELD_CHANGE, requirementId, origin(detail), chainDepth(detail),
                    fieldChangePayload(base, entry.field(), oldValue, newValue)));
        }
        return drafts;
    }

    private static Map<String, Object> fieldChangePayload(Map<String, Object> base, String field,
                                                          Object before, Object after) {
        Map<String, Object> payload = new LinkedHashMap<>(base);
        payload.put("field", field);
        payload.put("before", before);
        payload.put("after", after);
        return payload;
    }

    /** Full snapshot key set, missing keys as explicit nulls (§4.2). */
    private static Map<String, Object> snapshot(Map<String, Object> detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", first(detail, "type"));
        payload.put("priority", first(detail, "priority"));
        payload.put("status", first(detail, "status"));
        payload.put("approvalResult", first(detail, "approvalResult"));
        payload.put("estimateDays", first(detail, "estimateDays", "estimate_days"));
        payload.put("domainId", first(detail, "domainId", "domain_id"));
        payload.put("ownerVendorId", first(detail, "ownerVendorId", "owner_vendor_id"));
        payload.put("assigneeVendorId", first(detail, "assigneeVendorId", "assignee_vendor_id"));
        payload.put("ownerUserId", first(detail, "ownerUserId", "owner_user_id"));
        payload.put("releaseId", first(detail, "releaseId", "release_id"));
        payload.put("field", null);
        payload.put("before", null);
        payload.put("after", null);
        return payload;
    }

    private static Map<String, Object> statusSnapshot(Map<String, Object> detail) {
        Map<String, Object> payload = snapshot(detail);
        Object to = first(detail, "to", "status");
        payload.put("status", to);
        payload.put("approvalResult", approvalResult(detail));
        return payload;
    }

    /** Overlay the entity values carried by an audit {@code after} map onto the snapshot. */
    private static void overlay(Map<String, Object> payload, Map<?, ?> values) {
        if (values == null) {
            return;
        }
        copy(payload, "type", values, "type");
        copy(payload, "priority", values, "priority");
        copy(payload, "status", values, "status");
        copy(payload, "approvalResult", values, "approvalResult");
        copy(payload, "estimateDays", values, "estimateDays");
        copy(payload, "domainId", values, "domainId");
        copy(payload, "ownerVendorId", values, "ownerVendorId");
        copy(payload, "assigneeVendorId", values, "assigneeVendorId");
        copy(payload, "ownerUserId", values, "ownerUserId");
        copy(payload, "releaseId", values, "releaseId");
        // r40：变更单的 after 快照带这四个键（既有 auditSnapshot 没有），复制进来规则句才能读到。
        copy(payload, "title", values, "title");
        copy(payload, "description", values, "description");
        copy(payload, "parentId", values, "parentId");
        copy(payload, "expectedDeliveryDate", values, "expectedDeliveryDate");
    }

    private static void copy(Map<String, Object> target, String key, Map<?, ?> source,
                             String sourceKey) {
        if (source.containsKey(sourceKey)) {
            target.put(key, source.get(sourceKey));
        }
    }

    /**
     * Approval outcome of a STATUS_CHANGE. The approval path passes it explicitly; for status
     * changes forged elsewhere the action name is the fallback (approve/reject/withdraw), so a
     * condition on {@code approvalResult} does not silently stop matching.
     */
    private static String approvalResult(Map<String, Object> detail) {
        Object direct = first(detail, "approvalResult");
        if (direct instanceof String text && !text.isBlank()) {
            return text;
        }
        Object action = first(detail, "action");
        if (action == null) {
            return null;
        }
        String normalized = action.toString().trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("approve")) {
            return "APPROVED";
        }
        if (normalized.startsWith("reject")) {
            return "REJECTED";
        }
        if (normalized.startsWith("withdraw")) {
            return "WITHDRAWN";
        }
        return null;
    }

    private static String origin(Map<String, Object> detail) {
        Object raw = first(detail, "automationOrigin", "origin");
        if (raw instanceof String text && !text.isBlank()) {
            return text.trim().toUpperCase(Locale.ROOT);
        }
        return ORIGIN_USER;
    }

    private static int chainDepth(Map<String, Object> detail) {
        Object raw = first(detail, "automationChainDepth", "chainDepth");
        int depth = DEFAULT_CHAIN_DEPTH;
        if (raw instanceof Number number) {
            depth = number.intValue();
        } else if (raw instanceof String text) {
            try {
                depth = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                depth = DEFAULT_CHAIN_DEPTH;
            }
        }
        return Math.max(DEFAULT_CHAIN_DEPTH, Math.min(MAX_CHAIN_DEPTH, depth));
    }

    private static String auditSuffix(Map<String, Object> detail) {
        Object raw = detail.get("auditId");
        if (raw instanceof Number number) {
            return Long.toString(number.longValue());
        }
        if (raw != null && !raw.toString().isBlank()) {
            return raw.toString().trim();
        }
        // Key unreadable (driver without generated keys): keep the event rather than lose it, the
        // service already logged the degradation.
        return "0";
    }

    private static Map<?, ?> mapAt(Map<String, Object> detail, String key) {
        Object raw = detail == null ? null : detail.get(key);
        return raw instanceof Map<?, ?> map ? map : null;
    }

    private static Object mapValue(Map<?, ?> map, String key) {
        return map == null ? null : map.get(key);
    }

    private static Object first(Map<String, Object> detail, String... keys) {
        if (detail == null) {
            return null;
        }
        for (String key : keys) {
            if (detail.containsKey(key)) {
                return detail.get(key);
            }
        }
        return null;
    }

    private static Long asLong(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Numbers compare by value (3.0 == 3.00); everything else by its text form. */
    private static boolean sameValue(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return new BigDecimal(leftNumber.toString())
                    .compareTo(new BigDecimal(rightNumber.toString())) == 0;
        }
        return Objects.equals(left, right);
    }
}
