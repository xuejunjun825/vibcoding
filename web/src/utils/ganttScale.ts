/**
 * 甘特刻度换算（DESIGN §1.6「条与刻度同一换算，避免漂移」）。
 *
 * 唯一比例来源：1 天 = 36 / 12 / 3.6 px（日 / 周 / 月）。
 * 页面上任何 `left`、`width`、刻度标签、拖拽像素→天数都必须经过这里，
 * 不允许在组件里再写一份 `36 * n`：两份换算一旦漂移，条就会与刻度错位。
 *
 * 纯函数、无 DOM、无 Vue —— 可被单测与性能脚本直接引用。
 */

export type GanttZoom = 'DAY' | 'WEEK' | 'MONTH'
export type GanttView = 'RELEASE' | 'SPRINT' | 'REQUIREMENT'

export const ZOOMS: ReadonlyArray<{ key: GanttZoom; label: string }> = [
  { key: 'DAY', label: '日' },
  { key: 'WEEK', label: '周' },
  { key: 'MONTH', label: '月' },
]

export const VIEWS: ReadonlyArray<{ key: GanttView; label: string; empty: string }> = [
  { key: 'RELEASE', label: '版本', empty: '当前版本视图没有可排期的条目' },
  { key: 'SPRINT', label: '迭代', empty: '当前迭代视图没有可排期的条目' },
  { key: 'REQUIREMENT', label: '需求', empty: '当前需求视图没有可排期的条目' },
]

/** DESIGN §1.6：日 36 / 周 12（84/周）/ 月 3.6（≈110/月） */
const PX_PER_DAY: Record<GanttZoom, number> = { DAY: 36, WEEK: 12, MONTH: 3.6 }

const MS_PER_DAY = 86_400_000

export function pxPerDay(zoom: GanttZoom): number {
  return PX_PER_DAY[zoom] ?? PX_PER_DAY.DAY
}

/** 以本地正午解析 `yyyy-MM-dd`，避开夏令时把「同一天」算成 23/25 小时 */
export function parseDay(iso: string): Date {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '')
  if (!match) return new Date(NaN)
  return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 12, 0, 0, 0)
}

export function isoOf(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

export function isIsoDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
}

export function addDays(iso: string, days: number): string {
  const date = parseDay(iso)
  if (Number.isNaN(date.getTime())) return iso
  return isoOf(new Date(date.getTime() + days * MS_PER_DAY))
}

/** `to - from`，按日历天求差（两端都按正午解析，故不受夏令时影响） */
export function diffDays(from: string, to: string): number {
  const a = parseDay(from)
  const b = parseDay(to)
  if (Number.isNaN(a.getTime()) || Number.isNaN(b.getTime())) return 0
  return Math.round((b.getTime() - a.getTime()) / MS_PER_DAY)
}

export function todayISO(): string {
  return isoOf(new Date())
}

/** 区间内天数（含首尾） */
export function daysInclusive(startIso: string, endIso: string): number {
  return Math.max(0, diffDays(startIso, endIso) + 1)
}

export function timelineWidth(startIso: string, endIso: string, zoom: GanttZoom): number {
  return daysInclusive(startIso, endIso) * pxPerDay(zoom)
}

/** 某天在时间轴内的像素偏移 */
export function xOf(iso: string, rangeStart: string, zoom: GanttZoom): number {
  return diffDays(rangeStart, iso) * pxPerDay(zoom)
}

/** 一段日期的像素宽（含首尾：`03-04 → 03-11` 是 8 天） */
export function spanWidth(startIso: string, endIso: string, zoom: GanttZoom): number {
  return daysInclusive(startIso, endIso) * pxPerDay(zoom)
}

/** 拖拽像素 → 天数（四舍五入到整天，最小 1 天） */
export function pxToDays(px: number, zoom: GanttZoom): number {
  const per = pxPerDay(zoom)
  if (!per) return 0
  return Math.round(px / per)
}

export interface GanttTick {
  key: string
  label: string
  left: number
  width: number
  /** 日视图的周末（背景 #FAFBFC） */
  weekend?: boolean
  /** 周一（周视图刻度加粗判断用） */
  major?: boolean
}

function startOfWeek(iso: string): string {
  const date = parseDay(iso)
  const weekday = date.getDay() // 0 = 周日
  const offset = weekday === 0 ? -6 : 1 - weekday
  return addDays(iso, offset)
}

