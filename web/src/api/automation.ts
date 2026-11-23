/**
 * 自动化规则引擎端点封装（TECHNICAL §7 / §9）。
 *
 * 页面只调用这里的方法：路径、枚举与错误码解释集中在同一处，服务端契约变化时
 * 只有本文件要改。四视图（规则列表 / 编辑器 / 执行日志 / 需求标签）共用。
 *
 * 三条硬约定：
 * 1. 没有 DELETE，也没有 /events —— 停用即永久保留，事件只由服务端审计旁路产出；
 * 2. 枚举一律来自 `GET /automation/meta`（模块级缓存，会话内只拉一次），
 *    前端不硬编码触发/动作/条件键/状态码；
 * 3. 409 / 400 原样抛 `ApiError`，由页面渲染常驻条与字段错误，不在此吞掉。
 */
import { http } from './client'
import type { PageResponse } from './types'

const AUTOMATION = '/api/req/automation'

/** 四类触发（TECHNICAL §2.1；值域最终以 meta 为准） */
export type AutomationTrigger =
  | 'REQUIREMENT_CREATED'
  | 'STATUS_CHANGE'
  | 'FIELD_CHANGE'
  | 'SCHEDULE'

/** 四类允许动作（白名单；SET_STATUS / APPROVE / CREATE_RELEASE 之类在服务端 400） */
export type AutomationActionType = 'NOTIFY' | 'ASSIGN_OWNER' | 'ADD_TAG' | 'WEBHOOK'

export type RecipientKind = 'USER' | 'ROLE' | 'DOMAIN_OWNER'

/** 人天比较符（PRD 3.3；meta.estimateOps 给同序候选） */
export type EstimateOp = 'gt' | 'gte' | 'lt' | 'lte' | 'eq'

export interface EstimateCondition {
  op: EstimateOp | string
  value: number
}

/**
 * 条件（封闭 schema，空对象 = 无条件）。
 * 键全部可选；数组内 OR、键之间 AND，字段缺失即不命中。
 */
export interface AutomationCondition {
  types?: string[]
  priorityIn?: string[]
  statusIn?: string[]
  approvalResults?: string[]
  estimateDays?: EstimateCondition | null
  domainIds?: number[]
  vendorIds?: number[]
  fields?: string[]
}

export interface AutomationRecipient {
  kind: RecipientKind | string
  id?: number
  code?: string
}

export interface AutomationAction {
  type: AutomationActionType | string
  recipients?: AutomationRecipient[]
  userId?: number
  tag?: string
  configId?: number
  path?: string
}

/** 规则视图 = 服务端 `RuleView`（version 是规则自身乐观锁，不是发版版本） */
export interface AutomationRule {
  id: number
  name: string
  enabled: boolean
  triggerType: string
  condition?: AutomationCondition | null
  actions?: AutomationAction[] | null
  scopeType: string
  scopeId?: number | null
  scopeLabel?: string | null
  priority: number
  version: number
  createdBy?: number | null
  createdByName?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  lastRun?: AutomationRunBrief | null
}

export interface AutomationRunBrief {
  at?: string | null
  status?: string | null
  skipReason?: string | null
}

/** 提交体 = 服务端 `RuleCmd`（编辑时 version 必带，用于乐观锁） */
export interface AutomationRuleCmd {
  name: string
  enabled: boolean
  triggerType: string
  scopeType: string
  scopeId: number | null
  priority: number
  condition: AutomationCondition
  actions: AutomationAction[]
  version?: number
}

export interface AutomationRunAction {
  type: string
  status: string
  message?: string | null
  target?: string | null
}

/** 执行日志行 = 服务端 `RunView`（日志只回规则名/需求编号/逐动作结果，不含正文与完整 URL） */
export interface AutomationRun {
  id: number
  ruleId: number
  ruleName?: string | null
  ruleVersion?: number | null
  eventType?: string | null
  eventId?: string | null
  requirementId?: number | null
  requirementCode?: string | null
  status: string
  skipReason?: string | null
  actions?: AutomationRunAction[] | null
  durationMs?: number | null
  createdAt?: string | null
  /** 事件链深度（列表契约未强制；缺失时展开区显示 —，不臆造） */
  chainDepth?: number | null
}

export interface AutomationMetaTrigger {
  code: string
  label: string
}

export interface AutomationWebhookConfig {
  id: number
  system?: string
  name: string
  host?: string
}

/** 枚举真值来源：触发 / 动作 / 条件键 / 人天比较符 / 字段 / 需求状态 / webhook 配置 */
export interface AutomationMeta {
  triggers: AutomationMetaTrigger[]
  actions: AutomationMetaTrigger[]
  conditionKeys: string[]
  estimateOps: string[]
  fields: string[]
  statuses: string[]
  webhookConfigs: AutomationWebhookConfig[]
}

