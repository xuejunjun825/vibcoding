<!-- meta: summary="把版本发布与发布单审批做成按发布等级可配置的多级串行链，并把测试计划、报告三签、质量门、发布单准入串成同源、可回放的端到端发版链路。" -->
基线依赖：docs/teamflow/20261108-r2-devops-req-platform（版本/发布单审批驱动状态机、驳回回退、会签与流程配置）；docs/teamflow/20261117-r26-wbs-estimate-gate（多节点串行、会签/或签、逐级 NODE_APPROVED 留痕的引擎行为不得回归）
取代：docs/teamflow/20261108-r2-devops-req-platform#AC-10：版本发布与发布单审批的默认链路由单节点改为按发布等级的多级串行，驳回回退与通过级联语义保持不变。
取代：docs/teamflow/20261108-r2-devops-req-platform#AC-6：流程绑定维度在「需求类型 × 业务域」之外增加「发布等级」，版本发布与发布单各 3 个等级槽可独立绑定。

# 发版多级审批链与端到端发版链路（r30）

## 1. 背景与目标

现状（main = 79e1f1c）：`RELEASE` 默认只有单节点「产品评审」（PRODUCT 会签），`REQ_DEPLOY` 默认只有单节点「平台管理员审批」（PLATFORM_ADMIN 会签）。审批引擎本身已支持多节点、会签/或签、条件分支与逐级留痕（r26 的 `WBS_ESTIMATE` 已跑过两节点链），缺的是**分级默认链、可配置绑定，以及把前置质量证据串成一条可读、不可绕过、可回放的链路**。结果是：版本详情看不出「测试没过 → 质量门不过 → 发布单被拦」的因果，演练时只能靠口头解释；审批也只有一级，体现不出逐级管控。

目标（均可验收）：

- G1 默认就可演：全新库不改任何配置，版本发布走三级（产品 → PMO → 管理层），发布单走两级（PMO → 平台管理员），每一级都有待办、留痕与驳回回退。
- G2 分级可配：常规 / 紧急修复 / 回滚三类发布，各自绑定不同链路；紧急必须填原因，回滚必须带上一发布单号。
- G3 证据同源：版本详情一次返回「测试计划 → 报告三签 → 质量门 → 发布单准入」四段状态，数值来自各域，前端不重算。
- G4 不可抵赖：发布单提交时把四段状态固化为不可变快照，后续测试数据怎么变都不改快照，可按提交轮次回放。
- G5 负向真拦：计划未批、门不过、越级审批、分级缺字段分别返回 409 / 409 / 403 / 400，且不产生副作用。

## 2. 用户故事与验收标准

优先级：P0 = 本迭代必须交付并进新门禁；P1 = 影响可读性/交付卫生但可在一轮内补齐；P2 = 本轮不做。

### US-1 作为产品经理，我要默认就能走完三级发布审批，且每一级都可追溯

- **AC-1（P0）默认分级链与待办推进**：全新库完成平台种子后，`RELEASE` 的绑定为：NORMAL = 产品经理会签（PRODUCT/ALL）→ PMO 会签（PMO/ALL）→ 管理层（MGMT/ANY）；EMERGENCY = 产品经理会签 → 管理层；ROLLBACK = PMO 会签 → 平台管理员。`REQ_DEPLOY` 三个等级默认同为两级：PMO 会签 → 平台管理员。发起后实例停在 n1 且**仅 n1 生成待办**；n1 未全部通过时 n2/n3 待办数为 0；n1 完成后 n2 待办才出现。实例记录 `definitionVersion`，与所绑定定义的版本一致。
  `验证`：种子后 6 个绑定槽可读且指向对应定义；逐级查询 `/approvals/tasks` 的待办数与 `currentNode`；jq 断言节点 key 序列为 `n1,n2,n3`（NORMAL）。
