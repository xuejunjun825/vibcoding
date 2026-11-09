<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { GanttRow, GanttWarning } from '@/api/collab'
import { addDays, diffDays, pxPerDay, type GanttZoom } from '@/utils/ganttScale'

/**
 * 甘特计划条（DESIGN §1.2 / §1.3）。
 *
 * 承诺与事实分离：实心 18px 计划条 + 6px 浅色实际条；没有审计就没有实际条，
 * 绝不用「今天」补一段。拖动只改 `transform`（跟手 0ms），松手把目标日期 emit 给页面，
 * 由页面走唯一写入口 —— 这个组件不调接口，也不乐观改数据。
 */
const props = withDefaults(
  defineProps<{
    row: GanttRow
    zoom: GanttZoom
    left: number
    width: number
    draggable?: boolean
    selected?: boolean
    pending?: boolean
    flashing?: boolean
  }>(),
  { draggable: true, selected: false, pending: false, flashing: false },
)

const emit = defineEmits<{
  (e: 'open', row: GanttRow): void
  (e: 'select', row: GanttRow): void
  (e: 'preview', payload: { row: GanttRow; start: string; end: string; px: number }): void
  (e: 'commit', payload: { row: GanttRow; start: string; end: string }): void
}>()

const dragging = ref(false)
const dragPx = ref(0)
/** 松手后、服务端回执前保留的位移：条不会先弹回原位再跳过去（DESIGN 反模式「拖完静默回退」） */
const pendingPx = ref(0)
const resize = ref(false)
const hover = ref(false)

const planStart = computed(() => props.row.planStart ?? '')
const planEnd = computed(() => props.row.planEnd ?? '')
const hasPlan = computed(() => Boolean(planStart.value && planEnd.value))

const warnings = computed<GanttWarning[]>(() => props.row.warnings ?? [])
const overdue = computed(() => warnings.value.some((item) => item.code === 'OVERDUE'))
const dueSoon = computed(() => warnings.value.some((item) => item.code === 'DUE_SOON'))
const outOfWindow = computed(() => warnings.value.some((item) => item.code === 'OUT_OF_PARENT_WINDOW'))
const done = computed(() => Boolean(props.row.done))

const barClass = computed(() => ({
  'gbar--done': done.value,
  'gbar--overdue': !done.value && overdue.value,
  'gbar--due': !done.value && !overdue.value && dueSoon.value,
  'gbar--out': outOfWindow.value,
  'gbar--selected': props.selected,
  'gbar--pending': props.pending,
  'gbar--dragging': dragging.value,
  'gbar--flash': props.flashing,
  'gbar--hover': hover.value,
}))

/** 条内文字只在 ≥96 时显示；否则外置标签，避免裁字（DESIGN 反模式 11） */
const showInsideLabel = computed(() => props.width >= 96)
const insideLabel = computed(() => (done.value ? '✓ ' : '') + props.row.title)

const actualStyle = computed(() => {
  const start = props.row.actualStart
  const end = props.row.actualEnd
  if (!start || !end) return null
  const per = pxPerDay(props.zoom)
  const left = diffDays(planStart.value, start) * per
  const width = Math.max(per, (diffDays(start, end) + 1) * per)
  return { left: `${left}px`, width: `${width}px` }
})

const milestoneStyle = computed(() => {
  const date = props.row.plannedDate
  if (!date) return null
  const per = pxPerDay(props.zoom)
  return { left: `${diffDays(planStart.value, date) * per}px` }
})

const aria = computed(() => {
  const parts = [props.row.title]
  if (hasPlan.value) parts.push(`计划 ${planStart.value} 至 ${planEnd.value}`)
  if (done.value) parts.push('已完成')
  for (const warning of warnings.value) parts.push(warning.message)
  return parts.join('，')
})

function dayDelta(px: number): number {
  return Math.round(px / pxPerDay(props.zoom))
}

