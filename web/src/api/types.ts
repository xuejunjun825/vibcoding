/**
 * 与 req-service / deliver-service 的契约类型（TECHNICAL §4）。
 * 只声明前端实际读取的字段；服务端多返回的字段不影响类型兼容。
 */

/* ---------------- 通用 ---------------- */

export interface PageResponse<T> {
  items: T[]
  total: number
  page: number
  size: number
  nextCursor?: string | null
}

export interface PageQuery {
  page?: number
  size?: number
}

/** 后端错误体：{status,code,message,detail}（TECHNICAL §4.2） */
export interface ApiErrorBody {
  status?: number
  code?: string
  message?: string
  detail?: unknown
  traceId?: string
}

export type FunctionCode =
  | 'tenant:manage'
  | 'org:manage'
  | 'req:report:bus'
  | 'req:report:dev'
  | 'req:review'
  | 'req:split'
  | 'release:plan'
  | 'req:assign-vendor'
  | 'req:deliver'
  | 'test:quick'
  | 'deploy:create'
  | 'deploy:execute'
  | 'deploy:force'
  | 'defect:report'
  | 'defect:handle'
  | 'defect:board'
  | 'task:manage'
  | 'case:manage'
  | 'plan:manage'
  | 'trace:view'
  | 'metric:manage'
  | 'workflow:manage'
  | 'apiclient:manage'
  | 'export:detail'
  | 'audit:view'

/* ---------------- 认证 ---------------- */

export interface LoginPayload {
  tenantCode: string
  username: string
  password: string
}

export interface SessionUser {
  id: number
  name: string
  roles: string[]
  domainIds: number[]
  vendorId?: number | null
  tenantId?: number
  tenantName?: string
}

export interface LoginResponse {
  token: string
  expiresIn: number
  user: SessionUser
  permissions: Array<string | { code: string; access?: 'ALLOW' | 'RESTRICTED' | 'DENY' }>
  defaultRoute: string
  tenantId?: number
  tenantCode?: string
}

export interface ScopeInfo {
  type?: 'ALL' | 'DOMAIN' | 'VENDOR' | 'ORG' | 'SELF' | string
  domainIds: number[]
  vendorId?: number | null
  orgIds?: number[]
  label?: string
}

export interface MeResponse {
  /** 服务端可能有嵌套 {user, scope} 或扁平两种形态，两种都要认 */
  user?: SessionUser
  id?: number
  name?: string
  roles?: string[]
  domainIds?: number[]
  vendorId?: number | null
  permissions?: unknown[]
  scope?: ScopeInfo
}

/* ---------------- 通知 ---------------- */

export interface NotificationRow {
  id: number
  type: string
  title: string
  content?: string
  bizType?: string
  bizId?: number
  readFlag: boolean | number
  createdAt: string
}

/* ---------------- 需求 ---------------- */

export type RequirementType = 'BUS' | 'PDR' | 'RD'
export type Priority = 'P0' | 'P1' | 'P2' | 'P3'

export interface RequirementRow {
  id: number
  code: string
  type: RequirementType
  status: string
  priority: Priority
  title: string
  description?: string
  domainId?: number
  domainName?: string
  vendorId?: number | null
  vendorName?: string | null
  ownerId?: number | null
  ownerName?: string | null
  parentId?: number | null
  releaseId?: number | null
  releaseName?: string | null
  expectedDeliveryDate?: string | null
  childCount?: number
  doneCount?: number
  estimateDays?: number | null
  actualDays?: number | null
  risk?: boolean
  summary?: boolean
  updatedAt?: string
  createdAt?: string
}

export interface RequirementRollup {
  childCount: number
  doneCount: number
  estimateDays: number
  actualDays: number
}

export interface RequirementDelivery {
  mrUrl?: string | null
  estimateDays?: number | null
  actualDays?: number | null
  owner?: { id: number; name: string } | null
}

/**
 * GET/详情类端点里 `RequirementViews.View` 的扁平形状（已归一）。
 * 注意：线上 `GET /requirements/{id}` 外面还包了一层，见 {@link RequirementDetailResponse}；
 * 页面统一通过 `normalizeRequirementDetail` 转成本类型，不要在页面里直接摊平。
 */
export interface RequirementDetail extends RequirementRow {
  domain?: { id: number; name: string } | null
  parent?: { id: number; code: string; title: string } | null
  children: RequirementRow[]
  rollup?: RequirementRollup
  delivery?: RequirementDelivery
  release?: { id: number; name: string; status?: string } | null
  /** 服务端唯一事实源：只渲染这里给出的动作（DESIGN §10 / AC-3） */
  allowedActions: string[]
  permissions?: { fields?: string[] }
  attachments?: AttachmentRow[]
}

/**
 * `GET /api/req/requirements/{id}` 的**真实线上形状**（RequirementViews.Detail）：
 * `{view, children, allowedActions, permissions}`。页面不要声明成扁平；
 * 需要页面形状时走 `normalizeRequirementDetail()`。
 */
