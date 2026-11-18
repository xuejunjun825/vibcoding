# r29 存量 PDR/RD 工时评估单回填 · 技术变更单

- 基线依赖：`docs/teamflow/20261117-r26-wbs-estimate-gate/`（评估门、六态、±20% STALE）
- 取代项：**无**。不改任何历史 PRD 的 AC，也不改 r26 判定语义。类型：技术驱动改造（运维能力补齐）。
## 1. 背景与目标（一句话）

给 r26 之前创建、没有工时评估单的存量 PDR/RD 提供**可重复执行、默认 dry-run** 的一次性回填能力，经真实审批引擎生成 APPROVED 评估单，解除 `schedule`／`start_dev` 的 409 硬门。
不复用演示夹具：`DemoEstimateFixture` 前半段是 JDBC **重建**演示业务数据（Tx1），只有后半段（Tx2）走引擎；且依赖 `PLATFORM_SEED_ENABLED` 与演示 code 前缀，生产不可用。
## 2. 影响面

| 文件 | 性质 | 说明 |
|---|---|---|
| `req-service/.../requirement/WbsBackfillService.java` · `WbsBackfillController.java` | 新增 | 规划（plan）+ 执行（apply）与判定/建单/批单/报告；`POST /internal/wbs-estimate-backfill`，两道门 |
| `req-service/src/main/resources/application.yml` | 改 1 行 | `platform.ops.backfill.enabled: ${PLATFORM_OPS_BACKFILL_ENABLED:false}` |
| `req-service/src/test/.../WbsBackfillServiceTest.java` | 新增 | 过滤／原因码／事务内重读人天／失败不中断／幂等 |
| `scripts/backfill-wbs-estimate.sh` · `scripts/wbs-backfill-verify.sh` | 新增 | 薄封装（curl+jq+退出码，不做业务判断）／6 组 18 条断言 |
| `scripts/platform-up.sh`（+1 行 export）· `Makefile`（`.PHONY`+`## ` 说明+目标） | 改 | 本地栈打开开关；`make wbs-backfill-verify` 薄封装 |
| 任务夹 `TECH-CHANGE.md`／`RUNBOOK.md`、`docs/teamflow/memory.md` | 新增/改行 | 契约与运行手册；memory 仅同主题行替换 |

不动：`WbsEstimateService`（判定与校验不改）、`ApprovalEngine`（**不加代批语义**）、状态机、`StateTables`、任何 `pom.xml`、`web/`、schema/Flyway。
## 3. 技术方案
### 3.1 入口选型：`/internal/**`（非独立 `main` 类）

① 只有跑在容器里才能复用 `ApprovalEngine` + `@Transactional` + `TenantContext`／`SysDb` 的租户绑定，独立进程要自建 `DataSource` 与租户切面，等价重造半个上下文；② 独立进程只能 **JDBC 直插** `wf_instance/wf_task`，直接违反禁直插（缺 SUBMIT 快照、审批中心待办、审计）；③ 与 `SeedController`／`DemoResetController` 同范式同侧（8082 直连、不经网关、前端不可达）。
双门：`platform.ops.backfill.enabled` 默认 `false`（为假直接 **404**，不暴露「有接口但无权」）＋ `X-Internal-Token` 比对（不符 **401 `INTERNAL_TOKEN_INVALID`**——`InternalTokenFilter` 在控制器之前拦截；控制器内的 403 分支只是与 `SeedController` 同构的防御，真实链路不可达）。**不复用** `PLATFORM_SEED_ENABLED`：生产 seed 必须持续关闭，回填不该把它打开。
### 3.2 选取与判定：状态只走唯一真相 `WbsEstimateService.statusOf`

计划 SQL（`SysDb`，按 tenant 显式绑定；**不注入 `JdbcTemplate`**，守 lint L9）：

```sql
SELECT r.id,r.code,r.type,r.status,r.estimate_days,r.domain_id,d.owner_user_id
FROM requirement r LEFT JOIN biz_domain d ON d.id=r.domain_id AND d.tenant_id=r.tenant_id
WHERE r.tenant_id=? AND r.type IN ('PDR','RD','BUS') ORDER BY CASE r.type WHEN 'RD' THEN 0 WHEN 'PDR' THEN 1 ELSE 2 END, r.id
```

