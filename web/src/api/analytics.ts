import { download, http } from './client'
import type { PageResponse } from './types'

/**
 * 分析域客户端（T6 · TECHNICAL §3–§7）：岗位工作台 / 数据看板 / 自助报表。
 *
 * 三条硬约束，改动前先读：
 * 1. **前端不做指标运算**（DESIGN §9）：比率、P90、环比 delta 全部由服务端
 *    `AnalyticsMetrics` 算好；这里只搬运 `Metric.value/prev/delta`。
 * 2. **可见性只在服务端**：本文件不拼 scope 参数之外的任何过滤；`domainId/vendorId`
 *    是用户主动选的筛选，行级隔离仍由后端 `ScopedJdbc` 收口。
 * 3. 响应里的 `cache`（HIT/MISS）优先于响应头 `X-Report-Cache`：`request()` 只回
 *    body、读不到头，所以三处缓存态一律取 body 字段（后端两个都发）。
 *
 * 类型即前后端契约：后端 `AnalyticsViews` / `ReportViews` 的 record 字段名以本文件
 * 为准（T2–T5 并行落地时按此对齐；漂移会在 QA 的 r2/r7 对拍里暴露）。
 */

const DELIVER = '/api/deliver'

/** 时间预设：7/30/90 天，或自定义区间（`preset=custom` + from/to） */
export type PresetKey = '7d' | '30d' | '90d' | 'custom'

/** 缓存态：服务端 300s TTL 的结果标记 */
export type CacheState = 'HIT' | 'MISS'

/** 三页共享的筛选（同时也是 URL query 的唯一 schema） */
export interface AnalyticsQuery {
  domainId?: number | null
  vendorId?: number | null
  /** YYYY-MM-DD */
  from?: string
  /** YYYY-MM-DD */
  to?: string
  preset?: PresetKey
}

/* ------------------------------------------------------------------ 指标 */

/**
 * 单个指标读数。`value/prev` 的单位由 `unit` 决定，小数位由 `digits` 决定；
 * `delta` 是**服务端算好的环比变化率**（0.021 = +2.1%），`null` 表示无基期
 * （前端画断点，不补 0）。
 */
export interface Metric {
  key: string
  label: string
  unit: string
  digits: number
  value: number | null
  prev: number | null
  delta: number | null
  /** 口径说明（卡片 tooltip 原样展示） */
  caliber: string
  /** 数据截至（服务端时钟） */
  asOf: string
}

export interface Metrics {
  dataAsOf: string
  cache: CacheState | string
  items: Metric[]
}

/* ---------------------------------------------------------------- 数据集 */

export type FieldKind = 'DIM' | 'TIME' | 'MEASURE'

export interface DatasetField {
  key: string
  label: string
  kind: FieldKind
  /** kind=MEASURE 的等价标记（后端两个都发，前端任取其一） */
  measure: boolean
}

export interface Dataset {
  code: string
  label: string
  fields: DatasetField[]
}

/* ------------------------------------------------------------------ 交叉表 */

/**
 * 一张数据行。`values` 与 `colHeaders` 等长（空格为 `null`，渲染成「—」）；
 * `counts` 是同格样本数（P90/均值类度量的样本量，表格 tooltip 用）。
 */
export interface CrossRow {
  key: string
  values: Array<number | null>
  counts: Array<number | null>
}

/**
 * 交叉表响应（页面 `maxRows=5000`、导出 `maxRows=10000` 都由服务端定）。
 * 表头是**叶子表头**（长度 = values 长度）；有列维时 `colGroups` 给第一层分组跨度，
 * 缺省时前端按「无列维」渲染单层表头。
 */
export interface CrossTab {
  rowHeaders: string[]
  colHeaders: string[]
  rows: CrossRow[]
  subtotal: CrossRow | null
  total: CrossRow | null
  /** 分组数是否被截断（截断提示「已截断至 5,000」） */
  truncated: boolean
  /** 截断前的分组总数（工具条「128 组」） */
  groups?: number
  /** 列维的两层表头：label + 该组占几列 */
  colGroups?: Array<{ label: string; span: number }>
  dataAsOf: string
  cache: CacheState | string
}

/* ------------------------------------------------------------------ 看板 */

