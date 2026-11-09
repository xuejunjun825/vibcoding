package com.example.req.collab.wiki;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.requirement.Rows;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Wiki 全文检索（TECHNICAL §3.2 / AC-13）。
 *
 * <p>先过滤可见性再计数：{@code total} 只统计当前调用方看得到的命中，否则「命中数」
 * 本身就成了一条权限旁路（能看到别的域有几篇）。</p>
 *
 * <p>MySQL 的 ngram 分词器不是每个实例都装了（{@code ft_wiki} 建表可能失败），
 * 因此启动时探测 {@code @@ngram_token_size}；缺失或全文检索 0 命中时降级 LIKE，
 * 并把 {@code degraded=true} 透出给前端标注，绝不把「降级」伪装成「没有结果」。</p>
 */
@Service
public class WikiSearchService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    private static final int SNIPPET_RADIUS = 60;

    private final NamedParameterJdbcTemplate jdbc;
    private final ScopedJdbc scoped;
    private volatile Boolean ngramAvailable;

    public WikiSearchService(NamedParameterJdbcTemplate jdbc, ScopedJdbc scoped) {
        this.jdbc = jdbc;
        this.scoped = scoped;
    }

    public WikiViews.SearchResult search(WikiViews.SearchQuery query) {
        String q = query == null || query.q() == null ? "" : query.q().trim();
        if (q.isEmpty()) {
            throw new ApiException(400, "WIKI_SEARCH_EMPTY", "检索词不能为空");
        }
        int page = query.page() == null || query.page() < 1 ? 1 : query.page();
        int size = query.size() == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, query.size()));
        int offset = (page - 1) * size;
        DataScope scope = TenantContext.require();
        String predicate = WikiVisibility.predicate(scope, "s");

        boolean ngram = ngramAvailable();
        List<Map<String, Object>> rows;
        long total;
        boolean degraded = !ngram;
        if (ngram) {
            String booleanQuery = booleanQuery(q);
            if (!booleanQuery.isEmpty()) {
                Map<String, Object> params = new HashMap<>();
                params.put("booleanQuery", booleanQuery);
                params.put("limit", size);
                params.put("offset", offset);
                String where = """
                         FROM wiki_page p
                         JOIN wiki_space s ON s.id = p.space_id
                        WHERE p.status <> 'DELETED'
                          AND MATCH(p.title, p.content_md) AGAINST(:booleanQuery IN BOOLEAN MODE)
                          AND /*scope:p*/""" + predicate;
                rows = scoped.list("SELECT p.id, p.title, p.content_md, p.updated_at, s.name AS space_name"
                        + where + " ORDER BY MATCH(p.title, p.content_md) AGAINST(:booleanQuery IN BOOLEAN MODE) DESC,"
                        + " p.updated_at DESC LIMIT :limit OFFSET :offset", params);
                total = count("SELECT COUNT(*) AS total" + where, params);
                if (total == 0) {
                    degraded = true;
                    rows = List.of();
                }
            } else {
                degraded = true;
                rows = List.of();
                total = 0;
            }
            if (degraded) {
                return likeSearch(q, page, size, offset, scope, predicate, true);
            }
        } else {
            return likeSearch(q, page, size, offset, scope, predicate, true);
        }
        List<WikiViews.SearchHit> items = rows.stream().map(row -> hit(row, q)).toList();
        return new WikiViews.SearchResult(items, total, degraded, page, size);
    }

    private WikiViews.SearchResult likeSearch(String q, int page, int size, int offset,
                                              DataScope scope, String predicate, boolean degraded) {
        List<String> terms = terms(q);
        StringBuilder where = new StringBuilder("""
                 FROM wiki_page p
                 JOIN wiki_space s ON s.id = p.space_id
                WHERE p.status <> 'DELETED'
                  AND /*scope:p*/""").append(predicate);
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < terms.size(); i++) {
            where.append(" AND (p.title LIKE :like").append(i)
                    .append(" OR p.content_md LIKE :like").append(i).append(")");
            params.put("like" + i, "%" + terms.get(i) + "%");
        }
        params.put("limit", size);
        params.put("offset", offset);
        List<Map<String, Object>> rows = scoped.list(
                "SELECT p.id, p.title, p.content_md, p.updated_at, s.name AS space_name" + where
                        + " ORDER BY p.updated_at DESC, p.id DESC LIMIT :limit OFFSET :offset", params);
        long total = count("SELECT COUNT(*) AS total" + where, params);
        List<WikiViews.SearchHit> items = rows.stream().map(row -> hit(row, q)).toList();
        return new WikiViews.SearchResult(items, total, degraded, page, size);
    }

    private long count(String sql, Map<String, Object> params) {
        Map<String, Object> row = scoped.one(sql, params);
        return row == null ? 0L : Rows.toLong(row.get("total"), 0L);
    }

    private WikiViews.SearchHit hit(Map<String, Object> row, String q) {
        String content = Rows.toStr(row.get("content_md"));
        return new WikiViews.SearchHit(
                Rows.toLong(row.get("id"), 0L),
                Rows.toStr(row.get("title")),
                snippet(content, q),
                Rows.toStr(row.get("space_name")),
                Rows.toDateTime(row.get("updated_at")));
    }

    /** 首个命中前后各 60 字，总长 ≤120；命中词用 \u0001/\u0002 标记，前端只负责染色。 */
    static String snippet(String content, String q) {
        String text = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        List<String> terms = terms(q);
        if (text.isEmpty()) {
            return "";
        }
        int hit = -1;
        String hitTerm = null;
        String lower = text.toLowerCase();
        for (String term : terms) {
            int at = lower.indexOf(term.toLowerCase());
            if (at >= 0 && (hit < 0 || at < hit)) {
                hit = at;
                hitTerm = term;
            }
        }
        int start = hit < 0 ? 0 : Math.max(0, hit - SNIPPET_RADIUS);
        int end = Math.min(text.length(), start + 120);
        if (hit >= 0 && hit + hitTerm.length() > end) {
            end = Math.min(text.length(), hit + hitTerm.length() + 20);
            start = Math.max(0, end - 120);
        }
        String out = text.substring(start, end);
        for (String term : terms) {
            int at = out.toLowerCase().indexOf(term.toLowerCase());
            if (at >= 0) {
                out = out.substring(0, at) + '\u0001' + out.substring(at, at + term.length())
                        + '\u0002' + out.substring(at + term.length());
            }
        }
        return (start > 0 ? "…" : "") + out + (end < text.length() ? "…" : "");
    }

    private static String booleanQuery(String q) {
        StringBuilder out = new StringBuilder();
        for (String term : terms(q)) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append('+').append(term);
        }
        return out.toString();
    }

    private static List<String> terms(String q) {
        List<String> out = new ArrayList<>();
        for (String raw : q.trim().split("\\s+")) {
            String cleaned = raw.replaceAll("[+\\-><()~*\"@]", "").trim();
            if (!cleaned.isEmpty()) {
                out.add(cleaned);
            }
        }
        return out.isEmpty() ? List.of(q.trim()) : out;
    }

    private boolean ngramAvailable() {
        Boolean cached = ngramAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (ngramAvailable == null) {
                boolean available;
                try {
                    Integer size = jdbc.queryForObject("SELECT @@ngram_token_size", Map.of(), Integer.class);
                    available = size != null && size > 0;
                } catch (DataAccessException ex) {
                    available = false;
                }
                ngramAvailable = available;
            }
            return ngramAvailable;
        }
    }
}
