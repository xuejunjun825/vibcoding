<script setup lang="ts">
import { computed } from 'vue'
import GanttBar from './GanttBar.vue'
import type { GanttRow, GanttWarning } from '@/api/collab'
import {
  buildBands,
  buildTicks,
  gridShading,
  spanWidth,
  timelineWidth,
  todayISO,
  weekendShading,
  xOf,
  diffDays,
  type GanttZoom,
} from '@/utils/ganttScale'

/**
 * 甘特时间轴（DESIGN §6 `GanttTimeline`）。
 *
 * 纯展示：行、条、今天线、周末底色全部由 props 决定；告警只读服务端 `warnings[]`，
 * 这里不做任何「差几天算临期」的判断。网格用 CSS 渐变而不是「一天一个 div」，
 * 300 行也不会因为网格而变慢。
 */
const props = withDefaults(
  defineProps<{
    rows: GanttRow[]
    zoom: GanttZoom
    rangeStart: string
    rangeEnd: string
    selectedKey?: string
    pendingKey?: string
    flashKey?: string
    collapsed?: string[]
    readOnly?: boolean
    loading?: boolean
    truncated?: boolean
  }>(),
  {
    selectedKey: '',
    pendingKey: '',
    flashKey: '',
    collapsed: () => [],
    readOnly: false,
    loading: false,
    truncated: false,
  },
)

const emit = defineEmits<{
  (e: 'toggle', row: GanttRow): void
  (e: 'select', row: GanttRow): void
  (e: 'open', row: GanttRow): void
  (e: 'preview', payload: { row: GanttRow; start: string; end: string; px: number }): void
  (e: 'commit', payload: { row: GanttRow; start: string; end: string }): void
}>()

/** 左树定稿 360（DESIGN §1.6：12 + 缩进 48 + chevron 16 + 图标 16 + 编号 64 + 标题 + chip 72 + 12） */
const TREE_WIDTH = 360
const ROW_HEIGHT = 40

const timelineW = computed(() => timelineWidth(props.rangeStart, props.rangeEnd, props.zoom))
const ticks = computed(() => buildTicks(props.rangeStart, props.rangeEnd, props.zoom))
const bands = computed(() => buildBands(props.rangeStart, props.rangeEnd, props.zoom))
const weekend = computed(() => weekendShading(props.rangeStart, props.zoom))
const grid = computed(() => gridShading(props.zoom))

const backgroundImage = computed(() => {
  const layers = [grid.value, weekend.value?.image].filter((item): item is string => Boolean(item) && item !== 'none')
  return layers.length ? layers.join(', ') : 'none'
})

const backgroundPosition = computed(() => {
  const positions: string[] = []
  if (grid.value !== 'none') positions.push('0 0')
  if (weekend.value) positions.push(`${weekend.value.offset}px 0`)
  return positions.length ? positions.join(', ') : '0 0'
})

const todayX = computed(() => {
  const today = todayISO()
  if (diffDays(props.rangeStart, today) < 0 || diffDays(today, props.rangeEnd) < 0) return null
  return xOf(today, props.rangeStart, props.zoom)
})

function keyOf(row: GanttRow): string {
  return `${row.type}:${row.id}`
}

const childrenMap = computed(() => {
  const map = new Map<string, number>()
  for (const row of props.rows) {
    if (row.parentId === null || row.parentId === undefined) continue
    const key = `${row.type === 'TASK' ? 'REQUIREMENT' : row.type}:${row.parentId}`
    map.set(key, (map.get(key) ?? 0) + 1)
  }
  return map
})

function hasChildren(row: GanttRow): boolean {
  // 只有需求节点会被后端展开/折叠（REQ:id）；版本/迭代行恒展开
  if (row.type !== 'REQUIREMENT') return false
  if ((childrenMap.value.get(keyOf(row)) ?? 0) > 0) return true
  return props.rows.some((item) => item.parentId === row.id && item.type !== row.type)
}

function isCollapsed(row: GanttRow): boolean {
  return props.collapsed.includes(keyOf(row))
}

const CHIP_LABEL: Record<string, string> = {
  DUE_SOON: '临期',
  OVERDUE: '逾期',
  OVERLOAD: '超载',
  OUT_OF_PARENT_WINDOW: '越窗',
  TRUNCATED: '已折叠',
}

function chipLabel(warning: GanttWarning): string {
  const base = CHIP_LABEL[warning.code] ?? '告警'
  const days = warning.days
  if ((warning.code === 'DUE_SOON' || warning.code === 'OVERDUE') && typeof days === 'number' && days >= 0 && days < 10) {
    return `${base}${days}天`
  }
  return base
}

