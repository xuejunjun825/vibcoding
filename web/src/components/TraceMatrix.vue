<script setup lang="ts">
import { computed } from 'vue'
import type { TraceRow } from '@/api/types'

/**
 * 追溯矩阵（DESIGN §6.6 / AC-15）：
 * 单元格三态 ✓齐备 / !缺口 / –不适用；缺口行在渲染前二次断言，
 * **不信任后端单一 complete 布尔** —— gaps 非空就一定按缺口渲染。
 */
interface TraceColumn {
  key: string
  label: string
  hint?: string
}

const props = withDefaults(
  defineProps<{
    rows: TraceRow[]
    loading?: boolean
    /** 缺口行是否整体标红条；默认 true */
    highlightGaps?: boolean
    columns?: TraceColumn[]
  }>(),
  {
    loading: false,
    highlightGaps: true,
    columns: () => [
      { key: 'task', label: '任务' },
      { key: 'mr', label: 'MR' },
      { key: 'execution', label: '执行' },
      { key: 'defect', label: '缺陷' },
      { key: 'release', label: '发布单' },
      { key: 'version', label: '版本' },
    ],
  },
)

const emit = defineEmits<{ (event: 'open', requirementId: number): void }>()

const gapCount = computed(
  () => props.rows.filter((row) => (row.gaps?.length ?? 0) > 0 || !row.complete).length,
)

const columns = computed(() => props.columns)

function cellOf(row: TraceRow, key: string) {
  const cell = row.cells?.[key]
  if (!cell) return { state: 'GAP' as const, text: '缺口', title: '缺少对应记录' }
  if (cell.state === 'NA') return { state: 'NA' as const, text: '不适用', title: '该链路不适用' }
  if (cell.state === 'COMPLETE') {
    return { state: 'COMPLETE' as const, text: '齐备', title: cell.label ?? '链路齐备' }
  }
  return { state: 'GAP' as const, text: '缺口', title: cell.label ?? '链路缺口' }
}

function hasGap(row: TraceRow): boolean {
  if (!props.highlightGaps) return false
  return (row.gaps?.length ?? 0) > 0 || row.complete === false
}

function gapLabels(row: TraceRow): string {
  const labels = (row.gaps ?? []).map(
    (key) => columns.value.find((column) => column.key === key)?.label ?? key,
  )
  return labels.join('、')
}

function open(row: TraceRow): void {
  emit('open', row.requirementId)
}
</script>

<template>
  <div class="matrix">
    <div class="matrix__scroll">
      <div class="matrix__grid" :style="{ '--cols': columns.length }">
        <div class="matrix__cell matrix__cell--head matrix__cell--req">需求</div>
        <div
          v-for="column in columns"
          :key="column.key"
          class="matrix__cell matrix__cell--head"
          :title="column.hint ?? column.label"
        >
          {{ column.label }}
        </div>

        <template v-if="props.loading">
          <template v-for="index in 6" :key="`sk-${index}`">
            <div class="matrix__cell matrix__cell--skeleton"><span class="matrix__bar" /></div>
            <div v-for="column in columns" :key="`sk-${index}-${column.key}`" class="matrix__cell">
              <span class="matrix__bar matrix__bar--sm" />
            </div>
          </template>
        </template>

        <template v-else>
          <div
            v-for="row in props.rows"
            :key="row.requirementId"
            class="matrix__row-group"
            :class="{ 'matrix__row-group--gap': hasGap(row) }"
          >
            <button
              type="button"
              class="matrix__cell matrix__cell--req matrix__cell--link"
              @click="open(row)"
            >
              <span class="matrix__req-code">{{ row.requirementCode }}</span>
              <span class="matrix__req-title" :title="row.title">{{ row.title }}</span>
              <span v-if="row.gaps?.length" class="matrix__gap-note">
                缺口：{{ gapLabels(row) }}
              </span>
            </button>

            <button
              v-for="column in columns"
              :key="`${row.requirementId}-${column.key}`"
              type="button"
              class="matrix__cell matrix__cell--state"
              :class="`matrix__cell--${cellOf(row, column.key).state.toLowerCase()}`"
              :title="`${row.requirementCode} · ${column.label}：${cellOf(row, column.key).title}`"
              @click="open(row)"
            >
              <span class="matrix__mark" aria-hidden="true">
                <template v-if="cellOf(row, column.key).state === 'COMPLETE'">✓</template>
                <template v-else-if="cellOf(row, column.key).state === 'GAP'">!</template>
                <template v-else>–</template>
              </span>
              <span class="matrix__state-text">{{ cellOf(row, column.key).text }}</span>
            </button>
          </div>
        </template>
      </div>
    </div>

    <div v-if="!props.loading" class="matrix__legend">
      <span class="matrix__legend-item"><i class="matrix__swatch matrix__swatch--complete" />齐备</span>
      <span class="matrix__legend-item"><i class="matrix__swatch matrix__swatch--gap" />缺口</span>
      <span class="matrix__legend-item"><i class="matrix__swatch matrix__swatch--na" />不适用</span>
      <span class="matrix__legend-count">
        共 {{ props.rows.length }} 条需求，{{ gapCount }} 条存在缺口
      </span>
    </div>
  </div>
