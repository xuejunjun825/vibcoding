<!-- meta: summary="r40 技术方案：V16 两表 + 变更单领域/影响快照/第 6 类审批/应用事务 + PUT 收口 + 两个动作码 + 变更 Tab/四步抽屉/详情深链 + change-verify；含 8 处 PRD→代码事实校正与 5 个并行任务边界。" -->
基线依赖：docs/teamflow/20261123-r39-automation-rule-engine（字段变更事件口径、审计旁路与既有门禁）

# 需求变更单（r40）技术方案

## 0. 结论

变更单是**留痕单据**，不是编辑表单：创建只冻结 `before/after/impact` 并起审批；审批只把状态推到 `APPROVED`；只有显式 `apply` 才在一个事务里写需求字段、写 `req_change_item`、写一条 `REQUIREMENT_CHANGE_APPLIED` 审计并置 `APPLIED`。`APPROVED` 与 `APPLIED` 在 API、审计、UI 三处必须可区分。

实现落在现有 `req-service` 的需求/审批/审计/自动化骨架上，不新建服务、不新增依赖、不改 `pom.xml`/`web/package.json`。V16 是唯一新增迁移；动作码由 RBAC 启动预置自愈，不写权限迁移。

## 1. PRD → 代码事实校正（实现按本表，不按 PRD 字面）

| # | PRD 写法 | 代码事实 | 本方案 |
|---|---|---|---|
| F1 | 锁定后需求改不了 | `RequirementService.update` 不检查版本锁；`RELEASE_LOCKED` 只在版本成员增减/版本编辑 | 不新增 PUT 的锁门；锁定留痕由 `impact.postLock` + apply 的 `CHANGE_AFTER_RELEASE_LOCK` 表达（PRD Q7） |
| F2 | PUT 改 `releaseId` | `update` 只处理 parentId/estimateDays/sonarKey + 四内容字段，**完全忽略 releaseId** | 早期态直改若带 releaseId，补共享写字段器真正落库；晚期态先被 409 拦下 |
| F3 | 审批人解析为空 → 409 `APPROVAL_UNAVAILABLE` | 引擎实际抛 409 `APPROVER_UNRESOLVED`；`APPROVAL_UNAVAILABLE` 是 503 引擎不可用 | 创建接口如实透传 `APPROVER_UNRESOLVED`，前端按 409 展示；不造新码 |
| F4 | 路由用 `@RequiresAction` | 注解零使用，全部写端点由 `PermissionRouteTable` + `PermissionDeclarationValidator` 收口 | 两个动作码只进路由表，不引入第二套声明 |
| F5 | apply 复用 `req:deliver` | PRODUCT 对 `req:deliver` 是 DENY、对 `req:split` 是 RESTRICTED | `change_apply` 的 functionCodes = `["req:deliver","req:split"]`（any-of） |
| F6 | 动作登记进 `RequirementActionMatrix` | 矩阵 `ACTIONS` 恰 16 条状态迁移，测试锁「StateTables 并集 == 16」 | 两个变更动作**不进** `ACTIONS`；可见性走 `View.permissions` 布尔，`allowedActions` 仍只放迁移动作 |
| F7 | 既有 `UPDATE` 审计可当 before/after | `auditSnapshot` 不含 `description`/`parentId` | 变更单用独立 7 字段快照器；既有 UPDATE 审计形状不动 |
| F8 | 字段变更事件覆盖变更 | `AutomationEventMapper` 只跟踪 priority/ownerUserId/estimateDays/releaseId，且无 `REQUIREMENT_CHANGE_APPLIED` case | apply 只写 1 条审计，mapper 新增 case + 独立 7 字段词表；`UPDATE_FIELDS` 不动，避免既有 PUT 事件回归 |
| F9 | 第④步预览影响 | PRD 只有落库的创建接口 | 新增 `POST /requirements/{id}/changes/preview`：同 body、同校验、同 `assess`，不写库、不起审批、不动 items |

## 2. 总体结构

```text
RequirementDetailView
  ├─ ChangeTab ── list/get ──┐
  ├─ ChangeCreateDrawer ─────┤
  │    └─ preview (step 4)   │
  └─ RequirementChangeDetailView ── apply
                              ▼
RequirementChangeController (create / preview / list / detail)
RequirementChangeApplyController (apply)
        │
RequirementChangeService ──► RequirementChangeValidator ──► RequirementChangeFields
        │                └─► RequirementChangeImpactAssessor ──► ScopedJdbc / repositories
        ├─► ApprovalEngine.start  (REQ_CHANGE, 同事务)
        └─► AuditService.record("CHANGE_REQUESTED")
RequirementChangeApplyService
        ├─ RequirementRepository.findWithLockByIdAndTenantId（行锁）
        ├─ RequirementReleaseMembership.move（release_id + release_requirement 双写）
        ├─ RequirementChangeItemRepository
        └─ AuditService.record("REQUIREMENT_CHANGE_APPLIED")
             └─ AuditListener → AutomationEventMapper（字段事件）
ApprovalEngine.finishApproved → RequirementChangeCallback.onApproved（PENDING→APPROVED）
```

关键取舍：

- **创建与审批同事务**：`RequirementChangeService.create` 为 `@Transactional`，先插变更单拿到 id，再 `ApprovalEngine.start`，回填 `instance_id`，最后写审计。任一步抛异常，变更单与实例一起回滚（满足 AC-2/AC-4）。
- **预览与创建共用一条校验链**：`preview` 复用 `Validator.validateCreate + ImpactAssessor.assess`，只是不落库；这样 AC-17④ 的预测与提交后 `impact_json` 不可能两套逻辑。
- **apply 用需求行锁串行化**：同一需求的并发 apply 先抢 `SELECT ... FOR UPDATE`，再做 before 比对；同单第二次 apply 在锁后看到 `APPLIED` → 409，跨单改不同字段则先后成功（AC-6/7）。
- **不改评估单写路径**：STALE 仍是 `WbsEstimateService.statusOf` 的读时派生；apply 不写评估表，只保证预测函数与派生函数同源。

## 3. 数据模型（V16）

文件：`req-service/src/main/resources/db/migration/V16__requirement_change.sql`（唯一新增迁移；V1–V15 不动）。

