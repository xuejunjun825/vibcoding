# r26 技术方案：工时评估初审（第五类审批单据 + 排期/开发前置门）

> 输入：`PRD.md`（18 条 AC）、`DESIGN.md`（§0 观感规格、动作行 pin）。分支 `feat/wbs-estimate-approval`，不新建分支。
> 结论一句话：**不新增表、不加列、不加迁移、不新增权限码/动作码/状态**——用审批引擎既有的 `wf_instance/wf_task/wf_history` 承载第五类单据，单据人天与说明放在引擎 `attributes` 快照里；门在 `RequirementService.transition` 里"读时派生"判定，前端把 `schedule`/`start_dev` 从"按 `allowedActions` 渲染"改成"渲染看权限、禁用看门"。

---

## 1. 架构判断

| 决定 | 理由 |
|---|---|
| 单据落在 `wf_instance`，`biz_type=WBS_ESTIMATE` | 审批引擎已提供发起/或签/驳回/时间线/回调/通知全套语义；另建 `req_estimate` 表等于复制一遍状态机，且 AC-6（审批中心可见）无法零成本达成。 |
| 提交的人天与说明进 `StartApproval.attributes`（→ `wf_history.detail_json.document`） | `StartApproval` 的 Javadoc 已明确 attributes 是"启动时快照"，引擎 `documentOf` 只在 SUBMIT 不存在时才回读单据——正好是"单据值不可被后续编辑污染"的语义；不动表结构。 |
| `STALE` 只派生不落库 | 人天有 `PUT /{id}`（`extra.estimateDays`）与 `POST /{id}/deliver` 两条写路径，落库就要拦全部写路径（含未来的第三条）；读时按当前 `requirement.estimate_days` 比对 `approvedDays` 不可能漏。 |
| 门放在 `transition` 而非控制器 | 判定顺序要求"403 → 409 ILLEGAL_TRANSITION → 既有门 → 409 ESTIMATE_NOT_APPROVED"，而这个顺序只在 `transition` 内部成立；放控制器会把门提到状态机之前。 |
| 前端新增 `views/req/estimate.ts` 纯函数模块 | 卡片门提示、动作行内联提示、按钮 `title` 三处必须逐字一致（AC-13 断言），复制字符串必然漂移；且**不能**改 `dict.ts`/`StatusPill`（裸枚举与跨页面回归风险）。 |
| 两个受门动作"渲染看权限、禁用看门" | r23 拆解门、r25 无权限按钮已经是同一交互形态；`allowedActions` 不含它们时按钮消失，用户只看到"按钮没了"，无法理解原因（PRD §8.3 已定）。 |

---

## 2. 数据模型与存储

**零 schema 变更**（`ddl-auto: validate` + Flyway V13 保持原样）：

| 承载 | 字段 | 说明 |
|---|---|---|
| `wf_instance` | `biz_type='WBS_ESTIMATE'`、`biz_id=requirement.id`、`req_type`（PDR/RD，引擎只用于路由）、`initiator_id`、`status`、`current_node`、`started_at/ended_at`、`active_flag` | 唯一键 `uk_wf_instance_active(tenant_id,biz_type,biz_id,active_flag)` 天然保证 `PENDING≤1`；`status` 列长 16，容纳新增的 `SUPERSEDED`。 |
| `wf_history` | SUBMIT 条目的 `detail_json.document = {estimateDays, note, reqType, domainId, source}`；APPROVE/REJECT 条目带 `comment` | 单据人天与说明的**唯一落点**；驳回原因取 REJECT 条目的 `comment`。 |
| `wf_task` | 或签节点的待办，`approver_id` = 域负责人 / 产品经理 | 审批人 id 从这里读，名字由前端字典解析（后端不返回 `approverName`）。 |
| `requirement.estimate_days` | 既有列 | 通过时写入 `approvedDays`（AC-3）；改动超 ±20% 即门失效（AC-10）。 |

记录态只有 `PENDING / APPROVED / REJECTED / SUPERSEDED`（`WITHDRAWN` 视同 `NONE`），`STALE` 是读取时派生的第六态。

**新增常量**（仅两处）：

```java
// req-service/.../approval/WorkflowVersion.java
public static final String BIZ_WBS_ESTIMATE = "WBS_ESTIMATE";

// req-service/.../approval/ApprovalInstance.java
public static final String STATUS_SUPERSEDED = "SUPERSEDED";
```

**状态派生**（`WbsEstimateService.statusOf`，唯一真相）：

```
PENDING  = 存在 active_flag=1 的 WBS_ESTIMATE 实例
否则取 id 最大的历史实例 last：
  last == null            → NONE
  last.status == REJECTED → REJECTED
  last.status == SUPERSEDED → SUPERSEDED
  last.status == WITHDRAWN  → NONE
  last.status == APPROVED:
      approved = document.approvedDays(或 estimateDays 快照)
      cur      = requirement.estimateDays
      approved == null || approved <= 0 → STALE
      cur == null                       → STALE
      |cur - approved| > approved × 0.20 → STALE      // 严格大于，BigDecimal 比较
      否则                                → APPROVED
```

