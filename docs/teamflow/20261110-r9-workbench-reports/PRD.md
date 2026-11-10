<!-- meta: summary="补齐文档声称已交付而实际缺失的岗位工作台、数据看板、自助报表：单一聚合引擎保证三处读数一致，报表默认 PRIVATE、按登录账号 Scope 聚合、导出必带水印" -->

基线依赖：`docs/teamflow/20261108-r2-devops-platform/`（§4.9–§4.11 功能规格与 4 数据集白名单，其已交付行为不得回退）、`docs/teamflow/20261110-r7-role-page-access-fix/`（导航每条显式 functionCode 与页面准入口径）、`docs/teamflow/20261110-r8-code-policies-403-fix/`（可选能力探测降级不升级为整页错误的约定）

取代：`docs/teamflow/20261110-r8-code-policies-403-fix/#AC-6`：把 negative 用例基线由 25 校正为 23（r8 QA 实测 23 全绿、脚本未改、无跳过，原数字系基线过时）；本轮门禁断言一律以实测数为下限。

# 岗位工作台 · 数据看板 · 自助报表

## 1. 背景与目标

### 1.1 事实（已核实，非推测）

1. `docs/teamflow/DELIVERED-MODULES.md` §9「度量、看板与报表（r2 + M6）」把三件事列为已交付：岗位工作台（待办中心 + 快捷动作 + 数据卡片 + 我的列表 + 消息，按角色预置模板）、自助报表（4 数据集 + 拖拽行/列/值/筛选 → 交叉表 → 图表，保存/共享/设为卡片，CSV/XLSX 导出）、页面（报表、价值流、工作台、指标管理）。
2. 实际状态：全仓**无** `/reports` 路由、**无** `report_definition` 表与接口、**无** `/dashboard`；`/workbench` 仅是按 `plan:execute|case:manage` 准入的 M6 质量工作台（`deliver-service/.../quality/workbench/WorkbenchQuery.java` 只返回 PlanCard/CaseCard/totals），不是 `20261108-r2-devops-platform/PRD.md` §4.9 的按岗位预置工作台。
3. 这是**文档与实现不一致的欠账**，也是演示现场最容易被追问的一处（"你说已交付，页面在哪？"）。

### 1.2 本轮目标（可判定）

| # | 目标 | 判定方式 |
|---|---|---|
| G1 | 岗位工作台可用：8 类角色模板各有非空、有意义的五区内容 | 8 个角色账号逐一登录 `/portal`，卡片数与模板声明一致、每区非空 |
| G2 | 数据看板可用：五块聚合与既有公式同源 | 看板读数 = 工作台卡片读数 = 报表读数（同筛选下逐位相等） |
| G3 | 自助报表可用：拖拽出交叉表、存得下、共享受控、导出带水印且不超权限 | 定义保存/复制/删除全链路 + 两厂商账号聚合不同 + 导出文件含水印且不含他厂行 |
| G4 | 文档回到真实状态 | `DELIVERED-MODULES.md` §9 与 `DEMO-BY-ROLE.md` 与实际路由/接口/脚本一一对应，附截图 |

### 1.3 成功判据（一句话）

一个 PM 在 `/portal` 看到「我的待办 12 · 完成率 68.4% · 平均周期 6.3 天 · 准时率 91.2%」，点进 `/dashboard` 用同一筛选看到同样的 4 个数、点进任一报表交叉表还是同样的 4 个数；把报表共享给同角色同事后，同事看到的行数与他自己能看见的需求行数一致；点导出拿到的 XLSX 每页页脚都有带账号的水印、CSV 首行是可读的 `#` 元信息而不是被吃掉表头。以上任一条不成立即本轮失败。

## 2. 用户故事

| # | 角色 | 故事 | 验收 |
|---|---|---|---|
| US-1 | 业务部门（biz1） | 我登录后想知道"我还有几件事要办、我的需求推进到哪"，不想在 5 个菜单之间找 | AC-2、AC-3 |
| US-2 | 二级企业负责人（po1/suite1） | 我要看本单位需求的入池率与完成率，并按厂商/域切换 | AC-2、AC-4 |
| US-3 | 产品（pm） | 我要看需求池规模、拆解进度、本周新增，并跳到待评审清单 | AC-2、AC-4 |
| US-4 | PMO（tl1/vendor1） | 我要看排期压力、厂商准时交付率与延期风险 | AC-2、AC-4、AC-11 |
| US-5 | 研发/测试（dev1/qa1） | 我只要"分给我的"待办与我这周的人天/缺陷 | AC-2 |
| US-6 | 管理层（MGMT） | 我不操作，只要一屏看得懂吞吐与周期 P90 | AC-2、AC-4 |
| US-7 | 管理员（admin） | 我要按角色关掉某个卡片，不改代码不发版 | AC-14 |
| US-8 | 任意有报表权限的人 | 我要拖几个字段就出一张交叉表，存下来、共享给同角色、导出带水印的 XLSX 交周会 | AC-5–AC-10 |
| US-9 | 厂商 A 的 PMO（vendor1） | 我导出的报表里**不能**出现厂商 B 的任何一行，也不能因为报表是 admin 共享的就看到全量 | AC-10、AC-11 |

每条故事的验收标准在 §11 中编号落地，可复跑、可量化。

## 3. 范围

### 3.1 本轮做

1. **岗位工作台**（§4.9）：`/portal` 五区（待办中心 / 快捷动作 / 4 张数据卡片 / 我的列表 / 消息入口）+ 8 类角色模板 + 全局筛选（域/时间/厂商）+ 管理员按角色配卡片显隐。
2. **数据看板**（§4.10）：`/dashboard` 五块（需求量多维 / 环比趋势 / 周期分段平均与 P90 / 人天预估·实际·偏差 / 厂商交付排名 / 交付质量），与工作台、报表**共用同一聚合引擎**。
3. **自助报表**（§4.11）：`/reports` 列表 + `/reports/:id` 编辑器；4 数据集字段白名单、拖拽行/列/值/筛选、即时交叉表、图表切换、保存/重命名/复制/删除、PRIVATE 默认 + 手动共享范围、设为工作台卡片、XLSX（水印）/CSV（首行元信息）导出、5 分钟缓存、维度与时间跨度上限、`report_definition` 迁移。
4. **文档与证据**：`DELIVERED-MODULES.md` §9、`DEMO-BY-ROLE.md` 同步真实状态并附截图；两个新门禁脚本接入聚合清单。