</template>

<style scoped>
.matrix {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
}
.matrix__scroll {
  overflow-x: auto;
}
.matrix__grid {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) repeat(var(--cols), 56px);
  min-width: 260px;
}
.matrix__cell {
  display: flex;
  align-items: center;
  height: 32px;
  padding: 0 12px;
  border-bottom: 1px solid var(--divider);
  font-size: 12px;
  color: var(--text-body);
  background: transparent;
}
.matrix__cell--head {
  height: 36px;
  background: var(--bg-table-head);
  color: var(--text-muted-aa);
  font-weight: 500;
  border-bottom: 1px solid var(--border);
  /* 56px 列宽装得下“发布单”，靠不折行 + 收紧内边距，而不是把列撑宽 */
  white-space: nowrap;
  padding: 0 6px;
  position: sticky;
  top: 0;
  z-index: 1;
}
.matrix__cell--head:not(.matrix__cell--req) {
  justify-content: center;
}
.matrix__row-group {
  display: contents;
}
.matrix__row-group:hover .matrix__cell {
  background: var(--bg-hover);
}
.matrix__cell--req {
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  height: auto;
  min-height: 44px;
  padding: 6px 12px;
  gap: 1px;
}
.matrix__cell--link {
  width: 100%;
  border: 0;
  border-bottom: 1px solid var(--divider);
  border-left: 2px solid transparent;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
}
.matrix__row-group--gap .matrix__cell--link {
  border-left-color: var(--color-warning);
}
.matrix__req-code {
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--text-weak);
}
.matrix__req-title {
  font-size: 12px;
  color: var(--text-title);
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.matrix__gap-note {
  font-size: 11px;
  color: var(--warning-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}
.matrix__cell--state {
  justify-content: center;
  gap: 4px;
  padding: 0;
  border: 0;
  border-bottom: 1px solid var(--divider);
  border-radius: var(--r-cell);
  box-shadow: inset 0 0 0 4px transparent;
  cursor: pointer;
  font-family: inherit;
  transition: box-shadow var(--t-color) var(--ease);
}
.matrix__cell--state:hover {
  box-shadow: inset 0 0 0 4px #c9d6ee;
}
.matrix__cell--complete {
  background: var(--color-success-bg);
  color: var(--success-ink);
}
.matrix__cell--gap {
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.matrix__cell--na {
  background: var(--color-info-bg);
  color: var(--text-weak);
}
.matrix__mark {
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
}
.matrix__state-text {
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
}
.matrix__cell--skeleton {
  background: var(--bg-hover);
}
.matrix__bar {
  display: block;
  width: 70%;
  height: 10px;
  border-radius: 3px;
  background: var(--divider);
  animation: shimmer 1.2s var(--ease) infinite;
}
.matrix__bar--sm {
  width: 22px;
  margin: 0 auto;
}
.matrix__legend {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px 12px;
  border-top: 1px solid var(--divider);
  font-size: 12px;
  color: var(--text-muted-aa);
}
.matrix__legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.matrix__swatch {
  width: 12px;
  height: 12px;
  border-radius: 3px;
}
.matrix__swatch--complete {
  background: var(--color-success-bg);
  border: 1px solid #bfe6cb;
}
.matrix__swatch--gap {
  background: var(--color-warning-bg);
  border: 1px solid #efd7a3;
}
.matrix__swatch--na {
  background: var(--color-info-bg);
  border: 1px solid var(--border);
}
.matrix__legend-count {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
@keyframes shimmer {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 0.75;
  }
}
</style>