边界：`approved=10` → `cur=12.0` 偏差 `0.20`，`compareTo` 相等 **不放行门**（放行）；`cur=12.5` → `0.25 > 0.20` → `STALE`。

---

## 3. 后端设计

### 3.1 新增/改动文件

| 文件 | 改动 |
|---|---|
| `req-service/.../approval/WorkflowVersion.java` | +`BIZ_WBS_ESTIMATE` |
| `req-service/.../approval/ApprovalInstance.java` | +`STATUS_SUPERSEDED` |
| `req-service/.../approval/DocumentSummaryProvider.java` | +`default Map<String,Object> summary(ApprovalInstance instance, long viewerId)`（默认委托旧三参方法，四个既有实现零改动） |
| `req-service/.../approval/ApprovalCenterService.java` | `summary(instance, viewerId)` 改调实例感知的重载 |
| `req-service/.../requirement/WbsEstimateService.java` | **新**：提交、唯一性/重提、状态派生、门判定、视图 |
| `req-service/.../requirement/WbsEstimateViews.java` | **新**：`EstimateView` record |
| `req-service/.../requirement/EstimateSnapshot.java` | **新**：SUBMIT 快照解析（`document`/`days`/`note`），供 Service 与 Provider 共用 |
| `req-service/.../requirement/WbsEstimateSummaryProvider.java` | **新**：`bizType()=WBS_ESTIMATE` 的摘要/属性 |
| `req-service/.../requirement/WbsEstimateCallback.java` | **新**：通过/驳回/撤回回调 |
| `req-service/.../requirement/RequirementErrors.java` | +`estimateNotApproved` / `estimateAlreadyApproved` |
| `req-service/.../requirement/RequirementDtos.java` | +`SubmitEstimateRequest` |
| `req-service/.../requirement/RequirementController.java` | +`POST /{id}/estimate` |
| `req-service/.../requirement/RequirementService.java` | 门插点、`allowedActions` 门过滤、`detail` 带 `estimate`、两个回调辅助方法 |
| `req-service/.../requirement/RequirementViews.java` | `Detail` 增 `EstimateView estimate` |
| `req-service/.../seed/SeedService.java` | +第五类流程种子；`biz_domain.owner_user_id` 回填 |

### 3.2 公共契约（下游逐字调用）

```java
// requirement/WbsEstimateService.java
@Component
public class WbsEstimateService {
    public static final String BIZ_TYPE = "WBS_ESTIMATE";
    public static final BigDecimal STALE_THRESHOLD = new BigDecimal("0.20");

    /** 提交（重提）：校验 → 已通过且人天未变则 409 → 作废旧 PENDING → engine.start。 */
    public WbsEstimateViews.EstimateView submit(long requirementId, BigDecimal estimateDays, String note);

    /** 详情页 estimate 块；BUS 返回 null（调用方判空）。 */
    public WbsEstimateViews.EstimateView view(RequirementEntity entity);

    /** 六态之一：NONE|PENDING|APPROVED|REJECTED|SUPERSEDED|STALE。 */
    public String statusOf(RequirementEntity entity);

    /** 该 (type, action) 是否受门约束：PDR+schedule、RD+start_dev。 */
    public boolean gated(String type, String action);

    /** 门未满足抛 409 ESTIMATE_NOT_APPROVED（detail 三键）；满足直接返回。 */
    public void ensureGate(RequirementEntity entity, String action);

    /** 从 allowedActions 里摘掉被门挡住的受门动作（空白列表返回原集合语义）。 */
    public List<String> applyGate(RequirementEntity entity, List<String> actions);
}

// requirement/WbsEstimateViews.java
public record EstimateView(String status, Long instanceId, String instanceStatus,
                           BigDecimal proposedDays, BigDecimal approvedDays, String note,
                           String reason, Long approverId,
                           LocalDateTime submittedAt, LocalDateTime decidedAt,
                           String required) {
    public static EstimateView none();   // status=NONE, required="ESTIMATE_APPROVED", 其余 null
}

// requirement/EstimateSnapshot.java
final class EstimateSnapshot {
    static Map<String, Object> document(ApprovalStore store, WorkflowJson json, long instanceId);
    static BigDecimal days(Map<String, Object> document);   // estimateDays 优先，approvedDays 兜底
    static String note(Map<String, Object> document);
}

// requirement/WbsEstimateSummaryProvider.java
@Component
public class WbsEstimateSummaryProvider extends RequirementDocumentSummary {
    public String bizType();                                            // "WBS_ESTIMATE"
    public Map<String, Object> summary(long tenantId, long bizId, long viewerId);
    public Map<String, Object> summary(ApprovalInstance instance, long viewerId);  // 带单据人天/说明/结论
}

// requirement/WbsEstimateCallback.java
@Component
public class WbsEstimateCallback implements ApprovalCallback {
    public String bizType();
    public void onApproved(long bizId, long instanceId);
    public void onRejected(long bizId, long instanceId, String reason);
    public void onWithdrawn(long bizId, long instanceId);
}

// requirement/RequirementService.java（新增公共方法，供回调复用）
public void applyApprovedEstimate(long requirementId, BigDecimal approvedDays, long instanceId);
public void recordEstimateEvent(long requirementId, String action, Map<String, Object> detail);

// requirement/RequirementErrors.java
public static ApiException estimateNotApproved(long requirementId, String status);
public static ApiException estimateAlreadyApproved(long requirementId, Long instanceId);
```

