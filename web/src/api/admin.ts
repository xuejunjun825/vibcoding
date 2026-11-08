/**
 * 系统管理端点封装（TECHNICAL §4.3「认证与管理员」）。
 * 这些页面由 T9 实现，但接口层在基座里先收敛好，避免每页各拼一次 URL。
 */
import { http } from './client'
import type {
  ApiClientRow,
  AuditRow,
  MetricDefinition,
  PageResponse,
  PermissionMatrixResponse,
} from './types'

const ADMIN = '/api/req/admin'
const DELIVER_ADMIN = '/api/deliver/admin'

export interface TenantRow {
  tenantId: number
  code: string
  name: string
  status?: string
  createdAt?: string
}

export interface OrgRow {
  id: number
  name: string
  parentId?: number | null
  tenantId?: number
  children?: OrgRow[]
}

export interface DomainRow {
  id: number
  code?: string
  name: string
  ownerId?: number | null
  ownerName?: string
}

export interface VendorRow {
  id: number
  name: string
  contact?: string
  status?: string
  domainIds?: number[]
}

export interface UserRow {
  id: number
  username: string
  name: string
  roles: string[]
  orgId?: number | null
  orgName?: string
  domainIds?: number[]
  vendorId?: number | null
  status?: string
}

/**
 * 流程定义行 = 后端 `WorkflowAdminService.WorkflowView`。
 * 注意：后端没有 `code` 列（业务键是 bizType + reqType + domainId + version），
 * 这里保留可选 `code` 只为兼容旧页面，新代码请用 bizType。
 */
export interface WorkflowDefinitionRow {
  id: number
  tenantId?: number
  /** 兼容字段：服务端不返回；显示请用 bizType */
  code?: string
  name: string
  bizType: string
  reqType?: string
  domainId?: number
  version: number
  status: 'DRAFT' | 'PUBLISHED' | 'DISABLED' | string
  isDefault?: boolean
  publishedAt?: string | null
  publishedBy?: number | null
  createdAt?: string
  updatedAt?: string
  /** 节点图（APPROVAL/CONDITION/CC）；编辑页回填用 */
  definition?: Record<string, unknown>
}

export function listPermissions(): Promise<PermissionMatrixResponse> {
  // AC-5 的证据接口：10 角色 × 功能码逐格断言
  return http.get<PermissionMatrixResponse>(`${ADMIN}/permissions`, undefined, {
    silentForbidden: true,
  })
}

export function listTenants(params: {
  page?: number
  size?: number
}): Promise<PageResponse<TenantRow>> {
  return http.get<PageResponse<TenantRow>>(`${ADMIN}/tenants`, params)
}

export function createTenant(payload: {
  code: string
  name: string
}): Promise<{ tenantId: number; provisioned: Record<string, number> }> {
  return http.post(`${ADMIN}/tenants`, payload)
}

export function listOrgs(): Promise<OrgRow[]> {
  return http.get<OrgRow[]>(`${ADMIN}/orgs`)
}

export function saveOrg(payload: Partial<OrgRow>): Promise<OrgRow> {
  return payload.id
    ? http.put<OrgRow>(`${ADMIN}/orgs`, payload)
    : http.post<OrgRow>(`${ADMIN}/orgs`, payload)
}

export function listDomains(): Promise<DomainRow[]> {
  return http.get<DomainRow[]>(`${ADMIN}/domains`)
}

export function saveDomain(payload: Partial<DomainRow>): Promise<DomainRow> {
  return payload.id
    ? http.put<DomainRow>(`${ADMIN}/domains`, payload)
    : http.post<DomainRow>(`${ADMIN}/domains`, payload)
}

export function listVendors(): Promise<VendorRow[]> {
  return http.get<VendorRow[]>(`${ADMIN}/vendors`)
}

export function saveVendor(payload: Partial<VendorRow>): Promise<VendorRow> {
  return payload.id
    ? http.put<VendorRow>(`${ADMIN}/vendors`, payload)
    : http.post<VendorRow>(`${ADMIN}/vendors`, payload)
}

