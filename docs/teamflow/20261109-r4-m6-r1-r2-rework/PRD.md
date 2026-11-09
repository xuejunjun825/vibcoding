<!-- meta: summary="窄范围返工修复 M6 验收遗留 R1（发布单创建/更新引用 DRAFT/PENDING_REVIEW 测试计划时在写库前 409 PLAN_NOT_APPROVED 并返回 planIds）与 R2（历史 5 项快照与无快照单据返回 legacy=true 且前端渲染可达的「质量门未启用」提示），顺带把 QG-01 空需求集从 100% 改为 N/A，使 M6 验收由「有条件通过」转为「通过」。" -->
# M6 验收返工：计划批准硬校验 · 历史快照门禁标识 · QG-01 空集 N/A

基线依赖：docs/teamflow/20261109-r3-qa-quality-management/（M6 已交付的质量域实现、20 条 AC 语义与 308 条质量域断言；其 AC-2~AC-4、AC-6~AC-10 行为与门禁/聚合/指标/隔离/状态机单点实现不得回归）

取代：docs/teamflow/20261109-r3-qa-quality-management/#AC-5：QG-01 在版本无需求可覆盖时由隐含的「覆盖 100% 通过」改为显式 N/A（覆盖子项不计分母、不计 FAIL，报告子项仍按原规则），其余质量门规则与判定顺序不变。

> 验收依据：docs/teamflow/20261109-r3-qa-quality-management/ACCEPTANCE.md §4（R1/R2 缺陷与 Q-01 观察项）。本轮实现已提交在分支 `feat/qa-quality-management`（15cd53c），在本分支继续，**先修再合并**。

---

## 1. 背景与目标

### 1.1 背景

M6 质量管理补全（r3）验收结论为「有条件通过」：功能面全绿，但 §4 留下两条缺陷与一条观察项，都属「文档写了、实现没兜住」：

1. **R1（P2，阻塞 AC-1.4）**：`POST /api/req/deploy-orders` 在创建发布单时不查询覆盖需求的测试计划。实测把 DRAFT 计划 142 覆盖的需求 5 放进发布单，接口返回 **201** 且 `deploy_order` 行数 115→116。未批准计划因此在创建期被引用并留下脏关联；虽然提交期仍会被 QG-02（已签报告）拦住，但违反 AC-1.4 的准入输入治理。
2. **R2（P3，AC-7.5 展示）**：历史 5 项准入快照（不含 `QUALITY_GATE`）在 API 侧 `legacy=true`、`gate=null`，但 `QualityGatePanel.vue:232` 的提示条件要求「存在 `QUALITY_GATE` 项」，而历史快照按定义没有该项，条件不可达；`AdmissionEvaluator.java:119-120` 对 NULL 快照还返回 `legacy=false`。结果是页面既不显示「质量门未启用」，也不伪造 PASS——**沉默**，审阅者无从判断历史单据到底有没有过门禁。
3. **Q-01（P3）**：`GateEvaluator.java:350` 用 `total == 0 ? 100`，空需求集合时 QG-01 显示「需求覆盖 100%」，把「无需求可覆盖」伪装成一个漂亮的通过数字。

本轮只修这三项，不重做质量域、不改架构单点、不动其它 AC 与 r1/r2 既有行为。

### 1.2 目标

1. **R1 在写库前拦截**：创建（及更新路径共用的校验单点）组装需求后，按需求查询覆盖它的测试计划；存在 `DRAFT`/`PENDING_REVIEW` 计划 → HTTP 409 `PLAN_NOT_APPROVED`，body 列出 `planIds` 与修复指引，`deploy_order`/`deploy_order_requirement` 行数不变。已批准计划、无计划 → 201（保持 r2 行为）。
2. **R2 让「未启用」可见**：5 项快照与无快照单据的 API 一律 `legacy=true`、`gate=null`；前端在「legacy 且无 `QUALITY_GATE` 项」时渲染一条合成提示「质量门未启用（历史单据）」，不伪造 PASS、不改变通过/失败判定、空 items 也不崩。
3. **Q-01 不再假绿**：空需求集时 QG-01 覆盖子项判 **N/A**，不显示 100%，不计覆盖分母、不计 FAIL；报告子项与既有非空口径不变。
4. **可复验地转通过**：原 308 条断言全绿并新增断言，r3 §4 的 R1/R2 转为 ✅，M6 验收结论由「有条件通过」改为「通过」。

