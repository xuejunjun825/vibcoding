package com.example.req.pdr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.requirement.RequirementErrors;
import com.example.req.sys.SysDb;

/**
 * 评审统计（设计 §4.4）。
 *
 * <p>三个口径固定下来，避免前端各自再算一遍：</p>
 * <ul>
 *   <li><b>占比</b>：0–1 的小数，保留 4 位；同组之和允许因四舍五入差 ±0.0001，前端乘 100 展示。</li>
 *   <li><b>评审时长</b>：{@code created_at → closed_at} 的小时数，<b>只统计已出结论的单</b>——
 *       还在评的单没有 closed_at，把它算成 0 会稀释均值。</li>
 *   <li><b>闭环率</b>：(TASKED + RESOLVED) / 全部意见。分母是意见数不是评审单数，因为转任务是
 *       按条做的。</li>
 * </ul>
 *
 * <p>可见性<b>不用 SQL 过滤</b>：厂商账号能看哪些评审单取决于 {@link DataScope}，逻辑在
 * {@code canSeeDomain/canSeeVendor} 里，SQL 里再抄一遍就是第二个真相（验收 C4 钉的正是这条）。
 * 数据量是「评审单」级别（百条），先取回再内存过滤是划算的。</p>
 */
@Service
public class PdrStatsService {

    private static final List<String> GROUP_BYS = List.of("CATEGORY", "REVIEWER", "DECISION");

    private final SysDb db;

    public PdrStatsService(SysDb db) {
        this.db = db;
    }

    public PdrViews.StatsView stats(String groupBy, Long domainId, String from, String to) {
        DataScope scope = TenantContext.require();
        String group = normalizeGroupBy(groupBy);
        Timestamp fromTs = startOf(from, "from");
        Timestamp toTs = startOf(to, "to");

        List<ReviewRow> reviews = reviews(scope, domainId, fromTs, toTs);
        List<Long> reviewIds = reviews.stream().map(ReviewRow::id).toList();
        List<OpinionRow> opinions = opinions(scope.tenantId(), reviewIds);

        long decided = reviews.stream()
                .filter(row -> PdrReviewService.ST_APPROVED.equals(row.status())
                        || PdrReviewService.ST_REJECTED.equals(row.status()))
                .count();
        long tasked = opinions.stream().filter(row -> PdrReviewService.OP_TASKED.equals(row.status())).count();
        long resolved = opinions.stream().filter(row -> PdrReviewService.OP_RESOLVED.equals(row.status())).count();

        List<BigDecimal> hours = new ArrayList<>();
        for (ReviewRow row : reviews) {
            BigDecimal value = PdrReviewService.hoursBetween(row.createdAt(), row.closedAt());
            if (value != null) {
                hours.add(value);
            }
        }
        BigDecimal closureRate = ratio(tasked + resolved, opinions.size());

        return new PdrViews.StatsView(group, reviews.size(), decided, opinions.size(), tasked, resolved,
                closureRate, average(hours), p90(hours),
                buckets(group, reviews, opinions, scope.tenantId()));
    }

    /**
     * 报表数据集描述（{@code GET /pdr-reviews/datasets}）。
     *
     * <p>只描述形状，不接交付域的 SQL 引擎：{@code /reports} 的数据集注册在
     * {@code deliver-service/AnalyticsDatasets}，跨服务加数据集是另一件事，这里给前端「能按什么维度看」
     * 的清单，C3 的门禁断言需要两边对齐时以本清单为契约。</p>
     */
    public List<PdrViews.DatasetInfo> datasets() {
        return List.of(
                new PdrViews.DatasetInfo("pdr_review", "PDR 评审单",
                        List.of("status", "mode", "conclusion", "initiatorId", "domainId"),
                        List.of("reviewCount", "decidedCount", "avgReviewHours")),
                new PdrViews.DatasetInfo("pdr_review_opinion", "PDR 评审意见",
                        List.of("category", "decision", "status", "reviewerId", "reviewId"),
                        List.of("opinionCount", "taskedCount", "closureRate")));
    }

    // ------------------------------------------------------------------ 查询

    private record ReviewRow(long id, long requirementId, String status, String mode,
                             String conclusion, Timestamp createdAt, Timestamp closedAt) {
    }

    private record OpinionRow(long id, long reviewId, long reviewerId, String decision, String status,
                              String category) {
    }

