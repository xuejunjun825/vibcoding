# r13 技术方案 · 分析域四页前端视图

> 对应 PRD：`docs/teamflow/20261111-r13/PRD.md`（20 条 AC） · 设计输入：`docs/teamflow/20261111-r13/DESIGN.md`（PRD §5 D1–D10 的落盘引用）
> 基线依赖：`docs/teamflow/20261110-r11-reports-portal-backend/`（后端接口与 wire 真源）、`docs/teamflow/20261110-r9-workbench-reports/`
> 取代：AC-13 图表维度规则、AC-15 pin 409 出口（相对 `docs/teamflow/20261110-r12-analytics-portal-views/`）
> 结论：**零后端改动、零新依赖、零库表变更**。四页 = 4 个 SFC + 10 个共享组件/纯函数 + 2 个复用文件的类型对齐；所有数字来自 r11 接口，所有筛选/防抖/URL 解析收口在 `FilterBar.vue` + `stores/analytics.ts`。

---

## 1. 现状、缺口与结论

| 层 | 现状（r9/r10/r11 已交付） | 本轮只做 |
|---|---|---|
| 路由 | `analytics.routes.ts` 已注册 4 条，`lazyView()` 找不到视图文件即 reject | 建 4 个视图文件，路由表**零改** |
| 导航 | `layouts/nav.ts:246-263` 三项已挂 `PORTAL_VIEW`/`REPORT_VIEW` | 零改 |
| SDK | `api/analytics.ts` 496 行，函数齐全，**类型与后端 wire 有 10 处漂移** | 只改类型/参数位置（§2），不加函数 |
| 状态 | `stores/analytics.ts` 已有 URL 同步/300ms 防抖/AbortController，**缺 `reqType`** | 加 `reqType` 一字段（§2.2） |
| 后端 | `/portal`、`/portal/config`、`/analytics/*`、`/reports/**`、`/reports/datasets` 全部就绪 | **零 diff**（缺口只记录 G-1..G-8） |
| 前端组件 | `components/analytics/` **目录不存在**；echarts 已装但全仓从未 import | 新建 10 个组件 + 1 个 composable + 1 个 css |
| 门禁 | a2 5 页 35 条 / a3 侧栏基线 33（实际 36，R1 已 FAIL） / 总数下限 115 | a2 +28=63、a3 33→36、总下限 143 |

**关键判断 1**：`TrendPoint`/`Breakdown`/`QualityIndex`/`CrossTab`/`useAnalyticsStore` 在 `web/src` 内**没有任何既有消费者**（`grep` 除 `api/analytics.ts`、`stores/analytics.ts` 外为 0 命中），因此类型对齐是纯增量修正，不会波及既有页面。

**关键判断 2**：后端 `chartType` 白名单只有 `TABLE|BAR|LINE|PIE`，没有 `STACKED`/`HEATMAP`。AC-13 的「堆叠柱/热力」必须映射为**客户端渲染模式**：堆叠柱 = `BAR + stacked`（可落库），热力 = 本地视图（不落库，记 G-8）。这既满足 AC-13，也不动后端。

---

## 2. M0：契约对齐（唯一允许改动的两个既有文件）

### 2.1 `web/src/api/analytics.ts` 类型修正（15 条，逐条对应 wire）

| # | 类型/函数 | 现状 | 改为 | 依据 |
|---|---|---|---|---|
| 1 | `AnalyticsQuery` | 无 `reqType` | `reqType?: RequirementType \| null` | `AnalyticsController` metrics/dashboard 的 `@RequestParam reqType` |
| 2 | `Breakdown` | `{key,title,rowHeaders,rows,total,truncated}` | `{key:string; label:string; colHeaders:string[]; rows:CrossRow[]; subtotal:CrossRow; total:CrossRow}` | `AnalyticsViews.Breakdown(key,label,colHeaders,rows,subtotal,total)` |
| 3 | `TrendPoint` | `period` | `bucket:string`（保留 `current/previous/delta`） | `TrendPoint(String bucket,Long,Long,Double)` |
| 4 | `Trend` | `{bucket,unit,points}` | `{unit:string; points:TrendPoint[]}` | `Trend(String unit,List<TrendPoint>)` |
| 5 | `Segment` | `{key,label,count,share,avgDays,p90Days,longTail}` | `{key:string; label:string; count:number; avg:number\|null; p90:number\|null; tail:boolean}` | `Segment(key,label,long,Double,Double,boolean)` |
| 6 | `Effort` | 无 `unit` | `{estimate:number\|null; actual:number\|null; deviation:number\|null; missingEstimate:number; unit:string}` | `Effort(...,String unit)` |
| 7 | `VendorRankRow` | `done` / `avgCycleDays` | `completed:number` / `avgCycle:number\|null` | `VendorRank(...,completed,...,avgCycle,boolean mine)` |
| 8 | `Quality` | `{items: QualityIndex[]}`，`QualityIndex.threshold/hint` | `{cards: QualityCard[]}`，`QualityCard{key,label,value:number\|null,unit,digits,level,sample:number}`，删 `threshold/hint` | `Quality(List<QualityCard> cards)` |
| 9 | `QualityLevel` | `'GOOD'\|'WARN'\|'BAD'\|'UNKNOWN'` | `'GOOD'\|'WARN'\|'BAD'\|'NONE'` | 后端 level 取值 `NONE` |
| 10 | `CrossTab` | `groups?:number`、`colGroups?` | `groups:number`（删 `colGroups`） | `CrossTab(...,int groups,boolean truncated,...)` |
| 11 | `ReportScope` | `PRIVATE\|ROLE\|DOMAIN\|PUBLIC` | 追加 `'VENDOR'` | `ShareVisibility` 五档 |
| 12 | `ReportDetail` | `canWrite?` / `canShare?` | `canWrite:boolean; canShare:boolean` | `ReportDetail` record 必返 |
| 13 | `runReport(id, body?, signal?)` | 筛选塞进 body | `runReport(id: number, body?: {definition: ReportDefinition}, params?: AnalyticsQuery, signal?: AbortSignal): Promise<CrossTab>`，筛选走 query | `RunCmd` 无 `reqType` 字段 |
| 14 | `fetchPortalConfig()` | 无参 | `fetchPortalConfig(roleCode?: string)` → `?roleCode=` | `GET /portal/config?roleCode=` |
| 15 | `shareReport(id, body)` | 403 走全局跳 `/403` | 内部 `{ silentForbidden: true }` | AC-10「403 页内提示，不跳转」 |

