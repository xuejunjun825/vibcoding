package com.example.req.seed;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.security.UserIdentity;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalStore;
import com.example.req.approval.ApprovalTask;
import com.example.req.approval.WorkflowVersion;
import com.example.req.requirement.RequirementDtos.DeliverRequest;
import com.example.req.requirement.RequirementService;
import com.example.req.sys.SysDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 演示夹具 Tx3：把 {@link DemoFixture} 里「业务提报已提交」的那条 BUS 真的走完两级审批，
 * 并把待测 RD 的交付事实（MR / 实际人天）写进库。
 *
 * <p>为什么要独立成类、而不是塞进 720 行的 {@link DemoResetService}：它跑的是 JPA + 审批引擎，
 * 必须与 Tx1 的裸 JDBC 分行事务（{@link DemoEstimateFixture} 已验证的范式）。独立成类之后
 * 「哪个事务里发生了什么」一眼可见，重置的单一入口仍然是 {@link DemoResetService#reset()}。</p>
 *
 * <p>两条边界，都是踩过的坑：</p>
 * <ul>
 *   <li>审批单必须走真实动作链：{@code submit → 会签 → review_start → 会签}，每一单都由
 *       {@code ApprovalEngine.approve} 按 assignee 校验落章。直插 {@code wf_task} 会得到
 *       「APPROVED 但流水/节点对不上」的假夹具，门禁一查流水就穿帮。</li>
 *   <li>MR 只能经 {@code RequirementService.deliver} 写：那条路径自带 {@code DELIVER} 审计
 *       （{@code after.mrUrl}），负向用例与发布单准入读的是同一份事实。</li>
 * </ul>
 */
@Service
public class DemoApprovalFixture {

    private static final Logger log = LoggerFactory.getLogger(DemoApprovalFixture.class);

    /** 讨论记录里的「r32 演示夹具」来源标记：审计与流水能一眼看出不是人点的。 */
    private static final String NOTE = "r32 演示夹具：真实审批链";

    /** 会签轮次上限（与门禁脚本的 approve_biz 同口径）：超过就是流程没收敛，不能静默返回。 */
    private static final int MAX_ROUNDS = 8;

    /** 演示交付：MR 地址与工时只在 {@link DemoFixture} 收口一次，文档/脚本都引用同一份。 */
    private static final List<String> DEMO_CODES = List.of("DEMO-R-01", "DEMO-R-04");

    private final SysDb db;
    private final ApprovalEngine engine;
    private final ApprovalStore store;
    private final RequirementService requirements;
    private final String tenantCode;

    public DemoApprovalFixture(SysDb db,
                               ApprovalEngine engine,
                               ApprovalStore store,
                               RequirementService requirements,
                               @Value("${platform.tenant:" + DemoFixture.TENANT_CODE + "}") String tenantCode) {
        this.db = db;
        this.engine = engine;
        this.store = store;
        this.requirements = requirements;
        this.tenantCode = tenantCode == null || tenantCode.isBlank()
                ? DemoFixture.TENANT_CODE : tenantCode.trim();
    }

    /**
     * 在 Tx1/Tx2 之后执行：R-01 走完提报 + 复核两级会签（净状态 POOLED），
     * R-04 经 deliver 写入 {@link DemoFixture#MR_URL} 与 {@link DemoFixture#ACTUAL_DAYS}。
     *
     * <p>失败一律抛 409 —— 调用方在 TransactionTemplate 里执行，异常让本段整体回滚，
     * 不会留下「审批到一半」的演示态。</p>
     */
    @Transactional
    public void seed() {
        long tenantId = tenantId();
        long requirement01 = requirementId(tenantId, DEMO_CODES.get(0));
        long requirement04 = requirementId(tenantId, DEMO_CODES.get(1));
        long notificationBaseline = notificationBaseline();
        DataScope previousScope = TenantContext.scopeOrNull();
        TenantContext.Principal previousPrincipal = TenantContext.principal();
        try {
            // 提报：po1 是 R-01 的 reporter（BUSINESS 档），走的是与页面完全相同的 actions 入口。
            actAs(identity(tenantId, "po1"));
            requirements.transition(requirement01, "submit", "r32 演示：业务提报");

            long submitInstance = activeInstance(tenantId, WorkflowVersion.BIZ_REQ_SUBMIT, requirement01);
            approveAll(submitInstance);

            // 复核：PRODUCT 角色持有 req:review，pm 是种子里的产品经理。
            actAs(identity(tenantId, "pm"));
            requirements.transition(requirement01, "review_start", "r32 演示：发起需求复核");

            long reviewInstance = activeInstance(tenantId, WorkflowVersion.BIZ_REQ_REVIEW, requirement01);
            approveAll(reviewInstance);

            // 交付：MR / 实际人天走专用入口（不是 actions），DELIVER 审计自带 after.mrUrl。
            actAs(identity(tenantId, "pm"));
            requirements.deliver(requirement04, new DeliverRequest(
                    DemoFixture.ACTUAL_DAYS, DemoFixture.ACTUAL_DAYS, DemoFixture.MR_URL, null));

            cleanupNotifications(notificationBaseline);
            assertSeeded(tenantId, requirement01, requirement04, submitInstance, reviewInstance);
            log.info("demo-reset: 审批夹具完成（R-01 两级会签 APPROVED、R-04 MR={}）", DemoFixture.MR_URL);
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

    /**
     * 把一张会签单批到空：每轮只处理「当时仍 PENDING」的任务，每单先切换到该单的审批人
     * （引擎按 assignee 校验，代批会被 409 挡住），再调用 {@code approve}。
     *
     * <p>必须循环而不是批一条：{@code REQ_SUBMIT}/{@code REQ_REVIEW} 在 {@code SeedService}
     * 里是按 PRODUCT 角色定义的会签节点，任务条数随账号基数变化；写死「批一条」在种子多一个
     * 产品经理的那天就会静默留下半张单。</p>
     */
    private void approveAll(long instanceId) {
        long tenantId = tenantId();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<ApprovalTask> pending = store.tasks(instanceId).stream()
                    .filter(ApprovalTask::isPending)
                    .toList();
            if (pending.isEmpty()) {
                return;
            }
            for (ApprovalTask task : pending) {
                actAs(identity(tenantId, task.getApproverId()));
                engine.approve(task.getId(), NOTE);
            }
        }
        throw new ApiException(409, "DEMO_RESET_INVARIANT",
                "审批实例 " + instanceId + " 超过 " + MAX_ROUNDS + " 轮仍未收敛", Map.of());
    }

    /** 后置断言：夹具自证，不满足就整体回滚（发现问题的时机必须早于演示，而不是演示中途）。 */
    private void assertSeeded(long tenantId, long requirement01, long requirement04,
                              long submitInstance, long reviewInstance) {
        Map<String, String> problems = new LinkedHashMap<>();
        String status01 = requirementStatus(tenantId, requirement01);
        if (!"POOLED".equals(status01)) {
            problems.put("R-01 净状态", "期望 POOLED，实际 " + status01);
        }
        expectInstance(problems, tenantId, "REQ_SUBMIT", submitInstance);
        expectInstance(problems, tenantId, "REQ_REVIEW", reviewInstance);
        long histories = db.count("SELECT COUNT(*) FROM wf_history WHERE tenant_id = ?"
                + " AND instance_id IN (?, ?)", tenantId, submitInstance, reviewInstance);
        if (histories < 4) {
            problems.put("审批流水", "期望 ≥ 4 条（两单各 SUBMIT/APPROVE/NODE_APPROVED），实际 " + histories);
        }
        long nodeApproved = db.count("SELECT COUNT(*) FROM wf_history WHERE tenant_id = ?"
                + " AND instance_id IN (?, ?) AND action = 'NODE_APPROVED'",
                tenantId, submitInstance, reviewInstance);
        if (nodeApproved < 1) {
            problems.put("节点通过流水", "0 条 NODE_APPROVED（会签没有真正落章）");
        }
        Object[] r04 = db.row("SELECT status, mr_url, actual_days FROM requirement"
                + " WHERE tenant_id = ? AND id = ?", tenantId, requirement04).orElse(null);
        if (r04 == null) {
            problems.put("R-04", "行不存在");
        } else {
            String status04 = SysDb.asString(r04[0]);
            String mrUrl = SysDb.asString(r04[1]);
            BigDecimal actualDays = decimal(r04[2]);
            if (!"PENDING_TEST".equals(status04)) {
                problems.put("R-04 状态", "期望 PENDING_TEST，实际 " + status04);
            }
            if (!DemoFixture.MR_URL.equals(mrUrl)) {
                problems.put("R-04 MR", "期望 " + DemoFixture.MR_URL + "，实际 " + mrUrl);
            }
            // 列是 DECIMAL(6,1)：按数值比，避免 "2.0" 与 "2.00" 这种标度差异把断言判红。
            if (actualDays == null || actualDays.compareTo(DemoFixture.ACTUAL_DAYS) != 0) {
                problems.put("R-04 实际人天", "期望 " + DemoFixture.ACTUAL_DAYS.toPlainString()
                        + "，实际 " + SysDb.asString(r04[2]));
            }
        }
        long deliverAudits = db.count("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ?"
                + " AND biz_type = 'REQUIREMENT' AND biz_id = ? AND action = 'DELIVER'", tenantId, requirement04);
        if (deliverAudits == 0) {
            problems.put("R-04 DELIVER 审计", "0 行（deliver 没走审计路径）");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(409, "DEMO_RESET_INVARIANT",
                    "审批/交付夹具后置断言失败（本段已回滚）：" + problems, Map.of());
        }
    }

    /** 单张审批单必须「已通过 + 有定义版本 + 无待办 + 审批人齐全」，否则夹具只是看起来像。 */
    private void expectInstance(Map<String, String> problems, long tenantId, String bizType, long instanceId) {
        Object[] row = db.row("SELECT status, definition_id FROM wf_instance"
                + " WHERE tenant_id = ? AND id = ?", tenantId, instanceId).orElse(null);
        if (row == null) {
            problems.put(bizType + " 审批单", "实例 " + instanceId + " 不存在");
            return;
        }
        if (!"APPROVED".equals(SysDb.asString(row[0]))) {
            problems.put(bizType + " 审批单", "期望 APPROVED，实际 " + SysDb.asString(row[0]));
        }
        if (SysDb.asNullableLong(row[1]) == null) {
            problems.put(bizType + " 定义版本", "definition_id 为空（引擎没解析到流程定义）");
        }
        long tasks = db.count("SELECT COUNT(*) FROM wf_task WHERE tenant_id = ? AND instance_id = ?",
                tenantId, instanceId);
        long pending = db.count("SELECT COUNT(*) FROM wf_task WHERE tenant_id = ? AND instance_id = ?"
                + " AND status = 'PENDING'", tenantId, instanceId);
        long unassigned = db.count("SELECT COUNT(*) FROM wf_task WHERE tenant_id = ? AND instance_id = ?"
                + " AND approver_id IS NULL", tenantId, instanceId);
        if (tasks == 0) {
            problems.put(bizType + " 待办", "0 条 wf_task（会签节点没解析出审批人）");
        }
        if (pending > 0) {
            problems.put(bizType + " 待办", "仍有 " + pending + " 条 PENDING");
        }
        if (unassigned > 0) {
            problems.put(bizType + " 审批人", unassigned + " 条任务 approver_id 为空");
        }
    }

    private long activeInstance(long tenantId, String bizType, long bizId) {
        return store.activeInstance(tenantId, bizType, bizId)
                .map(ApprovalInstance::getId)
                .orElseThrow(() -> new ApiException(409, "DEMO_RESET_INVARIANT",
                        bizType + " 没有在途实例（需求 " + bizId + "）", Map.of()));
    }

    // ---------------------------------------------------------------- 身份 / 数据

    /** 与登录同源：角色、域、厂商、组织子树都从库里读，scope 与 principal 一起还原。 */
    private UserIdentity identity(long tenantId, String username) {
        Object[] user = db.row("SELECT id FROM sys_user WHERE tenant_id = ? AND username = ?",
                        tenantId, username)
                .orElseThrow(() -> new ApiException(409, "DEMO_RESET_TENANT",
                        "账号 " + username + " 不存在：先执行 make platform-seed", Map.of()));
        return identity(tenantId, SysDb.asLong(user[0]));
    }

    private UserIdentity identity(long tenantId, long userId) {
        Object[] user = db.row("SELECT username, name, vendor_id, org_id FROM sys_user"
                        + " WHERE tenant_id = ? AND id = ?", tenantId, userId)
                .orElseThrow(() -> new ApiException(409, "DEMO_RESET_INVARIANT",
                        "审批人 " + userId + " 不存在", Map.of()));
        List<String> roles = db.scalars("SELECT r.code FROM sys_user_role ur"
                        + " JOIN sys_role r ON r.id = ur.role_id"
                        + " WHERE ur.tenant_id = ? AND ur.user_id = ? ORDER BY r.code", tenantId, userId)
                .stream().map(SysDb::asString).toList();
        Set<Long> domainIds = new LinkedHashSet<>();
        for (Object value : db.scalars("SELECT domain_id FROM sys_user_domain"
                + " WHERE tenant_id = ? AND user_id = ? ORDER BY domain_id", tenantId, userId)) {
            domainIds.add(SysDb.asLong(value));
        }
        return new UserIdentity(userId, tenantId, SysDb.asString(user[0]), SysDb.asString(user[1]),
                tenantCode, "演示租户", roles, domainIds, SysDb.asNullableLong(user[2]),
                orgSubtree(tenantId, SysDb.asNullableLong(user[3])));
    }

    private void actAs(UserIdentity identity) {
        TenantContext.set(identity.scope());
        TenantContext.setPrincipal(identity.principal());
    }

    private Set<Long> orgSubtree(long tenantId, Long orgId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (orgId == null) {
            return ids;
        }
        String path = db.row("SELECT path FROM sys_org WHERE tenant_id = ? AND id = ?", tenantId, orgId)
                .map(row -> SysDb.asString(row[0])).orElse(null);
        if (path == null) {
            return ids;
        }
        for (Object value : db.scalars("SELECT id FROM sys_org WHERE tenant_id = ? AND path LIKE ?",
                tenantId, path + "%")) {
            ids.add(SysDb.asLong(value));
        }
        return ids;
    }

    private long requirementId(long tenantId, String code) {
        return db.row("SELECT id FROM requirement WHERE tenant_id = ? AND code = ?", tenantId, code)
                .map(row -> SysDb.asLong(row[0]))
                .orElseThrow(() -> new ApiException(409, "DEMO_RESET_INVARIANT",
                        "演示需求 " + code + " 不存在：Tx1 未插入", Map.of()));
    }

    private String requirementStatus(long tenantId, long requirementId) {
        return db.row("SELECT status FROM requirement WHERE tenant_id = ? AND id = ?",
                        tenantId, requirementId)
                .map(row -> SysDb.asString(row[0])).orElse("MISSING");
    }

    /** 通知是按 id 自增落库的：记下边界，链上新增的（审批提醒）按 id > 边界删回 4 条口径。 */
    private long notificationBaseline() {
        return db.row("SELECT COALESCE(MAX(id), 0) FROM notification WHERE tenant_id = ?", tenantId())
                .map(row -> SysDb.asLong(row[0])).orElse(0L);
    }

    private void cleanupNotifications(long sinceId) {
        int removed = db.update("DELETE FROM notification WHERE tenant_id = ? AND id > ?",
                tenantId(), sinceId);
        if (removed > 0) {
            log.info("demo-reset: 清理审批链产生的 {} 条通知（id > {}）", removed, sinceId);
        }
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private long tenantId() {
        return db.row("SELECT id FROM sys_tenant WHERE code = ?", tenantCode)
                .map(row -> SysDb.asLong(row[0]))
                .orElseThrow(() -> new ApiException(409, "DEMO_RESET_TENANT",
                        "租户 " + tenantCode + " 不存在：先执行 make platform-seed", Map.of()));
    }
}