### 3.3 API 设计

**`POST /api/req/requirements/{id}/estimate`** → `201`

```json
{ "estimateDays": 8.5, "note": "含联调 0.5 天" }
```

```json
{ "instanceId": 1042, "status": "PENDING",
  "estimate": { "status": "PENDING", "instanceId": 1042, "instanceStatus": "PENDING",
                "proposedDays": 8.5, "approvedDays": null, "note": "含联调 0.5 天",
                "reason": null, "approverId": null,
                "submittedAt": "2026-11-17T10:12:03", "decidedAt": null,
                "required": "ESTIMATE_APPROVED" } }
```

失败矩阵（顺序即断言顺序）：

| 条件 | 状态码 | code | detail |
|---|---|---|---|
| 需求不存在 / 越权数据面 | 404 / 403 | `NOT_FOUND` / `OUT_OF_SCOPE` | 沿用既有 |
| 无 `req:deliver`（F4 复用发起码） | 403 | `FORBIDDEN` | `{required:"req:deliver"}` |
| 类型 BUS | 400 | `VALIDATION_FAILED` | `{field:"type"}` |
| 终态（`stateMachine.isTerminal`） | 400 | `VALIDATION_FAILED` | `{field:"status"}` |
| `estimateDays` 缺失/≤0 | 400 | `VALIDATION_FAILED` | `{field:"estimateDays"}` |
| 非 0.5 天精度 | 400 | `INVALID_DAY_PRECISION` | 沿用 `RequirementRules.checkHalfDay(days,"estimateDays")` |
| 说明 > 500 字 | 400 | `VALIDATION_FAILED` | `{field:"note"}` |
| 请求体未知字段 | 400 | `VALIDATION_FAILED` | `{field:"<name>"}` |
| 已 `APPROVED` 且人天未变 | 409 | `ESTIMATE_ALREADY_APPROVED` | `{instanceId}` |
| 并发双提交（唯一键兜底） | 409 | `ILLEGAL_TRANSITION` | `{instanceId}`（引擎 `activeInstance` 判定） |

**`GET /api/req/requirements/{id}`** 的 `Detail` 增 `estimate` 块（`BUS` 为 `null`，`NONE` 是完整对象而非 `null`——AC-13 的卡片要在"未评估"时也渲染）。

**门失败**：`409 ESTIMATE_NOT_APPROVED`，`detail = {requirementId, status, required:"ESTIMATE_APPROVED"}`，`status` 即六态之一；**不落库、不写 `STATE_CHANGE` 审计、不发通知**。

### 3.4 提交与唯一性（`WbsEstimateService.submit`）

```
1. entity = requirementService.requireWritable(id)                  // 404/403/终态 400
2. requireFunction("req:deliver")                                    // 403
3. type != BUS 且 !isTerminal(type,status)                           // 400
4. days > 0 && RequirementRules.checkHalfDay(days,"estimateDays")    // 400
5. note 去空白后 > 500 → 400
6. approved = lastApprovedInstance(id)
   if (approved != null && days.compareTo(EstimateSnapshot.days(approved)) == 0)
        → 409 ESTIMATE_ALREADY_APPROVED
   if (approved != null && 偏差 ≤ 20%) → 409 ESTIMATE_ALREADY_APPROVED（人天未变或仍在时效内）
   // 偏差 > 20%：允许新建，旧单保持 APPROVED 供追溯
7. 旧 PENDING：instance.finish(STATUS_SUPERSEDED, now) + 取消其待办（store.saveInstance/saveTask）
8. engine.start(new StartApproval(BIZ_TYPE, id, entity.getType(), entity.getDomainId(),
        Map.of("estimateDays", days, "note", note, "source", "UI"), null, null))
9. auditService.record("ESTIMATE_SUBMIT", "REQUIREMENT", id,
        {instanceId, estimateDays, note})                            // 需求动态一条
10. 返回 view(id)
```

第 7 步的 `finish` 之后 `active_flag` 置空，第 8 步的 `activeInstance` 预检必然通过，`uk_wf_instance_active` 只作为并发兜底。

### 3.5 回调（AC-3 / AC-4 / 撤回）