function startOfMonth(iso: string): string {
  const date = parseDay(iso)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-01`
}

export function monthLabel(iso: string): string {
  const date = parseDay(iso)
  return `${date.getMonth() + 1}月`
}

export function shortDate(iso: string): string {
  const date = parseDay(iso)
  if (Number.isNaN(date.getTime())) return iso
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** 底部刻度：日=每天一格；周=每自然周一格；月=每月一格 */
export function buildTicks(startIso: string, endIso: string, zoom: GanttZoom): GanttTick[] {
  const per = pxPerDay(zoom)
  const total = daysInclusive(startIso, endIso)
  const ticks: GanttTick[] = []
  if (total <= 0) return ticks

  if (zoom === 'DAY') {
    for (let i = 0; i < total; i += 1) {
      const iso = addDays(startIso, i)
      const weekday = parseDay(iso).getDay()
      ticks.push({
        key: iso,
        label: String(parseDay(iso).getDate()),
        left: i * per,
        width: per,
        weekend: weekday === 0 || weekday === 6,
      })
    }
    return ticks
  }

  if (zoom === 'WEEK') {
    // 首段可能被区间裁掉一截：段起点取区间起点，段尾取「该周周日」
    let cursor = startOfWeek(startIso)
    let segStart = startIso
    while (diffDays(segStart, endIso) >= 0) {
      const weekEnd = addDays(cursor, 6)
      // 区间可能结束在某周中间：段尾取 min(周日, 区间末)。反过来写会把整段拉满区间
      const visibleEnd = diffDays(weekEnd, endIso) >= 0 ? weekEnd : endIso
      if (diffDays(segStart, visibleEnd) < 0) break
      ticks.push({
        key: `w-${cursor}`,
        label: shortDate(segStart),
        left: xOf(segStart, startIso, zoom),
        width: daysInclusive(segStart, visibleEnd) * per,
        major: true,
      })
      cursor = addDays(cursor, 7)
      segStart = cursor
    }
    return ticks
  }

  // MONTH
  let cursor = startOfMonth(startIso)
  let segStart = startIso
  while (diffDays(segStart, endIso) >= 0) {
    const next = addMonths(cursor, 1)
    const monthEnd = addDays(next, -1)
    const visibleEnd = diffDays(monthEnd, endIso) >= 0 ? monthEnd : endIso
    if (diffDays(segStart, visibleEnd) < 0) break
    ticks.push({
      key: `m-${cursor}`,
      label: monthLabel(cursor),
      left: xOf(segStart, startIso, zoom),
      width: daysInclusive(segStart, visibleEnd) * per,
    })
    cursor = next
    segStart = cursor
  }
  return ticks
}

export function addMonths(iso: string, months: number): string {
  const date = parseDay(iso)
  const target = new Date(date.getFullYear(), date.getMonth() + months, 1, 12)
  return isoOf(target)
}

/** 顶部月/年带（日/周=按月；月视图=按年），与底部刻度同一套像素 */
export function buildBands(startIso: string, endIso: string, zoom: GanttZoom): GanttTick[] {
  const per = pxPerDay(zoom)
  const bands: GanttTick[] = []
  if (daysInclusive(startIso, endIso) <= 0) return bands

  if (zoom === 'MONTH') {
    let cursor = `${parseDay(startIso).getFullYear()}-01-01`
    let segStart = startIso
    while (diffDays(segStart, endIso) >= 0) {
      const next = `${Number(cursor.slice(0, 4)) + 1}-01-01`
      const yearEnd = addDays(next, -1)
      const visibleEnd = diffDays(yearEnd, endIso) >= 0 ? yearEnd : endIso
      bands.push({
        key: `y-${cursor}`,
        label: `${cursor.slice(0, 4)}年`,
        left: xOf(segStart, startIso, zoom),
        width: daysInclusive(segStart, visibleEnd) * per,
      })
      cursor = next
      segStart = cursor
    }
    return bands
  }

  let cursor = startOfMonth(startIso)
  let segStart = startIso
  while (diffDays(segStart, endIso) >= 0) {
    const next = addMonths(cursor, 1)
    const monthEnd = addDays(next, -1)
    const visibleEnd = diffDays(monthEnd, endIso) >= 0 ? monthEnd : endIso
    const year = cursor.slice(0, 4)
    bands.push({
      key: `b-${cursor}`,
      label: year === endIso.slice(0, 4) || cursor.slice(5, 7) !== '01' ? monthLabel(cursor) : `${year}年${monthLabel(cursor)}`,
      left: xOf(segStart, startIso, zoom),
      width: daysInclusive(segStart, visibleEnd) * per,
    })
    cursor = next
    segStart = cursor
  }
  return bands
}

/**
 * 周末底色的渐变表达式（不为每一天建 DOM：DESIGN §1.4 性能手段）。
 * 返回 `background-image` 与像素偏移；月视图周末过窄，不画。
 */
export function weekendShading(
  startIso: string,
  zoom: GanttZoom,
): { image: string; offset: number } | null {
  const per = pxPerDay(zoom)
  if (zoom === 'MONTH') return null
  const period = 7 * per
  const satStart = 5 * per
  const image = `repeating-linear-gradient(to right, transparent 0 ${satStart}px, var(--gantt-weekend) ${satStart}px ${period}px)`
  // 区间起点离周一的偏移：保证周末条纹与真实日历对齐
  const monday = startOfWeek(startIso)
  const shift = diffDays(monday, startIso) * per
  return { image, offset: -shift }
}

/** 竖向网格：日/周按周期重复；月视图由月格边框承担，不再叠一层 */
export function gridShading(zoom: GanttZoom): string {
  if (zoom === 'DAY') {
    return 'repeating-linear-gradient(to right, var(--gantt-grid) 0 1px, transparent 1px 36px)'
  }
  if (zoom === 'WEEK') {
    return 'repeating-linear-gradient(to right, var(--gantt-grid) 0 1px, transparent 1px 84px)'
  }
  return 'none'
}

/** 锁定「今天」落在区间内，避免滚到看不见的远方 */
export function clampRangeStart(startIso: string, endIso: string, today: string): { from: string; to: string } {
  if (diffDays(startIso, endIso) < 0) return { from: today, to: today }
  return { from: startIso, to: endIso }
}

/** 各视图默认窗口（对齐今天，避免打开就落在一段空白里） */
export function defaultWindow(zoom: GanttZoom, today: string = todayISO()): { from: string; to: string } {
  if (zoom === 'DAY') return { from: addDays(today, -7), to: addDays(today, 35) }
  if (zoom === 'WEEK') return { from: addDays(today, -28), to: addDays(today, 84) }
  return { from: startOfMonth(addMonths(today, -3)), to: addDays(addMonths(today, 6), -1) }
}
