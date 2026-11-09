import { http } from './client'
import type { PageResponse } from './types'

/**
 * 协作域（CTeam）HTTP 契约：甘特、Wiki、价值流。
 *
 * 铁律（与 DESIGN §1.7 反模式一一对应）：
 * - 甘特告警只渲染服务端 `warnings[]`，前端不推断「临期/逾期/超载」；
 * - 改期只有一个写入口 `PATCH /gantt/rows/{type}/{id}`，409 的 `detail` 由本文件解析成
 *   可渲染的两类冲突（越父窗口 / 版本冲突），页面不再各自摸 `detail` 结构；
 * - Wiki 的 diff 只在服务端算（`/versions/diff`），前端不引第二份 LCS；
 * - 价值流读数一律来自 `/flow/board`，页面不重算均值/P90/瓶颈。
 */

const REQ = '/api/req'
const DELIVER = '/api/deliver'

/* ------------------------------------------------------------------ 甘特 */

export type GanttView = 'RELEASE' | 'SPRINT' | 'REQUIREMENT'
export type GanttZoom = 'DAY' | 'WEEK' | 'MONTH'
export type GanttRowType = 'RELEASE' | 'SPRINT' | 'REQUIREMENT' | 'TASK'

export interface GanttQuery {
  view: GanttView
  zoom: GanttZoom
  domainId?: number | null
  vendorId?: number | null
  releaseId?: number | null
  sprintId?: number | null
  from?: string | null
  to?: string | null
  /** `REQ:12,REL:3`；空表示按视图展开全部 */
  expanded?: string
  limit?: number
}

/** 服务端唯一来源的告警；`message` 已含原因与天数，前端只挑 chip 用的短文案 */
export interface GanttWarning {
  code: 'DUE_SOON' | 'OVERDUE' | 'OVERLOAD' | 'OUT_OF_PARENT_WINDOW' | 'TRUNCATED' | string
  level: 'RED' | 'ORANGE' | string
  message: string
  days?: number | null
  overDays?: number | null
  items?: string[]
}

export interface GanttRow {
  type: GanttRowType | string
  id: number
  parentId?: number | null
  level: number
  code?: string
  title: string
  ownerId?: number | null
  ownerName?: string | null
  planStart?: string | null
  planEnd?: string | null
  actualStart?: string | null
  actualEnd?: string | null
  status?: string
  done?: boolean
  milestone?: boolean
  plannedDate?: string | null
  /** 乐观锁令牌（行 updated_at 毫秒串），改期时原样回传 */
  version?: string | null
  warnings?: GanttWarning[]
}

export interface GanttBoard {
  view: string
  zoom: string
  rangeStart: string
  rangeEnd: string
  rows: GanttRow[]
  total: number
  truncated: boolean
  /** 当周容量（人天×10），前端只按它画负载，不自定阈值 */
  capacityTenths: number
  boardWarnings: GanttWarning[]
}

export interface RescheduleBody {
  planStart: string
  planEnd: string
  version?: string | null
  confirmToken?: string | null
}

export interface RescheduleResult {
  id: number
  type: string
  planStart: string
  planEnd: string
  /** 审计主键：回执上要显示「已写入审计」 */
  auditId: number
  version: string
}

/** 越父窗口：一次确认码 + 父窗口，用于 640 弹窗的倒计时与说明 */
export interface OutOfWindowDetail {
  kind: 'OUT_OF_PARENT_WINDOW'
  parentWindow: { start?: string | null; end?: string | null }
  violatingDays: number
  confirmToken: string
  expiresAt: string
}

export interface VersionConflictDetail {
  kind: 'VERSION_CONFLICT'
  currentVersion: string | null
}

export type RescheduleConflict = OutOfWindowDetail | VersionConflictDetail

export function fetchGantt(q: GanttQuery): Promise<GanttBoard> {
  return http.get<GanttBoard>(`${REQ}/gantt/rows`, { ...q })
}

export function reschedule(
  type: GanttRowType | string,
  id: number,
  body: RescheduleBody,
): Promise<RescheduleResult> {
  return http.patch<RescheduleResult>(`${REQ}/gantt/rows/${type}/${id}`, body)
}

/**
 * 把 409 的 `detail` 归一成两类可渲染冲突。
 * 认不出来的一律返回 null，由调用方按「结果未知」保守处理 —— 绝不猜成成功。
 */
export function parseRescheduleConflict(detail: unknown): RescheduleConflict | null {
  if (!detail || typeof detail !== 'object') return null
  const raw = detail as Record<string, unknown>
  if (typeof raw.confirmToken === 'string' && raw.confirmToken) {
    const parent = (raw.parentWindow ?? {}) as Record<string, unknown>
    return {
      kind: 'OUT_OF_PARENT_WINDOW',
      parentWindow: {
        start: typeof parent.start === 'string' ? parent.start : null,
        end: typeof parent.end === 'string' ? parent.end : null,
      },
      violatingDays: Number(raw.violatingDays ?? 0),
      confirmToken: raw.confirmToken,
      expiresAt: typeof raw.expiresAt === 'string' ? raw.expiresAt : '',
    }
  }
  if (raw.currentVersion !== undefined && raw.currentVersion !== null) {
    return { kind: 'VERSION_CONFLICT', currentVersion: String(raw.currentVersion) }
  }
  return null
}

