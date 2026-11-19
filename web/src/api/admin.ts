/**
 * 系统管理端点封装（TECHNICAL §4.3「认证与管理员」）。
 * 这些页面由 T9 实现，但接口层在基座里先收敛好，避免每页各拼一次 URL。
 */
import { http } from './client'
import type {
  ApiClientRow,
  AuditRow,
  FlowBindingSlot,
  GrantInput,
  GrantSaveResult,
  MetricDefinition,
  PageResponse,
  PermissionMatrixResponse,
  RoleGrantView,
  RoleRow,
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

/**
 * 删除组织（r18 新增，AC-14）。
 *
 * 有子组织（409 ORG_HAS_CHILDREN）或有人员（409 ORG_HAS_USERS）时后端拒绝，
 * detail 里带 counts —— 页面就该就地引导「去迁移」，绝不吞成 toast。
 */
export function deleteOrg(id: number): Promise<void> {
  return http.del<void>(`${ADMIN}/orgs/${id}`)
}

/** 把人员迁到目标组织后再删源组织（同事务 + 审计） */
export function moveOrgUsers(
  id: number,
  body: { userIds: number[]; targetOrgId: number },
): Promise<{ moved: number }> {
  return http.post<{ moved: number }>(`${ADMIN}/orgs/${id}/move-users`, body)
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

/* ---------------- 发布等级流程绑定（r30，复用 workflow:manage） ----------------
 * 6 个槽 = bizType(RELEASE/REQ_DEPLOY) × releaseLevel(NORMAL/EMERGENCY/ROLLBACK)。
 * DELETE 是「恢复默认」：服务端删掉绑定行，解析回落到默认定义，不复制默认 id。
 */

export function listFlowBindings(): Promise<FlowBindingSlot[]> {
  return http.get<FlowBindingSlot[]>(`${ADMIN}/flow-bindings`)
}

export function saveFlowBinding(
  bizType: string,
  level: string,
  definitionId: number,
): Promise<FlowBindingSlot> {
  return http.put<FlowBindingSlot>(`${ADMIN}/flow-bindings/${bizType}/${level}`, { definitionId })
}

export function resetFlowBinding(bizType: string, level: string): Promise<FlowBindingSlot> {
  return http.del<FlowBindingSlot>(`${ADMIN}/flow-bindings/${bizType}/${level}`)
}

export function listApiClients(
  params: { page?: number; size?: number },
  options?: { silentForbidden?: boolean },
): Promise<PageResponse<ApiClientRow>> {
  // 第二参只给「非管理页借读凭证名」用（执行器台账）：调用点声明 silentForbidden 后，
  // 403 只降级为空表，不再由 client.ts 全局跳 /403（D-01）；管理面自己不传，行为不变。
  return http.get<PageResponse<ApiClientRow>>(`${ADMIN}/api-clients`, params, options)
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

/* ---------------- r18 RBAC：角色与三层授权（归属 req-service） ---------------- */

export function listRoles(): Promise<RoleRow[]> {
  return http.get<RoleRow[]>(`${ADMIN}/roles`)
}

export function createRole(body: {
  code: string
  name: string
  description?: string
}): Promise<RoleRow> {
  return http.post<RoleRow>(`${ADMIN}/roles`, body)
}

export function updateRole(
  id: number,
  body: { name: string; description?: string },
): Promise<RoleRow> {
  return http.put<RoleRow>(`${ADMIN}/roles/${id}`, body)
}

/** 停用/启用：停用即时失权（后端在主体装配处过滤 status='ACTIVE'） */
export function setRoleStatus(id: number, status: string): Promise<RoleRow> {
  return http.patch<RoleRow>(`${ADMIN}/roles/${id}/status`, { status })
}

/** 内置角色不可删（409）；删除前页面必须二次确认 */
export function deleteRole(id: number): Promise<void> {
  return http.del<void>(`${ADMIN}/roles/${id}`)
}

/** 三级授权快照：模块 → 页面 → (功能码, 动作) 全量 1680 行 */
export function getRoleGrants(id: number): Promise<RoleGrantView> {
  return http.get<RoleGrantView>(`${ADMIN}/roles/${id}/permissions`)
}

/**
 * 全量覆盖保存（一次 PUT 写满 1680 行 + 1 条审计）。
 * 409 `ADMIN_LOCKOUT_RISK` / `HIGH_RISK_REQUIRES_CONFIRM` 由页面渲染，不要 toast。
 */
export function saveRoleGrants(
  id: number,
  body: { grants: GrantInput[]; confirmHighRisk?: boolean },
): Promise<GrantSaveResult> {
  return http.put<GrantSaveResult>(`${ADMIN}/roles/${id}/permissions`, body)
}