保留不动：`listPortal`、`fetchMetrics`、`fetchDashboard`、`fetchDatasets`、`listReports`、`getReport`、`createReport`、`updateReport`、`copyReport`、`deleteReport`、`pinReport`、`exportReport`、`cardLimitCards`、`REPORT_ERROR_TEXT`、`Metric`/`Metrics`/`Portal*`/`ReportSummary`/`ReportCmd`/`ReportShareCmd`/`PinResult`/`ReportListQuery`/`Dataset*`/`CrossRow`。

> `requirement` 数据集字段：`fetchDatasets()` 返回 4 个 `Dataset`（`code/label/fields[{key,label,kind,measure}]`），字段池**只从响应渲染**，源码不出现 `req.total`/`cycle.p90` 之类的 key（AC-12 grep 断言）。

### 2.2 `web/src/stores/analytics.ts`：只加一字段

```ts
export interface AnalyticsFilter {
  domainId: number | null
  vendorId: number | null
  reqType: RequirementType | null   // ★ 新增
  from: string | null
  to: string | null
  preset: PresetKey
}
```

必须同步改的 5 处（`signature()` 漏改 = 请求不重发的隐性缺陷）：

1. `filterFromQuery()`：读 `query.reqType`（仅接受 `BUS|PDR|RD`，其它值丢弃）。
2. `filterToQuery()`：`reqType` 非空才写键（`buildQuery` 也会丢空值，双保险）。
3. `normalizeFilter()`：`reqType` 非白名单 → `null`。
4. `signature()`：拼串**加 `reqType`**（否则从 `RD` 切 `BUS` 不重发）。
5. `resetFilter()`：清 `reqType`（六键全清，保留 `foo` 等非筛选键）。

`applyToRoute()` 现有「保留非筛选键」逻辑不变（深链 `foo=1` 靠它）。

---

## 3. 数据模型与存储

**本轮不新增表、不改迁移、不改 `definition_json` 结构。** 前端侧只有三种「状态」：

| 状态 | 存放 | 生命周期 | 说明 |
|---|---|---|---|
| 筛选（六键） | URL query | 刷新/前进后退可恢复 | 唯一真相，`store.filter` 是它的投影 |
| 报表定义 | `report_definition.definition_json`（后端） | 持久 | 前端只在编辑器内持 `reactive` 草稿 + 保存快照用于 `dirty` |
| 页面本地 UI 态 | 组件 `ref` | 组件存活期 | 页码、转置、图表模式、弹层开关、抽屉角色选择 |

`ReportDefinition`（编辑器形态，与 `ReportDefinitionCodec` 的存盘形态一致）：

```ts
interface ReportDefinition {
  datasetId: string
  rows: string[]       // ≤3，DIM/TIME
  columns: string[]    // ≤2，DIM/TIME
  measures: string[]   // 1–3，MEASURE
  filters: ReportFilter[]  // ≤8，{field, op, value:string|string[]}
}
```

限额/算子（与后端常量逐一对齐，前端只做**前置拦截**，后端仍是权威）：

| 常量 | 值 | 后端来源 |
|---|---|---|
| 行 | 3 | `AnalyticsDatasets.MAX_ROWS` |
| 列 | 2 | `MAX_COLS` |
| 维度合计 | 4 | `MAX_DIMS` |
| 值 | 1–3 | `MAX_MEASURES` |
| 筛选 | 8 | `MAX_FILTERS` |
| 算子 | `= != > >= < <= IN` | `AnalyticsDatasets.OPERATORS` |
| 分组截断 | 5000 | `AnalyticsQueryService.MAX_GROUPS` → `truncated` |
| 导出行上限 | 10000 | `EXPORT_MAX_ROWS` → 400 `REPORT_EXPORT_LIMIT` |

---

## 4. API 设计（只消费，不改后端）

### 4.1 端点 → 前端函数 → 调用方

