# QA 复验报告 · r13 分析域四页前端视图（第 2 轮 · 修复复验）

- 需求任务夹：`docs/teamflow/20261111-r13/`（PRD.md / DESIGN.md / TECHNICAL.md / EVIDENCE.md）
- 分支：`feat/analytics-portal-views`；被测栈：网关 `127.0.0.1:8080` + 前端 `127.0.0.1:5173`（dist 生产构建，本轮已重建）+ 无头 Chrome CDP `9222`
- 测试日期：2026-11-12；轮次：**修复复验（re-verification）**，上一轮结论为「不通过（D1/D2 两项 P1）」
- **结论：通过（可验收）**。上一轮两项 P1 均以原命令复跑确认关闭且命中数归零；20 条 AC 全部通过并有可执行证据；仅 P3 观察项 D3（转置后 DOM 单元格 10006）延续开放，按规则不阻断验收。

## 1. 复验范围与方法

| 维度 | 做法 | 覆盖 |
|---|---|---|
| 回归契约 | 先按原命令重跑 `logs/teamflow/tf-mwc1mure-iyojpx/scripts/` 下既有**全部** 26 个探针/门禁（上一轮留下的检查器即本需求的回归契约），逐条记录 pass 数 | §2 #1–#26 |
| 缺陷项 | 按上一轮缺陷行的原 Check command 复跑，不替换等价物；重算同一 sweep 的命中数 | §3 |
| 新覆盖面 | 补「取数竞态：迟到响应丢弃 / 卸载中止 / 切报表收敛」——上一轮所有探针只断言请求**次数**与最终 DOM，从未制造真正迟到的响应 | §5 |
| 门禁 | `vue-tsc --noEmit`、`npm run build`、`scripts/tests/lint.sh`、`scripts/access-verify.sh` | §2 #3–#6 |
| 静态 | 架构唯一实现扫描、接口一致性（导入符号/成员/形状）、依赖零新增、提交面卫生 | §6 §7 |
| 截图 | 20 张 1440×900 重新生成 + Pillow 尺寸/非空白校验 | §2 #26 |

## 2. 已执行检查（原命令重跑，命令 + 退出码 + 通过数）

| # | 命令 | 退出码 | 结果（逐字） |
|---|---|---|---|
| 1 | `node …/scripts/qa-r13-ac1-boundary.mjs` | 0 | `guarded=5 dirty=7 violated=0 total.values=2 distinct=2 colHeaders=16` |
| 2 | `node …/scripts/qa-r13-arch-feed-single.mjs` | 0 | `views=4 duplicate-primitives=0 composable=ok wired=3/3 self-test=3/3` |
| 3 | `cd web && npx vue-tsc --noEmit` | 0 | 0 类型错误 |
| 4 | `cd web && npm run build` | 0 | `✓ built in 7.82s`（ChartView/ReportEditorView 分包） |
| 5 | `bash scripts/tests/lint.sh` | 0 | 17/17（新增 L15「分析域取数原语单点」） |
| 6 | `bash scripts/access-verify.sh` | 0 | 145/145（a0 29 a1 8 a2 63 a3 9 a4 14 a5 22；下限 143） |
| 7 | `node …/scripts/qa-r13-pages-smoke.mjs` | 0 | 57/57（4 账号×4 路由、控制台 0、READONLY 插桩、五块顺序、`/reports/abc`→404、`999999`→「报表不存在」） |
| 8 | `node …/scripts/qa-r13-crosstab-scale.mjs` | 0 | 17/17（5000 行 DOM≤3000、sticky、翻页/转置不重发、60 列横滚；OBS 转置后 10006） |
| 9 | `node …/scripts/qa-r13-share-pin.mjs` | 0 | 18/18（五档顺序、固定提示语、无 TENANT、ROLE 空值拦截、403 页内、真实 409 恰 4 行） |
| 10 | `node …/scripts/qa-r13-export-artifact.mjs` | 0 | 19/19（XLSX PK 头+水印+报表名、CSV BOM、下载落地、上限 400 页内提示） |
| 11 | `node …/scripts/t8-editor-e2e.mjs` | 0 | `run 请求 7 次，控制台错误 0`（限额穷举、保存 replace、槽位/连点/范围各 1 次、另存、未保存守卫、404） |
| 12 | `node …/scripts/t5-portal-e2e.mjs` | 0 | `ALL PASS · 195 assertions`（卡片同源、单区 DEGRADED、整页 500 重试、抽屉 10 角色 + PUT 幂等） |
| 13 | `node …/scripts/t7-reports-list-check.mjs` | 0 | `a7 24 24`（两段 Tab、操作可见性、三种空态） |
| 14 | `APP=http://127.0.0.1:5180 node …/scripts/t4-dialogs-check.mjs` | 0 | `t4-dialogs 37 37`（临时 vite 配置已删、5180 已停） |
| 15 | `node …/scripts/filter-url-sync-probe.mjs` | 0 | 深链 6 键回显、改窗保留 foo/reqType、换域清 vendorId、**后退/前进逐次还原**、重置回 30d、控制台 0 |
| 16 | `node …/scripts/filter-debounce-probe.mjs` | 0 | 3 次快改 → 1 次请求；URL `preset=30d`；控制台 0 |
| 17 | `node …/scripts/dict-forbidden-probe.mjs` | 0 | `dict-403 {"path":"/dashboard","hint":true,"filters":4,"page":true,"intercepted":3}` |
| 18 | `node …/scripts/editor-limits-probe.mjs` | 0 | 图表 4/4 禁用且给原因、行第 4 字段只提示、run 请求 0、控制台 0 |
| 19 | `node …/scripts/dashboard-layout.mjs` | 0 | 五块 `breakdown,cycle,effort,vendor,quality`、`数据截至 2026-11-12 16:25HIT`、1280/1440 页面无溢出、跳转带 `dataset/preset/domainId` |
| 20 | `node …/scripts/share-refresh-probe.mjs` | 0 | PRIVATE→PUBLIC→PRIVATE 重开回填，控制台 0 |
| 21 | `bash …/scripts/capture-contract.sh` | 0 | 28/28（六份响应固化 `docs/…/evidence/*.json`，report id=228） |
| 22 | `bash …/scripts/crosstab-total-shape.sh` | 0 | 9/9（cols id=250：`total.values` 长度=度量数 2；CSV 总计行 21 字段仅 2 非空） |
| 23 | `node --experimental-strip-types …/scripts/t1-reqtype-check.mjs` | 0 | `t1-reqtype 12 12`（见 §5 检查桩说明） |
| 24 | `node --experimental-strip-types …/scripts/t3-chartmodel-check.mjs` | 0 | `t3-chartmodel-check: 47/47 asserts` |
| 25 | `node …/scripts/qa-r13-interface-check.mjs` | 0 | `files=38 imports=27 props=29 problems=0` |
| 26 | `node …/scripts/shots.mjs` + Pillow 校验 | 0 | 20 张 1440×900；`consoleErrors` 全部 `[]`；`pngs=20 ok=20 bad=[]` |

