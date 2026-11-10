<!-- meta: summary="演示前 P0：新增登录可读的只读字典接口并统一非管理页字典调用，403 不再把整页踢走，侧栏/顶栏按功能码隐藏，补业务方种子账号与幂等演示数据命令，且既有权限矩阵与门禁不回归" -->
基线依赖：docs/teamflow/20261109-r6-ccode-cteam-ctest-p0（M8 九项能力、56 格权限矩阵、页面准入与既有门禁不得回归）；数据隔离口径沿用 docs/teamflow/20261108-r2-devops-req-platform §7

# 演示前 P0：非管理员角色可打开核心页面（角色页面准入修复）

## 0. 一页结论

- 演示前实测：`pm/tl1/po1/dev1/qa1/vendor1` 打开 `/requirements`、`/releases`、`/approvals`、`/test-plans`、`/code-policies` 全部落到全局「无权访问该单据」页，只有 `admin` 正常。演示无法按角色走。
- 三条修复主线：**① 字典读取不再借用 `/admin/*`**（新增登录可读、按 §7 裁剪的只读字典端点）；**② 403 只在「调用方没有声明可降级」时才触发全局跳转**；**③ 导航条目逐条声明功能码，无权限即不渲染**。另有业务方种子账号与可选的一键干净演示数据。
- 明确不放松后端鉴权：`/admin/*`、`plan:manage`、`CCODE_POLICY_VIEW` 等权限一格不改，无权限接口仍返回 403，只是前端不再被「可选能力探测」的 403 打断。
- 验收口径不是「7 个角色 × 5 个页面全部 ✅」——权限矩阵决定 `测试计划` 对 po1/dev1、`分支策略` 对 pm/po1/qa1 本就无权限。验收口径是「**无预期外的 403**」：该开的 30 格全开，该拒的 5 格仍拒且菜单不显示（见 AC-5 矩阵与 §9 待确认第 1 条）。

## 1. 背景与目标

### 1.1 现状（2026-11-10 实测）

`docs/teamflow/DEMO-BY-ROLE.md` §3 的「角色 × 页面」矩阵在浏览器里不成立：非管理员进入五个页面时，页面外壳刚挂载就被重定向到全局 403 页，演示脚本第 2 步就中断。同一现象也出现在缺陷看板、质量指标、测试环境、用例评审、用例执行等**非五个验收页**上——它们共用同一套字典读取。

### 1.2 根因（证据链）

| # | 位置 | 事实 |
|---|---|---|
| 1 | `web/src/views/req/platform.ts:146` | `loadDictionary()` 用 `Promise.all([listDomains().catch(()=>[]), listVendors().catch(()=>[]), listUsers({page:1,size:200}).then(r=>r.items).catch(()=>[])])` 取显示字典 |
| 2 | `web/src/api/admin.ts:14,117,127,137` | `listDomains/listVendors/listUsers` 全部打到 `/api/req/admin/*` |
| 3 | `platform-common/src/main/java/com/example/platform/common/security/PermissionRouteTable.java:44` | `^/admin/(orgs\|vendors\|domains\|users\|dicts)(/.*)?$` 要求 `org:manage` |
| 4 | `web/src/api/client.ts:179` | 任何 403（未声明 `silentForbidden`）都执行 `navigate('/403?...')` |
| 5 | 结论 | `platform.ts:146` 的 `.catch()` 只能吞掉 Promise 异常，**跳转在 client 层已经发生**，页面级降级无效 |

同一根因影响至少 15 个调用点（需求池/版本列表/需求详情/需求创建/审批中心、缺陷列表/缺陷详情/缺陷看板、用例评审、测试计划列表/详情/执行、测试环境、质量指标、分支策略），因此修复必须**收敛到一个字典模块**，而不是只给五个页面打补丁。

### 1.3 目标

