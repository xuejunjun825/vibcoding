# r30 技术方案：发版多级审批链与端到端发版链路

> 输入：`docs/teamflow/20261118-r30-release-approval-chain/PRD.md`（23 条 AC）、`DESIGN.md`（UI/UX 定稿）、`docs/teamflow/memory.md`、`AGENTS.md`。
> 基线：`main = 79e1f1c`，开发分支 `feat/release-multilevel-approval`，技术栈 JDK 25 / Spring Boot 4.0.9 / Spring Cloud 2025.1.4 / MySQL / Vue 3 + TS + Vite。
> 结论：**审批引擎（r26）不改执行语义，只在 `NODE_APPROVED` 留痕上补 `nodeName`**；新增的是「分级绑定 → 解析出 definitionId → 交给现有 `StartApproval` 口子」这一层，以及「四段链路聚合 + 提交快照」这一层。所有判定仍只有一份：门在 deliver，准入在 `AdmissionPolicy`，审批在引擎。

---

## 0. 不变量（实现时不可破坏）

1. **单一判定源**：`flowChain.qualityGate` 只透传 deliver 的 `result/failedRules/waivers`；`deployAdmission` 只透传 `AdmissionPolicy` 的 9 项。`req-service` 与 `web` 都不得出现第二套门规则或第二套 9 项顺序（AC-9 静态检索）。
2. **发布只有一条出口**：不存在 `POST /releases/{id}/publish`。PUBLISHED 只能由 `INSTANCE_APPROVED` 回调 `ReleaseApprovalCallback → ReleaseService.publish` 产生（AC-14）。
3. **待办只在当前节点生成**：引擎 `advance/addTasks` 的现有语义不动；任何“一次建全部节点待办”的改动都是 P0 缺陷（AC-1/AC-16）。
4. **在途实例钉版本**：实例的 `definition_id/definition_version` 在发起时写入，之后绑定怎么改都不影响在途；绑定只在“下一次发起”生效（AC-4/AC-6）。
5. **提交是唯一写快照的时刻**：`DeployOrderService.submit` 成功路径内、同一事务、状态进入 `PENDING_APPROVAL` 的同一次写入；被 409/400/503 拒绝时零写入（AC-11）。
6. **详情聚合 fail-soft，提交闸门 fail-closed**：链路取不到某段时返回 `status:"MISSING"+reason`，绝不当“通过”；但 `submit` 遇到计划/门不可用一律 503/409，绝不降级放行。
7. **不新增权限码、不新增导航项、不改 `pom.xml` / `web/package.json`**：新绑定页复用 `workflow:manage`（AC-21 的 `rbac=113`、`access=206` 基线不变）。

---

## 1. 改动面总表

### 1.1 后端（req-service）

| 文件 | 类型 | 作用 |
|---|---|---|
| `req-service/src/main/resources/db/migration/V14__release_flow_chain.sql` | 新增 | release 等级/驳回字段 + `wf_flow_binding` + `deploy_flow_snapshot` |
| `req-service/src/main/java/com/example/req/release/ReleaseLevel.java` | 新增 | 三值枚举语义、标签、`normalise` |
| `req-service/src/main/java/com/example/req/release/ReleaseLevelValidator.java` | 新增 | AC-7 字段校验（原因长度、回滚单号可见性与状态） |
| `req-service/src/main/java/com/example/req/release/FlowBindingEntity.java` | 新增 | `wf_flow_binding` 实体 |
| `req-service/src/main/java/com/example/req/release/FlowBindingRepository.java` | 新增 | 按租户/类型/等级查询 |
| `req-service/src/main/java/com/example/req/release/FlowBindingService.java` | 新增 | 6 槽读取/保存/恢复默认/解析 definitionId |
| `req-service/src/main/java/com/example/req/release/FlowBindingController.java` | 新增 | `GET/PUT/DELETE /admin/flow-bindings` |
| `req-service/src/main/java/com/example/req/release/FlowChainViews.java` | 新增 | `flowChain` 四段、`approvalLevels`、快照视图的 record 契约 |
| `req-service/src/main/java/com/example/req/release/FlowChainService.java` | 新增 | 四段聚合 + 段 digest（唯一聚合点） |
| `req-service/src/main/java/com/example/req/integration/DeliverFlowEvidenceClient.java` | 新增 | 调 deliver 唯一内部聚合端点 |
| `req-service/src/main/java/com/example/req/release/FlowSnapshotEntity.java` | 新增 | `deploy_flow_snapshot` 实体 |
| `req-service/src/main/java/com/example/req/release/FlowSnapshotRepository.java` | 新增 | 按单据/轮次读取 |
| `req-service/src/main/java/com/example/req/release/FlowSnapshotService.java` | 新增 | 固化、轮次、回放、段级 stale |
| `req-service/src/main/java/com/example/req/approval/ApprovalLevelProjector.java` | 新增 | 实例 + 定义 + 任务 + 历史 → `approvalLevels[]` |
| `req-service/src/main/java/com/example/req/approval/ApprovalEngine.java` | 改动 | `NODE_APPROVED.detail` 补 `nodeName`；确认无管理员代批旁路 |
| `req-service/src/main/java/com/example/req/seed/FlowChainSeedService.java` | 新增 | 4 条分级定义（RELEASE 三级 + 一条 REQ_DEPLOY 复用）+ 6 个默认绑定（幂等） |
| `req-service/src/main/java/com/example/req/seed/SeedService.java` | 改动 | 末尾调用分级种子（AC-4 的 `make` 幂等升级入口） |
| `req-service/src/main/java/com/example/req/release/ReleaseService.java` | 改动 | create/updateLevel、startApproval 走绑定、detail 装 flowChain/levels/驳回字段、驳回回填 |
| `req-service/src/main/java/com/example/req/release/ReleaseController.java` | 改动 | `PATCH /releases/{id}/level`、发起审批返回定义信息 |
| `req-service/src/main/java/com/example/req/requirement/RequirementViews.java` | 改动 | `ReleaseDetail` 增 `releaseLevel`/`approvalLevels`/`flowChain`/驳回字段 |
| `req-service/src/main/java/com/example/req/deploy/DeployOrderService.java` | 改动 | submit 闸门顺序 + 快照固化 + 详情装 levels/快照 |
| `req-service/src/main/java/com/example/req/deploy/DeployViews.java` | 改动 | `OrderDetail` 增 `releaseLevel`/`approvalLevels`/`flowSnapshot`/`flowSnapshotHistory` |
| `req-service/src/main/java/com/example/req/deploy/DeployController.java` | 改动 | 快照回放/历史两个只读路由 |

### 1.2 后端（deliver-service）