```java
onApproved(id, instanceId):
    applyApprovedEstimate(id, days, instanceId)   // 写 estimate_days + refreshParentRollup
    recordEstimateEvent(id, "ESTIMATE_APPROVED", {instanceId, estimateDays})
    supersedeOtherPending(id, instanceId)         // 通过时把其它 PENDING 置 SUPERSEDED（正常只有一张）
onRejected(id, instanceId, reason):
    recordEstimateEvent(id, "ESTIMATE_REJECTED", {instanceId, reason})
onWithdrawn(id, instanceId):
    recordEstimateEvent(id, "ESTIMATE_WITHDRAWN", {instanceId})
```

`applyApprovedEstimate` / `recordEstimateEvent` 都走 `RequirementService` 内部（`@Transactional` 已由引擎事务包裹），审计 `biz_type="REQUIREMENT"`、`biz_id=需求 id`——「需求动态」读的就是这张审计表，前端无需新增接口。

### 3.6 门（AC-7/8/9/10/11/12）

`RequirementService.transition` 插点（**唯一一处**，在 `review_pass` 反向守卫与 RD `GatePort` 之后、`target == "APPROVING"` 之前）：

```java
if (estimateGate.gated(entity.getType(), action)) {
    estimateGate.ensureGate(entity, action);     // 409 ESTIMATE_NOT_APPROVED
}
```

完整顺序（断言顺序，AC-12）：

```
TenantContext.require → requireWritable(403) → authorize(403, source=UI)
→ stateMachine.target(409 ILLEGAL_TRANSITION) → review_pass 反向守卫
→ r23 父门/GatePort(409 既有码) → estimateGate(409 ESTIMATE_NOT_APPROVED)
→ 落库 + STATE_CHANGE 审计 + 通知
```

`allowedActions` 门过滤：保持 `allowedActions(type,status,reporterId)`（状态机 ∩ 矩阵）不变，新增 `estimateGate.applyGate(entity, list)` 在**三个**返回值位置调用——`toView(entity, rollup)`、`detail()`、`transition()` 的 `TransitionResult`。语义：`status != APPROVED` 时摘掉 `schedule`(PDR) / `start_dev`(RD)，其余动作零影响。列表行按行判定（每行一次 `findFirst...`，与既有 `toView` 里逐行查父编码的成本同级）。

> 口径边界（写进 memory）：`allowedActions` 是"接口会不会放行"的真相；前端**不**用它决定渲染，只用 `canAction` + `estimate.status`。

### 3.7 种子与摘要

`SeedService`（在既有四个流程之后追加，幂等靠 `ensure` 的 `(tenant,biz_type,req_type,version)`）：

```java
// WBS_ESTIMATE / ANY：域负责人或产品经理，或签
String apprEstimate = "{\"schemaVersion\":1,\"start\":\"n1\",\"nodes\":["
    + "{\"key\":\"n1\",\"type\":\"APPROVAL\",\"name\":\"工时评估初审\",\"mode\":\"ANY\","
    + "\"approvers\":[{\"type\":\"ROLE\",\"value\":\"PRODUCT\"},"
    + "{\"type\":\"DOMAIN_OWNER\",\"value\":\"\"}],\"next\":\"end\"}]}";
seedWorkflow(tenantId, "WBS_ESTIMATE", "ANY", "工时评估初审（域负责人或产品经理）", apprEstimate, users.get("admin"), now);
```

**必须同时回填域负责人**：现有种子建 `biz_domain` 时没有 `owner_user_id`，`ApproverResolver.TYPE_DOMAIN_OWNER` 会解析为空 → 或签节点只剩 PRODUCT，AC-15 的"审批人=该域负责人"不成立。加一行幂等回填（只补空值）：

```java
jdbc.update("UPDATE biz_domain SET owner_user_id = ? WHERE tenant_id = ? AND owner_user_id IS NULL",
        users.get("pm"), tenantId);
```

`WbsEstimateSummaryProvider`：`attributes()` 直接继承需求基类（路由用 `priority/reqType/domainId`）；`summary()` 在基类字段之上叠加单据字段（`estimateStatus / proposedDays / approvedDays / estimateNote`），并重写实例感知的 `summary(instance, viewerId)` 读 SUBMIT 快照——审批人看到的是**提交时**的人天，不是需求当前值。未注册时审批中心降级为空摘要的行为不变。

### 3.8 演示夹具（AC-15，引擎路径）

`DemoResetService` 现在是单事务 JDBC 重建。评估单必须走引擎（`ApprovalEngine.start` 解析定义/节点/审批人，`approve` 触发回调写需求人天与动态），而 JPA 写入在 JDBC `counts()` 里看不到（未 flush / 不同连接）——所以**两段事务**：

```java
// DemoResetService：@Transactional 挪到 resetBusiness()，reset() 变成编排入口
public ResetResult reset() {
    ResetResult business = self.resetBusiness();        // Tx1：删表 + 重建 12 需求小链路 + 既有 66 条不变量
    int seeded = estimateFixture.seedDemo();            // Tx2：引擎路径 11 张 APPROVED
    return business.withEstimates(estimateFixture.counts(), seeded);   // 3 条新不变量
}
```

