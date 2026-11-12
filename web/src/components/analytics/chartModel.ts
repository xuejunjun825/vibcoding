import type { ChartType, CrossRow, CrossTab } from '@/api/analytics'

/**
 * 图表维度规则 + 交叉表 → 图表数据（AC-13 / TECHNICAL §6.5）。
 *
 * 后端 `chart_type` 白名单只有 TABLE|BAR|LINE|PIE（G-3），所以：
 *  - 堆叠柱 = BAR + 列维（可持久化）；
 *  - 热力 = 本地视图模式（`HEATMAP`），可以选可以画，但不写回报表定义（G-8）。
 * 维度规则集中在这里的纯函数里：四个「禁用 + 原因」控件与图表读取同一份判定，避免各处漂移。
 */
export type ChartMode = 'BAR' | 'LINE' | 'PIE' | 'HEATMAP'

/** 视图层可选的图表类型 = 落库白名单 + 本地热力 */
export type ChartViewType = ChartType | 'HEATMAP'

/** 兼容别名（早期草稿用的是 ViewMode；两处都保留，避免调用方改名） */
export type ChartViewMode = ChartViewType

export interface ChartPlan {
  /** 实际渲染模式（TABLE 时无意义，用 BAR 占位） */
  mode: ChartMode
  stacked: boolean
  disabled: boolean
  reason: string | null
}

export interface ChartSeries {
  name: string
  /** 与 `categories` 等长；`null` = 该格无样本（图上留断点，绝不补 0） */
  values: Array<number | null>
}

export interface ChartModel {
  mode: ChartMode
  categories: string[]
  series: ChartSeries[]
  /** 热力专用：`[xIndex, yIndex, value]`，下标对齐 `heat.x` / `heat.y` */
  heat: { x: string[]; y: string[]; cells: Array<[number, number, number]> }
  /** 度量单位（调用方从 dataset.fields 反查后传入；查不到留空） */
  unit: string
}

/** 选择器文案（编辑器与列表的「图表类型」控件共用，避免各写一套中文） */
export const CHART_VIEW_MODES: ReadonlyArray<{ value: ChartViewType; label: string }> = [
  { value: 'TABLE', label: '表格' },
  { value: 'BAR', label: '柱状图' },
  { value: 'LINE', label: '折线图' },
  { value: 'PIE', label: '饼图' },
  { value: 'HEATMAP', label: '热力（本地）' },
]

export const CHART_MODE_LABEL: Record<ChartViewType, string> = {
  TABLE: '表格',
  BAR: '柱状图',
  LINE: '折线图',
  PIE: '饼图',
  HEATMAP: '热力',
}

const NEED_ONE_DIM = '请先放入至少 1 个维度'
const NEED_ROW_AND_COL = '热力需要行维 + 列维'
const overflowReason = (dims: number): string =>
  `${dims} 个维度无法选择图表类型，请减少到 2 个以内`

function blocked(mode: ChartMode, reason: string): ChartPlan {
  return { mode, stacked: false, disabled: true, reason }
}

/**
 * 维度规则（TECHNICAL §6.5 的 4×5 表，逐条照抄）：
 *
 * | dims | TABLE | BAR | LINE | PIE | HEATMAP（本地） |
 * | 0    | ✓ | 禁用「请先放入至少 1 个维度」 | 同左 | 同左 | 禁用 |
 * | 1    | ✓ | ✓ 柱 | ✓ 折线 | ✓ 饼 | 禁用「热力需要行维 + 列维」 |
 * | 2    | ✓ | ✓ 堆叠柱（有列维才 stacked） | 禁用「折线仅支持 1 个维度」 | 禁用「饼图仅支持 1 个维度」 | 行维+列维齐备才可用 |
 * | ≥3   | ✓ | 禁用「N 个维度无法选择图表类型，请减少到 2 个以内」 | 同左 | 同左 | 同左 |
 *
 * `TABLE` 常驻可用：它的 `mode` 无意义（调用方应按 `chartType === 'TABLE'` 走 CrossTable），
 * 这里回 `BAR` 只是为了满足封闭的 `ChartPlan` 形状。
 */