| 文件 | 类型 | 作用 |
|---|---|---|
| `deliver-service/src/main/java/com/example/deliver/quality/internal/FlowEvidenceService.java` | 新增 | 批量取计划/报告签署/门最新快照 |
| `deliver-service/src/main/java/com/example/deliver/quality/internal/FlowEvidenceController.java` | 新增 | `GET /internal/releases/{releaseId}/flow-evidence` |

### 1.3 前端（web）

| 文件 | 类型 | 作用 |
|---|---|---|
| `web/src/api/types.ts` | 改动 | `FlowChain`/`ApprovalLevel`/`FlowSnapshot`/`ReleaseDetail`/`DeployOrderDetail` 类型 |
| `web/src/api/req.ts` | 改动 | `getDeployFlowSnapshot`、`updateReleaseLevel` |
| `web/src/api/admin.ts` | 改动 | `listFlowBindings`/`saveFlowBinding`/`resetFlowBinding` |
| `web/src/components/release/FlowChainPanel.vue` | 新增 | 四段传送带（只消费 `flowChain`） |
| `web/src/components/release/ApprovalLevelList.vue` | 新增 | 侧栏逐级阶梯 |
| `web/src/components/release/FlowSnapshotCard.vue` | 新增 | 发布单回执卡 + 轮次回放 |
| `web/src/views/release/ReleaseDetailView.vue` | 改动 | 概览页签顶部挂链路面板、侧栏挂等级/审批进度、等级编辑弹窗 |
| `web/src/views/deploy/DeployOrderDetailView.vue` | 改动 | 审批进度 + 快照卡 |
| `web/src/views/admin/AdminFlowBindingsView.vue` | 新增 | 6 槽绑定页 |
| `web/src/router/routes/deliver.routes.ts` | 改动 | `admin/flow-bindings` 路由（复用 `workflow:manage`，无新页面码/导航项） |

### 1.4 脚本与文档

| 文件 | 类型 | 作用 |
|---|---|---|
| `scripts/release-flow-verify.sh` | 新增 | 32 条断言、S1–S6 六段门禁（AC-20） |
| `scripts/release-flow-upgrade.sh` | 新增 | 幂等升级入口（连跑两次 seed，断言计数不增） |
| `Makefile` | 改动 | `release-flow-verify`、`release-flow-upgrade` 两个薄封装 + help |
| `docs/teamflow/DEMO-RELEASE-FLOW.md` | 新增 | 8 步正常链 + 被拦链（AC-17） |

---

## 2. 数据模型与存储（V14）

只新增 `V14__release_flow_chain.sql`，**不修改 V1–V13**。

### 2.1 `release` 扩列

```sql
ALTER TABLE `release`
  ADD COLUMN release_level            VARCHAR(16) NOT NULL DEFAULT 'NORMAL'  COMMENT 'NORMAL/EMERGENCY/ROLLBACK',
  ADD COLUMN emergency_reason         VARCHAR(500) NULL,
  ADD COLUMN previous_deploy_order_code VARCHAR(24) NULL,
  ADD COLUMN rejected_node_key        VARCHAR(32) NULL,
  ADD COLUMN rejected_node_name       VARCHAR(64) NULL,
  ADD COLUMN rejected_by              BIGINT NULL,
  ADD COLUMN rejected_at              DATETIME(3) NULL,
  ADD COLUMN reject_reason            VARCHAR(512) NULL;
```

- 默认 `NORMAL`，存量升级即兼容；`emergency_reason/previous_deploy_order_code` 只在对应等级写值，切走时置 NULL（避免“改了等级还带着旧原因”）。
- 驳回五列由 `ReleaseService.onApprovalRejected` 从实例历史最后一条 `REJECT` 回填；重提发起时清空（AC-3 详情字段 + 新一轮语义）。
- 不加索引：版本查询走主键/已有 `idx_release_domain`，等级不参与筛选。

### 2.2 `wf_flow_binding`（6 槽，唯一键即槽位）