### 3.2 非目标（本轮明确不做）

- 用户级拖拽自定义布局（卡片位置/大小自由拖拽，二期；本轮只有管理员按角色配显隐）。
- 移动端适配（沿用 §4.9 结论：不做）。
- 报表订阅/定时推送/邮件（二期）。
- 宽表、物化视图、预聚合 cube（统一多维查询按需聚合，不落宽表）。
- 任意 SQL、公式编辑器、自定义计算字段。
- 新数据集（严格 4 个：需求 / 交付 / 审批效率 / 质量）。
- 改 `/workbench`（M6 质量工作台）任何行为；改既有全局 403 语义。
- 新建微服务、新 Redis/缓存中间件（缓存用进程内 TTL Map）。

## 4. 反面目标（anti-goals）

以下每一条都是"测试全绿但人会用脚投票"的失败形态，验收时逐条人工核对。

1. **三处读数不一致**：同一筛选下工作台卡片显示 128、看板表格 130、报表交叉表 129。→ 任何人当场失去对全部数字的信任，比没有这个功能更糟。核对方式：三个页面截图 + 导出 CSV 的同一格数字逐位比对。
2. **"共享报表"变成"共享数据"**：admin 建的 PUBLIC 报表被 vendor1 打开后，看到 VEN-B 的 12 条需求，导出的 XLSX 里出现他厂行。→ 一次演示就构成数据泄露事故。核对方式：vendor1 导出文件的厂商列 distinct 值只能是 VEN-A。
3. **空模板**：MGMT/OPS 或某个新加角色登录 `/portal`，4 张卡片全是"—"、列表空白、没有一句解释。→ 页面像坏了；用户会认为"这个模块没做完"（正是本轮要修的欠账）。核对方式：每个角色截图，空态必须有文案 + 下一步动作。
4. **水印形同虚设**：导出文件肉眼找不到水印；或 CSV 首行 `#` 元信息把表头挤到第二行，Excel 打开后列错位、筛选失效。→ 水印等于没加，且 CSV 变得不可用。核对方式：打开导出文件截图；CSV 用 Excel 打开检查首行是否为表头可筛选。
5. **交叉表不可读**：2 个行维度 + 2 个列维度 + 3 个值把表格撑到 60 列，用户横向滚到看不见行标签，导出后每列宽 1 个字符。→ 数据对了但没人能用。核对方式：造一个 4 维 + 3 值的报表，页面截图与导出文件截图。

## 5. 交互流程摘要

### 5.1 工作台 `/portal`

登录 → 按当前账号角色取模板（无匹配则兜底模板）→ 顶部全局筛选条（域 / 时间 / 厂商，默认：本域 + 近 30 天）→ 五区自上而下：

1. **待办中心**：三源并发取数（审批待办 `GET /api/req/approvals?tab=todo` 的 total；任务待办 `GET /api/deliver/tasks` 我的未完成 total；超期项 `plan_end_date < 今天` 且未完成的 total）。每源独立降级：失败/无权限只把该块显示"暂不可用·重试"，**不影响**其他区（沿用 r8 的"可选探测只降级到页面内"约定）。点数字 → 带筛选跳对应列表页。
2. **快捷动作**：模板声明 3–5 个按钮，直接跳转（不做内联弹窗）。
3. **数据卡片**：4 张，每张 = 指标名 + 值 + 环比 + 口径提示（hover 显示数据集与公式 key）+「数据截至 HH:mm」；点击跳看板并带上同筛选。
4. **我的列表**：5–10 行，列固定（编号/标题/状态/负责人/计划完成/停留天数），行点击进详情；空态给"去提报需求"。
5. **消息入口**：未读数徽标 + 跳通知中心。

全局筛选改变 → 五区同刷（一次批量请求，非 5 次串行）。

### 5.2 看板 `/dashboard`

进入时继承工作台筛选（同一 URL query 约定 `?domainId=&from=&to=&vendorId=`）→ 五块自上而下，每块右上角"在报表中打开"（把该块的 dataset+维度+度量预置进 `/reports/new`）。

### 5.3 报表 `/reports`

列表（我的 / 共享给我两段）→ 新建：左字段面板（按数据集分组，维度与度量分区）拖入右上的行/列/值/筛选槽 → 松开即查（300ms 防抖，取消在途请求）→ 交叉表渲染 → 顶部切图表（柱状/堆叠/折线/饼）→ 保存（首次弹名称，默认 `数据集-日期`；保存后 URL 变 `/reports/:id`）→ 共享（选范围，需 REPORT_SHARE）→ 设为卡片 → 导出（XLSX 默认 / CSV 次选，CSV 旁常驻提示"对外提交请使用 XLSX"）。

## 6. 功能规格（落地口径）

### 6.1 路由与导航

| 路由 | name | 标题 | functionCode | 说明 |
|---|---|---|---|---|
| `/portal` | `portal` | 岗位工作台 | `PORTAL_VIEW` | 新增 |
| `/dashboard` | `analytics-dashboard` | 数据看板 | `PORTAL_VIEW` | 新增；与工作台同一码，避免"能看工作台不能看看板" |
| `/reports` | `report-list` | 自助报表 | `REPORT_VIEW` | 新增 |
| `/reports/:id` | `report-editor` | 报表编辑 | `REPORT_VIEW` | 新增；`:id=new` 为新建 |
| `/workbench` | `workbench` | 工作台 | `plan:execute`/`case:manage` | **不动**（M6 质量工作台） |

- `nav.ts` 分析域组新增 3 条（岗位工作台 / 数据看板 / 自助报表），每条显式 `functionCode`；`TOP_NAV` 8 项**不变**（nav.ts:324 注释"不再增加"继续成立）。
- 侧栏"我的"组内 `/portal` 也可达（同一路由，仅一处入口即可，不重复渲染两级菜单）。
- 面包屑与浏览器标题按上表 `title`；`/portal`、`/dashboard`、`/reports` 刷新保持（不用 hash 传状态）。

### 6.2 工作台模板（8 类 + 兜底）

模板是**代码常量**（`PortalTemplates`），数据库只存管理员的覆盖行（无行 = 用默认），避免种子与租户耦合。

