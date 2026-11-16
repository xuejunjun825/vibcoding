<!-- meta: summary="给需求流转补服务端前置门：产生 PDR 要求父 BUS 已入池（409 PARENT_NOT_POOLED），产生 RD 要求父 PDR 已排期或开发中（409 PARENT_NOT_SCHEDULED），拆解/直建/改父三条写入口同码同结构；并把「漏填业务域」等提报裸 500 收敛为 400 VALIDATION_FAILED（指名字段），前端按同一判定收敛拆解入口。" -->
基线依赖：docs/teamflow/20261116-r22-action-matrix-datadriven（其「需求动作经 ActionPermissionResolver 数据驱动判定」与 16 动作契约不得回归）

# PRD · r23 需求流转前置门与提报必填校验

## 1. 背景与目标

`docs/teamflow/FLOW-CONTROL-AUDIT.md`（基线 main=535939a）逐条核对了需求全流程 8 个管控点，其中两条被实测证伪：

- **G1（审批旁路）**：`RequirementService.decompose` 只用 `requireWritable()`（仅拦终态），没有父需求状态前置。实测 `BUS-0391` 处于 `SUBMITTED`，`pm` 直接 `POST /api/req/requirements/{id}/decompose` → **201** 生成 `PDR-0149`，并可立即排期。等于「未审批入池的业务需求」可以直接进入交付链路。
- **G3（裸 500）**：`POST /api/req/requirements` 不带 `domainIds` → **500** `DataIntegrityViolationException: Column 'domain_id' cannot be null`。遗漏一个字段，用户看到的是服务端错误而不是「缺哪个字段」。

本轮（R-A）只做这两件，目标是把「管控点靠流程约定」变成「不满足条件时系统直接拒绝」：

1. **流转前置门（所有写入口同一套语义）**：产生产品需求（BUS→PDR，无论拆解还是直建）要求父业务需求已 `POOLED`；产生研发需求（PDR→RD，无论拆解、直建还是改父）要求父产品需求已 `SCHEDULED` 或 `IN_DEV`。拒绝时 409 + 稳定错误码 + 结构化 detail，三条入口**同码同结构**，前端据此收敛入口（**不得只在前端拦**）。禁止出现「拆解路径拦、直建路径不拦」的两套语义。
2. **提报必填校验**：`domainIds` 缺失/为空/含空值 → 400 `VALIDATION_FAILED` 且 detail 指名字段；顺带把同类必填的裸 500 全部核查一遍，能收敛的收敛成 400/403，逐条写进 ACCEPTANCE。

本轮不新增状态、不改状态机表语义；`decompose` 仍走 `req:split` 路由守卫，不进入 16 动作矩阵。

## 2. 用户故事与验收标准

### A. 拆解前置门（后端）

**US-1** 作为评审人，我不希望未入池的业务需求被拆成产品需求，否则审批环节形同虚设。
**US-2** 作为交付负责人，我不希望产品需求还没排期就被拆出研发需求，否则排期与容量评估被绕过。

- **AC-1（P0）** 父 BUS 非 `POOLED`（`SUBMITTED` / `APPROVING` / `PENDING_REVIEW` / `CLOSED` 之外同理）时，`POST /api/req/requirements/{busId}/decompose` → **409**，`code=PARENT_NOT_POOLED`，`detail` 恰含 `{parentId, parentStatus, required:"POOLED"}`。且**不落库**：`requirement` 行数不变、父需求无新子需求、`audit_log` 无 `DECOMPOSE` 记录、无 `PDR-####` 新编号消耗（编号可消耗但不产生业务行）。
- **AC-2（P0）** 父 PDR 不在 `{SCHEDULED, IN_DEV}` 时（含 `PENDING_ANALYSIS` / `DELIVERED` / `ACCEPTED_CLOSED`），`POST .../{pdrId}/decompose` → **409**，`code=PARENT_NOT_SCHEDULED`，`detail` 恰含 `{parentId, parentStatus, allowed:["SCHEDULED","IN_DEV"]}`。同样不落库。
- **AC-3（P0）** 同一判定覆盖**直建入口**，两条都要：
  - `POST /api/req/requirements` 带 `type=PDR` + `parentId=<非 POOLED 的 BUS>` → 409 `PARENT_NOT_POOLED`（与 AC-1 同码同 detail）；
  - `POST /api/req/requirements` 带 `type=RD` + `parentId=<非 SCHEDULED/IN_DEV 的 PDR>` → 409 `PARENT_NOT_SCHEDULED`（与 AC-2 同码同 detail，`allowed:["SCHEDULED","IN_DEV"]`）。

  两条都不落库；单测或静态断言证明 decompose 与 create 调用的是**同一个**前置门函数（禁止两套实现）。
