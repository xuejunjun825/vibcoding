package com.example.req.automation.engine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.req.requirement.Rows;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The evaluated snapshot: the requirement row as it is now, with the event's payload values laid
 * over it (payload wins only where it carries a value; a {@code null} placeholder in the mapper
 * output never erases a real column).
 *
 * <p>Built at dispatch time, under the rule's scope, and handed to the pure evaluator and to the
 * actions. Keeping it a plain {@code Map<String,Object>} is what lets the evaluator stay a static
 * function and the actions stay testable.</p>
 */
public final class AutomationSnapshot {

    public static final String K_TYPE = "type";
    public static final String K_PRIORITY = "priority";
    public static final String K_STATUS = "status";
    public static final String K_APPROVAL_RESULT = "approvalResult";
    public static final String K_ESTIMATE_DAYS = "estimateDays";
    public static final String K_DOMAIN_ID = "domainId";
    public static final String K_OWNER_VENDOR_ID = "ownerVendorId";
    public static final String K_ASSIGNEE_VENDOR_ID = "assigneeVendorId";
    public static final String K_OWNER_USER_ID = "ownerUserId";
    public static final String K_RELEASE_ID = "releaseId";
    public static final String K_FIELD = "field";
    public static final String K_BEFORE = "before";
    public static final String K_AFTER = "after";
    public static final String K_CODE = "code";
    public static final String K_TITLE = "title";

    private static final Map<String, String> ROW_COLUMNS = Map.ofEntries(
            Map.entry(K_TYPE, "type"),
            Map.entry(K_PRIORITY, "priority"),
            Map.entry(K_STATUS, "status"),
            Map.entry(K_ESTIMATE_DAYS, "estimate_days"),
            Map.entry(K_DOMAIN_ID, "domain_id"),
            Map.entry(K_OWNER_VENDOR_ID, "owner_vendor_id"),
            Map.entry(K_ASSIGNEE_VENDOR_ID, "assignee_vendor_id"),
            Map.entry(K_OWNER_USER_ID, "owner_user_id"),
            Map.entry(K_RELEASE_ID, "release_id"),
            Map.entry(K_CODE, "code"),
            Map.entry(K_TITLE, "title"));

    private final Map<String, Object> values;

    private AutomationSnapshot(Map<String, Object> values) {
        this.values = values;
    }

    /** Row -> typed snapshot. Missing columns stay absent (the evaluator reports them as missing). */
    public static AutomationSnapshot fromRow(Map<String, Object> row) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (row != null) {
            ROW_COLUMNS.forEach((key, column) -> {
                Object value = Rows.object(row, column);
                if (value != null) {
                    values.put(key, value);
                }
            });
        }
        return new AutomationSnapshot(values);
    }

    /** Event payload over the row: only non-null payload values are applied. */
    public AutomationSnapshot overlay(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(values);
        payload.properties().forEach(entry -> {
            JsonNode node = entry.getValue();
            if (node == null || node.isNull()) {
                return;
            }
            merged.put(entry.getKey(), jsonValue(node));
        });
        applyFieldChange(merged);
        return new AutomationSnapshot(merged);
    }

    /**
     * A {@code FIELD_CHANGE} event carries {@code field/before/after} instead of the field's own
     * key, so a condition on that field would see the stale row value (or nothing at all when the
     * column is still empty). The state after the change is what a rule is meant to judge, so the
     * after-value is projected onto the snapshot key the conditions read.
     */
    private static void applyFieldChange(Map<String, Object> merged) {
        Object field = merged.get(K_FIELD);
        Object after = merged.get(K_AFTER);
        if (field == null || after == null) {
            return;
        }
        String name = String.valueOf(field);
        String key = ROW_COLUMNS.containsKey(name) ? name : null;
        if (key == null) {
            for (Map.Entry<String, String> entry : ROW_COLUMNS.entrySet()) {
                if (entry.getValue().equals(name)) {
                    key = entry.getKey();
                    break;
                }
            }
        }
        if (key != null) {
            merged.put(key, after);
        }
    }

    public AutomationSnapshot overlay(String payloadJson, ObjectMapper mapper) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return this;
        }
        return overlay(mapper.readTree(payloadJson));
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public String string(String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public Long longValue(String key) {
        return Rows.toLong(values.get(key));
    }

    public BigDecimal decimal(String key) {
        return Rows.toDecimal(values.get(key));
    }

    private static Object jsonValue(JsonNode node) {
        if (node.isString()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.toString();
    }
}
