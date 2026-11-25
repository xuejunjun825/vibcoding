package com.example.req.requirement;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.requirement.RequirementPoolOpsViews.MergeRequest;
import com.example.req.requirement.RequirementPoolOpsViews.MergeResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 重复需求合并（r47 契约 §3.3）：一个事务里关源、搬跟单、迁子需求、写审计、发通知。
 *
 * <p>为什么前置校验拆到 {@link RequirementMergeRules}：六条规则里只有「父子环」需要查库，
 * 其余都是两个快照的比较；拆开后单测可以直接把错误码逐条打出来，而事务代码只负责加锁、
 * 取快照、调规则、落效果。</p>
 */
@Service
public class RequirementMergeService {

    private final RequirementService requirements;
    private final RequirementRepository repository;
    private final RequirementStateMachine stateMachine;
    private final RequirementFollowQuery follows;
    private final AuditService auditService;
    private final NotificationWriter notifications;
    private final JdbcTemplate jdbc;

    public RequirementMergeService(RequirementService requirements,
                                   RequirementRepository repository,
                                   RequirementStateMachine stateMachine,
                                   RequirementFollowQuery follows,
                                   AuditService auditService,
                                   NotificationWriter notifications,
                                   JdbcTemplate jdbc) {
        this.requirements = requirements;
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.follows = follows;
        this.auditService = auditService;
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    @Transactional
    public MergeResult merge(long sourceId, MergeRequest request) {
        DataScope scope = TenantContext.require();
        if (request == null || request.targetId() == null) {
            throw RequirementErrors.validation("targetId 必填");
        }
        long targetId = request.targetId();
        if (targetId <= 0) {
            throw RequirementErrors.validation("targetId 非法", Map.of("targetId", targetId));
        }
        // 先做可见性判定：任何人都不能拿不可见的需求当源或目标（越权在进事务效果前就 403）。
        requirements.requireVisible(sourceId);
        requirements.requireVisible(targetId);

        // 锁两行：并发「A→B / B→A」在环检查前就被串行化，避免两边都通过校验后各写一半。
        RequirementEntity source = repository.findWithLockByIdAndTenantId(sourceId, scope.tenantId())
                .orElseThrow(() -> RequirementErrors.notFound("需求", sourceId));
        RequirementEntity target = repository.findWithLockByIdAndTenantId(targetId, scope.tenantId())
                .orElseThrow(() -> RequirementErrors.notFound("需求", targetId));

        boolean related = related(sourceId, targetId, scope.tenantId());
        RequirementMergeRules.check(
                new RequirementMergeRules.Source(sourceId, source.getType(), source.getStatus(),
                        source.getMergedIntoId(),
                        stateMachine.isTerminalAcrossTypes(source.getStatus())),
                new RequirementMergeRules.Target(targetId, target.getType(), target.getStatus(),
                        target.getMergedIntoId()),
                related);

        String reason = normalizeReason(request.reason());
        List<Long> followerIds = follows.userIds(sourceId);
        int movedFollows = follows.moveAll(sourceId, targetId);
        int movedChildren = jdbc.update(
                "UPDATE requirement SET parent_id = ?, updated_at = NOW(3), updated_by = ?"
                        + " WHERE tenant_id = ? AND parent_id = ?",
                targetId, scope.userId(), scope.tenantId(), sourceId);

        source.setStatus("CLOSED");
        source.setCloseReason("MERGED");
        source.setMergedIntoId(targetId);
        source.setClosedAt(LocalDateTime.now());
        repository.save(source);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sourceId", sourceId);
        detail.put("targetId", targetId);
        detail.put("targetCode", target.getCode());
        detail.put("reason", reason);
        detail.put("movedFollows", movedFollows);
        detail.put("movedChildren", movedChildren);
        auditService.record("REQUIREMENT_MERGED", RequirementService.BIZ_TYPE, sourceId, detail);

        notifyMerged(source, target, followerIds);
        return new MergeResult(sourceId, targetId, movedFollows, movedChildren, true);
    }

    /**
     * 目标是否与源在同一棵子树里（含同一 id）。
     *
     * <p>两个方向都要走：目标可能是源的祖先（把父合进子会造环），也可能是源的后代（把子合进
     * 父会让子需求 reparent 回自己）。只查一个方向是这类 bug 的经典漏法。</p>
     */
    private boolean related(long sourceId, long targetId, long tenantId) {
        if (sourceId == targetId) {
            return true;
        }
        return ancestorChainContains(sourceId, targetId, tenantId)
                || ancestorChainContains(targetId, sourceId, tenantId);
    }

    /** 从 startId 沿 parent_id 上行，途中是否出现 candidate。seen 防脏数据成环导致死循环。 */
    private boolean ancestorChainContains(long startId, long candidate, long tenantId) {
        Set<Long> seen = new HashSet<>();
        Long current = startId;
        while (current != null && seen.add(current)) {
            RequirementEntity entity = repository.findByIdAndTenantId(current, tenantId).orElse(null);
            if (entity == null || entity.getParentId() == null) {
                return false;
            }
            if (entity.getParentId() == candidate) {
                return true;
            }
            current = entity.getParentId();
        }
        return false;
    }

    /**
     * 合并通知：源需求报告人 + 源跟单人（去重去 0），按合并事件各发一次。
     *
     * <p>通知失败不能回滚合并（{@link NotificationWriter} 是 REQUIRES_NEW + best-effort），
     * 否则一次通知写库抖动就会让运营的合并白做。</p>
     */
    private void notifyMerged(RequirementEntity source, RequirementEntity target,
                              List<Long> followerIds) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (source.getReporterId() != null) {
            recipients.add(source.getReporterId());
        }
        recipients.addAll(followerIds);
        String content = "需求「" + source.getCode() + " " + source.getTitle()
                + "」已合并到「" + target.getCode() + " " + target.getTitle() + "」";
        for (Long recipient : recipients) {
            if (recipient == null || recipient <= 0) {
                continue;
            }
            // biz_id 用 source：接收人跟的是「被合并的那条」，从通知点进去要能看到它的去向
            // （status=CLOSED / mergedIntoId=target）；用 target 会让 source 提报人收到一条自己
            // 已经订阅不到的通知（门禁 P3-9 也按 source 计数）。
            notifications.send(recipient, "REQUIREMENT_MERGED", "需求已合并",
                    content, RequirementService.BIZ_TYPE, source.getId());
        }
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "重复需求合并";
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200);
    }
}