- **AC-4（P0）** **改父入口**同样封死：`PUT /api/req/requirements/{pdrId}` 带 `parentId=<非 POOLED 的 BUS>` → 409 `PARENT_NOT_POOLED`；`PUT /api/req/requirements/{rdId}` 带 `parentId=<非 SCHEDULED/IN_DEV 的 PDR>` → 409 `PARENT_NOT_SCHEDULED`（同码同结构），且改父失败**不落库**（`parent_id` 保持原值）。约束：类型分层与祖先链校验仍**先于**状态门（既有「BUS 的父设为 PDR → 400 `INVALID_PARENT`」不变）；`parentId` 与当前父相同时不触发状态门（编辑其它字段不能被误伤）；`parentId=null`（置顶）沿用既有类型规则。
- **AC-5（P0）** 正常链路不误伤：`POOLED` BUS 拆 PDR → **201**；`SCHEDULED` PDR 拆 RD → **201**；`IN_DEV` PDR 拆 RD → **201**；`POST` 直建 RD 于 `SCHEDULED` PDR → **201**；`PUT` 把 RD 改挂到另一 `SCHEDULED/IN_DEV` PDR → **200**；`PUT` 用原父 id 回写 → **200**（不误伤）。子需求继承父域、`type`/`parentId` 正确、`audit_log` 新增一条 `DECOMPOSE`、父需求 `rollup` 立即刷新、父需求手填人天仍 400 `PARENT_DAYS_READONLY`、`BUS→PDR→RD` 之外的组合仍 400 `INVALID_PARENT`。
- **AC-6（P1）** 判定顺序与既有守卫不变：终态父仍 400 `STATUS_NOT_WRITABLE`（文案不变）；RD 拆解仍 400「该类型不能再拆解」；无 `req:split` 仍 **403 先于** 409；`decompose` **不**加入 `allowedActions`，16 动作矩阵与 r22 数据驱动判定零改动。
- **AC-7（P1）** **同源判定**：`GET /api/req/requirements/{id}` 的 `view` 新增 `decomposeGate: {allowed:boolean, reason:string|null, parentStatus:string, required:string|string[]|null}`，与 409 由**同一个函数**产出；单测遍历 BUS/PDR 全部状态，断言 `allowed` ⇔ 「不会 409」、`reason` 与 409 码一致、`required` 与 detail 一致。`decomposeGate` 只描述状态/类型前置，不含权限信息（权限仍由 `can('req:split')` + 403 决定，避免信息泄漏）。

### B. 前端收敛（同一判定）

**US-3** 作为业务人员，我要在点「拆解」之前就知道为什么不能拆，而不是点开填完表单才收到 409。

- **AC-8（P1）** 需求详情页 `ChildrenTab` 的「拆解子需求」按钮与空状态动作，都按 `can('req:split') && decomposeGate.allowed` 决定可用；不可用时**禁用并展示一句中文原因**（含当前状态与所需状态，如「父需求尚未入池（当前 SUBMITTED），入池后才能拆解」）。空状态动作**不得**再直接 `emit('decompose')` 绕过判定。`npm run typecheck` 0 错误；静态断言：两个入口均绑定 `decomposeGate`，且不存在仅用 `can('req:split')` 决定拆解可用性的写法。

### C. 提报必填校验

**US-4** 作为提报人，漏填业务域时我要看到「缺哪个字段」，而不是 500。

- **AC-9（P0）** `POST /api/req/requirements` 的 `domainIds` 缺失、空数组、含 `null`/非正整数 → **400** `VALIDATION_FAILED`，`detail.field="domainIds"`；不再出现 500 / `DataIntegrityViolationException`；不落库。
- **AC-10（P1）** 同类必填**逐条核查并给出结论表**（写进 ACCEPTANCE，每条含：请求、当前 HTTP/code、期望 HTTP/code、是否本轮修改、不落库断言）：
  1. `type` 非法（含缺失）→ 400（现有口径，不改）；
  2. `priority` 非法 → 400（现有口径，不改）；
  3. `title` 缺失/空白 → 400（现有口径，不改）；
  4. `estimateDays` 非数字/负值 → 400；
  5. `parentId` 指向不存在/他租户需求 → 404（现有口径）；
  6. `domainIds` 含**不存在或跨租户**的域 → 400 `VALIDATION_FAILED`，`detail={field:"domainIds", invalidIds:[...]}`；
  7. `domainIds` 含**调用者越权**的域 → 403 `OUT_OF_SCOPE`，`detail={field:"domainIds", domainIds:[...]}`（租户级账号放行；`DataScope` 域集合为空按注释语义「不限制」，见 §7 假设）；
  8. `decompose` 请求体缺 `title` → 400（现有口径）；
  9. 以上任一条**不得**出现裸 500；每条的「不落库」都要有断言或实测记录。
