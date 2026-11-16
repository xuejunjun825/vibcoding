<!-- meta: summary="新增第 11 个内置角色「厂商项目经理」VENDOR_PM：以 168 行默认授权（10 类只读页面 + 唯一写动作排期 + 11 条写动作显式 DENY）承载厂商侧项目管理岗，账号强制绑定厂商、版本与用例按本厂商收敛，存量租户经 V13 与种子补齐，并交付演示账号 vpm1。" -->
基线依赖：docs/teamflow/20261116-r22-action-matrix-datadriven（数据驱动三档判定、16 动作契约与 1680 格等价不得回归）；docs/teamflow/20261116-r23-flow-control-gates（流转前置门与提报必填校验不得回归）
取代：docs/teamflow/20261110-r7-role-page-access-fix#AC-5（该 AC 的「厂商账号一律隐藏用例库」对 VENDOR_PM 开只读例外，其余厂商账号维持隐藏）

# PRD · r24 厂商项目经理角色（VENDOR_PM）

## 0. 一页结论

`FLOW-CONTROL-AUDIT.md` §3 G4 的结论是：厂商侧「项目管理」没有独立岗位角色，现有 10 个内置角色里找不到对应项，`vendor1` 实测是 **PMO + `vendor_id=VEN-A`**。用 PMO 借位会同时带来两件不该给厂商的事——**版本审批**与**域内跨厂商视图**；只靠 `vendor_id` 又表达不出「这个岗位能做什么、不能做什么」。

本轮新增第 11 个内置角色 **`VENDOR_PM`（厂商项目经理）**，仍然只加数据、不改三档判定：

1. 168 行默认授权：11 个只读页面（需求池、版本计划、迭代、缺陷、用例库、测试计划、追溯矩阵、甘特、Wiki、缺陷看板、质量指标）+ 唯一写动作 `req.action.schedule`（排期）+ 缺陷处理动作；`release.action.*`、测试计划/用例维护、评审、分解、指派厂商、发布、角色/用户/租户管理一律 DENY。
2. 账号必须绑定 `vendor_id`；数据域仍是厂商（`DataScope` 既有 VENDOR tier，判定代码不动），列表看不到他厂商、详情 403 `OUT_OF_SCOPE`。
3. 存量租户由 `V13` + 种子自愈补齐角色与 168 行；`make platform-seed` / `make platform-demo-reset` 幂等。
4. 演示账号 `vpm1`（`VENDOR_PM` + `VEN-A`，密码 `Passw0rd!`），`DEMO-BY-ROLE.md` 同步；授权页能显示 168 格。

一句话产品语义：**厂商项目经理是「本厂商交付的只读驾驶舱 + 排期扳手」，不是缩小版 PMO——他能把需求排进版本，但不能决定版本发不发、需求过不过、用例改不改。**

## 1. 背景与目标

### 1.1 现状与问题

- `PermissionMatrix.java` 的 `ROLES` 只有 10 个（BUSINESS / SUB_BUSINESS / SUB_BUSINESS_LEADER / PRODUCT / PMO / DEV / QA / OPS / MGMT / PLATFORM_ADMIN），PMO 行注释明确写着「vendor PMO 就是 PMO 行 + vendor scope」；`RoleCatalog`、`SeedService.roleDefs`、`V3` 迁移四处同样只有 10 个。
- 演示账号 `vendor1` = PMO + VEN-A，因此它**能审批版本**（`release.action.approve` 由 `release:plan` 派生 ALLOW），也**继承 PMO 的域内视角**；而 `vendor2` = DEV + VEN-B。厂商「项目管理」既没有名字，也没有稳定的能力边界。
- 仅靠「有没有 `vendor_id`」判断厂商身份，只能回答「看哪一家的数据」，回答不了「这个岗位能不能审批、能不能维护用例、能不能管人」。

### 1.2 目标

| # | 目标 | 可观测结果 |
|---|---|---|
| G1 | 岗位角色化 | 角色列表 / 授权页出现第 11 个内置角色 `VENDOR_PM`「厂商项目经理」，168 格授权可读可核对 |
| G2 | 能力边界清晰 | 只读跟踪 10 类页面 + 排期；版本审批、需求评审、测试计划审批/执行、用例维护、角色/用户/租户管理全部 403 |
| G3 | 厂商隔离不靠角色码 | `vpm1` 的需求/缺陷/任务/用例/版本列表只含 VEN-A；他厂商详情 403 `OUT_OF_SCOPE`；`DataScope` 判定零改动 |
| G4 | 可复现 | 存量租户升级、`platform-seed`、`platform-demo-reset` 后角色存在、168 行、无重复；`vpm1` 可直接演示 |

### 1.3 成功度量

- `vpm1` 登录后侧栏出现需求池 / 版本计划 / 迭代 / 缺陷 / 用例库 / 测试计划 / 追溯矩阵 / 甘特 / Wiki / 质量看板，且需求池里只有 VEN-A 承接的需求；对任意需求点「评审通过」「分解」「指派厂商」均不可见，直接调接口 403。
- 版本页能看、能排期、不能审批：`POST /releases/{id}/approval` → 403；`POST /requirements/{id}/actions {action:"schedule"}` → 200。
- 门禁全绿且断言数只增不减（详见 AC-12 与 §12）。