| 端点 | 参数位置 | SDK 函数 | 调用方 |
|---|---|---|---|
| `GET /api/deliver/portal` | query：六键 | `listPortal(params, signal)` | PortalView（整页一次） |
| `GET /api/deliver/portal/config` | query `roleCode?` | `fetchPortalConfig(roleCode?)` | CardConfigDrawer |
| `PUT /api/deliver/portal/config` | body `{roleCode,cardKeys[]}` | `savePortalConfig(body)` | CardConfigDrawer |
| `GET /api/deliver/analytics/metrics` | query `keys` + 六键 | `fetchMetrics(keys, params, signal)` | 证据对照（AC-4），页面不直接调 |
| `GET /api/deliver/analytics/dashboard` | query 六键 + `bucket=WEEK\|MONTH` | `fetchDashboard(params, signal)` | DashboardView |
| `GET /api/deliver/reports` | query `segment,keyword,page,size` | `listReports(query, signal)` | ReportListView |
| `GET /api/deliver/reports/datasets` | — | `fetchDatasets(signal)` | ReportEditorView |
| `GET /api/deliver/reports/{id}` | path | `getReport(id, signal)` | ReportEditorView |
| `POST /api/deliver/reports` | body `ReportCmd` | `createReport(body)` | 编辑器保存（201） |
| `PUT /api/deliver/reports/{id}` | body `ReportCmd` | `updateReport(id, body)` | 编辑器保存 |
| `POST /api/deliver/reports/{id}/copy` | — | `copyReport(id)` | 列表/编辑器「另存」 |
| `DELETE /api/deliver/reports/{id}` | — | `deleteReport(id)` | 列表删除（204） |
| `POST /api/deliver/reports/{id}/share` | body `{scope,scopeRef?}` | `shareReport(id, body)` | ShareDialog |
| `POST /api/deliver/reports/{id}/run` | **body 只放 `{definition?}`**；筛选与 `reqType` 在 query | `runReport(id, body?, params, signal)` | 编辑器预览 |
| `GET /api/deliver/reports/{id}/export` | query `format` + 六键 | `exportReport(id, params)` | 编辑器导出 |
| `POST /api/deliver/reports/{id}/pin` | — | `pinReport(id)` | 编辑器 pin |

### 4.2 错误码 → UI 落点

| code | status | UI |
|---|---|---|
| `REPORT_NOT_FOUND` | 404 | 编辑器/列表深链：「报表不存在或已被删除」+ 返回列表（不显示「无权限」） |
| `REPORT_NAME_DUPLICATED` | 409 | 名称输入框下行内红字，编辑内容不丢 |
| `PORTAL_CARD_LIMIT` | 409 | PinLimitDialog（`cardLimitCards(error)`） |
| `REPORT_SCOPE_FORBIDDEN` / 任意 403 | 403 | 页内提示，不跳 `/403`（`silentForbidden`） |
| `REPORT_DIM_LIMIT` / `MEASURE_LIMIT` / `FILTER_LIMIT` | 400 | 槽位旁提示上限（前端已前置拦截，此处兜底） |
| `REPORT_EXPORT_LIMIT` | 400 | 导出按钮下方提示上限 + 建议缩小筛选，编辑态不变 |
| `REPORT_GROUP_LIMIT` / `truncated=true` | 200 | 表格上方「结果超过 5000 行，仅展示前 5000 行」 |

文案统一走 `REPORT_ERROR_TEXT[code] ?? error.message`，未知码回落原文。

---

## 5. 文件结构与模块契约

```
web/src/
├── api/analytics.ts                     ✎ 只改类型/参数位置（§2.1）
├── stores/analytics.ts                  ✎ 只加 reqType（§2.2）
├── composables/useAnalyticsFeed.ts      ★ 取数原语（防抖+abort+过期丢弃）
├── components/analytics/
│   ├── FilterBar.vue                    ★ 全仓唯一筛选控件
│   ├── DataStamp.vue                    ★ dataAsOf + HIT/MISS
│   ├── CrossTable.vue                   ★ 交叉表（sticky/分页/转置/截断）
│   ├── ChartView.vue                    ★ echarts 按需注册
│   ├── chartModel.ts                    ★ 交叉表 → 图表数据（纯函数）
│   ├── ShareDialog.vue                  ★ 五档共享弹层
│   ├── PinLimitDialog.vue               ★ 409 出口
│   └── portalRoles.ts                   ★ 10 角色 code+中文名（镜像后端模板）
├── components/portal/
│   ├── PortalSection.vue                ★ 单区渲染 + 区内降级
│   └── CardConfigDrawer.vue             ★ AC-20 卡片配置
├── components/reports/
│   ├── SlotBoard.vue                    ★ 字段池 + 四槽 + DnD
│   └── limits.ts                        ★ 限额/算子/投放合法性（纯函数）
└── views/
    ├── portal/PortalView.vue            ★
    ├── analytics/DashboardView.vue      ★
    ├── analytics/shared.css             ★ 四页共享结构类（.an-*）
    └── reports/{ReportListView,ReportEditorView}.vue  ★
```

### 5.1 公开签名（dev 必须逐字调用，改名 = 首个调用即崩）

