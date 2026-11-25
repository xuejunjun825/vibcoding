package com.example.req.requirement;

import com.example.req.requirement.RequirementSimilarity.Candidate;
import com.example.req.requirement.RequirementSimilarity.Scored;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 相似度打分（r47 契约 §3.1）：两个入口共用的唯一公式，逐条钉住权重、阈值、并列次序。
 *
 * <p>为什么不连库测：这一层是纯函数，库只是预筛候选。库侧（LIKE 预筛、scope、排除已合并/终态）
 * 由 HTTP 冒烟与 QA 门禁覆盖；公式的正确性在这里用可手算的样本钉死。</p>
 */
class RequirementSimilarityTest {

    private static Candidate candidate(long id, String type, String title, String description,
                                       Long domainId, long followCount) {
        return new Candidate(id, "DEMO-R-" + id, type, title, description, "POOLED", domainId,
                "报告人", "供应链部", followCount);
    }

    @Test
    @DisplayName("同标题 + 同域 + 同类型：分数 ≥ 0.6 且排第一")
    void sameTitleSameDomainOutranksEverything() {
        Candidate exact = candidate(1, "PDR", "采购订单移动审批", null, 7L, 2);
        Candidate partial = candidate(2, "PDR", "采购订单移动审批（二期）", null, 7L, 0);
        Candidate unrelated = candidate(3, "RD", "库存预警定时任务", null, 9L, 5);

        List<Scored> ranked = RequirementSimilarity.rank("采购订单移动审批", null, 7L, "PDR",
                List.of(unrelated, partial, exact), 5);

        assertThat(ranked).isNotEmpty();
        assertThat(ranked.get(0).candidate().id()).as("完全命中的需求必须排第一").isEqualTo(1L);
        assertThat(ranked.get(0).score()).as("§7 要求演示相似 PDR ≥0.6").isGreaterThanOrEqualTo(0.6);
        assertThat(ranked).as("跨域且标题/描述零命中的候选低于 0.15 被丢弃")
                .noneMatch(entry -> entry.candidate().id() == 3L);
    }

    @Test
    @DisplayName("同标题跨域 / 跨类型：少了 +1 域奖励与 +0.5 类型奖励，分数严格更低")
    void domainAndTypeBonusesMoveTheScore() {
        Candidate sameBoth = candidate(1, "PDR", "采购订单移动审批", null, 7L, 0);
        Candidate otherDomain = candidate(2, "PDR", "采购订单移动审批", null, 9L, 0);
        Candidate otherType = candidate(3, "RD", "采购订单移动审批", null, 7L, 0);

        double full = RequirementSimilarity.rank("采购订单移动审批", null, 7L, "PDR",
                List.of(sameBoth), 5).get(0).score();
        double noDomain = RequirementSimilarity.rank("采购订单移动审批", null, 7L, "PDR",
                List.of(otherDomain), 5).get(0).score();
        double noType = RequirementSimilarity.rank("采购订单移动审批", null, 7L, "PDR",
                List.of(otherType), 5).get(0).score();

        assertThat(full - noDomain).as("同域奖励 +1").isEqualTo(1.0);
        assertThat(full - noType).as("同类型奖励 +0.5").isEqualTo(0.5);
    }

    @Test
    @DisplayName("标题权重是描述的 3 倍：标题命中比描述命中更值钱")
    void titleHitWeighsThreeTimesDescriptionHit() {
        Candidate titleHit = candidate(1, "PDR", "采购订单移动审批", "完全无关的补充说明", 7L, 0);
        Candidate descHit = candidate(2, "PDR", "完全无关的标题词", "采购订单移动审批", 7L, 0);

        double title = RequirementSimilarity.rank("采购订单移动审批", "采购订单移动审批", 7L, "PDR",
                List.of(titleHit), 5).get(0).score();
        double description = RequirementSimilarity.rank("采购订单移动审批", "采购订单移动审批", 7L, "PDR",
                List.of(descHit), 5).get(0).score();

        assertThat(title).as("标题 3 权重 × 1.0 vs 描述 1 权重 × 1.0").isGreaterThan(description);
    }

    @Test
    @DisplayName("分数四舍五入到 2 位；低于 0.15 直接丢弃")
    void roundsToTwoDecimalsAndDropsNoise() {
        Candidate weak = candidate(1, "BUS", "采购订单协同", null, 8L, 0);
        List<Scored> ranked = RequirementSimilarity.rank("采购订单移动审批", null, 7L, "PDR",
                List.of(weak), 5);

        assertThat(ranked).allSatisfy(entry ->
                assertThat(Math.round(entry.score() * 100.0) / 100.0).isEqualTo(entry.score()));
        assertThat(ranked).allSatisfy(entry -> assertThat(entry.score()).isGreaterThanOrEqualTo(0.15));
    }

    @Test
    @DisplayName("并列时先比热度再比 id；limit 默认 5、上限 20")
    void tiesBreakByFollowCountThenIdAndLimitIsClamped() {
        Candidate hotter = candidate(9, "PDR", "采购订单移动审批", null, 7L, 6);
        Candidate colder = candidate(3, "PDR", "采购订单移动审批", null, 7L, 0);
        Candidate coldTieHigherId = candidate(4, "PDR", "采购订单移动审批", null, 7L, 0);

        List<Scored> ranked = RequirementSimilarity.rank("采购订单移动审批", null, 7L, "PDR",
                List.of(coldTieHigherId, colder, hotter), 5);
        assertThat(ranked).extracting(entry -> entry.candidate().id())
                .containsExactly(9L, 3L, 4L);

        List<Scored> one = RequirementSimilarity.rank("采购订单移动审批", null, 7L, "PDR",
                List.of(hotter, colder), 1);
        assertThat(one).hasSize(1);

        List<Scored> defaulted = RequirementSimilarity.rank("采购订单移动审批", null, 7L, "PDR",
                List.of(hotter, colder), 0);
        assertThat(defaulted).as("limit ≤0 回落默认 5").hasSize(2);

        assertThat(RequirementSimilarity.clampLimit(999)).isEqualTo(20);
    }

    @Test
    @DisplayName("空查询/空候选返回空表，不抛异常")
    void blankInputIsEmptyNotAnError() {
        assertThat(RequirementSimilarity.rank(null, null, null, null,
                List.of(candidate(1, "PDR", "采购订单移动审批", null, 7L, 0)), 5)).isEmpty();
        assertThat(RequirementSimilarity.rank("采购订单移动审批", null, 7L, "PDR",
                List.of(), 5)).isEmpty();
    }

    @Test
    @DisplayName("rank 截断、rankAll 保留全部命中：total 口径不随 limit 缩水")
    void rankAllKeepsEveryHit() {
        List<RequirementSimilarity.Candidate> candidates = List.of(
                candidate(1, "PDR", "供应商门户单点登录", null, 3L, 0),
                candidate(2, "PDR", "供应商门户单点登录", null, 3L, 0),
                candidate(3, "PDR", "供应商门户单点登录", null, 2L, 0));

        List<RequirementSimilarity.Scored> all =
                RequirementSimilarity.rankAll("供应商门户单点登录", null, 3L, "PDR", candidates);
        List<RequirementSimilarity.Scored> limited =
                RequirementSimilarity.rank("供应商门户单点登录", null, 3L, "PDR", candidates, 1);

        assertThat(all).hasSize(3);
        assertThat(limited).hasSize(1);
        assertThat(all.get(0).score()).isGreaterThan(all.get(2).score());
    }
}