## 2. 关键设计决策

> 这四条是产品约束，技术方案必须遵守；其中 D2 是「只加数据」能成立的前提。

**D1 · 第 11 个内置角色，168 行，总行数 1680 → 1848。**
`VENDOR_PM` 与其余 10 个角色一样由注册表推导授权：60 FUNCTION + 36 MENU + 72 ACTION = 168 行/租户；11 角色 × 168 = 1848。既有 10 角色的 1680 行**逐格不变**（这是本轮最重要的非回归断言）。

**D2 · 页面准入键与写动作分层，功能码 ALLOW 不等于可写。**
前端菜单/路由是**双条件**：页面码（MENU）非 DENY **且** 绑定功能码（FUNCTION）非 DENY（`web/src/layouts/navAccess.ts` 的 `navVisible`；`web/src/router/index.ts` 的 `admitByPage` + `requiredCodes`）。因此版本计划（绑定 `release:plan`）、迭代（`task:manage`）、用例库（`case:manage`）、测试计划（`plan:manage`）这 4 个页面，若把绑定码设 DENY，**页面根本不会渲染**，也就谈不上「只读可见」。

所以本角色把 4 个码设为 **ALLOW，仅作页面准入键**，再用 **11 条 ACTION 显式 DENY** 收口写操作：
`release.action.create/update/approve/close`、`task.action.create/assign/update/close`、`sprint.action.create/close`、`test.action.case_edit`。
这不是「放开功能」：测试/版本/迭代的写接口已由 ACTION 层路由守卫把守（`PermissionRouteTable` 中 `/testcases`、`/test-plans`、`/releases`、`/sprints`、`/tasks` 的写规则都声明具体 action），ACTION DENY 即可让写请求 403；`req.action.schedule` 由 `release:plan` 派生 ALLOW，正好是唯一保留的写动作。
**验收口径：写能力只认 ACTION 层。** 授权页与角色说明必须写明「这 4 个功能码是页面准入键，不代表可写」；前端写按钮只允许用 `allowedActions`（ACTION 层）控制，禁止用 `can('case:manage')` 之类的功能码判断可写（AC-10 静态断言）。

**D3 · 不新增功能码。**
需求文字里的 `req:pool:view` / `release:view` / `plan:view` / `test:*` 在现有 60 个功能码中**并不存在**（代码里只有 `req:report:bus`、`req:report:dev`、`req:review`、`release:plan`、`plan:manage`、`case:manage`、`trace:view` 等）。新增只读码会把 60 码契约、168 格/角色、1680/1848 行与所有绑定关系一起改掉，且要给既有 10 角色补行，风险远大于收益。因此本轮**不新增码**，用 D2 的「准入键 + ACTION 收口」表达只读（见 §10 待澄清第 4 条）。

**D4 · 角色目录四处同源，授权行仍由种子推导。**
`PermissionMatrix.roles()`、`req-service/.../sys/RoleCatalog.java`、`SeedService.roleDefs`、`V13__vendor_pm_role.sql` 必须给出同一个 `VENDOR_PM / 厂商项目经理`。`V12` 已确立「迁移只做 DDL、不手写 1680 行」；`V13` 至少为存量租户建角色行，168 行授权由启动自愈（`RbacBootstrap` → `RbacSeeder.seedTenant`，按已存在的角色补齐）与 `make platform-seed` 写入。**验收以「升级后应用启动完成」为时点**：角色存在、168 行、无重复；若技术选择在 `V13` 内联授权行，必须证明与 `RbacSeeder.preset` 逐格相等且 s2 哈希一致。

**D5 · 两处既有缺口必须随角色一起补，否则「不能跨厂商」不成立。**
- **版本没有 `vendor_id` 列**（`ScopeSql` 明确 `release` 无厂商列），当前 VENDOR 域账号的版本列表只按域过滤，等于能看见其它厂商承接的版本。本轮为 VENDOR tier 增加**派生可见性**：版本可见 ⇔ 该版本下至少有一条需求属于本厂商（`owner_vendor_id` 或 `assignee_vendor_id` = 本人厂商）；详情不可见即 403 `OUT_OF_SCOPE`。只对 `tier=VENDOR` 附加，内部账号不受影响。
- **用例库 `internalOnly`**：`web/src/router/index.ts` 对 `vendorId != null` 的账号一律 403 `INTERNAL_ONLY`，`navAccess` 同样不渲染。需求明确要求 VENDOR_PM 能看到用例库，因此**只对 `VENDOR_PM` 开只读例外**（路由 + 导航 + 深链），其余厂商账号维持隐藏——这是对 r7 AC-5 的定点取代，不是全面放开。用例数据本身仍由 `test_case.vendor_id` 经 `ScopeSql` 收敛；`vendor_id` 为空的行按既有语义（内部行）可见。

## 3. 角色 × 能力表

### 3.1 基本信息

| 项 | 值 |
|---|---|
| 角色码 | `VENDOR_PM`（大写、稳定，不随租户变化） |
| 中文名 | 厂商项目经理 |
| 角色类型 | 内置（`builtin=true`，不可删除、不可改码；可改授权） |
| 数据域 | 厂商域：`vendor_id` 必填；`DataScope` 仍由 `vendor_id` 推出 `VENDOR` tier（判定代码不动） |
| 授权行 | 168 行/租户（FUNCTION 60 + MENU 36 + ACTION 72）；全租户合计 1848 |
| 定位 | 本厂商承接需求/版本/迭代/缺陷/测试的**只读跟踪** + **排期** + **缺陷处理** |

