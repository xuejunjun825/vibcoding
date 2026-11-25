package com.example.req.requirement;

import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.contract.StateTables;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相似需求的候选池（r47 契约 §3.1 的 SQL 半边）。
 *
 * <p>只做三件事：可见性（复用 {@code /*scope:r*}{@code /}，厂商/域/组织与列表同一条 SQL 作用域）、
 * 排除（自身、已合并、终态）、LIKE 预筛（最多 {@link RequirementSimilarity#PREFILTER_LIMIT} 行）。
 * 排序与阈值一概不在这里——它们在 {@link RequirementSimilarity}，两个入口共用。</p>
 *
 * <p>为什么不用 MySQL 全文/ngram 索引：契约要求打分公式在 Java 侧可解释、可单测，SQL 只当漏斗。</p>
 */
@Component
public class RequirementSimilarQuery {

    /**
     * 终态从 {@link StateTables} 推导而不是抄字面量：BUS=CLOSED / PDR=ACCEPTED_CLOSED / RD=DONE。
     * 哪天状态机改了终态，这里跟着改，不会留下「已关闭需求还在相似列表里」的漏网。
     */
    private static final List<String> TERMINAL_STATUSES = terminalStatuses();

    private final ScopedJdbc jdbc;
    private final RequirementFollowQuery followQuery;

    public RequirementSimilarQuery(ScopedJdbc jdbc, RequirementFollowQuery followQuery) {
        this.jdbc = jdbc;
        this.followQuery = followQuery;
    }

    public List<RequirementSimilarity.Candidate> candidates(String title, String description,
                                                            Long excludeId) {
        List<String> terms = RequirementSimilarity.prefilterTerms(title, description);
        if (terms.isEmpty()) {
            return List.of();
        }
        StringBuilder likes = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < terms.size(); i++) {
            String key = "term" + i;
            params.put(key, "%" + terms.get(i) + "%");
            if (i > 0) {
                likes.append(" OR ");
            }
            likes.append("r.title LIKE :").append(key)
                    .append(" OR r.description LIKE :").append(key);
        }
        params.put("terminal", TERMINAL_STATUSES);
        StringBuilder sql = new StringBuilder(
                "SELECT r.id AS id, r.code AS code, r.type AS type, r.title AS title,"
                        + " r.description AS description, r.status AS status, r.domain_id AS domain_id,"
                        + " u.name AS reporter_name, o.name AS org_name"
                        + " FROM requirement r"
                        + " LEFT JOIN sys_user u ON u.id = r.reporter_id AND u.tenant_id = r.tenant_id"
                        + " LEFT JOIN sys_org o ON o.id = r.origin_org_id AND o.tenant_id = r.tenant_id"
                        + " WHERE /*scope:r*/"
                        + "   AND r.merged_into_id IS NULL"
                        + "   AND r.status NOT IN (:terminal)");
        if (excludeId != null && excludeId > 0) {
            params.put("excludeId", excludeId);
            sql.append("   AND r.id <> :excludeId");
        }
        sql.append("   AND (").append(likes).append(")")
                .append(" ORDER BY r.updated_at DESC, r.id DESC")
                .append(" LIMIT ").append(RequirementSimilarity.PREFILTER_LIMIT);

        List<Map<String, Object>> rows = jdbc.list(sql.toString(), params);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(row -> longOf(row.get("id"))).filter(id -> id != null).toList();
        Map<Long, RequirementFollowQuery.Stats> stats = followQuery.stats(ids);
        List<RequirementSimilarity.Candidate> candidates = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Long id = longOf(row.get("id"));
            RequirementFollowQuery.Stats follow = stats.getOrDefault(id, RequirementFollowQuery.Stats.EMPTY);
            candidates.add(new RequirementSimilarity.Candidate(
                    id == null ? 0L : id,
                    text(row.get("code")),
                    text(row.get("type")),
                    text(row.get("title")),
                    text(row.get("description")),
                    text(row.get("status")),
                    longOf(row.get("domain_id")),
                    text(row.get("reporter_name")),
                    text(row.get("org_name")),
                    follow.followCount()));
        }
        return candidates;
    }

    private static List<String> terminalStatuses() {
        // 终态在 StateTables 里只作为迁移的「目标」出现，TABLE 的 key 不含它们（CLOSED /
        // ACCEPTED_CLOSED / DONE 都不是任何迁移的起点），所以遍历 statuses(type) 一个都取不到——
        // 第一版就是这么写的，结果 SQL 展开成 status NOT IN ()，相似检索整条 500。
        // 这里改成「字面量 + 状态机校验」：状态机哪天改了终态，启动时立刻炸，而不是静默漏过滤。
        List<String> terminal = new ArrayList<>();
        for (String[] pair : new String[][]{
                {StateTables.BUS, "CLOSED"},
                {StateTables.PDR, "ACCEPTED_CLOSED"},
                {StateTables.RD, "DONE"}}) {
            if (!StateTables.isTerminal(pair[0], pair[1])) {
                throw new IllegalStateException("状态机终态已变更，相似检索的排除集需要同步："
                        + pair[0] + "/" + pair[1]);
            }
            terminal.add(pair[1]);
        }
        return List.copyOf(terminal);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : Long.valueOf(value.toString());
    }
}
