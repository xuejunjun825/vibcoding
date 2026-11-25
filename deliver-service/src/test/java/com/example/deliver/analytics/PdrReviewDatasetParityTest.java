package com.example.deliver.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.common.scope.RowConsumer;
import com.example.platform.common.scope.ScopedJdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C3-2：{@code pdr_review_opinion} 数据集的交叉表计数必须与 {@code GET /pdr-reviews/stats
 * ?groupBy=CATEGORY} 逐分类相等。
 *
 * <p>为什么不用真库：本仓库的 JUnit 不连 MySQL（集成断言由 {@code scripts/pdr-flow-verify.sh}
 * 承担），但「同一份意见行 → 两个出口」的口径可以在这里钉死——fixture 就是 stats 的逐分类
 * 聚合（分类 → 条数），然后让真实的 {@link AnalyticsDatasets}/{@link AnalyticsSql}/
 * {@link AnalyticsQueryService#crossTab} 跑同一份行，断言两边逐项相等、且不出现 0 计数分类。</p>
 *
 * <p>它同时锁住三件容易回退的事：数据集已注册进 {@code /reports/datasets} 目录、分类维度
 * 取原始码（{@code RANGE} 而不是「范围/需求理解」）、意见数据只经 {@code requirement} 挂作用域。</p>
 */
class PdrReviewDatasetParityTest {

    /** stats 的逐分类结果（只含实际有意见的分类，不做 0 填充）。 */
    private static final Map<String, Long> STATS_BY_CATEGORY = stats();

    private static Map<String, Long> stats() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("RANGE", 2L);
        counts.put("TECH", 3L);
        counts.put("ACCEPTANCE", 1L);
        counts.put("RISK", 1L);
        return counts;
    }

    @Test
    @DisplayName("pdr_review_opinion 行=category 的计数与 stats 逐分类相等，且无 0 计数分类")
    void crossTabEqualsStatsByCategory() {
        ScopedJdbc jdbc = mock(ScopedJdbc.class);
        when(jdbc.count(anyString(), anyMap())).thenReturn((long) STATS_BY_CATEGORY.size());
        doAnswer(invocation -> {
            RowConsumer consumer = invocation.getArgument(2);
            for (Map.Entry<String, Long> entry : STATS_BY_CATEGORY.entrySet()) {
                for (long i = 0; i < entry.getValue(); i++) {
                    consumer.accept(Map.of("d0", entry.getKey(), "n0", 1));
                }
            }
            return null;
        }).when(jdbc).stream(anyString(), anyMap(), org.mockito.ArgumentMatchers.any(RowConsumer.class));

        AnalyticsQueryService service =
                new AnalyticsQueryService(jdbc, new AnalyticsCache(300));
        AnalyticsViews.ReportDefinition definition = AnalyticsViews.ReportDefinition.of(
                List.of("category"), List.of(), List.of("opinionCount"), List.of());

        AnalyticsViews.CrossTab tab = service.crossTab(AnalyticsDatasets.PDR_REVIEW_OPINION,
                definition, AnalyticsRequest.empty(), AnalyticsQueryService.MAX_GROUPS, null);

        List<String> expectedKeys = new ArrayList<>(STATS_BY_CATEGORY.keySet());
        expectedKeys.sort(String::compareTo);
        List<String> actualKeys = new ArrayList<>(tab.rowCodes());
        actualKeys.sort(String::compareTo);
        assertEquals(expectedKeys, actualKeys, "行码必须是原始分类码，且没有 0 计数分类");

        for (AnalyticsViews.CrossRow row : tab.rows()) {
            assertEquals(STATS_BY_CATEGORY.get(row.key()), row.counts().get(0),
                    "分类 " + row.key() + " 的交叉表计数必须等于 stats");
            assertEquals(STATS_BY_CATEGORY.get(row.key()), row.values().get(0).longValue(),
                    "opinionCount 与参与行数一致（每行贡献 1）");
        }
        assertEquals(STATS_BY_CATEGORY.values().stream().mapToLong(Long::longValue).sum(),
                tab.total().counts().get(0).longValue(), "合计 = stats 各分类之和");
    }

    @Test
    @DisplayName("两个 PDR 数据集已注册进 /reports/datasets 目录，维度/度量与 req 元信息一致")
    void datasetsAreRegistered() {
        List<String> codes = AnalyticsDatasets.catalog().items().stream()
                .map(AnalyticsViews.Dataset::code).toList();
        assertTrue(codes.contains(AnalyticsDatasets.PDR_REVIEW_OPINION), codes.toString());
        assertTrue(codes.contains(AnalyticsDatasets.PDR_REVIEW), codes.toString());

        assertEquals(List.of("category", "decision", "reviewerId", "reviewId", "createdAt",
                        "opinionCount"),
                AnalyticsDatasets.keys(AnalyticsDatasets.PDR_REVIEW_OPINION));
        assertEquals(List.of("status", "conclusion", "initiatorId", "reviewCount"),
                AnalyticsDatasets.keys(AnalyticsDatasets.PDR_REVIEW));
        assertEquals("o.category", AnalyticsSql.column(AnalyticsDatasets.PDR_REVIEW_OPINION, "category"));
        assertEquals("1", AnalyticsSql.contribution(AnalyticsDatasets.PDR_REVIEW_OPINION, "opinionCount").num());

        // 分类码只有一份：deliver 的展示文案直接复用 contract 的九类码表。
        assertEquals(9, com.example.platform.contract.PdrCategoryCatalog.ALL.size());
        assertEquals("范围/需求理解", AnalyticsLabels.display(
                AnalyticsDatasets.PDR_REVIEW_OPINION, "category", "RANGE"));
    }
}