## 3. 缺陷复验对比（round-2 级 —— 非「严重级」列名，避免被缺陷追踪器当新缺陷导入）

| 编号 | round-2 级 | 功能模块 | 上一轮结论 | 本轮实测（原 Check command） | 关闭判据 | 结论 |
|---|---|---|---|---|---|---|
| D1 | 已关闭 | 交付边界（后端） | P1 越界改 `AnalyticsQueryService` total 行 | `violated=0`；`git status -- deliver-service` 为空；`total.values=2`（=度量数），`distinct=2` | 后端零 diff 且 total 行长度=度量数 | **关闭** |
| D2 | 已关闭 | 架构（取数原语） | P1 编辑器自建 AbortController/runSeq | `duplicate-primitives=0`；`ReportEditorView.vue:103` 走 `useAnalyticsFeed`，`wired=3/3`，lint L15 单点自检 3/3 | 全仓仅一处防抖+abort+seq | **关闭** |
| D3 | P3 | 交叉表转置 | P3 转置后 DOM 10006 单元格 | 仍为 `OBS 转置后 DOM 单元格 = 10006`（常规态 S1/S2 均 ≤3000，功能与报错均正常） | 转置后单元格亦 ≤3000 | **仍开放（观察）** |

**缺陷类命中数重算（同一 sweep）**：D1 类＝`deliver-service` 被改文件数 1→**0**、越界构造点 1→**0**；D2 类＝视图内 `AbortController` 1 处（旧 `:269`）+ 自建 `runSeq` 1 处（旧 `:96`）→**0**（`grep -rn AbortController web/src` 仅 `composables/useAnalyticsFeed.ts`、`stores/analytics.ts`、`api/client.ts`）；视图内裸 `fetch(` 0 处、`/api/` 直连 0 处、300ms 字面量仅 `DEBOUNCE_MS` 一处。修复摘要口径与本轮 sweep 一致，无分歧。