```sql
CREATE TABLE IF NOT EXISTS `wf_flow_binding` (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT UNSIGNED NOT NULL,
  biz_type      VARCHAR(32) NOT NULL COMMENT 'RELEASE / REQ_DEPLOY',
  release_level VARCHAR(16) NOT NULL COMMENT 'NORMAL/EMERGENCY/ROLLBACK',
  definition_id BIGINT UNSIGNED NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by    BIGINT UNSIGNED NULL,
  updated_by    BIGINT UNSIGNED NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_flow_binding (tenant_id, biz_type, release_level),
  KEY idx_flow_binding_def (definition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- **无绑定行 = 未配置**，解析时回退到该 `bizType` 的既有默认定义（老单节点），再回退到“按 bizType 取任一 PUBLISHED”，仍无 → 409 `WORKFLOW_NOT_CONFIGURED`。这保证老库不执行 V14 的数据迁移也有链可用，同时“恢复默认”只需删行（回到 AC-1 绑定由种子保证存在，删行后由默认解析补上；种子在 `/internal/seed` 再次执行时会把 6 槽写回，见 §3.2）。
  - 取舍：`reset` 不写“默认定义 id”的快照，而是删行。理由：AC-1 的默认链本身就是“seed 出来的绑定”，删行后解析走同一份默认定义解析逻辑，只有一个真相；若把默认 id 抄进表，后续换默认链就出现两份配置。

### 2.3 `deploy_flow_snapshot`（不可变，只插不改）

```sql
CREATE TABLE IF NOT EXISTS `deploy_flow_snapshot` (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id          BIGINT UNSIGNED NOT NULL,
  deploy_order_id    BIGINT UNSIGNED NOT NULL,
  release_id         BIGINT UNSIGNED NOT NULL,
  round_no           INT NOT NULL,
  release_level      VARCHAR(16) NOT NULL,
  definition_id      BIGINT UNSIGNED NULL,
  definition_version INT NULL,
  node_keys          VARCHAR(256) NULL COMMENT 'n1,n2,n3',
  payload_json       MEDIUMTEXT NOT NULL COMMENT '四段 + 元数据的规范化 JSON',
  payload_hash       CHAR(64) NOT NULL COMMENT 'payload_json 的 SHA-256（小写 hex）',
  captured_at        DATETIME(3) NOT NULL,
  created_by         BIGINT UNSIGNED NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_flow_snapshot_round (tenant_id, deploy_order_id, round_no),
  KEY idx_flow_snapshot_release (tenant_id, release_id, captured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- 唯一键 `(tenant, deploy_order_id, round_no)` 是轮次连续性的物理保证：并发双提交只有一条能插入，另一条 `DataIntegrityViolationException` 回滚整个 submit（不会出现 1,3 空洞）。
- 不写 `updated_at/updated_by`，仓库层只有 `save`（insert）与两个读方法；**代码里不存在 UPDATE/DELETE 该表的路径**（AC-11 静态审计点）。

### 2.4 升级幂等（AC-4）

- 结构：Flyway `V14` 只做 DDL，天然一次。
- 数据：`FlowChainSeedService.seed()` 用既有 `ensure(table, keys, extra)` 语义（命中即返回、不改行）写 4 条定义（RELEASE NORMAL/EMERGENCY/ROLLBACK 各一条 + 一条 REQ_DEPLOY，被三个 REQ_DEPLOY 等级共用）+ 6 个绑定；`POST /internal/seed` 末尾调用，`make release-flow-upgrade → scripts/release-flow-upgrade.sh` 连跑两次并断言：定义数、绑定槽数、各定义 `version` 均不增。
- 老定义（RELEASE/REQ_DEPLOY 的单节点行）**不删不覆盖**：它们是不同 `id`，还能被 `PUT /admin/flow-bindings/...` 绑上；绑定后一级通过即 `INSTANCE_APPROVED`（引擎本来就支持单节点）。
- 在途实例：`wf_instance.definition_id/definition_version` 不动，引擎按 `definitionId` 读旧 JSON 走完。

---

## 3. 后端设计

### 3.1 等级与字段校验（AC-5 / AC-7 / AC-15）

`ReleaseLevel`（`release` 包，纯静态）：

```java
public static final String NORMAL = "NORMAL", EMERGENCY = "EMERGENCY", ROLLBACK = "ROLLBACK";
public static boolean isValid(String level);          // 严格三值
public static String normalise(String raw);           // trim + upper；null/空 → NORMAL
public static String label(String level);             // 常规 / 紧急修复 / 回滚
```

`ReleaseLevelValidator.validate(long tenantId, String level, String emergencyReason, String previousDeployOrderCode)`：

- 非法枚举 → 400 `VALIDATION_FAILED`，`detail.field=releaseLevel`（AC-5）。
- `EMERGENCY`：`emergencyReason == null || trim().isEmpty()` 或 `trim().length() > 500` → 400 `detail.field=emergencyReason`。
- `ROLLBACK`：`previousDeployOrderCode` 空 → 400 `detail.field=previousDeployOrderCode`；按 `(tenant, code)` 查 `deploy_order`，不存在/不可见 → 400（同字段）；状态 ∉ {`APPROVED`,`SUCCESS`} → 400（同字段，`detail.status=实际值`）。
- `NORMAL`：忽略两个字段（不校验、不落值）。
- 校验发生在**写库前**：`create` 与 `PATCH /releases/{id}/level` 都先校验再写；`startApproval` 再校验一次（AC-7 要求两处），失败即 409/400 且版本行不变（`@Transactional` 保证）。

可见性：复用 `ReleaseService.requireVisible` 同一套 `DataScope`/租户过滤；回滚单号查询直接走 `DeployOrderRepository.findByTenantIdAndCode` + `requireVisible(order)`，不新造权限口径。

`PATCH` 的编辑窗口：

- `status != PLAN` 或 `locked_at != null` 或存在在途实例 → 409 `RELEASE_LOCKED`（复用 `RequirementErrors.releaseLocked()`）。
- 成功 → 写 `release` + `audit_log`（`before/after` 的 `releaseLevel/emergencyReason/previousDeployOrderCode` 三键 JSON）→ 返回最新 `ReleaseRow`。

### 3.2 六槽绑定与解析（AC-1 / AC-6 / AC-4）

`FlowBindingService`：

```java
public List<FlowChainViews.BindingSlot> slots(long tenantId);                    // 恒 6 条，未绑定时 definitionId=null
public FlowChainViews.BindingSlot save(long tenantId, String bizType, String level, long definitionId);
public FlowChainViews.BindingSlot reset(long tenantId, String bizType, String level);
public Long resolveDefinitionId(long tenantId, String bizType, String level);     // null = 未配置，调用方回退
```

- `slots`：一次读 `wf_flow_binding` + 一次批量读 `wf_definition`（`IN`），拼 `definitionName/status/nodeSummary(3 个节点名)/isDefault`。`isDefault` 的判定 = 该槽定义 id 等于“本 bizType 的默认绑定目标”（`FlowChainSeedService` 的常量映射），不是“表里没行”。
- `save` 校验：`bizType ∈ {RELEASE, REQ_DEPLOY}`、`level ∈ 三值`；定义必须同租户、`status=PUBLISHED`、`biz_type` 相同；否则 400 `BINDING_INVALID`（跨类型/不存在）或 409 `DEFINITION_NOT_PUBLISHED`。保存 upsert + `audit_log`。**不改在途实例**（实例已钉 `definition_id`）。
- `reset`：删除该槽行（无行即“恢复默认”），返回重新解析后的槽。种子再次执行会写回默认绑定，两条路径结果一致。
- 解析顺序（`resolveDefinitionId` 的调用方 `ReleaseService.startApproval` / `DeployOrderService.startApproval`）：
  1. 槽位绑定行 → 该 `definition_id`；
  2. 该 `bizType` 的默认定义（按 `FlowChainSeedService.DEFAULTS` 的名字查找，且必须 PUBLISHED）；
  3. 该 `bizType` 下任一 PUBLISHED（`ORDER BY is_default DESC, version DESC`）；
  4. 全无 → 409 `WORKFLOW_NOT_CONFIGURED`。
- 把解析出的 `definitionId` 传给 `engine.start(new StartApproval(..., definitionId, initiatorId))`——这是 r26 已有的口子，**引擎的 `resolveWorkflow` 不需要改匹配逻辑**，也天然满足“老单节点定义仍可绑定”。

默认链定义（`FlowChainSeedService`，节点 key 固定 `n1/n2/n3`，`mode` 见括号）：

| bizType | 等级 | 定义名 | 节点链 |
|---|---|---|---|
| RELEASE | NORMAL | 版本发布-常规三级 | 产品经理会签(PRODUCT/ALL) → PMO 会签(PMO/ALL) → 管理层(MGMT/ANY) |
| RELEASE | EMERGENCY | 版本发布-紧急两级 | 产品经理会签 → 管理层 |
| RELEASE | ROLLBACK | 版本发布-回滚两级 | PMO 会签 → 平台管理员(PLATFORM_ADMIN/ALL) |
| REQ_DEPLOY | NORMAL/EMERGENCY/ROLLBACK | 发布单审批-两级 | PMO 会签 → 平台管理员 |

> 三个 REQ_DEPLOY 槽默认指向同一条两级定义（AC-1 允许“三级同链”，槽仍可独立改）。定义 JSON 用现有 `WorkflowDefinition` 结构（`schemaVersion=1`、`start="n1"`、末节点 `next="end"`），由 `WorkflowJson` 序列化后写入 `wf_definition.definition_json`，`status=PUBLISHED`、`is_default=1`、`version=1`。

### 3.3 逐级审批与 `approvalLevels`（AC-1 / AC-2 / AC-3 / AC-16）

**引擎只改一处**：`advance` 里 `NODE_APPROVED` 的 detail 由 `Map.of("nodeKey",..., "mode",...)` 改为 `Map.of("nodeKey", key, "nodeName", node.displayName(), "mode", mode)`（AC-2 要三键；已有历史行不补写，QA 只断言新链）。同时审计 `approve()`：若存在“管理员代批任意节点”的分支必须删除（AC-16 平台管理员也不能代批非本人节点）；现状 `assertAssignee` 在写历史之前，天然保证 403 时任务仍 `PENDING`、`wf_history` 无 `APPROVE`。

`ApprovalLevelProjector`：

```java
public List<FlowChainViews.ApprovalLevel> project(long instanceId);
```

一次取 `instance`、`definition`（by id + version，走 `WorkflowRegistry.byId`）、`tasks(instanceId)`、`history(instanceId)`（读多写零，批量 4 次查询，不做 N+1）。状态推导：

- 节点在历史里有 `NODE_APPROVED` → `APPROVED`（`handledAt` 取该历史时间，`comment` 取该节点最后一条 `APPROVE` 的 comment）。
- 节点是 `instance.currentNode` 且实例 `status=PENDING` → `ACTIVE`；该节点内有 `REJECT` 历史 → `REJECTED`。
- 其余 → `WAITING`（`approvers` 为空数组，不预解析角色成员，避免详情页触发权限查询；UI 在 `WAITING` 行只说“待到达”）。
- `approvers`：该节点已建任务的所有 `approverId`（去重、升序）；`mode` 取定义节点 `mode`；`nodeName` 取定义 `name`，缺省退 key。
- 顺序 = 定义节点顺序，长度 = 定义节点数（AC-2 的“无缺失/重复”）。

挂载点：

- `ReleaseService.detail` → `approvalLevels` = 当前 `activeInstance` 的投影；无在途实例且有最后一次实例 → 投影最后一次（驳回后仍有历史可读）；从未发起 → `[]`。
- `DeployOrderService.detail` 同理，按 `bizType="REQ_DEPLOY"`。

**越级/越权**：不新增入口。AC-16 的“mgr1 对 n2 待办 approve → 403”走现有 `POST /approvals/tasks/{taskId}/actions`：n2 的 task 存在但 `approverId != mgr1` → `assertAssignee` 403 `FORBIDDEN`。n1 未完成时 n2 根本没有 task（引擎只在当前节点建待办），调用方拿到 404，这与 AC-16 的“n2/n3 无待办”一致，无需额外接口。

### 3.4 `flowChain` 四段聚合（AC-8 / AC-9）

**一次跨服务调用，不是四次。** deliver 新增唯一内部只读端点：

```
GET /internal/releases/{releaseId}/flow-evidence?requirementIds=1,2,3
```

返回 `{testPlan, signoff, qualityGate}` 三段原始事实（deliver 自己拥有计划/报告/门）。`req` 的 `DeliverFlowEvidenceClient`（预算 800ms，走 `InternalTokenClient.deliver`，自动带 `X-Tenant-Id`）解析成 `Map`，**不绑定 deliver 内部 record**；取不到 → 3 段全 `MISSING`，reason=`EVIDENCE_UNAVAILABLE`，详情仍 200。

deliver 侧 `FlowEvidenceService`（一次请求内批量，禁止循环单查）：

- `testPlan`：`SELECT ... FROM test_plan WHERE tenant_id=? AND release_id=?`（`/*scope:p*/`），取每张计划最新一轮 `test_plan_round` 的 `conclusion`（一条 `LEFT JOIN` 取 `MAX(round_no)`），按 `code` 去重；**覆盖该版本需求的计划**：`requirementIds` 非空时与 `test_plan_requirement` 求交，空则退化为“版本下全部计划”。
  - `total=plan 数`，`approved = status=='APPROVED' 的数`，`pending = status ∈ {DRAFT,PENDING_REVIEW} 的数`，`allApproved = total>0 && approved==total`。
- `signoff`：最新一份 `scope='RELEASE' AND release_id=?` 报告（`ORDER BY created_at DESC, id DESC LIMIT 1`），`SignoffService.progress(reportId, versionNo)` 直接给 `status/conclusion/approved/total/pendingRole/steps`；`levels` = `steps` 映射为 `{orderNo,signRole,roleName,status,signerName,signedAt}`。
- `qualityGate`：`quality_gate_snapshot` 按 `release_id` 最新一条（**只读已固化快照，绝不触发 evaluate**），读不到 → 404 `GATE_NOT_EVALUATED`（由 req 转 MISSING）。`failedRules` 只取 `!passed()` 的 `{code,status,actual,threshold,fixHint}`；`waivers` 原样透传 `rules_json` 里的已批豁免列表。

`req` 侧 `FlowChainService.assemble(long releaseId)` 合并第四段与元数据：

- `deployAdmission`：该版本**最新发布单**（`ORDER BY id DESC LIMIT 1`，不限状态）；无单 → `MISSING`/`DEPLOY_ORDER_NOT_FOUND`。
  - `status ∈ {DRAFT}` → `AdmissionEvaluator.evaluate(order)` 实时评估（不写 `admission_result_json`），`frozen=false`；
  - 已提交（`PENDING_APPROVAL/APPROVED/...`）→ `AdmissionEvaluator.snapshot(order)` 读冻结快照，`frozen=true`；
  - 评估不可用（gate 抛 `QualityGateUnavailable`）→ `MISSING`/`GATE_UNAVAILABLE`（详情不 5xx）。
  - `items` 直接放 `AdmissionItem` 列表（9 项，固定顺序），`passedCount/failedCount` 由 `AdmissionCounts` 给出。
- `status` 段语义（DESIGN §4 判定表的唯一实现）：`MISSING` 无数据；`PASS` 放行；`FAIL` 明确不过；`PENDING` 还在等（计划待批、签署未齐、发布单草稿）。**任何情况都不把缺段当 PASS。**
- `digests`：对每段用 `ObjectMapper`（`ORDER_MAP_ENTRIES_BY_KEYS` + `SORT_PROPERTIES_ALPHABETICALLY`）序列化后 `SHA-256`，输出 16 位 hex 片段（只用于 stale 比较，不对外承诺算法细节之外的东西）。digest 在**这里算一次**，快照捕获与详情读都复用它。

聚合性能：`FlowChainService` 只做 1 次 deliver HTTP + 1 次发布单查询 + 1 次投影的 4 条小查询；计划/报告/门都在 deliver 内部一次批量完成（PRD §7 的 N+1 要求）。

### 3.5 提交快照（AC-10 / AC-11）

`FlowSnapshotService`：

```java
@Transactional
public FlowChainViews.FlowSnapshotView capture(DeployOrderEntity order, FlowChainViews.FlowChain chain,
                                                Long definitionId, Integer definitionVersion,
                                                List<String> nodeKeys, Long actorId);
public List<FlowChainViews.FlowSnapshotSummary> history(long deployOrderId);
public FlowChainViews.FlowSnapshotView latest(long deployOrderId);      // 无快照 → null
public FlowChainViews.FlowSnapshotView replay(long deployOrderId, int round);  // 无该轮 → 404 SNAPSHOT_NOT_FOUND
```

- `round_no` = `MAX(round_no)+1`（同单据），插入；唯一键兜底并发。
- `payload_json` 结构（即回放内容）：
  ```json
  {"round":1,"capturedAt":"...","releaseLevel":"NORMAL","definitionId":31,"definitionVersion":1,
   "nodeKeys":["n1","n2","n3"],"segments":{ "testPlan":{...},"signoff":{...},"qualityGate":{...},"deployAdmission":{...} },
   "digests":{"testPlan":"ab12...","signoff":"...","qualityGate":"...","deployAdmission":"..."}}
  ```
- `payload_hash` = 上述 JSON 规范序列化后的 SHA-256 全 hex（AC-10 逐字节不变：只读、无再序列化回写）。
- 回放/详情 `segmentStale`：读当前链的 `digests` 与快照 `digests` 逐段比较；`changedSinceSubmit = 任一不同`。前端不自行比较（DESIGN §4）。快照内 `segments` 永远原样返回，不叠加当前值。
- 捕获时机：`DeployOrderService.submit` 成功路径里，`startApproval` 之后、状态写 `PENDING_APPROVAL` 同一事务。`StartResult` 提供 `definitionId/definitionVersion/nodeKeys`（`WorkflowDefinition` 的节点 key 顺序）。
- 拒绝路径：submit 的 4 类异常（`AdmissionFailed/QualityGateFailed/SkipNotAcknowledged/PlanNotApproved`）在 `noRollbackFor` 下不回滚已写内容，因此**快照必须在这些闸门之后才调 `capture`**；AC-11 的“拒绝不写快照、轮次不变”由调用顺序保证，并由唯一键兜底。
- 只读性：`FlowSnapshotRepository` 只暴露 `find...` 与 `save`；`DeployController` 只有 GET 两个快照路由。静态审计 grep 该表的 `UPDATE/DELETE` 应为 0。

### 3.6 发布单提交闸门顺序（AC-12 / AC-13 / AC-11）

`DeployOrderService.submit` 调整为（顺序即契约）：

```
1) canSubmit（状态/权限）
2) requireNoGoConfirmed
3) planGuard.requireApproved(releaseId, requirementIds)      ← 新增；409 PLAN_NOT_APPROVED(detail.planIds) / 503
4) 版本必须 LOCKED（AC-14：驳回回 PLAN 后不可提交）        ← 新增；409 RELEASE_NOT_LOCKED
5) admissionEvaluator.evaluateAndStore（门判定 + 9 项）
6) requireSkippedAcknowledged
7) gate 非 passed → 409 QUALITY_GATE_FAILED(snapshotId, failedRules)
8) 全 PASS 或 forced，否则 409 ADMISSION_FAILED
9) snapshot = flowSnapshotService.capture(...)               ← 新增，四段来自 chainService.assemble
10) startApproval（绑定解析 → definitionId）
11) status=PENDING_APPROVAL + audit
```

- 第 3) 步在 5) 之前，满足 AC-12“不写快照、不提交”；第 9) 步在 10) 之后，保证快照里带得到 `definitionVersion/nodeKeys`，且提交失败时轮次不增。
- `flowChain` 在 capture 时重新 `assemble` 一次（而不是复用前端传参），保证快照是服务端事实；第 5) 步已经固化门快照，assemble 读到的就是同一份。
- `PlanNotApprovedException` 需加入 `noRollbackFor`（或复用已有 `PlanCheckUnavailable`），否则 409 会把审计回滚掉——审计不是本 AC 的要求，但不回滚更符合“失败也要留痕”的既有风格；这里选择**保留现有风格**：计划未批不写审计、只回滚，简单且无副作用。