### 1.3 成功判据（硬门禁）

```
bash scripts/quality-verify.sh          # 原 308 条 + 新增断言，全绿
bash scripts/platform-verify.sh         # 167/167
bash scripts/platform-test-negative.sh  # 25/25
bash scripts/platform-unit.sh           # 133/133
bash scripts/tests/lint.sh              # 16/16
bash scripts/verify.sh                  # 14/14
npm --prefix web run typecheck          # 0 error
npm --prefix web run build              # exit 0
```

---

## 2. 用户故事与验收标准

### 2.1 用户故事总览

| 编号 | 角色 | 故事 | 对应 AC |
|---|---|---|---|
| US-1 | 发布经理 | 我在建发布单时如果引用了未批准的测试计划，希望**当场**被拒绝并看到是哪几个计划、怎么修，而不是建完才发现关联是脏的。 | AC-1 ~ AC-4 |
| US-2 | 发布经理 / 审批人 / 审计 | 我看 M6 之前的历史发布单时，要明确看到「质量门未启用（历史单据）」，而不是面对一片空白去猜它是否通过。 | AC-5 ~ AC-6 |
| US-3 | 测试负责人 / PMO | 版本没有需求可覆盖时，QG-01 应显示 N/A 而不是 100%，避免用一个虚高的覆盖率数字误导发布决策。 | AC-7 |
| — | 团队 | 上述修复不引入回归，M6 验收从「有条件通过」转「通过」。 | AC-8 |

### 2.2 R1 · 发布单创建/更新的计划批准校验

**AC-1（P2｜创建 409，写库前判定）**
- 给定需求 `r` 被计划 `p` 覆盖（`test_plan_case.requirement_id = r`），且 `p.status ∈ {DRAFT, PENDING_REVIEW}`；
- 当调用 `POST /api/req/deploy-orders`，请求体 `requirementIds` 含 `r`（显式指定或按版本带出）；
- 那么返回 **HTTP 409**，错误码 `PLAN_NOT_APPROVED`，body 的 `planIds` 数组包含 `p.id`（覆盖本次请求中所有未批准计划，去重）并带可操作的修复指引（先完成计划评审批准，或移除对应需求后重试）；
- 且 `deploy_order` 行数、`deploy_order_requirement` 行数前后不变，不产生 CREATE 审计记录（事务不落库）。

**AC-2（P2｜更新同规则、校验单点）**
- 若发布单存在更新入口（修改需求集合），同一校验必须在更新写库前执行，409 时原单字段与关联不变；
- 无论更新端点是否在本轮落地，校验只能有**一个实现点**（create/update 共用的私有方法），不得在控制器或第二个服务里复制判断；
- 当前代码中没有发布单更新端点（`DeployOrderController` 仅 POST 创建、无 PUT/PATCH），本轮默认不新增端点，只保证校验单点可复用（见 §9 Q2）。

**AC-3（P2｜正例与已批准口径）**
- 覆盖计划状态 ∈ `{APPROVED, IN_PROGRESS, COMPLETED, CLOSED}` → **201** 正常创建（这四态是 `PlanLifecycle.java:73` 定义的「已批准集合」；不得按字面 `status != APPROVED` 把执行中/已完成计划误判为未批准）；
- 需求**没有任何**覆盖计划 → **201**（保持 r2 既有行为，不得因修复而堵死无计划流程）；
- 同一请求重复调用不会因已存在的发布单而改变判定（校验只读）。

**AC-4（P2｜取数边界与不可用策略）**
- req-service 只能通过既有只读模型/内部只读接口查询 deliver 的 `test_plan` + `test_plan_case`，**不得直连 deliver 表**、不得新增跨库 JOIN、不得复制质量门/聚合实现；
- 查询超时或 deliver 不可用时默认 **fail-closed**：HTTP 503 `PLAN_CHECK_UNAVAILABLE`，不落单（与 AC-5.5 质量门 fail-closed 同向）；该默认值见 §9 Q1；
- 校验为只读，不写 deliver 任何表，不改变计划状态。

