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
  // M6 质量域（PermissionMatrix 41 码；与服务端同一个码，前端不另起别名）
  | 'strategy:manage'
  | 'plan:edit'
  | 'plan:approve'
  | 'plan:execute'
  | 'plan:assign'
  | 'env:manage'
  | 'report:generate'
  | 'report:sign'
  | 'report:export'
  | 'gate:view'
  | 'waiver:apply'
  | 'waiver:approve'
  | 'review:manage'
  | 'proddefect:report'
  | 'qualitymetric:view'
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
  /** 6 项事实的签名：第 6 项加入后必须变化，否则旧结果会被误判为最新 */
  signature?: string | null
  /** M6 之前生成的 5 项单据：视图补 legacy 标记，不伪造 PASS */
  legacy?: boolean
  passed?: boolean
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
  /** 提交时固化的门禁快照（AC-5/AC-7）：回放只看它，不重算 */
  qualityGateSnapshotId?: number | null
  gateResult?: GateStatus | string | null
  /** NO-GO 已二次确认（AC-10）：再次提交不再重复要确认 */
  noGoConfirmed?: boolean
  review?: ReviewRow | null
  execution?: {
    result: 'SUCCESS' | 'FAILED' | 'ROLLED_BACK'
    startedAt?: string
    endedAt?: string
    note?: string
    defectId?: number | null
  } | null
}

/** POST `/deploy-orders/{id}/submit` 的返回；NO-GO 未确认时抛 409 NO_GO_CONFIRM_REQUIRED */
export interface DeploySubmitResult {
  status: DeployOrderStatus | string
  snapshotId?: number | null
  gateResult?: GateStatus | string | null
}

/** 409 明细里 `failedRules` 的元素：受理门禁失败时逐条给出修复指引 */
export interface AdmissionConflictDetail {
  failedRules?: GateRuleResult[]
  failedCodes?: string[]
  snapshotId?: number | null
  allowedNext?: string[]
  unmet?: Array<{ key: string; label?: string; actual?: string; expected?: string; fixHint?: string }>
  unexecutedCaseIds?: number[]
  reason?: string
}

/* ---------------- 门禁 / 准入 ---------------- */