逐行判：`BUS`→`SKIPPED/TYPE_BUS`（扫进来只为报告可见，防运维以为漏扫）；`StateTables.isTerminal(type,status)`→`TERMINAL`（PDR `ACCEPTED_CLOSED`／RD `DONE`，不在 SQL 里硬编码第二份终态表）；`estimateDays` 空或 `≤0`→`SKIPPED/NO_ESTIMATE_DAYS`（**不臆造**）；再看 `statusOf`：`PENDING`→`HAS_ACTIVE_ESTIMATE`（人在批，不去顶）、`APPROVED`→`HAS_APPROVED_ESTIMATE`（幂等落点）、`STALE`→补（旧单 supersede）、`NONE|REJECTED|SUPERSEDED`→补。
### 3.3 单条一个 `REQUIRES_NEW` 事务：建单 + 批单原子

```
TX(requirement)                                    // 失败只回滚本条，整批继续
 1 重读 requirement（estimateDays/status/domain_id/owner_user_id）
 2 重算 statusOf；已变 APPROVED/PENDING → SKIPPED  // 并发第二道闸
 3 RequirementRules.checkHalfDay(days)              // 复用同一函数，禁复制 0.5 常量；否则 FAILED/INVALID_ESTIMATE_DAYS
 4 解析审批人（§3.4）→ TenantContext.set(scope(tenant, approverId))  // 发起人=审批人，免 initiator 解析失败
 5 engine.start(StartApproval(BIZ_TYPE, id, type, domainId, attrs, null, null))
     attrs={estimateDays, note:"存量回填（<batchId>）", source:"BACKFILL", batchId, backfillRunId, approverSource}
 6 task=待办中 approverId==审批人者，否则第一个；切 scope 到 task.approverId；engine.approve(task.id,"存量回填（<batchId>）")
 7 复核 statusOf=="APPROVED"，否则 FAILED/APPROVED_NOT_DERIVED  // 快照人天与实体人天不一致的护栏
 finally 恢复 TenantContext
```

**必须事务内重读 `estimateDays`**：`RequirementService.applyApprovedEstimate` 在 RD 获批后会把父 PDR 的 `estimateDays` 改写成 `RequirementRollup` 汇总值（既有行为），用一次性读出的旧计划会把汇总值反覆盖、派生 `STALE`；故 RD 先于 PDR 排序，人天以「建单那一刻」库值为准。
不复用 `WbsEstimateService.submit`：其前置是「页面用户对该需求是否可见/可交付」、`source` 写死 `UI`、审计写 `ESTIMATE_SUBMIT`——对运维回填均不成立且污染 UI 口径；本方案只复用它的**判定**（`statusOf`）与**校验**（`checkHalfDay`）。
### 3.4 审批人与 `FALLBACK_APPROVER`（口径冲突见 §7-2）

优先 `biz_domain.owner_user_id`（域负责人，与种子流程 `DOMAIN_OWNER` 节点同源）；取不到（NULL／用户禁用）回退**平台管理员**（`PLATFORM_ADMIN` 角色、该租户 id 最小者）。但引擎 `assertAssignee` 要求「当前身份 == 任务审批人」且明令**不得代批**，种子流程又是 `ROLE:PRODUCT ∪ DOMAIN_OWNER` 或签、无 PLATFORM_ADMIN 分支，故落地为：解析出的审批人做发起人 → 取该实例**真实待办**审批人（优先其本人，否则第一个）→ 由真实审批人完成；非域负责人一律打 `FALLBACK_APPROVER` 并记 `approverSource`；实例无待办 → `FAILED/NO_APPROVER`。
### 3.5 幂等

`statusOf==APPROVED`（偏差 0 ≤ 20%）即跳过 ⇒ 连跑两次，第二次 `planned=0、created=0`，不增 `wf_instance`／`wf_history`。唯一二次命中是「两次之间有人改人天」→ 派生 `STALE` → 再补一单，这是正确语义（报告 `previousStatus=STALE`）。并发双跑由 active 单唯一约束 + 引擎 409 兜底 → `FAILED/CONCURRENT_ACTIVE`，不重试。
### 3.6 报告与退出码

