/**
 * 需求变更单的纯函数层（r40，TECHNICAL §11.2 / DESIGN §3.1–§4.5）。
 *
 * 两份文档对本模块的分工是一致的：**标签与格式化只在这里与 dict.ts 各写一份**，
 * 页面组件不再自造文案；**早期态表不在这里复制**——锁定状态只读服务端下发的
 * `permissions.editableFields`（AC-12/AC-13，后端 `RequirementChangeFields.editableFields` 是唯一来源）。
 */
import type { ChangeImpact, ChangeStatus, ChangeType, ChangeImpactSummary } from '@/api/req'
import { CHANGE_STATUS, CHANGE_TYPE } from '@/stores/dict'

export type { ChangeStatus, ChangeType, ChangeImpact }

/** 变更单状态/类型标签唯一来源（dict.ts）；这里 re-export 只是让页面少一条 import */
export { CHANGE_STATUS, CHANGE_TYPE }

export interface ChangeTypeMeta {
  type: ChangeType
  label: string
  hint: string
  /** 该类型允许改的字段：与后端 `RequirementChangeFields.allowedFields` 逐字一致 */
  fields: string[]
}

/**
 * 四类变更的白名单（TECHNICAL §4.1 / DESIGN §4.2）。一次变更只改一类字段，
 * 因此白名单按类型分组；服务端仍是最终闸门（`CHANGE_FIELD_NOT_ALLOWED` 由页面就地渲染）。
 */
export const CHANGE_TYPES: ChangeTypeMeta[] = [
  {
    type: 'CONTENT',
    label: '内容',
    hint: '标题、描述、优先级、期望交付日',
    fields: ['title', 'description', 'priority', 'expectedDeliveryDate'],
  },
  {
    type: 'DAYS',
    label: '人天',
    hint: '预估人天（0.5 步进）；父需求由子需求汇总',
    fields: ['estimateDays'],
  },
  { type: 'RELEASE', label: '版本', hint: '把需求移入另一个版本', fields: ['releaseId'] },
  { type: 'PARENT', label: '父需求', hint: '把需求挂到另一个父需求下', fields: ['parentId'] },
]

/** 字段中文标签（变更单语境；automation 规则句另有一份 snake_case 词表，展示语境不同） */
const FIELD_LABELS: Record<string, string> = {
  title: '标题',
  description: '描述',
  priority: '优先级',
  expectedDeliveryDate: '期望交付日',
  estimateDays: '预估人天',
  releaseId: '所属版本',
  parentId: '父需求',
}

export function fieldLabel(field: string): string {
  return FIELD_LABELS[field] ?? field
}

/** 变更域字段（÷7）——供编辑抽屉分组与 409 预填使用 */
export const CHANGE_FIELD_TYPES: Record<string, ChangeType> = {
  title: 'CONTENT',
  description: 'CONTENT',
  priority: 'CONTENT',
  expectedDeliveryDate: 'CONTENT',
  estimateDays: 'DAYS',
  releaseId: 'RELEASE',
  parentId: 'PARENT',
}

export function changeTypeOfField(field: string): ChangeType | null {
  return CHANGE_FIELD_TYPES[field] ?? null
}

/**
 * 把 409 `CHANGE_REQUIRED` 的字段集归到「一个」变更类型：抽屉一次只改一类，
 * 取数量最多的那一类；混合类型时调用方仍需提示「其余字段要另发一张单」。
 */
export function changeTypeOfFields(fields: string[]): ChangeType | null {
  const counts = new Map<ChangeType, number>()
  for (const field of fields) {
    const type = changeTypeOfField(field)
    if (!type) continue
    counts.set(type, (counts.get(type) ?? 0) + 1)
  }
  let best: ChangeType | null = null
  let max = 0
  for (const [type, count] of counts) {
    if (count > max) {
      best = type
      max = count
    }
  }
  return best
}

export function typeMeta(type?: string | null): ChangeTypeMeta | null {
  return CHANGE_TYPES.find((item) => item.type === type) ?? null
}

export function typeLabel(type?: string | null): string {
  return (type && CHANGE_TYPE[type]) || typeMeta(type)?.label || type || '—'
}

export function statusLabel(status?: string | null): string {
  if (!status) return '—'
  return CHANGE_STATUS[status]?.label ?? status
}

/* ---------------- 路径 ---------------- */

export function changeEndpoint(requirementId: number | string): string {
  return `/api/req/requirements/${requirementId}/changes`
}

export function changeListPath(requirementId: number | string): string {
  return `/requirements/${requirementId}?tab=change`
}

export function changeDetailPath(requirementId: number | string, changeId: number | string): string {
  return `/requirements/${requirementId}/changes/${changeId}`
}