### 3.2 页面（MENU）

| 页面 | 页面码 | 绑定功能码 | VENDOR_PM | 可写性 |
|---|---|---|---|---|
| 需求池 | `req.pool` | 无 | 可见 | 仅排期（`req.action.schedule`） |
| 版本计划 | `req.release` | `release:plan` | 可见（准入键） | 只读：新建/编辑/审批/关闭全 DENY |
| 迭代 | `req.iteration` | `task:manage` | 可见（准入键） | 只读：任务/迭代写动作全 DENY |
| 缺陷 | `defect.list` | 无 | 可见 | 处理类动作可用；不提缺陷 |
| 用例库 | `test.cases` | `case:manage` | 可见（准入键 + `internalOnly` 例外） | 只读：`test.action.case_edit` DENY |
| 测试计划 | `test.plans` | `plan:manage` | 可见（准入键） | 只读：新建/审批/执行全 DENY |
| 追溯矩阵 | `qa.trace` | `trace:view` | 可见（RESTRICTED） | 只读 |
| 甘特 | `collab.gantt` | `GANTT_VIEW` | 可见 | 只读：`gantt.action.edit` DENY |
| Wiki | `collab.wiki` | `WIKI_VIEW` | 可见 | 只读：编辑/管理空间 DENY |
| 缺陷看板（质量看板） | `defect.board` | `defect:board` | 可见 | 只读 |
| 质量指标（质量看板） | `qa.metrics` | `qualitymetric:view` | 可见 | 只读 |

另：个人页（我的任务/通知等）与 M9 全员只读页（工作台 `/portal`、看板 `/dashboard`、报表 `/reports`）沿用既有「全员可读」语义，不额外授予配置/共享/导出。

**明确不可见**：`admin.*` 全部（组织/用户/厂商/权限/角色/流程/客户端/集成/指标/审计）；`req.create`、`me.approvals`、`deploy.orders`、`qa.gates`、`test.reports`、`test.strategies`、`test.envs`、`test.executors`、`code.policies`、`collab.value-stream`。

### 3.3 功能码（FUNCTION，60 格）

| 取值 | 数量 | 具体码 |
|---|---|---|
| ALLOW | 12 | `release:plan`、`task:manage`、`case:manage`、`plan:manage`（以上 4 个仅作页面准入键）、`defect:handle`、`defect:board`、`qualitymetric:view`、`WIKI_VIEW`、`GANTT_VIEW`、`PORTAL_VIEW`、`REPORT_VIEW`、`notification:read` |
| RESTRICTED | 1 | `trace:view`（通过，数据仍按厂商收敛） |
| DENY | 47 | 其余全部，含 `tenant:manage`、`org:manage`、`req:report:bus`、`req:report:dev`、`req:review`、`req:split`、`req:assign-vendor`、`req:deliver`、`test:quick`、`deploy:create/execute/force`、`defect:report`、`metric:manage`、`workflow:manage`、`apiclient:manage`、`export:detail`、`audit:view`、`strategy:manage`、`plan:edit/approve/execute/assign`、`env:manage`、`report:generate/sign/export`、`gate:view`、`waiver:*`、`review:manage`、`proddefect:report`、`integration:manage`、`WIKI_EDIT/ADMIN`、`GANTT_EDIT`、`FLOW_*`、`CCODE_POLICY_*`、`CASE_REVIEW_*`、`TEST_EXECUTOR_*`、`PORTAL_CONFIG`、`REPORT_SHARE` |

> `defect:handle` 让「指派/开始处理/修复完成/验证通过/重开/关闭」6 个动作可用；`defect:report` DENY 使「新建缺陷」不可用——符合「缺陷：只读与处理」。

### 3.4 动作（ACTION，72 格）

| 取值 | 数量 | 内容 |
|---|---|---|
| ALLOW | 7 | `req.action.schedule`（排期）；`defect.action.assign/handle/resolve/verify/reopen/close`（缺陷处理） |
| DENY | 65 | 其余全部，**其中 11 条是显式覆盖**（否则会由页面准入键派生 ALLOW）：`release.action.create/update/approve/close`、`task.action.create/assign/update/close`、`sprint.action.create/close`、`test.action.case_edit` |

由此保证：`req.action.approve/reject/review_start/review_pass/review_reject/accept/close/decompose/assign_vendor/deliver/submit/withdraw/start_dev/merge/finish/submit_test/test_pass` 全部 DENY；`test.action.case_review`、`test.action.plan_create/plan_approve/execute` 全部 DENY；`deploy.action.*`、`user.action.*`、`role.action.*`、`org.action.*`、`vendor.action.manage`、`tenant` 类动作全部 DENY。

### 3.5 与 PMO 的差异对照

