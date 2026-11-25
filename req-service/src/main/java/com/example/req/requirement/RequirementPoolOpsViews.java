package com.example.req.requirement;

import java.util.List;

/**
 * 需求池运营（r47）的响应/请求体：与 {@link RequirementViews} 分开，避免把「池子运营」的
 * 契约塞进已被列表/详情广泛复用的视图类里。
 */
public final class RequirementPoolOpsViews {

    private RequirementPoolOpsViews() {
    }

    /** 相似需求条目：字段与契约 §3.1 一一对应，score 已四舍五入到 2 位。 */
    public record SimilarItem(long id, String code, String type, String title, String status,
                              Long domainId, String reporterName, String orgName, double score,
                              long followCount) {
    }

    /** 相似列表；{@code total} 是过滤阈值后的条数（不是候选池大小）。 */
    public record SimilarPage(List<SimilarItem> items, long total) {
    }

    /** 跟单请求体；note 可空（契约 §3.2）。 */
    public record FollowRequest(String note) {
    }

    /** 跟单结果：201 新建 / 200 已跟单都返回同一形状，{@code created} 区分两者。 */
    public record FollowResult(long id, long followCount, boolean followedByMe, boolean created) {
    }

    /** 合并请求体；reason 可空（缺省写审计为「重复需求合并」）。 */
    public record MergeRequest(Long targetId, String reason) {
    }

    /** 合并结果（契约 §3.3）。 */
    public record MergeResult(long sourceId, long targetId, int movedFollows, int movedChildren,
                              boolean closed) {
    }
}
