/**
 * CCode 分支策略端点封装（TECHNICAL §2.5 / §4.4）。
 * 核验状态只由服务端判定：没有核验记录 = UNVERIFIED，前端不推断、不置绿。
 */
import { http } from './client'

const REQ = '/api/req'

export type PolicyPatternType = 'GLOB' | 'REGEX'
export type PolicyMergeMethod = 'MERGE' | 'SQUASH' | 'FF_ONLY'
export type PolicyCheckResult = 'MATCH' | 'DRIFT' | 'UNVERIFIED'

/** 未核验/不可达的服务端原因枚举（tooltip 文案的唯一来源见 PolicyCheckPill） */
export type PolicyCheckReason =
  | 'INTEGRATION_DISABLED'
  | 'INTEGRATION_UNREACHABLE'
  | 'AUTH_FAILED'
  | 'TIMEOUT'
  | 'API_UNSUPPORTED'
  | 'PERMISSION_INSUFFICIENT'

export interface PolicyDiffItem {
  /** 检查项：保护分支 / 合并方式 / 审批人数 / 推送角色 */
  item: string
  expected?: string | null
  actual?: string | null
  ok: boolean
  /** 期望/实际的取证来源（GitLab 接口名或字段） */
  source?: string | null
}

export interface PolicyCheckView {
  id?: number | null
  result: PolicyCheckResult | string
  reason?: PolicyCheckReason | string | null
  items?: PolicyDiffItem[] | null
  checkedAt?: string | null
  checkedByName?: string | null
}

export interface PolicyRow {
  id: number
  domainId: number
  domainName?: string | null
  gitlabProject: string
  branchPattern: string
  patternType: PolicyPatternType
  protected: boolean
  mergeMethod: PolicyMergeMethod
  approvalsRequired: number
  pushRoles: string[]
  mergeRoles: string[]
  enabled: boolean
  version: number
  /** 服务端派生的最新核验态；三个可能的字段名都兼容，缺失即未核验 */
  latestCheck?: PolicyCheckView | null
  lastCheck?: PolicyCheckView | null
  check?: PolicyCheckView | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface PolicyQuery {
  domainId?: number | null
  project?: string
  enabled?: boolean | null
}

export interface PolicyBody {
  id?: number
  domainId: number
  gitlabProject: string
  branchPattern: string
  patternType: PolicyPatternType
  protected: boolean
  mergeMethod: PolicyMergeMethod
  approvalsRequired: number
  pushRoles: string[]
  mergeRoles: string[]
  enabled: boolean
  version?: number
}

export const POLICY_RESULT_LABEL: Record<string, string> = {
  MATCH: '一致',
  DRIFT: '有差异',
  UNVERIFIED: '未核验',
}

export const POLICY_REASON_LABEL: Record<string, string> = {
  INTEGRATION_DISABLED: 'GitLab 未启用',
  INTEGRATION_UNREACHABLE: 'GitLab 不可达',
  AUTH_FAILED: '鉴权失败（Token 失效或权限不足）',
  TIMEOUT: '请求超时',
  API_UNSUPPORTED: 'GitLab 版本不支持该接口',
  PERMISSION_INSUFFICIENT: '权限不足（需 Maintainer 及以上）',
}

/** 行上的最新核验态：无记录 → UNVERIFIED（永不为绿） */
export function latestCheckOf(row: PolicyRow): PolicyCheckView {
  return row.latestCheck ?? row.lastCheck ?? row.check ?? { result: 'UNVERIFIED' }
}

function unwrapList<T>(raw: T[] | { items?: T[]; records?: T[] } | null | undefined): T[] {
  if (Array.isArray(raw)) return raw
  if (raw && Array.isArray(raw.items)) return raw.items
  if (raw && Array.isArray(raw.records)) return raw.records
  return []
}

export async function fetchPolicies(q: PolicyQuery = {}): Promise<PolicyRow[]> {
  const raw = await http.get<PolicyRow[] | { items?: PolicyRow[]; records?: PolicyRow[] }>(
    `${REQ}/code/policies`,
    {
      domainId: q.domainId ?? undefined,
      project: q.project || undefined,
      enabled: q.enabled ?? undefined,
    },
  )
  return unwrapList(raw)
}

export function savePolicy(body: PolicyBody): Promise<PolicyRow> {
  return body.id
    ? http.put<PolicyRow>(`${REQ}/code/policies/${body.id}`, body)
    : http.post<PolicyRow>(`${REQ}/code/policies`, body)
}

export function deletePolicy(id: number): Promise<void> {
  return http.del<void>(`${REQ}/code/policies/${id}`)
}

/**
 * 核验：唯一外呼入口。集成未启用/不可达时后端返回 409 并已落 UNVERIFIED 记录，
 * 调用方按 code 渲染为未核验（client 对 409 不弹 toast）。
 */
export async function checkPolicy(id: number): Promise<PolicyCheckView> {
  const raw = await http.post<PolicyCheckView | { check?: PolicyCheckView; result?: string }>(
    `${REQ}/code/policies/${id}/check`,
    {},
  )
  if (raw && typeof raw === 'object' && 'check' in raw && raw.check) return raw.check
  return raw as PolicyCheckView
}

export async function fetchChecks(id: number, limit = 20): Promise<PolicyCheckView[]> {
  const raw = await http.get<PolicyCheckView[] | { items?: PolicyCheckView[] }>(
    `${REQ}/code/policies/${id}/checks`,
    { limit },
  )
  return unwrapList(raw)
}