/** 需求量多维：行维值 × 列维值 的一张矩阵 */
export interface Breakdown {
  key: string
  title: string
  rowHeaders: string[]
  colHeaders: string[]
  rows: CrossRow[]
  total: CrossRow | null
  truncated: boolean
}

export interface TrendPoint {
  /** 周期标签（如 2026-W45 / 2026-11） */
  period: string
  current: number | null
  previous: number | null
  /** 环比变化率（0.021 = +2.1%），服务端算 */
  delta: number | null
}

export interface Trend {
  bucket: 'WEEK' | 'MONTH'
  unit: string
  points: TrendPoint[]
}

/** 周期分段（5 段：<=3 / 4-7 / 8-14 / 15-30 / >30 天） */
export interface Segment {
  key: string
  label: string
  count: number
  /** 占全部完成需求的比例（0..1） */
  share: number
  avgDays: number | null
  p90Days: number | null
  /** 长尾段（>30 天）标记，仅渲染用 */
  longTail: boolean
}

/** 人天三列：预估 / 实际 / 偏差；`missingEstimate` 是无预估的需求数 */
export interface Effort {
  estimate: number | null
  actual: number | null
  deviation: number | null
  missingEstimate: number
}

export interface VendorRankRow {
  rank: number
  vendorId: number
  vendorName: string
  /** 当前账号所属厂商：置顶展示但名次不变 */
  mine: boolean
  done: number
  /** 0..1 */
  onTimeRate: number | null
  avgCycleDays: number | null
}

export type QualityLevel = 'GOOD' | 'WARN' | 'BAD' | 'UNKNOWN'

/** 交付质量卡：阈值与等级都由服务端给，前端不写死阈值 */
export interface QualityIndex {
  key: string
  label: string
  unit: string
  digits: number
  value: number | null
  /** 人读阈值（如「<= 5%」），展示用 */
  threshold: string
  level: QualityLevel
  sample: number | null
  hint?: string | null
}

export interface Quality {
  items: QualityIndex[]
}

export interface Dashboard {
  breakdowns: Breakdown[]
  trend: Trend
  segments: Segment[]
  effort: Effort
  vendorRank: VendorRankRow[]
  quality: Quality
  dataAsOf: string
  cache: CacheState | string
}

/* ---------------------------------------------------------------- 工作台 */

export type TodoMode = 'FULL' | 'READONLY' | 'FALLBACK'

export type SectionState = 'OK' | 'DEGRADED'

/** 工作台卡片：模板卡（key=模板常量）与个人卡（key=`report:<id>`）同池 */
export interface PortalCard {
  key: string
  title: string
  kind: 'TEMPLATE' | 'PERSONAL'
  /** 卡片上的读数（1–2 个 Metric；数值与单位全部来自服务端） */
  metrics: Metric[]
  link?: string | null
  hint?: string | null
  /** 个人卡对应的报表 id（模板卡为 null） */
  reportId?: number | null
}

export interface PortalSection {
  key: string
  title: string
  state: SectionState
  /** 该区降级时的可读原因（单块失败只置 DEGRADED，不整页空） */
  error?: string | null
  cards: PortalCard[]
}

/** 待办 tile：`count=null` 表示该源失败（渲染「—」+ title 原因，不显示 0） */
export interface PortalTodoSource {
  key: string
  label: string
  count: number | null
  /** 副行（「较昨日 +2」），后端不给则为 null */
  sub?: string | null
  link: string
  state: SectionState
  error?: string | null
}

export interface Portal {
  template: string
  templateLabel: string
  todoMode: TodoMode
  sections: PortalSection[]
  todoSources: PortalTodoSource[]
  dataAsOf: string
  cache: CacheState | string
}

/** 一张卡片的引用（配置页与 409 弹层共用） */
export interface PortalCardRef {
  key: string
  title: string
  reportId?: number | null
}

export interface PortalConfig {
  roleCode: string
  cardKeys: string[]
  maxCards: number
  /** 当前可配的模板卡（管理员只做显隐，不能加卡） */
  cards?: PortalCardRef[]
}

export interface PortalConfigCmd {
  roleCode: string
  cardKeys: string[]
}

/* ------------------------------------------------------------------ 报表 */

export type ReportScope = 'PRIVATE' | 'ROLE' | 'DOMAIN' | 'PUBLIC'

export type ChartType = 'TABLE' | 'BAR' | 'LINE' | 'PIE'

