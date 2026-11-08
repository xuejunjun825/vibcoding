package com.example.req.deploy;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.approval.DocumentSummaryProvider;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 需求发布审批（{@code REQ_DEPLOY}）的摘要来源。
 *
 * <p>审批人看到的是"发什么、发到哪个环境、什么时候、谁负责、几项准入没过（是否已强制放行）"——
 * 这些字段决定了要不要批，所以不走通用摘要。越权（版本不在域范围）时名称打码。</p>
 */
@Component
public class DeploySummaryProvider implements DocumentSummaryProvider {

    public static final String BIZ_TYPE = DeployOrderService.APPROVAL_BIZ_TYPE;

    private final DeployOrderRepository deployOrderRepository;
    private final DeployOrderRequirementRepository orderRequirementRepository;
    private final ReleaseRepository releaseRepository;
    private final AdmissionEvaluator admissionEvaluator;

    public DeploySummaryProvider(DeployOrderRepository deployOrderRepository,
                                 DeployOrderRequirementRepository orderRequirementRepository,
                                 ReleaseRepository releaseRepository,
                                 AdmissionEvaluator admissionEvaluator) {
        this.deployOrderRepository = deployOrderRepository;
        this.orderRequirementRepository = orderRequirementRepository;
        this.releaseRepository = releaseRepository;
        this.admissionEvaluator = admissionEvaluator;
    }

    @Override
    public String bizType() {
        return BIZ_TYPE;
    }

    @Override
    public Map<String, Object> attributes(long tenantId, long bizId) {
        DeployOrderEntity order = deployOrderRepository.findByIdAndTenantId(bizId, tenantId).orElse(null);
        if (order == null) {
            return Map.of();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("env", order.getEnv());
        attributes.put("releaseId", order.getReleaseId());
        attributes.put("forcedRelease", order.isForcedRelease());
        return attributes;
    }

    @Override
    public Map<String, Object> summary(long tenantId, long bizId, long viewerId) {
        DeployOrderEntity order = deployOrderRepository.findByIdAndTenantId(bizId, tenantId).orElse(null);
        if (order == null) {
            return Map.of("id", bizId, "state", "MISSING");
        }
        ReleaseEntity release = releaseRepository.findByIdAndTenantId(order.getReleaseId(), tenantId).orElse(null);
        boolean visible = visibleTo(release);
        DeployViews.AdmissionSnapshot snapshot = admissionEvaluator.snapshot(order);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", order.getId());
        summary.put("code", order.getCode());
        summary.put("releaseId", order.getReleaseId());
        summary.put("releaseName", visible && release != null ? release.getName() : "（无权查看名称）");
        summary.put("env", order.getEnv());
        summary.put("status", order.getStatus());
        summary.put("windowStart", String.valueOf(order.getWindowStart()));
        summary.put("windowEnd", String.valueOf(order.getWindowEnd()));
        summary.put("artifactVersion", order.getArtifactVersion());
        summary.put("requirementCount", orderRequirementRepository.countByTenantIdAndDeployOrderId(
                tenantId, order.getId()));
        summary.put("forcedRelease", order.isForcedRelease());
        summary.put("forceReleaseReason", order.isForcedRelease() ? order.getForceReleaseReason() : null);
        summary.put("admissionFailedCodes", snapshot == null ? java.util.List.of()
                : snapshot.items().stream().filter(item -> !item.passed()).map(AdmissionItem::code).toList());
        summary.put("masked", !visible);
        return summary;
    }

    private boolean visibleTo(ReleaseEntity release) {
        if (release == null) {
            return false;
        }
        DataScope scope;
        try {
            scope = TenantContext.require();
        } catch (RuntimeException ex) {
            return false;
        }
        if (!Objects.equals(scope.tenantId(), release.getTenantId())) {
            return false;
        }
        return scope.domainIds() == null || scope.domainIds().isEmpty()
                || scope.domainIds().contains(release.getDomainId());
    }
}