```sql
CREATE TABLE IF NOT EXISTS req_change (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id       BIGINT       NOT NULL,
  requirement_id  BIGINT       NOT NULL,
  change_type     VARCHAR(16)  NOT NULL,
  status          VARCHAR(16)  NOT NULL,
  reason          VARCHAR(500) NOT NULL,
  before_json     JSON         NOT NULL,
  after_json      JSON         NOT NULL,
  impact_json     JSON         NOT NULL,
  instance_id     BIGINT       NULL,
  applied_at      DATETIME(3)  NULL,
  applied_by      BIGINT       NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by      BIGINT       NOT NULL,
  PRIMARY KEY (id),
  KEY idx_req_change_req (tenant_id, requirement_id, created_at),
  KEY idx_req_change_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS req_change_item (
  change_id     BIGINT      NOT NULL,
  field         VARCHAR(64) NOT NULL,
  before_value  TEXT        NULL,
  after_value   TEXT        NULL,
  PRIMARY KEY (change_id, field)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

约定（沿用 V15 的「为什么」注释风格）：

- 无外键：与全库一致，隔离靠 `tenant_id` + Scope，删除顺序靠 `DemoFixture.DELETE_ORDER`。
- JSON 不做 CHECK：合法性只在 Java 侧封闭 schema 校验；`impact_json` 顶层键固定但值可为 `null`/`[]`。
- `req_change_item` 只在 apply 成功时写；驳回/撤回/待审批 0 行。
- 索引覆盖 AC-1 要求的 `(tenant_id, requirement_id, created_at)` 与 `(tenant_id, status)`。
- `DemoFixture.DELETE_ORDER` 在 `wf_*` 之前插入 `req_change_item`、`req_change`（先子后父），避免重置残留。

### 3.1 实体与仓库

- `RequirementChangeEntity`：JPA 手写 getter/setter、`@Version` 不需要（状态用条件更新）、`@PrePersist` 填 `createdAt`；JSON 列用 `String` + `@Column(columnDefinition = "JSON")`，反序列化在服务层用 `ObjectMapper`。
- `RequirementChangeItemEntity`：`@IdClass(Key.class)`，`Key(long changeId, String field)`。
- `RequirementChangeRepository`：
  - `Optional<RequirementChangeEntity> findByIdAndTenantId(Long id, Long tenantId)`
  - `@Query` 分页：`page(tenantId, requirementId, status, Pageable)`，`status` 为 null 时不过滤，按 `createdAt desc, id desc`
  - `long countByTenantIdAndRequirementIdAndStatus(...)`
  - `@Modifying int markStatus(long id, long tenantId, String expected, String next)`
  - `@Modifying int markApplied(long id, long tenantId, String expected, LocalDateTime appliedAt, Long appliedBy)`
- `RequirementChangeItemRepository`：`findByChangeIdOrderByFieldAsc`、`countByChangeId`。
- `RequirementRepository` 增 `@Lock(PESSIMISTIC_WRITE) Optional<RequirementEntity> findWithLockByIdAndTenantId(Long id, Long tenantId)`。
- `ReleaseRequirementRepository` 增 `List<ReleaseRequirementEntity> findByTenantIdAndRequirementId(Long tenantId, Long requirementId)`（影响评估/双写清理用）。

## 4. 领域模型

### 4.1 白名单与快照

`RequirementChangeFields`（纯函数，无 Spring）：

```java
public static List<String> allowedFields(String changeType);          // CONTENT/DAYS/RELEASE/PARENT，未知类型返回空
public static Map<String,Object> snapshot(RequirementEntity entity);   // 固定 7 键
public static Map<String,Object> merge(Map<String,Object> before, Map<String,Object> patch);
public static List<String> changedFields(Map<String,Object> before, Map<String,Object> after);
public static boolean sameValue(Object a, Object b);                   // BigDecimal compareTo；null 安全
public static Object normalize(String field, Object raw);              // 类型/精度/日期/ID 归一
public static List<String> editableFields(String type, String status);  // 早期态→7 字段；否则→空
public static String asText(Object value);                             // items 文本，null→null
```

固定 7 键与前端一致：`title, description, priority, expectedDeliveryDate, estimateDays, releaseId, parentId`。`snapshot` 用 `LinkedHashMap` 保序；`before_json`/`after_json` 都存全量 7 键（不是整行、无敏感列）。

`normalize` 规则（在 Fields 内实现，避免 T1 依赖 T3 的 `RequirementService`）：title `strip` 后非空且 ≤200；description 允许空/null，其余字符串 `strip` 后非空；priority 空→`P2`、大写、限 `P0/P1/P2/P3`（与 `RequirementService.normalizePriority` 同规则）；date 用 `LocalDate.parse`；`estimateDays` 用 `new BigDecimal(raw.toString())` 并走 `RequirementRollup.scale`；ID 用 `long`；非法值抛 `RequirementErrors.validation(...)`，未知字段由 Validator 抛 `RequirementErrors.changeFieldNotAllowed(changeType, field, allowed)`。

### 4.2 早期态与 PUT 收口

`RequirementChangeFields.editableFields(type,status)` 直接编码 AC-12 的逐类型表（唯一来源，前端只读 `permissions.editableFields`，不复制状态表）：

| 类型 | 早期可直改 | 其余 |
|---|---|---|
| BUS | `SUBMITTED, APPROVING, PENDING_REVIEW` | `POOLED`、`CLOSED` |
| PDR | `PENDING_ANALYSIS` | `SCHEDULED, IN_DEV, DELIVERED, ACCEPTED_CLOSED` |
| RD | `TODO` | `IN_DEV, PENDING_TEST, TEST_PASSED, MERGED, DONE` |

`RequirementChangeGuard.rejectDirectEdit(entity, request)` 在 `RequirementService.update` 的 `requireWritable` 之后、任何写字段之前执行：

1. 从 `request` 提取实际提供的四类字段（title/description/priority/expectedDeliveryDate 直接字段；estimateDays/parentId/releaseId 在 `@JsonAnySetter extra` 里）。
2. `normalize` 后与当前实体 `sameValue` 比对，只保留**真正变化**的字段。
3. 若为空 → 放行；若当前状态属于早期态 → 放行；否则抛 409：

```json
{"code":"CHANGE_REQUIRED","message":"已入池需求的内容/人天/版本/父需求变更必须走变更单",
 "detail":{"changeEndpoint":"/api/req/requirements/{id}/changes","fields":["title","estimateDays"]}}
