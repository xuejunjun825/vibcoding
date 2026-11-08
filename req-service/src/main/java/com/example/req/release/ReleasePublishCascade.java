package com.example.req.release;

import com.example.platform.common.audit.AuditService;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementRepository;
import com.example.req.requirement.RequirementRules;
import com.example.req.requirement.RequirementStateMachine;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 版本发布时的需求级联（D5，TECHNICAL §5.6）。
 *
 * <p>只做两件“已经被前置状态证明完成”的事：
 * <ul>
 *   <li>本版本内 {@code MERGED} 的 RD → {@code DONE}（合并动作已发生，发布只是收口）</li>
 *   <li>本版本内全部 RD 子需求均为 {@code DONE} 的 PDR → {@code DELIVERED}</li>
 * </ul>
 * 不伪造未发生的动作：未合并的 RD 不会因为发布而变 DONE，BUS 也不会自动关闭。</p>
 */
@Component
public class ReleasePublishCascade {

    private final RequirementRepository requirementRepository;
    private final RequirementStateMachine stateMachine;
    private final AuditService auditService;

    public ReleasePublishCascade(RequirementRepository requirementRepository,
                                 RequirementStateMachine stateMachine,
                                 AuditService auditService) {
        this.requirementRepository = requirementRepository;
        this.stateMachine = stateMachine;
        this.auditService = auditService;
    }

    /** 级联结果，用于审计与发布统计（不是 AC 断言对象，但可核对）。 */
    public record Result(int rdClosed, int pdrDelivered) {
    }

    @Transactional
    public Result cascade(ReleaseEntity release) {
        List<RequirementEntity> members = requirementRepository.findByTenantIdAndReleaseId(
                release.getTenantId(), release.getId());

        int rdClosed = 0;
        for (RequirementEntity member : members) {
            if (!RequirementRules.TYPE_RD.equals(member.getType())
                    || !"MERGED".equals(member.getStatus())) {
                continue;
            }
            String from = member.getStatus();
            member.setStatus("DONE");
            requirementRepository.save(member);
            auditTransition(member, "RELEASE_PUBLISH", from, "DONE", release.getId());
            rdClosed++;
        }

        int pdrDelivered = 0;
        for (RequirementEntity member : members) {
            if (!RequirementRules.TYPE_PDR.equals(member.getType())
                    || stateMachine.isTerminal(member.getType(), member.getStatus())) {
                continue;
            }
            List<RequirementEntity> children = requirementRepository
                    .findByTenantIdAndParentIdOrderByCreatedAtAsc(member.getTenantId(), member.getId());
            if (children.isEmpty()) {
                continue;
            }
            boolean allDone = children.stream()
                    .filter(child -> RequirementRules.TYPE_RD.equals(child.getType()))
                    .allMatch(child -> "DONE".equals(child.getStatus()));
            boolean hasRd = children.stream()
                    .anyMatch(child -> RequirementRules.TYPE_RD.equals(child.getType()));
            if (!hasRd || !allDone) {
                continue;
            }
            String from = member.getStatus();
            member.setStatus("DELIVERED");
            requirementRepository.save(member);
            auditTransition(member, "RELEASE_PUBLISH", from, "DELIVERED", release.getId());
            pdrDelivered++;
        }

        return new Result(rdClosed, pdrDelivered);
    }

    private void auditTransition(RequirementEntity entity, String action, String from, String to,
                                 long releaseId) {
        auditService.record("STATE_CHANGE", "REQUIREMENT", entity.getId(), Map.of(
                "from", Objects.requireNonNullElse(from, ""),
                "to", to,
                "action", action,
                "source", "RELEASE_PUBLISH",
                "comment", "版本 " + releaseId + " 发布级联"));
    }
}