```ts
// composables/useAnalyticsFeed.ts
export type FeedStatus = 'idle' | 'loading' | 'ready' | 'error'
export interface AnalyticsFeed<T> {
  data: Ref<T | null>
  status: Ref<FeedStatus>
  error: Ref<ApiError | null>
  load: (opts?: { debounce?: boolean }) => void   // debounce 默认 true（300ms）
  reload: () => void                              // 立即重取（区内「重试」）
}
export function useAnalyticsFeed<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  opts?: { immediate?: boolean }
): AnalyticsFeed<T>
// 行为：watch(useAnalyticsStore().query) → load()；每次请求新 AbortController，
//       发新请求前 abort 旧请求；seq 计数器丢弃过期响应；onScopeDispose abort。

// components/analytics/chartModel.ts
export type ChartMode = 'BAR' | 'LINE' | 'PIE' | 'HEATMAP'
export interface ChartSeries { name: string; data: (number | null)[] }
export interface ChartModel {
  mode: ChartMode
  categories: string[]
  series: ChartSeries[]
  heat: { x: string[]; y: string[]; cells: [number, number, number][] }
  unit: string
}
export interface ChartPlan { mode: ChartMode; stacked: boolean; disabled: boolean; reason: string | null }
export function chartPlanFor(chartType: ChartType, rowDims: number, colDims: number): ChartPlan
export function buildChartModel(tab: Pick<CrossTab, 'rowHeaders' | 'colHeaders' | 'rows' | 'total'>, plan: ChartPlan): ChartModel

// components/reports/limits.ts
export const REPORT_LIMITS = { rows: 3, columns: 2, dims: 4, measures: 3, filters: 8 } as const
export const FILTER_OPS: ReadonlyArray<{ value: ReportFilterOp; label: string }>
export type SlotKey = 'rows' | 'columns' | 'measures' | 'filters'
export function slotAccepts(slot: SlotKey, kind: FieldKind): boolean
export function addField(def: ReportDefinition, slot: SlotKey, field: DatasetField): { def: ReportDefinition; ok: boolean; reason?: string }
export function removeField(def: ReportDefinition, slot: SlotKey, key: string): ReportDefinition
export function normalizeFilterValue(op: ReportFilterOp, raw: string): string | string[]

// components/analytics/portalRoles.ts
export const PORTAL_ROLES: ReadonlyArray<{ code: string; label: string }>
export function portalRoleLabel(code: string): string
```

组件契约（props / emits）：

| 组件 | props | emits |
|---|---|---|
| `FilterBar.vue` | `fields?: Array<'domain'\|'vendor'\|'preset'\|'reqType'>`（默认全部）、`dense?: boolean` | 无（直接写路由） |
| `DataStamp.vue` | `dataAsOf?: string\|null`、`cache?: CacheState\|null`、`loading?: boolean` | — |
| `CrossTable.vue` | `tab: CrossTab`、`transpose?: boolean`、`pageSize?: number`（默认 100）、`dense?: boolean` | 无 |
| `ChartView.vue` | `model: ChartModel`、`stacked?: boolean`、`height?: number`（默认 280） | — |
| `ShareDialog.vue` | `modelValue: boolean`、`report: ReportDetail\|null` | `update:modelValue`、`saved(scope: ReportScope)` |
| `PinLimitDialog.vue` | `modelValue: boolean`、`cards: PortalCardRef[]` | `update:modelValue` |
| `PortalSection.vue` | `section: PortalSection`、`todoMode: TodoMode` | `retry` |
| `CardConfigDrawer.vue` | `modelValue: boolean` | `update:modelValue`、`saved` |
| `SlotBoard.vue` | `modelValue: ReportDefinition`、`dataset: Dataset\|null` | `update:modelValue` |

> 命名冲突提醒：`PortalSection` 既是 SDK 类型名也是组件文件名。`PortalView.vue` 里 `import PortalSection from '@/components/portal/PortalSection.vue'` 与 `import type { PortalSection as PortalSectionData }` 必须**重命名类型别名**，否则 Vue SFC 编译报重复标识符。

### 5.2 组装顺序

```
FilterBar/DataStamp ─┐
CrossTable/ChartView ─┼→ 四个 View（互不 import）→ app 路由 lazyView
ShareDialog/PinLimit ─┘
useAnalyticsFeed ─────→ 三个取数页（portal/dashboard/editor）
```

四页之间无 import 关系，只共享 `components/**` 与 `views/analytics/shared.css`。

---

## 6. 页面实现要点

### 6.1 `/portal`

- `const feed = useAnalyticsFeed<Portal>((s) => listPortal(store.query, s), { immediate: true })`；筛选变更由 feed 内部 watch 触发。
- 五区渲染：**直接用 `portal.sections` 的数组顺序**，`v-for` + `data-testid="portal-section-' + section.key"`；不排序、不按 key 映射模板（AC-3 grep 断言「无 `template ===` 分支」）。
- 只读态：`portal.todoMode === 'READONLY'` → 头部「只读」pill；`PortalSection` 内对 `key==='actions'` 直接不渲染（AC-3）。注意这是**按区 key 隐藏**，不是按角色映射模板，不算违反 D1。
- 卡片：`kind==='TEMPLATE'` → `MetricCard` 网格（`label/value/unit/digits/definition=caliber`），点击走 `card.link`；`kind==='PERSONAL'` → 卡片标题 + `card.metrics`（后端已用同一 `crossTab` 算好，**不二次 run**），点击 `router.push('/reports/'+card.reportId)`。
- 整页失败：`status==='error'` → `ErrorState` + 「重试」（`feed.reload()`，只发一次）。

### 6.2 `/dashboard`

