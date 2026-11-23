package com.example.req.requirement.change;

import com.example.platform.contract.DataScope;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseRepository;
import com.example.req.release.ReleaseRequirementEntity;
import com.example.req.release.ReleaseRequirementRepository;
import com.example.req.release.ReleaseService;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementErrors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 需求↔版本关联的移动（变更单专用通路）。
 *
 * <p>为什么不复用 {@code ReleaseService.addRequirements/removeRequirement}：那两个入口对
 * 非 PLAN 版本直接 409 {@code RELEASE_LOCKED}，而变更单的语义恰恰是「已锁定/已发布版本
 * 仍可应用内容变更，但必须留痕」（AC-10 / {@code CHANGE_AFTER_RELEASE_LOCK}）。所以这里
 * 绕开版本可编辑闸，改由变更单的 Validator 前置校验目标版本，并在 apply 里额外写锁审计。</p>
 *
 * <p>本类只维护关联行与实体内存里的 {@code releaseId}，不调用 {@code save}：调用方已经在
 * 同一事务里持有需求行锁并负责保存，避免两次 flush 造成半写。</p>
 */
@Component
public class RequirementReleaseMembership {

    private final ReleaseRepository releases;
    private final ReleaseRequirementRepository members;

    public RequirementReleaseMembership(ReleaseRepository releases,
                                        ReleaseRequirementRepository members) {
        this.releases = releases;
        this.members = members;
    }

    /**
     * 把需求移动到目标版本（{@code null} 表示移出全部版本）。
     *
     * @throws com.example.platform.common.error.ApiException 404 目标版本不存在或不可见；
     *         400 目标版本已取消
     */
    @Transactional
    public void move(RequirementEntity entity, Long targetReleaseId, long actorId) {
        DataScope scope = TenantContext.require();
        long tenantId = entity.getTenantId() != null ? entity.getTenantId() : scope.tenantId();
        if (targetReleaseId != null) {
            ReleaseEntity target = releases.findByIdAndTenantId(targetReleaseId, tenantId)
                    .orElseThrow(() -> RequirementErrors.notFound("发布版本", targetReleaseId));
            if (ReleaseService.STATUS_CANCELLED.equals(target.getStatus())) {
                throw RequirementErrors.validation("目标版本已取消，不能挂入需求",
                        java.util.Map.of("releaseId", targetReleaseId));
            }
        }
        List<ReleaseRequirementEntity> existing =
                members.findByTenantIdAndRequirementId(tenantId, entity.getId());
        for (ReleaseRequirementEntity membership : existing) {
            if (!same(membership.getReleaseId(), targetReleaseId)) {
                members.delete(membership);
            }
        }
        if (targetReleaseId != null && !members.existsByTenantIdAndReleaseIdAndRequirementId(
                tenantId, targetReleaseId, entity.getId())) {
            ReleaseRequirementEntity created = new ReleaseRequirementEntity();
            created.setTenantId(tenantId);
            created.setReleaseId(targetReleaseId);
            created.setRequirementId(entity.getId());
            created.setAddedBy(actorId);
            members.save(created);
        }
        entity.setReleaseId(targetReleaseId);
    }

    private static boolean same(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }
}
