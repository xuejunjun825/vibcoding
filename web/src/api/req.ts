/**
 * req-service 端点封装（TECHNICAL §4.3）。
 * 页面只调用这里的方法，不直接拼 URL：路径变更时只有一处要改。
 */
import { download, http } from './client'
import type { RequestOptions } from './client'
import type { MrCard, SonarCard } from './integration'
import type {
  ActionPayload,
  ActionResponse,
  AdmissionSnapshot,
  ApprovalInstanceDetail,
  ApprovalTimelineEvent,
  ApprovalTodoRow,
  AttachmentRow,
  CreateRequirementPayload,
  DeployOrderDetail,
  DeployOrderRow,
  DeploySubmitResult,
  GateDecision,
  GateItem,
  LoginPayload,
  LoginResponse,
  MeResponse,
  NotificationRow,
  PageResponse,
  ReleaseDetail,
  ReleaseQualitySettings,
  ReleaseRow,
  RequirementCreated,
  RequirementDetailResponse,
  RequirementRow,
  ReviewCreatePayload,
  ReviewDetail,
  ReviewRow,
} from './types'

const REQ = '/api/req'

/* ---------------- 认证 ---------------- */

export function login(payload: LoginPayload): Promise<LoginResponse> {
  return http.post<LoginResponse>(`${REQ}/auth/login`, payload, { silentAuth: true, silentForbidden: true })
}

export function fetchMe(): Promise<MeResponse> {
  return http.get<MeResponse>(`${REQ}/auth/me`)
}

/* ---------------- 通知 ---------------- */

export function listNotifications(params: {
  unreadOnly?: boolean
  page?: number
  size?: number
}): Promise<PageResponse<NotificationRow>> {
  return http.get<PageResponse<NotificationRow>>(`${REQ}/notifications`, {
    unreadOnly: params.unreadOnly ? true : undefined,
    page: params.page,
    size: params.size,
  })
}

export function markNotificationRead(id: number): Promise<void> {
  return http.post<void>(`${REQ}/notifications/${id}/read`)
}

export function markAllNotificationsRead(): Promise<void> {
  return http.post<void>(`${REQ}/notifications/read-all`)
}

/* ---------------- 需求 ---------------- */

export type RequirementListQuery = {
  type?: string
  keyword?: string
  parentId?: number | string
  releaseId?: number | string
  ownerId?: number | string
  priority?: string
  status?: string
  domainId?: number | string
  vendorId?: number | string
  orgId?: number | string
  dateFrom?: string
  dateTo?: string
  view?: 'agg' | 'flat'
  page?: number
  size?: number
  [key: string]: unknown
}

export function listRequirements(
  params: RequirementListQuery,
): Promise<PageResponse<RequirementRow>> {
  return http.get<PageResponse<RequirementRow>>(`${REQ}/requirements`, params)
}

/**
 * 详情返回 `{view, children, allowedActions, permissions}`（RequirementViews.Detail）。
 * 页面统一用 `normalizeRequirementDetail()` 转成扁平 `RequirementDetail`。
 */
export function getRequirement(id: number | string): Promise<RequirementDetailResponse> {
  return http.get<RequirementDetailResponse>(`${REQ}/requirements/${id}`)
}

export function createRequirement(
  payload: CreateRequirementPayload,
): Promise<RequirementCreated> {
  return http.post<RequirementCreated>(`${REQ}/requirements`, payload)
}

/** PUT 返回扁平的 RequirementViews.View（不是 Detail 包装）。 */
export function updateRequirement(
  id: number | string,
  payload: Partial<CreateRequirementPayload>,
): Promise<RequirementRow> {
  return http.put<RequirementRow>(`${REQ}/requirements/${id}`, payload)
}

export function actOnRequirement(
  id: number | string,
  payload: ActionPayload,
): Promise<ActionResponse> {
  // 非法流转会拿到 409 ILLEGAL_TRANSITION，页面用常驻条渲染，不 catch 成 toast
  return http.post<ActionResponse>(`${REQ}/requirements/${id}/actions`, payload)
}

export function decomposeRequirement(
  id: number | string,
  payload: CreateRequirementPayload,
): Promise<RequirementCreated> {
  return http.post<RequirementCreated>(`${REQ}/requirements/${id}/decompose`, payload)
}