- **AC-11（P2）** `PUT` 显式传 `domainIds: []` 时维持现状（回退父/原域、净 no-op → 200），本轮不改口径，只在 ACCEPTANCE 记录实测结论。

### D. 负向用例、门禁与证据

- **AC-12（P0）** `scripts/platform-test-negative.sh` 新增 **≥6** 条：①未入池 BUS 拆 PDR → 409 `PARENT_NOT_POOLED`（断言 `detail.required`）；②未排期 PDR 拆 RD → 409 `PARENT_NOT_SCHEDULED`（断言 `detail.allowed` 含两状态）；③未入池 BUS 直建 PDR → 409；④**直建 RD 于未排期 PDR → 409**；⑤**`PUT` 把 RD 父改到未排期 PDR → 409 且 `parent_id` 未变**；⑥缺 `domainIds` 提报 → 400（断言 `detail.field="domainIds"` 且非 500）。每条附「不落库」断言（`requirement` 计数 / `audit_log` 计数不变）。条数 **30 → ≥36**，既有 30 条不删不弱化，`make platform-test-negative` 全绿。
- **AC-13（P0）** 门禁不缩水（复跑实测并记录）：`compile` 通过；`platform-verify` **167**（夹具改造后断言数不变）；`platform-test-negative` ≥34；`rbac-verify` **111**（CANONICAL_HASH 不变）；`access-verify` **152**；`lint` 22；`platform-unit` ≥316；`verify` 14；`analytics` 74；`collab` s0–s8。
- **AC-14（P1）** 证据与提交：≥4 份证据（AC-1 的 409 响应体含 detail、AC-2 的 409 detail、AC-9 的 400 detail、AC-5 正常 201 回归 + 前端 typecheck/静态断言输出）；日志带 `runId=tf-mwisc832-uxfle0`；收尾提交含任务夹路径；改动面限于 `req-service` 需求域服务/错误/视图/前端两文件/`scripts/` 与本文档夹；`pom.xml`、`web/package.json` diff 为空。
- **AC-15（P2）** 文档一致性：`docs/teamflow/memory.md` 只做两处同主题替换（基线行 `platform-test-negative` 28→30 的笔误订正；新增一行「需求流转前置门」约定）；`AGENTS.md` 托管区之外零改动。

## 3. 范围与非目标

**范围内**
- `RequirementService` 拆解前置门（decompose），以及同一判定在 `POST /requirements`（BUS→PDR）与 `PUT` 改父（BUS→PDR）上的复用。
- 详情接口 `decomposeGate` + 前端拆解入口收敛。
- `domainIds` 必填/存在性/可见性校验与同类必填核查。
- 负向用例、门禁复跑、证据。

**非目标（本轮明确不做）**
- **不封非用户写入口**：种子/夹具接口（`SeedController`、`DemoResetController`，管理端 demo 用途）、`/internal/**` 内部回调、批量导入（核查结论：`RequirementController` 无导入端点）本轮不改。若核查发现存在「用户可达、且能绕过父状态」的第三个写入口，在 ACCEPTANCE 列出并说明本轮不封的理由（无该入口 / 仅 demo 管理端 / 下轮）。
- 不改 `StateTables` 语义、不新增状态、不改 `RequirementActionMatrix` / `ActionPermissionResolver` / 16 动作契约，不把 `decompose` 塞进 `allowedActions`。
- 不做 G2（工时门）与 G4（VENDOR_PM）——属后续轮次。
- 不做通用参数校验框架重构、不统一全部错误码；只改本轮点名的入口。
- 不改前端整体交互/视觉（UI-STYLE 不变），不做需求列表页批量拆解。

## 4. 反面目标 / anti-goals

即使测试全绿，只要出现下列任一情形，本次改动就应被驳回：