### 2.3 R2 · 历史 5 项快照的「质量门未启用」展示

**AC-5（P3｜API 快照语义）**
- 对 `admission_result_json` 存在、含 5 项且不含 `QUALITY_GATE` 的发布单：`GET /api/req/deploy-orders/{id}` 的 `admission.legacy = true`、`admission.gate = null`、`items` 仍为 5 项且**不伪造**第 6 项；
- 对 `admission_result_json` 为 NULL 的发布单：同样 `legacy = true`、`gate = null`、`items = []`（修正 `AdmissionEvaluator.java:119-120` 当前 `legacy=false`）；
- 正常 6 项快照保持 `legacy = false`、`gate` 原样，判定结果不受影响。

**AC-6（P3｜前端提示可达）**
- `QualityGatePanel` 在 `legacy === true` 且 `items` 中不存在 `QUALITY_GATE` 项时，渲染**一条**合成提示行/横幅，文案含「质量门未启用（历史单据）」，并说明「不补写 PASS，也不显示快照 hash」；
- 该条件对 5 项快照与空 items 都必须可达；空 items 时提示行独立渲染、页面不报错；
- 提示**不得**伪造 PASS/通过徽标、不得显示门禁 hash、不得改变 `passed`/提交按钮的判定逻辑；非 legacy 的正常快照不出现该提示；
- 断言方式（当前 web 无单测框架）：q5 增加源码静态断言（新可达条件与文案存在、旧不可达条件已移除）+ `npm run typecheck` + `npm run build exit 0`；浏览器视觉项入人工补测清单。

### 2.4 Q-01 · 空需求集的 QG-01

**AC-7（P3｜N/A 而非 100%）**
- 当发布单需求集合为空（`totalRequirements = 0`）时，QG-01 的覆盖子项判定为 **N/A**：`actual` 显示「N/A（无需求可覆盖）」，不得出现「100%」；证据中 `totalRequirements = 0`，覆盖百分比为空/不适用；
- N/A 不计入任何覆盖率/通过率分母、不计 FAIL；门禁整体不被该规则阻断（其余 4 条规则照常判定）；
- 报告子项不变：空需求但无已签报告时，QG-01 仍以「无已会签报告」FAIL，不得用 N/A 掩盖；
- `totalRequirements > 0` 时口径完全不变（`floor(covered*100/total)` 与阈值比较、证据字段、文案均保持）；
- 若技术评估表明新增规则状态会破坏既有快照/前端兼容，可降级为：保留 `pass=true` 的真空满足语义，但 `actual` 与 `evidence` 显式标 N/A 并加注释——不得改其它规则、不得改变已有 AC 语义。

### 2.5 回归与验收

**AC-8（P0｜合入门禁）**
- `quality-verify` 原 308 条断言全绿，且新增断言 **≥ 6 条**：q1 ≥ 3（DRAFT 409 + `planIds` 命中 + 行数不变；APPROVED 201；无计划 201）、q5 ≥ 2（API `legacy=true/gate=null`；前端提示可达的静态断言）、q4 ≥ 1（空需求集 QG-01 N/A）；
- `platform-verify 167/167`、`platform-test-negative 25/25`、`platform-unit 133/133`、`lint 16/16`、`verify 14/14`、web `typecheck` 0 error、`build` exit 0；
- 任务夹更新 QA-REPORT（修复点、复验命令、前后数字）与 ACCEPTANCE：R1/R2 转 ✅，M6 验收结论由「有条件通过」改为「通过」；
- 提交面干净：无构建产物、依赖目录、日志噪声入库；修复在本分支提交，**全绿后才合并**。

---

## 3. 范围与非目标

### 3.1 范围

- `req-service`：创建发布单的计划批准校验（含共享校验点、内部只读取数、409/503 错误体）；`AdmissionEvaluator` 对 NULL/5 项快照的 `legacy` 修正。
- `deliver-service`：仅新增/复用一个按需求查覆盖计划的内部只读接口（若既有接口不足）；QG-01 空集 N/A 的最小改动。
- `web`：`QualityGatePanel` 的 legacy 提示条件与合成提示行。
- 测试：`scripts/tests/quality/q1`、`q4`、`q5` 新增断言与夹具；任务夹文档更新。