| 维度 | PMO（域负责人，既有） | VENDOR_PM（本轮） |
|---|---|---|
| 岗位 | 甲方域内项目管理 | 厂商侧项目管理 |
| 数据域 | 授权域内全部厂商 | 仅本厂商（`vendor_id` 必填） |
| 版本审批 | **有**（`release.action.approve`） | **无**（DENY） |
| 版本新建/编辑/关闭 | 有 | 无（只读） |
| 需求评审/审批/验收 | 有（`req:review`） | 无（DENY） |
| 分解/指派厂商/交付/合并/完成 | 有 | 无（DENY） |
| 排期 | 有 | **有**（唯一保留的写动作） |
| 测试计划审批/执行 | 有（`plan:approve/execute`） | 无（只读） |
| 用例维护 | 有（`case:manage`） | 无（只读，`case_edit` DENY） |
| 甘特调整 / Wiki 编辑 | 有 | 无（只读） |
| 报表共享 | 有（`REPORT_SHARE`） | 无 |
| 组织/角色/用户/租户管理 | 无 | 无 |
| 跨厂商视图 | 有（域内） | **无** |

## 4. 用户故事与验收标准

### 4.1 角色注册与授权（管理员）

**US-1** 作为平台管理员，我要在角色列表里看到「厂商项目经理」并能核对它的 168 格授权，而不是靠口头约定知道厂商 PM 能做什么。

- **AC-1（P0）角色注册四处同源。** `PermissionMatrix.roles()`、`RoleCatalog`、`SeedService.roleDefs`、`V13` 四处都含 `VENDOR_PM`，中文名一致为「厂商项目经理」，角色码逐字一致；`GET /admin/permissions` 的 `roles` 含 `VENDOR_PM`；`GET /admin/roles` 返回其 `builtin=true`；新建租户预置 11 个角色。四处集合差集为空的断言进单测或静态检查。
- **AC-2（P0）168 格默认授权逐格可验。** `VENDOR_PM` 每租户 168 行（FUNCTION 60 + MENU 36 + ACTION 72），无缺行、无重复（`uk_rp` 冲突 0）。取值满足 §3.3/§3.4：FUNCTION 12 ALLOW / 1 RESTRICTED / 47 DENY；ACTION 7 ALLOW / 65 DENY，且 11 条显式 DENY 逐条命中；MENU 的 11 个指定页面码非 DENY，`admin.*` 与 §3.2 末尾列出的 10 个页面码为 DENY。
- **AC-3（P0）判定逻辑零改动、既有 1680 格不变。** `PermissionService`（三序与合并规则）、`RequirementActionMatrix`、`ActionRegistry`/`MenuRegistry` 的既有条目定义不出现语义修改；既有 10 角色的 1680 行与 r22 基线逐格相等（s2 对 10 角色子集的规范化哈希一致），本轮只新增 `VENDOR_PM` 的 168 行（1680 → 1848）。若为表达「只读页 + 显式动作 DENY」引入角色级 ACTION 覆盖，必须证明覆盖只命中 `VENDOR_PM` 的上述 11 个动作，对其它 10 角色 0 命中（遍历 11×72 断言）。

### 4.2 数据落地与演示账号

**US-2** 作为已上线租户的管理员，升级后角色列表里就该有「厂商项目经理」，168 行授权齐备；重复执行种子或重置不能长出重复行。
**US-3** 作为演示讲解人，我要能用 `vpm1` 一次登录演示「厂商 PM 能看什么、不能做什么」。

- **AC-4（P0）存量升级 / 新建 / 幂等。** 新增 `V13`（现最大 `V12`，版本号唯一）：已有租户升级并启动后，每租户 `sys_role` 有 1 个 `VENDOR_PM`、`sys_role_permission` 有 1848 行（该角色 168 行），无重复；新建租户同样 11 角色 / 1848 行。`make platform-seed` 连续两次、`make platform-demo-reset` 连续两次：行数恒为 1848、新增行 0、无重复、自定义角色清 0；`ACCEPTANCE` 记录「仅迁移已执行」与「应用启动完成」两个时点的行数。
- **AC-5（P0）演示账号与文档。** `SeedService` 新增 `vpm1`（`VENDOR_PM`，`vendor_id=VEN-A`，密码 `Passw0rd!`，`sys_user_role` 恰 1 条）；演示账号 13 → 14（`platform-verify` 的 12 → 13 口径同步，`DEMO-BY-ROLE` 账号表加一行）。`vendor1` 仍是 PMO + VEN-A，作为差异对照，不被改成 `VENDOR_PM`。

### 4.3 账号与隔离

**US-4** 作为平台管理员，我不允许建立一个「厂商项目经理但不属于任何厂商」的账号，否则它能看全租户。
**US-5** 作为 VEN-A 的厂商 PM，我只应该看到 VEN-A 承接的需求、版本、缺陷、任务与用例。