1. **误伤正常链路**：对已 `POOLED` 的 BUS 拆 PDR、或对已 `SCHEDULED`/`IN_DEV` 的 PDR 拆 RD，也返回 409。任何人用真实账号点一次「拆解」就能发现——本该能拆的拆不了。
2. **纸门 / 两套语义**：详情页按钮已禁用，但绕过页面直接 `POST /decompose` 仍 201；或「拆解路径拦、直建/改父路径不拦」——`POST /requirements` 仍能建出 PDR、或能直建 RD 于未排期 PDR、或用 `PUT` 把 RD 挂到未排期 PDR（演示 10 秒即可复现）。
3. **前端仍可点**：未入池时「拆解子需求」按钮高亮可点（或空状态动作仍能直接打开对话框），点开填完才 409；或按钮虽禁用却没有任何原因说明，用户不知道为什么。
4. **500 变 400 但不指名字段**：`detail` 里没有 `field: "domainIds"`，前端只能提示「参数错误」，用户仍不知道缺什么；或者为了不报错而**静默塞一个默认域**，把需求悄悄写进错误业务域。
5. **以删旧例换绿**：`platform-test-negative` 条数没有增加（或删掉/弱化既有用例），或新用例只断言 HTTP 码、不断言「不落库」，失败后脏数据留在库里仍算通过。

## 5. 交互流程摘要

1. 用户在需求详情页 → 「子需求」页签：`decomposeGate.allowed=false` 时按钮禁用，悬停/旁注显示原因（`PARENT_NOT_POOLED`：父需求当前 `SUBMITTED`，需 `POOLED`；`PARENT_NOT_SCHEDULED`：父产品需求当前 `PENDING_ANALYSIS`，需 `SCHEDULED` 或 `IN_DEV`）。
2. 允许时点「拆解子需求」→ 填标题/优先级/人天 → 提交 `POST /decompose` → 201 → 子需求出现、父汇总刷新、审计 `DECOMPOSE`。
3. 并发/绕过场景：父状态在提交前被他人改回（如 `POOLED` → `CLOSED`）→ 后端 409，前端就地提示原因并把该父需求详情刷新一次（不跳错误页）。
4. 直建/改父场景：通过接口直建 RD、或把 RD 改挂到未排期的 PDR → 409 `PARENT_NOT_SCHEDULED`，提示与拆解路径**同一句原因**（「父产品需求需先排期（SCHEDULED 或 IN_DEV）」）。
5. 提报页漏填业务域 → 400，表单在「业务域」字段下展示「请选择业务域」，不出现 500 弹窗。

## 6. 优先级

| 优先级 | 内容 |
|---|---|
| P0 | AC-1、AC-2、AC-3、AC-4、AC-5、AC-9、AC-12、AC-13 |
| P1 | AC-6、AC-7、AC-8、AC-10、AC-14 |
| P2 | AC-11、AC-15 |

## 7. 依赖与风险

| # | 依赖/风险 | 影响 | 应对 |
|---|---|---|---|
| R1 | `platform-verify` AC-2 现用「新建 BUS（SUBMITTED）→ 建 PDR → 建 RD」夹具，直建 RD 门槛生效后必 409 | 该段 4 条断言会红 | 夹具重做，二选一：①把新 PDR 先经发布单 `schedule` 到 `SCHEDULED` 再建 RD；②改用种子中 `SCHEDULED` 的 PDR（PDR-0001）作父并把人天断言改为**增量**断言。**断言数 167 不变**为硬约束；ACCEPTANCE 附改造前后逐条断言对照，并写明「改夹具是为适配更严的门，不是放宽门禁」 |
| R2 | 负向夹具需构造 `SUBMITTED` BUS 与 `PENDING_ANALYSIS` PDR | 若无夹具则用例空转 | 负向脚本自建（admin/pm 提报即 `SUBMITTED`；PDR 由 POOLED BUS 拆出即 `PENDING_ANALYSIS`），不直连改库 |
| R3 | 越权域校验与 `DataScope.canSeeDomain` 的空集语义 | 按现状实现（空集=不可见）会让部分账号无法提报 | 按 `DataScope` 注释语义（空集=该维度不限制）实现并补单测；`tier==TENANT` 放行 |
| R4 | 前端无单测框架、沙箱内浏览器走查常不可用 | 视觉证据可能缺失 | 以 `typecheck` 0 + 静态断言 + 接口证据替代，并在 ACCEPTANCE 注明未覆盖项 |
| R5 | 新增错误码需前端映射与 lint 检查 | 提示语缺失/门禁红 | 前端集中一处映射 reason→中文，lint 错误码清单同步检查 |
| R6 | 详情视图 record 增字段 | 既有 contract/视图测试 | 只加可选字段，构造点单处；`permissions` 等既有键形状不变 |
| R7 | 4 处脚本夹具用 `SELECT id FROM requirement … type='PDR' ORDER BY id LIMIT 1` 取父（`scripts/platform-test-negative.sh:92`、`scripts/collab-perf.sh:71`、`scripts/tests/collab/_lib.sh:102`、`scripts/tests/integration/_lib.sh:177`） | 若首个 PDR 被前序套件推到 `DELIVERED/ACCEPTED_CLOSED`，直建 RD 会 409，门禁假红 | 选取条件加 `AND status IN ('SCHEDULED','IN_DEV')`；无可用父时显式报错「先排期/造夹具」，不得静默改库 |