export function assignVendor(
  id: number | string,
  payload: { vendorId: number; ownerUserId?: number; reason: string },
): Promise<RequirementRow> {
  return http.post<RequirementRow>(`${REQ}/requirements/${id}/assign-vendor`, payload)
}

export function deliverRequirement(
  id: number | string,
  payload: { estimateDays: number; actualDays?: number; mrUrl: string; ownerUserId?: number },
): Promise<RequirementRow> {
  return http.post<RequirementRow>(`${REQ}/requirements/${id}/deliver`, payload)
}

export function quickTest(
  id: number | string,
  payload: { result: 'PASS' | 'FAIL'; note?: string },
): Promise<{ executionId: number }> {
  return http.post(`${REQ}/requirements/${id}/quick-test`, payload)
}

export function searchRequirements(q: string): Promise<RequirementRow[]> {
  return http.get<RequirementRow[]>(`${REQ}/requirements/search`, { q })
}

export function exportRequirementsCsv(params: RequirementListQuery): Promise<void> {
  return download(`${REQ}/requirements/export.csv`, params, 'requirements.csv')
}

export function listAttachments(bizType: string, bizId: number | string): Promise<AttachmentRow[]> {
  return http.get<AttachmentRow[]>(`${REQ}/attachments`, { bizType, bizId })
}

export function uploadAttachment(form: FormData): Promise<{ id: number }> {
  return http.post<{ id: number }>(`${REQ}/attachments`, form)
}

/* ---------------- 审批 ---------------- */

export function listApprovalTodo(
  params: { page?: number; size?: number },
  extra?: Partial<RequestOptions>,
): Promise<PageResponse<ApprovalTodoRow>> {
  return http.get<PageResponse<ApprovalTodoRow>>(`${REQ}/approvals/todo`, params, extra)
}

export function listApprovalDone(params: {
  page?: number
  size?: number
}): Promise<PageResponse<ApprovalTodoRow>> {
  return http.get<PageResponse<ApprovalTodoRow>>(`${REQ}/approvals/done`, params)
}

export function listApprovalMine(params: {
  page?: number
  size?: number
}): Promise<PageResponse<ApprovalTodoRow>> {
  return http.get<PageResponse<ApprovalTodoRow>>(`${REQ}/approvals/mine`, params)
}

export function getApprovalInstance(id: number | string): Promise<ApprovalInstanceDetail> {
  return http.get<ApprovalInstanceDetail>(`${REQ}/approvals/instances/${id}`)
}

export function handleApprovalTask(
  taskId: number | string,
  payload: { action: 'APPROVE' | 'REJECT'; comment?: string },
): Promise<{ instanceStatus: string; nodeStatus: string }> {
  return http.post(`${REQ}/approvals/tasks/${taskId}/actions`, payload)
}

export function addSign(
  taskId: number | string,
  payload: { type: 'BEFORE' | 'AFTER'; userIds: number[]; comment?: string },
): Promise<void> {
  return http.post<void>(`${REQ}/approvals/tasks/${taskId}/add-sign`, payload)
}

export function removeSign(
  taskId: number | string,
  payload: { targetUserId: number; reason: string },
): Promise<void> {
  return http.post<void>(`${REQ}/approvals/tasks/${taskId}/remove-sign`, payload)
}

export function transferTask(
  taskId: number | string,
  payload: { toUserId: number; comment?: string },
): Promise<void> {
  return http.post<void>(`${REQ}/approvals/tasks/${taskId}/transfer`, payload)
}

export function urgeTask(taskId: number | string): Promise<void> {
  return http.post<void>(`${REQ}/approvals/tasks/${taskId}/urge`)
}

export function withdrawInstance(instanceId: number | string, comment?: string): Promise<void> {
  return http.post<void>(`${REQ}/approvals/instances/${instanceId}/withdraw`, { comment })
}

export function approvalHistory(
  instanceId: number | string,
): Promise<Array<{ version: number; events: ApprovalTimelineEvent[] }>> {
  return http.get(`${REQ}/approvals/instances/${instanceId}/history`)
}

/* ---------------- 版本 ---------------- */

