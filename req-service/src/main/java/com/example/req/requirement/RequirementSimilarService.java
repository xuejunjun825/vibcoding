package com.example.req.requirement;

import com.example.req.requirement.RequirementPoolOpsViews.SimilarItem;
import com.example.req.requirement.RequirementPoolOpsViews.SimilarPage;
import com.example.req.requirement.RequirementSimilarity.Scored;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 相似需求的两个入口（r47 契约 §3.1）。
 *
 * <p>「按标题查」与「按 id 查」只在取查询条件上不同：候选池都来自 {@link RequirementSimilarQuery}，
 * 排序都来自 {@link RequirementSimilarity#rank}。这正是契约要求「两个入口必须走同一打分函数」的
 * 落点——这里没有第二份公式，也不允许将来出现。</p>
 */
@Service
public class RequirementSimilarService {

    private final RequirementSimilarQuery query;
    private final RequirementService requirements;

    public RequirementSimilarService(RequirementSimilarQuery query,
                                     RequirementService requirements) {
        this.query = query;
        this.requirements = requirements;
    }

    /** 新建需求时的建议：标题/描述/域/类型都由调用方传入，可见性仍由 SQL scope 决定。 */
    @Transactional(readOnly = true)
    public SimilarPage similar(String title, String description, Long domainId, String type,
                               int limit) {
        List<RequirementSimilarity.Candidate> candidates =
                query.candidates(title, description, null);
        return toPage(RequirementSimilarity.rankAll(title, description, domainId, type, candidates),
                limit);
    }

    /** 详情页的「相似需求」：以该需求自身为查询条件，排除自身。 */
    @Transactional(readOnly = true)
    public SimilarPage similarTo(long id, int limit) {
        RequirementEntity entity = requirements.requireVisible(id);
        List<RequirementSimilarity.Candidate> candidates =
                query.candidates(entity.getTitle(), entity.getDescription(), id);
        return toPage(RequirementSimilarity.rankAll(entity.getTitle(), entity.getDescription(),
                entity.getDomainId(), entity.getType(), candidates), limit);
    }

    private static SimilarPage toPage(List<Scored> scored, int limit) {
        int capped = RequirementSimilarity.clampLimit(limit);
        List<SimilarItem> items = scored.stream()
                .limit(capped)
                .map(entry -> {
                    RequirementSimilarity.Candidate candidate = entry.candidate();
                    return new SimilarItem(candidate.id(), candidate.code(), candidate.type(),
                            candidate.title(), candidate.status(), candidate.domainId(),
                            candidate.reporterName(), candidate.orgName(), entry.score(),
                            candidate.followCount());
                })
                .toList();
        return new SimilarPage(items, scored.size());
    }
}
