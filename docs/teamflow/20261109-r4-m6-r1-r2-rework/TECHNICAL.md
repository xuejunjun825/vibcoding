# M6 验收返工技术方案（R1 + R2 + Q-01）

> 范围：仅修 M6 验收遗留的两条缺陷与一条口径缺陷，不新增业务能力、不改数据面结构。
> 依据：`docs/teamflow/20261109-r4-m6-r1-r2-rework/PRD.md`（AC-1..AC-8、§8 工程约束）。
> 基线：`docs/teamflow/20261108-r6-m6-quality-management/`（M6 主体交付，提交 15cd53c）。

## 1. 结论

| 编号 | 缺陷 | 修法（一句话） | 落点 |
|---|---|---|---|
| R1 / AC-1..AC-4 | 发布单可引用 DRAFT/PENDING_REVIEW 计划 | deliver 增一个只读内部端点算「未批准覆盖计划」，req 在 `create` 解析出需求集后、任何写库前调用守卫，命中即 409 | deliver `quality/internal`、req `DeployOrderService.create` |
| R2 / AC-5..AC-6 | 5 项/NULL 快照 legacy=false 且前端提示不可达 | `legacyOf` 空集改判 true、`snapshot()` NULL 分支显式传 legacy=true；面板把提示移到列表外并用 `hasQualityGateItem` 判定 | `DeployViews`、`AdmissionEvaluator`、`QualityGatePanel.vue` |
| Q-01 / AC-7 | 空需求集 QG-01 显示「覆盖 100%」假绿 | `total==0` 时覆盖子项判 N/A（不是 FAIL、不计分母），actual 出「N/A（无需求可覆盖）」，报告子项口径不变 | `GateEvaluator.qg01` + `document()` |

不变式：**不发 SQL 迁移**、**不新增发布单更新端点**（全仓确无 PUT/PATCH）、**不改 `GateSnapshot`/`GateRuleResult` 契约**、**不新增前端路由**、**不新建 checker 脚本**（在 q1/q4/q5 原文件追加上下文）。

## 2. 现状核对（改动点精确定位）

| 文件 | 现状 | 本方案动作 |
|---|---|---|
| `req-service/src/main/java/com/example/req/deploy/DeployOrderService.java:165-208` | `create` 为 `@Transactional`；`:178` `resolveRequirementIds(...)`；`:180` 组 entity；`:196` `repository.save`；`:197-200` 写 `deploy_order_requirement`；`:206` `auditService.record(CREATE,...)` | 在 `:178` 之后、`:180` 之前插入一行 `planGuard.requireApproved(release.getId(), requirementIds);` |
| `req-service/src/main/java/com/example/req/deploy/DeployOrderService.java:211-231` | `resolveRequirementIds`：显式 ids 去重 + 租户可见性校验，否则按 `releaseId` 取 `type=RD` | 不改；R1 只消费其返回值 |
| `req-service/src/main/java/com/example/req/deploy/DeployViews.java:74-88` | `record AdmissionSnapshot(items, checkedAt, stale, signature, legacy, gate)`；3 参构造走 `legacyOf`；`legacyOf` 空集返回 `false` | `legacyOf` 空集返回 `true`（R2 根因） |
| `req-service/src/main/java/com/example/req/deploy/AdmissionEvaluator.java`（`snapshot`，stored==null 分支） | `new DeployViews.AdmissionSnapshot(List.of(), null, false)` → legacy=false | 显式 6 参：`(List.of(), null, false, null, true, null)` |
| `req-service/src/main/java/com/example/req/deploy/DeployErrors.java` | 现成 `AdmissionFailedException` 等工厂；`ApiException(status, code, message, detail)` | 增 `PLAN_NOT_APPROVED` / `PLAN_CHECK_UNAVAILABLE` 常量与两个工厂 |
| `deliver-service/.../quality/plan/PlanLifecycle.java:73` | `reportableStatuses()=Set.of("APPROVED","IN_PROGRESS","COMPLETED","CLOSED")` | 复用；新增 `pendingStatuses()=Set.of("DRAFT","PENDING_REVIEW")`（唯一口径，禁止字面量） |
| `deliver-service/.../quality/internal/QualityInternalController.java` | 私有 `tenantOf(header, releaseId)`：header 优先，否则 `SELECT tenant_id FROM \`release\``，否则 404 | 抽 `InternalTenantResolver`，本类改为委托（行为不变） |
| `deliver-service/.../quality/gate/GateEvaluator.java:348-361` | `percent = total==0 ? 100 : ...`，`coverageOk = percent>=required`，`pass = coverageOk && reportOk` | 空集走 N/A 降级（§5.3） |
| `deliver-service/.../quality/gate/GateEvaluator.java:522-525` | `document().requirements.coveragePercent` 空集写 100 | 空集写 `null`（与证据一致；该块只进 `snapshot_json`，全仓无读取方） |
| `web/src/components/quality/QualityGatePanel.vue:231-233` | `v-if="item.code === 'QUALITY_GATE' && legacy && !itemHasGateRules(item)"` 在 `v-for` 内 → 历史快照无该 item，永不渲染 | 删除该段，列表外新增独立横幅（§6.1） |
| `web/src/components/quality/QualityGatePanel.vue:195` | `gate-item--legacy` 类绑定同样依赖「存在 QUALITY_GATE 项」→ 死代码 | 一并删除；`itemHasGateRules` 仍被 :219/:234 使用，保留 |
| `web/src/views/deploy/DeployOrderListView.vue`（`submitCreate` catch） | `formError.value = messageOf(error)`，无 `ApiError` 分支 | 增 `PLAN_NOT_APPROVED` 专属文案（§6.2） |
| `deliver-service/.../quality/report/ReportService.java:157` | 已有 409 `PLAN_NOT_APPROVED`（生成报告场景，detail=`{planId,status,allowedStatuses}`） | 不复用该机器码的 detail 形状；同一 code 两种上下文，见 §7 去重说明 |