- **AC-6（P0）`VENDOR_PM` 必须绑定厂商。** `POST /admin/users`（角色含 `VENDOR_PM` 且 `vendorId` 缺失/为空）→ 400 `VALIDATION_FAILED`，detail 指名字段 `vendorId`，不落库；`PUT /admin/users` 把该角色用户的 `vendorId` 清空、或把角色改为 `VENDOR_PM` 而不给厂商，同样 400 且角色/域/厂商均保持原值（不留半成品账号）。只带 `vendorId` 不带该角色、或非 `VENDOR_PM` 用户不带 `vendorId`，沿用既有校验不误伤。允许 `VENDOR_PM` 与其它角色混用，但数据域仍由 `vendor_id` 决定、多角色合并仍「任一显式 DENY 即 DENY」，须在角色说明中提示混用会收窄而非放宽。
- **AC-7（P0）厂商隔离（列表过滤 + 详情 403）。** `vpm1` 的需求/缺陷/任务/用例/附件列表只含 VEN-A 承接行（`owner_vendor_id`/`assignee_vendor_id`/`vendor_id` = VEN-A）与既有的内部空厂商行，不含 VEN-B/VEN-C；需求总数与同厂商的 `vendor1` 相同、少于 `admin`；列表请求 200（不是 403 页）。他厂商需求/缺陷/任务/用例/附件的详情请求 → 403 `OUT_OF_SCOPE`；`audit_log` 无跨厂商读取成功的业务副作用（无新增写记录）。
- **AC-8（P0）版本可见性（补既有缺口）。** 对 `tier=VENDOR` 的账号，`GET /releases` 只返回「至少一条需求属于本厂商」的版本；每条返回版本的详情 200；仅含他厂商需求的版本详情 403 `OUT_OF_SCOPE` 且列表中不出现。内部账号（admin/pm/po1）版本总数与基线一致。行为收窄覆盖 `vendor1`（PMO + VEN-A），其可见版本数在 `DEMO-BY-ROLE` 更新并说明原因。
- **AC-9（P0）写动作边界（接口级）。** `vpm1` 对可排期需求 `GET` 详情时 `allowedActions` 只含 `schedule`（状态不允许排期时为空），不含 `approve/reject/review_*/decompose/assign_vendor/submit/withdraw/start_dev/deliver/merge/finish/submit_test/test_pass`。以下请求全部 403 `FORBIDDEN`「无权执行该操作」，且需求状态不变、无 `wf_instance` 新增：`POST /requirements/{id}/actions {approve|review_pass|decompose|assign_vendor|submit}`；`POST /releases`、`PATCH /releases/{id}`、`POST /releases/{id}/approval`；`POST/PUT/DELETE /testcases*`；`POST /test-plans`、`POST /test-plans/{id}/approve`、`POST /test-plans/{id}/execute`；`POST /sprints`、`POST /tasks`；`POST/PUT /admin/roles*`、`/admin/users*`、`/admin/orgs*`。反例：`POST /requirements/{id}/actions {action:"schedule"}` → 200。

### 4.4 前端

**US-6** 作为 VEN-A 的厂商 PM，我打开自己的后台，看到的是本厂商的进度与只读明细，不该出现点了就 403 的按钮。

- **AC-10（P0）前端显示与只读收敛。** 角色下拉、角色列表、授权页 `/admin/roles/:id/permissions` 正确显示 `VENDOR_PM` 及其 168 格（11 条写动作显示 DENY，`schedule` 显示 ALLOW）；新建/编辑用户可选该角色，选中后提示「必须绑定厂商」，未选厂商时前端拦一次、服务端兜底 400 并能定位到厂商字段。`vpm1` 侧栏出现 §3.2 的 11 个页面，`admin.*`/`req.create`/`me.approvals`/`deploy.orders` 等不渲染；用例库对 `VENDOR_PM` 可见、对其余 `vendorId != null` 账号仍 403 `INTERNAL_ONLY`。需求池只显示「排期」，版本页无「新建/编辑/审批/关闭」，用例库无「新建/编辑/导入」，测试计划无「新建/审批/执行」——**这些按钮的显示条件必须读 `allowedActions`（ACTION 层），不得读 `case:manage`/`plan:manage`/`release:plan` 等功能码**，并有静态断言。`npm run typecheck` 0 错误。

### 4.5 负向与门禁

**US-7** 作为团队成员，我要看到「越权被拒」的自动化证据，而不是只靠人工点。

- **AC-11（P0）新增负向用例 ≥3（实际 ≥5）。** `scripts/platform-test-negative.sh` 新增：①`vpm1` 需求列表无他厂商行 + 他厂商需求详情 403 `OUT_OF_SCOPE`；②`vpm1` 调 `req.action.approve`/`review_pass` 403 且状态/审批实例不变；③`vpm1` 调角色/用户管理接口 403（无 `role.*`/`user.*`）；④`vpm1` 版本审批 403；⑤无 `vendorId` 创建 `VENDOR_PM` 用户 400。断言总数为 49 → ≥54，只增不减。
- **AC-12（P0）门禁不缩水（数字只升不降）。** `make rbac-verify` ≥111（s0 的 1680/10×60 改为 1848/11×60、s2 的 `10 168` 改为 `11 168`、s8 的「内置 10 行」改为 11、s5 文案同步；新增 `VENDOR_PM` 行/格断言）；`make access-verify` ≥152（新增 VENDOR_PM 页面矩阵与隔离断言）；`make platform-verify` ≥168（新租户 10→11 角色、12→13 账号、新增授权断言；既有条件断言 ±1 浮动按实测记录）；`make platform-test-negative` ≥54；`make platform-demo-reset` ≥60（`TOTAL_CHECKS` 声明数与实执行数一致，`KEPT_USERS` 14）；`compile`、`lint`（≥22）、`unit`（≥329）、`verify`（14）、`analytics`（74）、`collab` s0–s8 全绿。**禁止删断言、改小基线、跳过用例**来换绿。
- **AC-13（P1）文档与记忆。** `DEMO-BY-ROLE.md`：账号表 +`vpm1` 行、隔离结论数更新、角色×页面矩阵新增 `VENDOR_PM` 列、新增 `vpm1` 小节（能做什么/不能做什么/403 示例）；`memory.md` **同主题替换**（内置角色 10→11、1848 行、VENDOR_PM 的「只读+排期」与 `vendor_id` 必填），不追加 changelog；`AGENTS.md` 托管区外零改动。
- **AC-14（P1）既有 10 角色与厂商账号不回归。** `vendor1`（PMO+VEN-A）仍可审批版本、仍有域内视图（版本可见性按 AC-8 收窄属预期，需在验收记录中逐项列出）；`vendor2`（DEV+VEN-B）、`tl1`/`pm`/`po1` 等 12 个既有账号的页面、需求数、动作集合与 r23 基线一致；`PermissionMatrixGridTest` 由 600 扩到 660，其中既有 10×60 子集逐格不变。