```java
// seed/DemoEstimateFixture.java（新）
@Service
public class DemoEstimateFixture {
    @Transactional
    public int seedDemo();                  // 11 条：DEMO-R-02..12
    public Map<String, Object> counts();    // {wbsInstances:11, wbsPending:0, requirementsWithEstimate:11}
}
```

每条的做法：

1. 以 `admin` 身份起单：`engine.start(new StartApproval(BIZ_TYPE, reqId, type, domainId,
   Map.of("estimateDays", 需求当前人天, "note", "演示夹具：工时评估"), null, 需求负责人))`（`initiatorId` 显式传负责人，满足"发起人=需求负责人"）。
2. 以**审批人身份**收口：`asUser(approverId, () -> engine.approve(taskId, "演示夹具：初审通过"))`，其中

```java
private void asUser(long userId, Runnable body) {
    DataScope prev = TenantContext.scopeOrNull();
    TenantContext.set(DataScope.of(tenantId, ScopeTier.TENANT, Set.of(), null, userId, Set.of()));
    try { body.run(); } finally { if (prev != null) TenantContext.set(prev); }
}
```

`ApprovalEngine.assertAssignee` 没有"管理员代批"（`不符合就 403`），所以只能用身份切换，不能新增代批口子——加代批会直接破坏 AC-16 的"无权限者审批 403"。切换只在 seed 包内、带 `finally` 复位，且不设置 `Principal`（矩阵回落到 scope 身份）。
3. 审批人取该域的 `biz_domain.owner_user_id`（pm），审批时间取 `now.minusDays(19)` / `now.minusDays(20)`，早于计划开始。

`counts()` 的三条新断言并入 reset 的不变量自检（`demo-reset` 断言 66 → 69）。幂等：`DELETE_ORDER` 已含 `wf_instance`，重跑先删后建，逐键一致。

---

## 4. 前端设计

### 4.1 文件

| 文件 | 改动 |
|---|---|
| `web/src/views/req/estimate.ts` | **新**：六态 META、`estimateGateTip`、偏差、CTA 文案、`gateActionOfType` |
| `web/src/api/types.ts` | +`EstimateBlock`、`RequirementDetail.estimate` |
| `web/src/api/req.ts` | +`submitEstimate(id, payload)` |
| `web/src/views/req/RequirementDetailView.vue` | 动作行 pin + 评估单加载 + 409 就地刷新 + 传给两个 tab |
| `web/src/views/req/tabs/OverviewTab.vue` | 概览顶部整宽评估卡片 + 520 提交弹窗 |
| `web/src/views/req/tabs/ApprovalTab.vue` | 顶部只读评估块（加载/失败/空三态） |
| `web/src/views/approval/ApprovalCenterView.vue` | 标签、筛选项、`bizKeyOf` 显式分支 |

不动：`StatusPill`、`stores/dict.ts`、`styles/tokens.css`、`ACTION_META`、`usePermission`。

### 4.2 `estimate.ts`（文案唯一来源）

```ts
export type EstimateStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED' | 'STALE'

export const ESTIMATE_META: Record<EstimateStatus, { label: string; tone: string; gateWord: string }> = {
  NONE:       { label: '未评估',   tone: 'muted',   gateWord: '无' },
  PENDING:    { label: '审批中',   tone: 'info',    gateWord: '待审核' },
  APPROVED:   { label: '已通过',   tone: 'success', gateWord: '已通过' },
  REJECTED:   { label: '已驳回',   tone: 'danger',  gateWord: '已驳回' },
  SUPERSEDED: { label: '已作废',   tone: 'muted',   gateWord: '已作废' },
  STALE:      { label: '已失效',   tone: 'warning', gateWord: '已失效' },
}

export function estimateStatusOf(block?: EstimateBlock | null): EstimateStatus
export function estimateGateTip(status: EstimateStatus): string
// APPROVED → ''；其余 → `需先通过工时评估（当前评估单：${gateWord}）`
export function estimateDeviationPercent(current?: number | null, approved?: number | null): number | null
export function gateActionOfType(type?: string | null): 'schedule' | 'start_dev' | null
export const ESTIMATE_CTA_LABEL = { submit: '提交评估', resubmit: '重新提交评估', view: '查看评估单' } as const
```

卡片门提示、动作行内联提示、按钮 `title` 全部调用 `estimateGateTip`，**禁止**在模板里拼字符串（AC-13 逐字一致靠这一点）。

### 4.3 动作行（`RequirementDetailView.vue`）