| 模板 | 适用角色 | 种子账号 | 待办三源 | 快捷动作 | 4 张卡片（preset key） |
|---|---|---|---|---|---|
| business | BUSINESS | biz1 | 待我确认 / 被驳回待改 / 超期 | 提报需求·我的需求·待我确认·自助报表 | `req.total` `req.completionRate` `cycle.avg` `req.avgDwell` |
| sub-business | SUB_BUSINESS_LEADER（兼容 SUB_BUSINESS） | po1、suite1 | 我的提报 / 待我跟单 / 超期 | 提报需求·本单位需求·待我跟单·数据看板 | `req.total` `req.inPoolRate` `req.completionRate` `req.overdueCount` |
| product | PRODUCT | pm | 待评审 / 待拆解 / 待我审批 | 需求池·待评审·拆解·数据看板 | `req.total` `req.newCount` `req.splitRate` `req.avgDwell` |
| pmo | PMO | tl1、vendor1 | 待排期 / 待分派 / 延期风险 | 排期·分派·厂商交付·数据看板 | `req.total` `vendor.onTimeRate` `vendor.accepted` `req.overdueCount` |
| dev | DEV | dev1、dev2、vendor2 | 分给我的在办 / 待填人天 / 待改驳回 | 我的在办·填人天·我的任务·自助报表 | `req.mineOpen` `effort.actual` `req.rejected` `cycle.avg` |
| qa | QA | qa1、qa2 | 待测需求 / 缺陷待验 / 回归待办 | 待测需求·缺陷看板·质量指标·自助报表 | `req.pendingTest` `quality.passRate` `quality.defectDensity` `quality.releaseSuccess` |
| mgmt | MGMT | （本轮新增 mgr1，见 Q3） | 只读：不展示待办三源 | 数据看板·自助报表·我的报表 | `req.total` `cycle.avg` `cycle.p90` `vendor.onTimeRate` |
| admin | PLATFORM_ADMIN | admin | 流程定义待发布 / 异常账号 / 待办积压 | 组织与账号·权限矩阵·流程定义·门禁看板 | `org.users` `workflow.enabled` `org.activeUsers` `org.abnormal` |
| fallback | 其余（含 OPS） | —（无 OPS 种子） | 隐藏 | 数据看板·自助报表 | 0 张（显示"当前角色未预置卡片，可联系管理员"） |

- 兜底模板同样必须有内容：保留"我的列表 + 消息入口"，并把待办区替换为说明文案；**不允许**出现四张"—"卡片（对应反面目标 3）。
- 卡片显隐：管理员在 `/portal` 右上"配置模板"（`PORTAL_CONFIG`）勾选某角色的卡片，保存到 `portal_card_config`；只影响该角色，不影响他人；关掉的卡片若被"设为卡片"引用，仍然在该账号的个人卡片区显示（个人 > 模板，见 §6.9）。
- 卡片**固定上限 4 张**（模板 4 + 个人最多 4，个人覆盖模板时不叠加）。

### 6.3 指标字典（单一实现，三处共用）

`AnalyticsMetrics` 是唯一指标注册表（key → dataset + 聚合表达式 + 单位 + 精度）。前端**只格式化，不做除法**：所有比率/环比由后端返回。

| key | 名称 | 数据集 | 聚合 | 单位/精度 |
|---|---|---|---|---|
| `req.total` | 需求总数 | requirement | `count(*)` | 整数 |
| `req.newCount` | 本期新增 | requirement | `count(*) where created_at in 本期` | 整数 |
| `req.inPoolRate` | 入池率 | requirement | 已入池 / 已提报（分母排除草稿） | % / 1 位 |
| `req.completionRate` | 完成率 | requirement | 已完成 / 已提报（分母排除草稿） | % / 1 位 |
| `req.splitRate` | 拆解完成率 | requirement | 已拆解父需求 / 已入池父需求 | % / 1 位 |
| `req.overdueCount` | 超期未完成 | requirement | `plan_end_date < 今天 and not 完成` | 整数 |
| `req.pendingTest` | 待测需求 | requirement | 状态为待测 | 整数 |
| `req.mineOpen` | 我的在办 | requirement | `owner_user_id=me or assignee=me`，未完成 | 整数 |
| `req.rejected` | 驳回待改 | requirement | 状态为已驳回 | 整数 |
| `req.avgDwell` | 平均停留 | requirement | 未完成需求 `今天 - created_at` 均值 | 天 / 1 位 |
| `cycle.avg` | 平均交付周期 | requirement | 完成需求 `closed_at - created_at` 均值 | 天 / 1 位 |
| `cycle.p90` | 交付周期 P90 | requirement | 同上集合 nearest-rank | 天 / 1 位 |
| `effort.estimate` | 预估人天 | requirement | `sum(estimate_days)` | 人天 / 1 位 |
| `effort.actual` | 实际人天 | requirement | `sum(actual_days)` | 人天 / 1 位 |
| `effort.deviation` | 人天偏差 | requirement | `(实际-预估)/预估`，预估为 0/NULL 时返回 null | % / 1 位，null 显示"—" |
| `vendor.onTimeRate` | 厂商准时率 | delivery | 按期完成 / 完成总数（按 `expected_delivery_date`） | % / 1 位 |
| `vendor.accepted` | 厂商承接量 | requirement | `assignee_vendor_id` 分组计数 | 整数 |
| `quality.passRate` | 测试通过率 | quality | 通过 / 执行 | % / 1 位 |
| `quality.defectDensity` | 缺陷密度 | quality | 缺陷数 / 已交付需求数 | 个/需求 / 2 位 |
| `quality.releaseSuccess` | 发布成功率 | quality | 成功发布 / 发布总数 | % / 1 位 |
| `org.users` / `org.activeUsers` / `org.abnormal` / `workflow.enabled` | 组织与流程计数 | admin 既有只读接口 | 直接透传既有接口，不新写查询 | 整数 |

**口径硬约束**：
- "完成"与"入池"取值必须来自单一常量（`RequirementStates`），analytics 包内完成态字面量只允许出现 1 处；禁止在 SQL 里散写 `'DONE'`/`'CLOSED'`。
- P90 必须调用 `FlowMetricFormulas.p90Dwell`（nearest-rank：`ceil(0.9×n)` 1-based；n=10 → 第 9 个，n=11 → 第 10 个）。禁止在 analytics 包内另写 p90 实现（静态断言：`p90` 标识符在 deliver 主源码仅出现于 `FlowMetricFormulas` 与其调用点）。
- 环比：与"上一个等长周期"比较；无基期（上期为 0 或无数据）返回 null，前端显示"—"，**不得**显示 `+∞`/`+100%`。
- 每个响应带 `dataAsOf`（ISO8601，聚合时刻）与 `X-Report-Cache: HIT|MISS`。