- **AC-2（P0）逐级留痕与状态门槛**：每级完成写 `NODE_APPROVED`（detail 至少含 `nodeKey`、`nodeName`、`mode`），单人动作写 `APPROVE`/`REJECT`；节点未完成时单据状态不变（版本保持 LOCKED，发布单保持 PENDING_APPROVAL）；**最后一级**完成才写 `INSTANCE_APPROVED` 并触发既有回调（版本 → PUBLISHED + 需求级联；发布单 → APPROVED）。版本/发布单详情返回 `approvalLevels[]`（`nodeKey,nodeName,mode,status,approvers,handledAt,comment`），长度与顺序等于定义节点序列。
  `验证`：三级链跑完后 `wf_history` 至少 3 条 `NODE_APPROVED` 且 nodeKey 依次为 n1,n2,n3；中间态查询版本仍 LOCKED；`approvalLevels` 与历史逐节点对应，无缺失/重复。
- **AC-3（P0）驳回回退且不可跳级**：任一级驳回后，版本回 PLAN、`locked_at=null`、成员与里程碑保留，详情含 `rejectedNodeKey/rejectedNodeName/rejectedBy/rejectedAt/rejectReason`；发布单回 REJECTED（可修改后重提）。旧实例终态 REJECTED、历史保留；重提创建**新实例**并从 n1 重新开始，旧实例不复活、不复用待办；未走完全部级别的单据不得 PUBLISHED / APPROVED。
  `验证`：在 n2 驳回后版本状态为 PLAN 且 PUBLISHED 计数为 0；重提后实例 id 不同、`currentNode=n1`；驳回后调用发布/执行入口按状态返回 409，DB 状态不变。
- **AC-4（P0）存量兼容与幂等升级**：提供 `make` 可触发的幂等升级入口（幂等种子或升级脚本二选一，名称入 Makefile help）。升级前后既有 `RELEASE`/`REQ_DEPLOY` 定义行不被删除或覆盖，历史定义版本与在途实例不变；新增 6 个默认绑定与默认等级定义。连续执行两次，定义数、绑定槽数、定义版本号均不增。老的**单节点**定义仍可被绑定；绑定后一级通过即完成。在途实例升级后按其 `definition_version` 走完，不因新默认链卡死。
  `验证`：升级前后定义/实例 hash 对比；二次执行 jq 计数相等；把 NORMAL 绑回旧单节点定义后跑通一次一级审批；在途实例在升级后继续审批至终态。

### US-2 作为发版负责人，我要按发布等级选择链路，并在服务端被强制校验

- **AC-5（P0）等级字段与编辑窗口**：`release` 新增 `releaseLevel ∈ {NORMAL, EMERGENCY, ROLLBACK}`，默认 NORMAL；创建可指定，列表/详情返回。中文标签由后端统一（常规 / 紧急修复 / 回滚）。仅 PLAN 且未发起审批时可改；LOCKED/PUBLISHED/CANCELLED 修改 → 409 `RELEASE_LOCKED`；非法枚举 → 400 `VALIDATION_FAILED` 且 `detail.field=releaseLevel`。等级变更写 `audit_log`（before/after）。
  `验证`：四个状态各一次修改；非法值一次；audit 行数与内容断言。
- **AC-6（P0）分级绑定可配且生效边界清晰**：平台管理员可读取和修改 6 个绑定槽（`RELEASE`/`REQ_DEPLOY` × NORMAL/EMERGENCY/ROLLBACK），返回定义 id、名称、节点摘要、是否默认；保存写 `audit_log`（before/after），**下一次发起生效，在途实例不变**；提供「恢复默认」= 回到 AC-1 的绑定。只允许绑定同租户、同 `bizType`、已发布的定义；跨类型/未发布/不存在 → 400/409 明确错误码且不落绑定。非平台管理员 → 403。
  `验证`：把 NORMAL 改为两级定义后新建实例 2 节点、在途实例仍 3 节点；跨 bizType 绑定被拒；恢复默认后节点序列回到 AC-1；非管理员 403。
- **AC-7（P0）分级必填项在服务端校验**：EMERGENCY 的 `emergencyReason` 去空格后 1–500 字；ROLLBACK 的 `previousDeployOrderCode` 必填，且必须指向同租户、调用方可见、状态 ∈ {APPROVED, SUCCESS} 的发布单。缺失/非法 → 400 `VALIDATION_FAILED` 且 `detail.field` 指出字段。创建/修改版本与发起审批两处都校验。合法后按绑定选链，节点数分别为 3/2/2 且审批角色符合 AC-1。
  `验证`：空原因、超长原因、空单号、不存在/状态不允许的单号各 1 条 400；合法紧急/回滚各发起一次并断言节点与角色。