export function listReleases(params: {
  domainId?: number | string
  status?: string
  page?: number
  size?: number
  [key: string]: unknown
}): Promise<PageResponse<ReleaseRow>> {
  return http.get<PageResponse<ReleaseRow>>(`${REQ}/releases`, params)
}

export function createRelease(payload: {
  name: string
  domainId: number
  startDate?: string
  endDate?: string
  plannedReleaseDate?: string
  gateMode?: 'STRICT' | 'LOOSE'
}): Promise<ReleaseRow> {
  return http.post<ReleaseRow>(`${REQ}/releases`, payload)
}

export function getRelease(id: number | string): Promise<ReleaseDetail> {
  return http.get<ReleaseDetail>(`${REQ}/releases/${id}`)
}

export function addRequirementsToRelease(
  id: number | string,
  requirementIds: number[],
): Promise<void> {
  return http.post<void>(`${REQ}/releases/${id}/requirements`, { requirementIds })
}

export function removeRequirementFromRelease(
  id: number | string,
  requirementId: number | string,
): Promise<void> {
  return http.del<void>(`${REQ}/releases/${id}/requirements/${requirementId}`)
}

/**
 * 提交发布审批（POST /releases/{id}/approval）：审批通过后版本自动锁定。
 * 与 {@link lockRelease} 是两条入口；本端点返回实例与版本 id。
 */
export function requestReleaseApproval(
  id: number | string,
): Promise<{ instanceId: number; releaseId: number }> {
  return http.post(`${REQ}/releases/${id}/approval`)
}

/**
 * 直接锁定版本（POST /releases/{id}/lock）：锁定后需求范围只读、不再接受增删。
 * 已锁定/已发布/已取消会 409，页面按 `release:plan` 权限与 `status === 'PLAN'` 渲染按钮。
 */
export function lockRelease(id: number | string): Promise<ReleaseRow> {
  return http.post<ReleaseRow>(`${REQ}/releases/${id}/lock`)
}

export function addMilestone(
  id: number | string,
  payload: { name: string; plannedDate: string },
): Promise<void> {
  return http.post<void>(`${REQ}/releases/${id}/milestones`, payload)
}

/* ---------------- 发布单 ---------------- */

export function listDeployOrders(params: {
  releaseId?: number | string
  env?: string
  status?: string
  page?: number
  size?: number
}): Promise<PageResponse<DeployOrderRow>> {
  return http.get<PageResponse<DeployOrderRow>>(`${REQ}/deploy-orders`, params)
}

export function createDeployOrder(payload: {
  releaseId: number
  env: string
  windowStart: string
  windowEnd: string
  artifactVersion: string
  steps?: unknown
  rollbackPlan?: string
  impact?: string
  ownerId?: number
}): Promise<DeployOrderRow> {
  return http.post<DeployOrderRow>(`${REQ}/deploy-orders`, payload)
}

export function getDeployOrder(id: number | string): Promise<DeployOrderDetail> {
  return http.get<DeployOrderDetail>(`${REQ}/deploy-orders/${id}`)
}

export function evaluateAdmission(id: number | string): Promise<AdmissionSnapshot> {
  return http.post<AdmissionSnapshot>(`${REQ}/deploy-orders/${id}/evaluate`)
}

/** 只读取上次求值结果（不重算）：提交页刷新时不能悄悄改变结论 */
export function getAdmission(id: number | string): Promise<AdmissionSnapshot> {
  return http.get<AdmissionSnapshot>(`${REQ}/deploy-orders/${id}/admission`)
}

/**
 * 提交发布单：服务端把门禁快照固化到单据上（AC-5/AC-7），此后回放只看快照。
 * NO-GO 评审结论未二次确认 → 409 `NO_GO_CONFIRM_REQUIRED`，页面据 code 弹确认而不吞错。
 *
 * `acknowledgedSkipCount`（M7）：9 项准入里有 SKIP 时，提交必须回传与 `skipCount` 等值的
 * 数字（SKIP 永不计 PASS，用户要显式确认放过了几项），否则 400 `SKIP_NOT_ACKNOWLEDGED`，
 * 错误 detail 里带服务端算出的 `skipCount`。跳过 0 项时不传等价于传 0。
 */