### 3.7 发布 / 驳回 / 重提（AC-3 / AC-14）

- `ReleaseService.publish` 不改：仅 `LOCKED` 可发；只由 `ReleaseApprovalCallback.onApproved` 调用。
- `onApprovalRejected`：写 `status=PLAN, locked_at=null` + 驳回五列（从 `wf_history` 最后一条 `REJECT`：`nodeKey/nodeName/operatorId/createdAt/comment`）+ 审计 `RELEASE_APPROVAL_REJECT`；不删成员/里程碑。
- 重提：`startApproval` 先清空驳回五列，再 `engine.start` 建新实例（引擎 `activeInstance` 为空才允许；旧实例已 `REJECTED`，不会复活）。发起时再走一次 `ReleaseLevelValidator`（AC-7 第二处）。
- 驳回后重复调用 `POST /releases/{id}/approval`：在途实例存在（LOCKED）→ 现有 `requireEditable` 409 `RELEASE_LOCKED`，不会产生第二个实例（AC-14）。
- 发布单驳回：`DeployOrderService` 的审批回调已有 `REJECTED` 语义，不改；驳回后的单据在 `submit` 第 4) 步因版本 `PLAN` 被 409。

---

## 4. API 契约

### 4.1 版本（req-service，网关 `/api/req/**`）