- `useAnalyticsFeed<Dashboard>((s) => fetchDashboard({ ...store.query, bucket: bucket.value }, s))`；`bucket` 本地 `ref<'WEEK'|'MONTH'>('WEEK')`，watch 后 `reload()`（不进 URL，PRD 未要求）。
- 五块顺序写死在模板（标题固定），数据为空时**保留标题 + 空态**，不隐藏区块（AC-6）。
- 需求量多维：`breakdowns[0]` → `CrossTable`（列头 + 行 + total）；环比用 `total.delta` 或 `Metric` 语义原样展示，不二次计算。
- 周期与 P90：`segments` 列表（`count/avg/p90/tail`），`p90===null` → 「—」。
- 人天：`effort.estimate/actual/deviation/missingEstimate`，`deviation===null` → 「—」。
- 排名：`vendorRank` 按 `rank` 升序（后端已排）渲染，`mine===true` 行加 `.an-rank__mine`；`completed`/`avgCycle` 直出。
- 质量：`quality.cards`，`level==='NONE'` → 「无样本」灰 pill（不是「正常」）。
- 趋势：`trend.points` 用 `ChartView`（`mode='LINE'`，`categories=points.map(p=>p.bucket)`，两条系列 current/previous）。
- 「在报表中打开」：`router.push({ path:'/reports/new', query:{ dataset:'requirement', ...filterToQuery(store.filter) } })`。

### 6.3 `/reports` 列表

- `useListQuery<ReportSummary>((q) => listReports({ segment: q.filters.segment as 'MINE'|'SHARED', keyword: q.filters.keyword, page: q.page, size: q.size }, signalOpt), { segment:'MINE' })`。`segment` 既是 URL 参数也是 Tab 状态，交给 `useListQuery` 的 `filters` 同步（不自己 `router.replace`）。
- 操作列按权限**预判**：
  - 打开：总是；
  - 复制：总是（`copyReport` → toast + `refresh()`）；
  - 共享：`row.ownerId === auth.user?.id && can('REPORT_SHARE')`；
  - 删除：`row.ownerId === auth.user?.id`（`canWrite` 语义在列表不可得，属主即写权，与后端 `canWrite = owner` 一致）。
- 删除：`ConfirmDialog` 二次确认 → `deleteReport` → `refresh()`；失败 toast 原文。
- 空态：`isEmpty && !isFiltered` → 「还没有报表」+「新建报表」；`isEmpty && isFiltered && segment==='SHARED'` → 「还没有人共享给你」；`isFiltered` → 「没有匹配的报表」+「清空筛选」。
- 共享范围标签：`REPORT_SCOPE_LABEL[scope]`（五档中文），PRIVATE 显示「私有」。

### 6.4 `/reports/:id` 编辑器

- 单组件承载 `new` 与 `id`：`watch(() => route.params.id, load, { immediate: true })`；**新建保存后 `router.replace` 不会重建组件**，所以 `load()` 必须能被再次触发（这是本轮最易漏的坑）。
- `load()`：`id==='new'` → `fetchDatasets()` + 从 `route.query.dataset` 初始化（默认 `requirement`）；否则 `getReport(id)` → 名称/定义/图表类型/`canWrite`/`canShare`，成功后 `run()` 一次。
- 字段池：`datasets.find(d => d.code === def.datasetId)`；切数据集 → 四槽清空 + `ElMessageBox` 确认（脏时）。
- 拖拽：字段 `draggable` + `dragstart` 写 `dataTransfer.setData('text/plain', key)`；槽 `dragover.prevent` + `drop` → `addField()`；双击直接进默认槽（度量→measures，维度→rows）。`addField` 返回 `reason` 时 `ElMessage.warning(reason)` 且不改状态。
- 预览：`run()` 需要 `id` 存在；不存在 → 预览区 `EmptyState`「先保存以生成报表」且按钮禁用（AC-13）。`watch(def, debounce 300)` → 有 id 时 `run()`（body 传当前草稿 `{definition}`）。
- 转置：`transpose` 是 `CrossTable` 的 prop，纯前端，不重发（AC-13）。
- 保存：`id==='new'` → `createReport` → `router.replace('/reports/'+r.id)` → `dirty=false` → `run()`；否则 `updateReport`。409 `REPORT_NAME_DUPLICATED` → 名称行内错误。
- 另存：`copyReport(id)` → `router.replace('/reports/'+copy.id)` → 重新 `load()`。
- 导出：`exportReport(id, { ...store.query, format })`；`format==='csv'` 时按钮下提示「对外请用 XLSX」；400 `REPORT_EXPORT_LIMIT` 页内提示。
- pin：`pinReport(id)` → 409 且 `error.code==='PORTAL_CARD_LIMIT'` → `PinLimitDialog` 打开；按钮 `loading` 期间禁用，防重复。
- 共享：`canShare` 才渲染入口 → `ShareDialog`。
- 未保存守卫：`onBeforeRouteLeave` → 脏则 `ConfirmDialog`（await 用户选择，`return false` 阻断）；`beforeunload` 脏时 `preventDefault()`。
- 404：`getReport` 抛 `REPORT_NOT_FOUND` → 整页 `EmptyState`「报表不存在或已被删除」+「返回列表」。

### 6.5 图表规则（`chartPlanFor`，取代 r12）