### US-3 作为管理层/PMO，我要在一个页面看懂版本卡在哪一段，且数字不被前端美化

- **AC-8（P0）单接口四段链路**：版本详情返回 `flowChain`：
  - `testPlan`：`total/approved/pending/allApproved/plans[{code,status,conclusion}]`（覆盖该版本需求的计划，去重）；
  - `signoff`：`reportId/versionNo/status/conclusion/levels[3]/approved/total/pendingRole`（最新一份 RELEASE 范围报告）；
  - `qualityGate`：`result/snapshotId/gateId/reportId/computedAt/failedRules/waivers`；
  - `deployAdmission`：`deployOrderId/code/status/frozen/items[9]/passedCount/failedCount`（该版本最新发布单；无单则 MISSING；有单未提交为后端实时评估 `frozen=false`；已提交为冻结快照 `frozen=true`）。
  无数据段返回 `status:"MISSING"` + `reason`，**不得返回 null**。四段均由后端聚合，`req-service` 不重算 QG-01..QG-05，只读 `deliver-service` 的判定与 `failedRules`。
  `验证`：一次 GET 版本详情取得四段；与 testplan/report/gate/deploy 明细逐项 jq 归一化后相等；连续 5 次 GET 结果一致。
- **AC-9（P1）前端只消费、不重算，且链路可读**：版本详情链路面板只使用 `flowChain`；`web/src` 中不存在第二套质量门判定、9 项准入顺序常量、或「按签署数自行推 PASS」的代码（静态检索 0 命中）；缺段显示「暂无（原因）」而不是「通过」；1280×800 下四段全部可见且无横向滚动（截图证据）。
  `验证`：源码检索 + 浏览器截图。

### US-4 作为审计者，我要看到提交当时做了什么，之后数据变化不能改写历史

- **AC-10（P0）提交快照固化并可回放**：发布单每次成功 `submit` 写入一份不可变 `flowSnapshot`：四段状态、`releaseLevel`、链定义版本、节点 key 序列、`capturedAt`、`payloadHash`。详情返回最新 `flowSnapshot` 与 `flowSnapshotHistory[]`，并支持按轮次回放。提交后即使撤销签署、改报告结论、改计划状态或使质量门失效，已提交快照的 JSON 与 hash 逐字节不变；实时链可以变化，但差异由后端给出 `changedSinceSubmit` 与段级 `stale`，前端不自行比较。
  `验证`：变更前后快照 hash 相等；同一轮次回放两次逐字段相等；实时段 `stale=true` 而快照内 `result` 不变。
- **AC-11（P0）快照的事务与写入边界**：提交被 409/503/400 拒绝时不写快照、不新增轮次；成功提交后轮次数与成功次数严格相等，轮次连续（1..N 无空洞）；快照只读（无更新/删除接口，DB 层无更新路径），且每张单据首轮快照在状态进入 PENDING_APPROVAL 的同一次提交中产生。
  `验证`：三类拒绝各一次后快照计数不变；成功 N 次后 `round` 为 1..N；代码/迁移审计无 UPDATE 快照路径。

### US-5 作为 QA/演示者，我要能复现「正常通过」和「被拦」两条路径

- **AC-12（P0）计划未批真拦**：测试计划未批准时提交发布单 → 409 `PLAN_NOT_APPROVED`，`detail.planIds` 非空；不落单/不提交/不写快照。计划批准后可正常提交。
  `验证`：构造未批计划 → create/submit；再批准 → 同一路径成功；两次 DB 行数与快照数对比。