## 3. 数据模型与存储

### 3.1 DDL：0 条
本返工 **不新增表、不新增列、不新增索引、不改列宽**。理由：R1 的判定信息已完整存在于 `test_plan` / `test_plan_case`；把「是否已批准」冗余成发布单列会引入双写与漂移，而批准状态是计划侧事实，必须在读取时求值。R2 是**读时派生语义**（legacy 由 items 内容决定），不落库。Q-01 只改 `evidence_json` / `snapshot_json` 的载荷，二者都是 JSON 列。

### 3.2 复用表与索引

`req-service/src/main/resources/db/migration/V1__platform_schema.sql:584`

```sql
test_plan(id, tenant_id, release_id, name, window_start, window_end, owner_id,
          status VARCHAR(12) DEFAULT 'DRAFT', gate_mode, domain_id, vendor_id, ...,
          KEY idx_plan(tenant_id, release_id, status))
```

`V1__platform_schema.sql:604`

```sql
test_plan_case(id, tenant_id, plan_id, case_id, case_version, requirement_id,
               executor_id, result VARCHAR(12) DEFAULT 'NOT_RUN', ...,
               UNIQUE uk_plan_case(plan_id, case_id),
               KEY idx_plan_case_req(tenant_id, requirement_id, result))
```

R1 查询走 `idx_plan(tenant_id, release_id, status)` 收窄到「本版本 + 待批状态」，再按 `uk_plan_case(plan_id, case_id)` 回表过滤需求；单版本计划数量为个位数到百级，`EXPLAIN` 预期 `ref` 级别，300ms 预算充裕。**不做全租户按需求反查**（那会命中其它版本的历史计划，见 §7 R-2）。

`deploy_order.admission_result_json`（V1 已有）：R2 不改其内容，只改读取时的派生；旧行保持原样，天然满足「历史快照逐字节不变」（q5 既有断言）。

`quality_gate_rule.evidence_json` / `quality_gate_snapshot.snapshot_json`：Q-01 新增 `coverageApplicable`、`coveragePercent` 允许 `null`；旧快照不回填，`replay` 仍按存储 JSON 原样返回。

### 3.3 状态口径单点

`PlanLifecycle` 是唯一状态源，本方案只加一个方法（不改 `allowedNext`）：

```java
/** 尚未批准、不允许被发布单引用的计划状态（R1 的唯一口径）。 */
public static Set<String> pendingStatuses() {
    return Set.of("DRAFT", "PENDING_REVIEW");
}
```

已批准集合继续用 `reportableStatuses()`，**SQL 里传集合参数，不写 `'APPROVED'` 字面量**（避免 V6 扩宽状态后两处漂移）。

### 3.4 快照语义（R2）

| `admission_result_json` | items | legacy | gate | 前端 |
|---|---|---|---|---|
| `NULL`（从未评估） | `[]` | `true` | `null` | 一条「质量门未启用（历史单据）」 |
| 5 项旧快照 | 5 项，无 `QUALITY_GATE` | `true` | `null` | 同上，且逐项照旧展示 |
| 6 项新快照 | 6 项，含 `QUALITY_GATE` | `false` | 有值 | 正常门禁卡，不出现提示 |