`dims = rowDims + colDims`；`chartType` 落库值只允许 `TABLE|BAR|LINE|PIE`。

| dims | TABLE | BAR | LINE | PIE | 热力（本地） |
|---|---|---|---|---|---|
| 0 | ✓ | 禁用「请先放入至少 1 个维度」 | 禁用同左 | 禁用同左 | 禁用 |
| 1 | ✓ | ✓ 柱 | ✓ 折线 | ✓ 饼 | 禁用「热力需要行维 + 列维」 |
| 2 | ✓ | ✓ 堆叠柱（有列维才 `stacked=true`） | 禁用「折线仅支持 1 个维度」 | 禁用「饼图仅支持 1 个维度」 | 仅当 `colDims>=1` 可用（不落库，G-8） |
| ≥3 | ✓ | 禁用「N 个维度无法选择图表类型，请减少到 2 个以内」 | 禁用同左 | 禁用同左 | 禁用同左 |

- `ChartView` 按需注册：`echarts/core` + `BarChart, LineChart, PieChart, HeatmapChart` + `GridComponent, TooltipComponent, LegendComponent, VisualMapComponent` + `CanvasRenderer`，`onMounted` init、`watch(model, setOption)`、`ResizeObserver`、`onBeforeUnmount dispose`。
- **切图不重发请求**：model 由 `tab` + `plan` 计算，纯前端。
- 单位：`ChartModel.unit` 来自 `tab` 所属度量的 `unit`（编辑器从 `dataset.fields` 反查）；查不到留空。

### 6.6 宽表与大结果（AC-13）

- `CrossTable` 外层 `.an-table__scroll{overflow:auto; max-height:62vh}`；`<table>` + `thead th{position:sticky;top:0;z-index:3;background:var(--bg-table-head)}`；首列 `th/td:first-child{position:sticky;left:0;z-index:2;background:var(--bg-card)}`，表头首格 `z-index:4`。
- 动态分页：`pageSize = clamp(floor(2800 / (colCount + 1)), 20, 100)`，保证 `(pageSize+1)*(colCount+1) ≤ 3000`（AC-13 DOM 单元格上限），页脚显示「共 N 行 · 第 x/y 页」。
- `truncated===true` → 表格上方告警条「结果超过 5000 行，仅展示前 5000 行」。
- `subtotal`/`total` 行：`tfoot` 内两行，`.an-table__sum` 加粗 + 背景 `--bg-table-head`。

---

## 7. 状态管理与并发/竞态

| 场景 | 处理 |
|---|---|
| 300ms 内连点 3 次筛选 | store 写入 URL 三次，`useAnalyticsFeed` 只 debounce 一次 + 请求中不叠加 → ≤2 次请求（AC-7） |
| 旧响应覆盖新状态 | `useAnalyticsFeed` 内 `seq`；回调前 `if (seq !== current) return` |
| 切页/卸载 | `onScopeDispose(() => { abort(); })`；store 侧不持有请求状态 |
| 前进/后退 | store `syncFromQuery` 由 `router.afterEach` 或视图 `watch(route.query)` 触发（沿用现有 store 行为），feed 随之重取 |
| 重置 | `store.resetFilter(router)` 只删六键，保留 `foo` |
| 大表渲染 | `v-memo` 不必要；靠分页把 DOM 控在 ≤3000 单元格 |
| 双击提交 | 所有异步按钮 `loading` 置位（保存/导出/pin/共享/删除） |

---

## 8. 边界与异常（逐条可验证）

1. `p90/avg/deviation/value/count === null` → 「—」+ `title` 说明，**绝不补 0**（`formatNumber` 已保证）。
2. `dataAsOf` 为空 → 「数据时间未知」，不显示 `Invalid Date`。
3. 字典 403 → 域/厂商下拉禁用 + 页内「字典不可用，已降级」；筛选条其余部分可用（AC-19）。
4. 厂商不属于当前域 → 选域时自动清 `vendorId`（一次 `applyToRoute` 同时写两键，避免两次请求）。
5. 自定义时间窗 `from > to` 或跨度 > 366 天 → 前端拦截提示，不写 URL。
6. `preset !== 'custom'` → 隐藏日期选择器，但 URL 仍带 `from/to`（由 `normalizeFilter` 计算）。
7. 报表无 `definition`（旧数据）→ 编辑器显示空槽 + 「该报表未保存字段，请重新配置」。
8. `canWrite===false`（他人共享报表）→ 保存/另存为新建按钮禁用，只读预览 + 复制入口。
9. 图表无数据（`rows.length===0`）→ 不初始化 echarts，显示 `EmptyState`（避免空 canvas 报错）。
10. `/reports/:id` 非法 id（如 `/reports/abc`）由路由正则挡在 `new|\\d+`，落到 404 路由。
11. 导出进行中再次点击 → 按钮 loading 挡住。
12. 控制台零错误（AC-16 断言项）：禁止 `console.log` 留在交付代码。

---

## 9. 样式（`web/src/views/analytics/shared.css` + SFC scoped）

只放跨页结构类，颜色间距全部走 `web/src/styles/tokens.css`：

