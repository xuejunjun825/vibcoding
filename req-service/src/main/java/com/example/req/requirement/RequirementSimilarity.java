package com.example.req.requirement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 相似需求的<b>唯一</b>打分实现（r47 契约 §3.1）。
 *
 * <p>为什么单一实现：两个入口（新建需求时的建议、需求详情里的「相似需求」）如果各写一份公式，
 * 「同一条需求在弹窗里 0.7、在详情里 0.4」这类分叉只会在演示时被发现。SQL 只负责用 LIKE 把候选
 * 缩到 200 行（{@link RequirementSimilarQuery}），排序与阈值只在这里，Java 侧可单测。</p>
 *
 * <p>公式（契约原文）：{@code score = round(3*titleHit/titleTokens + 1*descHit/descTokens
 * + 同域 +1 + 同类型 +0.5, 2)}；低于 {@link #MIN_SCORE} 丢弃。命中率用 2-gram 集合的交集比：
 * 中文标题没有空格，按字切分（1-gram）会把「订单」与「订单号」算成高度相似，2-gram 至少保留
 * 相邻字序，这也是契约选它的原因。</p>
 */
public final class RequirementSimilarity {

    /** 低于该分值不展示（契约 §3.1）：域/类型加分单独也到不了 0.15，必须真有一点赞同。 */
    public static final double MIN_SCORE = 0.15;

    static final double TITLE_WEIGHT = 3.0;
    static final double DESC_WEIGHT = 1.0;
    static final double DOMAIN_BONUS = 1.0;
    static final double TYPE_BONUS = 0.5;

    /** 候选池上限（契约 §3.1：LIKE 预筛最多 200 行，禁止全表打分）。 */
    public static final int PREFILTER_LIMIT = 200;
    /** 返回条数上限，与前端「最多 5 条」一致；URL 上允许调到 20 便于运营排查。 */
    public static final int MAX_LIMIT = 20;

    /** LIKE 预筛最多用几个 2-gram：8 个已够把标题命中收窄，再堆 OR 只会让索引失效更彻底。 */
    static final int MAX_PREFILTER_TERMS = 8;

    private RequirementSimilarity() {
    }

    /** 一条候选需求（SQL 预筛后的最小列集 + 展示名 + 热度）。 */
    public record Candidate(long id, String code, String type, String title, String description,
                            String status, Long domainId, String reporterName, String orgName,
                            long followCount) {
    }

    /** 打分结果；{@code score} 已四舍五入到两位小数。 */
    public record Scored(Candidate candidate, double score) {
    }

    /**
     * 排序：分值降序 → 跟单人数降序 → id 升序。
     *
     * <p>跟单人数做次要键：同分时「等的人多」的需求更值得先看；id 兜底保证同分同热度时顺序稳定，
     * 前端分页/截图不会每次抖动。</p>
     */
    public static List<Scored> rank(String title, String description, Long domainId, String type,
                                    List<Candidate> candidates, int limit) {
        int capped = clampLimit(limit);
        List<Scored> scored = rankAll(title, description, domainId, type, candidates);
        return scored.size() > capped ? new ArrayList<>(scored.subList(0, capped)) : scored;
    }

    /**
     * 全部命中（不截断），供 {@code total} 使用：契约 §3.1 的 total 是「命中总数」，
     * 不是返回条数——limit=1 时 items 1 条、total 仍应 ≥3，否则前端「共 N 条相似」会显示 1。
     */
    public static List<Scored> rankAll(String title, String description, Long domainId, String type,
                                       List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty() || isBlank(title) && isBlank(description)) {
            return List.of();
        }
        Set<String> titleGrams = grams(title);
        Set<String> descGrams = grams(description);
        List<Scored> scored = new ArrayList<>();
        for (Candidate candidate : candidates) {
            double value = score(titleGrams, descGrams, domainId, type, candidate);
            if (value < MIN_SCORE) {
                continue;
            }
            scored.add(new Scored(candidate, round(value)));
        }
        scored.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            if (byScore != 0) {
                return byScore;
            }
            int byFollow = Long.compare(b.candidate().followCount(), a.candidate().followCount());
            if (byFollow != 0) {
                return byFollow;
            }
            return Long.compare(a.candidate().id(), b.candidate().id());
        });
        return scored;
    }

    /** 单个候选的原始分值（未四舍五入，便于阈值判断与测试精确断言）。 */
    public static double score(String title, String description, Long domainId, String type,
                               Candidate candidate) {
        return score(grams(title), grams(description), domainId, type, candidate);
    }

    static double score(Set<String> queryTitleGrams, Set<String> queryDescGrams, Long domainId,
                        String type, Candidate candidate) {
        double titleHit = ratio(queryTitleGrams, grams(candidate.title()));
        double descHit = queryDescGrams.isEmpty()
                ? 0.0
                : ratio(queryDescGrams, grams(candidate.description()));
        double bonus = 0.0;
        if (domainId != null && domainId.equals(candidate.domainId())) {
            bonus += DOMAIN_BONUS;
        }
        if (type != null && !type.isBlank() && type.equals(candidate.type())) {
            bonus += TYPE_BONUS;
        }
        return TITLE_WEIGHT * titleHit + DESC_WEIGHT * descHit + bonus;
    }

    /** 2-gram 集合：去标点/空白、转小写；单字（如「单」）退化为 1-gram，否则永远 0 命中。 */
    public static Set<String> grams(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        int[] codePoints = normalized.codePoints().toArray();
        Set<String> grams = new LinkedHashSet<>();
        if (codePoints.length == 1) {
            grams.add(new String(codePoints, 0, 1));
            return grams;
        }
        for (int i = 0; i + 1 < codePoints.length; i++) {
            grams.add(new String(codePoints, i, 2));
        }
        return grams;
    }

    /**
     * LIKE 预筛词：优先标题 2-gram，不足 8 个再补描述。
     *
     * <p>返回的是「可能命中」的词不是「必须命中」的词——SQL 用 OR 只要命中一个就进候选池，真正的
     * 相似度仍由 {@link #rank} 判。取开头而不是随机，是为了同样的输入生成同样的 SQL（缓存/慢日志可比）。
     */
    public static List<String> prefilterTerms(String title, String description) {
        List<String> terms = new ArrayList<>(MAX_PREFILTER_TERMS);
        for (Set<String> source : List.of(grams(title), grams(description))) {
            for (String gram : source) {
                if (terms.size() >= MAX_PREFILTER_TERMS) {
                    return terms;
                }
                if (!terms.contains(gram)) {
                    terms.add(gram);
                }
            }
        }
        return terms;
    }

    public static int clampLimit(int limit) {
        if (limit <= 0) {
            return 5;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    static double ratio(Set<String> query, Set<String> candidate) {
        if (query.isEmpty() || candidate.isEmpty()) {
            return 0.0;
        }
        int hit = 0;
        for (String gram : query) {
            if (candidate.contains(gram)) {
                hit++;
            }
        }
        return (double) hit / query.size();
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        text.toLowerCase(Locale.ROOT).codePoints()
                .filter(cp -> Character.isLetterOrDigit(cp))
                .forEach(builder::appendCodePoint);
        return builder.toString();
    }

    static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
