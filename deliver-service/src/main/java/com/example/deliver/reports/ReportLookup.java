package com.example.deliver.reports;

import java.util.List;
import java.util.Optional;

/**
 * The narrow read port the portal needs from reports.
 *
 * <p>A portal card shows a report's name and total; it never needs the CRUD service. Keeping this
 * interface (declared in {@code reports}, implemented by {@code ReportCards}) lets {@code portal}
 * depend on {@code reports} without the reverse edge, which would be a package cycle — the pin
 * endpoint lives in {@code portal} precisely to keep that direction one-way.</p>
 */
public interface ReportLookup {

    /** The card-sized projection of one report. */
    record ReportCard(long id, String name, String dataset, String definitionJson,
                      String scope, Long scopeRef, long ownerId) {
    }

    Optional<ReportCard> card(long reportId);

    /** Recent reports this viewer may see, own first, newest first within each group. */
    List<ReportCard> recent(long ownerId, int limit);

    boolean visible(long reportId);
}
