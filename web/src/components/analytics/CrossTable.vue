<script setup lang="ts">
/**
 * 交叉表渲染（T3 · TECHNICAL §6.6）。
 *
 * 一个组件吃下所有表格形态，避免四个页面各写一套：列/行表头、sticky、转置、
 * 小计/总计、截断告警、按列数动态分页。**不取数、不算指标**——`tab` 原样来自
 * `POST /api/analytics/crosstab`（或 `runReport`），数值缺失保持 `null` 渲染为「—」。
 *
 * DOM 预算：一次最多渲染 (size + footerRows + 表头) × 列数 ≤ 3000 个单元格，
 * 否则 5000 行 × 多度量的交叉表会把主线程锁死（AC-13）。`size` 由列数反推，
 * `pageSize` prop 只是上限。
 */
import { computed, ref, watch } from 'vue'
import type { CrossRow, CrossTab } from '@/api/analytics'
import EmptyState from '@/components/EmptyState.vue'
import { formatNumber } from '@/utils/format'
import '@/views/analytics/shared.css'

const props = withDefaults(
  defineProps<{
    tab: CrossTab
    /** 转置：原列头变行，原行头 + 小计/总计变列（纯 computed，不重发请求） */
    transpose?: boolean
    /** 单页行数上限（实际值还会按列数收敛到 3000 单元格预算内） */
    pageSize?: number
    /** 紧凑行高（编辑器预览栏用） */
    dense?: boolean
  }>(),
  { transpose: false, pageSize: 100, dense: false },
)

interface HeadCell {
  label: string
  /** 小计/总计列（转置模式下它们成为列，需要独立底色） */
  sum: boolean
}

interface ViewRow {
  label: string
  values: Array<number | null>
  counts: Array<number | null>
}

const rowLabels = computed<string[]>(() =>
  props.tab.rowHeaders.length === props.tab.rows.length
    ? props.tab.rowHeaders
    : props.tab.rows.map((row) => row.key),
)

const colLabels = computed<string[]>(() =>
  props.tab.colHeaders.length > 0 ? props.tab.colHeaders : ['合计'],
)

const colCount = computed(() => colLabels.value.length)

function padTo(values: Array<number | null>, size: number): Array<number | null> {
  return Array.from({ length: size }, (_, index) => values[index] ?? null)
}

function valuesOf(row: CrossRow): Array<number | null> {
  return padTo(row.values, colCount.value)
}

function countsOf(row: CrossRow): Array<number | null> {
  return padTo(row.counts, colCount.value)
}

const head = computed<HeadCell[]>(() => {
  if (!props.transpose) {
    return [{ label: '', sum: false }, ...colLabels.value.map((label) => ({ label, sum: false }))]
  }
  const cells: HeadCell[] = [
    { label: '', sum: false },
    ...rowLabels.value.map((label) => ({ label, sum: false })),
  ]
  if (props.tab.subtotal) cells.push({ label: props.tab.subtotal.key || '小计', sum: true })
  if (props.tab.total) cells.push({ label: props.tab.total.key || '总计', sum: true })
  return cells
})

const bodyRows = computed<ViewRow[]>(() => {
  if (!props.transpose) {
    return props.tab.rows.map((row, index) => ({
      // 与服务端同源：行头优先中文显示串（`rowLabels` 已含长度守卫），raw `row.key` 只兜底。
      label: rowLabels.value[index] || row.key || '',
      values: valuesOf(row),
      counts: countsOf(row),
    }))
  }
  return colLabels.value.map((label, colIndex) => {
    const values = props.tab.rows.map((row) => row.values[colIndex] ?? null)
    const counts = props.tab.rows.map((row) => row.counts[colIndex] ?? null)
    if (props.tab.subtotal) {
      values.push(props.tab.subtotal.values[colIndex] ?? null)
      counts.push(props.tab.subtotal.counts[colIndex] ?? null)
    }
    if (props.tab.total) {
      values.push(props.tab.total.values[colIndex] ?? null)
      counts.push(props.tab.total.counts[colIndex] ?? null)
    }
    return { label, values, counts }
  })
})

const footerRows = computed<ViewRow[]>(() => {
  if (props.transpose) return []
  return [props.tab.subtotal, props.tab.total]
    .filter((row): row is CrossRow => row !== null)
    .map((row) => ({ label: row.key || '合计', values: valuesOf(row), counts: countsOf(row) }))
})

/** 列数（含标签列）；3000 单元格预算按它反推单页行数 */
const cellColumns = computed(() =>
  Math.max(2, props.transpose ? rowLabels.value.length + 3 : colCount.value + 1),
)

const footerCount = computed(() => footerRows.value.length)

const size = computed(() => {
  const soft = Math.min(100, Math.max(20, Math.floor(2800 / cellColumns.value)))
  const hard = Math.max(1, Math.floor(3000 / cellColumns.value) - footerCount.value - 1)
  return Math.max(1, Math.min(props.pageSize, soft, hard))
})

const totalRows = computed(() => bodyRows.value.length)
const totalPages = computed(() => Math.max(1, Math.ceil(totalRows.value / size.value)))
const page = ref(1)

const pagedRows = computed(() => {
  const start = (page.value - 1) * size.value
  return bodyRows.value.slice(start, start + size.value)
})

const rangeStart = computed(() => (totalRows.value === 0 ? 0 : (page.value - 1) * size.value + 1))
const rangeEnd = computed(() => Math.min(totalRows.value, page.value * size.value))

function goTo(next: number): void {
  page.value = Math.min(totalPages.value, Math.max(1, next))
}

watch(
  () => props.tab,
  () => {
    page.value = 1
  },
)
watch([size, totalPages], () => {
  if (page.value > totalPages.value) page.value = totalPages.value
})