`legacy` 的判定改为「**没有第 6 项就是历史结论**」，空集也成立；绝不补写 `QUALITY_GATE` 项、绝不伪造 `PASS`。

## 4. API 设计

### 4.1 新增（内部只读）`GET /internal/test-plans/pending-coverage`

- 服务：deliver-service；守卫：`InternalTokenFilter`（`/internal/**`，`X-Internal-Token`）。
- 查询参数：`releaseId`（必填，long）、`requirementIds`（可选，逗号分隔 long，可重复）。
- 请求头：`X-Tenant-Id`（可选；缺省由 `release` 行回退，**绝不从 body 取租户**）。
- 语义：返回该版本下、状态属于 `PlanLifecycle.pendingStatuses()`、且用例覆盖到给定需求的计划；**只读**，无写入、无快照、无审计。

200 响应：

```json
{
  "items": [
    {"planId": 142, "planName": "2026-11 回归计划", "status": "PENDING_REVIEW", "requirementIds": [5, 9]}
  ],
  "planIds": [142]
}
```

- `requirementIds` 为空/缺省 → `{"items":[],"planIds":[]}`，**短路不查库**（无需求就没有覆盖，AC-3 的「无计划」与「无需求」同一条放行路径）。
- 版本不存在 → 404 `RELEASE_NOT_FOUND`（沿用既有码）。
- `planIds` 去重升序；`items` 按 `planId` 升序，`requirementIds` 升序去重。

### 4.2 `POST /api/req/deploy-orders`（既有，新增失败语义）

请求体不变（`releaseId/env/windowStart/windowEnd/artifactVersion/steps/rollbackPlan/impact/ownerId/requirementIds?`）。响应：

| 场景 | 码 | body |
|---|---|---|
| 无覆盖计划，或覆盖计划全部 ∈ `reportableStatuses()` | 201 | `OrderDetail`（不变） |
| 任一覆盖计划 ∈ `{DRAFT, PENDING_REVIEW}` | 409 | `{"code":"PLAN_NOT_APPROVED","message":"发布单引用了未批准的测试计划 #142，请先完成计划评审批准或移除对应需求","detail":{"planIds":[142],"plans":[{"id":142,"name":"...","status":"DRAFT","requirementIds":[5]}],"requirementIds":[5],"hint":"先提交评审并批准计划，或从发布单移除该需求后重试"}}` |
| deliver 不可达/超时/非 2xx/载荷不可解析 | 503 | `{"code":"PLAN_CHECK_UNAVAILABLE","message":"暂时无法校验测试计划批准状态，请稍后重试","detail":{"reason":"..."}}` |

- **不落单**：两种失败都发生在 `repository.save` 之前；行数与审计条数不变（AC-1 的可验证点）。
- 机器可读位置统一为 `detail.planIds`（与既有 409 的 `detail.failedRules`/`detail.items` 同规格，`ApiError.detail` 直接消费）；`message` 同时带 `#id`，便于人读与文本断言。
- 未新增 update 端点（AC-2）：若未来新增，必须调用同一 `DeployPlanGuard.requireApproved`，本文件第 5.2 节写明。

### 4.3 `GET /api/req/deploy-orders/{id}`（既有，语义修正）

`admission` 字段形状不变；`legacy` 与 `gate` 按 §3.4 修正。示例（历史 5 项单）：

```json
{"admission":{"items":[...5 项...],"checkedAt":"2026-10-30T10:00:00","stale":true,
  "signature":"...","legacy":true,"gate":null},"qualityGateSnapshotId":null}
```

### 4.4 质量门求值载荷（既有端点，字段级变更）

`POST /api/deliver/quality-gates/{releaseId}/evaluate` 与内部同名端点返回的 `rules[]` 中，QG-01：

```json
{"code":"QG-01","status":"FAIL","required":true,
 "actual":"需求覆盖 N/A（无需求可覆盖）· 无已会签报告",
 "threshold":"覆盖 ≥ 100% · 已会签 RELEASE 报告","fixHint":"..."}
```

- `status` 仍是 `PASS/FAIL/WAIVED`（不引入 `N/A`，见 §5.3 取舍）；
- `quality_gate_rule.evidence_json`：`{"totalRequirements":0,"coveredRequirements":0,"coveragePercent":null,"coverageApplicable":false,"signedReport":false}`；
- 有需求时载荷与今日完全一致（回归面为零）。

## 5. 后端实现

### 5.1 deliver-service

