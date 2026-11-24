/**
 * 需求字段可编辑性单点（r43，TECHNICAL §4.2 / DESIGN §3.1–§3.3）。
 *
 * 两条纪律：
 * 1. **状态表不在这里复制**。哪些字段能改只由服务端下发的三数组决定
 *    （`editableFields` / `changeRequiredFields` / `lockedFields`，来源是
 *    `platform-contract` 的 `RequirementEditability` 矩阵）；三数组任一缺失即 fail-closed，
 *    前端绝不按 status/type 本地推断。
 * 2. **原因中文只在这里**。抽屉、详情、概览三处都要同一句话，任何一处自己拼字符串
 *    都会与后端 reasonCode 漂移（AC-10）；未知 code 也在这里兜底。
 */
import type { FieldDecision, RequirementDetail, RequirementFieldRule } from '@/api/types'

/**
 * 三态决策。定义在 `@/api/types` 的 `FieldDecision` 上（契约的编译期镜像），
 * 这里 re-export 只是让页面只 import 一个模块。
 */
export type EditDecision = FieldDecision

/** 详情页拿到的三数组视图：互斥、并集 11 字段、规范序（后端保证，前端不再排序合并） */
export interface EditabilityView {
  editable: RequirementFieldRule[]
  changeRequired: RequirementFieldRule[]
  locked: RequirementFieldRule[]
}

/** 抽屉/详情里真正有输入控件的 7 个字段（`type`/`ownerUserId`/`domainIds`/`sonarProjectKey` 只读） */
export const EDITABLE_UI_FIELDS: readonly string[] = [
  'title',
  'description',
  'priority',
  'expectedDeliveryDate',
  'estimateDays',
  'releaseId',
  'parentId',
]

/** DESIGN §1 的历史命名，保留别名以免两处各写一份顺序 */
export const EDIT_UI_FIELDS: readonly string[] = EDITABLE_UI_FIELDS

/**
 * 9 个原因码的中文（逐字取自 DESIGN §3.1/§3.2 与 PRD AC-4）。
 * `EARLY_STAGE` / `OPEN_STATE` 是「其实能改」的解释句，只在抽屉提示位出现，不产生标记。
 */
export const REASON_TEXT: Record<string, string> = {
  EARLY_STAGE: '当前处于早期状态，可直接修改',
  OPEN_STATE: '该字段不受状态限制，可直接修改',
  CHANGE_REQUIRED: '当前状态不能直接修改，需发起变更单',
  TERMINAL: '需求已结束，字段只读',
  PARENT_ROLLUP: '父需求人天由子需求汇总，不可手工填写',
  NO_PARENT: '业务需求为顶层，不能设置父需求',
  ASSIGN_FLOW: '负责人请通过「指派」操作变更',
  IMMUTABLE: '需求类型创建后不可修改',
  UNKNOWN_STATUS: '状态未识别，字段暂不可编辑',
}

/** 锁定标记的短词（`🔒 汇总`）；EDIT 类原因码与 CHANGE_REQUIRED 不产生标记 */
export const REASON_MARKER: Record<string, string> = {
  TERMINAL: '已结束',
  PARENT_ROLLUP: '汇总',
  NO_PARENT: '顶层',
  ASSIGN_FLOW: '指派变更',
  IMMUTABLE: '不可改',
  UNKNOWN_STATUS: '状态未知',
}

/** 未知/缺失 code 的兜底：宁可读起来平淡，也不要露出 reasonCode 或空白 */
const REASON_FALLBACK = '该字段当前不可修改'

export function reasonText(code?: string | null): string {
  if (!code) return REASON_FALLBACK
  return REASON_TEXT[code] ?? REASON_FALLBACK
}

export function reasonMarker(code?: string | null): string {
  if (!code) return ''
  return REASON_MARKER[code] ?? ''
}

/**
 * reasonCode → 决策。`FieldFlag` 只拿到一个 `{field, reasonCode}` 规则时靠它判三态，
 * 避免每个调用点再传一个 decision 参数（也就不会有第二个判定源）。
 */
export function decisionOfReason(code?: string | null): EditDecision {
  if (code === 'EARLY_STAGE' || code === 'OPEN_STATE') return 'EDIT'
  if (code === 'CHANGE_REQUIRED') return 'CHANGE_REQUIRED'
  // 未知 code 归为 LOCKED：fail-closed 只会让人多点一次「发起变更」，反之会让人白填一次表单
  return 'LOCKED'
}