- **AC-13（P0）质量门真拦且理由具体**：报告未三签 → 门 FAIL（`failedRules` 含 `REPORT_NOT_SIGNED` 对应的 QG-02 规则）；报告结论 CONDITIONAL 且无已批豁免 → FAIL（证据码 `REPORT_CONDITIONAL_UNAPPROVED`）；提交发布单 → 409 `QUALITY_GATE_FAILED`，detail 含 `snapshotId` 与非空 `failedRules`；单据保持 DRAFT，无快照。补齐签署或豁免后可提交。
  `验证`：两条负向各一次；断言 HTTP 码、错误码、failedRules 内容与单据状态；补齐后 same path 成功。
- **AC-14（P0）驳回后不能跳到发布**：任一级驳回后版本为 PLAN，`PUBLISHED` 计数为 0，发布回调不被触发；版本侧不存在可直接发布的 HTTP 入口（静态审计：无 publish 路由，发布只由 `INSTANCE_APPROVED` 回调触发）；审批未完成时重复调用 `POST /releases/{id}/approval` → 409（LOCKED）且不产生第二个实例；发布单为 REJECTED 时登记执行 → 409。
  `验证`：路由清单 + 两次调用 + DB 实例/状态计数。
- **AC-15（P0）分级缺字段真拦**：EMERGENCY 空原因 / 超长原因、ROLLBACK 空单号 / 不存在单号 / 状态不允许单号 → 400 `VALIDATION_FAILED`，`detail.field` 正确；版本写入被整体回滚（无半成品等级）。
  `验证`：5 条请求各一次；每条后查询版本等级/原因仍为变更前值。
- **AC-16（P0）越级/越权真拒**：n1 未完成时 n2/n3 无待办；n1 完成后，mgr1（n3 审批人）对 n2 待办 approve → 403 `FORBIDDEN`；pm 对 n2 待办 approve → 403；非 MGMT 账号对 n3 待办 approve → 403；平台管理员也不能代批非本人节点。每次后任务仍 PENDING、历史无 APPROVE。
  `验证`：按节点阶段逐个构造越权调用；断言 403 + 任务/历史不变。
- **AC-17（P0）演示文档齐备**：新增 `docs/teamflow/DEMO-RELEASE-FLOW.md`，含链路图（mermaid 或表格）、**8 步**可点脚本（角色/入口/操作/预期状态与关键字段/截图位）、正常链与被拦链各一条、演示数据恢复说明；步骤中的账号、路径、按钮名与实现一致，可直接照做。
  `验证`：文档结构检查 + 按文档实际执行（见 AC-18）。
- **AC-18（P0）真实端到端跑通**：按文档实际完成：建 NORMAL 版本 → 挂需求 → 测试计划执行完成 → 报告三签 → 质量门 PASS（snapshotId 非空）→ 发布单 9 项准入全 PASS → 提交并生成快照 → 发布单两级审批 → 版本三级审批 → PUBLISHED。每步有断言或截图；QA 报告记录命令、账号、时间与结果；`evidence/` ≥ 6 张可判读 PNG（链路面板、三签、门 PASS、准入 9 项、逐级审批、PUBLISHED）。
  `验证`：按脚本重放；截图抽查关键字段（snapshotId、节点、状态）。
- **AC-19（P0）被拦路径也可演示**：同一环境再造一条：报告未三签 → 门 FAIL → 发布单 submit 409 `QUALITY_GATE_FAILED`（failedRules 非空、无落库无快照）；并展示紧急链缺原因 400、越级审批 403 各一次。文档给出与正常链相同的可复现路径。
  `验证`：执行并记录；断言无发布单、无快照、门结果与 failedRules。

### US-6 作为团队，我要门禁只增不减、改动面可控

- **AC-20（P0）新增 `make release-flow-verify`**：脚本存在并接入 Makefile/help；≥30 条断言，分 6 段：S1 默认链与逐级待办、S2 逐级留痕与驳回回退、S3 等级绑定与 400 校验、S4 快照固化/回放/事务边界、S5 负向（409 PLAN / 409 GATE / 403 越级）、S6 老单节点兼容与在途实例。`PASS == TOTAL` 守卫；失败打印段号 + 断言名 + 实际/期望；连续执行两次结果一致且无残留单据；前置条件（服务已起、已 seed）不满足时明确失败而不是静默跳过。
  `验证`：`make release-flow-verify` 两次；人为改坏一处应失败并定位到段/断言。