```

`status` 仍由 `rejectStatusInBody` 拦；`domainIds`/`sonarKey` 直改不受影响。早期态带 `releaseId` 时，`update` 调 `RequirementReleaseMembership.move(...)` 真正双写（修 F2）；晚期态不可能走到这里。

### 4.3 创建校验次序与错误码

`RequirementChangeValidator.validateCreate(entity, changeType, reason, patch)`：

1. `changeType` 不在四类 → 400 `CHANGE_FIELD_NOT_ALLOWED`，detail `{changeType, field:null, allowedFields:[]}`。
2. `reason` `strip` 为空 → 400 `REASON_REQUIRED`；长度 >500 → 400 `VALIDATION_FAILED`（"原因最多 500 字"）。
3. `patch` 空 / 含白名单外字段 / 字段不属于该 `changeType` → 400 `CHANGE_FIELD_NOT_ALLOWED`，detail `{changeType, field, allowedFields}`。
4. 逐字段 `normalize` 合并出 `after`；`changedFields` 为空 → 400 `VALIDATION_FAILED`（"没有任何字段变化"）。
5. 领域规则（与 PUT 同码同结构）：
   - CONTENT：`RequirementService.requireTitle`、`normalizePriority`、日期非空且合法；
   - DAYS：`RequirementRules.checkDays(type, afterDays, actualDays)` → 400 `PARENT_DAYS_READONLY` / `INVALID_DAY_PRECISION`；
   - PARENT：`checkParentCombination` + `checkAncestorCycle` → 400 `INVALID_PARENT`；`parentGate` → 409 `PARENT_NOT_POOLED`/`PARENT_NOT_SCHEDULED`；
   - RELEASE：目标版本存在、`requireVisible`、状态非 `CANCELLED`（400 `VALIDATION_FAILED` "目标版本已取消"）。

创建接口先 `requirementService.requireWritable(id)`（终态 → 400 "需求已关闭，不可修改"），再校验；`requireVisible` 的 403/404 口径由它内部给出。

`validateApply(entity, after)` 重跑第 5 步（树/版本可能已变）并额外检查需求仍可写；不重跑白名单（patch 已冻结）。

## 5. 影响评估

`RequirementChangeImpactAssessor.assess(entity, changeType, after)` 返回固定顶层键：

```json
{
  "fields": [{"field":"title","before":"旧","after":"新"}],
  "children": {"count": 3},
  "release": {"id":10,"code":"R-10","name":"…","status":"LOCKED","locked":true},
  "targetRelease": {"id":11,"code":"R-11","name":"…","status":"PLAN","locked":false},
  "days": {"before":5.0,"after":6.0,"delta":1.0,"estimateStatus":"APPROVED",
           "approvedDays":4.0,"deltaRatio":0.5,"becomesStale":true},
  "admission": {"passedOrderCodes":["DO-2026-001"]},
  "postLock": {"value":true,"statuses":{"10":"LOCKED"},"lockedReleaseIds":[10]},
  "frozenAt": "2026-11-23T17:30:00+08:00"
}
```

- `release`：当前 `requirement.releaseId` 对应版本；无 → `null`。
- `targetRelease`：仅 `changeType=RELEASE` 时为 after 的目标版本；其他类型 `null`（键仍在）。
- `days`：始终计算；`estimateStatus` 取 `WbsEstimateService.statusOf(entity)`；`approvedDays` 取新方法 `approvedDaysOf(requirementId)`（无 APPROVED 评估 → `null`）；`deltaRatio` 为未舍入比值（`after-approved` 的绝对值 / approved），`becomesStale` 由 `wouldBeStale(approvedDays, afterDays)` 给出；`delta = after - before`。无数据时 `approvedDays/deltaRatio` 为 `null`，`becomesStale=false`，前端显示「无法评估」。
- `children.count`：`RequirementRepository.countByTenantIdAndParentId`。
- `postLock`：当前需求所属全部版本（`ReleaseRequirementRepository.findByTenantIdAndRequirementId` + `ReleaseRepository`）中 `LOCKED/PUBLISHED` 的集合；`value` 为集合非空。
- `admission.passedOrderCodes`：`ScopedJdbc.list` 查 `deploy_order` join `deploy_order_requirement`，`WHERE /*scope:d*/ AND dr.requirement_id=:rid AND d.admission_result_json IS NOT NULL`；逐行注入的 `AdmissionEvaluator.parse(json)` + `allPassed(result)` 过滤（二者是 Spring bean 的实例方法，不是静态）。解析失败按「未 PASS」排除，不阻断创建。
- 性能：4 条聚合查询（子需求 count、版本关联、发布单 join、评估单），无 N+1；目标 P95 <300ms。任何 DB 异常直接抛出（500），**不得**吞掉降级成"无影响"。

`impactSummary`（列表项）由同一 `impact` Map 派生：`children.count`、`days.delta`、`days.becomesStale`、`postLock.value`、`admission.passedOrderCodes.length`。

阈值同源：`WbsEstimateService` 增

```java
public static boolean wouldBeStale(BigDecimal approvedDays, BigDecimal afterDays); // 严格 > 0.20，approved 为空/<=0 → false
public BigDecimal approvedDaysOf(long requirementId);                               // 无 APPROVED → null
```

`decide()` 改为调用 `wouldBeStale`，保证 `impact.days.becomesStale` 与 apply 后 `statusOf` 逐项一致（AC-9）。恰好 20%：`compareTo(STALE_THRESHOLD) > 0` 为 false，不转。

## 6. 审批集成（第 6 类单据）

### 6.1 类型与默认流程

- `WorkflowVersion` 增 `public static final String BIZ_REQ_CHANGE = "REQ_CHANGE";`（注释同步「六类」）。
- `SeedService` 增一条默认定义（仅在 `platform.seed.enabled=true` 时执行，与既有五类同口径）：

```text
biz_type=REQ_CHANGE, req_type=ANY, domain_id=0, version=1, status=PUBLISHED, is_default=1
n1: APPROVAL, mode=ALL,  approvers=[ROLE PRODUCT], next=n2
n2: APPROVAL, mode=ALL,  approvers=[DOMAIN_OWNER], next=end
```

域负责人由 `ApproverResolver` 的 `DOMAIN_OWNER` 从 `biz_domain.owner_user_id` 解析；产品经理从 `PRODUCT` 角色解析。流程定义页可改版/绑定；`ApprovalEngine.resolveWorkflow` 已钉版本，在途单不受改版影响。

- 创建调用：

```java
new StartApproval(WorkflowVersion.BIZ_REQ_CHANGE, changeId, entity.getType(),
        entity.getDomainId(), Map.of("requirementId", id, "changeType", type, "fieldCount", n), null, actorId)