```json
{"batchId":"wbsbf-20261118T184500-a1b2c3","tenant":"t1","mode":"dry-run|apply","summary":{"scanned":26,"planned":6,"created":0,"skipped":20,"failed":0},
 "rows":[{"code":"PDR-0007","requirementId":7,"type":"PDR","status":"POOLED","estimateDays":5.0,"result":"CREATED","reason":null,
          "instanceId":91,"previousStatus":"NONE","approverId":2,"approverSource":"DOMAIN_OWNER","flags":[]}]}
```

`result ∈ PLANNED|CREATED|SKIPPED|FAILED`（dry-run 的待建行是 `PLANNED`，apply 成功为 `CREATED`）；`reason ∈ TYPE_BUS|TERMINAL|NO_ESTIMATE_DAYS|HAS_ACTIVE_ESTIMATE|HAS_APPROVED_ESTIMATE|INVALID_ESTIMATE_DAYS|NO_APPROVER|APPROVED_NOT_DERIVED|CONCURRENT_ACTIVE|NO_WORKFLOW_DEFINITION`；`flags` 含 `FALLBACK_APPROVER` 即审批人非域负责人。dry-run 下 `created` 恒 0、`planned`=将创建行数；apply 下 `planned==created+failed`。有 FAILED 仍返 200+完整报告（失败已按行记录），由脚本落 **退出码 1**；用法错 2；传输不可达 3。
### 3.7 批次可查（回滚口径依据）

`batchId` 两处留痕（均在 `wf_history`）：SUBMIT 的 `detail_json`（引擎 start 写入的 `attributes`）与 APPROVE 的 `comment`（「存量回填（batchId）」）。**`audit_log` 不含 batchId**：`RequirementService.applyApprovedEstimate` 与 `WbsEstimateCallback` 落的 `ESTIMATE_APPLIED`／`ESTIMATE_APPROVED` 只带 `instanceId`／`estimateDays`；本轮不加 `ESTIMATE_BACKFILLED` 审计事件，因为 web 事件字典没有对应文案，会显示英文裸码。查受影响范围：

```sql
SELECT h.instance_id,h.task_id,h.action,h.comment,h.detail_json
  FROM wf_history h
 WHERE h.detail_json LIKE '%存量回填（wbsbf-%'
    OR h.comment     LIKE '%存量回填（wbsbf-%'
 ORDER BY h.instance_id,h.id;
```

每个回填实例预期 2 行（`SUBMIT`＋`APPROVE`），行数为 `created × 2`。
## 4. 行为兼容性影响

- **业务语义零变化**：六态、±20%、409 `ESTIMATE_NOT_APPROVED` 三键（`requirementId/status/required`）、判定顺序、状态机、终态集、schema 全不动；回填只**新增已通过单据**，无任何「豁免/放行」旁路。
- **新增运维面**（本轮唯一用户可见增量，验收留痕）：`POST /internal/wbs-estimate-backfill`（默认关闭=404）、`make wbs-backfill-verify`、`scripts/backfill-wbs-estimate.sh --dry-run|--apply`。前端、既有 REST 契约、演示夹具均不变。
- **既有规则连带效果（非本轮引入）**：回填获批会同步 `estimate_days` 为单据快照并触发父 PDR 汇总刷新，故 apply 后个别 PDR 的 `estimate_days` 变为「子需求之和」；RUNBOOK 要求 apply 前导出计划并备份 `requirement` 表，报告保留原值以便比对。
## 5. 回归与验证计划
### 5.1 新增 `make wbs-backfill-verify`（`scripts/wbs-backfill-verify.sh`，自声明 `WBS_BACKFILL_TOTAL=18`，实跑数不符即红）

夹具脚本自建、结束 `platform-demo-reset` 复原：1 条无单 PDR、其下 1 条 RD、1 条 BUS、1 条空 `estimate_days`、1 条 `3.2` 天。断言**禁止只数「总数>0」**：