/** 最多两枚 chip，其余收成 +n（72 宽只容两枚） */
function chipsOf(row: GanttRow): { shown: GanttWarning[]; extra: number } {
  const warnings = [...(row.warnings ?? [])].sort((a, b) => (a.level === 'RED' ? -1 : 1) - (b.level === 'RED' ? -1 : 1))
  return { shown: warnings.slice(0, 2), extra: Math.max(0, warnings.length - 2) }
}

function chipTone(warning: GanttWarning): string {
  if (warning.code === 'OVERLOAD' || warning.code === 'OUT_OF_PARENT_WINDOW') return 'gt__chip--warn'
  return warning.level === 'RED' ? 'gt__chip--danger' : 'gt__chip--warn'
}

function hasBar(row: GanttRow): boolean {
  if (row.planStart && row.planEnd) return true
  return Boolean(row.milestone && row.plannedDate)
}

/** 条几何：区间外的部分裁掉；完全落在区间外则不画（避免溢出到别的月份） */
function barGeometry(row: GanttRow): { left: number; width: number } | null {
  const start = row.planStart
  const end = row.planEnd
  if (!start || !end) {
    if (row.milestone && row.plannedDate) {
      const point = xOf(row.plannedDate, props.rangeStart, props.zoom)
      if (point < 0 || point > timelineW.value) return null
      return { left: Math.max(0, point - 6), width: 12 }
    }
    return null
  }
  const rawLeft = xOf(start, props.rangeStart, props.zoom)
  const rawWidth = spanWidth(start, end, props.zoom)
  if (rawLeft + rawWidth <= 0 || rawLeft >= timelineW.value) return null
  const left = Math.max(0, rawLeft)
  const right = Math.min(timelineW.value, rawLeft + rawWidth)
  return { left, width: Math.max(6, right - left) }
}

function trackStyle(): Record<string, string> {
  return {
    width: `${timelineW.value}px`,
    backgroundImage: backgroundImage.value,
    backgroundPosition: backgroundPosition.value,
    backgroundRepeat: 'repeat',
  }
}

const rowStyle = { height: `${ROW_HEIGHT}px` }

const ICON_PATHS: Record<string, string> = {
  RELEASE: 'M2.6 3.2h8.2M7.2 3.2v7.4M7.2 6.4 10 3.6M7.2 6.6 4.4 4',
  SPRINT: 'M2.6 4.6 8 2.2l5.4 2.4v6.2L8 13.2l-5.4-2.4V4.6ZM8 2.2v11',
  REQUIREMENT: 'M3.6 2.6h5.2l3.6 3.4v7.4H3.6V2.6ZM8.8 2.6v3.4h3.6M5.6 8.6h4.4M5.6 10.8h3',
  TASK: 'M2.8 3.2h10.4v9.6H2.8V3.2ZM5.4 8.2l1.6 1.6 3.4-3.4',
}
</script>

