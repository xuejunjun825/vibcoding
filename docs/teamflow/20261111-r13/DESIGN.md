# r13 设计说明（精简版）· 分析域四页前端视图

> 对应 PRD：`docs/teamflow/20261111-r13/PRD.md`（20 条 AC）。
> **本文不重开设计**：§5 D1–D10 已在 PRD 阶段拍板，本文只做落盘引用 + 落到组件/文件这一层，供 `TECHNICAL.md` 与 dev 任务直接引用。
> 基线：`docs/teamflow/20261110-r11-reports-portal-backend/`（后端接口与 wire 形态）、`20261110-r9-workbench-reports/`（门户/报表口径）。
> 取代：PRD AC-13 图表维度规则、AC-15 pin 409 出口 —— 相对 `docs/teamflow/20261110-r12-analytics-portal-views/` 的旧版口径，以本 PRD 为准。

## 1. 设计目标与边界

- 四个**已注册但视图缺失**的路由从白页变为可用页：`/portal`、`/dashboard`、`/reports`、`/reports/:id`。
- 只消费 r11 后端既有接口；**不改后端、不改 `/workbench`、不改 nav 与路由表**。
- 一套筛选、一套聚合口径、一套 URL 状态：`FilterBar.vue` + `stores/analytics.ts` 是唯一实现，视图内不得出现第二套 fetch/防抖/URL 解析。
- 不做：移动端、暗色、可拖拽布局、订阅/推送、新数据集、E2E 框架、图表服务端渲染。

## 2. 信息架构

| 路由 | 视图文件 | 角色口径 | 页面职责 |
|---|---|---|---|
| `/portal` | `web/src/views/portal/PortalView.vue` | `PORTAL_VIEW`，十角色全开；`mgmt` 整页只读 | 岗位工作台：五区固定顺序 + 顶部筛选（域/厂商/时间窗） |
| `/dashboard` | `web/src/views/analytics/DashboardView.vue` | `PORTAL_VIEW` | 数据看板：五块纵向 + 同一筛选条（含需求类型） |
| `/reports` | `web/src/views/reports/ReportListView.vue` | `REPORT_VIEW` | 报表列表：两段 Tab + CRUD 入口 + 空态 |
| `/reports/:id` | `web/src/views/reports/ReportEditorView.vue` | `REPORT_VIEW` | 编辑器：三栏（字段池/四槽/预览）+ 顶栏动作 |

路由级 403 已由 `web/src/router/index.ts` 的 `requiredCodes(meta.functionCode)` 处理，视图内不再写守卫。

## 3. 页面结构（D1/D2/D3/D4 落地）

### 3.1 `/portal`（D1）
- 顶部：模板名 `templateLabel` + 「只读」pill（`todoMode=READONLY`）+ `DataStamp`（`dataAsOf` / `HIT|MISS`）+ 筛选条（`fields=['domain','vendor','preset']`，无 reqType）。
- 主体竖向五区，**顺序完全等于后端 `sections` 数组**，不按标题/字典重排；每区 `data-testid="portal-section-<key>"`。
- 区内：`state=DEGRADED` → 区标题 + 一行原因 + 「重试」；`cards` 网格，卡内指标只 `formatNumber(value,digits)`；`kind=PERSONAL` 卡可点进 `/reports/{id}`。
- `actions` 区在只读态隐藏；`todoSources` 为空显示「暂无待办」。
- admin 额外一个「卡片配置」按钮（`PORTAL_CONFIG`）→ 抽屉。

### 3.2 `/dashboard`（D2）
五块固定顺序与标题：需求量多维 → 周期与 P90 → 人天投入 → 厂商交付排名 → 交付质量。筛选 `fields=['domain','vendor','preset','reqType']`；「在报表中打开」→ `/reports/new?dataset=requirement&<筛选>`。

### 3.3 `/reports`（D3）
Tab「我的报表 / 共享给我」↔ URL `segment=MINE|SHARED`（默认 MINE）；列固定 名称/数据集/共享范围/更新时间/属主/操作；操作按归属与权限码**预判隐藏**（不做「点了才 403」）；他人 PRIVATE 深链按 `REPORT_NOT_FOUND` 显示「报表不存在」。

### 3.4 `/reports/:id`（D4）
三栏：左字段池（`/reports/datasets`，不硬编码）· 中四槽（行/列/值/筛选，HTML5 DnD）· 右预览（交叉表 / 转置 / 图表）。顶栏：名称、保存、另存、导出、pin、共享。

## 4. 组件树（新增）