/** 三数组齐备才算拿到矩阵；任一缺失/非数组 → null（调用方按 fail-closed 渲染） */
export function editabilityOf(detail?: RequirementDetail | null): EditabilityView | null {
  if (!detail) return null
  const { editableFields, changeRequiredFields, lockedFields } = detail
  if (
    !Array.isArray(editableFields) ||
    !Array.isArray(changeRequiredFields) ||
    !Array.isArray(lockedFields)
  ) {
    return null
  }
  return {
    editable: editableFields,
    changeRequired: changeRequiredFields,
    locked: lockedFields,
  }
}

function findRule(rules: RequirementFieldRule[], field: string): RequirementFieldRule | null {
  for (const rule of rules) {
    if (rule && rule.field === field) return rule
  }
  return null
}

/** 字段的规则（三数组互斥，命中即返回）；矩阵缺失或字段不属于 11 字段 → null */
export function ruleOf(
  detail: RequirementDetail | null | undefined,
  field: string,
): RequirementFieldRule | null {
  const view = editabilityOf(detail)
  if (!view) return null
  return (
    findRule(view.editable, field) ??
    findRule(view.changeRequired, field) ??
    findRule(view.locked, field)
  )
}

export function decisionOf(
  detail: RequirementDetail | null | undefined,
  field: string,
): EditDecision | null {
  const rule = ruleOf(detail, field)
  return rule ? decisionOfReason(rule.reasonCode) : null
}

/** 给定字段里是否有任意一个能直接改（「编辑」按钮是否可点） */
export function canEditAny(
  detail: RequirementDetail | null | undefined,
  fields: readonly string[],
): boolean {
  return fields.some((field) => decisionOf(detail, field) === 'EDIT')
}

/** 「编辑」按钮能否打开：只看 7 个有控件的字段，避免 4 个只读字段把按钮常亮 */
export function canOpenEdit(detail: RequirementDetail | null | undefined): boolean {
  return canEditAny(detail, EDITABLE_UI_FIELDS)
}

/** 7 个有控件的字段里：一个能编辑的都没有，但有受控字段（→ 应引导发起变更而不是说「只读」） */
function onlyChangeRequired(detail: RequirementDetail | null | undefined): boolean {
  if (canEditAny(detail, EDITABLE_UI_FIELDS)) return false
  return EDITABLE_UI_FIELDS.some((field) => decisionOf(detail, field) === 'CHANGE_REQUIRED')
}

/** 禁用「编辑」时的完整原因（`title` / `aria-label`） */
export function editDisabledReason(detail: RequirementDetail | null | undefined): string {
  if (!editabilityOf(detail)) return '未获取到可编辑性规则，暂不可编辑'
  if (!onlyChangeRequired(detail)) return '需求已结束或字段均被锁定，无法直接修改'
  return '当前状态不能直接修改，需发起变更单'
}

/** 禁用「编辑」时紧邻按钮的可见短句（DESIGN §3.1 三种场景） */
export function editDisabledHint(detail: RequirementDetail | null | undefined): string {
  if (!editabilityOf(detail)) return '缺少可编辑性规则'
  if (canEditAny(detail, EDITABLE_UI_FIELDS)) return ''
  return onlyChangeRequired(detail) ? '字段均需变更' : '字段均只读'
}

/** 规范序：7 个 UI 字段在前，其余矩阵字段按服务端顺序补在后面 */
function orderedFields(view: EditabilityView): string[] {
  const order: string[] = [...EDITABLE_UI_FIELDS]
  for (const rule of [...view.editable, ...view.changeRequired, ...view.locked]) {
    if (rule && typeof rule.field === 'string' && !order.includes(rule.field)) {
      order.push(rule.field)
    }
  }
  return order
}

/** 找指定决策下的第一个字段（409 预填、组头入口用）；没有则 null */
export function firstFieldOf(
  detail: RequirementDetail | null | undefined,
  decision: EditDecision,
): string | null {
  const view = editabilityOf(detail)
  if (!view) return null
  for (const field of orderedFields(view)) {
    if (decisionOf(detail, field) === decision) return field
  }
  return null
}