### 3.2 非目标

- 不重做质量域架构：`GateEvaluator`/`QualityAggregator`/`MetricFormulas`/`ScopeSql`/`StateTables` 仍各自只有一份实现，不新增第二份门禁/计划状态判断。
- 不修改 AC-2/AC-3/AC-4/AC-6/AC-8/AC-9/AC-10 的语义与判定；不改发布准入 6 项规则、豁免、报告、轮次、Go/No-Go。
- 不清洗存量脏关联：修复前已用未批准计划创建的发布单不回溯删除/改状态；如需数据治理另立需求。
- 不新增前端测试框架（不引入 vitest），沿用静态断言 + typecheck/build；不新增发布单更新端点（默认，见 Q2）。
- 不处理 Q-02 及其它 P3 观察项；不改 r1（Spring Cloud 骨架）任何行为；不引入新依赖、不改版本矩阵。
- 不做视觉改版：仅在既有面板内加一条提示行，沿用现有样式变量。

---

## 4. anti-goals / 反面目标

> 即使测试全绿，出现以下任一情况，人类验收者仍应拒绝本轮交付。

1. **「拒绝」了但单子还在**：接口返回 409，刷新列表却多出一张发布单（`deploy_order` 或 `deploy_order_requirement` 行数 +1，或审计里多一条 CREATE）。这是最容易被「状态码断言」漏掉的假修复。
2. **把「无计划」一起杀掉**：修复后凡是版本没有测试计划就建不了发布单（201 变 409），r2 的存量流程整体被堵死；正确结果是「无计划 201、未批准 409」。
3. **历史单据被洗绿**：页面为消除空白而显示「质量门 PASS/已通过」，或 API 的 `gate` 非 null、补一条伪造的第 6 项 PASS——审阅者据此误判历史发布单已过质量门，比不显示更糟。
4. **空集只是换个说法**：N/A 文案下仍输出「100%」，或反过来把无需求版本整门判 FAIL 锁死；用户看到的数字必须真的不再暗示「覆盖完整」。
5. **修复引入第二套真相**：req-service 里出现对 deliver 表的 SQL，或复制一份「已批准计划状态集合」；下一次状态机调整时两处漂移，门禁结论随入口不同而不同。

---

## 5. 交互流程摘要

### 5.1 创建/更新发布单（R1）

1. 发布经理选择版本、环境、时间窗与需求集合 → `POST /api/req/deploy-orders`。
2. 服务端解析需求（显式优先，否则按版本带出 RD 需求）→ **写库前**调用计划覆盖校验。
3. 命中 `DRAFT`/`PENDING_REVIEW` 覆盖计划 → 事务回滚，返回 `409 PLAN_NOT_APPROVED`，前端在表单顶部展示「计划未批准」+ `planIds` + 指引（去批准计划 / 移除需求）。
4. 已批准或无覆盖计划 → 正常 201 创建，后续提交仍走原准入与质量门（行为不变）。
5. 更新路径（若存在）执行同一校验单点，不重复实现。

### 5.2 历史单据详情（R2）

1. 打开 M6 之前的发布单 → `GET /deploy-orders/{id}` 返回 `admission: {legacy:true, gate:null, items:[…5 项或空]}`。
2. 面板据「legacy 且无 `QUALITY_GATE` 项」渲染合成提示行「质量门未启用（历史单据）」；5 项结论照常展示，不补第 6 项、不显示 hash。
3. 正常 6 项快照不出现该提示；提交路径不受影响。

### 5.3 空需求集门禁（Q-01）

1. 版本无需求可覆盖 → 求值质量门。
2. QG-01 覆盖子项输出 `N/A（无需求可覆盖）`，证据 `totalRequirements=0`；不显示 100%，不计分母、不计 FAIL。
3. 若同时无已签报告，QG-01 仍以报告缺失 FAIL；其余规则照常。

---

## 6. 优先级与里程碑

### 6.1 优先级

| 优先级 | AC | 说明 |
|---|---|---|
| P0 | AC-8 | 合入门禁与验收结论转换；不绿不合并 |
| P2 | AC-1 ~ AC-4 | R1，阻塞 AC-1.4，先修先验 |
| P3 | AC-5 ~ AC-7 | R2 展示 + Q-01 观察项，随本轮一并关闭 |

