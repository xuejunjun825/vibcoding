# r32 技术方案 · 发版链路端到端演示夹具

> 基线依赖：r30（多级审批 / 准入 / 快照 / flowChain）与 r31（第 32 条提交区间判据）；本需求不声明取代任何历史 AC。
> 分支：`feat/demo-release-green`；任务夹：`docs/teamflow/20261119-r32-release-flow-e2e/`。
> 改动面：`req-service/.../seed/`、`scripts/`、`docs/`（+ Makefile 帮助文本），**不改** `web/`、审批引擎、准入规则、快照表结构、状态机，不新增迁移。

## 0. 结论（先看这四条，第 1 条修正了 PRD 的隐含假设）

1. **8 步不能直接跑在 `DEMO-REL-1` 上。** 质量门 QG-01 是「版本需求覆盖率 ≥ 100%」：
   `GateEvaluator.qg01`（`deliver-service/src/main/java/com/example/deliver/quality/gate/GateEvaluator.java:346-372`）用 `coveredRequirements / totalRequirements`；
   `totalRequirements` 数的是 `release_requirement`（`:281-289`），`coveredRequirements` 只认「本版本内、已批准计划里的 `test_plan_case.requirement_id`」（`:291-303`）。
   `DEMO-REL-1` 挂了 11 条需求（`DemoFixture.REQUIREMENTS` 中 `inRelease=1` 的 R-01..R-11），种子计划只覆盖 C-01..C-10 → 命中 R-03/04/06/08/10 五条 = **5/11 = 45% < 100%，QG-01 永远 FAIL**，发布单提交过不去。
   **方案**：第 1 步先把 R-04 从 `DEMO-REL-1` 移出（`ReleaseService.removeRequirement:281-300`，版本为 `PLAN` 才可编辑，种子版本正是 `PLAN`），再建演示版本并把 R-04 挂进去；演示版本只有 1 条需求、2 条用例 → 覆盖率 100%。AC-3 的「`DEMO-REL-1` 计划覆盖 C-03/04」仍是 reset 态断言，也仍然成立，只是演示不再消费它。
2. **计划用例必须全跑完。** QG-03 在 STRICT 版本下要求 100%（`GateEvaluator.qg03`，`:458-472`：`strict && strictRequiresAll ? 100`）；`RoundService.close`（`deliver-service/.../plan/RoundService.java:159-196`）对未执行用例直接 409 `ROUND_HAS_UNEXECUTED`。演示计划只放 C-03/C-04 两条，两条都 PASS，轮次 PASS，计划 `COMPLETED`。
3. **负向清空 MR 用空串，不用 null。** `RequirementService.deliver:571` 只在 `mrUrl != null` 时写库：传 `""` 才会把 `mr_url` 清空（从而 `MR_FILLED` 失败），传 `null` 保持原值。
4. **夹具对 `wf_*` 只读不写。** R-01 的提报/评审两单由 `RequirementService.transition` + `ApprovalEngine` 真实跑出来（`submit → 逐级 approve → review_start → 逐级 approve`），`wf_instance/wf_task/wf_history` 全部由引擎落库。

## 1. 现状与差距

| 项 | 现状 | r32 差距 |
|---|---|---|
| R-04 | `PENDING_TEST`、`estimate_days=2`、`mr_url` 空、`actual_days` 空 | 需要 MR + 实际人天，且必须由 `deliver` 写入并留 `DELIVER` 审计 |
| R-01 | `POOLED` 直插，无 `REQ_SUBMIT/REQ_REVIEW` 实例 | 需要两单 `APPROVED`，由真实动作链产生，净状态仍 `POOLED` |
| 准入 | `approvalLine` 上溯到 R-01，但「提报/评审未发起」→ `REQ_APPROVALS_DONE` 永远 FAIL | 两单 `APPROVED` 后准入项转 PASS |
| 文档 | A.3 写「卡在哪」（2 PASS/4 FAIL/3 CLOSED） | 改为「全绿跑通」+ 逐步响应码 + 两阶梯留痕 + 负向实测 |
| 门禁 | S1–S6 共 32 条，演示链只覆盖到「提交成功」 | 新增 S7 八条覆盖到 `PUBLISHED` 与 409 指名，分母 32→40 |