### 6.4 数据看板五块

1. **需求量多维**：维度可取 域 / 厂商 / 类型 / 优先级 / 状态，时间粒度 周|月；最多二维交叉（1 行 + 1 列）；返回行列矩阵 + 行小计 + 列小计 + 总计。
2. **环比趋势**：按周或月，本期 vs 上期，返回 `period, current, previous, delta`；≥3 个周期才画趋势（不足则显示表格）。
3. **周期分段**：分段固定 ≤3 / 4–7 / 8–15 / 16–30 / >30 天，每段返回 `count / avg / p90`；P90 同 §6.3 口径。
4. **人天三列**：预估 / 实际 / 偏差（%）并列；附"无预估需求数"计数（避免偏差率被 NULL 稀释）。
5. **厂商交付排名 + 交付质量**：排名按完成数降序、同数量按准时率降序，取前 10，当前账号所属厂商高亮并置顶（不改排序事实，只加视觉标记）；质量为通过率 / 缺陷密度 / 发布成功率三卡。

图表用 ECharts（既有依赖）。空数据不画空坐标轴，显示"当前筛选无数据"。

### 6.5 自助报表：4 数据集白名单

白名单是**服务端常量**，与前端字段面板同源导出（前端从 `GET /api/deliver/reports/datasets` 取，不硬编码）。

| 数据集 | 维度（可拖行/列/筛选） | 度量（可拖值） |
|---|---|---|
| `requirement` 需求 | 域、厂商（承接）、提出单位、类型、优先级、状态、负责人、创建周、创建月、计划完成月 | 需求数、平均交付周期、P90 交付周期、平均停留、预估人天、实际人天、人天偏差 |
| `delivery` 交付 | 域、厂商、发布、状态、计划上线月、创建月 | 需求数、按期完成数、准时率、平均周期、P90 周期 |
| `approval` 审批效率 | 业务类型（评审入池/发布/变更/上线）、节点、结果、发起月 | 审批单数、平均耗时（小时）、P90 耗时、通过率 |
| `quality` 质量 | 域、厂商、测试计划、缺陷等级、状态、月份 | 需求数、测试通过率、缺陷密度、发布成功率、缺陷数 |

- 未知字段 → `400 REPORT_UNKNOWN_FIELD`，body 含 `field` 与 `dataset`；把非度量字段放"值"槽 → 同码。
- 白名单外的字段、跨数据集混用（如 requirement 的维度 + quality 的度量）→ `400 REPORT_DATASET_MISMATCH`。
- 前端字段面板只展示白名单项，拖拽结束后端仍二次校验（前端不是安全边界）。

### 6.6 查询 API 契约（统一多维查询，不落宽表）

```
POST /api/deliver/analytics/query
{ "dataset":"requirement",
  "rows":["domain","status"], "columns":["month"],
  "measures":[{"key":"req.total"},{"key":"cycle.p90"}],
  "filters":[{"field":"vendorId","op":"in","values":[3]}],
  "timeRange":{"from":"2026-08-01","to":"2026-10-31"},
  "limit":5000 }
→ { "columns":[...], "rows":[{"keys":{...},"cells":{...},"subtotal":{...}}],
    "totals":{...}, "truncated":false, "groups":128,
    "dataAsOf":"2026-11-10T06:12:31Z", "cache":"HIT" }
```

- 三个端点共用同一实现：`/analytics/query`（报表）、`/analytics/dashboard`（看板五块，服务端按块组合同引擎）、`/portal/cards`（工作台卡片 = 预置好的 query）。
- 聚合 SQL 100% 经 `ScopedJdbc`（每条带 `/*scope:alias*/` 标记）；`report` 包内**不得**出现自拼 `where domain_id=` 之类过滤（静态断言，见 AC-10）。
- 报表定义只存 JSON（`definition_json`），不落宽表、不建物化视图。

### 6.7 上限与缓存

| 项 | 上限 | 超出行为 |
|---|---|---|
| 行+列维度合计 | ≤4（行 1–3，列 0–2） | `400 REPORT_TOO_MANY_DIMENSIONS` |
| 度量 | ≤3 | `400 REPORT_TOO_MANY_MEASURES` |
| 筛选 | ≤8 | `400 REPORT_TOO_MANY_FILTERS` |
| 分组单元（行列组合数） | ≤5000 | `400 REPORT_TOO_MANY_GROUPS`，附 `groups` 实际值 |
| 时间跨度 | ≤366 天 | `400 REPORT_RANGE_TOO_WIDE` |
| 导出行数 | ≤10000（流式） | `400 REPORT_EXPORT_TOO_LARGE`，提示收窄筛选 |

缓存：进程内 `ConcurrentHashMap` + TTL 300s，key = `tenantId|userId|datasetId|canonicalJson(definition)|scopeHash`。
- 必须带 userId（或 scopeHash）：否则 vendor1 查过的结果会被 vendor2 命中（跨账号串数据）。
- 报表定义保存/删除后，按 `reportId` 前缀失效。
- 无 Redis、无 Caffeine、不加 `@Cacheable`（全仓无此依赖）。

### 6.8 导出

| 格式 | 规则 |
|---|---|
| XLSX（**默认**） | SXSSF 流式；首行合并单元格水印格，内容 `内部资料 · 租户=<tenantName> · 导出人=<username> · 报表=<name>#<id> · <yyyy-MM-dd HH:mm>`；页脚同文；表头冻结首行；列宽按内容自适应（最小 8、最大 40） |
| CSV | 首行 `# exporter=deliver-service tenant=<tenant> user=<username> report=<name>#<id> rows=<n> exportedAt=<iso>`；第二行表头；UTF-8 **带 BOM**（Excel 正确识别中文）；UI 常驻提示"对外提交请使用 XLSX" |

