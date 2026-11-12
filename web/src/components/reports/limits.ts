import type { DatasetField, FieldKind, ReportDefinition, ReportFilterOp } from '@/api/analytics'

/**
 * 报表编辑器的限额与槽位合法性（AC-12）。
 *
 * 这里的每个数字都逐字对齐后端 `AnalyticsDatasets` 的常量：
 * `MAX_ROWS=3 / MAX_COLS=2 / MAX_DIMS=4 / MAX_MEASURES=3 / MAX_FILTERS=8`，
 * 且后端还要求 `rows` 非空、`measures` 1–3。前端必须精确复刻，否则用户会撞到
 * 「后端 400 而界面毫无提示」的体验；超限时编辑器直接不发请求。
 */
export const REPORT_LIMITS = {
  rows: 3,
  columns: 2,
  dims: 4,
  measures: 3,
  filters: 8,
} as const

/** 与后端 `ReportDefinitionCodec.OPS` 逐字对齐（`=` 在最前：新增筛选的默认算子）。 */
export const FILTER_OPS: ReadonlyArray<{ value: ReportFilterOp; label: string }> = Object.freeze([
  { value: '=', label: '=' },
  { value: '!=', label: '!=' },
  { value: '>', label: '>' },
  { value: '>=', label: '>=' },
  { value: '<', label: '<' },
  { value: '<=', label: '<=' },
  { value: 'IN', label: 'IN' },
])

export type SlotKey = 'rows' | 'columns' | 'measures' | 'filters'

export const SLOT_LABEL: Record<SlotKey, string> = {
  rows: '行',
  columns: '列',
  measures: '值',
  filters: '筛选',
}

/** 度量只能进「值」；维度/时间只能进行、列、筛选。 */
export function slotAccepts(slot: SlotKey, kind: FieldKind): boolean {
  if (slot === 'measures') return kind === 'MEASURE'
  return kind === 'DIM' || kind === 'TIME'
}

export function slotLimit(slot: SlotKey): number {
  if (slot === 'measures') return REPORT_LIMITS.measures
  return REPORT_LIMITS[slot]
}

export interface AddFieldResult {
  def: ReportDefinition
  ok: boolean
  reason?: string
}

/** 纯函数：返回新定义 + 是否被接受；编辑器用 reason 就地提示而不是发请求撞后端。 */
export function addField(def: ReportDefinition, slot: SlotKey, field: DatasetField): AddFieldResult {
  const same = def[slot] as Array<string | { field: string }>
  const exists = same.some((item) => (typeof item === 'string' ? item === field.key : item.field === field.key))
  if (exists) return { def, ok: false, reason: `「${field.label}」已在${SLOT_LABEL[slot]}槽位` }

  if (!slotAccepts(slot, field.kind)) {
    const reason =
      slot === 'measures'
        ? `「${field.label}」是维度，只能进行/列/筛选`
        : `「${field.label}」是度量，只能进「值」槽位`
    return { def, ok: false, reason }
  }

  if (slot === 'rows' || slot === 'columns' || slot === 'filters') {
    const total = def[slot].length + 1
    const limit = slotLimit(slot)
    if (total > limit) {
      return { def, ok: false, reason: `${SLOT_LABEL[slot]}最多 ${limit} 个（当前已满）` }
    }
    // 筛选槽位存的是 ReportFilter 对象（不是字段 key）：新增即「字段 + 默认算子 + 空值」，
    // 由编辑器就地填值；这里若塞字符串会让渲染读到 undefined。
    if (slot === 'filters') {
      return {
        def: { ...def, filters: [...def.filters, { field: field.key, op: '=', value: '' }] },
        ok: true,
      }
    }
    if (def.rows.length + def.columns.length + 1 > REPORT_LIMITS.dims) {
      return { def, ok: false, reason: `行+列合计最多 ${REPORT_LIMITS.dims} 个维度` }
    }
    return {
      def: { ...def, [slot]: [...(def[slot] as string[]), field.key] } as ReportDefinition,
      ok: true,
    }
  }

  if (def.measures.length + 1 > REPORT_LIMITS.measures) {
    return { def, ok: false, reason: `值最多 ${REPORT_LIMITS.measures} 个（当前已满）` }
  }
  return { def: { ...def, measures: [...def.measures, field.key] }, ok: true }
}

export function removeField(def: ReportDefinition, slot: SlotKey, key: string): ReportDefinition {
  if (slot === 'filters') {
    return { ...def, filters: def.filters.filter((filter) => filter.field !== key) }
  }
  const next = (def[slot] as string[]).filter((item) => item !== key)
  return { ...def, [slot]: next } as ReportDefinition
}

/** IN 用逗号分隔；其它算子保留原串（空串即视为未填，由保存前校验拦下）。 */
export function normalizeFilterValue(op: ReportFilterOp, raw: string): string | string[] {
  if (op === 'IN') {
    return raw
      .split(',')
      .map((item) => item.trim())
      .filter((item) => item.length > 0)
  }
  return raw.trim()
}

/** 保存前的最小完整性检查：后端同样会拒，这里只是让错误出现在用户眼前。 */
export function definitionProblems(def: ReportDefinition | null): string[] {
  if (!def) return ['还没有选择数据集']
  const problems: string[] = []
  if (!def.rows.length) problems.push('至少选择 1 个行维度')
  if (!def.measures.length) problems.push('至少选择 1 个值')
  if (def.rows.length > REPORT_LIMITS.rows) problems.push(`行最多 ${REPORT_LIMITS.rows} 个`)
  if (def.columns.length > REPORT_LIMITS.columns) problems.push(`列最多 ${REPORT_LIMITS.columns} 个`)
  if (def.rows.length + def.columns.length > REPORT_LIMITS.dims) {
    problems.push(`行+列合计最多 ${REPORT_LIMITS.dims} 个维度`)
  }
  if (def.measures.length > REPORT_LIMITS.measures) problems.push(`值最多 ${REPORT_LIMITS.measures} 个`)
  if (def.filters.length > REPORT_LIMITS.filters) problems.push(`筛选最多 ${REPORT_LIMITS.filters} 个`)
  for (const filter of def.filters) {
    const filled = Array.isArray(filter.value)
      ? filter.value.length > 0
      : String(filter.value ?? '').trim().length > 0
    if (!filled) problems.push(`筛选「${filter.field}」还没填值`)
  }
  return problems
}