## 2. 数据模型与存储

**无 DDL、无迁移。** 第 32 条判据也只允许 `0..1` 条 `A V14`，本轮 0 条；不新增表、不新增列、不新增需求行（仍 12 条）。

| 表 | 相关列 | 本轮写入方 |
|---|---|---|
| `requirement` | `code/type/status/estimate_days/actual_days/mr_url/release_id/parent_id` | Tx1 JDBC 插入；Tx3 `RequirementService.deliver` 写 MR/人天 |
| `release_requirement` | `release_id/requirement_id` | Tx1 插入；演示第 1 步删除 R-04 关系、第 2 步写新版本关系 |
| `test_plan` / `test_plan_case` | `status`、`approved_at`、`case_id/requirement_id/executor_id/result` | Tx1 插种子计划（DRAFT/10 条）；演示另建计划（2 条） |
| `test_plan_round` | `case_scope_json/status/conclusion/active` | `start` 自动开首轮（CURRENT=全部计划用例）；`execute` 写执行；`close` 冻结 |
| `test_report*` / `report_signoff` | `scope/conclusion/version`、`approved` | 演示建 RELEASE 报告并三签 |
| `quality_gate*` / 准入快照 | `result`、`deploy_flow_snapshot.payload_hash/round_no` | `evaluate` 落门快照；发布单创建/提交落 flow 快照 |
| `wf_instance/wf_task/wf_history/wf_cc` | 引擎自有 | **只由 `ApprovalEngine` 写**；Tx1 的 `DELETE_ORDER` 先清空保证幂等 |
| `audit_log` | `action/biz_id/before/after` | `STATE_CHANGE`、`DELIVER`、`RELEASE_PUBLISH`、`NODE_APPROVED`(wf_history) |
| `notification` | `id` 水位线 | Tx3 结束按 `id > 基线` 删除链上产生的通知，保住夹具的 4 条 |

**写入时序（三段事务，互不混用 JPA 与裸 JDBC）：**

```
Tx1 resetBusiness()  JDBC：删旧 → 插 release(PLAN/STRICT)/requirement(R-01 置 SUBMITTED)/plan(DRAFT)… → counts+invariants+violations
Tx2 estimates.seedDemo()  JPA+引擎：11 条工时评估 APPROVED（RbacSeeder 的 WBS_ESTIMATE 定义）
Tx3 approvals.seed()      JPA+引擎：R-01 真跑两单 + R-04 deliver + 通知水位线清理 + 后置断言（失败即整体回滚、reset 报错）
```

分三段沿用 r30 的既有教训：Tx1 是裸 JDBC，JPA 读不到未提交的裸插行，因此凡走 `RequirementService`/`ApprovalEngine` 的必须另起事务；`DemoEstimateFixture` 已是这个范式。

**幂等**：`DemoFixture.DELETE_ORDER`（`DemoFixture.java:184-234`）已包含 `wf_cc/wf_history/wf_task/wf_instance`，第二次 reset 从「无实例」重新长出来；通知按 id 水位线删，不做文案匹配，避免演示文案调整后清理失效。

## 3. 夹具设计（新增 `DemoApprovalFixture`）

新增 `req-service/src/main/java/com/example/req/seed/DemoApprovalFixture.java`（`@Service`，构造注入 `SysDb`、`RequirementService`、`ApprovalEngine`、`ApprovalStore`、`TransactionTemplate`、`@Value("${platform.tenant:t1}")`），职责单一：**只负责「reset 之后补一条可真跑到发布的需求」**，不参与业务行插入。

### 3.1 R-01 的真实审批链（4 步 + 会签循环）

```
身份: po1 (BUSINESS, 非厂商)      → transition(R-01, "submit",  source="UI")   // SUBMITTED → APPROVING，启 REQ_SUBMIT
身份: 各待办 assignee (admin)     → engine.approve(taskId, "演示夹具：提报会签通过") × N（会签全批）
身份: pm  (内部, req:review)       → transition(R-01, "review_start", source="UI") // PENDING_REVIEW，启 REQ_REVIEW，状态不变
身份: 各待办 assignee (admin)     → engine.approve(...) × N                     // 回调 → review_pass → POOLED
```