## 4. 仍 OPEN 的缺陷

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D3 | P3 | 交叉表（质量观察） | 编辑器打开 5000 行报表后点「转置」 | 转置视图的 DOM 单元格量级也应受 AC-13 的 ≤3000 预算约束 | 转置后 5000 行变 5000 列，单页渲染 10006 个单元格；功能正确、无报错、常规态 S1/S2 均 ≤3000 | AC-13 | - | - |
| D4 | P3 | 门禁帮助文案 | `make help` 或直接看 `Makefile:85` | 帮助文案与 a2 现状一致（9 页×7 角色 = 63 条） | 仍写「5页×7角色矩阵」，而 `scripts/tests/access/a2-page-matrix.sh` 已是 9 页/63 条 | AC-17（表述一致性） | - | - |

> 说明：D3、D4 均为 P3、不阻断验收，故末两列填「-」；P0/P1/P2 本轮为 0 条。

## 5. 补测的新覆盖面（上一轮未覆盖，为什么漏）

上一轮的 26 个探针全部只断言「请求次数」与「最终 DOM」，没有**制造真正迟到的响应**——而 D2 的修复正是把 abort + seq 丢弃交给 `useAnalyticsFeed`，若 seq 失效，用户快速切筛选/切报表时会看到旧数据覆盖新数据且控制台干净。新增 `qa-r13-feed-race.mjs`（11/11，exit 0）：

| 场景 | 制造方式 | 断言 |
|---|---|---|
| R1 迟到响应丢弃 | Fetch 域扣住 `/dashboard` 第 1 次请求，期间切「近 7 天」让第 2 次先落地，再用 `dataAsOf=1999-01-01 00:00` 的伪造响应放行第 1 次 | 页面任何时刻不含该标记；口径戳仍是当前日期；控制台 0 |
| R2 卸载中止 | 请求在途时导航到 `/portal`，再放行响应 | `/portal` 五区正常、迟到响应到岸不报错 |
| R3 切报表收敛 | `/reports/250` → `/reports/251` | 标题收敛到 251、切换后不再为 250 发 run、控制台 0 |

另：`t1-reqtype-check.mjs` 首跑 `EXIT=1`（`TypeError: router.push is not a function`，`web/src/stores/analytics.ts:269`）。核查为**检查桩过期**而非产品缺陷——store 本轮把 `applyToRoute` 由 `replace` 改为 `push`（AC-7 前进/后退逐次还原，已由 `filter-url-sync-probe` 真实浏览器验证）；已让桩同时提供 `push/replace` 并保留全部原断言，复跑 `t1-reqtype 12 12`。

## 6. AC 逐条结论（20/20 通过）

| AC | 结论 | 依据 |
|---|---|---|
| AC-1 构建与改动边界（P0） | 通过 | #1（`violated=0`）、`git status -- deliver-service` 空、#3 #4 |
| AC-2 四路由×角色可达（P0） | 通过 | #7（57/57，含 `/reports/abc`→404、不存在 id 文案） |
| AC-3 /portal 五区顺序与模板（P0） | 通过 | #7（DOM 区顺序 == 后端 sections；READONLY 隐藏 actions 但仍渲染 4 区）、#12 |
| AC-4 卡片同源 + dataAsOf/cache（P0） | 通过 | #12（195）、#19（口径戳） |
| AC-5 单区降级不白屏（P0） | 通过 | #12（单区 DEGRADED + 区内重试只重发一次） |
| AC-6 /dashboard 五块与 null 语义（P0） | 通过 | #7 #19 |
| AC-7 筛选唯一实现/URL 双向/防抖（P0） | 通过 | #2 #15 #16；`FilterBar` 仅 portal/dashboard 两处 import |
| AC-8 在报表中打开带筛选（P0） | 通过 | #19（`/reports/new?dataset=requirement&preset=7d&domainId=1`） |
| AC-9 /reports 两段/CRUD/空态（P0） | 通过 | #13（24/24） |
| AC-10 共享五档+固定提示语（P0） | 通过 | #9（18/18）、#14（37/37） |
| AC-11 导出 XLSX/CSV/上限（P0） | 通过 | #10（19/19） |
| AC-12 三栏/字段池/四槽限额（P0） | 通过 | #11 #18；`limits.ts` 与 `AnalyticsDatasets.java:25-29` 逐字一致 |
| AC-13 交叉表/转置/图表规则/宽表（P0） | 通过（1 项 P3 观察） | #8 #24 #22；D3 |
| AC-14 保存/另存/未保存守卫/404（P0） | 通过 | #11 |
| AC-15 pin 409 + 去工作台移除（P0） | 通过 | #9（C1–C7，真实 409 恰 4 行） |
| AC-16 20 张截图与视觉基线（P0） | 通过 | #26（20/20 尺寸与非空白，四张负向齐全） |
| AC-17 access 门禁 143（P0） | 通过 | #6（145 ≥ 143；a2 63、a3 侧栏 36） |
| AC-18 lint/回归/依赖零新增（P0） | 通过 | #3 #4 #5；`git diff web/package.json` 与 lock 均 0 行 |
| AC-19 字典 403 降级 silentForbidden（P0） | 通过 | #17；access a5 22/22 含 `silentForbidden` 调用点判定 |
| AC-20 管理端卡片配置抽屉（P1） | 通过 | #12（10 角色、读 config 复选、PUT 幂等） |