**(a) `quality/internal/InternalTenantResolver.java`（新增，`@Component`）**

```java
public long of(String tenantHeader, long releaseId);   // header 优先 → release 行 → 404 RELEASE_NOT_FOUND
```

实现照搬 `QualityInternalController.tenantOf`（`NamedParameterJdbcTemplate` + `` SELECT tenant_id FROM `release` WHERE id = :id ``），`QualityInternalController` 改为注入并委托，删私有副本（行为与错误码不变）。

**(b) `quality/internal/PlanCoverageInternalController.java`（新增）**

```java
@RestController
public class PlanCoverageInternalController {
    @GetMapping("/internal/test-plans/pending-coverage")
    public Coverage pendingCoverage(@RequestParam long releaseId,
                                    @RequestParam(required = false) List<Long> requirementIds,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader);

    public record PlanRef(long planId, String planName, String status, List<Long> requirementIds) {}
    public record Coverage(List<PlanRef> items, List<Long> planIds) {}
}
```

流程：`requirementIds` 归一化（去空、去重、升序）→ 为空直接返回空 `Coverage` → `tenantId = tenantResolver.of(tenantHeader, releaseId)` → `TenantContext.set(DataScope.system(tenantId))`，`try/finally clear` → `ScopedJdbc.list(...)` → 按 `plan_id` 聚合成 `PlanRef`（保留首次出现的 name/status，需求 id 追加去重）。

SQL（`/*scope:p*/` 必须保留，缺标记会被 `ScopedJdbc` 拒绝）：

```sql
SELECT p.id AS plan_id, p.name AS plan_name, p.status AS status, c.requirement_id AS requirement_id
  FROM test_plan p
  JOIN test_plan_case c ON c.plan_id = p.id AND c.tenant_id = p.tenant_id
 WHERE /*scope:p*/ p.release_id = :releaseId
   AND p.status IN (:pendingStatuses)
   AND c.requirement_id IN (:requirementIds)
 ORDER BY p.id, c.requirement_id
```

参数：`releaseId`、`pendingStatuses=PlanLifecycle.pendingStatuses()`、`requirementIds`（`Map<String,Object>`，`NamedParameterJdbcTemplate` 自动展开集合）。

**(c) `quality/plan/PlanLifecycle.java`**：加 §3.3 的 `pendingStatuses()`（纯新增方法）。

**(d) `quality/gate/GateEvaluator.java`**：§5.3。

### 5.2 req-service

**(a) `integration/DeliverPlanCoverageClient.java`（新增，`@Component`）**

```java
public List<PlanRef> pending(long releaseId, List<Long> requirementIds);  // 空入参直接返回 List.of()，不发请求
public record PlanRef(long id, String name, String status, List<Long> requirementIds) {}
public static class PlanCheckUnavailable extends RuntimeException { ... }
```

- 走 `InternalTokenClient.deliver(PLAN_COVERAGE_BUDGET)`，`PLAN_COVERAGE_BUDGET = Duration.ofMillis(300)`；
- `GET /internal/test-plans/pending-coverage?releaseId={}&requirementIds=1,2,3`，解析 `ParameterizedTypeReference<Map<String,Object>>`（与既有客户端一致，不引入 contract record 的 JavaTime 依赖）；
- 连接失败、超时、非 2xx、`items` 结构异常 → 一律 `throw new PlanCheckUnavailable("...")`（**fail-closed**，不返回空列表）。

**(b) `deploy/DeployPlanGuard.java`（新增，`@Component`）**

```java
/** R1 的唯一校验点：发布单写库前拒绝未批准的覆盖计划。 */
public void requireApproved(long releaseId, List<Long> requirementIds) {
    if (requirementIds == null || requirementIds.isEmpty()) return;                 // 无需求 → 放行
    List<DeliverPlanCoverageClient.PlanRef> blocked;
    try {
        blocked = coverage.pending(releaseId, requirementIds);
    } catch (DeliverPlanCoverageClient.PlanCheckUnavailable e) {
        throw DeployErrors.planCheckUnavailable(e.getMessage());
    }
    if (!blocked.isEmpty()) throw DeployErrors.planNotApproved(blocked, requirementIds);
}
```

**(c) `deploy/DeployErrors.java`**：新增

```java
public static final String PLAN_NOT_APPROVED = "PLAN_NOT_APPROVED";
public static final String PLAN_CHECK_UNAVAILABLE = "PLAN_CHECK_UNAVAILABLE";
public static ApiException planNotApproved(List<DeliverPlanCoverageClient.PlanRef> plans, List<Long> requirementIds);
public static ApiException planCheckUnavailable(String reason);
```

