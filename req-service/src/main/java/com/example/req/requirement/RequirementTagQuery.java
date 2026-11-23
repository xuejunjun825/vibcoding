package com.example.req.requirement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import org.springframework.stereotype.Component;

/**
 * Requirement tags, read in one batch per page (r39).
 *
 * <p>Tags are shown on the list and the detail view, so a per-requirement query would make the
 * pool page N+1. One query per page, keyed by requirement id, keeps the cost of "show tags"
 * independent of the number of tags.</p>
 *
 * <p>The join carries the {@code /*scope:r*}{@code /} marker on purpose: a tag must never be a
 * side channel around data isolation — a tag on a requirement you cannot see is a tag you cannot
 * read.</p>
 */
@Component
public class RequirementTagQuery {

    private static final int CHUNK = 500;

    private final ScopedJdbc jdbc;

    public RequirementTagQuery(ScopedJdbc jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, List<RequirementViews.TagView>> byRequirementIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        long tenantId = TenantContext.require().tenantId();
        Map<Long, List<RequirementViews.TagView>> result = new LinkedHashMap<>();
        List<Long> all = new ArrayList<>(ids);
        for (int start = 0; start < all.size(); start += CHUNK) {
            List<Long> chunk = all.subList(start, Math.min(start + CHUNK, all.size()));
            List<Map<String, Object>> rows = jdbc.list("""
                    SELECT t.requirement_id AS requirement_id, t.tag AS tag, t.source AS source
                      FROM requirement_tag t
                      JOIN requirement r ON r.id = t.requirement_id AND /*scope:r*/
                     WHERE t.tenant_id = :tenant AND t.requirement_id IN (:ids)
                     ORDER BY t.id
                    """, Map.of("tenant", tenantId, "ids", chunk));
            for (Map<String, Object> row : rows) {
                Long requirementId = Rows.toLong(Rows.object(row, "requirement_id"));
                if (requirementId == null) {
                    continue;
                }
                result.computeIfAbsent(requirementId, key -> new ArrayList<>())
                        .add(new RequirementViews.TagView(Rows.str(row, "tag"),
                                Rows.str(row, "source")));
            }
        }
        return result;
    }
}