export function submitDeployOrder(
  id: number | string,
  confirmNoGo = false,
  acknowledgedSkipCount = 0,
): Promise<DeploySubmitResult> {
  return http.post<DeploySubmitResult>(`${REQ}/deploy-orders/${id}/submit`, {
    confirmNoGo,
    acknowledgedSkipCount,
  })
}

/** 强制放行已退役（AC-12）：路由保留是为了让旧脚本拿到 409 FORCE_RELEASE_RETIRED 而不是 404 */
export function forceRelease(id: number | string, reason: string): Promise<void> {
  return http.post<void>(`${REQ}/deploy-orders/${id}/force-release`, { reason })
}

/* ---------------- Go/No-Go 评审（发布决策域，数据只经摘要读取） ---------------- */

/** 评审列表（服务端分页；TECHNICAL §5.3 返回 Page<ReviewRow>） */
export function listReviews(
  releaseId: number | string,
  params: { page?: number; size?: number; decision?: string } = {},
): Promise<PageResponse<ReviewRow>> {
  return http.get<PageResponse<ReviewRow>>(`${REQ}/releases/${releaseId}/reviews`, params)
}

export function createReview(
  releaseId: number | string,
  payload: ReviewCreatePayload,
): Promise<ReviewDetail> {
  return http.post<ReviewDetail>(`${REQ}/releases/${releaseId}/reviews`, payload)
}

export function getReview(
  releaseId: number | string,
  reviewId: number | string,
): Promise<ReviewDetail> {
  return http.get<ReviewDetail>(`${REQ}/releases/${releaseId}/reviews/${reviewId}`)
}

/** NO-GO 必须二次确认后才允许提交发布单；重复确认幂等 */
export function confirmReview(
  releaseId: number | string,
  reviewId: number | string,
  note?: string,
): Promise<ReviewDetail> {
  return http.post<ReviewDetail>(`${REQ}/releases/${releaseId}/reviews/${reviewId}/confirm`, {
    note,
  })
}

/** 版本质量设置：观察窗小时数（默认 72）与报告有效期天数（默认 7）；返回整张版本详情 */
export function updateQualitySettings(
  releaseId: number | string,
  payload: ReleaseQualitySettings,
): Promise<ReleaseDetail> {
  return http.patch<ReleaseDetail>(`${REQ}/releases/${releaseId}/quality-settings`, payload)
}

export function recordExecution(
  id: number | string,
  payload: {
    result: 'SUCCESS' | 'FAILED' | 'ROLLED_BACK'
    startedAt: string
    endedAt: string
    note?: string
    defectId?: number
  },
): Promise<void> {
  return http.post<void>(`${REQ}/deploy-orders/${id}/execution`, payload)
}

export function deployStats(params: {
  releaseId?: number | string
  env?: string
  from?: string
  to?: string
}): Promise<{
  successRate: number
  avgDurationMinutes: number
  failReasons: Array<{ reason: string; count: number }>
}> {
  return http.get(`${REQ}/deploy-orders/stats`, params)
}

/* ---------------- CCode：MR 评审卡与行级评论（TECHNICAL §2.6） ---------------- */

export type MrReviewState = 'PENDING' | 'APPROVED' | 'CHANGES_REQUESTED'

export interface MrReviewer {
  id?: number | null
  name: string
  state?: MrReviewState | string | null
}

/** MrCard 的 M8 增列；服务端未下发时保持可缺省，页面渲染「未取到」而不是猜 */
export interface MrReviewFields {
  reviewers?: MrReviewer[] | null
  reviewState?: MrReviewState | string | null
  changedFiles?: number | null
  commentsCount?: number | null
}

/** 评审状态文案与 tone 的唯一映射（dict 的 StatusKind 未含该枚举，组件自包含） */
export const MR_REVIEW_STATE_LABEL: Record<string, string> = {
  PENDING: '待评审',
  APPROVED: '已批准',
  CHANGES_REQUESTED: '已要求修改',
}

export interface MrCommentRow {
  id: number
  gitlabNoteId?: number | null
  author?: string | null
  body: string
  filePath?: string | null
  lineNo?: number | null
  resolved?: boolean | null
  createdAt?: string | null
}

export interface MrCommentPage {
  items: MrCommentRow[]
  total?: number
  nextCursor?: string | null
}