```ts
const estimate = computed(() => detail.value?.estimate ?? null)
const estimateStatus = computed(() => estimateStatusOf(estimate.value))
const gateOpen = computed(() => estimateStatus.value === 'APPROVED')
const gateAction = computed(() => gateActionOfType(detail.value?.type))     // PDR→schedule, RD→start_dev

const pinnedGateAction = computed(() => {
  const action = gateAction.value
  if (!action) return null
  if (headerActions.value.includes(action)) return null          // 门已过且在 allowedActions → 走常规按钮
  const permitted = canAction(`req.action.${action}`)
  if (permitted && gateOpen.value) return null                   // 有权限 + 门已过 + 状态不允许 → 隐藏（第三类）
  return { action, permitted }
})

const gateActionTip = computed(() => {
  const pinned = pinnedGateAction.value
  if (!pinned) return ''
  return pinned.permitted
    ? estimateGateTip(estimateStatus.value)                      // 门未满足
    : `需要权限：req.action.${pinned.action}`                     // 权限不足（两个码不混用）
})
```

模板（插在常规动作按钮之后、无动作文案之前）：

```html
<el-button v-if="pinnedGateAction" class="btn--disabled-reason"
           :data-test="`action-${pinnedGateAction.action}`"
           :disabled="true" :title="gateActionTip"
           @click="onPinnedClick">
  {{ pinnedGateAction.permitted ? labelOf(pinnedGateAction.action) : labelOf(pinnedGateAction.action) }}
</el-button>
<span v-if="pinnedGateAction" class="detail__gate-hint" data-test="gate-hint">{{ gateActionTip }}</span>
```

`onPinnedClick` 首行 `return`（`disabled` 已挡点击，handler 兜底保证零请求零 URL 变化）。禁用样式沿用 r25：`#F2F3F5` 底 / `#A9AEB8` 字 / `#E5E6EB` 边、`opacity:1`、`cursor:not-allowed`，**不加** `pointer-events:none`（否则 `title` 不弹）。

`409 ESTIMATE_NOT_APPROVED` 落到既有 `handleActionError` 的 409 分支（常驻 `action-banner` + `refreshAll()`），错误码直接显示 `detail.status` 的文案在 banner 里补一句：`当前评估单：{{ gateWord }}`。

### 4.4 概览评估卡片（`OverviewTab.vue`）

- 位置：`.overview` 顶部、`.overview__grid` 之前，整宽 `.surface surface--pad`，`data-test="estimate-card"` `:data-status="estimateStatus"`。
- 结构：标题行（`v-if="estimate"`）= 状态 pill（`span.estimate-pill`，用 META 的 tone 上色，不复用 `StatusPill`）+ 右侧 CTA（`提交评估`/`重新提交评估` + `查看评估单`）；指标四列 = 当前人天（`detail.estimateDays`）/ 评估人天（`approvedDays ?? proposedDays`）/ 偏差（`estimateDeviationPercent`，1 位小数、整数去 `.0`）/ 审批人（`userName(approverId)`）；元信息行（提交人/时间、审批时间）；原因条（`REJECTED` 显 `reason`，`STALE` 显固定文案「人天相对评估值变动超过 20%，需重新评估」）；门提示行 `data-test="estimate-gate-tip"`。
- `NONE` 不是空态：字段用 `—` 占位，卡片照常渲染；`BUS` 整个卡片 `v-if` 不渲染。
- 提交弹窗（`el-dialog` width=520）：人天 `el-input-number :step="0.5" :min="0.5"`、说明 `el-input type=textarea maxlength=500 show-word-limit`；幂等提交 `submitting` 锁；成功 `emit('submitted')` 且 600ms `--flash-ok` 卡片闪光（`prefers-reduced-motion` 取消）。
- CTA 收敛（r25 规则）：`can('req:deliver')` 为 false 时仍渲染、`:disabled` + `:title="需要权限：req:deliver"` + handler 首行返回。
- 新增 props：`estimate: EstimateBlock | null`；新增 emit：`submitted`。

### 4.5 审批页签（`ApprovalTab.vue`）

新增 props：`estimate: EstimateBlock | null`、`estimateInstance: ApprovalInstanceDetail | null`、`estimateLoading: boolean`、`estimateError: string`。

只读块（在既有提报块**之上**）：状态、单据人天、说明、节点与时间线（复用 `ApprovalTimeline`）、审批人/时间；本块**不放**审批动作（审批统一在审批中心）。三态：`estimateLoading` → 骨架；`estimateError` → `ErrorState` + 重试；`estimateInstance == null` → `EmptyState`「该需求还没有工时评估单」。宽度按 UI-STYLE 单列，不横向滚动。

`RequirementDetailView.loadEstimateApproval()` 直接读 `detail.estimate.instanceId` 再 `getApprovalInstance(id)`——**不再**经过 `listApprovalMine()` 的 `bizType.includes('REQ')` 匹配（AC-6：`WBS_ESTIMATE` 不含 `REQ`，旧写法永远匹配不到）。既有 `loadApproval()`（提报单）保持原样。

### 4.6 审批中心

```ts
const BIZ_LABELS = { …, ESTIMATE: '工时评估' }
function bizKeyOf(rawType?: string): string {
  const raw = String(rawType ?? '').toUpperCase()
  if (raw === 'WBS_ESTIMATE') return 'ESTIMATE'      // 必须在 includes('REQ') 之前/独立分支
  …
}
```