/* ------------------------------------------------------------------ Wiki */

export type WikiVisibility = 'INHERIT' | 'DOMAIN' | 'VENDOR'

export interface WikiSpace {
  id: number
  code?: string
  name: string
  visibility: WikiVisibility | string
  ownerId?: number | null
  ownerName?: string | null
  description?: string | null
  pageCount?: number
  /** 只读空间：可读，但没有新建/移动入口 */
  canEdit?: boolean
}

export interface WikiTreeScope {
  spaceId?: number | null
  parentId?: number | null
  visibility?: WikiVisibility | string
  ownerId?: number | null
  description?: string | null
}

export interface WikiTreeNode {
  id: number
  parentId?: number | null
  title: string
  currentVersion?: number
  level?: number
  hasChildren?: boolean
  children?: WikiTreeNode[]
}

export interface WikiBreadcrumb {
  id: number
  title: string
}

export interface WikiPageMeta {
  id: number
  spaceId: number
  title: string
  contentMd: string
  currentVersion: number
  canEdit?: boolean
  visibility?: WikiVisibility | string
  updatedAt?: string | null
  updatedByName?: string | null
  deletedAt?: string | null
  /** 软删后剩余可恢复天数（30 天窗口） */
  remainingDays?: number | null
  contentHash?: string | null
}

export interface WikiPageLink {
  bizType: string
  bizId: number
  title?: string | null
}

export interface WikiAttachment {
  id: number
  name: string
  size?: number
  url?: string
}

export interface WikiPageDetail {
  page: WikiPageMeta
  breadcrumb: WikiBreadcrumb[]
  links: WikiPageLink[]
  attachments: WikiAttachment[]
}

export interface SavePageBody {
  title: string
  contentMd: string
  /** 乐观锁：落后即 409 WIKI_EDIT_CONFLICT */
  baseVersion: number
}

export interface SavePageResult {
  pageId: number
  noChange: boolean
  versionNo: number
  title?: string
  currentVersion?: number
}

export interface WikiVersion {
  versionNo: number
  title?: string
  savedAt?: string | null
  savedByName?: string | null
  savedBy?: number | null
  changeNote?: string | null
  contentHash?: string | null
  current?: boolean
}

export type DiffKind = 'SAME' | 'ADD' | 'DEL'

export interface WikiDiffLine {
  kind: DiffKind
  leftNo?: number | null
  rightNo?: number | null
  text: string
}

export interface WikiDiff {
  lines: WikiDiffLine[]
  added: number
  removed: number
  changed: number
}

export interface WikiSearchHit {
  pageId: number
  title: string
  snippet: string
  spaceName: string
  updatedAt?: string | null
}

export interface WikiSearchResult {
  items: WikiSearchHit[]
  total: number
  /** ngram 索引缺失时服务端降级 LIKE，页面要如实说明 */
  degraded?: boolean
  page: number
  size: number
}

export interface WikiTrashItem {
  id: number
  title: string
  spaceId?: number
  deletedAt?: string | null
  deletedByName?: string | null
  remainingDays: number
  /** 以「删除根」一行呈现，避免整棵子树刷屏 */
  deletedRootId?: number | null
}

export function fetchSpaces(): Promise<WikiSpace[]> {
  return http.get<WikiSpace[]>(`${REQ}/wiki/spaces`)
}

export function createSpace(body: { name: string; visibility: WikiVisibility; description?: string }): Promise<WikiSpace> {
  return http.post<WikiSpace>(`${REQ}/wiki/spaces`, body)
}

export function updateSpace(
  spaceId: number,
  body: { name?: string; visibility?: WikiVisibility; description?: string; ownerId?: number | null },
): Promise<WikiSpace> {
  return http.put<WikiSpace>(`${REQ}/wiki/spaces/${spaceId}`, body)
}

export function fetchTree(spaceId: number): Promise<WikiTreeNode[]> {
  return http.get<WikiTreeNode[]>(`${REQ}/wiki/spaces/${spaceId}/tree`)
}

export function fetchPage(pageId: number): Promise<WikiPageDetail> {
  return http.get<WikiPageDetail>(`${REQ}/wiki/pages/${pageId}`)
}

export function createPage(body: {
  spaceId: number
  parentId?: number | null
  title: string
  contentMd?: string
}): Promise<WikiPageDetail> {
  return http.post<WikiPageDetail>(`${REQ}/wiki/pages`, body)
}

export function savePage(pageId: number, body: SavePageBody): Promise<SavePageResult> {
  return http.put<SavePageResult>(`${REQ}/wiki/pages/${pageId}`, body)
}

export function movePage(pageId: number, parentId: number | null): Promise<void> {
  return http.post<void>(`${REQ}/wiki/pages/${pageId}/move`, { parentId })
}

