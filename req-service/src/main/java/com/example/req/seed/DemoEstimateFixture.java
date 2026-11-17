package com.example.req.seed;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalStore;
import com.example.req.approval.ApprovalTask;
import com.example.req.approval.StartApproval;
import com.example.req.approval.WorkflowVersion;
import com.example.req.sys.SysDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示夹具：给 11 条非 BUS 需求各造一张 <b>已通过</b>的工时评估单（r26）。
 *
 * <p>为什么必须走审批引擎而不是直接写 {@code wf_instance}：评估单通过的回调会写需求人天
 * （{@link com.example.req.requirement.RequirementService#applyApprovedEstimate}），
 * 走引擎才能同时留下 SUBMIT/APPROVE 两条流水、SUBMIT 快照里的单据人天与说明，
 * 以及审批中心待办/历史的真实形状——手写三张表只能造出「看起来像」的数据。</p>
 *
 * <p>为什么不在 {@link DemoResetService} 的事务里做：JDBC 重建与 JPA 引擎写入不能同事务
 * （未 flush 会让 {@code counts()} 看不到刚插入的行），所以由 {@code reset()} 编排成
 * Tx1（业务重建）+ Tx2（本类 {@link #seedDemo()}）两段。</p>
 *
 * <p>为什么在 seed 包内切身份：引擎的 {@code assertAssignee} 要求「当前用户 == 任务审批人」，
 * 这是 AC-16「无权限者审批 403」的同一道校验。<b>不给引擎加代批语义</b>——那会把校验放宽成
 * 演示专用后门，负向用例随即失效。这里通过 {@link TenantContext#set(DataScope)} 把线程身份
 * 切成发起人 / 审批人，用完即还。</p>
 *
 * <p>顺序约束：批准的瞬间会写需求人天并刷新父需求汇总，因此<b>先批子（RD）再批父（PDR）</b>，
 * 且单据人天取「批准那一刻的需求人天」。反过来会让人天快照与派生后的汇总打架，
 * 直接派生 STALE，演示卡片变成「已失效」。</p>
 */
@Service
public class DemoEstimateFixture {

    private static final Logger log = LoggerFactory.getLogger(DemoEstimateFixture.class);

    /** 单据说明：演示数据统一用一句话，前端「说明」行有内容可看。 */
    public static final String NOTE = "演示夹具：工时评估初审通过";

    /** 夹具只覆盖演示需求（{@link DemoFixture#REQUIREMENT_CODE_PREFIX}），种子里的历史需求不碰。 */
    private static final String SELECT_REQUIREMENTS =
            "SELECT r.id, r.code, r.type, r.estimate_days, r.domain_id, d.owner_user_id"
                    + " FROM requirement r"
                    + " LEFT JOIN biz_domain d"
                    + "   ON d.id = r.domain_id AND d.tenant_id = r.tenant_id"
                    + " WHERE r.tenant_id = ? AND r.code LIKE ?"
                    + "   AND r.type IN ('PDR', 'RD') AND r.status <> 'CANCELLED'"
                    + " ORDER BY CASE r.type WHEN 'RD' THEN 0 ELSE 1 END, r.id";

    private final SysDb db;
    private final ApprovalEngine engine;
    private final ApprovalStore store;

    @Value("${platform.tenant:t1}")
    private String tenantCode;

    public DemoEstimateFixture(SysDb db, ApprovalEngine engine, ApprovalStore store) {
        this.db = db;
        this.engine = engine;
        this.store = store;
    }

    /**
     * 给每条演示需求建一张评估单并以「域负责人」身份批准；返回建单张数。
     *
     * <p>事务由调用方（{@link DemoResetService#reset()} 的 Tx2）提供，这里再声明一次
     * {@code @Transactional} 是为了单独调用时也有事务；REQUIRED 传播下两者是同一个事务。</p>
     */
    @Transactional
    public int seedDemo() {
        long tenantId = tenantId();
        List<Object[]> rows = db.rows(SELECT_REQUIREMENTS, tenantId,
                DemoFixture.REQUIREMENT_CODE_PREFIX + "%");
        DataScope previousScope = TenantContext.scopeOrNull();
        TenantContext.Principal previousPrincipal = TenantContext.principal();
        int approved = 0;
        try {
            for (Object[] row : rows) {
                long requirementId = SysDb.asLong(row[0]);
                String code = SysDb.asString(row[1]);
                String type = SysDb.asString(row[2]);
                BigDecimal days = days(row[3]);
                Long domainId = SysDb.asNullableLong(row[4]);
                Long ownerId = SysDb.asNullableLong(row[5]);
                long initiator = ownerId == null ? userIdOf(tenantId, "pm") : ownerId;

                TenantContext.set(scope(tenantId, initiator));
                long instanceId = engine.start(new StartApproval(
                        WorkflowVersion.BIZ_WBS_ESTIMATE, requirementId, type, domainId,
                        attributes(days), null, null)).id();

                ApprovalTask task = pendingTask(instanceId, ownerId)
                        .orElseThrow(() -> new ApiException(409, "DEMO_RESET_INVARIANT",
                                "评估单没有待办任务：" + code, Map.of("requirementId", requirementId)));
                TenantContext.set(scope(tenantId, task.getApproverId()));
                engine.approve(task.getId(), NOTE);
                approved++;
            }
        } finally {
            TenantContext.clear();
            if (previousScope != null) {
                TenantContext.set(previousScope);
            }
            if (previousPrincipal != null) {
                TenantContext.setPrincipal(previousPrincipal);
            }
        }
        log.info("demo-reset: 建并批准工时评估单 {} 张", approved);
        return approved;
    }

    /** 三个不变量当场数（wbs 评估单全部落在 wf_instance，不新增表）。 */
    public Map<String, Object> counts() {
        long tenantId = tenantId();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("estimateApproved", db.count(
                "SELECT COUNT(*) FROM wf_instance WHERE tenant_id = ? AND biz_type = ? AND status = ?",
                tenantId, WorkflowVersion.BIZ_WBS_ESTIMATE, ApprovalInstance.STATUS_APPROVED));
        counts.put("estimatePending", db.count(
                "SELECT COUNT(*) FROM wf_instance WHERE tenant_id = ? AND biz_type = ? AND status = ?",
                tenantId, WorkflowVersion.BIZ_WBS_ESTIMATE, ApprovalInstance.STATUS_PENDING));
        counts.put("estimateRequirements", db.count(
                "SELECT COUNT(DISTINCT biz_id) FROM wf_instance"
                        + " WHERE tenant_id = ? AND biz_type = ? AND status = ?",
                tenantId, WorkflowVersion.BIZ_WBS_ESTIMATE, ApprovalInstance.STATUS_APPROVED));
        return counts;
    }

    private Optional<ApprovalTask> pendingTask(long instanceId, Long preferredApprover) {
        List<ApprovalTask> pending = store.tasks(instanceId).stream()
                .filter(ApprovalTask::isPending)
                .toList();
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        if (preferredApprover != null) {
            for (ApprovalTask task : pending) {
                if (preferredApprover.equals(task.getApproverId())) {
                    return Optional.of(task);
                }
            }
        }
        return Optional.of(pending.get(0));
    }

    private Map<String, Object> attributes(BigDecimal days) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("estimateDays", days);
        attributes.put("note", NOTE);
        attributes.put("source", "DEMO");
        return attributes;
    }

    /** 单据人天取当前需求人天（未估过的行兜底 1 人天，仍满足「人天 > 0」）。 */
    private BigDecimal days(Object value) {
        BigDecimal days = value instanceof BigDecimal decimal ? decimal
                : value == null ? null : new BigDecimal(value.toString());
        if (days == null || days.signum() <= 0) {
            return BigDecimal.ONE;
        }
        return days;
    }

    private DataScope scope(long tenantId, long userId) {
        return DataScope.of(tenantId, ScopeTier.TENANT, Set.of(), null, userId, Set.of());
    }

    private long userIdOf(long tenantId, String username) {
        return db.row("SELECT id FROM sys_user WHERE tenant_id = ? AND username = ?", tenantId, username)
                .map(row -> SysDb.asLong(row[0]))
                .orElseThrow(() -> new ApiException(409, "DEMO_RESET_TENANT",
                        "账号 " + username + " 不存在：先执行 make platform-seed", Map.of()));
    }

    private long tenantId() {
        return db.row("SELECT id FROM sys_tenant WHERE code = ?", tenantCode)
                .map(row -> SysDb.asLong(row[0]))
                .orElseThrow(() -> new ApiException(409, "DEMO_RESET_TENANT",
                        "租户 " + tenantCode + " 不存在：先执行 make platform-seed", Map.of()));
    }
}