下拉加 `<el-option label="工时评估" value="ESTIMATE" />`。筛选仍是当前页客户端过滤（与既有四类同口径，`ApprovalCenterService` 不加 `bizType` 参数，避免动引擎分页契约）；文档里写明该限制。

---

## 5. 关键实现点与边界

| 场景 | 行为 |
|---|---|
| 并发双提交 | 引擎 `activeInstance` 预检 + `uk_wf_instance_active` 唯一键 → 409 `ILLEGAL_TRANSITION`；不留半张单 |
| 重提竞态（旧单刚被通过） | 提交事务内先读再写，`PENDING` 不存在即跳过作废；已 `APPROVED` 且人天未变 → 409 |
| 通过时人天被第三方改过 | 回调以单据值覆盖需求人天（AC-3 优先单据），偏差在下一读取时自然归零 |
| 父需求（有子需求，人天=汇总） | 评估卡片照常渲染，但门只在 `PENDING_ANALYSIS/TODO` 行生效（父需求拿不到这两个动作）；不额外特判，避免假分支 |
| `PUT extra.estimateDays` / `POST deliver` 改人天 | 无需改代码：门与卡片都在读时派生，下一次读立刻 `STALE` |
| `estimate` 块缺失（老后端 / 请求失败） | 前端 `estimateStatusOf(undefined) = NONE` → 保守禁用 + 「无」文案，不误放行 |
| `WITHDRAWN` | 视同 `NONE`，不参与唯一性（`active_flag` 已释放） |
| PDR 的 `start_dev`（`SCHEDULED→IN_DEV`） | 按 PRD 只锁 `PDR+schedule` 与 `RD+start_dev`；PDR 的 `start_dev` 不受门约束（记入 §7 风险，待验收确认） |
| 权限文案的码 | 受门动作的授权由 r22 动作矩阵裁决，用 `canAction('req.action.schedule'/'req.action.start_dev')`，文案 `需要权限：req.action.schedule`；`ACTION_META.permissions`（`release:plan`）只作历史展示，不作判据 |

---

## 6. 测试策略

**新增单测（`mvn -o -pl req-service -am test`，≥18 例；AC-17 要求 ≥12）**

1. `WbsEstimateSubmitTest`（7 例）：正常 201 建单；BUS 400；终态 400；`0.5` 精度 400；说明 500 边界（500 通过 / 501 拒绝）；无 `req:deliver` 403；未知字段 400。
2. `WbsEstimateUniquenessTest`（4 例）：PENDING 重提 → 旧单 `SUPERSEDED` + 新单 `PENDING`；已 `APPROVED` 人天未变 → 409 `ESTIMATE_ALREADY_APPROVED`；已 `APPROVED` 偏差 > 20% → 允许新建且旧单保持 `APPROVED`；通过时其它 `PENDING` 置 `SUPERSEDED`。
3. `WbsEstimateGateTest`（12 组矩阵：`{NONE,PENDING,APPROVED,REJECTED,SUPERSEDED,STALE} × {PDR.schedule, RD.start_dev}`）：仅 `APPROVED` 放行，其余 409 且 `detail.status` 如实回报；外加 3 例顺序：403 先于门、`ILLEGAL_TRANSITION` 先于门、门失败不产生 `STATE_CHANGE` 审计。
4. `WbsEstimateStaleTest`（4 例）：`10→12.0` 放行、`10→12.5` 拦、`approved=null` → `STALE`、`deliver` 改人天后立即 `STALE`。
5. `WbsEstimateCallbackTest`（3 例）：通过写人天 + `ESTIMATE_APPROVED` 审计；驳回原因进审计；撤回不发 `APPROVED`。

风格沿用 `ApprovalEngineTest`（`InMemoryApprovalStore` / `FakeApproverDirectory` / `TestWorkflows`），不引 DB。

**脚本**

- `scripts/platform-verify.sh`：新建 `approve_estimate()`（提交 + 审批中心通过）并在现有 `schedule`/`start_dev` 调用前调用；新增 4 条正向断言（未评估 409 后被拦、通过后 200、卡片字段、审批中心可见）。
- `scripts/platform-test-negative.sh`：+4 条（未评估 `schedule` 409 / 未评估 `start_dev` 409 / 驳回后 `start_dev` 409 / 无权限者提交 403 / 无权限者审批 403，其中 403 拆两条 → 计 5），总数 ≥ 96。
- `scripts/platform-demo-reset.sh`：+3 条（`WBS_ESTIMATE` 11 张、`PENDING` 0、11 条需求有 `APPROVED`），66 → 69。
- `scripts/tests/access/a7-estimate-gate.mjs`（新，接入 `access-verify.sh`）：静态 + 运行时共 ≥ 6 条（按钮仍渲染、`disabled`、`title` 含"工时评估"、点击零请求、通过后可用、无权限文案不含"工时评估"），`access` 190 → ≥196。
- `rbac-verify` 不动（不新增权限码）：仍 113。

