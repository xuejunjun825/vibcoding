/**
 * r26 · 工时评估（`WBS_ESTIMATE`）六态的**唯一文案来源**。
 *
 * 三个消费点都必须调用 `estimateGateTip()`：概览卡片的门提示、动作行的内联提示、
 * 受门动作按钮的 `title`。模板里禁止拼字符串，否则 AC-13 的「逐字一致」立刻漂移。
 * 权限原因「需要权限：<码>」不在这里生成——两类原因物理分离，互不污染。
 *
 * 纯函数模块：不 import 组件 / 字典 / `platform.ts`，可被静态断言逐字比对。
 */
import type { EstimateBlock } from '@/api/types'

export type EstimateStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED' | 'STALE'
export type EstimateTone = 'success' | 'warning' | 'danger' | 'info' | 'muted'

export interface EstimateMeta {
  /** 卡片 pill 与审批块里的中文状态词 */
  label: string
  tone: EstimateTone
  /** 门提示括号里的状态词（PRD §5.7：无 / 待审核 / 已驳回 / 已作废 / 已失效） */
  gateWord: string
}

export const ESTIMATE_META: Record<EstimateStatus, EstimateMeta> = {
  NONE: { label: '未评估', tone: 'muted', gateWord: '无' },
  PENDING: { label: '审批中', tone: 'info', gateWord: '待审核' },
  APPROVED: { label: '已通过', tone: 'success', gateWord: '已通过' },
  REJECTED: { label: '已驳回', tone: 'danger', gateWord: '已驳回' },
  SUPERSEDED: { label: '已作废', tone: 'muted', gateWord: '已作废' },
  STALE: { label: '已失效', tone: 'warning', gateWord: '已失效' },
}

export const ESTIMATE_STATUSES = Object.keys(ESTIMATE_META) as EstimateStatus[]

/** estimate 块缺失（老后端 / 请求失败）时按 `NONE` 处理：保守禁用，不误放行 */
export function estimateStatusOf(block?: EstimateBlock | null): EstimateStatus {
  const raw = String(block?.status ?? '').trim().toUpperCase()
  return (ESTIMATE_STATUSES as string[]).includes(raw) ? (raw as EstimateStatus) : 'NONE'
}

/** 「需先通过工时评估（当前评估单：…）」；已通过时返回空串（两处提示都不出现） */
export function estimateGateTip(status: EstimateStatus): string {
  if (status === 'APPROVED') return ''
  return `需先通过工时评估（当前评估单：${ESTIMATE_META[status].gateWord}）`
}

/** 偏差百分比：只用于展示，判定以服务端 status 为准（不存在第二套阈值真相） */
export function estimateDeviationPercent(
  current?: number | null,
  approved?: number | null,
): number | null {
  const now = Number(current)
  const base = Number(approved)
  if (current == null || approved == null) return null
  if (!Number.isFinite(now) || !Number.isFinite(base) || base === 0) return null
  return ((now - base) / base) * 100
}

/** 1 位小数、整数去 `.0`、显式带符号（`+25%` / `0%` / `-16.7%` / `—`） */
export function estimateDeviationLabel(
  current?: number | null,
  approved?: number | null,
): string {
  const value = estimateDeviationPercent(current, approved)
  if (value === null) return '—'
  const rounded = Math.round(value * 10) / 10
  const text = Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1)
  if (rounded > 0) return `+${text}%`
  return `${text}%`
}

/** 受工时评估门约束的动作：PDR 的排期、RD 的开始开发，其余类型不受门约束 */
export function gateActionOfType(type?: string | null): 'schedule' | 'start_dev' | null {
  const key = String(type ?? '').trim().toUpperCase()
  if (key === 'PDR') return 'schedule'
  if (key === 'RD') return 'start_dev'
  return null
}

export const ESTIMATE_CTA_LABEL = {
  submit: '提交评估',
  resubmit: '重新提交评估',
  view: '查看评估单',
} as const

export const ESTIMATE_STALE_REASON = '人天相对评估值变动超过 20%，需重新评估。'
export const ESTIMATE_SUPERSEDED_REASON = '已被新的评估单取代。'

/**
 * 需求动态里的评估事件码（AC-3/AC-4，US-2）：与后端审计动作同名，
 * 让「最近动态」能把提交 / 通过 / 驳回（带原因）与既有审批时间线并排展示。
 * 文案与事件码都收在这一个纯函数模块里，页面模板不再拼字符串。
 */
export const ESTIMATE_EVENT = {
  SUBMIT: 'ESTIMATE_SUBMIT',
  APPROVED: 'ESTIMATE_APPROVED',
  REJECTED: 'ESTIMATE_REJECTED',
  WITHDRAWN: 'ESTIMATE_WITHDRAWN',
  SUPERSEDED: 'ESTIMATE_SUPERSEDED',
} as const

export const ESTIMATE_EVENT_LABEL: Record<string, string> = {
  ESTIMATE_SUBMIT: '提交工时评估',
  ESTIMATE_APPROVED: '工时评估通过',
  ESTIMATE_REJECTED: '工时评估驳回',
  ESTIMATE_WITHDRAWN: '撤回工时评估',
  ESTIMATE_SUPERSEDED: '工时评估单作废',
}