- `format` 缺省 = XLSX（不是 CSV）。
- 导出内容必须与页面表格**逐格一致**：行数 = 交叉表数据行数，列 = 行维度 + 值列；`totals` 行跟随导出。
- 导出走同一 `AnalyticsQuery`（同一 Scope 过滤），不得另写查询（AC-10 静态 + 动态双重断言）。

### 6.9 数据模型（V10）

```sql
-- req-service/src/main/resources/db/migration/V10__report_definition.sql
report_definition(
  id BIGINT PK AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(40) NOT NULL,
  dataset VARCHAR(24) NOT NULL,
  definition_json JSON NOT NULL,
  chart_type VARCHAR(16) NOT NULL DEFAULT 'TABLE',
  owner_id BIGINT NOT NULL,
  owner_role VARCHAR(32) NULL,
  scope VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',   -- PRIVATE/ROLE/DOMAIN/PUBLIC
  scope_ref VARCHAR(64) NULL,                     -- roleCode 或 domainId
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE/DELETED
  created_at/updated_at DATETIME(3),
  UNIQUE KEY uk_report_name (tenant_id, owner_id, name, status),
  KEY idx_report_owner (tenant_id, owner_id, status),
  KEY idx_report_scope (tenant_id, scope, status)
)
portal_card_config(
  id BIGINT PK, tenant_id BIGINT NOT NULL,
  role_code VARCHAR(32) NULL,      -- 模板级覆盖
  user_id BIGINT NULL,             -- 个人级（设为卡片）
  card_keys VARCHAR(512) NOT NULL, -- "req.total,cycle.avg" 或 "report:12"
  updated_by BIGINT, updated_at DATETIME(3),
  UNIQUE KEY uk_portal_scope (tenant_id, role_code, user_id)
)
```

- 两表都**不登记** `ScopeSql`（无域/厂商/组织维度，登记会误伤共享报表）；访问控制 = owner + scope 判定，封装在 `ReportAccess` 一处（仅 3 个分支：owner / scope 命中 / 管理员）。
- `report_definition` 不参与业务数据聚合，真正的数据可见性由 `ScopedJdbc` 在查询时施加。
- 软删：`status='DELETED'`，列表与详情（非 owner）均视为不存在 → `404 REPORT_NOT_FOUND`（不用 403，避免存在性泄露）。
- 种子：不新增报表种子（避免演示数据与真实定义混淆）；QA 脚本自建自删。

### 6.10 权限码（56 → 60）

| 码 | 含义 | 授予角色 |
|---|---|---|
| `PORTAL_VIEW` | 查看岗位工作台与数据看板 | 10 角色全部 |
| `PORTAL_CONFIG` | 配置角色模板卡片显隐 | PLATFORM_ADMIN |
| `REPORT_VIEW` | 查看/新建/编辑自己的报表 | 10 角色全部 |
| `REPORT_SHARE` | 把报表共享到 ROLE/DOMAIN/PUBLIC | PMO、PRODUCT、MGMT、PLATFORM_ADMIN |

- 不复用 M6 的 `report:export`/`report:generate`/`report:sign`（那是测试报告域，语义不同）。
- `PermissionRouteTable` 增加：`GET /portal/**`、`GET /analytics/**` → `PORTAL_VIEW`；`POST /reports/**`、`GET /reports/**` → `REPORT_VIEW`；`PUT /reports/{id}/scope` → `REPORT_SHARE`；`PUT /portal/config` → `PORTAL_CONFIG`。
- 函数码 56 → 60、矩阵格 560 → 600；三处硬编码基数必须同步（`platform-verify.sh:344`、`scripts/tests/collab/s0-schema.sh:99-102`、`scripts/tests/access/a3-nav-audit.mjs:126`）。
- 报表的所有权校验在服务层（`ReportAccess`），不靠路由码：有 `REPORT_VIEW` 只代表能进页面，不代表能看别人的 PRIVATE 报表。

## 7. 优先级

| 优先级 | 内容 | 理由 |
|---|---|---|
| **P0** | §6.1 路由与准入门禁、§6.2 工作台五区 + 8 模板、§6.3 指标字典与三处一致、§6.5 白名单、§6.6 查询 API、§6.8 导出（水印 + 不超权限）、§6.10 权限码、AC-1/2/3/5/9/10/11/15 | 缺任一条，"补齐"就不成立；泄露与水印是被审计项 |
| **P1** | §6.4 看板五块、§6.7 上限与缓存、§6.9 迁移、报表生命周期与共享（AC-4/6/7/8/12/13/14） | 能力完整度与演示说服力 |
| **P2** | 文档同步与截图（AC-17）、性能（AC-18）、图表"在报表中打开"的预置联动 | 体验与可维护性，可在验收前补 |

## 8. 依赖与风险

| 项 | 说明 | 应对 |
|---|---|---|
| 既有接口形态 | 待办三源复用 `/approvals?tab=todo`、`/deliver/tasks` 的 total，若某接口无 total 字段 | 只读改造：加 `total`（不新写聚合 SQL，避免第二套审批语义）；无 total 则退化为 `length`（页大小需 ≥ 待办数，否则分页取 total） |
| 跨服务同库 | deliver-service 直查 `requirement` 表（已有 `TraceabilityQuery` 先例） | 复用先例，SQL 带 scope marker；表结构变更仍由 req-service Flyway 负责 |
| 无缓存中间件 | 只能进程内 TTL Map | 单实例演示环境成立；多实例时缓存只影响命中率，不影响正确性（key 含 userId + scopeHash） |
| 种子账号缺 MGMT/OPS | 无法登录验证 mgmt 模板 | 新增 `mgr1`（MGMT）一个账号（12→13，同步 seed 断言与文档账号表）；OPS 用兜底模板 + 静态断言覆盖（见 Q3） |
| 权限码基数硬编码 3 处 | 漏改即门禁红 | AC-15 强制同步，`access-verify` a3 全集 +4 |
| 与 `/workbench` 混淆 | 两个"工作台" | 菜单文案区分：「岗位工作台」vs「质量工作台（原工作台）」；`/workbench` 标题保持不动，nav 条目文案改为"质量工作台"是否可接受见 Q2 备注 |
| 性能 | 多维 group by 在 5 万需求上可能 >1.5s | 依赖既有索引 `idx_req_list(tenant_id,type,status,domain_id,updated_at)`；必要时新增组合索引（V10 内加，不改既有列） |

## 9. 假设与待澄清