| 类 | 用途 |
|---|---|
| `.an-page` | 页面纵向容器（`display:flex;flex-direction:column;gap:12px`） |
| `.an-head` / `.an-head__title` / `.an-head__actions` | 页头（沿用 `deliver/shared.css` 的 `.dl-*` 结构范式） |
| `.an-grid` / `.an-grid--kpi` | 卡片网格（`repeat(auto-fill,minmax(220px,1fr))`） |
| `.an-card` / `.an-card__title` / `.an-card__body` | 白卡片 + 1px `--border` + `--r-ctl` |
| `.an-table__scroll` / `.an-table__sum` / `.an-table__empty` | 交叉表容器/合计行/空态 |
| `.an-hint` / `.an-hint--warn` | 页内提示/告警条 |
| `.an-3col` | 编辑器三栏（`grid-template-columns:260px minmax(420px,1fr) minmax(480px,1.2fr)`，1440 下不折叠） |
| `.an-slot` / `.an-slot--over` / `.an-chip` | 槽位/拖入高亮/字段 chip |

`/portal`、`/reports` 直接 `@import '@/views/analytics/shared.css'`（路径沿用 analytics 域目录，避免为三个目录各建一份）。

---

## 10. 门禁与证据

| 门禁 | 命令 | 期望 |
|---|---|---|
| 类型 | `cd web && npx vue-tsc --noEmit` | 0 error |
| 构建 | `cd web && npm run build` | 0 error（`vue-tsc` 内含于 `build`） |
| lint | `make lint` / `bash scripts/tests/lint.sh` | 16/16 |
| access | `make access-verify` | `PASS==TOTAL==143`（a2 63 / a3 9 段全绿） |
| 契约 | `curl` 六份响应落 `evidence/*.json` + `jq` 字段名断言 | 与 §2.1 表逐条一致 |
| 截图 | `node logs/teamflow/<runId>/scripts/shots.mjs`（无依赖，headless Chrome + CDP） | 20 张 1440×900 PNG，控制台零错误 |

截图脚本机制（无新依赖）：Node 22 内置 `WebSocket` 连 `--remote-debugging-port`，`Page.navigate` 前 `Page.addScriptToEvaluateOnNewDocument` 注入 `sessionStorage['devops.session']`（token 由 `POST /api/req/auth/login` 现取），`Emulation.setDeviceMetricsOverride` 设 1440×900，`Runtime.evaluate` 取 `document.title` 与 `console` 错误计数，`Page.captureScreenshot` 落盘。负向场景用 `Fetch.enable` 改写 `/api/deliver/portal` 响应（`portal-degraded.png`）或构造 5 张 pin 触发 409（`editor-409.png`）。

---

## 11. 任务拆分（按文件边界，4 波）

并行前提：`files` 互斥即可同波；`reads` 不阻塞。**所有任务都在 `feat/analytics-portal-views` 分支上做，不新开分支、不 rebase、不 `git clean`。**

| # | 任务 | files（拥有） | reads | 依赖 | 覆盖 AC |
|---|---|---|---|---|---|
| **T1** | 契约与状态地基：SDK 类型对齐 + store 加 `reqType` | `web/src/api/analytics.ts`、`web/src/stores/analytics.ts` | `deliver-service/.../AnalyticsViews.java`、`ReportViews.java` | — | AC-1/6/7/8/13 地基 |
| **T9** | access 门禁同步（与 T1 同波，脚本独立） | `scripts/tests/access/a2-page-matrix.sh`、`scripts/tests/access/a3-nav-audit.mjs`、`scripts/access-verify.sh` | `web/src/layouts/nav.ts` | — | AC-17 |
| **T2** | 取数原语与筛选条 | `web/src/composables/useAnalyticsFeed.ts`、`web/src/components/analytics/FilterBar.vue`、`DataStamp.vue` | `stores/analytics.ts`、`dictionaries.ts`、`views/req/platform.ts`（REQUIREMENT_TYPES） | T1 | AC-7/19 |
| **T3** | 交叉表 / 图表 / 共享样式 | `components/analytics/CrossTable.vue`、`ChartView.vue`、`chartModel.ts`、`views/analytics/shared.css` | `tokens.css`、`utils/format.ts` | T1 | AC-13 |
| **T4** | 弹层与角色常量 | `components/analytics/ShareDialog.vue`、`PinLimitDialog.vue`、`portalRoles.ts` | `api/analytics.ts`、`dictionaries.ts`、`usePermission.ts` | T1 | AC-10/15/20 |
| **T5** | `/portal` 页 | `views/portal/PortalView.vue`、`components/portal/PortalSection.vue`、`CardConfigDrawer.vue` | T2/T3/T4 产物 | T2,T3,T4 | AC-3/4/5/20 |
| **T6** | `/dashboard` 页 | `views/analytics/DashboardView.vue` | T2/T3 产物 | T2,T3 | AC-6/8 |
| **T7** | `/reports` 列表 | `views/reports/ReportListView.vue` | T2(`DataStamp` 不需要)、T3、T4、`useListQuery.ts` | T3,T4 | AC-9 |
| **T8** | 编辑器 | `views/reports/ReportEditorView.vue`、`components/reports/SlotBoard.vue`、`limits.ts` | T2/T3/T4 产物、`ReportDefinition` | T2,T3,T4 | AC-11/12/13/14/15 |
| **T10** | 交付自检 + 证据（唯一收尾任务） | `docs/teamflow/20261111-r13/EVIDENCE.md`、`evidence/*.png`、`logs/teamflow/<runId>/scripts/shots.mjs` | 全部 | T5–T9 | AC-16/18/19 + 收尾提交 |