```

- 审批人解析为空：引擎抛 409 `APPROVER_UNRESOLVED`（F3）；同事务回滚，变更单不落库。

### 6.2 回调映射

`RequirementChangeCallback implements ApprovalCallback`：

| 回调 | 动作 |
|---|---|
| `onApproved(bizId, instanceId)` | `markStatus(id, tenant, "PENDING", "APPROVED")`；行数 0 且当前 `APPLIED` → 幂等忽略；其他非法迁移不写 |
| `onRejected(bizId, instanceId, reason)` | `markStatus(..., "PENDING", "REJECTED")`；原因只存 `wf_history`，`req_change` 不建 `reject_reason` |
| `onWithdrawn(bizId, instanceId)` | `markStatus(..., "PENDING", "WITHDRAWN")` |

回调在引擎事务内执行；全部条件更新 + 幂等，绝不写需求字段。`APPROVED` 后 `GET` 需求逐字段仍为旧值（AC-5）。

### 6.3 审批中心摘要

`RequirementChangeSummaryProvider implements DocumentSummaryProvider`，`bizType() = REQ_CHANGE`：

- `attributes`：`{changeType, fieldCount, priority, reqType, domainId}`（条件/路由用）。
- `summary`：`{id, requirementId, code, title(越权打码), type, status, changeType, fieldCount, masked}`；按 `RequirementDocumentSummary` 的 `visibleTo` 同口径裁剪。
- 审批中心前端把 `bizType=REQ_CHANGE` 显示为「需求变更 · `<code>` · `<类型>` · `<fieldCount>` 项」，点击深链到变更详情（需从 summary 读 `requirementId` + `id`）。

### 6.4 事务与重复

`ApprovalEngine.start` 已用 `(tenant_id,biz_type,biz_id,active_flag)` 唯一键防同单重复；变更单 id 唯一，创建两次是两张不同单（AC-6 允许并存）。创建接口不加"同需求仅一张 PENDING"的限制。

## 7. 应用事务（apply）

`RequirementChangeApplyService.apply(long changeId)` 标注 `@Transactional`，顺序固定：

1. `change = repository.findByIdAndTenantId(changeId, tenant)`，否则 404。
2. `requirementService.requireVisible(change.requirementId)`；然后读变更状态：
   - `APPLIED` → 409 `CHANGE_ALREADY_APPLIED`，detail `{appliedAt}`；
   - 非 `APPROVED` → 409 `CHANGE_NOT_APPROVED`，detail `{status}`。
3. `requirementRepository.findWithLockByIdAndTenantId(...)` 取行锁；`requirementService.requireWritable(id)`（终态 → 400 "需求已关闭，不可修改"）。
4. 解析 `before_json/after_json`；`fields = changedFields`；逐字段比对 `before[field]` 与当前快照 `sameValue`，任一不等 → 409 `CHANGE_CONFLICT`，detail `{field, expected, actual}`，**任何写之前**返回。
5. `validator.validateApply(entity, after)`（父链/环/日期/人天/目标版本；目标版本 `CANCELLED` → 400）。
6. `markApplied(id, tenant, "APPROVED", now, actorId)`；`rows != 1` → 409（并发同单）。
7. RELEASE 变更：`membership.move(entity, afterReleaseId, actorId)`（先删旧关联、再插新关联、写 `requirement.release_id`；目标版本不可见/取消在步骤 5 已拦）。
8. 逐字段写需求：title/description/priority/expectedDeliveryDate/estimateDays/parentId/releaseId；`repository.save`；父变则 `require…` 后刷新 rollup（复用 `RequirementService` 的公开刷新入口，或在本服务重算 `RequirementRollup`）。
9. 写 `req_change_item`：每个 changed field 恰 1 行，`before_value/after_value = asText(...)`。
10. 写审计 1 条：`auditService.record("REQUIREMENT_CHANGE_APPLIED", "REQUIREMENT", requirementId, detail)`；`detail = {changeId, fields, before, after}`（`before/after` 为 7 键快照，供 `AutomationEventMapper` 派生字段事件）。
11. 锁定标记：若步骤 7 后需求所属版本含 `LOCKED/PUBLISHED`，再写 `CHANGE_AFTER_RELEASE_LOCK`，`detail = {changeId, releaseId, lockedReleaseIds, statuses}`；`PLAN` 不写。
12. 返回 `detail(changeId)`。

并发与回滚：

- 需求行锁把同一需求的 apply 串行化；两张单改不同字段时，第二张的 before 仍与当前一致 → 成功；改同字段 → 第二张 409。
- 同单第二次 apply：第 2 步或第 6 步拦下；审计/items 不会重复（items 主键兜底）。
- 任一步抛异常 → 整个事务回滚：需求字段、items、审计、变更状态、版本关联全部保持原样（AC-8 零副作用）。
- 捕获 `ObjectOptimisticLockingFailureException`（`requirement` 的 `@Version`）→ 409 `CHANGE_CONFLICT`，detail 带 `retryable:true`。

STALE：apply **不写**评估单；`WbsEstimateService.statusOf` 在下次读时按新 `estimateDays` 派生 `STALE`，`schedule/start_dev` 既有门禁自然 409。

## 8. API 契约

统一前缀 `/api/req`（网关 StripPrefix 后到 `req-service`）；错误体沿用 `ApiException`（`{code,message,detail}`）。

| 方法 | 路径 | 权限规则 | 成功 |
|---|---|---|---|
| POST | `/requirements/{id}/changes` | `req.action.change_request` | 201 `Created` |
| POST | `/requirements/{id}/changes/preview` | `req.action.change_request` | 200 `Preview` |
| GET | `/requirements/{id}/changes?status=&page=1&size=20` | 页面 `req.pool` | 200 `PageSlice<Row>` |
| GET | `/changes/{id}` | 页面 `req.pool` | 200 `Detail` |
| POST | `/changes/{id}/apply` | `req.action.change_apply` | 200 `Detail`（status=APPLIED） |

`CreateRequest` / `PreviewRequest` 同体：

```json
{"changeType":"DAYS","reason":"评估偏差，补 1 天","patch":{"estimateDays":6.0}}
```

`Created`：

```json
{"id":31,"status":"PENDING","instanceId":88,"impact":{...}}
```

`Preview`：`{changeType, before, after, fields, impact}`。

`Row`：

```json
{"id":31,"changeType":"DAYS","status":"APPROVED","reason":"…","createdBy":7,"createdAt":"…",
 "appliedAt":null,"impactSummary":{"children":1,"daysDelta":1.0,"becomesStale":true,
 "releaseLocked":true,"affectedOrderCount":2}}