## 7. 架构与接口核查（M3 质量门）

- **蓝图模块清单实测齐全**：4 个视图（`views/portal/PortalView.vue`、`views/analytics/DashboardView.vue`、`views/reports/ReportListView.vue`、`views/reports/ReportEditorView.vue`）+ 12 个共享单元（`components/analytics/{FilterBar,DataStamp,CrossTable,ChartView,ShareDialog,PinLimitDialog}.vue`、`chartModel.ts`、`portalRoles.ts`、`components/portal/{PortalSection,CardConfigDrawer}.vue`、`components/reports/{SlotBoard}.vue`、`limits.ts`）+ 1 个取数原语 `composables/useAnalyticsFeed.ts`，无蓝图外重复实现。
- **单一实现**：`FilterBar` 仅 `PortalView.vue:5`、`DashboardView.vue:7` 两处 import（AC-7 明文）；`shared.css` 唯一；`AbortController`/防抖/`seq` 仅存在于 store + composable + api client；视图内 `fetch(` 0 处、`/api/` 直连 0 处。
- **接口一致性（0a，静态）**：`qa-r13-interface-check.mjs` 38 文件 / 27 条相对导入 / 29 处 prop 绑定 **0 缺失**；`vue-tsc --noEmit` 兜住跨文件签名与参数形状（`runReport(id, body, params, signal)`、`cardLimitCards(error)`、`useAnalyticsFeed<T>(fetcher, opts)`）。
- **与蓝图的偏离（可接受）**：`useAnalyticsFeed` 增加可选 `enabled` 选项（编辑器「未保存不可运行」），默认行为不变、向后兼容；`ChartView` 保留 `ChartViewMode` 兼容别名。
- **重复风险点登记仍在**：`portalRoles.ts` 镜像后端 `PortalTemplates.ROLE_ORDER`（G-2 无角色端点）、`limits.ts` 镜像 `MAX_*`、`api/analytics.ts` 逐字对齐后端 record——均有注释声明，非漂移。

## 8. 提交面卫生（0c）

`git status --porcelain` 共 16 条：`memory.md`、4 个 access/lint 脚本、`web/src/api/analytics.ts`、`web/src/stores/analytics.ts`，以及 `docs/teamflow/` 与 `web/src/{views,components,composables}` 的新文件；**无** `node_modules/`、`dist/`、`target/`、`.env`、IDE 缓存（`npm run build` 后复核仍干净）。本轮临时文件 `web/vite.t4qa.config.mjs` 已在 t4 复跑后删除，5180 已停。

## 9. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收标准 | 方法/工具 |
|---|---|---|
| 视觉基线像素比对 | 20 张截图与设计基线无意外位移/换行 | Playwright `toHaveScreenshot` 或人工比对 `docs/teamflow/20261111-r13/evidence/` |
| 截图人工审阅 | 字号/间距/对齐/图表配色符合 DESIGN.md | 人工逐张看 20 张 PNG |
| 动效手感 | 弹层淡入、chip 出现/移除、加载脉冲不突兀 | 人工（CDP 无法评估帧率观感） |
| 响应式边界 | 1280/1440 已脚本验证；<1280 / >1920 未覆盖 | 人工或 Playwright 多视口 |
| 无障碍 | Tab 顺序、焦点可见、屏幕阅读器语义 | axe DevTools + 人工 |
| 真实下载落地 | 下载目录中 xlsx/csv 可直接用 Excel 打开 | 人工（脚本已验 ZIP/BOM/水印字节） |

## 10. 结论

- **可验收**：上一轮两项 P1 已按原命令复跑关闭（D1 后端零 diff 且 `total` 恢复度量级；D2 编辑器并入 `useAnalyticsFeed`，lint L15 守护单点），命中数归零；20 条 AC 全部通过，全部有可执行证据（28 个命令/探针，含新增竞态探针 11/11）。
- 门禁终态：`vue-tsc` 0 错、`build` 0、lint 17/17、access 145/145（下限 143）、20 张截图零控制台错误。
- 遗留：D3（转置 DOM 10006，P3）与 D4（Makefile 帮助文案仍写 5 页，P3），均为观察项，建议下一轮顺带处理，不阻断本轮验收。