export function chartPlanFor(
  chartType: ChartViewType,
  rowDims: number,
  colDims: number,
): ChartPlan {
  const dims = rowDims + colDims

  if (chartType === 'TABLE') {
    return { mode: 'BAR', stacked: false, disabled: false, reason: null }
  }

  if (dims === 0) return blocked('BAR', NEED_ONE_DIM)
  if (dims >= 3) return blocked('BAR', overflowReason(dims))

  if (chartType === 'HEATMAP') {
    if (rowDims < 1 || colDims < 1) return blocked('HEATMAP', NEED_ROW_AND_COL)
    return { mode: 'HEATMAP', stacked: false, disabled: false, reason: null }
  }

  if (dims === 2) {
    if (chartType === 'LINE') return blocked('LINE', '折线仅支持 1 个维度')
    if (chartType === 'PIE') return blocked('PIE', '饼图仅支持 1 个维度')
    // 2 维柱状 = 堆叠柱；只有列维时才堆叠（纯行维两行是同一度量的两个分组，堆叠没有意义）
    return { mode: 'BAR', stacked: colDims >= 1, disabled: false, reason: null }
  }

  return { mode: chartType, stacked: false, disabled: false, reason: null }
}

/**
 * 交叉表 → 图表数据（纯转换，不取数、不算指标）。
 *
 * - `rowHeaders` 与 `rows` 一一对应；长度不一致时退回 `row.key`。
 * - `total` 有意不进图形：饼图会把合计再计一次，柱/折线把合计当第三类会扭曲对比；
 *   合计在表格 `tfoot` 里呈现。`total` 仍留在入参类型里，保持与 `CrossTab` 形状兼容。
 * - 单位不在这里推断（本函数拿不到数据集字段），由调用方从 `dataset.fields` 反查后传入。
 */
export function buildChartModel(
  tab: Pick<CrossTab, 'rowHeaders' | 'colHeaders' | 'rows' | 'total'>,
  plan: ChartPlan,
  unit = '',
): ChartModel {
  const rows: CrossRow[] = tab.rows ?? []
  const empty: ChartModel = {
    mode: plan.mode,
    categories: [],
    series: [],
    heat: { x: [], y: [], cells: [] },
    unit,
  }
  if (plan.disabled || rows.length === 0) return empty

  const rowLabels =
    tab.rowHeaders.length === rows.length ? tab.rowHeaders : rows.map((row) => row.key)
  // 无列维时后端仍给一个「合计」列头；缺表头时补一个，保证 series 名不为空
  const colLabels = tab.colHeaders.length > 0 ? tab.colHeaders : ['合计']

  if (plan.mode === 'HEATMAP') {
    const cells: Array<[number, number, number]> = []
    rows.forEach((row, rowIndex) => {
      colLabels.forEach((_, colIndex) => {
        const value = row.values[colIndex] ?? null
        if (value !== null && Number.isFinite(value)) cells.push([colIndex, rowIndex, value])
      })
    })
    return {
      mode: 'HEATMAP',
      categories: [...rowLabels],
      series: [],
      heat: { x: [...colLabels], y: [...rowLabels], cells },
      unit,
    }
  }

  if (plan.mode === 'PIE') {
    // 饼图只画第一列（一个度量）：多度量同饼会重复计量
    return {
      mode: 'PIE',
      categories: [...rowLabels],
      series: [{ name: colLabels[0] ?? '合计', values: rows.map((row) => row.values[0] ?? null) }],
      heat: { x: [], y: [], cells: [] },
      unit,
    }
  }

  return {
    mode: plan.mode,
    categories: [...rowLabels],
    series: colLabels.map((name, colIndex) => ({
      name,
      values: rows.map((row) => row.values[colIndex] ?? null),
    })),
    heat: { x: [], y: [], cells: [] },
    unit,
  }
}