```

`Detail`：

```json
{"id":31,"requirement":{"id":12,"code":"REQ-012","title":"…","status":"POOLED"},
 "changeType":"DAYS","status":"APPROVED","reason":"…",
 "before":{"title":"…","estimateDays":5.0,...},"after":{...},
 "items":[{"field":"estimateDays","before":"5.0","after":"6.0"}],
 "impact":{...},"instanceId":88,"appliedAt":null,"appliedBy":null,
 "createdBy":7,"createdAt":"…","canApply":true}
```

`canApply` 仅当 `status=APPROVED` 且当前用户 `decide("req.pool","req.action.change_apply") != DENY`。

查询校验：`page>=1`；`1<=size<=100` 否则 400 `VALIDATION_FAILED`；`status` 只接受五态，未知 → 400。列表/详情先 `requireVisible(requirementId)`，跨租户/越权与需求详情同口径（403 或 404）。

错误码（HTTP + code + detail）：

| 触发 | 响应 |
|---|---|
| 未知/越权/跨租户需求 | 404 `NOT_FOUND` 或 403 `OUT_OF_SCOPE`（与详情一致） |
| `changeType` 非法、patch 空、字段越界 | 400 `CHANGE_FIELD_NOT_ALLOWED` |
| 原因空 | 400 `REASON_REQUIRED` |
| 无变化、日期/精度/父链/目标版本取消、需求已关闭 | 400 `VALIDATION_FAILED` |
| 父需求改人天 / 0.25 天 / 非法父子 | 400 `PARENT_DAYS_READONLY` / `INVALID_DAY_PRECISION` / `INVALID_PARENT` |
| 晚期 PUT 四类字段 | 409 `CHANGE_REQUIRED` |
| apply 状态不对 | 409 `CHANGE_NOT_APPROVED` / `CHANGE_ALREADY_APPLIED` |
| apply 字段冲突 | 409 `CHANGE_CONFLICT` |
| 审批人解析为空 | 409 `APPROVER_UNRESOLVED`（引擎原码，F3） |

## 9. 权限

### 9.1 动作码

`platform-contract/src/main/java/com/example/platform/contract/ActionRegistry.java` 增（`req.pool` 页）：

```java
action("req.action.change_request", "req.pool", "发起变更", "req:split");
action("req.action.change_apply",   "req.pool", "应用变更", "req:deliver", "req:split");
```

`functionCodes` 是 any-of：`change_request` 只对 `req:split` 可达者放行（研发受限、产品经理/域负责人可申请）；`change_apply` 对 `req:deliver` 或 `req:split` 任一可达者放行（研发可应用、产品经理可应用）。厂商追加 `ACTION_DENY_OVERRIDES` 两条显式 DENY（11→13），确保两个写接口 403。

### 9.2 路由表（声明式收口，无裸路径）

`PermissionRouteTable` 在 `:154` 的 `/requirements.*` 兜底**之前**插入：

```java
rule("POST", "^/requirements/\\d+/changes$",         action("req.pool", "req.action.change_request")),
rule("POST", "^/requirements/\\d+/changes/preview$", action("req.pool", "req.action.change_request")),
rule("GET",  "^/requirements/\\d+/changes$",         page("req.pool")),
rule("GET",  "^/changes/\\d+$",                      page("req.pool")),
rule("POST", "^/changes/\\d+/apply$",                action("req.pool", "req.action.change_apply")),
```

首匹配优先；`/changes/{id}/apply` 不以 `/requirements` 开头，必须独立规则。`PermissionDeclarationValidator` 启动时据此通过（不新增 `@RequiresAction`，F4）。

### 9.3 可见性（不进矩阵 ACTIONS）

`RequirementActionMatrix.ACTIONS` 保持 16 条（F6）。`RequirementService.changePermissions(entity)` 返回：

```java
Map.of("changeRequest", canRequest, "changeApply", canApply, "editableFields", editableFields)
```

`toView` 把该 Map 填进既有 `View.permissions`（原来恒 `Map.of()`）；`allowedActions` 仍只含 16 个迁移动作。前端只读这三个键，不复刻双依赖或早期态表（L12/L20 约束）。

### 9.4 计数迁移（+2 动作 → 75 / 173 / 1903 / 825）

| 位置 | 旧 → 新 |
|---|---|
| `scripts/tests/rbac/s1-registry-three-way.mjs:162/200` | 73 → 75 |
| `scripts/tests/rbac/s0-schema-migration.sh:2/109/113/122-124/129/158` | 73/171/1881 → 75/173/1903 |
| `scripts/tests/rbac/s2-seed-equivalence.sh:4/7/9/96/152/159/219` | 803 → 825 |
| `scripts/tests/rbac/s5-role-lockout.sh:110/179/211` | 171 → 173 |
| `scripts/tests/rbac/s7-negative-nine.sh:45/48` | 171 → 173 |
| `scripts/tests/rbac/s8-audit-reset.sh:50/190/194` | 73/171 → 75/173 |
| `scripts/tests/rbac/_lib.sh:68/75/77` | 73/171 → 75/173 |
| `scripts/rbac-verify.sh:28/30/38`、`scripts/platform-verify.sh:1131-1135` | 同步 |
| `scripts/platform-demo-reset.sh:709/713/715/716/745/771` | 1881/171 → 1903/173 |
| `RbacSeederTest` / `RbacRegistryTest` / `RbacSeedEquivalenceTest` | 73/171/1881/803 → 75/173/1903/825 |

`FUNCTION 671`、`MENU 407` 不变；`access-verify.sh:7` 的 73 是断言条数，不要改。断言总数不增不减，只改常量。

## 10. 审计与自动化事件

- 创建：`auditService.record("CHANGE_REQUESTED", "REQUIREMENT", requirementId, {changeId, changeType, fields})`，恰 1 条。
- 应用：`REQUIREMENT_CHANGE_APPLIED`，恰 1 条，`detail` 带 `changeId/fields/before/after`。**不写** `UPDATE` 审计（避免两条审计）。
- 锁：`CHANGE_AFTER_RELEASE_LOCK`，恰 1 条，仅当存在 LOCKED/PUBLISHED 版本。
- 自动化：`AutomationEventMapper` 增

```java
case "REQUIREMENT_CHANGE_APPLIED" -> fieldChanges(CHANGE_EVENT_FIELDS, detail);
```

新增独立词表（**不改** `UPDATE_FIELDS`，避免既有 PUT 事件回归）：

```java
CHANGE_EVENT_FIELDS = Map.of(
  "title","title", "description","description", "priority","priority",
  "estimateDays","estimate_days", "releaseId","release_id",
  "parentId","parent_id", "expectedDeliveryDate","expected_delivery_date");
