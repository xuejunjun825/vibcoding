package com.example.deliver.reqread;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.scope.ScopedJdbc;
import org.springframework.stereotype.Component;

/**
 * The only place where the delivery side reads requirement / release / domain rows.
 *
 * <p>Four SQL statements, one class: when the requirement side renames a column, this is the
 * single landing point, and a reviewer can check the whole cross-service coupling in one
 * screen. The delivery side never writes these tables — that is enforced statically by
 * {@code scripts/tests/lint.sh} (no INSERT/UPDATE/DELETE against requirement/release here).</p>
 */
@Component
public class ReqReadModel {

    private static final String COLUMNS = """
            r.id AS id, r.tenant_id AS tenant_id, r.code AS code, r.title AS title,
            r.type AS type, r.status AS status, r.priority AS priority,
            r.domain_id AS domain_id, r.assignee_vendor_id AS vendor_id,
            r.release_id AS release_id, r.owner_user_id AS owner_user_id
            """;

    private final ScopedJdbc jdbc;

    public ReqReadModel(ScopedJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the requirement, or {@code null} when it does not exist or is outside scope. */
    public RequirementSnapshot get(long id) {
        List<Map<String, Object>> rows = jdbc.list(
                "SELECT " + COLUMNS + " FROM requirement r WHERE r.id = :id AND /*scope:r*/",
                Map.of("id", id));
        return rows.isEmpty() ? null : toSnapshot(rows.get(0));
    }

    public List<RequirementSnapshot> byRelease(long releaseId) {
        return map(jdbc.list("SELECT " + COLUMNS
                + " FROM requirement r WHERE r.release_id = :releaseId AND /*scope:r*/"
                + " ORDER BY r.id", Map.of("releaseId", releaseId)));
    }

    public List<RequirementSnapshot> byIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return map(jdbc.list("SELECT " + COLUMNS
                + " FROM requirement r WHERE r.id IN (:ids) AND /*scope:r*/"
                + " ORDER BY r.id", Map.of("ids", List.copyOf(ids))));
    }

    /** Map keyed by requirement id, for batch enrichment without N+1 queries. */
    public Map<Long, RequirementSnapshot> byIdsIndexed(Collection<Long> ids) {
        Map<Long, RequirementSnapshot> index = new HashMap<>();
        for (RequirementSnapshot snapshot : byIds(ids)) {
            index.put(snapshot.id(), snapshot);
        }
        return index;
    }

    /**
     * How many requirements the current scope can see — the denominator of defect density.
     * Uses the same scope predicate as every other read, so the number cannot disagree with
     * a requirement list the same user obtains.
     */
    public long countVisible(Long domainId) {
        return countVisible(domainId, null);
    }

    /** Same count narrowed to one vendor — the denominator of a vendor-filtered density card. */
    public long countVisible(Long domainId, Long vendorId) {
        Map<String, Object> params = new HashMap<>();
        params.put("domainId", domainId);
        params.put("vendorId", vendorId);
        return jdbc.count("""
                SELECT COUNT(*) FROM requirement r
                WHERE /*scope:r*/
                  AND (:domainId IS NULL OR r.domain_id = :domainId)
                  AND (:vendorId IS NULL OR r.assignee_vendor_id = :vendorId)
                """, params);
    }

    /** Release name/status for the small "belongs to" line on the defect detail view. */
    public Map<String, Object> release(long releaseId) {
        List<Map<String, Object>> rows = jdbc.list("""
                SELECT rel.id AS id, rel.code AS code, rel.name AS name, rel.status AS status
                FROM `release` rel WHERE rel.id = :releaseId AND /*scope:rel*/
                """, Map.of("releaseId", releaseId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<RequirementSnapshot> map(List<Map<String, Object>> rows) {
        return rows.stream().map(this::toSnapshot).toList();
    }

    private RequirementSnapshot toSnapshot(Map<String, Object> row) {
        return new RequirementSnapshot(
                Rows.toLong(Rows.object(row, "id"), 0L),
                Rows.toLong(Rows.object(row, "tenant_id"), 0L),
                Rows.str(row, "code"),
                Rows.str(row, "title"),
                Rows.str(row, "type"),
                Rows.str(row, "status"),
                Rows.str(row, "priority"),
                Rows.toLong(Rows.object(row, "domain_id"), 0L),
                Rows.toLong(Rows.object(row, "vendor_id")),
                Rows.toLong(Rows.object(row, "release_id")),
                Rows.toLong(Rows.object(row, "owner_user_id")));
    }
}