export interface AutomationRuleListQuery {
  [key: string]: unknown
  keyword?: string
  triggerType?: string
  /** 'true' / 'false'；空串由 client 的 buildQuery 丢掉 */
  enabled?: string
  page?: number
  size?: number
  /** `priority,asc` / `priority,desc`（服务端未声明该参数时退化为页内排序） */
  sort?: string
}

export interface AutomationRunListQuery {
  [key: string]: unknown
  ruleId?: number | string
  status?: string
  requirementCode?: string
  /** yyyy-MM-dd（Asia/Shanghai 的自然日） */
  from?: string
  to?: string
  page?: number
  size?: number
}

/** 作用域二选一（PRD 3.4） */
export const SCOPE_TYPES = [
  { code: 'DOMAIN', label: '域' },
  { code: 'VENDOR', label: '厂商' },
] as const

export function listRules(query: AutomationRuleListQuery): Promise<PageResponse<AutomationRule>> {
  return http.get<PageResponse<AutomationRule>>(`${AUTOMATION}/rules`, query)
}

export function getRule(id: number | string): Promise<AutomationRule> {
  return http.get<AutomationRule>(`${AUTOMATION}/rules/${id}`)
}

export function createRule(cmd: AutomationRuleCmd): Promise<AutomationRule> {
  return http.post<AutomationRule>(`${AUTOMATION}/rules`, cmd)
}

export function updateRule(id: number | string, cmd: AutomationRuleCmd): Promise<AutomationRule> {
  return http.put<AutomationRule>(`${AUTOMATION}/rules/${id}`, cmd)
}

/** 启停也带 version：防止「停用」覆盖别人刚改的条件 */
export function setRuleEnabled(
  id: number | string,
  enabled: boolean,
  version: number,
): Promise<AutomationRule> {
  return http.patch<AutomationRule>(`${AUTOMATION}/rules/${id}/enabled`, { enabled, version })
}

export function listRuns(query: AutomationRunListQuery): Promise<PageResponse<AutomationRun>> {
  return http.get<PageResponse<AutomationRun>>(`${AUTOMATION}/runs`, query)
}

export function getRun(id: number | string): Promise<AutomationRun> {
  return http.get<AutomationRun>(`${AUTOMATION}/runs/${id}`)
}

/**
 * meta 缓存：模块级 Promise，会话内只拉一次（TECHNICAL §9.3）。
 * 失败时清空缓存，允许下一次进入页面重试；不缓存失败。
 */
let metaPromise: Promise<AutomationMeta> | null = null

function normalizeMeta(raw: AutomationMeta | null | undefined): AutomationMeta {
  return {
    triggers: raw?.triggers ?? [],
    actions: raw?.actions ?? [],
    conditionKeys: raw?.conditionKeys ?? [],
    estimateOps: raw?.estimateOps ?? [],
    fields: raw?.fields ?? [],
    statuses: raw?.statuses ?? [],
    webhookConfigs: raw?.webhookConfigs ?? [],
  }
}

export function getMeta(): Promise<AutomationMeta> {
  if (!metaPromise) {
    metaPromise = http
      .get<AutomationMeta>(`${AUTOMATION}/meta`, undefined, { silentForbidden: true })
      .then(normalizeMeta)
      .catch((error: unknown) => {
        metaPromise = null
        throw error
      })
  }
  return metaPromise
}

/** 测试/登出用：丢弃缓存（换租户 = 重新登录 = 整页刷新，正常路径不需要） */
export function resetMetaCache(): void {
  metaPromise = null
}

export function triggerLabel(meta: AutomationMeta | null, code?: string | null): string {
  if (!code) return '—'
  return meta?.triggers.find((item) => item.code === code)?.label ?? code
}

export function actionLabel(meta: AutomationMeta | null, code?: string | null): string {
  if (!code) return '—'
  return meta?.actions.find((item) => item.code === code)?.label ?? code
}

export function webhookConfigLabel(config: AutomationWebhookConfig): string {
  const host = config.host ? ` · ${config.host}` : ''
  return `${config.name}${host}`
}

/** 409 冲突里的服务端当前版本（`detail.currentVersion`），缺失时返回 null */
export function currentVersionOf(detail: unknown): number | null {
  if (!detail || typeof detail !== 'object') return null
  const record = detail as Record<string, unknown>
  const raw = record.currentVersion ?? record.current_version
  const value = Number(raw)
  return Number.isFinite(value) && value > 0 ? Math.floor(value) : null
}

/** 400 校验错误里的字段路径（`detail.field`），缺失时返回 null */
export function fieldOf(detail: unknown): string | null {
  if (!detail || typeof detail !== 'object') return null
  const record = detail as Record<string, unknown>
  const raw = record.field ?? record.path
  return typeof raw === 'string' && raw ? raw : null
}