**波次**：W1 = T1 + T9（并行）→ W2 = T2/T3/T4（并行）→ W3 = T5/T6/T7/T8（并行）→ W4 = T10（串行）。
T10 的 `files` 与其他任务完全互斥，但它**必须在 W3 全绿后**才跑（截图依赖页面）。

### 11.1 git 收尾（PRD §12 约束 1/11，落在 T10 的 spec 里）

```bash
# 只此一次提交，只纳入 AC-1 白名单
git add web/src/api/analytics.ts web/src/stores/analytics.ts \
        web/src/components/analytics web/src/components/portal web/src/components/reports \
        web/src/composables/useAnalyticsFeed.ts \
        web/src/views/portal web/src/views/analytics web/src/views/reports \
        scripts/tests/access/a2-page-matrix.sh scripts/tests/access/a3-nav-audit.mjs scripts/access-verify.sh \
        docs/teamflow/20261111-r13
git commit -m "feat(analytics): r13 分析域四页前端视图（portal/dashboard/reports/editor）"
```

禁止纳入：`logs/`（含截图脚本）、`*.xlsx`/`*.csv` 导出件、`target/`、`.nacos/`、临时截图。提交前 `git status --porcelain` 必须只剩被忽略项。

---

## 12. 测试策略

| 层 | 手段 | 断言 |
|---|---|---|
| 类型/构建 | `vue-tsc --noEmit`、`npm run build` | 0 error；无 `@ts-ignore`、无 `: any`（`grep -rn "@ts-ignore" web/src/views web/src/components/analytics` 为空） |
| 契约（防类型再漂移） | `curl` 固化 `/portal`、`/analytics/dashboard`、`/reports/datasets`、`/reports/{id}/run`、`/portal/config`、`/reports?page=1&size=5` → `evidence/*.json`，用 `jq -r 'paths(scalars)|join(".")'` 出字段名集合，与 §2.1 表比对 | 10 处漂移项全部命中新名；出现未登记字段 → 记 G-8 |
| 门禁 | `make access-verify` | `PASS==TOTAL==143`；a2 63 全 PASS；a3 R1 通过 |
| 页面 | CDP 截图 20 张 + `Runtime.evaluate` 取 `console` 错误 | 20/20 存在、非空、1440×900；控制台错误 0 |
| 负向 | ① `Fetch` 改写 portal 待办区 `state=DEGRADED` ② 连续 pin 5 张触发 409 ③ `/reports/{他人私有 id}` ④ 字典 403 ⑤ 3 维度选图表 ⑥ 行 4 字段超限 | ①其余四区仍在 ②弹层列 4 张 ③「报表不存在」 ④下拉禁用不跳 403 ⑤控件禁用+原因 ⑥提示上限且不发请求 |
| 交互手工 | 拖拽入槽/拖回、转置、100 列 sticky 滚动、5000 行 DOM 计数 | `document.scrollingElement.scrollWidth <= innerWidth`；表格单元格 ≤3000；`getComputedStyle(td).position === 'sticky'` |
| 回归 | `/requirements`、`/workbench`、`/admin/permissions` 打开 | 无全局样式/路由副作用；后端 `git diff --stat deliver-service req-service` 为空 |

复用的既有验证脚本（不重建）：`scripts/access-verify.sh`、`scripts/tests/access/a2-page-matrix.sh`、`scripts/tests/access/a3-nav-audit.mjs`、`scripts/tests/lint.sh`、`scripts/dev-up.sh`。

---

## 13. 风险与遗留（G 系列，只记录不改后端）

| 编号 | 内容 | 处置 |
|---|---|---|
| G-1 | 无 unpin 接口，pin 满 4 张无法自服务移除 | 弹层文案「下轮支持移除」+「去工作台移除」 |
| G-2 | 无角色 id 字典 | `ROLE` 档用数字输入 + `portalRoles.ts` 提供 code/中文名（仅用于 AC-20 抽屉与展示） |
| G-3 | `chartType` 白名单无 `STACKED`/`HEATMAP` | 堆叠柱=BAR+stacked；热力=本地视图（G-8） |
| G-4 | `/reports` 列表不返回 `canWrite/canShare` | 用 `ownerId` 与权限码预判（与后端 `canWrite=owner` 一致） |
| G-5 | 范围筛选（preset/from/to/reqType）不持久化 | 编辑器顶部 chips 明示「范围条件不随报表保存」 |
| G-6 | `download()` 读不到 `Content-Disposition` | 用 SDK 兜底文件名 `report-<id>-<yyyyMMdd>.<ext>`（AC-11 已接受） |
| G-7 | `X-Report-Cache` 不放 `Access-Control-Expose-Headers` | 缓存态读 body 的 `cache` 字段，不读响应头 |
| G-8 | 热力模式无法落库 | UI 提示「热力为视图模式，保存后按表格打开」；下一轮若后端加 `HEATMAP` 再升级 |

风险（对应 PRD §9）：T1 类型改错会让全部页面报错 → T1 单独提交前先 `vue-tsc`；T10 截图依赖真实栈与 4 个账号 → 若栈不可用，T10 必须报告「环境不可用」并保留已完成的静态门禁证据，不得伪造截图。