let startX = 0
let startY = 0
let moved = false

function onPointerDown(event: PointerEvent, mode: 'move' | 'resize'): void {
  if (!props.draggable || !hasPlan.value) return
  if (event.button !== 0) return
  event.stopPropagation()
  event.preventDefault()
  resize.value = mode === 'resize'
  dragging.value = true
  moved = false
  startX = event.clientX
  startY = event.clientY
  dragPx.value = 0
  ;(event.target as HTMLElement).setPointerCapture?.(event.pointerId)
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerCancel)
}

function onPointerMove(event: PointerEvent): void {
  if (!dragging.value) return
  const dx = event.clientX - startX
  const dy = event.clientY - startY
  if (!moved && Math.abs(dx) < 3 && Math.abs(dy) < 3) return
  moved = true
  // 竖向位移不参与：行不跟随，避免误拖到别的行
  dragPx.value = dx
  if (resize.value) {
    const delta = Math.max(0, dayDelta(dx))
    emit('preview', {
      row: props.row,
      start: planStart.value,
      end: addDays(planEnd.value, delta),
      px: dragPx.value,
    })
  } else {
    const delta = dayDelta(dx)
    emit('preview', {
      row: props.row,
      start: addDays(planStart.value, delta),
      end: addDays(planEnd.value, delta),
      px: dragPx.value,
    })
  }
}

function onPointerUp(): void {
  if (!dragging.value) return
  const dx = dragPx.value
  const wasResize = resize.value
  cleanup()
  if (!moved) {
    emit('select', props.row)
    emit('open', props.row)
    return
  }
  if (wasResize) {
    const delta = Math.max(0, dayDelta(dx))
    if (delta === 0) return
    pendingPx.value = dx
    emit('commit', { row: props.row, start: planStart.value, end: addDays(planEnd.value, delta) })
    return
  }
  const delta = dayDelta(dx)
  if (delta === 0) return
  pendingPx.value = dx
  emit('commit', { row: props.row, start: addDays(planStart.value, delta), end: addDays(planEnd.value, delta) })
}

/** 父级换了日期（成功）或撤掉 pending（失败）才清掉那段位移，中间不断档 */
watch(
  () => props.left,
  () => {
    pendingPx.value = 0
  },
)
watch(
  () => props.pending,
  (next, prev) => {
    if (prev && !next) pendingPx.value = 0
  },
)

function onPointerCancel(): void {
  cleanup()
}

function cleanup(): void {
  dragging.value = false
  resize.value = false
  dragPx.value = 0
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerCancel)
}

/** 键盘可达：←/→ ±1 天、Shift ±7 天、Enter 打开详情（DESIGN §1.3） */
function onKeydown(event: KeyboardEvent): void {
  if (!hasPlan.value) return
  const step = event.shiftKey ? 7 : 1
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    event.preventDefault()
    if (!props.draggable) return
    const delta = event.key === 'ArrowLeft' ? -step : step
    emit('commit', { row: props.row, start: addDays(planStart.value, delta), end: addDays(planEnd.value, delta) })
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    emit('open', props.row)
  }
}

const transform = computed(() => {
  const offset = dragPx.value || pendingPx.value
  return offset ? `translateX(${offset}px)` : undefined
})
</script>

<template>
  <div
    ref="bar"
    class="gbar"
    :class="barClass"
    :style="{ left: `${left}px`, width: `${Math.max(width, 6)}px`, transform }"
    :tabindex="hasPlan || row.milestone ? 0 : -1"
    role="gridcell"
    :aria-label="aria"
    :title="aria"
    @pointerdown="onPointerDown($event, 'move')"
    @pointerenter="hover = true"
    @pointerleave="hover = false"
    @keydown="onKeydown"
  >
    <span v-if="hasPlan && !row.milestone" class="gbar__fill" />
    <span v-if="!row.milestone && actualStyle" class="gbar__actual" :style="actualStyle" />
    <span v-if="showInsideLabel && !row.milestone" class="gbar__label">{{ insideLabel }}</span>
    <span v-if="row.milestone" class="gbar__milestone" :style="milestoneStyle ?? undefined" aria-hidden="true" />
    <span
      v-if="draggable && !row.milestone"
      class="gbar__handle"
      role="separator"
      aria-label="拖动右边缘调整结束日期"
      @pointerdown.stop="onPointerDown($event, 'resize')"
    />
  </div>
