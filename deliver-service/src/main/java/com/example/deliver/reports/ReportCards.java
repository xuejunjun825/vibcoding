package com.example.deliver.reports;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.deliver.analytics.AnalyticsDirectory;
import com.example.deliver.support.ShareVisibility;
import com.example.platform.common.tenant.TenantContext;

/**
 * The one implementation of {@link ReportLookup}: JPA lookups plus the shared-tier predicate.
 *
 * <p>Both the portal read model and the report detail use {@link ShareVisibility#visible}, so a
 * report that shows up as a card cannot then 404 on open, and one that 404s cannot appear as a
 * card. Repository queries carry no scope condition on purpose — visibility lives in exactly one
 * place.</p>
 */
@Component
public class ReportCards implements ReportLookup {

    private static final String ACTIVE = "ACTIVE";

    private final ReportDefinitionRepository reports;
    private final AnalyticsDirectory directory;

    public ReportCards(ReportDefinitionRepository reports, AnalyticsDirectory directory) {
        this.reports = reports;
        this.directory = directory;
    }

    @Override
    public Optional<ReportCard> card(long reportId) {
        long tenantId = TenantContext.require().tenantId();
        return reports.findByIdAndTenantIdAndStatus(reportId, tenantId, ACTIVE).map(ReportCards::toCard);
    }

    @Override
    public List<ReportCard> recent(long ownerId, int limit) {
        long tenantId = TenantContext.require().tenantId();
        ShareVisibility.Viewer viewer = viewer();
        List<ReportDefinitionEntity> all =
                reports.findByTenantIdAndStatusOrderByUpdatedAtDesc(tenantId, ACTIVE);
        List<ReportCard> mine = new ArrayList<>();
        List<ReportCard> shared = new ArrayList<>();
        for (ReportDefinitionEntity entity : all) {
            if (!ShareVisibility.visible(entity.getScope(), entity.getScopeRef(), entity.getOwnerId(), viewer)) {
                continue;
            }
            if (entity.getOwnerId() == ownerId) {
                mine.add(toCard(entity));
            } else {
                shared.add(toCard(entity));
            }
        }
        int max = Math.max(0, limit);
        List<ReportCard> out = new ArrayList<>(Math.min(max, mine.size() + shared.size()));
        out.addAll(mine);
        if (out.size() < max) {
            out.addAll(shared.subList(0, Math.min(shared.size(), max - out.size())));
        }
        return out;
    }

    @Override
    public boolean visible(long reportId) {
        return card(reportId)
                .filter(c -> ShareVisibility.visible(c.scope(), c.scopeRef(), c.ownerId(), viewer()))
                .isPresent();
    }

    private ShareVisibility.Viewer viewer() {
        Set<Long> roleIds = directory.roleIds(TenantContext.roles());
        return ShareVisibility.viewer(roleIds);
    }

    private static ReportCard toCard(ReportDefinitionEntity entity) {
        return new ReportCard(entity.getId(), entity.getName(), entity.getDataset(),
                entity.getDefinitionJson(), entity.getScope(), entity.getScopeRef(), entity.getOwnerId());
    }
}