- 状态迁移依据 `StateTables.java:60-71`（BUS）：`SUBMITTED --submit--> APPROVING --approve--> PENDING_REVIEW --review_start--> PENDING_REVIEW --review_pass--> POOLED`；`RequirementReviewCallback` 在 `PENDING_REVIEW` 时把 `REQ_REVIEW` 的通过翻译成 `review_pass`，落点正是 `POOLED`，所以「真跑一遍」不会把 R-01 留在异常态。
- 会签不能只批一条：`SeedService.java:254-262` 把 `REQ_SUBMIT/REQ_REVIEW` 定义成 PRODUCT 会签，必须循环到 `store.tasks(instanceId)` 无 `pending` 为止（`DemoEstimateFixture` 只批一条，不能照抄）。
- 身份用 `TenantContext.setPrincipal(new Principal(userId, username, name, "t1", "t1", roles, Set.of(), vendorId))` + `DataScope.of(...)` 成对设置，`callerOf` 才拿得到真实 `roles/vendorId`；每次切换后 `finally` 复原。
- 断言（Tx3 内，失败即回滚）：R-01 状态 `POOLED`；两实例 `APPROVED` 且 `definition_id` 非空；`wf_task.assignee_id` 非空；`wf_history` ≥4 条且含 `NODE_APPROVED`。

### 3.2 R-04 的交付写入

`requirementService.deliver(r04Id, new DeliverRequest(new BigDecimal("2.0"), new BigDecimal("2.0"), "https://git.example.com/mr/r32-e2e", null))`：

- `checkHalfDay` 通过（半天粒度）；`mr_url`/`actual_days`/`estimate_days` 一次写全；
- 审计 `DELIVER` 的 `after.mrUrl` 即 AC-1 要的证据；`refreshParentRollup` 会顺带更新 R-02 的汇总，不影响任何既有计数；
- 常量收口到 `DemoFixture`：`MR_URL`、`ACTUAL_DAYS`，文档与门禁都引用同一字符串。

### 3.3 通知与残留

链上 `notifyTransition` 会写通知（submit/approve/review_start/review_pass）。Tx3 进入前取 `SELECT COALESCE(MAX(id),0) FROM notification`，结束前 `DELETE FROM notification WHERE tenant_id=? AND id > ?`。这样 `notifications=4` 的既有口径不变，且不依赖通知文案。

### 3.4 `scripts/platform-demo-reset.sh` 新增 4 条断言（70 → 74）

| 断言 | 取数 |
|---|---|
| R-04 就绪 | `type=RD AND status=PENDING_TEST AND release_id=DEMO-REL-1 AND mr_url<>'' AND estimate_days=2.0 AND actual_days BETWEEN 1.5 AND 2.0` |
| R-01 提报单 APPROVED | `wf_instance` join `wf_definition`，`biz_type=REQ_SUBMIT AND biz_id=R-01 AND status=APPROVED` |
| R-01 评审单 APPROVED | 同上 `REQ_REVIEW` |
| 计划覆盖 + 形态 | `DEMO-REL-1` 的计划里有 `requirement_id=R-04` 且 `case_id∈{C-03,C-04}`；同查 `definition_id NOT NULL`、`wf_history>=4` |

计数口径只增不减（requirements 12 / releases 1 / planCases 10 / notifications 4 等保持），`TOTAL` 由 70 改 74，`PASS==TOTAL` 与非 0 退出保留；两次连续 reset 仍幂等（第二次跑同一批断言）。

## 4. API 设计（8 步即验收脚本，全部走公开路由）