- **G1 页面可用**：有权限的角色打开目标页面时，看到标题、筛选器和数据，不出现全局 403 页；无权限时仍明确 403。
- **G2 权限不膨胀**：不新增业务权限码、不放宽 `/admin/*`、不在字典里回传管理字段。
- **G3 入口不撒谎**：侧栏/顶栏只显示「路由已注册且当前角色有功能码」的条目；隐藏而非置灰，不留死链。
- **G4 演示可复现**：12 个账号可登录；一条幂等命令重建干净演示数据；既有门禁与隔离数字不回归。

成功度量：AC-5 矩阵 30 个 ✅ 格全部正常渲染、5 个 ⛔ 格全部落 403 且菜单隐藏；`make access-verify` ≥45 断言全绿；§8 列出的既有门禁一条不红。

## 2. 用户故事与验收标准

> AC 编号仅属于本任务夹。优先级：P0 = 演示阻断，P1 = 演示需要，P2 = 演示体验。

### US-1 作为非管理员，我能正常打开需求池/版本计划/审批中心

**AC-1（P0）只读字典端点契约**
- 新增三个端点，均要求有效 JWT、**不要求任何功能码**：
  - `GET /api/req/dictionaries/domains` → `[{id, code, name}]`
  - `GET /api/req/dictionaries/vendors` → `[{id, code, name}]`
  - `GET /api/req/dictionaries/users` → `[{id, username, name}]`
- 返回**直接是数组**，不套分页结构；按 `id` 升序；`users` 不分页、上限 1000。
- 响应字段白名单：domains/vendors 恰好 `id,code,name`，users 恰好 `id,username,name`。**不得**出现 `roles/roleCodes/status/enabled/domainIds/vendorId/orgId/passwordHash/createdAt` 等管理字段（用 JSON 键集合断言）。
- 匿名或过期 JWT → **401**（不是 403、不是空数组）；未知子路径 → 404。
- 管理接口 `/api/req/admin/{domains,vendors,users}` 的权限、分页、字段**一个都不变**。

**AC-2（P0）字典按 §7 裁剪**
- 租户：只返回调用者 JWT 所属租户的数据；`X-Tenant-Id` 与 JWT 不一致 → 403；跨租户条目 0 条。
- domains：内部账号 = 本租户启用域；厂商账号 = 本租户启用域 ∩ 其授权域（`sys_user_domain`）。
- vendors：内部账号 = 本租户全部启用厂商；厂商账号 = 仅本厂商。
- users：内部账号 = 本租户全部启用用户；厂商账号 = 仅本厂商用户。厂商账号**不得**通过 users 字典枚举到其他厂商或甲方人员。
- 断言：`admin` 的 domains id 集合 = `/admin/domains` 启用域 id 集合；`vendor1` 的 vendors 恰为 `VEN-A`；`vendor2` 的 users 全部属于 `VEN-B`；`po1` 的 users 不含其他租户。

**AC-3（P0）前端字典调用 100% 收敛**
- 新增 `web/src/api/dictionaries.ts`，只封装上述三个端点；**所有非管理页**的字典读取改走它。
- 静态断言：`web/src` 中 `listDomains|listVendors|listUsers` 的出现只允许在 `api/admin.ts`（定义）、`api/dictionaries.ts`（迁移后的再导出/封装）、`views/admin/*`（管理页自身的增删改查）。
- 迁移覆盖（至少）：`views/req/platform.ts`、`views/req/RequirementCreateView.vue`、`views/req/RequirementDetailView.vue`、`views/req/tabs/ApprovalTab.vue`、`views/release/ReleaseListView.vue`、`views/approval/ApprovalCenterView.vue`、`views/deliver/DefectListView.vue`、`views/deliver/DefectDetailView.vue`、`views/deliver/DefectBoardView.vue`、`views/deliver/TestPlansView.vue`、`views/deliver/TestPlanDetailView.vue`、`views/deliver/TestPlanExecuteView.vue`、`components/deliver/CaseReviewPanel.vue`、`views/quality/TestEnvsView.vue`、`views/quality/QualityMetricsView.vue`、`views/code/CodePolicyView.vue`。
- 每个调用点都有本地降级（空数组 / 「暂无数据」空态 / 隐藏对应筛选项），不允许把字典失败升级为页面失败。