- **Q1（fact）聚合放在 deliver-service，不新建服务**：理由是 POI/XLSX 水印管道、`MetricFormulas`/`FlowMetricFormulas`、97 处 `ScopedJdbc` 都在 deliver，且已有跨域读 `requirement` 的先例；网关 `/api/deliver/**` 已覆盖，路由表零改动。若改为新建 analytics 服务，需新增网关 route、复制导出与水印管道、并解决跨服务公式同源问题（工作量约 +40%）。
- **Q2（fact）数据看板独立路由 `/dashboard`，不做成工作台的一个 tab**：PRD §4.10 与 §4.9 是两节，看板有自己的多维交互与"在报表中打开"；若并入 tab，URL 无法深链到某个看板筛选，演示时也无法单独给链接。
- **Q3（taste）是否为 MGMT 新增演示账号 `mgr1`（12 → 13）**：建议新增（否则 mgmt 模板只能靠静态断言，演示时无法登录查看）；代价是 seed 断言 12→13、`DEMO-BY-ROLE` 账号表 +1 行、negative 用例里"登录用户数"类断言要同步。若不新增，改用 admin 临时切角色（不安全，不推荐）。
- **Q4（taste）「设为卡片」超过 4 张时的行为**：建议**拒绝并提示**（`409 PORTAL_CARD_LIMIT`，弹层列出当前 4 张让用户先移除一张），而不是静默替换最早的一张——静默替换会让用户以为"设了但没生效"。
- **Q5（taste）宽交叉表的呈现**：建议**横向滚动 + 冻结首列（行标签列）+ 表头吸顶**，导出时列宽自适应（8–40）；备选是折叠次要列/只导出前 N 列，但那会让"导出与页面一致"这条失效。
- **Q6（taste）PUBLIC 共享是否放开**：建议放开，但语义严格限定为"共享**定义**，不共享数据"——任何查看者打开 PUBLIC 报表，聚合仍按其自身 Scope 执行；UI 在共享弹层与报表头部明示这句话。若决定收紧（PUBLIC 仅 PLATFORM_ADMIN 可选），只需改 `REPORT_SHARE` 的校验分支 + 一条 UI 文案。

## 10. 工程约束（逐字保留原始指令）