export type ReportFilterOp = '=' | '!=' | '>' | '>=' | '<' | '<=' | 'IN'

export interface ReportFilter {
  field: string
  op: ReportFilterOp
  value: string | string[]
}

/** 报表定义（服务端白名单校验：行 ≤3、列 ≤2、合计维度 ≤4、值 1–3、筛选 ≤8） */
export interface ReportDefinition {
  datasetId: string
  rows: string[]
  columns: string[]
  measures: string[]
  filters: ReportFilter[]
}

export interface ReportSummary {
  id: number
  name: string
  datasetId: string
  datasetLabel: string
  scope: ReportScope
  /** ROLE 时是角色码，DOMAIN 时是域 id */
  scopeRef?: string | null
  ownerId: number
  ownerName: string
  /** 列表「维/值」列的计数（服务端给，避免前端解析定义） */
  dimCount: number
  measureCount: number
  chartType: ChartType
  updatedAt: string
}

export interface ReportDetail extends ReportSummary {
  definition: ReportDefinition
  /** 服务端判定的写权限；缺省时前端不据此禁用，仍以 403 为准 */
  canWrite?: boolean
  canShare?: boolean
}

export interface ReportCmd {
  name?: string
  datasetId?: string
  definition?: ReportDefinition
  chartType?: ChartType
}

export interface ReportShareCmd {
  scope: ReportScope
  scopeRef?: string | null
}

export interface PinResult {
  pinned: boolean
  roleCode: string
  cards: PortalCardRef[]
}

export interface ReportListQuery {
  segment?: 'MINE' | 'SHARED'
  keyword?: string
  page?: number
  size?: number
}

export interface ReportExportParams extends AnalyticsQuery {
  format?: 'xlsx' | 'csv'
  /** 兜底文件名：服务端 Content-Disposition 已是权威名，见下方注释 */
  fileName?: string
}

/* ------------------------------------------------------------ 请求函数 */

/** `/portal`：五区一次返回 */
export function listPortal(
  params?: AnalyticsQuery,
  signal?: AbortSignal,
): Promise<Portal> {
  return http.get<Portal>(`${DELIVER}/portal`, { ...params }, { signal })
}

export function fetchPortalConfig(): Promise<PortalConfig> {
  return http.get<PortalConfig>(`${DELIVER}/portal/config`)
}

/** 管理员只做 4 张卡显隐（不扩契约：不能加卡、不能改顺序） */
export function savePortalConfig(body: PortalConfigCmd): Promise<PortalConfig> {
  return http.put<PortalConfig>(`${DELIVER}/portal/config`, body)
}

/** `/analytics/metrics?keys=a,b,c` */
export function fetchMetrics(
  keys: string[],
  params?: AnalyticsQuery,
  signal?: AbortSignal,
): Promise<Metrics> {
  return http.get<Metrics>(`${DELIVER}/analytics/metrics`, { keys: keys.join(','), ...params }, { signal })
}

export function fetchDashboard(
  params?: AnalyticsQuery & { bucket?: 'WEEK' | 'MONTH' },
  signal?: AbortSignal,
): Promise<Dashboard> {
  return http.get<Dashboard>(`${DELIVER}/analytics/dashboard`, { ...params }, { signal })
}

/** 4 数据集字段白名单（前端字段面板的唯一来源，不在前端写字段表） */
export function fetchDatasets(signal?: AbortSignal): Promise<{ items: Dataset[] }> {
  return http.get<{ items: Dataset[] }>(`${DELIVER}/reports/datasets`, undefined, { signal })
}

export function listReports(
  params?: ReportListQuery,
  signal?: AbortSignal,
): Promise<PageResponse<ReportSummary>> {
  return http.get<PageResponse<ReportSummary>>(`${DELIVER}/reports`, { ...params }, { signal })
}

export function getReport(id: number | string, signal?: AbortSignal): Promise<ReportDetail> {
  return http.get<ReportDetail>(`${DELIVER}/reports/${id}`, undefined, { signal })
}

export function createReport(body: ReportCmd): Promise<ReportDetail> {
  return http.post<ReportDetail>(`${DELIVER}/reports`, body)
}

export function updateReport(id: number | string, body: ReportCmd): Promise<ReportDetail> {
  return http.put<ReportDetail>(`${DELIVER}/reports/${id}`, body)
}