```

`fieldChanges` 复用现有实现（逐字段比对、`sameValue`、`eventId = REQ:<id>:FIELD:<field>:<auditId>`、payload 含 `field/before/after` + 全量快照键）；每实际变化字段恰 1 条事件，title-only 规则命中 1 次 run。`CHANGE_AFTER_RELEASE_LOCK` 无 case，默认不产事件。

`AutomationSpecs.FIELDS` 从 4 个扩到 8 个（7 个可变更字段 + 既有 `owner_user_id`：`title/description/priority/owner_user_id/estimate_days/release_id/parent_id/expected_delivery_date`；其中 title/description/parent_id/expected_delivery_date 为本轮新增），同步：
`web/src/components/AutomationRuleSentence.vue` 的 `map`、`web/src/views/admin/automation/RuleEditorView.vue` 的 `FIELD_LABELS`。规则保存/元数据候选自然带出新字段；不新增表、不改 V15。

## 11. 前端

### 11.1 路由与入口

- `web/src/router/routes/req.routes.ts`：需求详情路由补 `meta: { title, parentPath, pageCode: 'req.pool' }`；新增

```ts
{ path: '/requirements/:id/changes/:changeId', name: 'requirement-change-detail',
  component: () => import('../views/req/RequirementChangeDetailView.vue'),
  meta: { title: '需求变更详情', pageCode: 'req.pool', parentPath: '/requirements' } }
