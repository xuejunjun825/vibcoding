import { useDictStore, type StatusKind } from '@/stores/dict'

/**
 * 分析域回显词典（r14 T3 / AC-7）：把**表头与筛选值**里的 code 译成中文。
 *
 * 只做显示层翻译，不碰提交值——`definition_json` 里仍是 code，存量报表因此不需要迁移。
 * 词典本体只有一份：`web/src/stores/dict.ts`（与服务端 `AnalyticsLabels`、`sys_dict` 种子
 * 由一致性门禁逐字符串比对）。这里不复制任何 code→中文 的字面表，只声明「字段名 → 词典」的绑定。
 */
export const UNKNOWN_LABEL = '未知状态'

/** 分析域字段键 → dict 词典 kind。未列出的字段（domain/vendor/org/node/plan/发布…）不做翻译。 */
const FIELD_KIND: Partial<Record<string, StatusKind>> = {
  status: 'requirement',
  type: 'reqType',
  priority: 'priority',
  severity: 'severity',
  result: 'approval',
}

/** 与门禁 `i18n-live.mjs` 的判据同源：形如枚举码却没登记，就是注册表缺项，不能漏到界面上。 */
const ENUM_CODE = /^[A-Z][A-Z0-9_]*$/

/**
 * 回显一个取值。
 *
 * - 空白 → `''`（保持既有空值行为，不新增占位）；
 * - 已登记 → 词典中文；
 * - 未登记且形如枚举码 → {@link UNKNOWN_LABEL}（**不是** meta() 的「去下划线」兜底，
 *   那样会把 `FOO_BAR` 显示成「FOO BAR」，仍是英文）；
 * - 其余（中文、时间桶、编号）原样返回。
 */
export function valueLabel(field: string, value: string | null | undefined): string {
  const raw = (value ?? '').trim()
  if (!raw) return ''
  const kind = FIELD_KIND[field]
  if (!kind) return raw
  const dict = useDictStore()
  if (dict.has(kind, raw)) return dict.label(kind, raw)
  if (ENUM_CODE.test(raw)) return UNKNOWN_LABEL
  return raw
}