## 8. 里程碑建议（≤3 个任务，串行）

1. **T1 后端前置门**：`PARENT_NOT_POOLED` / `PARENT_NOT_SCHEDULED` 常量与判定函数 → `decompose` 接入 → `create`（BUS→PDR 与 PDR→RD）与 `PUT` 改父（父未变则跳过）复用 → 详情 `decomposeGate` + 单测。开工先 `mvn -o -DskipTests compile`。
2. **T2 提报校验**：`domainIds` 必填/存在性/可见性校验 + 单测；逐条核查 §2 AC-10 的 9 个入口并留证。开工先 `mvn -o -DskipTests compile`。
3. **T3 前端 + 负向 + 门禁**：`ChildrenTab` 两入口绑定 `decomposeGate`、原因文案；`platform-test-negative.sh` 增 ≥6 条；改造 `platform-verify` AC-2 夹具与 R7 的四处父 PDR 选取；复跑 AC-13 全部门禁并出证据。开工先 `mvn -o -DskipTests compile`。

## 9. 假设与待澄清

- **已确认（本轮澄清拍板，不再待澄清）** 直建与改父 RD 一并封：`POST /requirements`（type=RD + parentId）与 `PUT` 改父都要求父 PDR 在 `SCHEDULED|IN_DEV`，与拆解同码同结构（AC-3/AC-4）。
- **假设 1（taste，待确认）** 不可拆时前端呈现为「禁用按钮 + 一句原因」（需求原文口径）。备选是「隐藏按钮 + 顶部提示条」；默认禁用+原因，因为用户需要知道「为什么现在不能拆」。
- **假设 2（taste，待确认）** 越权域返回 **403 `OUT_OF_SCOPE`** 而非 400。默认 403：与既有「无权访问」语义一致；若你希望统一成 400 以免前端多一条分支，可改，但 ACCEPTANCE 的入口表要同步。
- **假设 3（fact）** 域可见性按 `DataScope` 注释（空集=该维度不限制）实现，`tier==TENANT` 直接放行；若按当前实现（空集=不可见）会误伤部分 DOMAIN 档账号。此项需单测钉住。
- **假设 4（fact）** 409 `detail` 键名固定为 `parentId` / `parentStatus` / `required`（BUS 门）与 `allowed`（RD 门），前后端与负向用例三处共用；若团队已有别的命名习惯，改动需三处同步。
- **假设 5（fact）** 第三类写入口核查结论：`RequirementController` 无批量导入端点，`SeedController`/`DemoResetController` 仅管理端 demo 用途，`/internal/**` 为内部回调不产生用户需求——故本轮无需再封；若实现阶段发现新入口，按 AC-12 追加用例并在 ACCEPTANCE 登记。

## 10. 工程约束

- **分支**：在既有 `feat/flow-precondition-gates` 上继续，基线为 `main=535939a`（r22 合并点）；不新开分支、不 rebase、不切换。
- **未提交改动**：当前仅 `?? docs/teamflow/20261116-r23-flow-control-gates/`（本任务夹），保留并随收尾提交入库；不得 `git clean` / `git checkout -- .`。
- **每任务开工**：先 `mvn -o -DskipTests compile`（离线），失败即停并上报，不得跳过或改联网。
- **不改**：`StateTables` 语义、`RequirementActionMatrix`/`ActionPermissionResolver`/16 动作契约、`PermissionMatrixGridTest`、`pom.xml`、`web/package.json`。
- **负向用例**：统一追加进 `scripts/platform-test-negative.sh`，`make platform-test-negative` 必须全绿；只增不降。
- **.gitignore 核对**：现有规则已覆盖 `target/`、`.nacos/`、`node_modules/`、`dist/`、`*.log`、`.env*`、`.idea/`、`logs/teamflow/` 等，本轮无新增构建产物；**不得**把 `docs/teamflow/**/evidence/*.png` 加入忽略（证据必须入库）；一次性探针脚本放 `logs/teamflow/tf-mwisc832-uxfle0/`（已被忽略）。
- **收尾**：单次提交，message 含 `runId=tf-mwisc832-uxfle0` 与任务夹路径；提交前跑完 AC-13 门禁；文档只写 `docs/teamflow/<本夹>/` 与 `docs/teamflow/memory.md`。
