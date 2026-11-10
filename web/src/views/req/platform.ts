import { ref } from 'vue'
import { loadDictionaries } from '@/api/dictionaries'
import type { DictionaryDomain, DictionaryUser, DictionaryVendor } from '@/api/dictionaries'
import type { ApprovalTimelineEvent, RequirementDetail, RequirementRow } from '@/api/types'

/**
 * 需求/版本/审批/发布四个域共用的展示字典。
 *
 * 设计约束（DESIGN §1.7）：状态与动作的中文文案只在 `stores/dict.ts` 一份，
 * 这里只补平台侧的枚举（需求类型、动作→权限），不复制状态词典。
 */

export const REQUIREMENT_TYPES = [
  { value: 'BUS', label: '业务需求', description: '业务方提出，需评审与产品分析后拆解' },
  { value: 'PDR', label: '产品需求', description: '产品负责人提出，可继续拆解研发需求' },
  { value: 'RD', label: '研发需求', description: '可直接进入开发、测试与交付' },
] as const

export const PRIORITIES = ['P0', 'P1', 'P2', 'P3'] as const

export function typeLabel(type?: string | null): string {
  return REQUIREMENT_TYPES.find((item) => item.value === type)?.label ?? (type || '—')
}

export function typeTone(type?: string | null): 'bus' | 'pdr' | 'rd' {
  if (type === 'PDR') return 'pdr'
  if (type === 'RD') return 'rd'
  return 'bus'
}

export function requirementPath(id: number | string): string {
  return `/requirements/${id}`
}

export function releasePath(id: number | string): string {
  return `/releases/${id}`
}

/** 父需求的聚合数字：detail 用 rollup，列表行用 childCount/doneCount（两套后端都认） */
export function childInfo(row: RequirementRow & { rollup?: RequirementDetail['rollup'] }): {
  childCount: number
  doneCount: number
} {
  return {
    childCount: row.rollup?.childCount ?? row.childCount ?? 0,
    doneCount: row.rollup?.doneCount ?? row.doneCount ?? 0,
  }
}

export interface ActionMeta {
  action: string
  label: string
  /** 满足任一即显示可点击；缺省表示只看状态机给出的 allowedActions */
  permissions?: string[]
  danger?: boolean
}

/** 动作 → 权限（与 contract PermissionMatrix 的功能码一致，不另造字符串） */
export const ACTION_META: Record<string, ActionMeta> = {
  submit: { action: 'submit', label: '提交审批' },
  withdraw: { action: 'withdraw', label: '撤回', danger: true },
  review_start: { action: 'review_start', label: '发起评审', permissions: ['req:review'] },
  review_pass: { action: 'review_pass', label: '评审通过', permissions: ['req:review'] },
  review_reject: { action: 'review_reject', label: '评审退回', permissions: ['req:review'], danger: true },
  schedule: { action: 'schedule', label: '排期', permissions: ['release:plan'] },
  start_dev: { action: 'start_dev', label: '开始开发', permissions: ['req:deliver'] },
  deliver: { action: 'deliver', label: '提交交付', permissions: ['req:deliver'] },
  submit_test: { action: 'submit_test', label: '提交测试', permissions: ['test:quick'] },
  test_pass: { action: 'test_pass', label: '测试通过', permissions: ['test:quick'] },
  merge: { action: 'merge', label: '合入', permissions: ['req:deliver'] },
  finish: { action: 'finish', label: '完成', permissions: ['req:deliver'] },
  accept: { action: 'accept', label: '验收通过', permissions: ['req:review'] },
  close: { action: 'close', label: '关闭', permissions: ['req:review'], danger: true },
  assign: { action: 'assign', label: '转派', permissions: ['req:assign-vendor'] },
  force_release: { action: 'force_release', label: '强制放行', permissions: ['deploy:force'], danger: true },
}

export const DANGER_ACTIONS = new Set(['close', 'review_reject', 'withdraw', 'force_release', 'reject'])

export function metaOf(action: string): ActionMeta {
  return ACTION_META[action] ?? { action, label: action }
}

/** 动作的确认文案：影响范围写清楚，避免只弹「确定吗」 */
export const ACTION_IMPACT: Record<string, string[]> = {
  withdraw: ['审批实例终止，单据回到发起人', '已处理的审批意见保留在流转记录中'],
  close: ['需求关闭后不再参与版本与门禁统计', '关联的子需求不会自动关闭'],
  review_reject: ['需求退回上一环节，需要修改后重新提交'],
  accept: ['需求进入验收关闭，不再允许开发侧流转'],
  force_release: ['将越过未通过的准入项发布', '域负责人会收到通知，原因记入审计日志'],
}