export interface RequirementDetailResponse {
  view: RequirementDetail
  children: RequirementRow[]
  allowedActions: string[]
  permissions?: { fields?: string[] }
}

/** 创建/拆解返回 `{id,code,type,status,allowedActions}`（不是详情）。 */
export interface RequirementCreated {
  id: number
  code: string
  type: RequirementType
  status: string
  allowedActions: string[]
}

export interface CreateRequirementPayload {
  type: RequirementType
  title: string
  description: string
  domainIds: number[]
  priority: Priority
  expectedDeliveryDate?: string | null
  parentId?: number | null
  attachments?: number[]
}

export interface ActionPayload {
  action: string
  comment?: string
}

export interface ActionResponse {
  status: string
  allowedActions: string[]
}

export interface AttachmentRow {
  id: number
  fileName: string
  size?: number
  uploadedBy?: string
  createdAt?: string
}

/* ---------------- 审批 ---------------- */

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'CANCELLED' | 'SKIPPED'

export interface ApprovalTimelineEvent {
  action: string
  operator: string
  comment?: string | null
  at: string
  origin?: 'SIGN' | 'ADD_SIGN' | 'REMOVE_SIGN' | 'TRANSFER' | 'WITHDRAW' | 'URGE' | null
}

export interface ApprovalTaskRow {
  id: number
  nodeId?: number
  nodeName: string
  assignee: { id: number; name: string }
  status: ApprovalStatus
  comment?: string | null
  handledAt?: string | null
}

export interface ApprovalInstanceDetail {
  instance: {
    id: number
    definitionId?: number
    definitionVersion: number
    status: ApprovalStatus
    currentNode: string | null
    initiator?: { id: number; name: string } | null
    startedAt?: string
  }
  document: {
    bizType: string
    bizId: number
    summary: Record<string, unknown> | string
  }
  nodes: Array<{ id: number; name: string; mode?: string; status?: string }>
  tasks: ApprovalTaskRow[]
  timeline: ApprovalTimelineEvent[]
}

export interface ApprovalTodoRow {
  taskId: number
  instanceId: number
  bizType: string
  bizId: number
  summary: string
  nodeName: string
  arrivedAt: string
  dueAt?: string | null
  initiator?: string
}

/* ---------------- 版本 / 发布单 ---------------- */

export type ReleaseStatus = 'PLAN' | 'LOCKED' | 'PUBLISHED' | 'CANCELLED'

export interface ReleaseRow {
  id: number
  code: string
  name: string
  status: ReleaseStatus
  domainId?: number
  domainName?: string
  startDate?: string
  endDate?: string
  plannedReleaseDate?: string
  gateMode?: 'STRICT' | 'LOOSE'
  lockedAt?: string | null
  releasedAt?: string | null
  /** 服务端给出的可执行动作（release:plan 权限下的 lock/publish/…），页面只渲染这些 */
  allowedActions?: string[]
  requirementCount?: number
  doneCount?: number
  riskCount?: number
}

export interface ReleaseDetail {
  release: ReleaseRow
  requirements: RequirementRow[]
  stats: { total: number; done: number; estimateDays: number; actualDays: number }
  riskItems: RequirementRow[]
}

export type DeployOrderStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'REJECTED'
  | 'EXECUTING'
  | 'SUCCESS'
  | 'FAILED'
  | 'ROLLED_BACK'

export interface AdmissionSnapshot {
  items: GateItem[]
  checkedAt?: string | null
  stale?: boolean
}

export interface DeployOrderRow {
  id: number
  code: string
  releaseId: number
  releaseName?: string
  env: string
  status: DeployOrderStatus
  windowStart?: string
  windowEnd?: string
  artifactVersion?: string
  ownerId?: number
  ownerName?: string
  createdAt?: string
}

export interface DeployOrderDetail extends DeployOrderRow {
  steps?: unknown
  rollbackPlan?: string
  impact?: string
  admission?: AdmissionSnapshot
  forcedRelease?: { reason: string; by: string; at: string } | null
  execution?: {
    result: 'SUCCESS' | 'FAILED' | 'ROLLED_BACK'
    startedAt?: string
    endedAt?: string
    note?: string
    defectId?: number | null
  } | null
}

/* ---------------- 门禁 / 准入 ---------------- */

export interface GateItem {
  code: string
  name: string
  passed: boolean
  detail?: string | null
  fixHint?: string | null
  forced?: boolean
}

export interface GateBlocker {
  caseId?: number
  caseCode?: string
  caseVersion?: number
  result?: string
  note?: string | null
  name?: string
  detail?: string
}

export interface GateDecision {
  passed: boolean
  mode: 'STRICT' | 'LOOSE' | string
  reason?: string
  blockers?: GateBlocker[]
  items?: GateItem[]
}

/* ---------------- 缺陷 / 任务 / 迭代 ---------------- */

export type DefectStatus = 'NEW' | 'IN_PROGRESS' | 'PENDING_VERIFY' | 'CLOSED'
export type Severity = 'P0' | 'P1' | 'P2' | 'P3'