`planNotApproved` → `new ApiException(409, PLAN_NOT_APPROVED, message, Map.of("planIds", ids, "plans", plans, "requirementIds", reqIds, "hint", HINT))`；`planCheckUnavailable` → `new ApiException(503, PLAN_CHECK_UNAVAILABLE, "暂时无法校验测试计划批准状态，请稍后重试", Map.of("reason", reason == null ? "" : reason))`。`plans` 序列化为 `{id,name,status,requirementIds}`（record 直接 Jackson）。

**(d) `deploy/DeployOrderService.java`**：构造器注入 `DeployPlanGuard`；`create` 中 `:178` 之后插入：

```java
List<Long> requirementIds = resolveRequirementIds(cmd.requirementIds(), release, scope.tenantId());
planGuard.requireApproved(release.getId(), requirementIds);   // R1：写库前唯一闸门
```

时序要点：远程校验发生在**任何 INSERT 之前**，事务内不持有行锁；异常直接冒出（默认 rollback），不会走到 `:196` 的 `save` 与 `:206` 的审计。`submit` 路径不改（准入第 6 项已由质量门负责）。

**(e) `deploy/DeployViews.java`**：`legacyOf` 空集返回 `true`，注释改为「空集 = 从未评估的旧单」。**(f) `deploy/AdmissionEvaluator.java`**：`stored==null` 分支显式 `new AdmissionSnapshot(List.of(), null, false, null, true, null)`。

### 5.3 Q-01：QG-01 空需求集

`facts.totalRequirements()` 是**版本级**需求数（`requirementCount(releaseId)`），与发布单 `requirementIds` 无关 —— 因此「空需求集」= 该版本没有挂任何需求，此时 `percent=100` 是假绿。

```java
long total = facts.totalRequirements();
long covered = facts.coveredRequirements();
int requiredPercent = ruleset.intOf(config.thresholdJson(), "coveragePercent", 100);
boolean coverageApplicable = total > 0;
Integer percent = coverageApplicable ? (int) Math.floor(covered * 100.0 / total) : null;
boolean coverageOk = !coverageApplicable || percent >= requiredPercent;   // 空集真空满足，不计分母
boolean reportOk = facts.report() != null;
boolean pass = coverageOk && reportOk;
String coverageText = coverageApplicable
        ? "需求覆盖 " + percent + "%（" + covered + "/" + total + "）"
        : "需求覆盖 N/A（无需求可覆盖）";
```

- `actual = coverageText + " · " + (reportOk ? "报告 vX 已会签" : "无已会签报告")`；
- `evidence`：`totalRequirements`、`coveredRequirements`、`coveragePercent`（可 `null`）、`coverageApplicable`、`signedReport`；
- `document()` 的 `requirements.coveragePercent` 空集写 `null`（与证据一致，避免快照里出现 100）；
- **取舍（为何不做规则级 N/A）**：QG-01 是「覆盖子项 ∧ 报告子项」的合取，规则级 N/A 会把「报告未会签」这一真实 FAIL 一起吞掉；且 `GateRuleResult` 只有 `PASS/WAIVED/FAIL/SKIPPED`，新增 `N/A` 要改契约 + 前端枚举 + q4/q5 既有断言。PRD AC-7 明确允许降级为「`pass=true` 真空满足 + actual/evidence 标 N/A」，本方案取该路径，回归面最小。

## 6. 前端

### 6.1 `web/src/components/quality/QualityGatePanel.vue`

- 新增 computed：

```ts
const hasQualityGateItem = computed(() => gateItems.value.some(item => item.code === 'QUALITY_GATE'))
const legacyNotice = computed(() => props.legacy && !hasQualityGateItem.value)
```

- 把提示移到 `v-for` **之外**，位于头部与列表之间（`items` 为空也渲染）：

```vue
<p v-if="legacyNotice" class="gate-panel__legacy" role="status">
  质量门未启用（历史单据）— 不补写 PASS，也不显示快照 hash。
</p>
```

- 删除 :231-233 的旧 `<p v-if="item.code === 'QUALITY_GATE' && legacy && !itemHasGateRules(item)" ...>` 与 :195 的 `gate-item--legacy` 死类；`.gate-panel__legacy` 样式复用原 `.gate-item__legacy` 的配色（新增一条规则，不改既有 rule）。
- 空 `items` 且非 legacy（如新单未评估）仍走原有空态，不显示该提示；`mode='admission'` 的 6 项渲染不变。

### 6.2 `web/src/views/deploy/DeployOrderListView.vue`