- **AC-21（P0）既有门禁不缩水、无后门**：`platform-verify ≥193`、`platform-test-negative ≥130`、`access ≥206`、`rbac = 113`、`wbs-backfill-verify = 18`、`platform-demo-reset ≥69`、`unit ≥371`、`lint = 22`、`web` typecheck + build 全绿；`FORCE_RELEASE_RETIRED` 仍恒 409；静态审计无「跳级/代批/绕过审批」路由与动作码；发布单强制放行仍不可用。
  `验证`：逐条跑门禁；检索 force/skip/代批相关路由与 allowedActions。
- **AC-22（P0）改动面与仓库卫生**：迁移只新增 `V14`（不改 V1–V13）；改动限于 `platform-contract` / `platform-common` / `req-service` / `deliver-service`（只读聚合，不改门规则）/ `web`（版本详情链路面板、流程绑定页与类型/文案）/ `scripts` / `docs/teamflow`；`pom.xml` 与 `web/package.json` diff 为空；`.gitignore` 复核本栈产物（`target/`、`.nacos/`、`.run/`、`.m2/`、`logs/`、`node_modules/`、`dist/`、`.vite/`、`coverage/`、IDE、`.env*`），且不得忽略 `docs/teamflow/**/evidence/*.png`、`*.sql`、`config/nacos/`；`git status` 仅预期文件。
  `验证`：`git diff --stat`、`git diff pom.xml web/package.json` 为空、迁移目录检查、`git status --porcelain` 清单。
- **AC-23（P1）分支与提交信息**：在 `feat/release-multilevel-approval` 分支完成，不直接提交 main；收尾提交信息写清 `runId=tf-mwm516ql-s4dqpy` 与任务夹 `docs/teamflow/20261118-r30-release-approval-chain`；工作区唯一未提交项（任务夹目录）随收尾一并入库，不 stash、不丢弃。
  `验证`：`git branch --show-current`、`git log -1 --format=%B`、`git status`。

## 3. 范围与非目标

**范围内**：默认分级链与 6 个绑定槽、版本等级字段与必填校验、逐级留痕/驳回回退/403、版本详情四段同源链路、发布单提交快照与回放、前端链路面板与绑定页、演示文档与真实端到端、新门禁 `release-flow-verify`、存量幂等升级。

**非目标**：

- 不重写审批引擎，不新增审批节点类型（条件/抄送沿用现有能力）。
- 不改变测试计划生命周期、报告三签顺序、QG-01..QG-05 规则语义与豁免审批流程。
- 不改变 `AdmissionPolicy` 九项定义与顺序、不放宽 `DeployPlanGuard` 与 `FORCE_RELEASE_RETIRED`。
- 不做发布单的「执行编排/回滚执行」改造，不新增发布窗口/制品管理能力。
- 不做移动端适配，不引入新的 UI 组件库或设计体系。
- 不做跨租户审批链模板市场/导入导出。

## 4. anti-goals / 反面目标

以下任一发生，即使测试全绿也应被否决：

1. **待办抢跑**：n1 还没批，n2/n3 的待办已经躺在审批中心；或 n3 能直接处理。审批链只是画在文档里。（可检验：逐级查询待办数与 `currentNode`。）
2. **伪快照**：发布单提交后撤销一次签署，快照里的三签/门结果跟着变；或同一单两次打开 hash 不同。「可回放」是假的。（可检验：改数据前后 GET 快照并比对 hash。）
3. **只拦前端**：绕过页面直接 `curl` 提交，未三签也能 200 并生成快照；演示里的「被拦」只是一个弹窗。（可检验：直调 API。）
4. **升级即事故**：升级脚本删掉旧单节点定义，在途版本卡在 n2 推不动，历史审批记录查不到。（可检验：升级前后定义/实例对比。）
5. **为演示放水**：给未签报告开绿灯、条件通过默认豁免、或恢复强制放行；链路演示全绿但质量门失效。（可检验：既有负向套件与 `FORCE_RELEASE_RETIRED` 仍红。）