**evidence ≥ 3 张**（`docs/teamflow/20261117-r26-wbs-estimate-gate/evidence/`）：未评估时按钮禁用 + 原因、审批中心待办「工时评估」、驳回原因出现在需求动态；附 409 实测输出与 reset 计数。

---

## 7. 风险与取舍

| 风险 | 处理 |
|---|---|
| 引擎无"管理员代批" | 夹具用 `TenantContext.set` 身份切换（seed 包内、`finally` 复位）；**不**给引擎加代批方法，否则 AC-16 的"无权限审批 403"失效 |
| 夹具跨两段事务 | reset 的幂等性与全部业务不变量留在 Tx1；Tx2 只新增评估单，失败即整体 500 且重跑自愈；文档与脚本里写明"评估单不在 Tx1 回滚范围内" |
| `biz_domain.owner_user_id` 回填 | 只补空值、幂等；影响面是或签节点解析与域负责人通知，均正向 |
| PDR `start_dev` 未受门约束 | 按 PRD 只锁两个动作；如验收要求补锁，只需在 `gated()` 增加一组（单点改动，测试矩阵已按"两动作"参数化） |
| 审批中心筛选是当前页客户端过滤 | 与既有四类同口径；若要服务端筛选需改 `ApprovalStore`/`ApprovalCenterService` 分页契约，超出本轮改动面 |
| `EstimateSnapshot` 与引擎私有 `documentOf` 实现同形 | 接受重复：把 SUBMIT 快照解析集中到一个 10 行工具类，两处调用；不改引擎可见性以缩小改动面 |

---

## 8. 任务拆分（T1 → T4 串行，与 PRD §11 对齐）

**git 动作（每个任务的提交口径）**

- 全程在 `feat/wbs-estimate-approval`，不新建分支、不切 `main`、不 rebase。
- `?? docs/teamflow/20261117-r26-wbs-estimate-gate/` 随首个任务一并提交，提交信息用 `docs: r26 工时评估初审任务夹`；代码改动用 `feat: 工时评估初审与排期/开发前置门`。两个提交分开，不 stash、不丢弃。
- 不 tag、不 push；提交前 `.gitignore` 审计（`target/`、`web/node_modules/`、`web/dist/`、`*.log`、`.nacos/`、`logs/teamflow/`、`*.tsbuildinfo`、`.env*`），缺口在本轮补齐并在提交说明写明。

| 任务 | 文件边界 | 出口 |
|---|---|---|
| **T1 单据与引擎接入** | `approval/WorkflowVersion.java`、`approval/ApprovalInstance.java`、`approval/DocumentSummaryProvider.java`、`approval/ApprovalCenterService.java`、`requirement/WbsEstimateService.java`、`WbsEstimateViews.java`、`EstimateSnapshot.java`、`WbsEstimateSummaryProvider.java`、`WbsEstimateCallback.java`、`RequirementErrors.java`、`RequirementDtos.java`、`RequirementController.java`、`RequirementService.java`（仅两个回调辅助方法）、`seed/SeedService.java`、`WbsEstimateSubmitTest.java`、`WbsEstimateUniquenessTest.java`、`WbsEstimateCallbackTest.java` | `mvn -o -DskipTests compile` 0 错；提交 → 审批中心出现待办 → 通过后需求人天变化、动态两条 |
| **T2 门与视图** | `requirement/RequirementService.java`（门插点、`applyGate` 三处、`detail`）、`RequirementViews.java`、`WbsEstimateGateTest.java`、`WbsEstimateStaleTest.java` | 12 组矩阵 + 3 条顺序 + 4 条阈值绿；409 detail 三键；既有 r23 门与版本门不回归 |
| **T3 前端** | `web/src/views/req/estimate.ts`、`api/types.ts`、`api/req.ts`、`views/req/RequirementDetailView.vue`、`tabs/OverviewTab.vue`、`tabs/ApprovalTab.vue`、`views/approval/ApprovalCenterView.vue` | `npm run typecheck` 0 错；vpm1 点击零请求不落 /403；通过后同一按钮变可用 |
| **T4 夹具、门禁与自检** | `seed/DemoFixture.java`、`seed/DemoResetService.java`、`seed/DemoEstimateFixture.java`（新）、`scripts/platform-verify.sh`、`scripts/platform-test-negative.sh`、`scripts/platform-demo-reset.sh`、`scripts/access-verify.sh`、`scripts/tests/access/a7-estimate-gate.mjs`（新）、`docs/teamflow/memory.md`、`evidence/` | 11 张 APPROVED / 0 在途；负向 ≥96；demo-reset ≥69；access ≥196；`platform-verify` ≥174；lint/unit/compile/typecheck/build 全绿；memory 同主题行替换 |

T4 末尾做一次**交付自检**（不是 QA 复演）：发现缺陷就地修；只把结论写进 `TECHNICAL.md` 的状态块与提交说明；不写 `QA-REPORT.md`/`ACCEPTANCE.md`，不跨浏览器/真机全矩阵。