`submitCreate()` 的 `catch` 改为：

```ts
} catch (error) {
  formError.value = planErrorText(error)
}

function planErrorText(error: unknown): string {
  if (error instanceof ApiError && error.code === 'PLAN_NOT_APPROVED') {
    const ids = (error.detail as { planIds?: number[] } | undefined)?.planIds ?? []
    return `发布单引用了未批准的测试计划${ids.length ? `（#${ids.join('、#')}）` : ''}：请先完成计划评审批准，或移除对应需求后重试。`
  }
  return messageOf(error)
}
```

`import { ApiError } from '@/api/client'`。503 无需特判（`messageOf` 已给后端文案），列表/详情其余逻辑不动。

### 6.3 状态管理

无 Pinia 变更、无新增全局 store、无新增 API 函数：`legacy` 已由 `admission.legacy` 经 `DeployOrderDetailView.vue:246` 传入；`planIds` 从错误对象即时读取。表单错误仍是页面级 `ref`，与现有约定一致。

## 7. 关键边界、失败模式与取舍

| 编号 | 边界/失败 | 行为 | 理由 |
|---|---|---|---|
| R-1 | 发布单无 `requirementIds`，版本也无 RD 需求 | 直接 201，不调 deliver | 无需求 = 无覆盖对象，AC-3 |
| R-2 | 覆盖计划属**其它 release** | 不计入（SQL 限定 `p.release_id`） | 覆盖证据必须属于被发布版本；否则历史 fixture/他版本的 DRAFT 计划会跨版本阻塞（q1 就留有已归档计划） |
| R-3 | 同一需求被多个计划覆盖，其中一个待批 | 409，`planIds` 全列 | 任一未批准即不可发布，避免「挑一个已批准计划洗白」 |
| R-4 | deliver 超时/500/返回体异常 | 503，不落单 | fail-closed：批准状态未知时发布的风险高于阻断；不重试（避免 300ms 翻倍） |
| R-5 | 显式 `requirementIds` 不要求已挂 release（`:211-231` 现状） | 仍按其值校验覆盖 | 语义是「这些需求要随本次发布上线」，与是否已挂版本无关；不改既有可见性规则 |
| R-6 | 种子版本（`SeedService.java:304`）自带 DRAFT 计划覆盖 rd1/rd2 | 对种子版本建单将 409 | 这正是修复意图；现有脚本均用新建版本（已核对 platform-verify/platform-test-negative/q1/q4/q5） |
| R-7 | `admission_result_json` 为 NULL 的**新建**单 | 也显示 legacy 横幅 | AC-5 显式要求 NULL → legacy=true；文案与「历史」略有张力，按 PRD 文案执行，不改写 |
| R-8 | 旧快照 5 项 | `legacy=true, gate=null`，不补第 6 项 | AC-5；q5 的「快照逐字节不变」仍成立 |
| R-9 | QG-01 空集且报告已会签 | QG-01 PASS，actual 带 N/A | 覆盖子项真空满足，报告子项独立判定 |
| R-10 | QG-01 空集且无报告 | QG-01 FAIL，actual「N/A（无需求可覆盖）· 无已会签报告」 | N/A 只描述覆盖子项，不掩盖报告缺失（AC-7） |

**去重说明（写入蓝图 duplications）**：`PLAN_NOT_APPROVED` 在 `ReportService.java:157`（生成报告）与本次发布单创建各有一处，detail 形状不同（`{planId,status,allowedStatuses}` vs `{planIds,plans,...}`）。两者语义都是「计划未批准」，但对象不同（单个报告源计划 vs 发布单覆盖集合），因此**不合并**；前端按页面上下文分别处理，机器码相同是有意为之，文档在此显式登记，避免后续误判为重复实现。

## 8. 测试策略

### 8.1 新增断言（在原 checker 内追加，不新建文件）

**`scripts/tests/quality/q1-plan-lifecycle.sh`（+4~5 条，覆盖 AC-1/2/3）**，追加在现有生命周期断言之后：

