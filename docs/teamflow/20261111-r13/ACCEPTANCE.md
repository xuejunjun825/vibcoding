# 验收报告 · r13 分析域四页前端视图

| 项 | 内容 |
|---|---|
| 需求 | 为 `/portal`、`/dashboard`、`/reports`、`/reports/:id` 补齐前端视图（PRD 20 条 AC） |
| 任务夹 | `docs/teamflow/20261111-r13/` |
| 分支 | `feat/analytics-portal-views` |
| 验收日期 | 2026-11-12 |
| 证据底稿 | `PRD.md` §7、`QA-REPORT.md`（第 2 轮）、`EVIDENCE.md`、`evidence/` 20 张 PNG |

**结论：通过。** 20 条 AC 全部满足；独立复跑的类型检查、构建、lint、access 门禁全绿；实现与架构蓝图一致，`deliver-service`、router、nav、`/workbench` 零 diff。AC-13 留 1 项 P3（转置后 DOM 10006），连同其余观察项列于 §5，不阻断本轮验收。

## 1. 验收方法

1. 以 PRD §7 的 20 条 AC 为唯一判据，逐条对照 QA 报告与 EVIDENCE 的可执行证据，不复述其结论。
2. 独立复跑关键门禁：`vue-tsc`、`npm run build`、`lint.sh`、`access-verify.sh`，并核对改动边界、模块清单与截图尺寸。
3. 架构一致性：按注入蓝图逐项核对 4 个视图 + 12 个共享单元 + 1 个取数原语；grep 单一实现；确认无后端/路由/导航改动。
4. 人工视觉抽查 6 张关键截图（正常态 3 张、降级/409/空态各 1 张），确认无白屏、遮挡、错位。

## 2. 独立复跑结果（本轮执行，非引用 QA）

| 检查 | 命令 | 结果 |
|---|---|---|
| 类型 | `cd web && npx vue-tsc --noEmit` | exit 0，0 错误 |
| 构建 | `cd web && npm run build` | exit 0，`✓ built in 8.48s` |
| lint | `bash scripts/tests/lint.sh` | 17/17（含 L15 取数单点） |
| access | `bash scripts/access-verify.sh` | 145/145（a0 29 / a1 8 / a2 63 / a3 9 / a4 14 / a5 22） |
| 边界 | `git diff --stat -- deliver-service req-service platform-* web/src/router web/src/layouts/nav.ts web/src/views/workbench web/package.json web/package-lock.json` | 空 |
| 截图 | `sips` 逐张量尺寸 | 20 张全部 1440×900，0 异常 |
| 模块 | 按蓝图清单 `ls` | 4 视图 + 12 单元 + 1 composable 全部存在 |
| 单一实现 | `grep -rn "FilterBar\|AbortController\|fetch("` | FilterBar 仅 2 视图 import；AbortController 仅 store；视图 0 直连 |
| 卫生 | `grep -rn "@ts-ignore\|: any"` | 0 命中 |

## 3. 逐条 AC 核对（20/20）