/* ---------------- 数值与缺失数据 ---------------- */

/** 缺数据统一「无法评估」：绝不显示 0 或 —（DESIGN §4.3 反模式 3） */
export const IMPACT_MISSING = '无法评估'

export function impactMissing(value: unknown): string {
  if (value === null || value === undefined) return IMPACT_MISSING
  if (typeof value === 'number' && !Number.isFinite(value)) return IMPACT_MISSING
  if (typeof value === 'string' && !value.trim()) return IMPACT_MISSING
  return String(value)
}

export function isMissing(value: unknown): boolean {
  return impactMissing(value) === IMPACT_MISSING
}

/** 人天值：1 位小数 + 「天」（0.5 步进由服务端校验，这里只负责显示） */
export function formatDaysValue(value?: number | null): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return IMPACT_MISSING
  return `${value.toFixed(1)} 天`
}

/** 本次改动量：`+2.0 天` / `−0.5 天` / `±0 天` */
export function formatDaysDelta(delta?: number | null): string {
  if (delta === null || delta === undefined || !Number.isFinite(delta)) return IMPACT_MISSING
  if (delta === 0) return '±0 天'
  const sign = delta > 0 ? '+' : '−'
  return `${sign}${Math.abs(delta).toFixed(1)} 天`
}

/**
 * 相对已批评估的偏离比例（TECHNICAL/DESIGN §4.3）：
 * 1 位小数；`|r|` 落在 20.0%–20.1% 带内改显 2 位小数——否则会出现「显示 20.0% 却转了 STALE」。
 * 阈值判定永远用**未舍入**比值（见 `staleVerdict`），这里只负责显示。
 */
export function formatRatio(ratio?: number | null): string {
  if (ratio === null || ratio === undefined || !Number.isFinite(ratio)) return IMPACT_MISSING
  const abs = Math.abs(ratio)
  const digits = abs > 0.2 && abs <= 0.201 ? 2 : 1
  const sign = ratio > 0 ? '+' : ratio < 0 ? '−' : ''
  return `${sign}${(abs * 100).toFixed(digits)}%`
}

export type ChangeTone = 'success' | 'warning' | 'danger' | 'neutral' | 'primary'

export interface StaleVerdict {
  /** 人天卡片上的一句话结论 */
  text: string
  tone: ChangeTone
}

/** STALE 判定口径（严格 > 0.20），预测与 `WbsEstimateService.decide` 同源 */
export const STALE_THRESHOLD = 0.2

/**
 * 人天偏离的判定结论。`becomesStale` 来自服务端（与 decide 同源），前端只在
 * 「恰好 20%」「无已批评估」这两个措辞上补充说明，不重算阈值结论。
 */
export function staleVerdict(days?: ChangeImpact['days'] | null): StaleVerdict {
  if (!days) return { text: IMPACT_MISSING, tone: 'neutral' }
  const estimateStatus = String(days.estimateStatus ?? '').toUpperCase()
  if (estimateStatus === 'STALE') return { text: '已转 STALE', tone: 'warning' }
  const approved = days.approvedDays
  if (approved === null || approved === undefined || !(approved > 0)) {
    return { text: '无已批工时评估，不触发 STALE', tone: 'neutral' }
  }
  const ratio = days.deltaRatio
  if (ratio !== null && ratio !== undefined && Math.abs(ratio - STALE_THRESHOLD) < 1e-9) {
    return { text: '恰好 20%，不转 STALE', tone: 'neutral' }
  }
  if (days.becomesStale === true) return { text: '将转 STALE', tone: 'warning' }
  if (days.becomesStale === false) return { text: `未超过 ${STALE_THRESHOLD * 100}%，不转 STALE`, tone: 'neutral' }
  return { text: IMPACT_MISSING, tone: 'neutral' }
}

/* ---------------- 列表影响摘要 ---------------- */

export interface ImpactSummaryLines {
  line1: string
  line2: string
}

/**
 * 列表「影响摘要」两行（DESIGN §3.1）：任一分量缺失 → 该行「无法评估」，绝不写 0。
 * 人天优先显示 `before→after`（服务端若只派生 delta，则退化为 `+2.0 天`）。
 */