**`GET /releases/{id}` → 200 `ReleaseDetail`**（在原字段上新增，其余不变）：

```json
{
  "release": {"id":7,"code":"REL-...","name":"...","status":"LOCKED","releaseLevel":"NORMAL","releaseLevelLabel":"常规",
              "emergencyReason":null,"previousDeployOrderCode":null},
  "requirements": [], "groups": [], "stats": {}, "riskItems": [], "milestones": [],
  "activeInstanceId": 41,
  "approvalLevels": [
    {"index":1,"nodeKey":"n1","nodeName":"产品经理会签","mode":"ALL","status":"APPROVED",
     "approvers":[3],"handledAt":"2026-11-18T13:02:11","comment":"同意"}
  ],
  "rejectedNodeKey": null, "rejectedNodeName": null, "rejectedBy": null,
  "rejectedAt": null, "rejectReason": null,
  "flowChain": {
    "releaseLevel":"NORMAL",
    "testPlan":{"status":"PASS","reason":null,"total":1,"approved":1,"pending":0,"allApproved":true,
                "plans":[{"code":"TP-20261118-01","status":"APPROVED","conclusion":"PASS"}]},
    "signoff":{"status":"PASS","reason":null,"reportId":12,"versionNo":2,"conclusion":"PASS",
               "levels":[{"orderNo":1,"signRole":"TEST_LEAD","roleName":"测试负责人","status":"APPROVED","signerName":"测试-孙","signedAt":"..."}],
               "approved":3,"total":3,"pendingRole":null},
    "qualityGate":{"status":"PASS","reason":null,"result":"PASS","snapshotId":9,"gateId":3,"reportId":12,
                   "computedAt":"...","failedRules":[],"waivers":[]},
    "deployAdmission":{"status":"PASS","reason":null,"deployOrderId":7,"code":"DO-...","orderStatus":"DRAFT",
                       "frozen":false,"items":[/* 9 项 AdmissionItem */],"passedCount":9,"failedCount":0},
    "digests":{"testPlan":"a1b2c3d4e5f60718","signoff":"...","qualityGate":"...","deployAdmission":"..."}
  }
}
```