## 5. 范围与非目标

### 5.1 范围

1. `platform-contract`：角色注册 `VENDOR_PM`、168 行预设（含 11 条 ACTION 显式 DENY 的承载方式）、`RoleCatalog` 同步。
2. `req-service`：`V13` 迁移（存量租户角色行）、`SeedService`（`vpm1` + 角色定义）、`RbacSeeder` 预设覆盖、`AdminUserController` 的「VENDOR_PM 必须有 vendorId」校验、版本列表/详情的 VENDOR 派生谓词。
3. `web`：`internalOnly` 对 VENDOR_PM 的例外（路由 + 导航）、角色下拉/列表/授权页展示、用户弹窗厂商必填提示、只读页面按 `allowedActions` 收按钮。
4. `scripts` 与文档：门禁数字订正与新增断言、5 条负向、`DEMO-BY-ROLE`、`memory`。
5. 演示账号 `vpm1` 与 168 行授权可复现。

### 5.2 非目标

- 不改 `PermissionService` 三序、`DataScope`/`ScopeSql` 的 tier 判定、`RequirementActionMatrix` 的数据驱动契约。
- 不新增功能码（`req:pool:view`/`release:view`/`plan:view` 不落地），不新增页面，不改 16 动作契约。
- 不改 `vendor1`/`vendor2` 的角色码；不把厂商账号迁到新角色。
- 不做厂商侧独立门户/登录页、字段级权限、审批流改造、多租户自定义角色模板。
- 不做顺手重构（授权缓存、审计、前端目录结构一律不动）。
- 不引入新的权限表；`V13` 只解决「第 11 个角色在存量租户中存在」。

## 6. anti-goals / 反面目标

> 以下每一条都是「技术上门禁全绿、用户仍会拒绝验收」的具体失败，按演示现场可直接观察。

1. **角色建了，登录等于没有。** `vpm1` 登录后侧栏只剩工作台，需求池/版本计划/测试计划点不开；或授权页打开后 168 格全是 DENY。原因是只加了 `sys_role` 行、没生成授权，或只授了 MENU 没授页面绑定码。
2. **只读页变成可写页。** `vpm1` 在版本页看到「审批版本」并成功，或在需求上看到「评审通过/分解/指派厂商」并成功，或用例库能新建/修改用例。这比「看不到」更糟：它把「厂商 PM」变成了「缩小版 PMO」。
3. **跨厂商数据泄漏。** `vpm1` 的需求列表里出现 VEN-B 的需求，点开他厂商需求详情返回 200，或在用例库看到 VEN-C 的用例；版本列表里出现只含他厂商需求的版本。注意「看不到」必须发生在列表层，不能靠点开才 403。
4. **升级或重置后角色不存在/重复。** 已上线租户升级后管理员在角色列表里找不到「厂商项目经理」，或该角色 168 格为空；重复执行 `platform-seed`/`platform-demo-reset` 后行数变 336 或出现重复行。
5. **用删断言/放宽基线换绿。** 把 1680 的逐格断言删成「大于 0」，把 negative 的新增用例跳过，或让 `VENDOR_PM` 只能靠直接改库获得权限却宣称「已支持」。验收看的是断言数与 diff，不是绿灯颜色。

## 7. 交互流程摘要

1. **管理员配置**：`/admin/roles` 看到 11 个内置角色 → 打开「厂商项目经理」授权页 → 页面层 11 页可见、功能层 12 ALLOW/1 RESTRICTED、动作层 11 个写动作 DENY、`schedule` ALLOW → 保存（可改，仍受 168 行全量覆盖语义约束）。
2. **建账号**：`/admin/users` 新建 → 选角色 `VENDOR_PM` → 必须选厂商 VEN-A → 不选则前端提示、服务端 400 `VALIDATION_FAILED`（`vendorId`）；保存后该用户 `vendor_id=VEN-A`，登录即为 VENDOR 数据域。
3. **厂商 PM 日常**：`vpm1` 登录 → 侧栏 10 类页面 → 需求池只见 VEN-A 需求 → 打开需求点「排期」（选版本/日期）→ 200；版本页看本厂商版本、无审批按钮；测试计划/用例库只读；甘特/Wiki/质量看板只读；缺陷可指派/处理/关闭。
4. **越权尝试**：直接访问他厂商需求/版本详情 → 403 `OUT_OF_SCOPE`；直接调审批/评审/角色接口 → 403 `FORBIDDEN`；管理员页面深链 → 403（页面码 DENY）。
5. **升级/重置**：已有租户升级 → 启动后角色与 168 行存在；`make platform-demo-reset` → `vpm1` 回来、1848 行、14 个账号；重复执行结果不变。