| 组 | 断言 |
|---|---|
| S1 契约（4） | 开关关时 404；dry-run 回 `mode=dry-run`；每行七键（`code/type/status/estimateDays/instanceId/result/reason`）齐全；dry-run 前后 `wf_instance`／`wf_history` **逐表计数不变** |
| S2 生效（5） | 目标 PDR `estimate.status=APPROVED`；其 `schedule` 不再 409（状态确实前进）；RD `start_dev` 不再 409；`created==planned>0`；单据说明与意见均等于 `存量回填（<batchId>）` |
| S3 幂等（3） | 第二次 `planned=0 && created=0`；`wf_instance` 计数不变；`wf_history` 计数不变 |
| S4 BUS（2） | BUS 无 `WBS_ESTIMATE` 单（按 `biz_id` 查）；报告存在 `TYPE_BUS` 行 |
| S5 人天缺失（2） | 报告 `NO_ESTIMATE_DAYS` 行；该需求 `schedule` 仍 409 且 `code=ESTIMATE_NOT_APPROVED` |
| S6 失败语义（2） | `3.2` 天行 `FAILED/INVALID_ESTIMATE_DAYS` 且同批其余行仍 CREATED（不中断）；该次退出码 `1` |
### 5.2 回归下限（QA 取实测，只增不减）

`make lint` 22 · `make platform-unit` ≥371+新增单测 · `make platform-verify` ≥193 · `make platform-test-negative` ≥130 · `make access-verify` ≥206 · `make rbac-verify` 113（s0–s9） · `make platform-demo-reset` 69 · `make wbs-backfill-verify` 18/18 · `cd web && npm run typecheck && npm run build` 全绿。
## 6. 风险与回滚

| 风险 | 处置 |
|---|---|
| 父 PDR 人天被汇总改写 | 既有规则；apply 前备份 + 报告留原值；RD 先批、事务内重读 |
| 审批人解析不出/不在待办 · 租户未种 `WBS_ESTIMATE` 流程 | 打 `FALLBACK_APPROVER`；无待办 `FAILED/NO_APPROVER`；`start` 抛错 → 行级 `FAILED`，整批继续，绝不静默 |
| 窗口期误重复触发／大批量长锁 | 单实例串行不重试、apply 前必 dry-run 确认 `planned`、开关用完即关；逐条独立事务按 id 串行，先在只读副本看耗时 |

**回滚**：回填「只增单据」，不改状态机、不迁 schema，**无需数据回滚**，也不提供反向脚本。确需撤销：用 §3.7 SQL 按 `batchId` 枚举受影响需求 → 页面重新提交评估单（UI 既有路径会 supersede 旧单，偏差超 20% 自然转 `STALE`），被改写的 PDR 人天按备份核对。反向动作留人工，避免运维脚本反向改 `wf_*`。
## 7. 假设与待澄清

1. **入口形态**：已选 `/internal`；若改独立进程，须自带容器装配 + JDBC 直插 `wf_*`（违反禁直插），需先放宽该约束。
2. **「回退平台管理员」与禁代批冲突**：落地为「域负责人优先 → 否则实例真实待办审批人（含 PRODUCT/平台管理员）」+ `FALLBACK_APPROVER`；若要求管理员真能代批，须改 `ApprovalEngine.assertAssignee`（本轮明令不改）或给流程加 `USER:admin` 节点。
3. **`STALE` 是否自动补**：已选「补 + supersede」。若认为人天漂移应交人工，改 `SKIPPED/STALE_MANUAL` 一行即可，S2 夹具改为 `NONE` 起算。
4. **`NO_ESTIMATE_DAYS` 是否代填 · `BUS` 是否补单**：都不（不臆造、BUS 不可排期）；若改口径，前者报告加 `imputedDays` 并单列验收项，后者放宽 §3.2 的 `TYPE_BUS` 分支并同步 S4。
5. **演示夹具是否改走同一服务 · 批次并发**：都不改／不做分布式互斥（单实例串行 + 唯一约束兜底，多副本并行需租约表）；后者若要并跑，须先落租约表。
## 8. 交付清单（三方对接）

- 开发：§2 九个文件 + 单测 + `RUNBOOK.md`；提交信息带 `runId=tf-mwlztf64-ylus7r` 与任务夹 `docs/teamflow/20261118-r29-wbs-estimate-backfill`。QA：§5.1 十八组断言逐条实测留痕；§5.2 门禁复跑取实测值；「dry-run 零写入」用 `wf_instance`／`wf_history` 双表计数，不接受「报告写了 dry-run」。
- 验收：只看三件事——存量需求 `schedule/start_dev` 由 409 转可执行、连跑两次零变更、BUS 与无人天需求被正确拦下，外加 `batchId` 可反查。