### 6.2 里程碑建议

| 里程碑 | 内容 | 出口 |
|---|---|---|
| M1 | R1：校验单点 + 内部只读取数 + 409/503 错误体 | q1 新增 3 条断言绿，行数不变 |
| M2 | R2：`legacy` 修正 + 面板可达提示 | q5 的 API/静态断言绿，typecheck/build 绿 |
| M3 | Q-01：空集 N/A + 注释 | q4 新增断言绿，非空口径不变 |
| M4 | 全量回归 + QA-REPORT/ACCEPTANCE 更新 + 本分支提交 | §1.3 全部命令绿；「有条件通过」→「通过」；**之后**再合并 |

---

## 7. 依赖与风险

| 项 | 说明 | 应对 |
|---|---|---|
| deliver 只读接口 | req-service 需要按需求查覆盖计划；当前 deliver 仅有缺陷内部接口，可能需新增内部只读端点 | 在技术方案中定端点与令牌/超时；只读、不落库、不直连表 |
| 计划状态口径 | 字面「status != APPROVED」会误伤 IN_PROGRESS/COMPLETED/CLOSED | 以 `PlanLifecycle.java:73` 的已批准集合为准，AC-3 补 IN_PROGRESS/COMPLETED 用例 |
| 种子无历史快照 | r3 记录种子库 5 项快照为 0 条 | q5 构造专用夹具（`admission_result_json` 不含 QUALITY_GATE 与 NULL 两种） |
| 前端无测试框架 | web 无 vitest，无法做组件级断言 | 静态断言 + typecheck/build + 人工补测清单，不新增框架 |
| 空集 N/A 改动面 | 新增规则状态可能触及快照/前端枚举 | 优先只改 `actual`/`evidence` 文案与证据，保留真空满足语义（AC-7 降级路径） |
| 修复与合并顺序 | 先修再合并；未全绿合并会把缺陷带入主干 | M4 全绿后才合并；合并动作由用户/宿主确认后执行 |

---

## 8. 工程约束（原文保留）

- **分支与基线**：实现已提交在分支 `feat/qa-quality-management`（15cd53c）。原文：**「请在本分支上继续，不要开新分支。」** 不得新建分支、不得 rebase 改写已提交历史；**先修再合并**。
- **未提交改动**：工作区现有 1 处未提交改动 `?? docs/teamflow/20261109-r4-m6-r1-r2-rework/`（本任务夹）。保留并随本轮修复一并提交，**不得** `git clean`/`git stash`/回滚该目录；提交时只纳入修复代码、断言与任务夹文档，不带构建/依赖/日志噪声。
- **修复位置（原文）**：
  - R1：`req-service/src/main/java/com/example/req/deploy/DeployOrderService.java:152` 的 `create(...)`（update 同样处理）。
  - R2：`web/src/components/quality/QualityGatePanel.vue:232`；`req-service/src/main/java/com/example/req/deploy/AdmissionEvaluator.java:119-120`。
  - Q-01：`deliver-service/src/main/java/com/example/deliver/quality/gate/GateEvaluator.java:350`。
