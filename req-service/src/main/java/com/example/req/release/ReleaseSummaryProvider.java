package com.example.req.release;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.approval.DocumentSummaryProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 版本发布审批（{@code RELEASE}）的摘要来源。
 *
 * <p>条件分支需要 {@code domainId/gateMode}，审批中心展示需要人数与人天聚合；
 * 越权（域不在范围）时名称打码，不返回成员明细。</p>
 */
@Component
public class ReleaseSummaryProvider implements DocumentSummaryProvider {

    public static final String BIZ_TYPE = ReleaseService.BIZ_TYPE;

    private final ReleaseRepository releaseRepository;
    private final ReleaseRequirementRepository releaseRequirementRepository;

    public ReleaseSummaryProvider(ReleaseRepository releaseRepository,
                                  ReleaseRequirementRepository releaseRequirementRepository) {
        this.releaseRepository = releaseRepository;
        this.releaseRequirementRepository = releaseRequirementRepository;
    }

    @Override
    public String bizType() {
        return BIZ_TYPE;
    }

    @Override
    public Map<String, Object> attributes(long tenantId, long bizId) {
        ReleaseEntity release = releaseRepository.findByIdAndTenantId(bizId, tenantId).orElse(null);
        if (release == null) {
            return Map.of();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("domainId", release.getDomainId());
        attributes.put("gateMode", release.getGateMode());
        attributes.put("status", release.getStatus());
        return attributes;
    }

    @Override
    public Map<String, Object> summary(long tenantId, long bizId, long viewerId) {
        ReleaseEntity release = releaseRepository.findByIdAndTenantId(bizId, tenantId).orElse(null);
        if (release == null) {
            return Map.of("id", bizId, "state", "MISSING");
        }
        boolean visible = visibleTo(release);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", release.getId());
        summary.put("code", release.getCode());
        summary.put("name", visible ? release.getName() : "（无权查看名称）");
        summary.put("domainId", release.getDomainId());
        summary.put("status", release.getStatus());
        summary.put("gateMode", release.getGateMode());
        summary.put("startDate", String.valueOf(release.getStartDate()));
        summary.put("endDate", String.valueOf(release.getEndDate()));
        summary.put("plannedReleaseDate", String.valueOf(release.getPlannedReleaseDate()));
        summary.put("memberCount", releaseRequirementRepository.countByTenantIdAndReleaseId(
                tenantId, release.getId()));
        summary.put("masked", !visible);
        return summary;
    }

    private boolean visibleTo(ReleaseEntity release) {
        DataScope scope;
        try {
            scope = TenantContext.require();
        } catch (RuntimeException ex) {
            return false;
        }
        if (!Objects.equals(scope.tenantId(), release.getTenantId())) {
            return false;
        }
        return scope.domainIds().isEmpty() || scope.domainIds().contains(release.getDomainId());
    }
}