    private List<ReviewRow> reviews(DataScope scope, Long domainId, Timestamp from, Timestamp to) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.requirement_id, r.status, r.mode, r.conclusion, r.created_at, r.closed_at,
                       q.domain_id, q.assignee_vendor_id, q.owner_vendor_id
                  FROM pdr_review r
                  JOIN requirement q ON q.id = r.requirement_id AND q.tenant_id = r.tenant_id
                 WHERE r.tenant_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(scope.tenantId());
        if (domainId != null && domainId > 0) {
            sql.append(" AND q.domain_id = ?");
            params.add(domainId);
        }
        if (from != null) {
            sql.append(" AND r.created_at >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND r.created_at < ?");
            params.add(to);
        }
        sql.append(" ORDER BY r.id DESC");
        List<ReviewRow> rows = new ArrayList<>();
        for (Object[] cells : db.rows(sql.toString(), params.toArray())) {
            Long vendorId = SysDb.asNullableLong(cells[8]);
            Long ownerVendorId = SysDb.asNullableLong(cells[9]);
            long rowDomainId = SysDb.asLong(cells[7]);
            if (!scope.canSeeDomainOrUnrestricted(rowDomainId)
                    || !scope.canSeeVendor(vendorId != null ? vendorId : ownerVendorId)) {
                continue;
            }
            rows.add(new ReviewRow(SysDb.asLong(cells[0]), SysDb.asLong(cells[1]),
                    SysDb.asString(cells[2]), SysDb.asString(cells[3]), SysDb.asString(cells[4]),
                    PdrReviewService.timestamp(cells[5]), PdrReviewService.timestamp(cells[6])));
        }
        return rows;
    }

    private List<OpinionRow> opinions(long tenantId, List<Long> reviewIds) {
        if (reviewIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", reviewIds.stream().map(id -> "?").toList());
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.addAll(reviewIds);
        List<OpinionRow> rows = new ArrayList<>();
        for (Object[] cells : db.rows("""
                SELECT id, review_id, reviewer_id, decision, status, category
                  FROM pdr_review_opinion
                 WHERE tenant_id = ? AND review_id IN (%s)
                """.formatted(placeholders), params.toArray())) {
            rows.add(new OpinionRow(SysDb.asLong(cells[0]), SysDb.asLong(cells[1]),
                    SysDb.asLong(cells[2]), SysDb.asString(cells[3]), SysDb.asString(cells[4]),
                    SysDb.asString(cells[5])));
        }
        return rows;
    }

    // ------------------------------------------------------------------ 分桶

    private List<PdrViews.Bucket> buckets(String groupBy, List<ReviewRow> reviews,
                                          List<OpinionRow> opinions, long tenantId) {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        switch (groupBy) {
            case "CATEGORY" -> {
                // r41（QA-PLAN §3-4）：只统计真实存在的分类，不做 0 填充——
                // deliver 的 /reports 交叉表同样只返回有数据的行，C3-2 要求两者逐项相等。
                for (String code : PdrCategories.ALL) {
                    labels.put(code, PdrCategories.label(code));
                }
                for (OpinionRow row : opinions) {
                    counts.merge(row.category(), 1L, Long::sum);
                }
            }
            case "DECISION" -> {
                for (String code : List.of(PdrReviewService.DEC_PASS, PdrReviewService.DEC_NEED_CHANGE,
                        PdrReviewService.DEC_REJECT)) {
                    labels.put(code, decisionLabel(code));
                    counts.put(code, 0L);
                }
                for (OpinionRow row : opinions) {
                    counts.merge(row.decision(), 1L, Long::sum);
                }
            }
            default -> {
                for (OpinionRow row : opinions) {
                    labels.putIfAbsent(String.valueOf(row.reviewerId()), "");
                    counts.merge(String.valueOf(row.reviewerId()), 1L, Long::sum);
                }
                fillReviewerNames(tenantId, labels);
            }
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        List<PdrViews.Bucket> buckets = new ArrayList<>(counts.size());
        counts.forEach((key, count) -> buckets.add(new PdrViews.Bucket(key,
                labels.getOrDefault(key, key), count, ratio(count, total))));
        buckets.sort(PdrReviewService.bucketOrder());
        return buckets;
    }

    /** 评审人姓名：一次 IN 查询补全，别为每个桶查一次用户表。 */
    private void fillReviewerNames(long tenantId, Map<String, String> labels) {
        List<String> ids = labels.keySet().stream().filter(key -> key.matches("\\d+")).toList();
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.addAll(ids);
        for (Object[] cells : db.rows("""
                SELECT id, COALESCE(name, username) FROM sys_user
                 WHERE tenant_id = ? AND id IN (%s)
                """.formatted(placeholders), params.toArray())) {
            labels.put(String.valueOf(SysDb.asLong(cells[0])), SysDb.asString(cells[1]));
        }
        labels.replaceAll((key, value) -> value == null || value.isBlank() ? "用户 " + key : value);
    }

    // ------------------------------------------------------------------ 工具

    private static String normalizeGroupBy(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) {
            return "CATEGORY";
        }
        String value = groupBy.trim().toUpperCase();
        if (!GROUP_BYS.contains(value)) {
            throw new ApiException(400, "INVALID_GROUP_BY", "groupBy 只能是 CATEGORY / REVIEWER / DECISION",
                    Map.of("groupBy", groupBy, "allowed", GROUP_BYS));
        }
        return value;
    }

    /**
     * {@code from/to} 都是「含当天」的日期：{@code to=2026-11-30} 表示统计到 30 号 23:59:59，
     * 因此条件写成 {@code created_at < to + 1 day}。
     */
    private static Timestamp startOf(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value.trim());
            LocalDateTime start = "to".equals(field) ? date.plusDays(1).atStartOfDay() : date.atStartOfDay();
            return Timestamp.valueOf(start);
        } catch (DateTimeParseException ex) {
            throw RequirementErrors.validation(field + " 需要 ISO 日期，例如 2026-11-01");
        }
    }

    private static String decisionLabel(String code) {
        return switch (code) {
            case PdrReviewService.DEC_PASS -> "通过";
            case PdrReviewService.DEC_NEED_CHANGE -> "需修改";
            default -> "驳回";
        };
    }

    private static BigDecimal ratio(long part, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * P90：升序取第 {@code ceil(0.9 * n)} 个（最近秩法）。1 条样本时 P90 = 该条，
     * 而不是插值出来的一个不存在的时长。
     */
    private static BigDecimal p90(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);
        int index = (int) Math.ceil(0.9 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