**AC-4（P0）403 语义：可选能力探测不得打断页面**
- `client.ts` 保持默认行为：调用方**未**声明 `silentForbidden` 时，403 仍 `navigate('/403')` 并保留原页面 query（真无权限时用户必须看见明确提示）。
- 调用方声明 `silentForbidden: true` 时：403 只以 `ApiError(403)` reject，**不导航、不清会话**，由调用方降级；401 的会话过期逻辑（`clearSession` + 跳登录）保持不变，`silentAuth` 语义不变。
- `api/dictionaries.ts` 的所有请求必须声明 `silentForbidden: true`；能力探测（如「能否按域筛选」「能否选人」）同样声明。
- 静态断言：字典模块的请求选项含 `silentForbidden`；`client.ts` 的 403 分支以该选项为唯一开关。
- 说明：「页面无降级处理」无法由 client 运行时判断，落地口径 = **调用方未声明 `silentForbidden` 即视为无降级**（见 §9 第 2 条）。

**AC-5（P0）角色 × 页面矩阵（验收主表）**

以 `t1` 租户、`X-Tenant-Id: 1`、密码 `Passw0rd!`（admin 为 `Admin@12345`）实测：

| 页面 | admin | pm | tl1 | po1 | dev1 | qa1 | vendor1 |
|---|---|---|---|---|---|---|---|
| `/requirements` 需求池 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/releases` 版本计划 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/approvals` 审批中心 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/test-plans` 测试计划 | ✅ | ✅ | ✅ | ⛔ | ⛔ | ✅ | ✅ |
| `/code-policies` 分支策略 | ✅ | ⛔ | ✅ | ⛔ | ✅ | ⛔ | ✅ |

- ✅ = 页面标题/筛选器/列表正常渲染，**无全局 403 页**，控制台无未处理 403 异常。
- ⛔ = 路由守卫落到全局 403 页（有返回入口），且侧栏/顶栏**不显示**该入口；不是白屏、不是 404、不是空数据伪装成功。
- 统计口径：30 格 ✅ + 5 格 ⛔；**任何 ✅ 变 ⛔ 即失败，任何 ⛔ 变「白屏/半页」也失败**。
- 这 5 个 ⛔ 由既有权限矩阵决定（`plan:manage` 对 po1/dev1 为 DENY；`CCODE_POLICY_VIEW` 对 pm/po1/qa1 为 DENY），本轮**不新增权限码**去凑「全绿」。

**AC-6（P0）数据隔离不回归**
- 修复后在 `X-Tenant-Id: 1` 下：`po1` 需求池 `total=8`；`vendor1` `total=3`；`vendor2` `total=0`。
- 字典接口不改变任何列表的 `total`；用字典拿到的 id 反查不可见单据仍 403。

### US-2 作为非管理员，侧栏不出现我打不开的入口

**AC-7（P0）导航条目 100% 声明功能码**
- `web/src/layouts/nav.ts` 的每个侧栏/顶栏条目（含分组标题下的每一项、`TOP_NAV` 每一项）都必须显式带 `functionCode` 字段。
- 对后端没有权限码、登录即可读的页面（需求池、审批中心、我的任务、通知等），`functionCode` 取显式哨兵 `AUTHENTICATED`——**不是省略字段、不是 `undefined`**；过滤器把哨兵解释为「已登录」。
- 静态断言：条目总数 = 带 `functionCode` 的条目数；每个值 ∈ 56 个权限码 ∪ `{AUTHENTICATED}`；不允许出现矩阵里不存在的码。

**AC-8（P0）隐藏语义统一**
- 侧栏与顶栏使用同一判定：`路由已注册 && (functionCode === 'AUTHENTICATED' || auth.canAny(functionCode))`。
- 无权限条目**不渲染**（不是置灰、不是 disabled、不是点击后 403）；分组内 0 个可见子项时整个分组不渲染。
- 菜单项与路由 `meta.functionCode` 必须一致：不存在「菜单显示但点开 403」或「有权限但菜单不显示」的条目。

**AC-9（P0）关键隐藏/可见抽检**
- `po1`：隐藏 用例库、测试计划、测试报告、分支策略、价值流、管理后台；可见 需求池、版本计划、审批中心、甘特、Wiki、我的任务、通知。
- `dev1`：隐藏 用例库、测试计划、测试报告；可见 需求池、版本计划、审批中心、分支策略、甘特。
- `qa1`：隐藏 甘特、分支策略、价值流、管理后台；可见 测试计划、用例库、测试报告、质量门、质量指标。
- `pm`：隐藏 分支策略、管理后台；可见 需求池、版本计划、测试计划、用例库、审批中心。
- `tl1`/`vendor1`：可见 分支策略、测试计划、版本计划；`vendor1` 隐藏 用例库、管理后台。
- `admin`：五个页面入口全部可见。

**AC-10（P0）深链不回归**
- 直输无权限 URL（如 po1 → `/test-plans`、qa1 → `/code-policies`、dev1 → `/test-plans`）→ 全局 403 页，有「返回工作台」且不循环跳转；浏览器后退可回到上一页。
- 直输有权限 URL（如 po1 → `/requirements`）→ 正常渲染，刷新后仍正常（不依赖内存缓存）。

### US-3 作为业务方，我能登录并提报需求（P1）

**AC-11（P1）业务方种子账号**
- `SeedService` 新增两个账号，随 `make platform-seed` 幂等写入：
  - `biz1` / `Passw0rd!`，角色 `BUSINESS`（业务部门），显示名「陈业务（业务部门）」；
  - `suite1` / `Passw0rd!`，角色 `SUB_BUSINESS_LEADER`（二级企业业务），显示名「许二级（二级企业业务）」。
- 授权域与 `po1` 同源（同一批 `sys_user_domain` 行）；同租户 `t1`。
- 均可登录（200，返回角色码与可见功能码）；`biz1` 可提交 `BUSINESS` 类型需求并在需求池看到自己提报的记录；二人菜单不出现测试计划/分支策略/管理后台。
- 不新增权限码，不改 56 格矩阵；只使用既有角色授权。

**AC-12（P1）种子脚本与文档同步**
- `scripts/platform-seed.sh` 的账号断言从 10 改为 12（保留「只校验种子账号存在、不校验租户用户总数」的既有口径）；重复执行两次计数一致。
- `SeedResult.loginUsers` 返回 12 条 `user:password`；`docs/teamflow/DEMO-BY-ROLE.md` §1 账号表补两行。
- 既有 10 个账号、密码、角色、域授权、厂商归属不变。

### US-4 作为演示者，我能一键重建干净数据（P2）

**AC-13（P2）幂等演示数据命令**
- 新增 `make platform-demo-reset`（脚本入口放 `scripts/`，Makefile 只做薄封装），行为：
  - 保留：12 个账号、7 个域、3 个厂商、角色/权限、流程定义、指标定义、字典数据；
  - 重建固定小数据集：需求 12 条（8 条属 po1 所在企业、3 条 VEN-A、1 条 VEN-C）、版本 1 个、迭代 1 个、用例 12 条、缺陷 3 条、测试计划 1 个（≥10 用例）、甘特 1 组 8 行、通知 ≤5 条；
  - 清除验收夹具痕迹：`code` 匹配 `s1-|s5-|s7-|neg-sm-` 的行为 0；`actual_days > 30` 的行为 0；甘特逾期行 0；通知不再出现 99+；
  - 隔离基线保持：`po1 total=8`、`vendor1 total=3`、`vendor2 total=0`。
- 幂等：连续执行两次，上述所有计数完全一致；不重复插入、不报唯一键冲突。
- 可观测：命令结束打印一张计数表（表名/行数），非 0 退出即失败原因明确。

**AC-14（P2）不污染既有链路**
- 该命令**不**进入 `make test` / `make platform-up` / `make platform-seed`，只能显式调用。
- 执行后：`make platform-seed` 仍幂等通过；`make verify`、`make quality-verify`、`make integration-verify`、`make collab-verify`、`make test-negative` 全绿。
- 失败时整体回滚（事务或先清后建的 staging），不留下「账号在、业务数据半套」的中间态；不 DROP 表、不改 schema、不动 Flyway 版本。

### US-5 作为团队，修复后门禁与文档可信（P0）

**AC-15（P0）既有门禁一条不红**
- `make verify` 14 条、`make platform-verify`、`make quality-verify`（348）、`make integration-verify`（230）、`make collab-verify`（263）、`make test-negative`（25）、`make unit`（≥234）、`make lint`（16）、`npm run typecheck`（0 错）、`npm run build` 全部通过。
- 只允许**新增**断言与用例；不允许删除、跳过、放宽既有断言来换绿。

**AC-16（P0）新增 `make access-verify`**
- ≥45 条断言，覆盖：
  1. 字典契约：3 端点 × {匿名 401、7 角色 200、字段白名单}；
  2. 字典裁剪：admin/vendor1/vendor2/po1 的集合断言 ≥6 条；
  3. 页面 API 矩阵：5 页面 × 7 角色的**期望状态**（200 或 403）共 35 条，⛔ 格必须 403；
  4. 数据隔离 3 条（AC-6 的三个 total）；
  5. 导航静态审计 1 条（AC-7 的 100% 覆盖）+ 关键隐藏项 ≥5 条。
- 栈未启动时以非 0 退出并提示「先 make platform-up」，**不打印 SKIP 冒充通过**；在 `make test` 中于 `platform-up` 之后执行。
- 负向明确：非管理员调 `/api/req/admin/domains` 必须仍 403；未登录调字典必须 401。

**AC-17（P0）文档同步且边界清楚**
- 更新 `docs/teamflow/DEMO-BY-ROLE.md`：§1 账号表（12）、§3 矩阵（按 AC-5 实际结果，标出 5 个 ⛔ 及其原因）、§6 P0 两条结论改为「已修复」，并写明验证命令。
- **不改**同文档的工作台/自助报表相关表述（下一轮处理）；不改 AGENTS.md 托管区外内容。
- 若本轮形成新团队约定（字典单一入口、导航必须声明功能码、演示重置命令），在 `docs/teamflow/memory.md` **替换同主题行**，不追加变更日志。

## 3. 范围与非目标

**范围**：上述 AC-1 ~ AC-17；后端字典端点与裁剪、前端 client/nav/15 处字典调用、种子账号、演示重置命令、验证脚本与文档。

**非目标**：
- 不重做岗位工作台、自助报表、首页看板（文档里已标注下一轮）。
- 不新增/删除权限码，不调整 56 格矩阵，不放宽 `/admin/*`。
- 不做行级权限引擎重构；沿用既有 ScopeSql / 租户拦截器。
- 不引入 Playwright 等浏览器测试依赖；矩阵用 HTTP + 静态审计 + 人工浏览器抽检（截图留证）。
- 不修改历史任务夹、不改 Flyway 迁移、不改数据库 schema。
- 不做演示脚本的动画/视觉优化。

## 4. 反面目标（anti-goals）

> 以下情形即使所有测试变绿，也应被人工判定为失败。

1. **页面「能开」但筛选器是坏的**：po1 打开需求池，域/厂商/人员下拉为空或只剩「全部」，用户无法判断这是修复成功还是数据没了。→ 必须看到与 admin 同源的 7 个域、3 个厂商、可选人员。
2. **为了不跳 403 把提示也删了**：po1 打开 `/test-plans` 时不再出现「无权访问」，而是白屏、空列表或一直转圈。→ 真无权限必须仍是明确 403 页。
3. **用放宽权限换通畅**：非管理员调 `/api/req/admin/domains` 变成 200，或字典响应里带出角色/状态/域授权等管理字段。→ 安全回归，一票否决。
4. **菜单隐藏过头**：po1 看不到需求池/版本计划/审批中心，演示第一步就断；或 qa1 看不到测试计划。→ 该可见的入口必须可见。
5. **演示重置变成清库**：`platform-demo-reset` 跑完 `platform-seed` 或任一 verify 变红，或第二次执行数据翻倍/报唯一键冲突。→ 幂等与隔离是硬要求。
6. **只在当前标签页有效**：刷新页面、换账号、直接粘贴 URL 后仍落 403。→ 修复必须落在代码与数据层，不能靠手工改库或浏览器缓存。

## 5. 交互流程摘要

1. 登录（`POST /api/req/auth/login`）→ 前端保存 token/租户，路由进入目标页。
2. 路由守卫读 `meta.functionCode`：有码且 `!canAny` → 全局 403 页；无码或哨兵 → 放行。
3. 布局渲染导航：条目按「路由已注册 + 功能码判定」过滤；无权限不渲染，空分组消失。
4. 页面挂载：先取字典（`api/dictionaries.ts`，`silentForbidden`）→ 渲染筛选器；再取主数据（列表/详情）。
5. 字典 403/网络失败 → 本地降级为空筛选，页面继续渲染；主数据真 403 → 由调用方决定提示或跳转（默认仍跳 403）。
6. 全局 403 页展示「无权限 + 所需功能码 + 返回工作台」，不自动重试。

## 6. 优先级与里程碑

| 优先级 | 内容 | 说明 |
|---|---|---|
| P0 | AC-1~AC-10、AC-15~AC-17 | 演示阻断，先合入 |
| P1 | AC-11~AC-12 | 业务方角色演示需要 |
| P2 | AC-13~AC-14 | 体验与可复现性，可与 P1 同批 |

建议切片（一轮交付内完成）：
- M1（0.5d）后端字典端点 + 裁剪 + 单测；
- M2（0.5d）前端 `dictionaries.ts` + `client.ts` + nav 改造 + 15 处迁移；
- M3（0.5d）种子 12 账号 + 脚本断言 + 文档；
- M4（1d）`access-verify` + 全门禁 + 7 角色浏览器抽检；
- M5（0.5d，可后置）`platform-demo-reset`。

## 7. 依赖与风险

**依赖**：运行中的 `make platform-up`（req 8082 / deliver 8083 / gateway 8080 / web 5173）、MySQL `devops_platform`、既有权限矩阵与路由表、既有 `platform-seed`。

| 风险 | 影响 | 应对 |
|---|---|---|
| 新字典路径被 `/admin` 规则或通配规则误伤 | 修复无效 | 路由表显式断言：`/dictionaries/*` 无功能码要求；用 access-verify 覆盖 |
| 15 处调用点漏改，个别页面仍 403 | 演示中断 | AC-3 静态断言 + AC-5 矩阵逐页验证 |
| 菜单过滤过度，隐藏了应可见入口 | 演示中断 | AC-9 同时断言「必须可见」，不只断言隐藏 |
| 403 默认策略改动导致真无权限无提示 | 体验/安全反馈缺失 | AC-4 保持默认跳转，只有显式 silent 才降级 |
| 新增 2 账号影响既有脚本/断言 | 门禁红 | AC-12 同步改断言；保留「不校验租户用户总数」口径 |
| demo-reset 与 verify 夹具互相污染 | 门禁红 | AC-14 明确不进自动链路 + 执行后全门禁复跑 |
| 前端产物缓存导致「本机好了、演示机没好」 | 现场翻车 | 重新 `npm run build`；验收含硬刷新与深链 |

## 8. 工程约束

- 任务原文动作：**「开始修复（演示前 P0 修复：非管理员角色打不开需求池/版本计划/审批中心/测试计划/分支策略）」**；本 PRD 即为该动作的需求定义。
- 基线：**`main = ea877fa`**；本地 **`make platform-up`** 可复现（**req 8082 / deliver 8083 / gateway 8080 / web 5173 / MySQL `devops_platform`**）。
- 工作区现状（不得回滚、不得覆盖）：4 处未提交改动 `docs/teamflow/20261110-r7-role-page-access-fix/`、`docs/teamflow/DEMO-BY-ROLE.html`、`docs/teamflow/DEMO-BY-ROLE.md`、`docs/teamflow/demo-shots/`。开发阶段如需切分支/提交，按 host 分支策略从最新 `main` 起 `feat/role-page-access-fix`；这些演示文档改动随本需求一并提交，**不夹带无关产物**。
- `.gitignore` 审计结论：现有规则已覆盖本项目栈的 `target/`、`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`、`.env*`、`.nacos/`、`.run/`、`.m2/`、`logs/`、`logs/teamflow/`、`test-results/`、`playwright-report/`、`.idea/`、`.vscode/`、`.DS_Store`、`*.pid`、`*.hprof`，**本轮无需新增规则**；新增截图/日志必须落 `logs/teamflow/tf-mw9yzocw-5kv3pt/`（已被忽略），不得散落项目根。
- 不新增第三方依赖（后端不引库、前端不加包）；不改 Maven 版本矩阵；不改 AGENTS.md 托管区及团队资产区；TeamFlow 文档只写本任务夹与 `docs/teamflow/memory.md`。
- 必须本机 `make platform-up` 后以 7 个角色实际登录验证，不接受只跑单测。

## 9. 假设与待澄清

**(a) 未决事实（fact）**

- **7×5 矩阵的字面冲突**：原文验收写「7 角色 × 5 页面全绿」，但同一段又要求「确实无功能码的页面仍 403」。按既有矩阵，`测试计划` 对 po1/dev1、`分支策略` 对 pm/po1/qa1 本就无权限，共 5 格。**假设**：不新增权限码，验收改为「无预期外 403」+ 5 格显式 ⛔（AC-5）。若改为真·全绿，需给 5 格补权限码并同步矩阵/路由/文档，演示语义会变成「人人可看治理页」。
- **domains 的 §7 裁剪口径**：**假设**内部账号可见本租户全部启用域（域是租户级元数据，不是业务行），厂商账号再与授权域求交。若要求「只返回调用者能看到单据的域」，需按 ScopeSql 反查，字典会随数据变化且更慢。
- **users 字典的厂商边界**：**假设**厂商账号只能看到本厂商用户；否则厂商可枚举甲方人员。若演示需要「厂商选甲方接口人」，应改为按单据可见性派生，而不是放开全租户。
- **demo-reset 的定位**：**假设**它是显式演示工具、不进 `make test`，且重置后仍保持 po1=8 / vendor1=3 / vendor2=0。若希望它成为标准种子的一部分，`platform-seed` 与各 verify 夹具需一起改造。
- **「页面无降级处理」的落地**：client 无法运行时判断页面是否降级，**假设**以「调用方是否声明 `silentForbidden`」为唯一开关（AC-4）。若要求默认不跳转，则所有真无权限场景都要页面自己实现 403 提示。

**(b) 用户口味（taste）**

- **403 默认策略**：推荐「默认仍跳全局 403（真无权限可见）+ 字典/探测显式 silent」；备选「默认静默、页面内提示」，页面更连贯但真无权限时反馈变弱。
- **演示数据规模**：推荐 12 条需求、3 条缺陷、1 个测试计划，够 15 分钟剧本且一眼能看完；备选 30 条更接近真实，但演示时滚动与筛选成本高。
- **新账号显示名**：推荐 `biz1`=「陈业务（业务部门）」、`suite1`=「许二级（二级企业业务）」；名字会出现在审批人/提报人下拉里，属于演示观感，可换。