export function impactSummaryLines(summary?: ChangeImpactSummary | null): ImpactSummaryLines {
  const children = summary?.children
  const before = (summary as { beforeDays?: number | null } | undefined)?.beforeDays
  const after = (summary as { afterDays?: number | null } | undefined)?.afterDays
  let days = IMPACT_MISSING
  if (before !== null && before !== undefined && after !== null && after !== undefined) {
    days = `${before}→${after}`
  } else if (summary?.daysDelta !== null && summary?.daysDelta !== undefined) {
    days = formatDaysDelta(summary.daysDelta)
  }
  const line1 =
    isMissing(children) || days === IMPACT_MISSING
      ? IMPACT_MISSING
      : `子需求 ${children} · 人天 ${days}`

  const locked = summary?.releaseLocked
  const orders = summary?.affectedOrderCount
  if (isMissing(locked) || isMissing(orders)) return { line1, line2: IMPACT_MISSING }
  const segments = [locked ? '已锁定' : '未锁定']
  if (summary?.becomesStale === true) segments.push('将转 STALE')
  segments.push(`影响 ${orders} 张发布单`)
  return { line1, line2: segments.join(' · ') }
}

/* ---------------- 权限 ---------------- */

export interface ChangePermissions {
  changeRequest: boolean
  changeApply: boolean
  /** null = 服务端未下发（旧响应）；调用方必须 fail-closed 处理 */
  editableFields: string[] | null
}

/**
 * 读服务端下发的变更权限（TECHNICAL §9.3 把三个键放进既有 `View.permissions`）。
 * types.ts 里 permissions 的声明尚未跟上（另一条线在改），因此这里做一次窄化读取，
 * 不复制后端的双依赖判定：`changeRequest/changeApply` 是服务端已经算好的布尔。
 */
export function changePermissionsOf(detail: unknown): ChangePermissions {
  const record = (detail ?? {}) as Record<string, unknown>
  const permissions = record.permissions
  const bag =
    permissions && typeof permissions === 'object' ? (permissions as Record<string, unknown>) : record
  const fields = bag.editableFields
  const editableFields = Array.isArray(fields)
    ? fields.filter((item): item is string => typeof item === 'string')
    : null
  return {
    // 未下发（老响应 / 后端该字段尚未上线）按「允许」处理：入口渲染、点击由路由表兜底 403，
    // 好过在灰度窗口里给所有人一个死按钮；后端明确 false 时仍然禁用。
    changeRequest: bag.changeRequest !== false,
    // 应用是破坏性写：未下发一律视为无权限（不渲染 APPLY），宁可少给一次也不误给
    changeApply: bag.changeApply === true,
    editableFields,
  }
}

export function isFieldEditable(detail: unknown, field: string): boolean {
  const { editableFields } = changePermissionsOf(detail)
  // 服务端没下发 → fail-closed：宁可只读 + 引导走变更单，也不放行一次必然 409 的直改
  if (editableFields === null) return false
  return editableFields.includes(field)
}

/* ---------------- 错误码 → 界面文案 ---------------- */

export interface ChangeErrorText {
  text: string
  code: string
  /** CHANGE_REQUIRED 时被拦的字段（用于 banner 预填） */
  fields: string[]
}

const ERROR_TEXT: Record<string, string> = {
  CHANGE_FIELD_NOT_ALLOWED: '该字段不属于此类变更的白名单，请换一种变更类型',
  REASON_REQUIRED: '请填写变更原因',
  PARENT_DAYS_READONLY: '父需求人天由子需求汇总，不能直接修改',
  INVALID_DAY_PRECISION: '人天必须是 0.5 的整数倍',
  INVALID_PARENT: '父需求不合法：不能选自己或自己的下级',
  PARENT_NOT_POOLED: '父需求尚未入池，不能挂接',
  PARENT_NOT_SCHEDULED: '父需求当前状态不允许拆解',
  CHANGE_REQUIRED: '这些字段已不能直接修改，请发起变更单',
  CHANGE_NOT_APPROVED: '变更单尚未审批通过，不能应用',
  CHANGE_ALREADY_APPLIED: '变更单已应用，不能重复应用',
  CHANGE_CONFLICT: '字段在申请之后又被改过，本次不应用（未写入任何字段）',
  APPROVER_UNRESOLVED: '审批人为空，无法发起审批；请联系管理员补齐流程角色',
  RELEASE_LOCKED: '版本已锁定，不能直接加入或移出',
  VALIDATION_FAILED: '提交内容未通过校验',
}

export function changeErrorText(error: unknown): ChangeErrorText {
  const record = (error ?? {}) as { code?: unknown; message?: unknown; detail?: unknown }
  const code = typeof record.code === 'string' ? record.code : ''
  const detail = record.detail as { fields?: unknown } | undefined
  const fields = Array.isArray(detail?.fields)
    ? (detail?.fields as unknown[]).filter((item): item is string => typeof item === 'string')
    : []
  const message = typeof record.message === 'string' ? record.message : ''
  const text = ERROR_TEXT[code] ?? (message || '操作失败')
  return { text, code, fields }
}