**`PATCH /releases/{id}/level`** body `{releaseLevel, emergencyReason?, previousDeployOrderCode?}` → 200 `ReleaseRow`；401/403/404/409 `RELEASE_LOCKED`/400 `VALIDATION_FAILED(detail.field)`。

**`POST /releases/{id}/approval`** → 200 `{instanceId, releaseId, definitionId, definitionVersion, nodeKeys}`；409 `RELEASE_LOCKED`/`WORKFLOW_NOT_CONFIGURED`。

**`POST /releases`** 新增可选 `releaseLevel/emergencyReason/previousDeployOrderCode`；`GET /releases` 行增 `releaseLevel/releaseLevelLabel`。

### 4.2 发布单（req-service）

**`GET /deploy-orders/{id}`（`OrderDetail`）新增**：

```json
{"releaseLevel":"NORMAL","approvalLevels":[...],
 "flowSnapshot":{"round":1,"capturedAt":"...","releaseLevel":"NORMAL","definitionId":31,"definitionVersion":1,
                 "nodeKeys":["n1","n2"],"chain":{/* 四段原样 */},"payloadHash":"<64hex>",
                 "segmentStale":{"testPlan":false,"signoff":true,"qualityGate":false,"deployAdmission":false},
                 "changedSinceSubmit":true},
 "flowSnapshotHistory":[{"round":1,"capturedAt":"...","payloadHash":"<64hex>"}]}
```

**`GET /deploy-orders/{id}/flow-snapshots/{round}`** → 200 同上 `flowSnapshot`（`segmentStale` 按当前链计算）；404 `SNAPSHOT_NOT_FOUND`。
**`GET /deploy-orders/{id}/flow-snapshots`** → `FlowSnapshotSummary[]`（升序）。

`POST /deploy-orders/{id}/submit` 错误码：409 `PLAN_NOT_APPROVED(detail.planIds)`、409 `RELEASE_NOT_LOCKED`、409 `QUALITY_GATE_FAILED(detail.snapshotId,failedRules)`、409 `ADMISSION_FAILED`、503（plan/gate 不可用）。

### 4.3 绑定（req-service，管理员）

**`GET /admin/flow-bindings`** → 6 条：

```json
[{"bizType":"RELEASE","releaseLevel":"NORMAL","releaseLevelLabel":"常规","definitionId":31,
  "definitionName":"版本发布-常规三级","definitionVersion":1,"status":"PUBLISHED","isDefault":true,
  "nodes":[{"key":"n1","name":"产品经理会签","mode":"ALL"}],"updatedAt":"..."}]
```

**`PUT /admin/flow-bindings/{bizType}/{level}`** body `{definitionId}` → 200 槽；400 `BINDING_INVALID`/409 `DEFINITION_NOT_PUBLISHED`/403 `FORBIDDEN`。
**`DELETE /admin/flow-bindings/{bizType}/{level}`** → 200 槽（`definitionId` 回到默认解析结果，`isDefault:true`）。

### 4.4 deliver 内部

**`GET /internal/releases/{releaseId}/flow-evidence?requirementIds=1,2`** → 200：

```json
{"testPlan":{...},"signoff":{...},"qualityGate":{...},"generatedAt":"..."}
```

- 令牌头 `X-Internal-Token` + `X-Tenant-Id`，租户来自头（`InternalTenantResolver`），`TenantContext.set(DataScope.system(tenantId))` try/finally clear（与 `QualityInternalController` 同款）。
- 任一段无数据返回段内 `{"status":"MISSING","reason":"..."}`；**不 404 整体**（详情要能显示其余段）。deliver 自身异常 → 500，由 req 转 `EVIDENCE_UNAVAILABLE`。

---

## 5. 前端设计

### 5.1 组件与页面拆分（对齐 DESIGN）

| 组件/页面 | 数据来源 | 关键约束 |
|---|---|---|
| `components/release/FlowChainPanel.vue` | `props.chain: FlowChain` | 纯展示，**不请求、不判定**；四段等高 `--h-chain-seg`，`MISSING` 用虚线边 + 「暂无」+ reason(2 行 clamp)；`data-testid="release-flow-chain"` |
| `components/release/ApprovalLevelList.vue` | `props.levels: ApprovalLevel[]`、`props.currentNode` | 行高 `--h-level-row`；未到级不渲染按钮（本组件本来就没有按钮，审批动作只在审批中心） |
| `components/release/FlowSnapshotCard.vue` | `props.snapshot`、`props.history`、`props.loading` | 12 位 hash 展示（全文 `title`）；轮次下拉触发父级 `replay`；`stale` 段盖章「已变化」；无删除/重算入口 |
| `views/release/ReleaseDetailView.vue` | `getRelease(id)` 一次拿全 | 概览页签顶部挂 `<FlowChainPanel>`；右侧栏挂等级 chip + 编辑按钮 + `<ApprovalLevelList>`；`status==='PLAN' && !locked` 才显示等级编辑 |
| `views/deploy/DeployOrderDetailView.vue` | `getDeployOrder(id)` | 详情底部挂 `<ApprovalLevelList>` 与 `<FlowSnapshotCard>`；提交成功后重拉详情 |
| `views/admin/AdminFlowBindingsView.vue` | `listFlowBindings()` | 6 张槽卡片（不是表格）；保存/恢复默认各自 `ElMessage`；入口从 `AdminWorkflowsView` 顶部按钮跳转 |

### 5.2 路由与权限

- `web/src/router/routes/deliver.routes.ts` 的 admin 子路由加 `{path:'admin/flow-bindings', name:'admin-flow-bindings', component: AdminFlowBindingsView, meta:{functionCode:'workflow:manage'}}`。
- **不加 `pageCode`、不加导航项**：`scripts/tests/access/a3-nav-audit.mjs` R10 断言 nav 的 pageCode 集合与 `MenuRegistry` 36 个页面码逐字一致，新增页面码会直接红；绑定页是流程定义的子页，靠 `workflow:manage` 功能码守卫即可。入口按钮放在 `AdminWorkflowsView` 顶部。
- 后端 `FlowBindingController` 用与 `WorkflowAdminService` 相同的管理员判定（复用其 `requireAdmin`，若为 private 则提升为包级/public），保证非管理员 403。