| AC | 判定 | 依据 |
|---|---|---|
| AC-1 构建与改动边界 | 通过 | 本轮复跑 tsc/build 均 0；后端、router、nav、`/workbench`、package/lock 零 diff；改动仅限 r13 允许范围。 |
| AC-2 四路由与角色可达 | 通过 | QA `qa-r13-pages-smoke` 57/57（4 账号×4 路由、控制台 0、`/reports/abc`→404、`999999`→「报表不存在」）；16 张基础截图。 |
| AC-3 /portal 五区顺序与模板 | 通过 | `PortalView.vue:82` 直接 `v-for="section in sections"`；无 `template ===` 分支；`portal-mgmt.png` 只读态隐藏 actions 且显「只读」；t5 195 断言。 |
| AC-4 卡片同源 + 口径戳 | 通过 | t5 卡片数字与 metrics/run 同源断言；`DataStamp.vue` 统一 `dataAsOf` + HIT/MISS；抽查截图均显示「数据截至 …」。 |
| AC-5 单区降级不白屏 | 通过 | `portal-degraded.png` 实测待办 DEGRADED、其余四区 OK、区内「重试」；整页 500 重试只发一次（t5）。 |
| AC-6 /dashboard 五块与 null | 通过 | `dashboard-layout` 五块顺序/标题、null→「—」、canvas、1280/1440 无溢出；截图佐证。 |
| AC-7 筛选唯一实现/URL 双向 | 通过 | FilterBar 仅 `PortalView.vue:5`、`DashboardView.vue:7` 两处 import，fields 分别 3/4 项；`filter-url-sync`（深链/前进后退/重置）、`filter-debounce`（3 改→1 请求）、`qa-r13-feed-race` 11/11（abort+seq）。 |
| AC-8 在报表中打开带筛选 | 通过 | 跳转 URL `/reports/new?dataset=requirement&preset=7d&domainId=1`；编辑器顶部范围 chips（`editor-409.png` 可见）。 |
| AC-9 /reports 两段/CRUD/空态 | 通过 | `t7-reports-list-check` 24/24（两段、六列、操作按属主/权限、三种空态）；`reports-empty.png`。 |
| AC-10 共享五档 + 固定提示语 | 通过 | `t4-dialogs` 37/37、`qa-r13-share-pin` 18/18（五档顺序、无 TENANT、ROLE 数字校验、403 页内、固定提示语）。 |
| AC-11 导出 XLSX/CSV/上限 | 通过 | `qa-r13-export-artifact` 19/19（XLSX PK 头/水印/报表名、CSV BOM、下载落地、400 上限页内提示）。 |
| AC-12 三栏/字段池/四槽限额 | 通过 | `t8-editor-e2e`（三栏、4 数据集 17 字段、四槽、限额穷举）；`editor-limits-probe`（超限 run 请求 0）。 |
| AC-13 交叉表/转置/图表/宽表 | 通过（1 项 P3 观察） | `qa-r13-crosstab-scale` 17/17（5000 行 DOM≤3000、sticky、翻页/转置不重发、60 列横滚）；`t3-chartmodel` 47/47（4×5 规则表）；转置后 DOM 10006 见 D3。 |
| AC-14 保存/另存/未保存守卫 | 通过 | `t8-editor-e2e`（新建 201→replace→run、编辑刷新深比较、另存副本、409 行内、未保存守卫、404）。 |
| AC-15 pin 409 + 去工作台移除 | 通过 | `qa-r13-share-pin` 18/18；`editor-409.png` 真实 409 恰列 4 张、无第 5 张、「去工作台移除」「下轮支持移除」。 |
| AC-16 20 张截图与视觉基线 | 通过 | 20 张实测 1440×900；`EVIDENCE.md` §3 逐张记 URL/期望/实际；控制台 0；人工抽查 6 张布局合理、无遮挡白屏。 |
| AC-17 access 覆盖新路由 | 通过 | 本轮复跑 145/145：a2 63（9 页×7 角色）、a3 侧栏 36、a0 29/a1 8/a4 14/a5 22；≥143 下限且未删项。 |
| AC-18 lint/回归/依赖 | 通过 | build 0、lint 17/17（原 16 + L15 单点守卫）、无 `@ts-ignore`/`: any`、package/lock 零新增；既有页面由 smoke 覆盖。 |
| AC-19 字典 403 silentForbidden | 通过 | `dict-forbidden-probe`：`/dashboard` 字典 403 → 页内提示、URL 不变、不跳 `/403`；a5 22/22 含调用点判定。 |
| AC-20 卡片配置抽屉（P1） | 通过 | t5 195 断言：admin 可见、pm/vendor1 不可见、10 角色、读 config 复选、两次 PUT 幂等。 |

## 4. 架构一致性核查（M3）