- 分支：当前已在 `feat/workbench-reports`（原始状态核对原文："分支：feat/workbench-reports。未提交改动 1 处：?? docs/teamflow/20261110-r9-workbench-reports/"）。该未提交目录**就是本需求的产物夹**，直接随本轮提交，不 stash、不丢弃；不要为"干净工作区"把它移到别处。
- 技术栈沿用：gateway 8080 / req-service 8082 / deliver-service 8083 / web 5173（Vue3 + Element Plus + ECharts）；MySQL + Flyway；JWT + `X-Tenant-Id`。
- 数据可见性**必须**走既有单点（`ScopeSql`/`Scope` 拦截器与需求侧同一套过滤），不允许在新接口里另写过滤逻辑。
- 导出走既有带水印管道（`ReportExportService` 的 POI/水印实现），不新写第二个导出器。
- 路由：**保留现有 `/workbench`（M6 质量工作台）不动**；岗位工作台与报表另开路由（`/portal`、`/dashboard`、`/reports`），并把 `nav.ts` 的 functionCode 声明补全（无权限入口不渲染）。
- 不改既有门禁：r1 四步（`make dev-up/verify/test-negative/dev-down`）、`platform-verify`、`access-verify`、`quality-verify`、`integration-verify`、`collab-verify`、negative、unit、lint 全部不得回归；新增能力要有自己的可复跑脚本（`scripts/workbench-verify.sh`、`scripts/report-verify.sh`）并接入聚合门禁清单（`Makefile` + `scripts/test-all.sh`）。
- 隔离与权限：工作台与报表的数字、列表、导出都必须按域×厂商、二级企业、部门范围过滤（复用同一拦截器）；负向用例必须覆盖「厂商 A 的报表数字不含厂商 B」。
- **版本控制卫生（开发阶段动作）**：本仓已有 `.gitignore` 覆盖 `logs/teamflow/`、`target/`、`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`.env*`、`*.log`、`*.pid`、`test-results/`、`.idea/`、`.vscode/`、`.DS_Store` 等。本轮需**补充**（追加，不重写）：`scripts/tests/**/.artifacts/`（门禁脚本产出的导出夹具与截图）、`*.xlsx` 与 `*.csv` 仅在 `scripts/tests/**` 下忽略（仓库内已有合法 csv 种子，禁止全局忽略）、`web/test-results/`、`.playwright/`。验收阶段的收尾提交会 `git add -A` 整树，上述噪声若漏进去会成为永久历史。
- 提交时机与内容：QA 与验收通过后由验收阶段提交一次，message 采用 `feat: 补齐岗位工作台/数据看板/自助报表（runId=<runId>，任务夹 docs/teamflow/20261110-r9-workbench-reports）`；不得夹带与本书无关的重构。

## 11. 验收标准

> 编号仅在本任务夹内有效。每条给出**可复跑**证据；`workbench-verify` / `report-verify` 为新增脚本，段号与断言数在 AC-16 钉住。

**AC-1 路由与准入（P0）**
`/portal`、`/dashboard`、`/reports`、`/reports/:id` 四个路由存在且可刷新保持；`nav.ts` 三条新条目均显式声明 `functionCode`；无 `PORTAL_VIEW` 的账号访问 `/portal` 落 `/403`；有码账号停留渲染。`/workbench` 行为零变化：`plan:execute|case:manage` 角色仍可进、无码仍 403，且其页面标题、菜单文案未被改写为"岗位工作台"。
证据：`access-verify` a3/a4 新增可见集断言 + 浏览器矩阵（附 4 个账号截图）。

**AC-2 工作台五区与格式（P0）**
8 个模板账号（biz1/po1/pm/tl1/dev1/qa1/mgr1/admin）逐一登录 `/portal`：五区全部渲染；卡片数 = 模板声明数（4，兜底模板 0）；每张卡片有值、有环比（无基期显示"—"）、有"数据截至"；数值格式：整数千分位、比率 1 位小数带 `%`、天数 1 位带"天"、`null` 显示"—"而非 0；全局筛选（域/时间/厂商）改变后五区同刷且 URL query 同步；任一路由源失败时该区显示"暂不可用"且其他区不受影响（用无权限账号构造）。
证据：`workbench-verify` w1–w3；截图 8 张。

**AC-3 三处读数一致 + 单一公式（P0）**
同筛选（域=D1、近 30 天、厂商不筛选）下，`req.total`、`req.completionRate`、`cycle.avg`、`vendor.onTimeRate` 四个指标在 `/portal` 卡片、`/dashboard` 表格、`/reports` 交叉表三处**逐位相等**（整数相等；比率/days 精确到 1 位小数相等）；后端仅一处计算（三端点共用 `AnalyticsQuery`）；静态断言：analytics 包内无第二处 `p90` 实现、完成态字面量仅 1 处、前端源码内无对计数做除法的指标格式化（比率均来自后端字段）。
证据：`workbench-verify` w4（三端点同参对拍 + 静态审计）；`report-verify` r2 复用同一断言。

**AC-4 看板五块 + P90 口径（P1）**
五块全部有数据；多维块支持 1 行 × 1 列交叉并给出行/列/总计小计；分段块五段齐备（空段显示 0 而非缺行）；P90 与 `FlowMetricFormulas.p90Dwell` 完全一致，脚本钉住 `n=10 → 第 9 个`、`n=11 → 第 10 个` 两个边界（构造 10/11 条已知时长数据，断言取值）；人天偏差在预估为 NULL 时返回 null 且页面显示"—"，并给出"无预估需求数"；厂商排名前 10 且当前账号厂商高亮。
证据：`workbench-verify` w5；`report-verify` r3（P90 边界用同一构造数据）。

**AC-5 4 数据集白名单（P0）**
`GET /reports/datasets` 返回恰好 4 个数据集，字段与 §6.5 表逐项一致（脚本比对常量清单，防前后端漂移）；未知字段 → `400 REPORT_UNKNOWN_FIELD`（body 含 `field`）；非度量字段放值槽 → 同码；跨数据集混用 → `400 REPORT_DATASET_MISMATCH`；白名单外的字段无法通过任何参数注入（尝试 `field=status;drop` 类注入仅得 400）。
证据：`report-verify` r0。

**AC-6 交叉表与图表切换（P1）**
拖入行/列/值/筛选后 800ms 内出交叉表（本地栈、固定 5 万行数据）；行列维度交换后结果矩阵转置一致（`A[i][j] == 转置后[j][i]`）；`null` 单元格显示"—"不显示 0；空结果仍渲染表头 + 一行"当前筛选无数据"；图表切换规则：柱状（1 维/1 值）、堆叠柱（2 维/1 值）、折线（时间维）、饼（1 维且类别 ≤8，超出提示改柱状并禁用饼）；切换图表不改变已查数据（不重复请求）。
证据：`report-verify` r1；截图 2 张（交叉表 / 堆叠柱）。

**AC-7 报表生命周期（P1）**
新建（默认 `PRIVATE`，名称 1–40 字符，同 owner 重名 → `409 REPORT_NAME_DUPLICATED`）→ 重命名 → 复制（副本名 `<原名>-副本`、owner=当前账号、force PRIVATE、不共享）→ 删除（软删，列表与详情均不可见）；他人 PRIVATE 报表的 GET/PUT/DELETE 一律 `404 REPORT_NOT_FOUND`（非 403）；只有 owner 可改/删/共享；`definition_json` 往返不变（保存后读回与原定义结构等价，字段顺序无关）。
证据：`report-verify` r4。

**AC-8 共享范围与 REPORT_SHARE（P1）**
`scope` ∈ PRIVATE/ROLE/DOMAIN/PUBLIC；改 scope 需 `REPORT_SHARE`，无码 → `403 REPORT_SCOPE_FORBIDDEN`；ROLE 的 `scope_ref` 必须是本人角色或管理员可指定；DOMAIN 必须与本人域有交集；列表分"我的/共享给我"两段且同一报表不重复出现；共享后同角色账号可见该报表**定义**，但其聚合结果按查看者自身 Scope 计算（见 AC-10/11）。
证据：`report-verify` r5（含 4 组正负例）。

**AC-9 导出与水印（P0）**
默认格式 = XLSX（不传 `format` 即 XLSX）；XLSX 首行合并水印格 + 每页页脚均含 `租户`、`导出人`、`报表名#id`、时间；CSV 首行为 `# exporter=...` 元信息、第二行表头、UTF-8 BOM；导出数据行数 = 页面交叉表数据行数（含 totals 行时一致）；导出行 ≤10000，超限 `400 REPORT_EXPORT_TOO_LARGE`；CSV 旁提示"对外提交请使用 XLSX"。
证据：`report-verify` r6（脚本解析 xlsx：读首行/页脚/行数；解析 csv：行数 = 1+1+N）。

**AC-10 导出不超权限 + 单一过滤（P0）**
同一报表（admin 建的 PUBLIC）分别由 admin 与 vendor1 导出：vendor1 文件内厂商列 distinct = 仅 VEN-A，且行数 = vendor1 在页面看到的行数；`admin` 行数 ≥ vendor1 行数；静态断言：`deliver-service/.../report/**` 内每处 `ScopedJdbc` 调用的 SQL 均含 `/*scope:` 标记（数量相等），且包内无 `where .*domain_id` / `vendor_id =` 形式的自写过滤；运行期断言：故意删掉一条 marker → 查询抛 `scope marker missing in SQL`（fail-closed 生效）。
证据：`report-verify` r7（动态双账号 + 静态审计 + 变异自检）。

**AC-11 两厂商隔离（P0）**
同一 `definition_json`，vendor1 与 vendor2 的聚合结果**不同**，且各自结果中不出现对方的 `vendorId`；交叉表维度含"厂商"时，vendor1 只出现 VEN-A 一行；厂商维度小计之和 = 该账号总计（防止"总计按全租户算、明细按 Scope 算"的错配）。
证据：`report-verify` r8。

**AC-12 上限与缓存（P1）**
5 种超限各自返回对应错误码（维度 5 个 / 度量 4 个 / 筛选 9 个 / 分组 >5000 / 跨度 367 天），且错误 body 含实际值与上限；首次查询 `X-Report-Cache: MISS`、1 秒内同参再查 `HIT`；TTL 300s（可注入短 TTL 验证过期后回 MISS）；**跨账号不串**：vendor1 查询后 vendor2 同 definition 必为 MISS 且结果不同；保存/删除报表后该报表缓存失效（再查 MISS）；响应始终带 `dataAsOf`。
证据：`report-verify` r2（含缓存头与跨账号）。

**AC-13 V10 迁移（P1）**
`V10__report_definition.sql` 建 `report_definition` 与 `portal_card_config`（列、唯一键、索引与 §6.9 一致）；重复启动 Flyway 校验通过、不重复执行；两表**不**出现在 `ScopeSql.TABLES`（脚本断言），而业务查询仍 100% 走 `ScopedJdbc`；`definition_json` 用 JSON 列且可存 UTF-8 中文名；`status` 软删后列表不返回。
证据：`report-verify` r0b（迁移后 schema 查询 + 二次启动 + ScopeSql 断言）。

**AC-14 设为工作台卡片 + 管理员配显隐（P1）**
报表"设为卡片"后 `/portal` 出现该卡片（值 = 该报表首格度量、带"数据截至"与刷新）；个人卡片上限 4，超出 → `409 PORTAL_CARD_LIMIT` 且弹层列出当前 4 张（不静默替换）；管理员用 `PORTAL_CONFIG` 关掉某角色的一张模板卡片后，该角色登录看到的卡片数 −1，其他角色不变，个人卡片不受影响；关掉后又打开可恢复（幂等）。
证据：`workbench-verify` w3/w5 + `report-verify` r6 尾段。

**AC-15 权限码 56→60 + 三处基数同步（P0）**
`PermissionMatrix` 函数码 56 → 60，矩阵格 560 → 600；四个新码的授予角色与 §6.10 一致（10 角色 ALLOW / admin only / 10 角色 / 4 角色）；三处硬编码基数同步（`scripts/platform-verify.sh:344`、`scripts/tests/collab/s0-schema.sh:99-102`、`scripts/tests/access/a3-nav-audit.mjs:126`）；`access-verify` 总数 ≥115 → 新增 a6 段（报表/工作台准入 ≥8 断言）后 ≥123，a3 全集 +4；`PermissionRouteTable` 新规则生效（无码调用对应端点 → 403）。
证据：`platform-verify`、`collab-verify` s0、`access-verify`（a3/a4/a6）。

**AC-16 新门禁 + 既有门禁零回归（P0）**
`scripts/workbench-verify.sh` 段 `w0..w5`，断言 ≥36、每段 ≥4；`scripts/report-verify.sh` 段 `r0..r8`（含 r0b），断言 ≥48、每段 ≥3；两者在栈未起时 **非 0 退出且不写计数**（fail-closed）；`Makefile` 增 `workbench-verify`/`report-verify`，`scripts/test-all.sh` 聚合清单接入。既有门禁全部 ≥ 实测基线且不红：`verify` 14、`platform-verify` 167、`quality-verify` 348、`integration-verify` 231、`collab-verify` 263、negative **23**（见文首"取代"）、`unit` 237、`lint` 16、`access-verify` ≥123、`typecheck`、`build`。
证据：两脚本运行输出摘要 + 既有门禁逐条结果。

**AC-17 文档同步与截图（P2）**
`DELIVERED-MODULES.md` §9 改为真实状态：三块能力、4 个新路由、4 个新权限码、两个门禁脚本名、`report_definition` 表；删除或改写原"已交付"表述中不成立的部分（不得只加一句"补充说明"）；`DEMO-BY-ROLE.md` 补三行演示路径（pm 看工作台 / admin 配卡片 / vendor1 导出带水印）与账号表（若 Q3 采纳则 12→13）；新增截图 `portal-pm.png`、`dashboard-admin.png`、`reports-vendor-export.png`（1440×900，与页面一致）。
证据：文档 diff + 截图经图像复核。

**AC-18 性能（P2）**
固定数据集（5 万需求 / 2 千缺陷 / 5 百审批单）：工作台首屏（五区一次批量）P95 ≤ 2.0s；看板多维聚合 P95 ≤ 1.5s；交叉表（4 维 3 值）P95 ≤ 1.5s；XLSX 导出 1 万行 ≤ 5s；缓存命中 ≤ 300ms。测量方式：脚本对每端点各 20 次取 P95，冷缓存先清（重启或按前缀失效）。
证据：`report-verify` r8 性能尾段输出（P50/P95/最慢一次）。

## 12. 门禁与证据清单

| 门禁 | 覆盖 | 断言下限 |
|---|---|---|
| `scripts/workbench-verify.sh`（w0–w5） | 路由准入、模板矩阵、五区格式、三处一致、看板五块、卡片配置 | ≥36（每段 ≥4） |
| `scripts/report-verify.sh`（r0–r8 + r0b） | 数据集白名单、迁移、交叉表、上限缓存、生命周期、共享、导出水印、超权限、隔离、性能 | ≥48（每段 ≥3） |
| `scripts/access-verify.sh`（a0–a6） | 新增 a6 报表/工作台准入；a3 全集 +4 码 | 总数 ≥123 |
| 既有 | platform 167 / quality 348 / integration 231 / collab 263 / negative 23 / unit 237 / lint 16 / verify 14 / typecheck / build | 全部 ≥ 基线 |

## 13. 里程碑建议

| 切片 | 内容 | 依赖 | 预估 |
|---|---|---|---|
| S1（P0 骨架） | V10 迁移、`AnalyticsQuery` + 指标字典 + 4 数据集白名单、`/reports/datasets`、权限码 4 个 + 三处基数、`/portal` 壳与准入 | 无 | 1.5 天 |
| S2（P0 工作台） | 8 模板 + 五区 + 全局筛选 + 卡片显隐 + 兜底模板 | S1 | 1.5 天 |
| S3（P0/P1 看板+报表） | `/dashboard` 五块；`/reports` 列表 + 编辑器 + 交叉表 + 图表 + 生命周期 + 共享 | S1 | 2.5 天 |
| S4（P0 导出与隔离） | XLSX/CSV 水印导出、Scope 单点接入、两厂商隔离用例、上限与缓存 | S1、S3 | 1 天 |
| S5（收尾） | 两门禁脚本 + 聚合清单、文档与截图、性能测量、收尾提交 | S2–S4 | 1 天 |

并行建议：S2 与 S3 可由两人并行（模板/卡片 vs 报表编辑器），共用 S1 的引擎与契约；S4 必须等 S3 的查询契约冻结后再动导出。