## 8. 优先级

| 优先级 | 内容 | 理由 |
|---|---|---|
| P0 | AC-1 ~ AC-12 | 角色不存在/授权不全/隔离漏/门禁红，任一项都使本轮不可交付 |
| P1 | AC-13、AC-14 | 文档与兼容性回归；可与 P0 同批，但不阻塞代码合入 |
| P2 | 无（可选：授权页对 4 个「页面准入键」加 tooltip 说明） | 不改变验收结论，时间允许再做 |

## 9. 依赖与风险

| 依赖/风险 | 影响 | 应对 |
|---|---|---|
| 页面准入是「MENU + 绑定功能码」双条件 | 把 4 个码设 DENY 会让页面直接消失 | D2：准入键 ALLOW + 11 条 ACTION DENY；AC-2/AC-9 双向断言 |
| 角色级 ACTION 覆盖是新机制 | 可能误伤其它角色或与库内旧行不一致 | 覆盖仅 11 条、仅 `VENDOR_PM`；11×72 遍历断言 + 10 角色 1680 子集哈希 |
| `release` 无 `vendor_id` 列 | 版本列表天然跨厂商 | 派生可见性谓词（EXISTS 本厂商需求），仅 VENDOR tier 生效；AC-8 正反例 |
| 用例库 `internalOnly` 是 r7 的显式策略 | 例外会扩大厂商可见面 | 仅 `VENDOR_PM` + 厂商 scope + 只读；其余厂商账号保持 403 `INTERNAL_ONLY`；AC-7/AC-10 |
| 10/1680 硬编码分布在脚本、单测、前端文案 | 漏改导致门禁红或文案撒谎 | AC-12 逐处订正；数字断言只增；前端文案随 AC-13 更新 |
| 存量 52 个租户 × 168 行 | 迁移量与启动补齐耗时 | `V13` 幂等、分批写入；AC-4 记录两个时点行数 |
| 多角色混用（VENDOR_PM + PMO） | 合并规则「任一 DENY 即 DENY」会让能力收窄，用户困惑 | 角色说明写明「混用只会收窄」；不禁止、不特判 |
| 只读页若用功能码控制按钮 | 按钮可见但点击 403 | AC-10 静态断言：写按钮只读 `allowedActions` |

## 10. 假设与待澄清

以下每条都是**尚未由需求/代码完全定死**、需要用户确认后才能定稿的点；括号内为推荐默认值。

- **① 是否给 `VENDOR_PM` 提报需求的权限（`req:report:*`）？** 需求原文写「`req:report`（提报本公司需求，如果需要）」，属于可选；**默认：不给**。理由：厂商是交付方，需求由甲方业务/产品提报；给了会把厂商诉求直接送进内部评审池，且要同时放开 `req.action.submit/withdraw` 与 `req.create` 页面。若改为给，需新增 `req:report:dev` ALLOW + 两个 ACTION ALLOW + 提交正向/负向用例。
- **② 版本计划页的可写边界？** 需求只说「参与排期、不能审批版本发布」；**默认：只读**（`release.action.create/update/approve/close` 全 DENY，排期只通过 `req.action.schedule` 完成）。理由：版本生命周期（建版/改名/关版）属于 PMO 职责；若放开 create/update，须保留 approve/close DENY，并补「vpm1 建版 200、审批 403」用例。
- **③ 用例库对厂商的例外范围？** 现有 r7 策略是「任何 `vendor_id != null` 的账号都不作用例库」；需求却要求 VENDOR_PM 看到用例库。**默认：只对 `VENDOR_PM` 开只读例外**（路由 + 导航 + 深链），数据仍按 `test_case.vendor_id` 收敛，`vendor_id` 为空的内部门行沿用既有可见语义。理由：这是最小开放面；若全面放开厂商账号，需重新评估内部用例的 IP 边界并改 r7 的整条策略。
- **④ 功能码命名如何落地？** 需求写的 `req:pool:view`/`release:view`/`plan:view`/`test:* 只读` 在现有 60 码中不存在。**默认：不新增码**，用 `release:plan`/`task:manage`/`case:manage`/`plan:manage` 作页面准入键 + `trace:view`/`GANTT_VIEW`/`WIKI_VIEW` 等既有只读码表达可见，写能力由 ACTION DENY 收口。理由：新增码会把 60 码、168 格、1848 行与全部绑定关系一起改，且要给既有 10 角色补行；若坚持按需求命名新增 5 个只读码，则每角色 168→188、总行 1848→2068，门禁与文档需全量重算。
- **⑤ 版本可见性收窄只针对 `VENDOR_PM`，还是所有 VENDOR 域账号？** `release` 无厂商列，补谓词后 `vendor1`（PMO+VEN-A）的版本列表也会从「域内全部」收窄为「含 VEN-A 需求的版本」。**默认：对所有 `tier=VENDOR` 生效**。理由：`tier` 由 `vendor_id` 决定（不引入角色码参与数据域判定），若只对 `VENDOR_PM` 收窄，等于在 `DataScope` 里按角色特判，破坏「隔离只由 vendor_id 决定」的既有设计。选后者需在 TECHNICAL 说明并接受判定复杂度。