| 步 | 方法与路径 | 关键请求 | 期望 |
|---|---|---|---|
| 0 | `POST /api/req/auth/login` | `{username,password}` | 200，取 token |
| 1 | `DELETE /api/req/releases/{DEMO-REL-1}/requirements/{R-04}` | — | 204，R-04 `release_id` 置空 |
| 1 | `POST /api/req/releases` | `{name,domainId,startDate,endDate,plannedReleaseDate,gateMode:"STRICT",releaseLevel:"NORMAL"}` | 201，`status=PLAN`，`approvalLevels=[]` |
| 2 | `POST /api/req/releases/{id}/requirements` | `{requirementIds:[R-04]}` | 200 |
| 3 | `POST /api/deliver/test-plans` | `{releaseId,name,ownerId:qa1,windowStart,windowEnd,caseIds:[C-03,C-04],gateMode:"STRICT"}` | 201 |
| 3 | `POST /api/deliver/test-plans/{id}/submit-review` → `/approve` → `/start` | `{}` / `{comment}` | 200 / 200 / 200（`start` 自动开首轮，scope=CURRENT=2 条） |
| 3 | `POST /api/deliver/test-plans/{id}/execute` ×2 | `{caseId,result:"PASS"}` | 200 |
| 3 | `POST /api/deliver/test-plans/{id}/rounds/{roundId}/close` | `{conclusion:"PASS"}` | 200（全部执行才允许） |
| 3 | `POST /api/deliver/test-plans/{id}/complete` | `{}` | 200，`status=COMPLETED` |
| 4 | `POST /api/req/requirements/{R-04}/actions` | `{action:"test_pass"}` | 200，`status=TEST_PASSED` |
| 5 | `POST /api/deliver/test-reports` | `{scope:"RELEASE",releaseId,note}` | 201 |
| 5 | `POST /api/deliver/test-reports/{id}/versions/1/signoff` ×3 | `{decision:"APPROVE"}`，qa1→pm→tl1 | 200；`approved=3,total=3` |
| 5 | `POST /api/deliver/quality-gates/{releaseId}/evaluate` | `{}` | 200，`result=PASS`（QG-01 1/1、QG-03 2/2、QG-02 已签） |
| 6 | `POST /api/req/deploy-orders` | `{releaseId,requirementId:R-04,env:"PROD"}` | 201，`status=DRAFT`，同响应/详情带 9 项准入 |
| 7 | `POST /api/req/releases/{id}/approval` | `{}` | 200，`status=LOCKED`，`currentNode=n1` |
| 7a | `POST /api/req/requirements/{R-04}/deliver` | `{mrUrl:""}` | 200（清空） |
| 7b | `POST /api/req/deploy-orders/{id}/submit` | `{}` | **409** `ADMISSION_FAILED`，`detail.failed==["MR_FILLED"]`，快照数不增 |
| 7c | `deliver` 恢复 → `submit` | `{mrUrl:"https://…/r32-e2e"}` | 200，`flowSnapshot.round=1`、`payloadHash` 64hex、`changedSinceSubmit=false` |
| 8 | 发布单逐级审批 | `approve_biz(order, DEPLOY_RELEASE)` | n1 仅 tl1/vendor1 → n2 admin；`APPROVED,APPROVED` |
| 8 | 版本逐级审批 | `approve_biz(release, RELEASE)` | n1 pm → n2 tl1/vendor1 → n3 mgr1；仅末级 `PUBLISHED`+`releasedAt`+`RELEASE_PUBLISH` |

失败语义沿用既有错误码：`ADMISSION_FAILED`（409，带 `failed[]`）、`RELEASE_NOT_LOCKED`、`PLAN_NOT_APPROVED`（`DeployPlanGuard:36`）、`QUALITY_GATE_FAILED`（`QG-0x`）、`FORBIDDEN`（403，任务仍 `PENDING`、`wf_history` 无 `APPROVE`）。

## 5. 前端与页面（不改 `web/`）

- 本轮是「让链路真跑通 + 把证据写进文档/门禁」，接口与页面契约都不变：`RELEASE` 详情页（版本/审批阶梯/准入）、`QUALITY` 计划与报告页、发布单详情页（flowChain 四段）都已消费本轮同样的接口。
- AC-11 的截图取自现有页面（版本详情 + 发布单详情 + 409 提示），不需要新组件、不需要新路由；若环境不可用，按 PRD §10 降级为「接口响应 + 落库查询」截图并在 A.3 注明。
- 因此前端**无组件拆分、无 store 变更**：`allowedActions`、`approvalLevels`、`flowChain` 都是后端下发，前端只渲染；这条要在验收里明说，避免「没改前端」被误读成漏做。