### 5.3 状态管理

- 不引入 Pinia 新 store：`ReleaseDetailView` 的 `detail` ref 即唯一数据源，`flowChain = computed(() => detail.value?.flowChain)`；`ApprovalLevelList` 只吃 props。
- 发布单快照回放是**同一页面内的局部状态**（`selectedRound` + `replayData`），切换轮次走 `getDeployFlowSnapshot(id, round)`，失败 toast 并回到最新轮。
- 等级编辑弹窗：`form = { releaseLevel, emergencyReason, previousDeployOrderCode }`；前端只做必填提示，**400 `detail.field` 决定错误挂到哪个输入**（不在前端复刻 1–500 与状态集合的规则）。
- 提交审批成功 → 重拉 `getRelease`（拿到 `approvalLevels` 与 `flowChain`），不本地拼状态。

### 5.4 1280×800

四段同行：内容宽 1000，段宽 241（间隙 12×3），段内次行最长为计划 code（≤13 字符）——DESIGN §0.6 已核算，`white-space: nowrap; text-overflow: ellipsis` 只用于 code；`overflow-x: hidden` 不允许出现。静态验收：`FlowChainPanel` 不引入 `min-width` > 240 的子元素。

---

## 6. 关键实现点与边界

1. **事务**：`startApproval` 与 `submit` 都在事务内；`FlowSnapshotService.capture` 用 `@Transactional(propagation = REQUIRED)` 加入调用方事务——快照与状态同生共死。
2. **并发提交**：同一发布单两个并发 submit，`uk_flow_snapshot_round` 让第二个在插入时失败并整体回滚；`canSubmit` 的状态校验也会先拦住（`PENDING_APPROVAL` 不可再提交）。
3. **审计不回滚的坑**：`noRollbackFor` 里的异常抛在 capture 之前，所以拒绝路径不会留半份快照；审计沿用现有“失败也写”的策略只在成功路径写。
4. **denormalized 驳回字段**：从历史推导会让详情多一次全量历史解析；驳回五列是“最近一次驳回”的缓存，重提时清空。历史仍是唯一流水（AC-3 要求历史保留）。
5. **`MISSING` 不是 FAIL**：详情聚合 fail-soft，但 `flowChain.deployAdmission.status` 的 `PENDING/FAIL` 不会反向影响发布单状态；发布单状态只由 `submit`/审批推进。
6. **deliver 不可用**：`DeliverFlowEvidenceClient` 800ms 超时；版本详情仍 200，三段 `MISSING/EVIDENCE_UNAVAILABLE`。但 `submit` 的 `planGuard`/`qualityGate` 走各自既有 budget（300/500ms），不可用即 503/409（fail-closed）。
7. **QM 语义词**：`GateSnapshot` 仍只有 PASS/PASS_WITH_WAIVER/FAIL；报告 CONDITIONAL 无豁免由 deliver 的门规则判 FAIL（`REPORT_CONDITIONAL_UNAPPROVED`），req 只透传 `failedRules`，不新增门结论值。
8. **种子账号**：`pm(PRODUCT)` → `tl1(PMO)` → `mgr1(MGMT)` 可跑通 RELEASE 三级；`tl1(PMO)` → `admin(PLATFORM_ADMIN)` 跑通 REQ_DEPLOY 两级。`vendor1` 也是 PMO 且属 VEN-A，PMO/ALL 是否包含厂商账号按现有 `ApproverResolver` 口径（种子不特殊处理），演示脚本只用 `tl1` 以免歧义。
9. **删除/修改保护**：`deploy_flow_snapshot` 无 UPDATE/DELETE 路径；`wf_flow_binding` 的 reset 是 DELETE（允许），保存是 upsert。
10. **错误码集中**：新增常量放 `RequirementErrors` 同级（`PLAN_NOT_APPROVED` 已由 planGuard 提供，新增 `RELEASE_NOT_LOCKED/WORKFLOW_NOT_CONFIGURED/BINDING_INVALID/DEFINITION_NOT_PUBLISHED/SNAPSHOT_NOT_FOUND`），HTTP 状态与 detail 由 `ApiException` 统一渲染。

---

## 7. 测试策略与门禁

### 7.1 单元/集成（`mvn -o -pl req-service -am test`）

- `ReleaseLevelValidatorTest`：三值 × 5 条非法字段（空/超长原因、空/不存在/状态不允许单号）→ 400 与 `detail.field`；NORMAL 忽略两字段。
- `FlowBindingServiceTest`：6 槽恒在、保存跨 bizType/未发布被拒、reset 回默认、解析回退顺序（绑定 → 默认 → 任一 PUBLISHED → 409）。
- `FlowChainServiceTest`：四段 status 判定表（PASS/FAIL/PENDING/MISSING）、digest 稳定（同输入两次相等）、deliver 不可用 → 三段 MISSING 不抛。
- `FlowSnapshotServiceTest`：轮次 1..N 连续、hash 稳定、`segmentStale` 计算、replay 越界 404。
- `ApprovalLevelProjectorTest`：三级定义 + 部分完成历史 → 状态序列 `APPROVED,ACTIVE,WAITING`；驳回 → `REJECTED`；长度 = 节点数。
- 既有 371+ 单测不得减少（AC-21）。

### 7.2 `make release-flow-verify`（AC-20，32 条断言）

`scripts/release-flow-verify.sh` 照 `scripts/wbs-backfill-verify.sh` 的骨架（`source scripts/lib.sh`、`set +e`、`pass/fail/section`、`CASE != TOTAL` 即红、EXIT trap 跑 `platform-demo-reset`）：

| 段 | 条数 | 断言（摘要） |
|---|---|---|
| S1 绑定与等级（6） | 6 | 6 槽可读且指向预期定义；NORMAL 三级节点 key `n1,n2,n3`；REQ_DEPLOY 两级；改槽后新实例用新链、在途不变；恢复默认后回到三级；非法等级 400 `detail.field`；非管理员 403 |
| S2 正常链（7） | 7 | 发起后 current=n1 且 n2/n3 待办 0；n1 通过后 n2 待办出现；每级 `NODE_APPROVED` 三键；中间态仍 LOCKED；末级后 PUBLISHED；`approvalLevels` 长度=3 且状态序列；发布单两级同理 |
| S3 驳回与越权（6） | 6 | n2 驳回 → PLAN/locked_at=null/PUBLISHED=0/五字段；重提新实例 id 不同、current=n1；mgr1 对 n2 任务 403 且 PENDING/history 无 APPROVE；pm 对 n2 403；重复发起 409；REJECTED 单据执行 409 |
| S4 链路与快照（7） | 7 | 详情四段键齐全；testPlan/signoff/qualityGate 与明细 jq 归一相等；连续 5 次一致；submit 后快照 hash；改报告后 hash 不变且 `stale.signoff=true`；replay 两次逐字段相等；拒绝提交后轮次不增 |
| S5 负向（4） | 4 | 计划未批 409 `PLAN_NOT_APPROVED(planIds)`；未三签 409 `QUALITY_GATE_FAILED(failedRules 含 QG-02)`；CONDITIONAL 无豁免 409；EMERGENCY 空原因 400 + 版本等级未变 |
| S6 回归与卫生（2） | 2 | 无 publish 路由 + 快照表无 UPDATE/DELETE（静态 grep）；`pom.xml`/`web/package.json` diff 为空、仅新增 V14 |