- **蓝图齐全**：`views/{portal/PortalView,analytics/DashboardView,reports/ReportListView,reports/ReportEditorView}.vue`；`components/analytics/{FilterBar,DataStamp,CrossTable,ChartView,ShareDialog,PinLimitDialog}.vue` + `chartModel.ts` + `portalRoles.ts`；`components/portal/{PortalSection,CardConfigDrawer}.vue`；`components/reports/{SlotBoard}.vue` + `limits.ts`；`composables/useAnalyticsFeed.ts`。无蓝图外重复实现。
- **单一实现**：FilterBar 仅 portal/dashboard 两处 import；防抖 + AbortController 只在 `stores/analytics.ts:235/305`（`schedule`），过期响应 seq 丢弃只在 `useAnalyticsFeed.ts:48-63`；视图内 0 处 `AbortController`、0 处直连 `/api/`。报表列表按蓝图复用既有 `useListQuery`。
- **契约一致**：`vue-tsc` 0；`qa-r13-interface-check` 38 文件/27 导入/29 props `problems=0`；`api/analytics.ts` 类型逐字对齐后端 record（含 `VENDOR`、`crossTab.groups`、`runReport` 走 query）。
- **可接受扩展**：`useAnalyticsFeed` 增可选 `enabled()`（编辑器未保存不取数，默认行为不变）；`ChartView` 保留 `ChartViewMode` 兼容别名。均向后兼容，已在代码注释说明。
- **重复风险登记**：`portalRoles.ts` 镜像后端 `PortalTemplates.ROLE_ORDER`、`limits.ts` 镜像 `MAX_*`、`api/analytics.ts` 镜像后端 record，均有注释声明，非漂移。
- **边界**：`deliver-service`、`req-service`、`platform-*`、`web/src/router`、`web/src/layouts/nav.ts`、`web/src/views/workbench` 零 diff；无新增依赖。

## 5. 遗留与观察（均不阻断）

| 编号 | 级别 | 内容 | 建议 |
|---|---|---|---|
| D3 | P3 | 5000 行报表转置后 DOM 单元格 10006（常规态 S1/S2 均 ≤3000，功能与报错正常） | 下轮给转置态也加分页/列裁剪，或明确 AC-13 预算只约束常规分页态 |
| D4 | P3 | `Makefile:85` 帮助文案仍写「5页×7角色矩阵」，a2 已是 9 页/63 条 | 改为「9页×7角色矩阵」 |
| O1 | P3 | access 实际 145 条（a5 为 22，PRD 算术用下限 20 得 143） | 保留 145、不删项；PRD/脚本注释统一「下限 143、实际 145」口径 |
| O2 | P3 | AC-1 白名单未枚举蓝图要求的 `components/portal|reports/**`、`composables/useAnalyticsFeed.ts`、`scripts/tests/lint.sh` | 属 PRD 枚举不全，实际未越界；下轮补全白名单 |
| O3 | P3 | lint 由 16 增至 17（新增 L15 单点守卫），与 AC-18 的「16/16」字面差异 | 属加强门禁，非放宽；按 17/17 验收 |

说明：`EVIDENCE.md` 开头已写明本轮不提交、收尾提交由 host 按 §11.1 白名单执行；工作区改动即交付物，不视为缺陷。

## 6. 人工补测建议（环境限制，非交付缺陷）

- 视觉基线像素比对：20 张截图与设计基线逐张比对（Playwright `toHaveScreenshot` 或人工）。
- 截图人工审阅：字号/间距/对齐/图表配色是否符合 DESIGN.md。
- 动效手感：弹层淡入、chip 出现/移除、加载脉冲（CDP 无法评估帧率观感）。
- 响应式边界：<1280 / >1920 视口未覆盖。
- 无障碍：Tab 顺序、焦点可见、屏幕阅读器语义（axe DevTools + 人工）。
- 真实下载落地：下载目录 xlsx/csv 用 Excel 打开（脚本已验 ZIP/BOM/水印字节）。

## 7. 最终判定

20 条 AC 全部满足；关键门禁独立复跑全绿；架构与蓝图一致、无重复实现、无越界改动。D3/D4 等 5 项均为 P3 观察，不影响本轮可用性与验收结论。同意交付。

验收结论：✅ 通过