## 6. 状态管理与一致性

- 状态唯一来源仍是后端状态机：`RequirementStateMachine`（`platform-contract/.../StateTables`）+ 审批引擎回调；夹具只驱动合法动作，不写状态列（除 Tx1 的初始插入）。
- 前端无本地状态改写；两阶梯的「当前节点/待办」由 `GET /deploy-orders/{id}`、`GET /releases/{id}` 的 `approvalLevels[].status` 派生。
- 快照一致性：提交成功即冻结 `deploy_flow_snapshot`（`round_no=1`、`payload_hash`），回放只读不写；`FlowSnapshotRepository` 无 UPDATE/DELETE（第 31 条静态检查持续守着）。

## 7. 关键实现点与边界

1. **会签循环上限**：`approveAll(instanceId)` 每轮重新取 pending，最多 8 轮，超限抛错（防死循环把 reset 挂住）。
2. **事务边界**：Tx3 内任何断言失败 → 抛 `IllegalStateException` → 整个 reset 回滚，`/demo-reset` 返回非 2xx；禁止 catch 后继续（会留下半截夹具）。
3. **`TEST_PASSED` 仍可 `deliver`**：`requireWritable` 只挡终态，负向才能在同一把 LOCKED 窗口内清空 MR；若未来收紧，S7 必须红，而不是改成「直接改库」。
4. **`release_id` 单值**：R-04 必须先移出旧版本才能挂新版本（`ReleaseService.addRequirements:244-276` 对「已属其他版本」返回 400），这是第 1 步必须是两步的原因。
5. **计划守卫**：新版本只允许存在「已批准且覆盖 R-04」的计划；演示计划在 `complete` 前不能建发布单（否则 409 `PLAN_NOT_APPROVED`）。
6. **越级不写历史**：`FORBIDDEN` 分支在 `authorize` 阶段返回，早于 `setStatus`，故任务仍 `PENDING`、`wf_history` 无 `APPROVE`——S7 与既有 S3 都断言这一点。
7. **幂等与并发**：演示只跑单线程顺序调用；`start` 的 `UNIQUE(plan_id, active)` 与 `ROUND_IN_PROGRESS` 保证重复 `start` 不会开出第二轮。
8. **不引入放行开关**：没有 `FORCE_RELEASE`、没有「演示模式跳过准入」的分支；所有捷径都必须是真实动作。

## 8. 门禁改造

### 8.1 `scripts/release-flow-verify.sh`：新增 S7 八条，`RELEASE_FLOW_TOTAL 32 → 40`

S7 先**自愈**：若 `RELEASE_FLOW_SKIP_RESET != 1`，先跑 `bash scripts/platform-demo-reset.sh`（setup 不计数），保证 S1–S5 造的数据不会污染演示夹具。执行顺序上 7a/7b（负向）在 7c（成功提交）之前，编号仍按 AC-12 排列。

| 编号 | 断言 | 取数 |
|---|---|---|
| S7-1 | 夹具就绪 | R-04 六字段 + R-01 两单 `APPROVED` + 种子计划覆盖 C-03/04 |
| S7-2 | 版本测试到 `TEST_PASSED` | 建版本 201/挂需求 200/计划 201/评审批准开始 200/执行 2×200/关轮次 200/完成 200/`test_pass` 200 + 详情状态 |
| S7-3 | 报告三签 | `signoff.approved=3,total=3`，三个签署人各一条 |
| S7-4 | 质量门 PASS | `evaluate.result=PASS`，且落库快照 `result=PASS`（不只信响应） |
| S7-5 | 提交快照 + 四段同源 | `round=1`、`payloadHash` 64hex、`changedSinceSubmit=false`；`flowChain` 四段：plan `COMPLETED`、signoff 3/3、gate `PASS`、admission 9 项 `FAIL=0`；`round=1` 回放两次 `jq -S` 一致 |
| S7-6 | 发布单两级 | n1 仅 tl1/vendor1、批后 n2 admin、`APPROVED,APPROVED`、每级 `NODE_APPROVED` 带 `nodeKey/nodeName/mode/actor` |
| S7-7 | 版本三级到 `PUBLISHED` | 前两级 `status=LOCKED` 且 `releasedAt` 空；末级 `PUBLISHED`+`releasedAt`+`audit RELEASE_PUBLISH` |
| S7-8 | 负向 409 指名 | 清空 MR 后 `submit` 409 `ADMISSION_FAILED`、`failed==["MR_FILLED"]`、快照数不变、`round` 仍 1；恢复后 200 且 `round=1` |