脚本自声明 `RELEASE_FLOW_TOTAL=32`，末尾 `CASE != TOTAL` 红；报告写 `.run/release-flow/`；前置缺失（栈没起/种子没跑）**明确失败**而不是跳过（r28 的教训：条件跳过让断言数缩水）。

### 7.3 既有门禁（AC-21 下限，只增不减）

`make platform-verify ≥193`、`make platform-test-negative ≥130`、`make access-verify ≥206`、`make rbac-verify =113`（+ 本需求不新增权限码，故意保持 113）、`make wbs-backfill-verify =18`、`make platform-demo-reset ≥69`、`make platform-unit ≥371`、`make lint =22`、`cd web && npm run typecheck && npm run build`。

### 7.4 端到端与演示（AC-17/18/19，QA 阶段）

`docs/teamflow/DEMO-RELEASE-FLOW.md` 8 步：建 NORMAL 版本 → 挂需求 → 测试计划执行 → 报告三签 → 质量门 PASS → 发布单 9 项 → 提交+快照 → 两级发布单审批 + 三级版本审批 → PUBLISHED；被拦链：未三签 → 409 → 演示缺原因 400 与越级 403。证据 ≥6 张 PNG 存任务夹 `evidence/`。

---

## 8. 任务拆分（按文件边界，可并行）

> 依赖约定：T1 先落“契约 + 地基”，T2/T3/T4/T5 以本文件的 §3/§4 签名为准并行；合并顺序 T1 → T2/T3 → T4/T5。所有任务只读本文件与 PRD/DESIGN，不各自发明接口名。

**T1 分级地基与 API（10 文件）** — `V14__release_flow_chain.sql`、`ReleaseLevel`、`ReleaseLevelValidator`、`FlowBindingEntity`、`FlowBindingRepository`、`FlowBindingService`、`FlowBindingController`、`FlowChainViews`、`FlowChainSeedService`、`SeedService`；另含 `ReleaseService`/`ReleaseController`/`RequirementViews` 的等级字段、绑定发起、detail 装配调用点（依赖 T2/T3 的签名，按 §4 契约调用）。验收：种子后 6 槽可读；建/改等级 400/409；发起实例带 `definitionId` 且 current=n1。

**T2 四段链路聚合（4 文件）** — `deliver/.../FlowEvidenceService`、`FlowEvidenceController`、`req/.../DeliverFlowEvidenceClient`、`FlowChainService`。验收：一次 GET 版本详情四段齐全；testPlan/signoff/qualityGate 与 deliver 明细一致；deliver 停机时三段 MISSING、详情仍 200。

**T3 快照与提交闸门（5 文件）** — `FlowSnapshotEntity`、`FlowSnapshotRepository`、`FlowSnapshotService`、`DeployOrderService`、`DeployViews`、`DeployController`。验收：提交成功 1..N 轮、hash 稳定、回放一致；三类拒绝不增轮次；`PLAN_NOT_APPROVED` 在 evaluate 之前。

**T4 前端（10 文件）** — `types.ts`、`req.ts`、`admin.ts`、`FlowChainPanel.vue`、`ApprovalLevelList.vue`、`FlowSnapshotCard.vue`、`ReleaseDetailView.vue`、`DeployOrderDetailView.vue`、`AdminFlowBindingsView.vue`、`deliver.routes.ts`。验收：概览四段无横滚；缺段显示“暂无（原因）”；等级编辑只 PLAN 可点；快照轮次回放；`npm run typecheck && npm run build` 绿。

**T5 门禁与演示（4 文件）** — `scripts/release-flow-verify.sh`、`scripts/release-flow-upgrade.sh`、`Makefile`、`docs/teamflow/DEMO-RELEASE-FLOW.md`。验收：`make release-flow-verify` 32/32；连跑两次升级计数不增；文档 8 步可照做。

**Git（AC-23，落在 T5 收尾，不拆成独立任务）**：保持在 `feat/release-multilevel-approval`；不 stash/丢弃任务夹；全部门禁与证据齐备后一次性提交，信息含 `runId=tf-mwm516ql-s4dqpy` 与 `docs/teamflow/20261118-r30-release-approval-chain`；提交前复核 `.gitignore` 覆盖真实产物；`git status` 只应出现预期文件。

**交付自检（T5 后由任一开发执行，不写 QA/ACCEPTANCE）**：本地跑 `mvn -o -pl req-service -am test`、`cd web && npm run typecheck`、`bash -n scripts/release-flow-verify.sh`，修掉自己发现的问题并记录结论；不预跑 QA 全矩阵。

---

## 9. 风险与取舍

| 风险 | 处理 |
|---|---|
| PMO/ALL 把厂商 PMO（vendor1）算进审批人 | 沿用 `ApproverResolver` 现有口径；演示只用 tl1。若要排除厂商，改绑定为 USER 类型，不改代码 |
| `GET /releases/{id}` 变重（多一次 deliver 调用 + 一次准入评估） | 800ms 预算 + 段内批量；DRAFT 单的准入评估不落库；连续 5 次一致作为验收 |
| DRAFT 单详情触发的实时评估会在 deliver 固化门快照 | 与既有 `evaluate` 语义一致（按 order+digest 幂等）；不写 `admission_result_json`，不进 flow 快照 |
| 驳回五列与历史可能不一致 | 只由 `onApprovalRejected` 单点写入；重提清空；历史仍是唯一流水 |
| 老库升级后“默认绑定”与手工回绑冲突 | 绑定行优先；删行即回默认；种子幂等写回，不覆盖手工改过的槽（`ensure` 语义：行存在即不动） |
| 快照 JSON 结构后续演进 | `payload_json` 存整段；回放只读原样；不写版本迁移，新增字段只增不改 |

**留给用户的默认口径**（PRD §9，实现按默认）：回滚单号同租户可见且状态 ∈ {APPROVED,SUCCESS}（可同版本）；管理层 MGMT/ANY；驳回重提从 n1 新实例；发布单三级默认同链；老库升级自动切新链、旧定义可回绑。