## 5. 交互流程摘要

| # | 角色 | 入口 | 操作 | 预期 |
|---|---|---|---|---|
| 1 | 平台管理员 | 管理 → 流程绑定 | 查看 RELEASE/NORMAL 三级链与 6 个槽 | 节点依次 产品→PMO→管理层，标记「默认」 |
| 2 | 产品经理 | 版本 → 新建 | 建 NORMAL 版本，挂 1 条需求 | 详情出现「发版链路」，测试段为待办 |
| 3 | 测试 | 测试计划 | 提交评审 → 批准 → 执行完成 | 链路测试段 allApproved=true |
| 4 | 测试负责人→产品→PMO | 测试报告 | 依次三签 | 链路签署段 3/3，状态 SIGNED |
| 5 | 测试/产品 | 质量门 | 评估 | 门 PASS，链路显示 snapshotId |
| 6 | 产品经理 | 发布单 | 创建 → 查看准入 9 项 → 提交 | 9 项全 PASS；生成 flowSnapshot（round=1） |
| 7 | 产品→PMO→管理层 | 审批中心 | 依次审批版本发布 | 每级一条 NODE_APPROVED；最后一级后 PUBLISHED |
| 8 | PMO→平台管理员 | 审批中心 | 审批发布单 → 登记执行 | 两级通过后 APPROVED；执行 SUCCESS |

**被拦路径**：步骤 4 只完成 2/3 签 → 步骤 5 门 FAIL（`failedRules` 非空）→ 步骤 6 提交 409 `QUALITY_GATE_FAILED`，无单据落库、无快照；补齐签署后同路径通过。

## 6. 优先级

- P0（必须）：AC-1～AC-8、AC-10～AC-22。
- P1（本轮补齐）：AC-9（前端可读性）、AC-23（提交与分支卫生）。
- P2：无。

## 7. 依赖与风险

| 依赖/风险 | 说明与缓解 |
|---|---|
| 审批引擎（r26） | 多节点串行与逐级留痕已具备；风险是「所有节点一次性建待办」的误改。以 AC-1/AC-16 的待办数断言锁住。 |
| deliver 三签/质量门 | 门结论只有 PASS / PASS_WITH_WAIVER / FAIL；未签与 CONDITIONAL 无豁免都落 FAIL。`req-service` 只读，不重算规则。风险是两服务契约漂移，用 AC-8 的 jq 全等与 `failedRules` 原样透传约束。 |
| 准入九项 | 复用 `AdmissionPolicy` 固定顺序与四态；链路里的 admission 段与发布单提交快照必须同一来源。风险是出现第二套 9 项实现，用 AC-9 静态检索拦。 |
| 数据迁移 | 新增 `V14`、`releaseLevel` 默认 NORMAL、快照表；风险是老库升级与在途实例。用幂等升级、`definitionVersion` 固定、AC-4 的双跑与在途用例缓解。 |
| 演示数据 | `platform-demo-reset` 会清空报告/门/签署，重置后没有现成三签与门 PASS。端到端需现场造计划并走三签；风险是步骤长、易漏。用 AC-17 固定 8 步与预期状态、AC-18 记录命令与截图缓解。 |
| 链路性能 | 版本详情聚合四个域，可能有 N+1。技术方案须批量查询；验收以连续 5 次一致与页面可读为准，不新增 p95 指标。 |
| 门语义词 | 需求原文提到「门 CONDITIONAL」，现有门只有三值；本 PRD 按「报告结论 CONDITIONAL 无豁免 → 门 FAIL」验收，不新增门结论值，避免影响全部既有门禁。 |

## 8. 工程约束