export function copyReport(id: number | string): Promise<ReportDetail> {
  return http.post<ReportDetail>(`${DELIVER}/reports/${id}/copy`)
}

/** 软删（服务端保留审计行，同属主可用回原名重建） */
export function deleteReport(id: number | string): Promise<void> {
  return http.del<void>(`${DELIVER}/reports/${id}`)
}

/** 共享只共享定义，数据永远按查看者 Scope 重算 */
export function shareReport(id: number | string, body: ReportShareCmd): Promise<ReportDetail> {
  return http.post<ReportDetail>(`${DELIVER}/reports/${id}/share`, body)
}

/** 设为工作台卡片；第 5 张 → 409 `PORTAL_CARD_LIMIT`（不静默替换） */
export function pinReport(id: number | string): Promise<PinResult> {
  return http.post<PinResult>(`${DELIVER}/reports/${id}/pin`)
}

/** 运行报表：省略 `body.definition` 即用已存定义；编辑器未保存时传草稿 */
export function runReport(
  id: number | string,
  body?: { definition?: ReportDefinition } & AnalyticsQuery,
  signal?: AbortSignal,
): Promise<CrossTab> {
  return http.post<CrossTab>(`${DELIVER}/reports/${id}/run`, body ?? {}, { signal })
}

/**
 * 导出：与页面同一份 CrossTab（服务端 `maxRows=10000` 再算一次）。
 *
 * 文件名：服务端的 `Content-Disposition` 才是权威名，但 `client.ts` 的 `download()`
 * 只回 blob、读不到响应头（见 [OUT-OF-SCOPE] 记录）。在 client 扩出 header 回调前，
 * 这里用 `report-<id>-<yyyyMMdd>.<ext>` 兜底；调用方可传 `fileName` 覆盖。
 */
export function exportReport(id: number | string, params: ReportExportParams = {}): Promise<void> {
  const { fileName, format = 'xlsx', ...query } = params
  return download(
    `${DELIVER}/reports/${id}/export`,
    { ...query, format },
    fileName ?? reportExportName(id, format),
  )
}

function reportExportName(id: number | string, format: 'xlsx' | 'csv'): string {
  const stamp = new Date().toISOString().slice(0, 10).replace(/-/g, '')
  return `report-${id}-${stamp}.${format}`
}

/**
 * 从 409 `PORTAL_CARD_LIMIT` 里取出当前 4 张卡（弹层要逐行列出）。
 * 服务端把清单放在 `detail.cards`；这里做唯一一次解析，页面不再摸 detail 结构。
 */
export function cardLimitCards(error: unknown): PortalCardRef[] {
  const detail = (error as { detail?: unknown } | null | undefined)?.detail
  if (!detail || typeof detail !== 'object') return []
  const cards = (detail as { cards?: unknown }).cards
  if (!Array.isArray(cards)) return []
  return cards
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item, index) => ({
      key: String(item.key ?? `card-${index}`),
      title: String(item.title ?? item.name ?? item.key ?? '未命名卡片'),
      reportId: typeof item.reportId === 'number' ? item.reportId : null,
    }))
}

/** 错误码 → 人话（页面只做映射，不改语义；未知码原样带回 detail.message） */
export const REPORT_ERROR_TEXT: Record<string, string> = {
  REPORT_UNKNOWN_FIELD: '字段不在数据集白名单里，请重新选择',
  REPORT_DATASET_MISMATCH: '定义里混入了其他数据集的字段',
  REPORT_DIM_LIMIT: '维度最多 3 行 2 列（合计不超过 4）',
  REPORT_MEASURE_LIMIT: '值最多 3 个',
  REPORT_FILTER_LIMIT: '筛选最多 8 条',
  REPORT_SPAN_LIMIT: '时间跨度最多 366 天',
  REPORT_GROUP_LIMIT: '结果分组过多，请收窄筛选',
  REPORT_EXPORT_LIMIT: '导出上限 1 万行，请收窄筛选',
  REPORT_NAME_DUPLICATED: '同名报表已存在',
  REPORT_NOT_FOUND: '报表不存在或无权查看',
  REPORT_SCOPE_FORBIDDEN: '只有属主可以修改共享与删除',
  PORTAL_CARD_LIMIT: '工作台最多 4 张卡片',
}