`--static-only` 自检同步改三处：`RELEASE_FLOW_TOTAL=40` 唯一、`^section "S[1-7] ` 恰 7 个、文档段仍是 `^## [1-8]\. ` 8 条 + `^## 被拦链`。其余 8 条静态检查不变；`CASE != TOTAL` 或 `FAIL>0` 仍 `exit 1`。

### 8.2 `scripts/platform-demo-reset.sh`

`TOTAL 70 → 74`，新增 3.4 的 4 条；输出仍打印每项 PASS/FAIL 与末尾汇总。

### 8.3 其余门禁

`platform-verify` / `platform-test-negative` / `access-verify` / `rbac-verify` 断言只增不减；第 32 条仍是 `merge-base(main,HEAD)..HEAD` 的 `git diff --name-status --no-renames` 纯区间判据 + 正负变异自检，r32 的 diff 只落在 `seed/`、`scripts/`、`docs/`、`Makefile` 帮助文本，不改 `pom.xml`/`web/package.json`/`web/pnpm-lock.yaml`、不新增迁移。

## 9. 文档与截图

`docs/teamflow/DEMO-RELEASE-FLOW.md`：

- 0 前置：`RD_ID=DEMO-R-04`、`CASE_ID∈{DEMO-C-03,DEMO-C-04}`、删除「DEMO-R-08 已填 MR/工时」，补「第 1 步要先移出 R-04」的原因。
- `## 1.`–`## 8.` 标题保留（静态检查按 `^## [1-8]\. ` 计数），正文改为 r32 实测：每步贴方法与响应码；第 7 步包含「清空 → 409 → 恢复 → 200」；第 8 步贴两阶梯的逐级待办与 `NODE_APPROVED` 留痕。
- `## 被拦链` 四条（报告未签 409 / 紧急缺原因 400 / 越级 403 / 无 publish 后门）保留。
- 附 A 改写：A.1 换成 r32 全绿表；A.2 保留三条实操纠正并追加「QG-01 覆盖率」与「计划必须全跑完」；A.3 从「卡在哪」改为「全绿跑通」+ `PUBLISHED` 落库证据（`release.status/released_at`、`audit RELEASE_PUBLISH`、`deploy_flow_snapshot`）；A.4 复现命令同步。
- 截图：`docs/teamflow/demo-shots/r32-published-chain.png`、`r32-admission-409-mr.png`（新文件，不覆盖 r30 的 `r30-*.png`）；`.gitignore` 复核 `demo-shots/*.png` 入库、`logs/teamflow/` 忽略。

## 10. 测试策略（分层，失败优先）

| 层 | 命令 | 覆盖 |
|---|---|---|
| 静态 | `bash scripts/release-flow-verify.sh --static-only` | 分母 40、分段 7、文档 8 步、EXIT trap |
| 单测 | `mvn -o -pl req-service -am test` | 种子/状态机既有用例不回归 |
| 夹具 | `make platform-demo-reset`（连跑两次） | 74 条断言、幂等、通知 4 条 |
| 真栈正链 | `make release-flow-verify` | S7-1..S7-7 |
| 真栈负向 | 同脚本 S7-8 + S3/S5 | 409 指名、403 不落历史、400 缺字段 |
| 回归 | `make platform-verify` / `platform-test-negative` / `access-verify` / `rbac-verify` / `lint` | 断言不减 |
| 视觉 | 现有页面截图 | AC-11 |