- **分支**：在 `feat/release-multilevel-approval` 上完成（当前分支，基线 main=79e1f1c）；不得直接提交 main。
- **未提交改动**：当前仅有 `?? docs/teamflow/20261118-r30-release-approval-chain/`（本任务夹）。保留并随收尾提交入库，不得 stash、丢弃或回滚。
- **提交时机与信息**：实现、门禁、演示与证据全部齐备后收尾提交；提交信息必须写清 `runId=tf-mwm516ql-s4dqpy` 与任务夹 `docs/teamflow/20261118-r30-release-approval-chain`，例如 `feat: 发版多级审批链与端到端链路（runId=tf-mwm516ql-s4dqpy，任务夹 docs/teamflow/20261118-r30-release-approval-chain）`。
- **版本库卫生**：开发阶段审计 `.gitignore` 是否覆盖本项目真实产物（Maven `target/`、`.nacos/`、`.run/`、`.m2/`、`logs/`；web `node_modules/`、`dist/`、`.vite/`、`coverage/`、`*.tsbuildinfo`；IDE；`.env*`；本机 dump），只补真实遗漏；不得忽略 `docs/teamflow/**/evidence/*.png`、`*.sql`、`config/nacos/`；门禁临时件统一落 `.run/` 或 `logs/teamflow/<runId>/`，收尾 `git status` 只应出现预期文件。
- **迁移**：只新增 `V14__*.sql`，不修改 V1–V13；`pom.xml` 与 `web/package.json` 无 diff。
- **门禁纪律**：不放宽、不删除既有断言；新脚本 `PASS == TOTAL`；`platform-verify`、`negative`、`access`、`rbac`、`wbs-backfill-verify`、`demo-reset`、`unit`、`lint`、`web` 全部不低于 AC-21 下限。
- **不可动语义**：`FORCE_RELEASE_RETIRED` 恒 409；不给任何角色增加跳级/代批能力；前端不做门禁判定；`DeployPlanGuard` fail-closed 语义不变。
- **文档边界**：只写任务夹、`docs/teamflow/DEMO-RELEASE-FLOW.md` 与（必要时）`memory.md` 同主题行；不写宿主 `docs/<role>/`，不散落日志。

## 9. 假设与待澄清

- **（事实）回滚链「上一发布单号」的合法范围**：需求只说「必须引用上一发布单号」，未定义状态集合。本 PRD 假定为：同租户、调用方可见、状态 ∈ {APPROVED, SUCCESS}，允许引用同版本的上一张已批单（新版本自身尚无已批单，不会自引用）。若要求必须跨版本（`releaseId` 不同），只需在服务端加一条过滤，AC-7 的状态集合不变。
- **（取舍）管理层「单人」的范围**：默认 MGMT/ANY，任一管理层成员可批；若要求固定分管人，改绑定即可，但种子需补人员映射，演示脚本也要换账号。
- **（取舍）驳回后重提的起点**：默认创建新实例、从 n1 重走（与引擎「新实例」语义一致，避免跨轮次留痕混杂）；若要求从被驳回级别续审，需要新增「从指定节点启动」能力，工作量与留痕规则都会变。
- **（取舍）发布单是否也按等级差异化**：默认 6 个槽都可配，但三个等级默认同链（PMO 会签 → 平台管理员）；若紧急发布单也要走不同链，只改默认绑定，不改代码。
- **（取舍）老库升级后的默认绑定**：默认自动切到新的多级链（保证开箱可演），旧单节点定义保留、可随时回绑；若要求升级后仍保持单节点、由管理员手动切换，把默认绑定指回旧定义即可，但「默认可演」的演示前提需要额外一步。

## 10. 里程碑建议

- **M1 链路与配置（AC-1、AC-4、AC-5、AC-6、AC-7）**：V14、默认定义、绑定页/接口、等级字段与 400 校验、幂等升级；出口 = 新库三级/两级可发起，老库升级双跑幂等。
- **M2 审批行为（AC-2、AC-3、AC-14、AC-16）**：逐级待办/留痕、驳回回退、越级 403；出口 = 一条三级链与一条驳回链跑通。
- **M3 链路与快照（AC-8、AC-9、AC-10、AC-11）**：`flowChain` 聚合、前端面板、提交快照与回放；出口 = 快照 hash 在数据变更后不变。
- **M4 门禁与演示（AC-12、AC-13、AC-15、AC-17～AC-23）**：负向、`release-flow-verify`、真实端到端、被拦演示、证据与提交；出口 = 全部门禁达标且 `git status` 干净。
