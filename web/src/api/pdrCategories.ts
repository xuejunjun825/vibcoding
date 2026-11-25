/**
 * PDR 评审意见分类（9 类）的**唯一**前端副本。
 *
 * 码表属于契约（顺序即下拉顺序）：`platform-contract/src/main/java/com/example/platform/contract/PdrCategoryCatalog.java`
 * 的 `ALL` + `dictionary()`。后端没有把这张表放进字典端点，
 * `GET /pdr-reviews/datasets` 也只回维度名（category）不回码/文案，
 * 所以前端只能在这里复刻一份——**只允许这一处**（lint L20「前端不复刻常量矩阵/字典」的口子）。
 *
 * 页面禁止再写字面量数组：下拉、分布图、chip 一律走 `PDR_CATEGORIES` / `PDR_CATEGORY_LABEL`。
 * 意见行本身带 `categoryLabel`（服务端 `PdrCategoryCatalog.label()` 的结果），渲染 chip 时优先用它，
 * 这里的表只负责下拉选项与统计图的顺序兜底。
 */

/** 与 `PdrCategoryCatalog.ALL` 逐字、同序（RANGE → OTHER） */
export const PDR_CATEGORIES = [
  'RANGE',
  'TECH',
  'SCHEDULE',
  'RISK',
  'ACCEPTANCE',
  'UI',
  'DATA',
  'COMPLIANCE',
  'OTHER',
] as const

export type PdrCategory = (typeof PDR_CATEGORIES)[number]

/** 与 `PdrCategoryCatalog.dictionary()` 的 label 逐字一致 */
export const PDR_CATEGORY_LABEL: Record<PdrCategory, string> = {
  RANGE: '范围/需求理解',
  TECH: '技术可行性',
  SCHEDULE: '工期/排期',
  RISK: '风险/依赖',
  ACCEPTANCE: '验收标准',
  UI: '界面/交互',
  DATA: '数据/兼容',
  COMPLIANCE: '合规/安全',
  OTHER: '其他',
}

/** 下拉选项：保序（后端注释明确「顺序即契约」） */
export const PDR_CATEGORY_OPTIONS: { value: PdrCategory; label: string }[] = PDR_CATEGORIES.map(
  (value) => ({ value, label: PDR_CATEGORY_LABEL[value] }),
)

/** 未知码不猜：回退码本身（与后端 `label()` 的兜底一致） */
export function pdrCategoryLabel(code?: string | null): string {
  if (!code) return '—'
  return PDR_CATEGORY_LABEL[code as PdrCategory] ?? code
}

/** INVALID_CATEGORY 的提示里要列出合法集合，只从这里拼 */
export const PDR_CATEGORY_LIST_TEXT = PDR_CATEGORIES.join(' / ')