门禁自身可重复：S7 自愈 reset、负向快照数比对、回放 `jq -S` 两次一致。

## 11. 任务拆分（文件边界不交叉，可并行）

**T1 夹具真跑链**（先做，其余任务读它的契约）
files：`req-service/src/main/java/com/example/req/seed/DemoFixture.java`、`.../seed/DemoApprovalFixture.java`（新增）、`.../seed/DemoResetService.java`
reads：`RequirementService.java`、`ApprovalEngine.java`、`DemoEstimateFixture.java`、`RequirementDtos.java`
spec：R-01 置 `SUBMITTED`；Tx3 `DemoApprovalFixture.seed()` 真跑两单 + `deliver` R-04 + 通知水位线清理 + 后置断言；`MR_URL`/`ACTUAL_DAYS` 收口常量；不改表结构。

**T2 门禁 S7 + 分母**（与 T3 并行）
files：`scripts/release-flow-verify.sh`
reads：`scripts/platform-demo-reset.sh`、`docs/teamflow/DEMO-RELEASE-FLOW.md`
spec：新增 S7 八条（含自愈 reset、7a/7b 先于 7c）、`RELEASE_FLOW_TOTAL=40`、静态分段 7；沿用既有 helper，不新建脚本。

**T3 demo-reset 断言**（与 T2 并行）
files：`scripts/platform-demo-reset.sh`
reads：`req-service/.../seed/DemoFixture.java`
spec：`TOTAL 70→74`，新增 3.4 四条；幂等与既有口径不降。

**T4 文档与截图**（依赖 T1/T2 能真跑）
files：`docs/teamflow/DEMO-RELEASE-FLOW.md`、`docs/teamflow/demo-shots/r32-published-chain.png`、`docs/teamflow/demo-shots/r32-admission-409-mr.png`
spec：A.1–A.4 重写、8 步标题与「被拦链」保留、两张新图不覆盖 r30。

**T5 回归与提交**（最后）
files：`docs/teamflow/memory.md`（只改同主题行）
spec：跑 §10 全部命令全绿；`git status` 干净（忽略项除外）；在 `feat/demo-release-green` 提交，提交信息含 `r32`、runId `tf-mwmvwpj7-6kmkas`、任务夹 `20261119-r32-release-flow-e2e`；复核 `.gitignore`（`demo-shots/*.png` 入库、`logs/teamflow/` 忽略）；**不 push、不建新分支**。

## 12. 风险与回退

| 风险 | 处置 |
|---|---|
| 会签定义被改成多人 → 循环上限不足 | `approveAll` 上限 8 + 超限报错；不静默通过 |
| po1 无 `submit`/pm 无 `review_start` 权限（矩阵 DENY） | T1 首跑即暴露：reset 非 2xx，错误信息点名动作与用户；改夹具身份（admin）并在 A.2 记录，不绕过授权 |
| 计划 `complete` 的退出准则（`exit_criteria_json`）比预期严 | 只放 2 条用例且全 PASS、轮次已关；若仍不过，读 `detail.unmet` 调整计划配置，不改退出准则 |
| S7 自愈 reset 与 S1–S5 数据冲突 | S7 之前 S1–S5 断言已结束，S6 只读 git/源码；reset 在 S7 起始执行 |
| 真实 UI 截图环境不可用 | 按 PRD §10 降级为接口+落库图，A.3 注明；不伪造截图 |

## 13. AC 映射

| AC | 落点 |
|---|---|
| AC-1 / AC-2 | T1（`deliver` + 真跑两单 + 后置断言），T3 断言 |
| AC-3 | T3（70→74、既有口径、幂等） |
| AC-4 / AC-5 / AC-6 / AC-7 | T4 文档 8 步 + T2 S7-2..S7-7 |
| AC-8 | T2 S7-8 + T4 第 7 步实测 |
| AC-9 | T2（S3/S5 不动）+ T4 被拦链保留 |
| AC-10 / AC-11 | T4 |
| AC-12 | T2（40 条、分段 7、PASS==TOTAL） |
| AC-13 | T5（四门禁、第 32 条、diff 面、提交） |
