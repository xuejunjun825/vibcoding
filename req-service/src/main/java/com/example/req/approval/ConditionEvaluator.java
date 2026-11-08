package com.example.req.approval;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 条件分支求值：{@code reqType / domainId / priority / estimateDays} 四个维度全部命中才算命中。
 *
 * <p>条件节点语义（AC-7）：按定义顺序取<b>第一个</b>命中的分支，未命中走 {@code defaultNext}；
 * 空 {@code when}（{}）视为无条件命中，可用于「兜底分支」但不得替代 defaultNext（发布校验会拦）。
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    /**
     * @param when 条件（可为空对象＝无条件命中）
     * @param doc  单据属性：{@code reqType}(String)、{@code domainId}(Long)、{@code domainIds}(Collection)、
     *             {@code priority}(String)、{@code estimateDays}(BigDecimal/Number)
     */
    public static boolean matches(WorkflowDefinition.When when, java.util.Map<String, Object> doc) {
        if (when == null || when.isEmpty()) {
            return true;
        }
        if (!when.reqType().isEmpty()) {
            String reqType = str(doc.get("reqType"));
            if (reqType == null || when.reqType().stream().noneMatch(v -> v != null && v.equalsIgnoreCase(reqType))) {
                return false;
            }
        }
        if (!when.domainId().isEmpty() && !domainHit(when.domainId(), doc)) {
            return false;
        }
        if (!when.priority().isEmpty()) {
            String priority = str(doc.get("priority"));
            if (priority == null || when.priority().stream().noneMatch(v -> v != null && v.equalsIgnoreCase(priority))) {
                return false;
            }
        }
        if (when.estimateDays() != null && !daysHit(when.estimateDays(), doc.get("estimateDays"))) {
            return false;
        }
        return true;
    }

    private static boolean domainHit(List<Long> expected, java.util.Map<String, Object> doc) {
        for (Long candidate : expected) {
            if (candidate == null) {
                continue;
            }
            if (candidate.equals(asLong(doc.get("domainId")))) {
                return true;
            }
            Object many = doc.get("domainIds");
            if (many instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (candidate.equals(asLong(item))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean daysHit(WorkflowDefinition.Days days, Object raw) {
        BigDecimal value = asDecimal(raw);
        if (value == null) {
            return false;
        }
        if (days.gte() != null && value.compareTo(days.gte()) < 0) {
            return false;
        }
        if (days.gt() != null && value.compareTo(days.gt()) <= 0) {
            return false;
        }
        if (days.lte() != null && value.compareTo(days.lte()) > 0) {
            return false;
        }
        if (days.lt() != null && value.compareTo(days.lt()) >= 0) {
            return false;
        }
        return true;
    }

    static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 供引擎挑选分支：返回第一个命中的分支下标，未命中返回 -1（调用方走 defaultNext）。 */
    public static int firstMatchingBranch(List<WorkflowDefinition.Branch> branches, java.util.Map<String, Object> doc) {
        if (branches == null) {
            return -1;
        }
        for (int i = 0; i < branches.size(); i++) {
            WorkflowDefinition.Branch branch = branches.get(i);
            if (branch != null && matches(branch.when(), doc)) {
                return i;
            }
        }
        return -1;
    }

    static boolean sameText(String left, String right) {
        return Objects.equals(left == null ? null : left.toUpperCase(), right == null ? null : right.toUpperCase());
    }
}