export interface DefectRow {
  id: number
  code: string
  title: string
  status: DefectStatus
  severity: Severity
  priority?: Priority
  domainId?: number
  vendorId?: number | null
  requirementId?: number | null
  requirementCode?: string
  releaseId?: number | null
  handlerId?: number | null
  handlerName?: string | null
  reporterName?: string
  reopenCount?: number
  foundAt?: string
  closedAt?: string | null
  expectedFixDate?: string | null
  testCaseCode?: string
  testCaseVersion?: number
  summary?: boolean
}

export interface DefectDetail extends DefectRow {
  description?: string
  reproSteps?: string
  attachments?: AttachmentRow[]
  allowedActions?: string[]
}

export interface TaskRow {
  id: number
  code?: string
  requirementId: number
  title: string
  status: string
  assigneeId?: number | null
  assigneeName?: string | null
  estimateHours?: number
  startDate?: string
  dueDate?: string
  sortNo?: number
}

export interface SprintBoard {
  columns: Array<{ key: string; name: string }>
  lanes: Array<{ id: number; name: string }>
  tasks: TaskRow[]
}

/* ---------------- 用例 / 计划 ---------------- */

export interface TestCaseRow {
  id: number
  code: string
  title: string
  moduleId?: number
  moduleName?: string
  type?: string
  priority?: Priority
  currentVersion?: number
  automated?: boolean
  requirementId?: number | null
  updatedAt?: string
}

export interface CaseStep {
  action: string
  expected: string
}

export interface TestCaseDetail extends TestCaseRow {
  version: number
  precondition?: string
  steps: CaseStep[]
  tags?: string[]
  scriptRef?: string
  readonly?: boolean
}

export interface CaseVersionRow {
  versionNo: number
  createdAt: string
  createdBy?: string
  current?: boolean
  note?: string
}

export type ExecutionResult = 'NOT_RUN' | 'PASS' | 'FAIL' | 'BLOCKED' | 'SKIP'

export interface PlanCaseRow {
  caseId: number
  caseCode: string
  caseVersion: number
  title?: string
  result: ExecutionResult
  executedAt?: string | null
  note?: string | null
  /** MANUAL / AUTO：自动上报的行才带 clientId/runId（后端 TestExecutionEntity） */
  source?: 'MANUAL' | 'AUTO' | string
  clientId?: number | null
  runId?: string | null
}

/** `/test-modules` 模块树节点（≤3 层，level 由服务端算，前端不猜）。 */
export interface TestModuleRow {
  id: number
  parentId?: number | null
  name: string
  path?: string
  level?: number
  domainId?: number | null
  vendorId?: number | null
  sortNo?: number
}

export interface PlanSummary {
  planned: number
  executed: number
  passed: number
  failed: number
  blocked: number
  skip: number
  notRun: number
  passRate: number
  executedPassRate: number
  coverage: number
  complete: boolean
}

export interface TestPlanDetail {
  plan: {
    id: number
    code: string
    name: string
    releaseId?: number
    status?: string
    gateMode?: 'STRICT' | 'LOOSE'
    windowStart?: string
    windowEnd?: string
    ownerName?: string
  }
  cases: PlanCaseRow[]
  summary: PlanSummary
}

/* ---------------- 追溯 ---------------- */

export interface TraceCell {
  state: 'COMPLETE' | 'GAP' | 'NA'
  label?: string
  count?: number
}

export interface TraceRow {
  requirementId: number
  requirementCode: string
  title: string
  type?: RequirementType
  domainName?: string
  vendorName?: string | null
  cells: Record<string, TraceCell>
  gaps: string[]
  complete: boolean
}

/* ---------------- 指标 ---------------- */

export interface MetricDefinition {
  id?: number
  code: string
  name: string
  /** 指标主题（缺陷/测试/交付…），后端 Dictionary 会带上 */
  topic?: string
  formula: string
  /** 数据源与取数口径（展示用，计算只在 MetricFormulas） */
  datasource?: string
  caliberNote?: string
  version?: number
  effectiveAt?: string | null
  ownerName?: string
  updatedAt?: string
}

export interface BoardMetrics {
  metrics: {
    openHigh: number
    density: number | string
    reopenRate: number | string
    mttrMinutes: number | string
  }
  definitions: MetricDefinition[]
  drill?: { path: string; params?: Record<string, string> }
}

export interface TestPassMetrics {
  passRate: number
  executedPassRate: number
  coverage: number
  definition?: MetricDefinition
}

/* ---------------- 管理 ---------------- */

export interface PermissionCell {
  code: string
  allow: string[]
  restricted: string[]
}

export interface PermissionMatrixResponse {
  functions: PermissionCell[]
  roles: Array<string | { code: string; name: string }>
}

export interface AuditRow {
  id: number
  action: string
  bizType?: string
  bizId?: number
  userName?: string
  detail?: unknown
  createdAt: string
}

export interface ApiClientRow {
  id: number
  name: string
  appKey: string
  scopes?: string[]
  ipWhitelist?: string
  rateLimitQps?: number
  status: string
  createdAt?: string
}