1. `q_create_plan "$REL" "未批准计划-$RUN_TAG" "${CASES[0]}"` 得 `DRAFT_PLAN`；取 `REQ0=$(db_q "SELECT requirement_id FROM test_case WHERE id=${CASES[0]}")`；
2. `api_call POST /api/req/deploy-orders`（显式 `requirementIds:[$REQ0]`，窗口同 `_lib.sh`）→ `deny "DRAFT 计划覆盖需求被拒" 409 PLAN_NOT_APPROVED`；
3. `expect_jq "detail.planIds 命中 DRAFT 计划" ".detail.planIds | index(${DRAFT_PLAN}) != null" 'true'`（`jget` 取 `.detail.planIds[0]`）；
4. 行数不变：`same_count deploy_order`、`same_count deploy_order_requirement`、`same_count audit_log "action='CREATE' AND biz_type='deploy_order'"`（快照在请求前取）；
5. `POST /api/deliver/test-plans/{id}/submit-review` → 再建单 → 409（PENDING_REVIEW 同口径）；
6. 批准（`approve`）后建单 → 201，并 `expect_jq "批准后无 legacy 误标" ".admission.legacy" 'false'`（顺带回归 R2 不误伤新单）；
7. 新版本 + 新需求、**不建计划** → 201（AC-3 无计划放行）。

**`scripts/tests/quality/q5-waiver-replay.sh`（+2~3 条，覆盖 AC-5/6）**：

1. 取一条既有发布单，`UPDATE deploy_order SET admission_result_json=NULL`（仅测试库 fixture）→ `GET /api/req/deploy-orders/{id}`：`.admission.legacy==true`、`.admission.gate==null`、`.admission.items|length==0`；
2. 5 项快照：用 `db_q` 把存储 JSON 里的 `QUALITY_GATE` 项剔除（`jq 'del(.items[] | select(.code=="QUALITY_GATE"))'`）→ 再取详情：`legacy==true`、`gate==null`、`items|length==5`、`[.admission.items[].code] | index("QUALITY_GATE") == null`；
3. 静态断言：`grep -q "legacyNotice" web/src/components/quality/QualityGatePanel.vue`、`grep -q "质量门未启用（历史单据）" ...`、`! grep -q "item.code === 'QUALITY_GATE' && legacy" ...`（旧不可达条件已删）。

**`scripts/tests/quality/q4-gate-admission.sh`（+1~2 条，覆盖 AC-7）**：新建版本、**不挂需求**、`q_gate_snapshot "$REL_EMPTY"`：QG-01 `actual` 含 `N/A（无需求可覆盖）`、不含 `100%`、`status=="FAIL"`（无报告）；证据落库校验 `db_q "SELECT JSON_EXTRACT(evidence_json,'$.coveragePercent') FROM quality_gate_rule ..."` 为 `NULL`、`coverageApplicable=false`、`totalRequirements=0`。

合计新增 ≥8 条（AC-8 要求 ≥6）；既有 308 条断言**一条不改**（q4 既有 6 项/QG-01 FAIL 场景的 actual 断言若有百分号匹配需回归确认，见 §8.3）。

### 8.2 全量回归矩阵（提交前必须全绿）

| 命令 | 期望 |
|---|---|
| `make quality-verify`（q0–q7） | 308 + 新增 ≥8 全 PASS，0 FAIL |
| `make platform-verify` | 167/167 |
| `make platform-test-negative` | 25/25 |
| `make platform-unit` | 133/133 |
| `make lint` | 16/16 |
| `make verify` | 14/14 |
| `cd web && npm run typecheck` | 0 error |
| `cd web && npm run build` | exit 0 |

另跑一次 `bash scripts/tests/quality/q4-gate-admission.sh` 单独确认 QG-01 新旧两态共存（有需求 100% 文案不变 / 空集 N/A）。

### 8.3 风险与人工补测

- **q4 既有断言**：若原断言用 `grep 100%` 校验 QG-01 actual，空集用例会与「有需求」用例分离，不冲突；dev 落地时先 `grep -n "QG-01" scripts/tests/quality/*.sh` 确认，不改旧断言语义。
- **frontend 静态断言**是 AC-6 的证据下限：再加 `npm run typecheck` + `build`（模板条件在编译期可见），不引入浏览器/E2E 新工具。
- **人工补测清单**（写入 QA 阶段，不新增自动化）：① 种子版本建单 → 页面显示 409 文案含 `#计划号`；② 历史 5 项单详情 → 横幅出现且无 `hash`；③ 新建单（NULL 快照）→ 横幅出现；④ 6 项正常单 → 无横幅；⑤ 空需求版本质量门页 → QG-01 显示 N/A。

## 9. 任务拆分（按文件边界，4 任务）

> 工程约束（PRD §8，逐任务生效）：分支 `feat/qa-quality-management`（HEAD 15cd53c），**不得新建分支、不得 rebase**；保留未提交的任务夹 `docs/teamflow/20261109-r4-m6-r1-r2-rework/`，**不得 clean/stash**；提交只含源码/脚本/文档，不含 `target/`、`node_modules/`、日志；四步全绿后才允许合并，**合并是用户确认的收尾步骤，dev 不自行 merge**。