function cellText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return Number.isInteger(value) ? formatNumber(value, 0) : formatNumber(value, 2)
}

function cellTitle(count: number | null | undefined): string | undefined {
  return typeof count === 'number' && Number.isFinite(count)
    ? `样本 ${formatNumber(count, 0)}`
    : undefined
}
</script>

<template>
  <div class="an-table">
    <div v-if="props.tab.truncated" class="an-hint an-hint--warn" role="status">
      结果超过 5000 行，仅展示前 5000 行
    </div>

    <div v-if="totalRows === 0" class="an-table__empty">
      <EmptyState
        compact
        variant="filtered"
        icon="filter"
        title="当前筛选下没有数据"
        description="调整业务域、厂商或时间窗后重试"
      />
    </div>

    <template v-else>
      <div class="an-table__scroll" :class="{ 'is-dense': props.dense }">
        <table class="an-table__table">
          <thead>
            <tr>
              <th
                v-for="(cell, index) in head"
                :key="`h-${index}`"
                scope="col"
                :class="{ 'an-table__corner': index === 0, 'is-sum': cell.sum }"
              >
                <span v-if="index > 0">{{ cell.label }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in pagedRows" :key="`r-${rowIndex}-${row.label}`">
              <th scope="row" class="an-table__rowhead" :title="row.label">
                <span class="an-table__ellipsis">{{ row.label || '—' }}</span>
              </th>
              <td
                v-for="(value, colIndex) in row.values"
                :key="`c-${colIndex}`"
                class="an-table__num"
                :class="{ 'is-sum': props.transpose && head[colIndex + 1]?.sum }"
                :title="cellTitle(row.counts[colIndex])"
              >
                {{ cellText(value) }}
              </td>
            </tr>
          </tbody>
          <tfoot v-if="footerCount > 0">
            <tr v-for="row in footerRows" :key="`f-${row.label}`" class="an-table__sum">
              <th scope="row" :title="row.label">{{ row.label }}</th>
              <td
                v-for="(value, colIndex) in row.values"
                :key="`fc-${colIndex}`"
                class="an-table__num"
                :title="cellTitle(row.counts[colIndex])"
              >
                {{ cellText(value) }}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>

      <div class="an-table__foot">
        <span class="an-table__count">
          共 {{ formatNumber(totalRows, 0) }} 行 · 第 {{ page }} / {{ totalPages }} 页
          <span class="an-table__range">（{{ rangeStart }}–{{ rangeEnd }}）</span>
        </span>
        <div v-if="totalPages > 1" class="an-table__pager">
          <button
            type="button"
            class="an-table__btn"
            :disabled="page <= 1"
            aria-label="上一页"
            @click="goTo(page - 1)"
          >
            上一页
          </button>
          <button
            type="button"
            class="an-table__btn"
            :disabled="page >= totalPages"
            aria-label="下一页"
            @click="goTo(page + 1)"
          >
            下一页
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.an-table {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.an-table__table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 13px;
  color: var(--text-body);
}

.an-table__table th,
.an-table__table td {
  box-sizing: border-box;
  padding: 10px 12px;
  height: 44px;
  line-height: 22px;
  border-bottom: 1px solid var(--divider);
  background: var(--bg-card);
  white-space: nowrap;
}

.an-table__table thead th {
  position: sticky;
  top: 0;
  z-index: 3;
  background: var(--bg-table-head);
  color: var(--text-secondary);
  font-weight: 600;
  text-align: right;
}

.an-table__table thead th.an-table__corner {
  left: 0;
  z-index: 4;
  text-align: left;
}

.an-table__table thead th.is-sum,
.an-table__table td.is-sum,
.an-table__table th.is-sum {
  background: var(--bg-table-head);
}

.an-table__rowhead {
  position: sticky;
  left: 0;
  z-index: 2;
  max-width: 260px;
  min-width: 120px;
  background: var(--bg-card);
  color: var(--text-title);
  font-weight: 500;
  text-align: left;
  box-shadow: 1px 0 0 var(--divider);
}

.an-table__ellipsis {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.an-table__num {
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-feature-settings: 'tnum';
}

.an-table__table tbody tr:hover td,
.an-table__table tbody tr:hover th {
  background: var(--bg-hover);
}

.an-table__table tbody tr:hover td.is-sum {
  background: var(--bg-table-head);
}

.an-table__table tfoot th,
.an-table__table tfoot td {
  background: var(--bg-table-head);
  color: var(--text-title);
  font-weight: 600;
  border-bottom: 1px solid var(--divider);
}

.an-table__table tfoot th {
  position: sticky;
  left: 0;
  z-index: 2;
  text-align: left;
}

.an-table__table tfoot tr:last-child th,
.an-table__table tfoot tr:last-child td {
  border-bottom: none;
}

.is-dense .an-table__table th,
.is-dense .an-table__table td {
  height: 32px;
  padding: 5px 10px;
  font-size: 12.5px;
}

.an-table__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  color: var(--text-secondary);
  font-size: 12px;
}

.an-table__range {
  color: var(--text-muted-aa);
}

.an-table__pager {
  display: flex;
  align-items: center;
  gap: 6px;
}

.an-table__btn {
  min-width: 60px;
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  color: var(--text-body);
  font-size: 12px;
  cursor: pointer;
  transition:
    background var(--t-color) var(--ease),
    border-color var(--t-color) var(--ease),
    color var(--t-color) var(--ease);
}

.an-table__btn:hover:not(:disabled) {
  border-color: var(--border-strong);
  background: var(--bg-hover);
  color: var(--text-title);
}

.an-table__btn:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}

.an-table__btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
</style>
