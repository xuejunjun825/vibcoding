package com.example.req.requirement;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import com.example.platform.contract.StateTables;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalException;
import com.example.req.approval.ApprovalStore;
import com.example.req.approval.ApprovalTask;
import com.example.req.approval.ApproverDirectory;
import com.example.req.approval.StartApproval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 存量回填的<b>单行写入器</b>：一个 {@code REQUIRES_NEW} 事务内完成
 * 「重读 → 重判 → 校验人天 → 解析审批人 → 建单 → 真实审批人批准 → 复核」。
 *
 * <p>为什么必须是独立 bean：{@code @Transactional(REQUIRES_NEW)} 走 Spring 代理才生效，
 * 自调用等于没加事务（仓内 {@code SyncLogWriter} 同因独立成 bean）。把「一行失败不拖垮整批」
 * 落成<b>事务边界</b>而不是调用方的 try/catch：{@link RowFailure} 抛出时本行事务整体回滚，
 * 刚建的实例（含 wf_instance/wf_task/wf_history）一并消失，不会留下 PENDING 孤儿实例把
 * 后续重跑全挡成 {@code HAS_ACTIVE_ESTIMATE}。</p>
 *
 * <p>为什么执行前必须重读人天：RD 获批会经 {@code applyApprovedEstimate} 把父 PDR 的
 * {@code estimate_days} 改写成子需求汇总值。用计划阶段一次性读出的旧值去建单会反覆盖汇总，
 * 立刻派生 {@code STALE}。所以扫描按 RD → PDR 排序，且人天一律取「建单那一刻」的库值。</p>
 *
 * <p>判定零复制：状态只问 {@link WbsEstimateService#statusOf}，终态只问
 * {@link StateTables#isTerminal}，人天精度只问 {@link RequirementRules#checkHalfDay}，
 * 审批人只问 {@link ApproverDirectory}（域负责人 → PLATFORM_ADMIN 升序兜底）。
 * 本类不写任何 {@code owner_user_id} SQL、不直插 {@code wf_*}。</p>
 */
@Component
public class WbsBackfillRowWriter {

    private static final Logger log = LoggerFactory.getLogger(WbsBackfillRowWriter.class);

    /** 回退审批人角色：域负责人取不到时的兜底（取 id 升序第一个）。 */
    private static final String FALLBACK_ROLE = "PLATFORM_ADMIN";

    private final ObjectProvider<ApprovalEngine> engine;
    private final ApprovalStore store;
    private final RequirementService requirements;
    private final WbsEstimateService estimates;
    private final ApproverDirectory directory;

    public WbsBackfillRowWriter(ObjectProvider<ApprovalEngine> engine,
                                ApprovalStore store,
                                RequirementService requirements,
                                WbsEstimateService estimates,
                                ApproverDirectory directory) {
        this.engine = engine;
        this.store = store;
        this.requirements = requirements;
        this.estimates = estimates;
        this.directory = directory;
    }

    /**
     * 补一条需求：成功返回 {@code CREATED} 行，幂等/并发命中返回 {@code SKIPPED} 行，
     * 任何不满足都抛 {@link RowFailure}（本行事务已回滚，调用方收敛成 {@code FAILED} 行）。
     *
     * <p>调用前 {@link TenantContext} 必须已绑定租户（服务层用 {@link DataScope#system} 绑过）；
     * 本方法内部逐段切身份，退出时还原。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WbsBackfillViews.Row apply(WbsBackfillViews.Candidate candidate, long tenantId,
                                      String batchId) {
        RequirementEntity entity = requirements.requireVisible(candidate.requirementId());
        String estimateStatus = estimates.statusOf(entity);

        // 并发第二道闸：计划到现在这段时间里，可能已经有人提交/批准了（r27 的唯一判态表）。
        if (WbsEstimateService.STATUS_APPROVED.equals(estimateStatus)) {
            return row(entity, candidate, estimateStatus, WbsBackfillViews.RESULT_SKIPPED,
                    WbsBackfillViews.REASON_HAS_APPROVED_ESTIMATE, null, null, null, List.of());
        }
        if (WbsEstimateService.STATUS_PENDING.equals(estimateStatus)) {
            return row(entity, candidate, estimateStatus, WbsBackfillViews.RESULT_SKIPPED,
                    WbsBackfillViews.REASON_HAS_ACTIVE_ESTIMATE, null, null, null, List.of());
        }
        if (RequirementRules.TYPE_BUS.equals(entity.getType())
                || StateTables.isTerminal(entity.getType(), entity.getStatus())) {
            // 计划阶段已判过；重判只为防「扫描后被关闭/改成 BUS」的窄窗口。
            String reason = RequirementRules.TYPE_BUS.equals(entity.getType())
                    ? WbsBackfillViews.REASON_TYPE_BUS : WbsBackfillViews.REASON_TERMINAL;
            return row(entity, candidate, estimateStatus, WbsBackfillViews.RESULT_SKIPPED,
                    reason, null, null, null, List.of());
        }

        BigDecimal days = entity.getEstimateDays();
        if (days == null || days.signum() <= 0) {
            throw new RowFailure(WbsBackfillViews.REASON_NO_ESTIMATE_DAYS, null);
        }
        RequirementRules.Violation precision = RequirementRules.checkHalfDay(days, "estimateDays");
        if (precision != null) {
            throw new RowFailure(WbsBackfillViews.REASON_INVALID_ESTIMATE_DAYS, null);
        }

        ApprovalEngine approvalEngine = engine.getIfAvailable();
        if (approvalEngine == null) {
            throw new RowFailure(WbsBackfillViews.REASON_ROW_FAILED, null);
        }
        Initiated initiated = resolveInitiator(tenantId, entity.getDomainId());
        if (initiated == null) {
            throw new RowFailure(WbsBackfillViews.REASON_NO_APPROVER, null);
        }

        DataScope previousScope = TenantContext.scopeOrNull();
        TenantContext.Principal previousPrincipal = TenantContext.principal();
        Long instanceId = null;
        try {
            long requirementId = entity.getId();
            TenantContext.set(scope(tenantId, initiated.userId()));
            instanceId = approvalEngine.start(new StartApproval(
                    WbsEstimateService.BIZ_TYPE, requirementId, entity.getType(),
                    entity.getDomainId(), attributes(days, batchId, requirementId,
                            initiated.source()), null, null)).id();
            long startedInstanceId = instanceId;

            // 只能由该实例的「真实待办审批人」批准：引擎 assertAssignee 禁代批（TECH §3.3/§7-2）。
            ApprovalTask task = pendingTask(startedInstanceId, initiated.userId())
                    .orElseThrow(() -> new RowFailure(WbsBackfillViews.REASON_NO_APPROVER,
                            startedInstanceId));
            TenantContext.set(scope(tenantId, task.getApproverId()));
            approvalEngine.approve(task.getId(), note(batchId));

            // 复核读回受管实体（回调刚改的人天必须可见）；不能拿计划快照比对。
            String after = estimates.statusOf(requirements.requireVisible(requirementId));
            if (!WbsEstimateService.STATUS_APPROVED.equals(after)) {
                throw new RowFailure(WbsBackfillViews.REASON_APPROVED_NOT_DERIVED, instanceId);
            }

            Long approverId = task.getApproverId();
            String source = approverId != null && approverId == initiated.userId()
                    ? initiated.source() : WbsBackfillViews.SOURCE_PENDING_TASK;
            List<String> flags = WbsBackfillViews.SOURCE_DOMAIN_OWNER.equals(source)
                    ? List.of() : List.of(WbsBackfillViews.FLAG_FALLBACK_APPROVER);
            log.info("wbs-backfill: {} 已补评估单 instanceId={} approver={}({})",
                    entity.getCode(), instanceId, task.getApproverId(), source);
            return row(entity, candidate, estimateStatus, WbsBackfillViews.RESULT_CREATED,
                    null, instanceId, task.getApproverId(), source, flags);
        } catch (RowFailure failure) {
            throw failure;
        } catch (RuntimeException ex) {
            throw new RowFailure(reasonOf(ex), instanceId);
        } finally {
            TenantContext.clear();
            if (previousScope != null) {
                TenantContext.set(previousScope);
            }
            if (previousPrincipal != null) {
                TenantContext.setPrincipal(previousPrincipal);
            }
        }
    }

    /** 单行失败：携带已建实例 id（有的话）供报告留痕；异常本身让事务回滚。 */
    public static class RowFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String reason;
        private final Long instanceId;

        public RowFailure(String reason, Long instanceId) {
            super(reason);
            this.reason = reason;
            this.instanceId = instanceId;
        }

        public String reason() {
            return reason;
        }

        public Long instanceId() {
            return instanceId;
        }
    }

    /**
     * 审批人解析：域负责人首个 → 否则 PLATFORM_ADMIN 里 id 最小者 → 都没有则 null（NO_APPROVER）。
     *
     * <p>返回 {@code (userId, source)} 而非裸 id：{@code approverSource}/{@code FALLBACK_APPROVER}
     * 必须与解析同源，再解析一次就可能分叉。目录只走 {@link ApproverDirectory}——
     * 自己写 {@code owner_user_id} SQL 会绕开启用状态/组织可见性规则。</p>
     */
    private Initiated resolveInitiator(long tenantId, Long domainId) {
        if (domainId != null) {
            List<Long> owners = directory.domainOwners(tenantId, List.of(domainId));
            if (owners != null) {
                for (Long owner : owners) {
                    if (owner != null) {
                        return new Initiated(owner, WbsBackfillViews.SOURCE_DOMAIN_OWNER);
                    }
                }
            }
        }
        List<Long> admins = directory.activeUsersByRole(tenantId, FALLBACK_ROLE);
        if (admins != null) {
            Optional<Long> first = admins.stream().filter(java.util.Objects::nonNull)
                    .min(Comparator.naturalOrder());
            if (first.isPresent()) {
                return new Initiated(first.get(), WbsBackfillViews.SOURCE_PLATFORM_ADMIN);
            }
        }
        return null;
    }

    /**
     * 该实例真实的待办审批人：优先解析出的本人，否则 id 最小的一个。
     *
     * <p>排序只为让同一实例的重复运行挑同一个人；不复制演示夹具的硬编码回退。</p>
     */
    private Optional<ApprovalTask> pendingTask(long instanceId, long preferred) {
        List<ApprovalTask> pending = store.tasks(instanceId).stream()
                .filter(task -> task.isPending() && task.getApproverId() != null)
                .sorted(Comparator.comparing(ApprovalTask::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        for (ApprovalTask task : pending) {
            if (task.getApproverId() != null && task.getApproverId() == preferred) {
                return Optional.of(task);
            }
        }
        return Optional.of(pending.get(0));
    }

    /** SUBMIT 快照：人天 + 说明 + 来源 + 批次三键（TECH §3.7 靠它反查受影响范围）。 */
    private static Map<String, Object> attributes(BigDecimal days, String batchId, long requirementId,
                                                  String approverSource) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("estimateDays", days);
        attributes.put("note", note(batchId));
        attributes.put("source", "BACKFILL");
        attributes.put("batchId", batchId);
        attributes.put("backfillRunId", batchId + "#" + requirementId);
        attributes.put("approverSource", approverSource);
        return attributes;
    }

    /** 单据说明与批准意见用同一句，批次因此可反查（{@code detail LIKE '%存量回填（wbsbf-%'}）。 */
    static String note(String batchId) {
        return "存量回填（" + batchId + "）";
    }

    private static WbsBackfillViews.Row row(RequirementEntity entity,
                                            WbsBackfillViews.Candidate candidate,
                                            String estimateStatus, String result, String reason,
                                            Long instanceId, Long approverId, String approverSource,
                                            List<String> flags) {
        return new WbsBackfillViews.Row(entity.getCode(), entity.getId(), entity.getType(),
                entity.getStatus(), entity.getEstimateDays(), result, reason, instanceId,
                estimateStatus, approverId, approverSource, flags);
    }

    /** 引擎异常 → 报告原因码（脚本只认 §3.6 的那组字符串）。 */
    private static String reasonOf(RuntimeException ex) {
        if (ex instanceof ApprovalException approval) {
            return switch (approval.code()) {
                case ApprovalException.NO_WORKFLOW -> WbsBackfillViews.REASON_NO_WORKFLOW_DEFINITION;
                case ApprovalException.ILLEGAL_TRANSITION, ApprovalException.TASK_ALREADY_HANDLED ->
                        WbsBackfillViews.REASON_CONCURRENT_ACTIVE;
                case ApprovalException.APPROVER_UNRESOLVED -> WbsBackfillViews.REASON_NO_APPROVER;
                default -> approval.code();
            };
        }
        if (ex instanceof ApiException api) {
            return api.code();
        }
        return WbsBackfillViews.REASON_ROW_FAILED;
    }

    private static DataScope scope(long tenantId, long userId) {
        return DataScope.of(tenantId, ScopeTier.TENANT, Set.of(), null, userId, Set.of());
    }

    /** 解析结果与来源必须成对出现，避免二次解析分叉。 */
    private record Initiated(long userId, String source) {
    }
}