- **R1 规则（原文）**：组装需求后按需求查覆盖它的测试计划（req-service 侧通过既有只读模型/接口查询 deliver 的 `test_plan` + `test_plan_case`，**不得直连 deliver 表**）；若存在计划且其状态 ∉ APPROVED（DRAFT/PENDING_REVIEW）→ HTTP 409，错误码 `PLAN_NOT_APPROVED`，body 含 `planIds` 数组与修复指引；无计划时保持 r2 行为（允许创建）。
- **R1 判定时机（原文）**：要求 409 在写入前判定，`deploy_order` 行数不变；已批准计划/无计划 → 201。
- **R2 规则（原文）**：API 侧已正确（5 项快照 → `legacy=true`、`gate=null`；NULL admission 需修正为 `legacy=true`）；前端 `QualityGatePanel.vue:232` 的提示条件改为「legacy 且无 `QUALITY_GATE` 项」即可达；渲染一条合成提示行/横幅「质量门未启用（历史单据）」，不伪造 PASS、不影响通过/失败判定。
- **Q-01 规则（原文）**：空需求集合时 QG-01 应判 N/A（不计通过率分母、不计 FAIL）而不是 100%，补一条断言；若判断会扩大改动面可只加注释与断言，不改变已有 AC 语义。
- **断言要求（原文）**：q1 加 AC-1.4 用例（DRAFT 计划 409 + `planIds` 含目标计划 + 行数不变；APPROVED 计划 201；无计划 201）；q5 加一条历史 5 项快照夹具（`admission_result_json` 不含 `QUALITY_GATE`）+ 断言 `legacy=true` 且前端渲染出「未启用」行（以既有前端测试/构建断言方式）。
- **范围纪律（原文）**：只碰上述三项；不改架构单点（`GateEvaluator`/`QualityAggregator`/`MetricFormulas`/`ScopeSql`/`StateTables` 仍各一份）、不动其它 AC、不改 r1/r2 既有行为；更新任务夹 acceptance/QA 记录，写清修复点与复验命令。
- **验收判据（原文）**：`bash scripts/quality-verify.sh` 全绿（原 308 条 + 新增断言）、`platform-verify 167/167`、`platform-test-negative 25/25`、`platform-unit 133/133`、`lint 16/16`、`verify 14/14`、web typecheck 0 error、构建 exit 0；ACCEPTANCE 里 R1/R2 转 ✅、验收结论从「有条件通过」改为「通过」。
- **版本控制卫生（dev 阶段动作）**：复核现有 `.gitignore` 对本项目栈的覆盖并只补缺口——Java/Maven（`target/`、`*.class`、`*.jar`、`.flattened-pom.xml`、`.m2/`）、Nacos（`.nacos/`）、前端（`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`）、本地运行态（`.run/`、`var/`、`*.hprof`）、日志与密钥（`logs/`、`logs/teamflow/`、`*.log`、`.env`、`.env.*`，保留 `.env.example`）、IDE/OS（`.idea/`、`*.iml`、`.vscode/`、`.DS_Store`）。**不得**把 `docs/teamflow/20261109-r4-m6-r1-r2-rework/`、`config/nacos/`、`scripts/` 加入忽略；提交前确认 `git status` 只有预期文件。

---

## 9. 假设与待澄清

- **Q1（kind: taste）计划校验遇 deliver 不可用/超时怎么办？** 默认 **fail-closed**：返回 `503 PLAN_CHECK_UNAVAILABLE` 且不落单（与质量门 AC-5.5 的 fail-closed 同向，避免「没校验就当通过」）。另一种合理选择是 fail-open（201 放行、可用性优先），代价是可能继续产生脏关联。若改 fail-open，AC-4 的 503 断言改为 201 放行断言。
- **Q2（kind: fact）「update 同样处理」指什么？** 当前 `DeployOrderService` 只有 `create`，`DeployOrderController` 没有 PUT/PATCH 更新端点（全仓无发布单更新入口）。默认理解为「校验必须收敛为 create/update 共用的单点，本轮不新增更新端点」；若你要求本轮新增发布单更新接口，需追加端点、权限与断言，范围会超出窄范围返工。
- **Q3（kind: taste）同一需求被多条计划覆盖时如何判？** 例如一条已批准计划 + 一条新草稿计划同时覆盖需求 r。默认按判据字面**严格**：只要存在任一 `DRAFT`/`PENDING_REVIEW` 覆盖计划即 409（防止草稿计划与该需求形成脏关联）；另一种选择是「存在至少一条已批准计划即放行」（更贴近实际：草稿可能只是下一轮准备）。若选后者，AC-1/AC-3 的断言需增加多计划组合用例。
- **Q4（kind: fact）「ACCEPTANCE 里 R1/R2 转 ✅」改哪一份？** 依据 ADR-0008，历史任务夹创建后不可变，默认 **不直接改写** `docs/teamflow/20261109-r3-qa-quality-management/ACCEPTANCE.md`，而在本轮 `docs/teamflow/20261109-r4-m6-r1-r2-rework/ACCEPTANCE.md` 中给出 R1/R2 ✅ 与「M6 验收结论：通过（取代 r3 §4 的有条件通过）」的结论声明。若你要求直接修改 r3 历史文件，请明确，否则按不可变策略执行。