export interface MrRefreshResult {
  card?: (MrCard & MrReviewFields) | null
  added?: number
  updated?: number
  commentsCount?: number | null
}

/** 刷新是前端唯一触发 GitLab 外呼的入口；其余读取都走本地快照 */
export function refreshMr(requirementId: number | string): Promise<MrRefreshResult> {
  return http.post<MrRefreshResult>(`${REQ}/requirements/${requirementId}/integration/mr/refresh`)
}

export async function fetchMrComments(
  requirementId: number | string,
  cursor?: string | null,
  limit = 50,
): Promise<MrCommentPage> {
  const raw = await http.get<MrCommentPage | MrCommentRow[]>(
    `${REQ}/requirements/${requirementId}/integration/mr-comments`,
    { cursor: cursor || undefined, limit },
  )
  if (Array.isArray(raw)) return { items: raw }
  return { items: raw?.items ?? [], total: raw?.total, nextCursor: raw?.nextCursor ?? null }
}

/* ---------------- CCode：代码质量只读快照（TECHNICAL §2.7） ---------------- */

export interface CodeQualityPayload {
  /** 无快照时严格为 null（服务端不补 0，页面也不许显示 0/100%/绿） */
  card: SonarCard | null
  emptyReason?: 'NO_SNAPSHOT' | 'INTEGRATION_DISABLED' | string | null
}

export interface ReleaseCodeQualityProject {
  projectKey: string
  requirementId?: number | null
  requirementCode?: string | null
  card: SonarCard | null
}

export interface ReleaseCodeQualityPayload {
  projects: ReleaseCodeQualityProject[]
  emptyReason?: 'NO_SNAPSHOT' | 'INTEGRATION_DISABLED' | string | null
}

export function fetchCodeQuality(requirementId: number | string): Promise<CodeQualityPayload> {
  return http.get<CodeQualityPayload>(`${REQ}/requirements/${requirementId}/code-quality`)
}

export function fetchReleaseCodeQuality(releaseId: number | string): Promise<ReleaseCodeQualityPayload> {
  return http.get<ReleaseCodeQualityPayload>(`${REQ}/releases/${releaseId}/code-quality`)
}

/* ---------------- Wiki 双向关联（TECHNICAL §2.3，文档 tab 使用） ---------------- */

export interface WikiLinkRow {
  pageId: number
  title: string
  spaceId?: number | null
  spaceName?: string | null
  visibility?: string | null
  updatedAt?: string | null
}

export interface WikiSearchItem {
  pageId: number
  title: string
  snippet?: string | null
  spaceName?: string | null
  updatedAt?: string | null
}

export async function fetchWikiLinks(bizType: string, bizId: number | string): Promise<WikiLinkRow[]> {
  const raw = await http.get<WikiLinkRow[] | { items?: WikiLinkRow[] }>(`${REQ}/wiki/links`, {
    bizType,
    bizId,
  })
  return Array.isArray(raw) ? raw : raw?.items ?? []
}

export function linkWikiPage(
  pageId: number,
  bizType: string,
  bizId: number | string,
): Promise<void> {
  return http.post<void>(`${REQ}/wiki/pages/${pageId}/links`, { bizType, bizId })
}

export function unlinkWikiPage(
  pageId: number,
  bizType: string,
  bizId: number | string,
): Promise<void> {
  return http.del<void>(`${REQ}/wiki/pages/${pageId}/links`, { params: { bizType, bizId } })
}

/** 关联抽屉的候选页搜索：只返回调用者可见的页（服务端先过滤再计数） */
export async function searchWikiPages(
  q: string,
  page = 1,
  size = 20,
): Promise<{ items: WikiSearchItem[]; total: number; degraded?: boolean }> {
  const raw = await http.get<{
    items?: WikiSearchItem[]
    total?: number
    degraded?: boolean
  }>(`${REQ}/wiki/search`, { q, page, size })
  return { items: raw?.items ?? [], total: raw?.total ?? 0, degraded: raw?.degraded }
}

export function deleteAttachment(id: number): Promise<void> {
  return http.del<void>(`${REQ}/attachments/${id}`)
}

export function downloadAttachment(id: number, fileName?: string): Promise<void> {
  return download(`${REQ}/attachments/${id}`, undefined, fileName)
}

export type { GateDecision, GateItem }