export function deletePage(pageId: number): Promise<void> {
  return http.del<void>(`${REQ}/wiki/pages/${pageId}`)
}

export function fetchTrash(spaceId?: number | null): Promise<WikiTrashItem[]> {
  return http.get<WikiTrashItem[]>(`${REQ}/wiki/trash`, { spaceId: spaceId ?? undefined })
}

export function restorePage(pageId: number): Promise<void> {
  return http.post<void>(`${REQ}/wiki/pages/${pageId}/restore`)
}

export function purgePage(pageId: number): Promise<void> {
  return http.del<void>(`${REQ}/wiki/pages/${pageId}/purge`)
}

export function fetchVersions(pageId: number): Promise<WikiVersion[]> {
  return http.get<WikiVersion[]>(`${REQ}/wiki/pages/${pageId}/versions`)
}

export function diffVersions(pageId: number, from: number, to: number): Promise<WikiDiff> {
  return http.get<WikiDiff>(`${REQ}/wiki/pages/${pageId}/versions/diff`, { from, to })
}

export function rollbackPage(pageId: number, versionNo: number): Promise<{ versionNo: number; pageId: number }> {
  return http.post<{ versionNo: number; pageId: number }>(`${REQ}/wiki/pages/${pageId}/rollback`, { versionNo })
}

export function searchWiki(q: string, page = 1, size = 20): Promise<WikiSearchResult> {
  return http.get<WikiSearchResult>(`${REQ}/wiki/search`, { q, page, size })
}

export function linkPage(pageId: number, body: { bizType: string; bizId: number }): Promise<void> {
  return http.post<void>(`${REQ}/wiki/pages/${pageId}/links`, body)
}

export function unlinkPage(pageId: number, body: { bizType: string; bizId: number }): Promise<void> {
  // DELETE 带体：client 的 del 只有 extra 参数，body 走 RequestOptions
  return http.del<void>(`${REQ}/wiki/pages/${pageId}/links`, { body })
}

export function fetchLinks(bizType: string, bizId: number): Promise<WikiSearchHit[]> {
  return http.get<WikiSearchHit[]>(`${REQ}/wiki/links`, { bizType, bizId })
}

/* --------------------------------------------------------------- 价值流 */

export interface FlowQuery {
  domainId?: number | null
  vendorId?: number | null
  from?: string | null
  to?: string | null
  reqType?: string | null
}

export interface FlowWindow {
  from: string
  to: string
}

export interface FlowStageCard {
  code: string
  name: string
  orderNo: number
  /** 该需求类型不适用：无指标、不可下钻 */
  applicable: boolean
  wip: number
  dwellAvg?: number | null
  dwellP90?: number | null
  sampleSize: number
  noDataCount: number
  /** 瓶颈由服务端判定；前端只画顶部 2px + 文案 */
  bottleneck: boolean
}

export interface FlowEfficiency {
  value?: number | null
  included: number
  excluded: number
}

export interface FlowBoard {
  window: FlowWindow
  stages: FlowStageCard[]
  efficiency: FlowEfficiency
  noDataCount: number
  generatedAt?: string | null
}

export interface FlowStageDefinition {
  id: number
  code: string
  name: string
  orderNo: number
  mappingJson?: string | null
  reqTypes?: string | null
  enabled: boolean
}

export interface FlowStageDraft {
  id?: number | null
  code: string
  name: string
  orderNo?: number
  enabled?: boolean
  reqTypes?: string | null
  mappingJson?: string | null
}

export interface FlowDrillRow {
  id: number
  code: string
  title: string
  type: string
  status: string
  ownerId?: number | null
  enteredAt?: string | null
  dwellDays?: number | null
}

export interface FlowTimelineRow {
  stageCode: string
  enteredAt?: string | null
  leftAt?: string | null
  dwellDays?: number | null
  sourceEvent?: string | null
  /** 缺口：显式「无数据」，绝不当 0 天 */
  noData: boolean
}

export function fetchFlowBoard(q: FlowQuery): Promise<FlowBoard> {
  return http.get<FlowBoard>(`${DELIVER}/flow/board`, { ...q })
}

export function fetchStages(): Promise<FlowStageDefinition[]> {
  return http.get<FlowStageDefinition[]>(`${DELIVER}/flow/stages`)
}

export function saveStages(stages: FlowStageDraft[]): Promise<FlowStageDefinition[]> {
  return http.put<FlowStageDefinition[]>(`${DELIVER}/flow/stages`, { stages })
}

export function drillStage(
  q: FlowQuery & { stageCode: string; page?: number; size?: number },
): Promise<PageResponse<FlowDrillRow>> {
  return http.get<PageResponse<FlowDrillRow>>(`${DELIVER}/flow/drill`, { ...q })
}

export function fetchFlowTimeline(
  requirementId: number,
  q: { from?: string | null; to?: string | null } = {},
): Promise<FlowTimelineRow[]> {
  return http.get<FlowTimelineRow[]>(`${DELIVER}/flow/requirements/${requirementId}/timeline`, { ...q })
}