</template>

<style scoped>
.gbar {
  position: absolute;
  top: 6px;
  height: 18px;
  border-radius: var(--r-cell);
  background: var(--gantt-plan-fill, var(--color-primary));
  cursor: grab;
  transition: opacity var(--t-color) var(--ease), background var(--t-color) var(--ease),
    outline-color var(--t-color) var(--ease);
  will-change: transform;
  touch-action: none;
  outline: 1px solid transparent;
  outline-offset: -1px;
  display: flex;
  align-items: center;
  z-index: 2;
}
.gbar:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
  z-index: 3;
}
.gbar--hover {
  /* hover：白色内描边 + 顶部 2px 深色（不投影「浮起来」） */
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.65);
}
.gbar--hover::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 2px;
  background: var(--color-primary-active);
  border-radius: 4px 4px 0 0;
}
.gbar--selected {
  outline: 2px solid var(--color-primary-active);
  outline-offset: 1px;
}
.gbar--dragging,
.gbar--pending {
  opacity: 0.55;
  outline: 1px dashed var(--color-primary);
  cursor: grabbing;
}
.gbar--dragging .gbar__fill,
.gbar--pending .gbar__fill {
  background: var(--color-primary-light);
}
.gbar--done {
  background: var(--color-primary-light);
  outline-color: #b0c8f5;
}
.gbar--done .gbar__label {
  color: var(--color-primary-hover);
}
.gbar--due {
  background: var(--color-warning-bg);
  outline-color: #e9c989;
}
.gbar--due .gbar__label {
  color: var(--warning-ink);
}
.gbar--overdue {
  background: var(--color-danger-bg);
  outline-color: #f7d4d5;
}
.gbar--overdue .gbar__label {
  color: var(--danger-ink);
}
.gbar--out {
  outline: 1px dashed var(--color-warning);
}
.gbar--flash {
  animation: bar-flash 600ms var(--ease);
}
@keyframes bar-flash {
  from {
    background: var(--color-success-bg);
  }
}
.gbar__fill {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  pointer-events: none;
}
.gbar__label {
  position: relative;
  z-index: 1;
  padding: 0 6px;
  font-size: 12px;
  font-weight: 500;
  color: #fff;
  line-height: 18px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  pointer-events: none;
}
.gbar__actual {
  position: absolute;
  top: 20px;
  height: 6px;
  border-radius: 3px;
  background: var(--gantt-actual-fill, #9dc0f2);
  pointer-events: none;
}
.gbar__milestone {
  position: absolute;
  top: -2px;
  width: 12px;
  height: 12px;
  margin-left: -6px;
  background: var(--bg-card);
  border: 1px solid var(--color-primary-active);
  transform: rotate(45deg);
  pointer-events: none;
}
.gbar__handle {
  position: absolute;
  top: -2px;
  right: -4px;
  width: 8px;
  height: 22px;
  cursor: ew-resize;
  z-index: 2;
}
.gbar__handle::after {
  content: '';
  position: absolute;
  right: 5px;
  top: 3px;
  width: 2px;
  height: 16px;
  background: #fff;
  opacity: 0;
  border-radius: 1px;
  transition: opacity var(--t-color) var(--ease);
}
.gbar--hover .gbar__handle::after {
  opacity: 0.9;
}
@media (prefers-reduced-motion: reduce) {
  .gbar {
    transition: none;
  }
  .gbar--flash {
    animation: none;
  }
}
</style>