```

零新菜单码、零授权格。深链刷新由既有菜单准入（`meta.pageCode`）保护。

- `RequirementDetailView.vue`：
  - `TAB_KEYS` 在 `approval` 后插 `change`，共 10 个；nav `v-if` 增加 `ChangeTab`；角标 = `PENDING` 总数。
  - 挂载时并行拉 `listChanges(id,{status:'PENDING',size:1})` 与 `listChanges(id,{status:'APPROVED',size:1})`，分别驱动 Tab 角标与「有 N 张已通过待应用」横幅（不新增后端字段）。
  - 头部「发起变更」按钮：`permissions.changeRequest` 为 false 时禁用 + title 原因；点击打开 `ChangeCreateDrawer`。
  - 编辑抽屉：`RequirementEditDrawer.vue`（560px），四组字段（内容/人天/版本/父需求）；`permissions.editableFields` 不含某组 → 该组只读 + 「需发起变更」按钮，预填该组类型打开同一个 `ChangeCreateDrawer`。
  - `handleActionError` 增 `CHANGE_REQUIRED`：展示可操作 banner（含「发起变更」），`detail.fields` 预填类型与字段；不得只 toast。
- `views/approval/ApprovalCenterView.vue`：`REQ_CHANGE` 显式分支（在 `includes('REQ')` 之前）→ 标签「需求变更」；摘要行「需求变更 · `<code>` · `<类型>` · `<字段数>` 项」；点击跳 `/requirements/:requirementId/changes/:id`。

### 11.2 组件与状态

| 文件 | 职责 |
|---|---|
| `web/src/api/req.ts` | 5 个方法：`listRequirementChanges(requirementId, params)`、`getRequirementChange(changeId)`、`createRequirementChange(requirementId, body)`、`previewRequirementChange(requirementId, body)`、`applyRequirementChange(changeId)`；沿用 `http` 封装，409 不 toast |
| `web/src/views/req/change.ts` | 状态/类型标签、`changeEndpoint(id)`、`formatDaysDelta`、`formatRatio`（20.0%–20.1% 带内 2 位小数）、`impactMissing`（缺数据 → 「无法评估」而非 0/—）、`fieldLabel` |
| `ChangeTab.vue` | 列表（状态/类型/原因摘要/影响摘要/申请人/时间）、分页、状态筛选、空态；`emit('changed')` 供父级刷新 |
| `ChangeCreateDrawer.vue` | 720px 四步：类型 → 原因（≤500）→ 字段（只渲染该类型白名单，before→after 实时）→ 影响预览（`preview` 接口）；提交防重复，400/409 字段级错误保留输入；成功文案「已提交审批，尚未生效」 |
| `ChangeDiffTable.vue` | 只列变化字段；值换行不截断；人天行 `before → after（+X%，阈值 ±20%，将/已转 STALE）` |
| `ChangeImpactCards.vue` | 子需求/版本状态/锁定/受影响发布单数/人天预测；缺数据「无法评估」 |
| `RequirementChangeDetailView.vue` | 状态横幅、before/after、影响卡、审批时间线、`APPLY` 二次确认；`canApply` 才整颗渲染；409 `CHANGE_CONFLICT` 高亮 `detail.field`；成功后刷新详情与需求 |
| `RequirementEditDrawer.vue` | 560px 编辑抽屉；四组按 `editableFields` 锁定；锁定组给「需发起变更」+ 预填 |
| `stores/dict.ts` | 增 `CHANGE_STATUS`、`CHANGE_TYPE` 选项（标签唯一来源） |
| `styles/tokens.css` | 9 个布局令牌：`--change-drawer-w:720px`、`--change-drawer-edit-w:560px`、`--change-gutter:24px`、`--change-row-gap:12px`、`--change-label-w:96px`、`--change-badge-size:16px`、`--change-banner-pad:12px 16px`、`--change-bp:1280px`、`--change-value-max:42ch`；0 新色板 |

视觉/文案硬约束：`APPROVED` 一律「已通过 · 待应用」；全站不出现「已生效」；`APPLIED` 才显示应用人/时间；应用按钮在非 `APPROVED` 或无权限时**不渲染**（不是置灰）；1280 以下隐藏申请人列、结果列并入 after。

## 12. 测试策略

### 12.1 单元测试（req-service）

| 测试 | 断言重点 |
|---|---|
| `RequirementChangeFieldsTest` | 四类型白名单；7 键快照；`BigDecimal 2.0 == 2.00`；null/日期/ID 归一；早期态表逐类型 |
| `RequirementChangeValidatorTest` | `CHANGE_FIELD_NOT_ALLOWED` 的 detail；`REASON_REQUIRED`；无变化 400；父需求人天/0.25 天/环；目标版本取消 |
| `RequirementChangeImpactTest` | 固定顶层键；`days` 20%/20.1% 边界；缺评估 `approvedDays=null`；锁定集合；发布单 PASS 过滤 |
| `RequirementChangeServiceTest` | 创建失败（engine 抛）→ 变更单不落库；preview 不写库；重复 PENDING 允许 |
| `RequirementChangeApplyServiceTest` | 未审批/重复/冲突零写；items 行数=fields；锁审计仅 LOCKED/PUBLISHED；RELEASE 双写恰一行；条件更新失败 409 |
| `RequirementChangeCallbackTest` | 仅 PENDING→APPROVED/REJECTED/WITHDRAWN；对 APPLIED 回调不写 |
| `WbsEstimateStaleTest` | 恰好 20% 不转、20.1% 转、下调同判 |
| `AutomationEventMapperTest` | `REQUIREMENT_CHANGE_APPLIED` 每变化字段 1 条；title 命中；锁审计 0 事件 |
| `PermissionRouteTableTest` | 新 5 条规则命中且优先于 `/requirements` 兜底 |
| `RbacSeederTest` 等 | 75/173/1903/825；VENDOR_PM 两码 DENY |

### 12.2 `scripts/change-verify.sh`（≥14 条，6 段）

`RUN_TAG=chg$(date +%y%m%d%H%M%S)`；`PASS==TOTAL` 守卫；失败打印「哪一项 + 怎么修」；结束清理本 RUN_TAG 的需求/变更/审批/审计/自动化事件，残留计数 0；两次连跑结果一致。

| 段 | 断言（至少） |
|---|---|
| S1 迁移与结构 | V16 在 `flyway_schema_history` 且 success；两表列/索引/主键；V1–V15 校验和不变；迁移后 `req_change` 0 行 |
| S2 申请与影响 | 非白名单 400；原因空 400；无变化 400；201 且 `impact` 8 个顶层键；`before/after` 7 键；两张 PENDING 并存；审批实例存在 |
| S3 审批映射 | 两级通过后 `APPROVED` 且需求逐字段不变；驳回原因可查且需求不变；撤回 `WITHDRAWN` |
| S4 应用正负 | 未审批 apply 409；通过后 apply 200、items=fields、审计 1 条；重复 409；同字段冲突 409 且零写；不同字段先后成功 |
| S5 人天与锁 | 恰好 20% 不 STALE、20.1% STALE；`impact.becomesStale == 实际`；LOCKED 版本 apply 200 + `CHANGE_AFTER_RELEASE_LOCK`；PLAN 不写；锁定下 PUT 四类字段 409 `CHANGE_REQUIRED` |
| S6 收口与审计查询 | 列表分页/过滤/total；详情 before/after/items；越权/厂商 403；按 `changeId` 查审计 ≥2 条；title-only 规则 apply 后 1 事件/1 run/1 通知 |

接入：`make change-verify` 薄封装；`scripts/test-all.sh` 在 `platform-verify` 之后加一步 `run_step "需求变更单（change-verify）" bash "$ROOT/scripts/change-verify.sh"`（栈已起、seed 已完成）。

### 12.3 E2E 与门禁

- `web/e2e/req-change.spec.mjs`：沿用 Chrome CDP(9222)+网关 8080，未起 SKIP exit 0；覆盖 Tab 角标、四步抽屉影响预览、提交后「尚未生效」、审批通过后「已通过 · 待应用」、apply 后新值、409 引导预填、深链刷新。
- 既有 8 项门禁：`platform-verify ≥194`、`negative ≥130`、`access ≥206`、`rbac ≥113`、`demo-reset 80/80 ×2`、`release-flow ≥44`、`automation ≥40`、`gate-order ≥20`、`lint ≥22`、`unit ≥447`（只增）。
- 前端：`cd web && npm run typecheck && npm run build` 0 error。

## 13. 任务拆分（按文件边界，T1 先行）

| 任务 | 文件（own） | 读取 | 交付/AC |
|---|---|---|---|
| **T1 数据与领域核心** | `req-service/src/main/resources/db/migration/V16__requirement_change.sql`；`requirement/change/{RequirementChangeEntity,RequirementChangeItemEntity,RequirementChangeRepository,RequirementChangeItemRepository,RequirementChangeFields,RequirementChangeDtos,RequirementChangeViews,RequirementChangeImpactAssessor,RequirementChangeValidator}.java`；`requirement/RequirementErrors.java`；`requirement/WbsEstimateService.java`；`requirement/RequirementRepository.java`；`release/ReleaseRequirementRepository.java`；`seed/DemoFixture.java`；`src/test/.../RequirementChangeFieldsTest.java`、`WbsEstimateStaleTest.java` | `RequirementEntity`、`RequirementRules`、`RequirementRollup`、`ReleaseService`、`ScopedJdbc`、`AdmissionEvaluator` | V16、7 字段快照/白名单/早期态、impact（含 preview 同源）、`wouldBeStale` 同源；AC-1/3/9/11 |
| **T2 创建/查询与审批** | `requirement/change/{RequirementChangeService,RequirementChangeController,RequirementChangeSummaryProvider,RequirementChangeCallback}.java`；`approval/WorkflowVersion.java`；`seed/SeedService.java`；测试 `RequirementChangeServiceTest`、`RequirementChangeCallbackTest`、`RequirementChangeImpactTest` | T1 全部、`ApprovalEngine`、`StartApproval`、`DocumentSummaryProvider`、`AuditService` | 创建（同事务）/preview/列表/详情；REQ_CHANGE 默认两级；回调映射；AC-2/4/5/14 |
| **T3 应用与收口** | `requirement/change/{RequirementChangeApplyService,RequirementChangeApplyController,RequirementChangeGuard,RequirementReleaseMembership}.java`；`requirement/RequirementService.java`；`automation/event/AutomationEventMapper.java`；`automation/spec/AutomationSpecs.java`；`platform-contract/.../ActionRegistry.java`；`platform-common/.../PermissionRouteTable.java`；`req-service/.../seed/RbacSeeder.java`；`RbacSeederTest`、`RequirementChangeApplyServiceTest`、`AutomationEventMapperTest`、`PermissionRouteTableTest` | T1/T2 契约、`ReleaseService`、`ApprovalEngine` 回调、`AuditService`、`RequirementActionMatrix` | apply 事务/冲突/items/审计/锁/RELEASE 双写；PUT 409；动作码/路由/厂商 DENY；AC-6/7/8/10/12/15/16 |
| **T4 前端** | `web/src/api/req.ts`；`web/src/views/req/change.ts`；`web/src/views/req/tabs/ChangeTab.vue`；`web/src/components/req/{ChangeCreateDrawer,ChangeDiffTable,ChangeImpactCards,RequirementEditDrawer}.vue`；`web/src/views/req/RequirementChangeDetailView.vue`；`web/src/views/req/RequirementDetailView.vue`；`web/src/router/routes/req.routes.ts`；`web/src/views/approval/ApprovalCenterView.vue`；`web/src/stores/dict.ts`；`web/src/styles/tokens.css`；`web/src/components/AutomationRuleSentence.vue`；`web/src/views/admin/automation/RuleEditorView.vue`；`web/e2e/req-change.spec.mjs` | T1/T2/T3 的 API/字段契约、`ApprovalTimeline.vue`、`ConfirmDialog.vue`、`usePermission` | 第 10 Tab/角标、四步抽屉、详情深链、409 引导、编辑抽屉锁定、审批中心标签；AC-13/17/18 |
| **T5 验证与文档** | `scripts/change-verify.sh`；`Makefile`；`scripts/test-all.sh`；RBAC/平台计数脚本（§9.4 清单）；`docs/teamflow/DELIVERED-MODULES.md`；`docs/teamflow/REQUIREMENT-FLOW.md`；`docs/teamflow/CHANGE-REQUEST-FLOW.md`；`docs/teamflow/memory.md`（仅同主题行） | T1–T4 契约、`scripts/lib.sh`、既有 verify 风格 | 6 段 ≥14 断言、make 目标、全量门禁、文档台账；AC-19/20/21 |

依赖：T2/T3 依赖 T1；T4 可与 T2/T3 并行（只依赖文档契约）；T5 最后跑。T2 与 T3 的控制器/路由文件互斥，不并行修改同一文件。**git 约束随任务执行**：全程在 `feat/req-change-request`，不新建/不切换分支；不 stash 现有 `docs/teamflow/20261123-r40-req-change-order/`；提交信息含 `tf-mwt1f266-4e4y2h` 与任务夹路径，任务夹不单独提交；`pom.xml`/`web/package.json`/lock diff 为空；只新增 V16；提交前 `git status --porcelain` 仅预期文件。

## 14. 风险与兼容

| 风险 | 对策 |
|---|---|
| 审批通过被误读为已生效 | API 无字段写入；`APPROVED` 文案「已通过 · 待应用」；apply 按钮仅 APPROVED 渲染；详情横幅显式写「尚未应用」 |
| 影响评估与 apply 不一致 | 预测与读时派生共用 `wouldBeStale`；`children/admission/lock` 都从 apply 同一数据源读取；DB 异常不降级 |
| 并发 apply | 需求行锁 + 条件状态更新 + items 主键；冲突 409 不部分写 |
| RELEASE 双写不一致 | 同一事务内先校验、再删旧关联/插新关联/写 `release_id`；`findByTenantIdAndRequirementId` 兜底清理历史重复 |
| 事件重复/漏 | apply 只写 1 条审计；mapper 独立 case；`CHANGE_AFTER_RELEASE_LOCK` 不映射；eventId 含 auditId |
| 既有门禁被动作码计数打断 | §9.4 一次改齐 75/173/1903/825；断言总数不变 |
| demo-reset 残留 | `DELETE_ORDER` 补两表（先 item 后 change），80 步不变 |
| 早期态与前端漂移 | 早期态唯一来源 `editableFields`，后端下发；lint L12 禁止前端复制状态文案 |
| 预览性能 | 4 条聚合查询、无 N+1；P95 <300ms；超时 500 不静默 |

## 15. AC → 交付映射

| AC | 主要落点 |
|---|---|
| AC-1 | V16、`DemoFixture`、`change-verify` S1 |
| AC-2/3/11 | `RequirementChangeValidator`、`RequirementChangeImpactAssessor`、创建接口、S2 |
| AC-4/5 | `WorkflowVersion`、`SeedService`、`RequirementChangeCallback`、`RequirementChangeSummaryProvider`、S3 |
| AC-6/7/8 | `RequirementChangeApplyService`、行锁、条件更新、S4 |
| AC-9/10 | `WbsEstimateService.wouldBeStale`、`RequirementReleaseMembership`、锁审计、S5 |
| AC-12/13 | `RequirementChangeGuard`、`editableFields`、`RequirementEditDrawer`、409 banner |
| AC-14/15 | 列表/详情接口、`ActionRegistry`、`PermissionRouteTable`、`View.permissions`、S6 |
| AC-16 | 一条 apply 审计 + mapper case + `AutomationSpecs.FIELDS`、S6 |
| AC-17/18 | `ChangeTab`、`ChangeCreateDrawer`、`RequirementChangeDetailView`、E2E |
| AC-19/20/21 | `change-verify`、`make test`、全量门禁、三份文档与台账 |

## 16. 交付顺序与命令

```bash
# 1) 数据/领域（T1）
mvn -o -Dmaven.repo.local=$PWD/.m2/repository -pl req-service -am test
# 2) 后端（T2/T3）
mvn -o -Dmaven.repo.local=$PWD/.m2/repository -pl req-service -am test
bash scripts/tests/lint.sh
# 3) 前端（T4）
cd web && npm run typecheck && npm run build
# 4) 集成（T5；先 platform-up + platform-seed）
make change-verify
make platform-demo-reset && make platform-demo-reset
make test
```

完成标准：`change-verify` `PASS==TOTAL` 两次一致；8 项既有门禁不降；文档台账更新；分支/提交按 §13。