export function listUsers(params: {
  keyword?: string
  page?: number
  size?: number
}): Promise<PageResponse<UserRow>> {
  return http.get<PageResponse<UserRow>>(`${ADMIN}/users`, params)
}

export function saveUser(payload: Partial<UserRow> & { password?: string }): Promise<UserRow> {
  return payload.id
    ? http.put<UserRow>(`${ADMIN}/users`, payload)
    : http.post<UserRow>(`${ADMIN}/users`, payload)
}

export function setUserStatus(id: number, status: string): Promise<void> {
  return http.patch<void>(`${ADMIN}/users/${id}/status`, { status })
}

export function resetPassword(id: number, password: string): Promise<void> {
  return http.post<void>(`${ADMIN}/users/${id}/reset-password`, { password })
}

export function listWorkflows(bizType?: string): Promise<WorkflowDefinitionRow[]> {
  return http.get<WorkflowDefinitionRow[]>(`${ADMIN}/workflows`, { bizType })
}

/** 单个流程（含 definition JSON）：编辑已发布/草稿时回填节点图。 */
export function getWorkflow(id: number | string): Promise<WorkflowDefinitionRow> {
  return http.get<WorkflowDefinitionRow>(`${ADMIN}/workflows/${id}`)
}

export function saveWorkflow(
  payload: Record<string, unknown> & { id?: number | string },
): Promise<WorkflowDefinitionRow> {
  // 更新必须带 id 走 PUT /workflows/{id}；否则后端只有 POST /workflows，会 405。
  return payload.id
    ? http.put<WorkflowDefinitionRow>(`${ADMIN}/workflows/${payload.id}`, payload)
    : http.post<WorkflowDefinitionRow>(`${ADMIN}/workflows`, payload)
}

export function publishWorkflow(id: number | string): Promise<WorkflowDefinitionRow> {
  return http.post<WorkflowDefinitionRow>(`${ADMIN}/workflows/${id}/publish`)
}

export function disableWorkflow(id: number | string): Promise<void> {
  return http.post<void>(`${ADMIN}/workflows/${id}/disable`)
}

export function listApiClients(params: {
  page?: number
  size?: number
}): Promise<PageResponse<ApiClientRow>> {
  return http.get<PageResponse<ApiClientRow>>(`${ADMIN}/api-clients`, params)
}

export function createApiClient(payload: {
  name: string
  scopes: string[]
  ipWhitelist?: string
  rateLimitQps?: number
}): Promise<{ appKey: string; secret: string }> {
  // secret 仅创建/轮换时返回一次，页面必须弹「仅显示一次」提示
  return http.post(`${ADMIN}/api-clients`, payload)
}

export function rotateApiClientSecret(id: number | string): Promise<{
  appKey: string
  secret: string
}> {
  return http.post(`${ADMIN}/api-clients/${id}/rotate`)
}

export function setApiClientStatus(id: number | string, status: string): Promise<void> {
  return http.patch<void>(`${ADMIN}/api-clients/${id}/status`, { status })
}

export function listAudit(params: {
  bizType?: string
  bizId?: number | string
  userId?: number | string
  from?: string
  to?: string
  page?: number
  size?: number
}): Promise<PageResponse<AuditRow>> {
  return http.get<PageResponse<AuditRow>>(`${ADMIN}/audit`, params)
}

/* ---------------- 指标字典（归属 deliver-service） ---------------- */

/**
 * 新增一版指标定义（POST /api/deliver/admin/metric-definitions）。
 * 口径变更 = 新版本（append-only）；历史数字仍按生效版本可解释。
 * 公式只允许在 platform-contract 的 MetricFormulas 里实现，这里保存的是文字口径。
 */
export function createMetricDefinition(payload: {
  code: string
  name: string
  topic?: string
  formula: string
  datasource?: string
  caliberNote?: string
}): Promise<MetricDefinition> {
  return http.post<MetricDefinition>(`${DELIVER_ADMIN}/metric-definitions`, payload)
}