<template>
  <div
    class="gt"
    :class="{ 'gt--loading': loading, 'gt--readonly': readOnly }"
    role="treegrid"
    aria-label="甘特时间轴"
    :aria-rowcount="rows.length + 1"
    :aria-busy="loading"
  >
    <div class="gt__scroll">
      <div class="gt__canvas" :style="{ width: `${TREE_WIDTH + timelineW}px` }">
        <div class="gt__head" role="row">
          <div class="gt__head-tree" role="columnheader" :style="{ width: `${TREE_WIDTH}px` }">
            <span class="gt__head-label">名称</span>
            <span class="gt__head-hint">{{ rows.length }} 行 · 拖拽条改期</span>
          </div>
          <div class="gt__head-time" role="columnheader" :style="{ width: `${timelineW}px` }">
            <div class="gt__bands">
              <div
                v-for="band in bands"
                :key="band.key"
                class="gt__band"
                :style="{ left: `${band.left}px`, width: `${band.width}px` }"
              >
                {{ band.label }}
              </div>
            </div>
            <div class="gt__ticks">
              <div
                v-for="tick in ticks"
                :key="tick.key"
                class="gt__tick"
                :class="{ 'gt__tick--weekend': tick.weekend, 'gt__tick--major': tick.major }"
                :style="{ left: `${tick.left}px`, width: `${tick.width}px` }"
              >
                <span class="gt__tick-label">{{ tick.label }}</span>
              </div>
            </div>
          </div>
        </div>

        <div v-if="todayX !== null" class="gt__today" :style="{ left: `${TREE_WIDTH + todayX}px` }" aria-hidden="true">
          <span class="gt__today-dot" />
          <span class="gt__today-label">今天</span>
        </div>

        <div
          v-for="row in rows"
          :key="`${row.type}-${row.id}`"
          class="gt__row"
          :class="{ 'gt__row--selected': selectedKey === `${row.type}:${row.id}` }"
          role="row"
          :style="rowStyle"
          :aria-level="row.level + 1"
          :aria-selected="selectedKey === `${row.type}:${row.id}`"
          :aria-expanded="hasChildren(row) ? !isCollapsed(row) : undefined"
        >
          <div class="gt__tree" role="rowheader" :class="`gt__tree--l${Math.min(row.level, 3)}`">
            <span class="gt__indent" :style="{ width: `${row.level * 16}px` }" />
            <button
              v-if="hasChildren(row)"
              type="button"
              class="gt__chev"
              :aria-label="isCollapsed(row) ? `展开 ${row.title}` : `折叠 ${row.title}`"
              @click.stop="emit('toggle', row)"
            >
              <svg viewBox="0 0 16 16" aria-hidden="true">
                <path
                  :d="isCollapsed(row) ? 'M6 4.4 10 8l-4 3.6' : 'M4.4 6 8 10l3.6-4'"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.4"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </button>
            <span v-else class="gt__chev gt__chev--ph" aria-hidden="true" />
            <svg class="gt__icon" :class="`gt__icon--${String(row.type).toLowerCase()}`" viewBox="0 0 16 16" aria-hidden="true">
              <path
                :d="ICON_PATHS[String(row.type)] ?? ICON_PATHS.REQUIREMENT"
                fill="none"
                stroke="currentColor"
                stroke-width="1.2"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <span class="gt__code">{{ row.code }}</span>
            <span class="gt__title" :title="row.title">{{ row.title }}</span>
            <span v-if="row.warnings?.length" class="gt__chips">
              <span
                v-for="(warning, index) in chipsOf(row).shown"
                :key="`${warning.code}-${index}`"
                class="gt__chip"
                :class="chipTone(warning)"
                :title="warning.message"
              >
                {{ chipLabel(warning) }}
              </span>
              <span v-if="chipsOf(row).extra > 0" class="gt__chip gt__chip--more" :title="`还有 ${chipsOf(row).extra} 条告警`">
                +{{ chipsOf(row).extra }}
              </span>
            </span>
            <button type="button" class="gt__more" :aria-label="`查看 ${row.title} 详情`" @click.stop="emit('open', row)">
              <svg viewBox="0 0 16 16" aria-hidden="true">
                <path d="M3.4 8h.01M8 8h.01M12.6 8h.01" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
            </button>
          </div>

          <div class="gt__track" role="gridcell" :style="trackStyle()">
            <template v-if="barGeometry(row)">
              <GanttBar
                :row="row"
                :zoom="zoom"
                :left="barGeometry(row)!.left"
                :width="barGeometry(row)!.width"
                :draggable="!readOnly"
                :selected="selectedKey === `${row.type}:${row.id}`"
                :pending="pendingKey === `${row.type}:${row.id}`"
                :flashing="flashKey === `${row.type}:${row.id}`"
                @open="emit('open', $event)"
                @select="emit('select', $event)"
                @preview="emit('preview', $event)"
                @commit="emit('commit', $event)"
              />
            </template>
            <span v-else-if="!hasBar(row)" class="gt__nosched">未排期</span>
          </div>
        </div>

        <div v-if="!rows.length && !loading" class="gt__empty">
          <slot name="empty"><p>当前视图没有可排期的条目</p></slot>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gt {
  position: relative;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  overflow: hidden;
}
.gt__scroll {
  overflow: auto;
  max-height: calc(100vh - 264px);
  min-height: 240px;
}
.gt__canvas {
  position: relative;
  min-width: 100%;
}
.gt__head {
  position: sticky;
  top: 0;
  z-index: 6;
  display: flex;
  height: 64px;
  background: var(--bg-table-head);
  border-bottom: 1px solid var(--border);
}
.gt__head-tree {
  position: sticky;
  left: 0;
  z-index: 7;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 12px;
  background: var(--bg-table-head);
  border-right: 1px solid var(--border);
}
.gt__head-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-muted-aa);
}
.gt__head-hint {
  font-size: 11px;
  color: var(--text-weak);
  white-space: nowrap;
}
.gt__head-time {
  position: relative;
  flex: none;
  height: 64px;
}
.gt__bands {
  position: relative;
  height: 28px;
  border-bottom: 1px solid var(--divider);
}
.gt__band {
  position: absolute;
  top: 0;
  height: 28px;
  padding: 0 8px;
  display: flex;
  align-items: center;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-muted-aa);
  border-right: 1px solid var(--divider);
  white-space: nowrap;
  overflow: hidden;
}
.gt__ticks {
  position: relative;
  height: 36px;
}
.gt__tick {
  position: absolute;
  top: 0;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
  border-right: 1px solid var(--divider);
}
.gt__tick--weekend {
  color: var(--text-weak);
}
.gt__tick--major {
  color: var(--text-body);
}
.gt__row {
  display: flex;
  border-bottom: 1px solid var(--divider);
  transition: background var(--t-color) var(--ease);
}
.gt__row:hover {
  background: var(--bg-hover);
}
.gt__row--selected {
  background: var(--bg-selected);
}
.gt__row:focus-within {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
.gt__tree {
  position: sticky;
  left: 0;
  z-index: 3;
  flex: none;
  width: 360px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px 0 12px;
  background: inherit;
  border-right: 1px solid var(--border);
}
.gt__row:hover .gt__tree,
.gt__row--selected .gt__tree {
  background: var(--bg-hover);
}
.gt__row--selected .gt__tree {
  background: var(--bg-selected);
}
.gt__indent {
  flex: none;
}
.gt__chev {
  flex: none;
  width: 16px;
  height: 16px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--text-muted-aa);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.gt__chev svg {
  width: 16px;
  height: 16px;
}
.gt__chev--ph {
  cursor: default;
}
.gt__chev:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
  border-radius: 2px;
}
.gt__icon {
  flex: none;
  width: 14px;
  height: 14px;
  color: var(--text-secondary);
}
.gt__icon--requirement {
  color: var(--color-primary);
}
.gt__code {
  flex: none;
  width: 64px;
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  color: var(--text-muted-aa);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gt__title {
  flex: 1 1 auto;
  min-width: 60px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-title);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gt__chips {
  flex: none;
  display: flex;
  gap: 4px;
}
.gt__chip {
  width: 72px;
  height: 20px;
  border-radius: var(--r-cell);
  border: 1px solid transparent;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
}
.gt__chip--warn {
  background: var(--color-warning-bg);
  border-color: #e9c989;
  color: var(--warning-ink);
}
.gt__chip--danger {
  background: var(--color-danger-bg);
  border-color: #f7d4d5;
  color: var(--danger-ink);
}
.gt__chip--more {
  width: auto;
  min-width: 24px;
  padding: 0 4px;
  background: var(--color-info-bg);
  border-color: var(--border);
  color: var(--text-muted-aa);
}
.gt__more {
  flex: none;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: var(--r-cell);
  background: none;
  color: var(--text-muted-aa);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--t-color) var(--ease), background var(--t-color) var(--ease);
}
.gt__more svg {
  width: 16px;
  height: 16px;
}
.gt__row:hover .gt__more,
.gt__more:focus-visible {
  opacity: 1;
}
.gt__more:hover {
  background: var(--bg-selected);
}
.gt__more:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.gt__track {
  position: relative;
  flex: none;
  height: 100%;
}
.gt__nosched {
  position: absolute;
  left: 8px;
  top: 12px;
  font-size: 12px;
  color: var(--text-weak);
}
.gt__today {
  position: absolute;
  top: 40px;
  bottom: 0;
  width: 1px;
  background: var(--gantt-today, var(--color-primary));
  z-index: 2;
  pointer-events: none;
}
.gt__today-dot {
  position: absolute;
  top: 24px;
  left: -3px;
  width: 6px;
  height: 6px;
  background: var(--gantt-today, var(--color-primary));
  clip-path: polygon(0 0, 100% 0, 50% 100%);
}
.gt__today-label {
  position: absolute;
  top: 4px;
  left: 4px;
  font-size: 11px;
  color: var(--color-primary);
  white-space: nowrap;
}
.gt__empty {
  padding: 48px 12px;
  text-align: center;
  color: var(--text-weak);
  font-size: 13px;
}
.gt--loading {
  opacity: 0.7;
}
.gt--readonly .gt__head-hint::after {
  content: ' · 只读';
  color: var(--warning-ink);
}
@media (max-width: 1280px) {
  .gt__chips {
    display: none;
  }
}
</style>