```
views/portal/PortalView.vue
├── components/analytics/FilterBar.vue        ← 全仓唯一筛选控件
├── components/analytics/DataStamp.vue
├── components/portal/PortalSection.vue       ← 单区渲染 + 区内降级
└── components/portal/CardConfigDrawer.vue    ← AC-20（P1）

views/analytics/DashboardView.vue
├── FilterBar.vue · DataStamp.vue
├── components/analytics/CrossTable.vue       ← 交叉表（sticky/分页/截断）
└── components/analytics/ChartView.vue        ← echarts 按需注册

views/reports/ReportListView.vue
├── components/analytics/ShareDialog.vue
└── useListQuery（既有）

views/reports/ReportEditorView.vue
├── components/reports/SlotBoard.vue          ← 四槽 + 字段池 + DnD + 限额
├── components/reports/limits.ts              ← 限额/算子/合法性（纯函数）
├── components/analytics/CrossTable.vue · ChartView.vue
├── components/analytics/ShareDialog.vue
└── components/analytics/PinLimitDialog.vue   ← AC-15 409 出口
```

## 5. 状态与 URL 契约

- **URL 即状态**：筛选六键 `domainId/vendorId/preset/from/to/reqType`，由 `stores/analytics.ts` 的 `applyToRoute`/`resetFilter` 单向写入，视图只读 `store.filter`。
- **请求即派生**：所有取数用 `store.query`，300ms 防抖 + AbortController；视图不自己拼参数。
- **页面本地态**：`CrossTable` 的页码/转置、图表类型（`report_definition.chart_type` 落库）、弹层开关、抽屉角色 — 不进 URL、不进 store。
- **未保存态**：编辑器 `dirty` 由 `definition` 与最近一次保存快照深比较得出，只驱动守卫与按钮禁用。

## 6. 关键交互规则（与 PRD AC 对齐）

1. **图表维度规则（取代 r12）**：`dims = rows.length + columns.length`；`TABLE` 常驻；1 维 → 柱/折线/饼；2 维 → 堆叠柱（有列维时堆叠）/ 热力（需行维+列维，本地视图模式）；>2 维全部禁用并显示具体原因；切图**不重发请求**。
2. **pin 上限（取代 r12）**：409 `PORTAL_CARD_LIMIT` → 弹层用 `cardLimitCards(error)` 读 `detail.cards` 列当前 4 张（可打开），给「去工作台移除」+「本轮暂不支持移除，下轮支持移除」。
3. **共享五档**：`PRIVATE|ROLE|DOMAIN|VENDOR|PUBLIC`，UI 不出现 `TENANT`；顶部固定提示语；`ROLE` 为数字角色 ID（后端无角色字典，G-2）；403 只页内提示。
4. **导出**：默认 XLSX，可切 CSV；文件名 `report-<id>-<yyyyMMdd>.<ext>`；CSV 明示「对外请用 XLSX」；超限 400 页内提示且不丢编辑态。
5. **降级**：字典 403 → 下拉禁用 + 页内提示；单区失败只坏一区；整页 500 → 整页错误卡 + 重试。
6. **未保存守卫**：路由跳转与 `beforeunload` 双拦截；保存成功后解除。

## 7. 视觉基线（D8）

`docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md`：主色 `#2B6FE5`、白卡片 + 1px `#E5E6EB`、表格行高 44–48、状态用浅底方角 pill；颜色间距一律走 `web/src/styles/tokens.css` 变量；Element Plus 组件优先；echarts 6 已装、按需注册；不引入新依赖。

## 8. 承接 PRD §11 待澄清项的落地取值（采推荐默认值，不视为重开设计）

| 编号 | 落地取值 |
|---|---|
| B1 `/portal` 筛选作用半径 | `/portal` 整页按当前筛选**一次取数**，待办/快捷入口的口径由后端决定，前端不做二次过滤，也不额外标注「不随筛选变化」 |
| B2 `/reports` 默认 Tab | 「我的报表」（`segment=MINE`） |
| B3 ROLE 共享档 | 保留五档，`ROLE` 用数字角色 ID + 一行说明（无字典） |
| B4 无数据角色 | 保留区标题 + 一句话空态，维持五区顺序稳定 |

## 9. 交付边界

- 新增文件仅 `web/src/{components/analytics,components/portal,components/reports,composables,views/analytics,views/portal,views/reports}` 与 2 个复用文件的类型对齐；后端零 diff。
- 证据：`docs/teamflow/20261111-r13/evidence/*.png`（20 张 1440×900）+ `EVIDENCE.md`；截图脚本只在 `logs/teamflow/<runId>/scripts/`。