export interface GateItem {
  code: string
  name: string
  passed: boolean
  detail?: string | null
  fixHint?: string | null
  forced?: boolean
  /** 第 6 项（QUALITY_GATE）展开的 5 条规则结果，形状与 deliver 侧 GateSnapshot 一致 */
  rules?: GateRuleResult[]
  /** 第 6 项引用的快照 id；缺失表示门禁从未求值（fail-closed） */
  snapshotId?: number | null
  /** 该条是否属于阻塞项（历史 5 项为 true） */
  required?: boolean
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

/* ================= 质量管理（M6 · TECHNICAL §5.2 / §7.3） =================
 * 唯一口径约定：
 * - 通过率/覆盖率/FPY 等比率由服务端 `MetricFormulas` 算好下发，前端**不再除一次**；
 *   分母为 0 时服务端给 null，页面渲染“—”而不是 0%（AC-8 边界）。
 * - 计划状态机只有 `StateTables.PLAN` 一份，页面渲染动作按钮只读 `allowedNext`
 *   （本文件不复制任何 stepper 顺序）。
 * - 门禁规则结果沿用 platform-contract 的 GateSnapshot 结构（code/status/required/
 *   actual/threshold/fixHint），req 与 deliver 两侧同形，避免两套字段名。
 */

/* ---------------- 测试策略（版本级，可多版本） ---------------- */

export type StrategyStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED'

export interface StrategyScope {
  requirementIds?: number[]
  moduleIds?: number[]
  caseIds?: number[]
  note?: string
}

export interface StrategyRiskRow {
  key: string
  level?: 'HIGH' | 'MEDIUM' | 'LOW' | string
  description?: string
  mitigation?: string
}

export interface StrategyRoundPlan {
  roundNo?: number
  roundType?: string
  name?: string
  scopeMode?: string
  plannedStart?: string
  plannedEnd?: string
  executorIds?: number[]
  note?: string
}

export interface StrategyRow {
  id: number
  releaseId: number
  versionNo: number
  status: StrategyStatus | string
  ownerId?: number | null
  ownerName?: string | null
  resourceNote?: string | null
  updatedAt?: string | null
  updatedBy?: number | null
}

export interface StrategyDetail extends StrategyRow {
  scope?: StrategyScope | null
  risk?: StrategyRiskRow[] | null
  envIds?: number[]
  roundPlan?: StrategyRoundPlan[] | null
  /** 策略引用的环境台账快照（服务端顺带返回，省一次请求） */
  envs?: EnvRow[]
}

/** POST/PUT `/test-strategies` 的请求体 */
export interface StrategyPayload {
  releaseId?: number
  scope?: StrategyScope
  risk?: StrategyRiskRow[]
  envIds?: number[]
  roundPlan?: StrategyRoundPlan[]
  resourceNote?: string
  ownerId?: number
}

/* ---------------- 测试计划生命周期（与 PlanViews.PlanDetail 逐字段对齐） ---------------- */

export interface PlanStrategyRef {
  id: number
  versionNo: number
  status: StrategyStatus | string
}

export interface PlanEnvRef {
  id: number
  name: string
  status: EnvStatus | string
}

export interface PlanCriterionItem {
  key: string
  op: string
  value: number | string
}

export interface PlanCriteria {
  entryCriteriaJson?: string | null
  exitCriteriaJson?: string | null
  entryItems?: PlanCriterionItem[]
  exitItems?: PlanCriterionItem[]
}

export interface PlanRoundsSummary {
  total: number
  activeRoundId?: number | null
  activeRoundNo?: number | null
  conclusion?: string | null
}

export interface PlanAssignmentSummary {
  rows: number
  plannedTotal: number
  caseTotal: number
  /** 计划量与用例总数的差额（服务端算，前端不四舍五入） */
  diff: number
  unassignedCases: number
}

/** PlanViews.Summary：计划、轮次、工作台共用同一组计数 */
export interface PlanCounts {
  total: number
  executed: number
  passed: number
  failed: number
  blocked: number
  notRun: number
  passRate: number | null
  coverage: number | null
}

export interface PlanDetail {
  id: number
  code: string
  name: string
  releaseId: number
  status: PlanStatus | string
  /** 合法动作由服务端状态表给出，stepper 与按钮只读它 */
  allowedNext: string[]
  revision: number
  gateMode: 'STRICT' | 'LOOSE' | string
  windowStart?: string | null
  windowEnd?: string | null
  ownerId?: number | null
  ownerName?: string | null
  strategy?: PlanStrategyRef | null
  env?: PlanEnvRef | null
  criteria?: PlanCriteria | null
  rounds?: PlanRoundsSummary | null
  assignment?: PlanAssignmentSummary | null
  summary?: PlanCounts | null
}

export type PlanStatus =
  | 'DRAFT'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CLOSED'
  | 'ARCHIVED'

/** PATCH `/test-plans/{id}`（APPROVED 之后必带 reason，服务端校验） */
export interface PlanUpdatePayload {
  name?: string
  windowStart?: string
  windowEnd?: string
  envId?: number | null
  entryCriteria?: unknown
  exitCriteria?: unknown
  approverId?: number | null
  reason?: string
}

/** 提交/批准/驳回/开始/完成/关闭/归档共用的动作体，字段全可选 */
export interface PlanActionPayload {
  reason?: string
  comment?: string
  approverId?: number | null
}

export interface PlanProgressByPerson {
  executorId: number
  executorName: string
  planned: number
  executed: number
  passed: number
  failed: number
  blocked: number
  passRate: number | null
}

export interface PlanProgressByModule {
  moduleId?: number | null
  moduleName?: string | null
  planned: number
  executed: number
  passed: number
  failed: number
  blocked: number
}

export interface PlanOverdueRow {
  caseId: number
  caseCode: string
  title?: string
  executorId: number
  executorName?: string
  dueDate?: string | null
  result?: string | null
}

export interface PlanProgress {
  byPerson: PlanProgressByPerson[]
  byModule: PlanProgressByModule[]
  overdue: PlanOverdueRow[]
  totals: PlanCounts
}

export interface PlanChangeLogRow {
  id: number
  revision: number
  changeType: string
  reason?: string | null
  operatorId?: number | null
  operatorName?: string | null
  approverId?: number | null
  approverName?: string | null
  createdAt?: string | null
  beforeJson?: string | null
  afterJson?: string | null
}

/* ---------------- 轮次 ---------------- */

export type RoundStatus = 'PLANNING' | 'RUNNING' | 'CLOSED'
export type RoundConclusion = 'PASS' | 'FAIL' | 'CONDITIONAL' | 'CANCELLED'

export interface RoundView {
  id: number
  planId: number
  roundNo: number
  roundType: string
  name?: string | null
  status: RoundStatus | string
  conclusion?: RoundConclusion | string | null
  startAt?: string | null
  endAt?: string | null
  caseTotal: number
  executed: number
  passed: number
  failed: number
  blocked: number
  passRate: number | null
  coverage: number | null
  active: boolean
  executorIds?: number[]
  note?: string | null
}

export interface RoundCaseRow {
  caseId: number
  caseCode: string
  title?: string | null
  result?: ExecutionResult | string | null
  executedAt?: string | null
  executorId?: number | null
  note?: string | null
}

export interface RoundDetail {
  round: RoundView
  cases: RoundCaseRow[]
  counts: PlanCounts
}

/** POST `/test-plans/{id}/rounds` */
export interface OpenRoundPayload {
  roundType: string
  name?: string
  /** ALL / RISK / CUSTOM：作用域在开轮时固化 */
  scopeMode?: string
  caseIds?: number[]
  executorIds?: number[]
  note?: string
}

/** POST `/test-plans/{id}/rounds/{roundId}/close`：结论只能下调，服务端判定 */
export interface CloseRoundPayload {
  conclusion: RoundConclusion | string
  note?: string
}

/* ---------------- 执行分配 ---------------- */

export interface AssignmentView {
  id: number
  planId: number
  roundId?: number | null
  executorId: number
  executorName?: string
  moduleId?: number | null
  moduleName?: string | null
  plannedCount: number
  dueDate?: string | null
  executedCount: number
  overdue: boolean
}

export interface AssignmentRow {
  executorId: number
  moduleId?: number | null
  plannedCount: number
  dueDate?: string | null
}

export interface AssignmentReplacePayload {
  roundId?: number | null
  rows: AssignmentRow[]
}

/* ---------------- 测试环境 ---------------- */

export type EnvStatus = 'AVAILABLE' | 'MAINTENANCE' | 'DISABLED'

export interface EnvRow {
  id: number
  name: string
  envType?: string | null
  baseUrl?: string | null
  status: EnvStatus | string
  ownerId?: number | null
  ownerName?: string | null
  note?: string | null
  /** 被进行中计划引用时返回引用计划 id（删除 409 的明细） */
  planIds?: number[]
  createdAt?: string | null
}

export interface EnvPayload {
  name: string
  envType?: string
  baseUrl?: string
  ownerId?: number | null
  note?: string
  status?: EnvStatus | string
}

/* ---------------- 测试报告（不可变版本 + 三级会签） ---------------- */

export type ReportStatus = 'SIGNING' | 'SIGNED' | 'REJECTED' | 'SUPERSEDED'
export type ReportConclusion = 'PASS' | 'FAIL' | 'CONDITIONAL'
export type SignRole = 'TEST_LEAD' | 'PRODUCT' | 'PMO'

export interface ReportRow {
  id: number
  code: string
  scope: 'PLAN' | 'RELEASE' | string
  planId?: number | null
  releaseId?: number | null
  status: ReportStatus | string
  currentVersion: number
  conclusion?: ReportConclusion | string | null
  passRate?: number | null
  coverage?: number | null
  dataCutoff?: string | null
  createdAt?: string | null
}

export interface ReportVersionRow {
  reportId?: number
  versionNo: number
  conclusion: ReportConclusion | string
  passRate: number | null
  coverage: number | null
  status: ReportStatus | string
  resultDigest?: string
  hash?: string
  generatedBy?: number | null
  generatedByName?: string | null
  generatedAt?: string | null
  signedAt?: string | null
}

/** 8 段快照：段名固定，页面按段渲染，未落地的段为 null */
export interface ReportSnapshot {
  conclusion?: ReportConclusion | string
  passRate?: number | null
  coverage?: number | null
  planRevision?: number | null
  resultDigest?: string | null
  sections?: Record<string, unknown>
  [key: string]: unknown
}

export interface ReportVersionDetail extends ReportVersionRow {
  snapshot?: ReportSnapshot | null
}

export interface ReportSignoffRow {
  signRole: SignRole | string
  signerId?: number | null
  signerName?: string | null
  decision?: 'APPROVE' | 'REJECT' | string
  comment?: string | null
  signedAt?: string | null
  orderNo?: number
}

export interface SignoffStep {
  signRole: SignRole | string
  roleName?: string
  status: 'PENDING' | 'SIGNED' | 'REJECTED' | string
  signerName?: string | null
  signedAt?: string | null
  comment?: string | null
  /** 当前轮到谁签（严格顺序，仅一步为 true） */
  current?: boolean
  /** 当前登录人是否有权签这一步 */
  canSign?: boolean
}

export interface SignoffProgress {
  reportId?: number
  versionNo?: number
  status: ReportStatus | string
  conclusion?: ReportConclusion | string | null
  steps: SignoffStep[]
}

export interface ReportDetail {
  report: ReportRow
  currentVersion: ReportVersionDetail
  versions: ReportVersionRow[]
  signoffProgress: SignoffProgress
  /** RELEASE 范围下未被任何已批准计划覆盖的需求（QG-01 的证据） */
  uncoveredRequirements?: Array<{ requirementId: number; code?: string; title?: string }>
  includedPlans?: Array<{ planId: number; code?: string; name?: string }>
}

export interface ReportGeneratePayload {
  scope: 'PLAN' | 'RELEASE'
  planId?: number | null
  releaseId?: number | null
  note?: string
}

export interface ReportSignoffPayload {
  decision: 'APPROVE' | 'REJECT'
  comment?: string
}

export interface ReportDiff {
  from?: number
  to?: number
  passRate?: { before: number | null; after: number | null }
  coverage?: { before: number | null; after: number | null }
  conclusion?: { before: string | null; after: string | null }
  addedFails?: Array<{ caseId?: number; caseCode?: string; title?: string }>
  closedDefects?: Array<{ defectId?: number; code?: string; title?: string }>
}

/* ---------------- 质量门 / 豁免 ---------------- */

export type GateStatus = 'PASS' | 'PASS_WITH_WAIVER' | 'FAIL' | 'NOT_EVALUATED'
export type GateRuleStatus = 'PASS' | 'WAIVED' | 'FAIL' | 'SKIPPED'

/** 与 platform-contract `GateSnapshot.GateRuleResult` 同形（req 准入第 6 项复用） */
export interface GateRuleResult {
  code: string
  status: GateRuleStatus | string
  required: boolean
  /** 实测值，服务端已格式化（“92.1%” / “3”），前端不再换算 */
  actual: string
  threshold: string
  /** 一条可执行的修复指引，页面直接展示 */
  fixHint: string
  /** 规则中文名（规则卡标题），缺省回退到 code */
  name?: string
}

/** 与 platform-contract `GateSnapshot` 同形 */
export interface GateSnapshot {
  snapshotId: number
  releaseId: number
  deployOrderId: number
  reportVersionId?: number | null
  result: GateStatus | string
  /** result_digest：同单同 hash 复用快照（幂等） */
  hash: string
  computedAt?: string | null
  rules: GateRuleResult[]
  openP0P1: number[]
}

export interface GateReplay extends GateSnapshot {
  diff?: { changedRules: GateRuleResult[] }
}

export interface GateRow {
  releaseId: number
  releaseName?: string | null
  gateId?: number | null
  status: GateStatus | string
  evaluatedAt?: string | null
  activeWaiverCount?: number
  reportId?: number | null
  reportVersionId?: number | null
  failedRules?: string[]
}

export interface GateRuleConfig {
  code: string
  enabled: boolean
  threshold?: number | string | null
}

export interface GateRule extends GateRuleConfig {
  name?: string
  /** QG-04 不可豁免（服务端硬判，前端只用于隐藏“申请豁免”） */
  waivable?: boolean
  status?: GateRuleStatus | string
  actual?: string
  thresholdText?: string
  fixHint?: string
  lastEvaluatedAt?: string | null
}

export interface GateDetail {
  releaseId: number
  releaseName?: string | null
  status: GateStatus | string
  rulesetVersion?: number
  evaluatedAt?: string | null
  rules: GateRule[]
  snapshots: GateSnapshot[]
  waivers: WaiverRow[]
}

export type WaiverStatus = 'PENDING' | 'ACTIVE' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'REVOKED'

export interface WaiverRow {
  id: number
  gateId?: number | null
  releaseId: number
  ruleCode: string
  ruleName?: string
  reason: string
  impactScope: string
  expiryAt?: string | null
  applicantId?: number | null
  applicantName?: string | null
  approverId?: number | null
  approverName?: string | null
  approvedAt?: string | null
  status: WaiverStatus | string
  revokedBy?: number | null
  revokedAt?: string | null
  revokeReason?: string | null
  createdAt?: string | null
}

export interface WaiverApplyPayload {
  ruleCode: string
  releaseId: number
  reason: string
  impactScope: string
  /** 1–30 天，服务端校验（AC-6 边界） */
  expiryDays: number
}

/* ---------------- 5 项质量指标 ---------------- */

export interface QualityMetricValue {
  code: string
  name: string
  /** 分母为 0 时为 null，页面显示“—” */
  value: number | null
  unit?: string | null
  version?: number | null
  formula?: string | null
  caliberNote?: string | null
  drillQuery?: Record<string, unknown> | null
}

export interface QualityMetrics {
  metrics: QualityMetricValue[]
  fpy?: number | null
  regressionPassRate?: number | null
  escapeRate?: number | null
  postReleaseDefects72h?: number | null
  rollbackRate?: number | null
}

/* ---------------- 工作台 ---------------- */

export interface MyCaseRow {
  caseId: number
  caseCode: string
  title?: string | null
  planId: number
  planCode?: string
  planName?: string
  roundId?: number | null
  roundNo?: number | null
  moduleId?: number | null
  moduleName?: string | null
  executorId?: number | null
  executorName?: string | null
  dueDate?: string | null
  result?: ExecutionResult | string | null
  overdue?: boolean
  /** 未分配给我但我负责的计划里的用例（AC-9.5） */
  unassigned?: boolean
}

/* ---------------- 版本质量 ---------------- */

export interface ReleaseQualityCounts {
  fpy?: number | null
  escape?: number | null
  postRelease?: number | null
  rollback?: number | null
}

export interface ReleaseQuality {
  releaseId: number
  gateHistory: GateSnapshot[]
  reports: ReportRow[]
  openP0P1: Array<{
    defectId?: number
    id?: number
    code?: string
    title?: string
    severity?: string
    status?: string
  }>
  escapeDefects: Array<{
    defectId?: number
    id?: number
    code?: string
    title?: string
    severity?: string
    foundAt?: string | null
  }>
  counts: ReleaseQualityCounts
}

/* ---------------- Go/No-Go 评审（req 域，TECHNICAL §5.3） ---------------- */

export type ReviewDecision = 'GO' | 'CONDITIONAL' | 'NO_GO'
export type ReviewStatus = 'RECORDED' | 'CONFIRMED'

export interface ReviewRow {
  id: number
  releaseId: number
  deployOrderId?: number | null
  gateSnapshotId?: number | null
  reportId?: number | null
  reportVersionNo?: number | null
  meetingAt: string
  decision: ReviewDecision | string
  conditions?: string | null
  gateEvaluated?: boolean
  status: ReviewStatus | string
  createdBy?: number | null
  createdAt?: string | null
  confirmedBy?: number | null
  confirmedAt?: string | null
}

export interface ReviewDetail extends ReviewRow {
  participants?: Array<{ userId?: number; name?: string; role?: string }>
  risks?: Array<{ key?: string; level?: string; note?: string }>
  openDefects?: Array<{ id?: number; code?: string; severity?: string; title?: string }>
  docPath?: string | null
  gateSnapshot?: GateSnapshot | null
}

export interface ReviewCreatePayload {
  meetingAt: string
  decision: ReviewDecision
  deployOrderId?: number | null
  reportId?: number | null
  reportVersionNo?: number | null
  conditions?: string
  participants?: Array<{ userId?: number; name?: string; role?: string }>
  risks?: Array<{ key?: string; level?: string; note?: string }>
  openDefects?: Array<{ id?: number; code?: string; severity?: string; title?: string }>
  docPath?: string
}

/** PATCH `/releases/{id}/quality-settings`：只改未来，不改历史统计 */
export interface ReleaseQualitySettings {
  observationHours?: number
  reportValidityDays?: number
}