/**
 * 后端 RequirementViews.Detail 是 `{view, children, allowedActions, permissions}` 包装，
 * 前端类型按扁平声明（另一条线可能改成扁平），这里统一成扁平，页面只认一种。
 */
export function normalizeRequirementDetail(raw: unknown): RequirementDetail {
  const record = (raw ?? {}) as Record<string, unknown>
  if (record.view && typeof record.view === 'object') {
    const view = record.view as RequirementDetail
    return {
      ...view,
      children: Array.isArray(record.children) ? (record.children as RequirementRow[]) : [],
      allowedActions: Array.isArray(record.allowedActions)
        ? (record.allowedActions as string[])
        : (view.allowedActions ?? []),
      permissions:
        (record.permissions as RequirementDetail['permissions'] | undefined) ?? view.permissions,
    }
  }
  const detail = raw as RequirementDetail
  return {
    ...detail,
    children: detail.children ?? [],
    allowedActions: detail.allowedActions ?? [],
  }
}

/** 审批单据摘要后端可能给字符串或对象（TaskRow.document），统一取可读文案 */
export function summaryText(summary: unknown, fallback = '—'): string {
  if (summary === null || summary === undefined) return fallback
  if (typeof summary === 'string') return summary || fallback
  if (typeof summary === 'number') return String(summary)
  if (typeof summary === 'object') {
    const record = summary as Record<string, unknown>
    for (const key of ['summary', 'title', 'name', 'code']) {
      const value = record[key]
      if (typeof value === 'string' && value) return value
    }
  }
  return fallback
}

export function messageOf(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return String(error)
}

/**
 * 域/厂商/人员选项来自只读字典端点（`api/dictionaries.ts`，仅认证、按租户/厂商裁剪）。
 * 失败时退化为空表，不阻断业务页面。
 *
 * 缓存留在页面层：换租户=重新登录=整页刷新，缓存自然失效，不引入第二套租户态。
 */
const domains = ref<DictionaryDomain[]>([])
const vendors = ref<DictionaryVendor[]>([])
const users = ref<DictionaryUser[]>([])
let pending: Promise<void> | null = null
let loaded = false

async function loadDictionary(): Promise<void> {
  const bundle = await loadDictionaries()
  domains.value = bundle.domains
  vendors.value = bundle.vendors
  users.value = bundle.users
  loaded = true
}

/**
 * 后端 timeline 的 action 是 `SUBMIT`/`APPROVE` 这类大写枚举，审批时间线用
 * `dict.actionLabel()` 查小写键；operator 也可能只给 operatorId。
 * 两处都在这里归一，避免时间线上出现原始英文枚举。
 */
export function normalizeTimeline(
  events: ApprovalTimelineEvent[] | null | undefined,
): ApprovalTimelineEvent[] {
  return (events ?? []).map((event) => {
    const raw = event as ApprovalTimelineEvent & { operatorId?: number; operatorName?: string }
    const operator = event.operator ?? raw.operatorName ?? (raw.operatorId != null ? `用户 #${raw.operatorId}` : '系统')
    return { ...event, operator, action: String(event.action ?? '').toLowerCase() }
  })
}

export function usePlatformDict() {
  /** 幂等装载：loaded 短路，并发调用共享同一个 pending（页面内多处 ensure 只发一轮请求） */
  async function ensure(): Promise<void> {
    if (loaded) return
    if (!pending) {
      pending = loadDictionary().finally(() => {
        pending = null
      })
    }
    return pending
  }

  function domainName(id?: number | null): string {
    if (id === null || id === undefined) return '—'
    return domains.value.find((item) => item.id === id)?.name ?? `域 #${id}`
  }

  function vendorName(id?: number | null): string {
    if (id === null || id === undefined) return '未指定厂商'
    return vendors.value.find((item) => item.id === id)?.name ?? `厂商 #${id}`
  }

  function userName(id?: number | null): string {
    if (id === null || id === undefined) return '未指派'
    return users.value.find((item) => item.id === id)?.name ?? `用户 #${id}`
  }

  return { domains, vendors, users, ensure, domainName, vendorName, userName }
}