### T1 · deliver 侧计划覆盖只读端点 + QG-01 空集（可并行）
- 文件：`deliver-service/src/main/java/com/example/deliver/quality/plan/PlanLifecycle.java`、`.../quality/internal/InternalTenantResolver.java`(新)、`.../quality/internal/PlanCoverageInternalController.java`(新)、`.../quality/internal/QualityInternalController.java`、`.../quality/gate/GateEvaluator.java`
- 契约：§4.1、§3.3、§5.3；`PlanLifecycle.pendingStatuses()`、`InternalTenantResolver.of(String,long)`、`PlanCoverageInternalController.pendingCoverage(long,List<Long>,String)` 与 `Coverage/PlanRef` 记录名必须逐字一致（req 侧按此解析）。
- 验收：内部端点 200 形状正确、空入参短路、404 版本不存在、`/*scope:p*/` 保留、QG-01 空集 actual/evidence 与 §8.1 q4 断言一致；`mvn -pl deliver-service -am test` 绿。

### T2 · req 侧守卫与 legacy 修正（可并行，依赖 T1 契约而非实现）
- 文件：`req-service/src/main/java/com/example/req/integration/DeliverPlanCoverageClient.java`(新)、`.../deploy/DeployPlanGuard.java`(新)、`.../deploy/DeployErrors.java`、`.../deploy/DeployViews.java`、`.../deploy/AdmissionEvaluator.java`、`.../deploy/DeployOrderService.java`
- 契约：§4.2、§5.2；`requireApproved` 只在 `create` 一处调用（AC-2）。
- 验收：409 的 `detail.planIds` 命中、行数/审计不变；503 fail-closed；NULL 与 5 项快照 `legacy=true,gate=null`；批准/无计划 201；`mvn -pl req-service -am test` 绿。

### T3 · 前端横幅与 409 文案（可并行）
- 文件：`web/src/components/quality/QualityGatePanel.vue`、`web/src/views/deploy/DeployOrderListView.vue`
- 契约：§6.1/§6.2；横幅在列表外、`legacy && !hasQualityGateItem`、文案逐字「质量门未启用（历史单据）— 不补写 PASS，也不显示快照 hash。」
- 验收：`npm run typecheck` 0 error、`npm run build` exit 0；grep 断言通过。

### T4 · 追加断言 + 全量回归（依赖 T1/T2/T3 完成后执行）
- 文件：`scripts/tests/quality/q1-plan-lifecycle.sh`、`scripts/tests/quality/q4-gate-admission.sh`、`scripts/tests/quality/q5-waiver-replay.sh`
- 动作：按 §8.1 追加；**在既有文件内追加，不新建 checker**；跑 §8.2 全矩阵；失败只修到绿，不弱化断言。
- 门禁：`quality-verify` 全绿且新增 ≥8；其余 5 套 + typecheck/build 全绿；记录各套件 PASS/TOTAL 供 QA 引用。
- 提交：单次原子提交（消息 `fix(m6): 发布单计划批准硬校验 + 历史快照 legacy 提示 + QG-01 空集 N/A`），不入库任务夹以外的临时文件。

依赖图：`T1 ∥ T2 ∥ T3 → T4`。T2 的联调可用 mock/契约先行，但 409 端到端必须由 T4 在真实栈验证。

## 10. 验证脚本与命令（交付时原样引用）

```bash
make platform-up && make platform-seed
make quality-verify          # q0..q7，308 + 新增≥8
make platform-verify         # 167
make platform-test-negative  # 25
make platform-unit           # 133
make lint                    # 16
make verify                  # 14
cd web && npm run typecheck && npm run build
```

单点复跑：`bash scripts/tests/quality/q1-plan-lifecycle.sh`、`q4-gate-admission.sh`、`q5-waiver-replay.sh`（均以 `guard` 收尾，打印 `#SUMMARY`）。

## 11. 明确不做

- 不新增/修改 DDL 与迁移；不回填历史快照；不改 `GateSnapshot`/`GateRuleResult`/`QualitySummary` 契约。
- 不新增发布单 update 端点；不改 `submit` 的准入第 6 项；不改豁免、报告、看板逻辑。
- 不新增 checker 文件、不写 QA-REPORT/ACCEPTANCE（属 QA 与验收阶段）、不做跨浏览器/E2E 新框架。
- 不改 `AGENTS.md`、不改 `docs/teamflow/architecture/`（本次无架构级变更）。
