/**
 * req-service 端点封装（TECHNICAL §4.3）。
 * 页面只调用这里的方法，不直接拼 URL：路径变更时只有一处要改。
 */
import { download, http } from './client'
import type { RequestOptions } from './client'
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
  GateDecision,
  GateItem,
  LoginPayload,
  LoginResponse,
  MeResponse,
  NotificationRow,
  PageResponse,
  ReleaseDetail,
  ReleaseRow,
  RequirementCreated,
  RequirementDetailResponse,
  RequirementRow,
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

export function submitDeployOrder(id: number | string): Promise<{ status: string }> {
  return http.post(`${REQ}/deploy-orders/${id}/submit`)
}

export function forceRelease(id: number | string, reason: string): Promise<void> {
  return http.post<void>(`${REQ}/deploy-orders/${id}/force-release`, { reason })
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

export type { GateDecision, GateItem }