## 11. 工程约束

- **分支**：`feat/vendor-pm-role`（已存在并已切换）；从当前分支继续，不新建、不切换、不 rebase。
- **工作区现状**：未提交改动仅 `?? docs/teamflow/20261116-r24-vendor-pm-role/`（本任务夹）。开发期间不要 stash/丢弃/回滚它；收尾提交时与代码一并纳入。
- **实施顺序与粒度（逐字保留）**：任务 ≤3 个且串行，每步先 `mvn -o -DskipTests compile`；不做顺手重构。
- **判定逻辑（逐字保留）**：不改三档判定逻辑（`PermissionService` 三序、`RequirementActionMatrix` 数据驱动）——只加数据；如确需改判定，必须在 TECHNICAL 里说明理由并保持既有 1680 行行为不变。
- **门禁（逐字保留）**：rbac-verify（111 基线）、access-verify（152 基线）、platform-verify（168 基线）、platform-test-negative（49 基线）、platform-demo-reset（57 基线）、lint、unit、compile 全绿；新增断言只能加不能减。
- **负向（逐字保留）**：新增负向用例 ≥3 条：VENDOR_PM 看不到其它厂商需求、VENDOR_PM 调 `req.action.approve` 被 403、VENDOR_PM 无 `role.*` 动作。
- **迁移**：现最大 `V12`，本轮用 `V13`；不修改任何历史迁移（Flyway checksum 不变）。
- **种子**：`SeedService`/`RbacSeeder` 必须含该角色；`make platform-seed` 与 `make platform-demo-reset` 后角色存在且幂等（重复执行不产生重复行）。
- **前端**：角色下拉、角色列表、授权页 `/admin/roles/:id/permissions`、新建/编辑用户四处必须显示/可选；`DEMO-BY-ROLE.md` 账号表加 `vpm1`。
- **`.gitignore` 审计（提交前执行）**：现有已覆盖 Maven（`target/`、`*.class`、`*.jar`）、Node（`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`）、日志（`logs/`、`*.log`、`logs/teamflow/`）、环境（`.env*` 且保留 `!.env.example`）、Nacos（`.nacos/`）。本轮补缺：IDE（`.idea/`、`*.iml`、`.vscode/` 的本地覆盖，若团队不共享）、前端测试产物（`playwright-report/`、`test-results/`）、`*.eslintcache`；**不得**忽略 `docs/teamflow/**/evidence/*.png` 与迁移 `.sql`。提交前用 `git status --short` 确认没有 `target/`、`node_modules/`、日志或本地库导出混入。
- **收尾提交**：全门禁复跑通过后，一次提交包含代码、脚本数字订正、任务夹（PRD/TECHNICAL/QA-REPORT/ACCEPTANCE）；提交信息带 `runId=tf-mwiuzjpm-poecia`。

## 12. 里程碑建议

| 里程碑 | 内容 | 完成判据 |
|---|---|---|
| M1 · 契约与数据（任务 1） | 角色注册 + 168 行预设（含 11 条 ACTION DENY）+ `RoleCatalog` + `V13` + `RbacSeeder`/`SeedService` + `vpm1` | `mvn -o -DskipTests compile` 通过；单测 168 行/1848 行/11 角色；seed 两次幂等 |
| M2 · 隔离与账号（任务 2） | `vendor_id` 必填校验、版本派生可见性、用例库例外、5 条负向、脚本数字订正 | negative ≥54；AC-6/7/8/9 探针通过；既有 10 角色 1680 格不变 |
| M3 · 前端与门禁（任务 3） | 角色下拉/列表/授权页、用户弹窗提示、只读按钮按 `allowedActions`、文档 | typecheck 0；rbac/access/platform/demo-reset 全绿且断言数不低于基线；`DEMO-BY-ROLE`/`memory` 更新 |

## 13. 验收口径

| 门禁 | 基线 | 本轮要求 |
|---|---|---|
| `make rbac-verify` | 111 | ≥111，s0/s2/s8/s5 的 10/1680 订正为 11/1848，新增 `VENDOR_PM` 断言 |
| `make access-verify` | 152 | ≥152，新增 VENDOR_PM 页面矩阵与隔离断言 |
| `make platform-verify` | 168 | ≥168，角色 10→11、账号 12→13、授权断言新增 |
| `make platform-test-negative` | 49 | ≥54（新增 5 条） |
| `make platform-demo-reset` | 57 | ≥60，`KEPT_USERS` 14，1848 行 |
| `compile` / `lint` / `unit` / `verify` / `analytics` / `collab` | 22 / 329 / 14 / 74 / s0–s8 | 全绿且不缩水，unit 预计 ≥335 |

**人工演示脚本（ACCEPTANCE 截图/记录 ≥4 张）**：①授权页 168 格与 11 条 DENY；②`vpm1` 需求池只含 VEN-A；③他厂商需求详情 403 `OUT_OF_SCOPE`；④`vpm1` 版本审批 403 / 排期 200；⑤`make platform-demo-reset` 后 `vpm1` 与 1848 行。
