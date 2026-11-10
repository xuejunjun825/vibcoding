import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import type { RouteLocationNormalizedLoaded, RouteLocationRaw, Router } from 'vue-router'
import type { AnalyticsQuery, CacheState, PresetKey } from '@/api/analytics'

/**
 * 分析域三页共享的筛选 store（T6 · TECHNICAL §6 / DESIGN §5.1）。
 *
 * 唯一的真相是 **URL query**：`filter` 是从当前路由 `query` 派生的 computed，
 * 组件里不保留第二份筛选副本；所有变更都走 `applyToRoute()` → `router.replace()`，
 * 因此刷新 / 前进后退 / 复制链接都能还原（AC-1）。
 *
 * 三页共享同一段 query schema：`domainId / vendorId / from / to / preset`。
 * 非法键（未知 preset、坏日期、跨度 > 366 天、负数 id）在这一层被丢弃并记进
 * `droppedKeys`，页面据此显示 12px「部分筛选已重置」，不白屏。
 */

/** 7 / 30 / 90 天预设 + 自定义区间 */
export const PRESETS: PresetKey[] = ['7d', '30d', '90d', 'custom']

export const PRESET_LABEL: Record<PresetKey, string> = {
  '7d': '近 7 天',
  '30d': '近 30 天',
  '90d': '近 90 天',
  custom: '自定义',
}

const PRESET_DAYS: Record<Exclude<PresetKey, 'custom'>, number> = { '7d': 7, '30d': 30, '90d': 90 }

/** 服务端同样限制 366 天（REPORT_SPAN_LIMIT），前端提前丢弃而不是发一个必 400 的请求 */
const MAX_SPAN_DAYS = 366

/** 筛选变化 → 发请求的合并窗口（DESIGN §4.4：300ms 防抖 + 取消在途） */
export const DEBOUNCE_MS = 300

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/

export interface AnalyticsFilter {
  /** null = 本域（由服务端按账号 Scope 收口），不是「全部域」 */
  domainId: number | null
  vendorId: number | null
  from: string
  to: string
  preset: PresetKey
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

function isoDate(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function atMidnight(value: string): number | null {
  if (!DATE_RE.test(value)) return null
  const time = new Date(`${value}T00:00:00`).getTime()
  return Number.isNaN(time) ? null : time
}

/** 含首尾的天数：2026-11-01 → 2026-11-30 = 30 */
function spanDays(from: string, to: string): number {
  const start = atMidnight(from)
  const end = atMidnight(to)
  if (start === null || end === null || end < start) return -1
  return Math.round((end - start) / 86_400_000) + 1
}

function rangeOfPreset(preset: Exclude<PresetKey, 'custom'>, today = new Date()): {
  from: string
  to: string
} {
  const end = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  const start = new Date(end)
  start.setDate(start.getDate() - (PRESET_DAYS[preset] - 1))
  return { from: isoDate(start), to: isoDate(end) }
}

function defaultFilter(today = new Date()): AnalyticsFilter {
  return { domainId: null, vendorId: null, preset: '30d', ...rangeOfPreset('30d', today) }
}

function pickFirst(value: unknown): unknown {
  return Array.isArray(value) ? value[0] : value
}

function readId(value: unknown, key: string, dropped: string[]): number | null {
  const raw = pickFirst(value)
  if (raw === undefined || raw === null || raw === '') return null
  const parsed = Number(raw)
  if (!Number.isInteger(parsed) || parsed <= 0) {
    dropped.push(key)
    return null
  }
  return parsed
}

/**
 * URL → 筛选（纯函数，store 与测试共用）。
 * 默认「本域 + 近 30 天」；`preset=custom` 缺合法 from/to 时退回近 30 天。
 */
export function filterFromQuery(
  query: Record<string, unknown> = {},
  today = new Date(),
): { filter: AnalyticsFilter; dropped: string[] } {
  const dropped: string[] = []
  const domainId = readId(query.domainId, 'domainId', dropped)
  const vendorId = readId(query.vendorId, 'vendorId', dropped)

  const rawPreset = pickFirst(query.preset)
  let preset: PresetKey | null = null
  if (rawPreset !== undefined && rawPreset !== null && rawPreset !== '') {
    if (PRESETS.includes(rawPreset as PresetKey)) preset = rawPreset as PresetKey
    else dropped.push('preset')
  }

  const rawFrom = pickFirst(query.from)
  const rawTo = pickFirst(query.to)
  const fromText = typeof rawFrom === 'string' ? rawFrom : ''
  const toText = typeof rawTo === 'string' ? rawTo : ''
  const hasDates = fromText !== '' || toText !== ''
  const datesValid =
    atMidnight(fromText) !== null &&
    atMidnight(toText) !== null &&
    spanDays(fromText, toText) > 0 &&
    spanDays(fromText, toText) <= MAX_SPAN_DAYS
  if (hasDates && !datesValid) {
    if (fromText !== '') dropped.push('from')
    if (toText !== '') dropped.push('to')
  }

  // 预设只是窗口快捷方式：显式日期一旦合法就以日期为准，再反推 preset
  if (datesValid) {
    const span = spanDays(fromText, toText)
    const matched = (Object.keys(PRESET_DAYS) as Array<Exclude<PresetKey, 'custom'>>).find(
      (key) => PRESET_DAYS[key] === span && toText === isoDate(today),
    )
    const resolved: PresetKey = preset === 'custom' ? 'custom' : (matched ?? 'custom')
    return {
      filter: { domainId, vendorId, from: fromText, to: toText, preset: resolved },
      dropped,
    }
  }

  if (preset === 'custom' || preset === null) {
    // 界面上「自定义」却没有任何合法日期 = 半截 URL，按默认重来并记一笔
    if (preset === 'custom' && !dropped.includes('from') && !dropped.includes('to')) dropped.push('preset')
    return { filter: { ...defaultFilter(today), domainId, vendorId }, dropped }
  }

  // 只有预设：由预设算出窗口，写回 URL 时三键齐全（刷新可还原）
  return { filter: { domainId, vendorId, preset, ...rangeOfPreset(preset, today) }, dropped }
}

/** 筛选 → URL query（五键齐全，刷新/复制链接不依赖内存默认值） */
export function filterToQuery(filter: AnalyticsFilter): Record<string, string> {
  const query: Record<string, string> = {
    from: filter.from,
    to: filter.to,
    preset: filter.preset,
  }
  if (filter.domainId !== null) query.domainId = String(filter.domainId)
  if (filter.vendorId !== null) query.vendorId = String(filter.vendorId)
  return query
}

/** 预设与日期对齐后的合法筛选（applyToRoute 的统一入口） */
export function normalizeFilter(input: Partial<AnalyticsFilter>, today = new Date()): AnalyticsFilter {
  const preset =
    input.preset && PRESETS.includes(input.preset) ? input.preset : ('30d' as PresetKey)
  const domainId = input.domainId ?? null
  const vendorId = input.vendorId ?? null

  const fromText = input.from ?? ''
  const toText = input.to ?? ''
  const datesValid =
    atMidnight(fromText) !== null &&
    atMidnight(toText) !== null &&
    spanDays(fromText, toText) > 0 &&
    spanDays(fromText, toText) <= MAX_SPAN_DAYS

  if (preset === 'custom') {
    if (datesValid) return { domainId, vendorId, preset, from: fromText, to: toText }
    return { domainId, vendorId, preset: '30d', ...rangeOfPreset('30d', today) }
  }
  // 预设优先：窗口由预设重算，避免「preset=7d 但 from/to 还是 30 天」的半截状态
  return { domainId, vendorId, preset, ...rangeOfPreset(preset, today) }
}

function signature(query: Record<string, unknown>): string {
  const keys = ['domainId', 'vendorId', 'from', 'to', 'preset']
  return keys.map((key) => `${key}=${String(pickFirst(query[key]) ?? '')}`).join('&')
}

export const useAnalyticsStore = defineStore('analytics', () => {
  /** 最近一次 syncFromQuery 拿到的路由；query 是响应式的，filter 随 URL 自动更新 */
  const route = shallowRef<RouteLocationNormalizedLoaded | null>(null)
  const cacheState = ref<CacheState | ''>('')
  const dataAsOf = ref('')
  /** 请求失败的可读原因（区级/内联提示共用，不弹全局框） */
  const lastError = ref('')
  const pending = ref(false)

  const parsed = computed(() => filterFromQuery((route.value?.query ?? {}) as Record<string, unknown>))
  const filter = computed<AnalyticsFilter>(() => parsed.value.filter)
  const droppedKeys = computed<string[]>(() => parsed.value.dropped)
  /** 发给后端的最小参数集（null 由 buildQuery 丢弃 → 本域/全部厂商） */
  const query = computed<AnalyticsQuery>(() => ({ ...filter.value }))

  let timer: ReturnType<typeof setTimeout> | null = null
  let inflight: AbortController | null = null

  /** 页面挂载/路由变化时调用；幂等（只换引用，不装 watcher） */
  function syncFromQuery(current: RouteLocationNormalizedLoaded): void {
    route.value = current
  }

  function presetOf(value: Partial<AnalyticsFilter> = filter.value): PresetKey {
    if (value.preset && PRESETS.includes(value.preset)) return value.preset
    if (!value.from || !value.to) return '30d'
    const span = spanDays(value.from, value.to)
    const matched = (Object.keys(PRESET_DAYS) as Array<Exclude<PresetKey, 'custom'>>).find(
      (key) => PRESET_DAYS[key] === span && value.to === isoDate(new Date()),
    )
    return matched ?? 'custom'
  }

  /**
   * 改筛选 → `router.replace`（不污染历史）。
   * 与当前 URL 同签名时**不导航**，避免 watch(route.query) ↔ applyToRoute 互相触发。
   */
  function applyToRoute(router: Router, patch: Partial<AnalyticsFilter> = {}): void {
    const next = normalizeFilter({ ...filter.value, ...patch })
    const current = router.currentRoute.value.query as Record<string, unknown>
    const merged: Record<string, string> = { ...filterToQuery(next) }
    for (const [key, value] of Object.entries(current)) {
      if (key === 'domainId' || key === 'vendorId' || key === 'from' || key === 'to' || key === 'preset') continue
      const flat = pickFirst(value)
      if (flat !== undefined && flat !== null) merged[key] = String(flat)
    }
    if (signature(merged) === signature(current)) return
    const target: RouteLocationRaw = { query: merged }
    void router.replace(target)
  }

  /** 回到默认（本域 + 近 30 天） */
  function resetFilter(router: Router): void {
    applyToRoute(router, defaultFilter())
  }

  /** 服务端响应回填缓存态与数据时间（body 字段，不读响应头） */
  function noteResponse(meta: { cache?: string | null; dataAsOf?: string | null }): void {
    cacheState.value = meta.cache === 'HIT' || meta.cache === 'MISS' ? meta.cache : ''
    dataAsOf.value = meta.dataAsOf ?? ''
  }

  function noteError(message: string): void {
    lastError.value = message
  }

  function cancelInflight(): void {
    if (inflight) {
      inflight.abort()
      inflight = null
    }
    pending.value = false
  }

  /**
   * 300ms 合并 + 取消在途：筛选连点时只发最后一次；旧请求被 abort，
   * 页面里 `signal.aborted` 的 catch 直接 return（不弹错，也不清空旧结果）。
   */
  function schedule(task: (signal: AbortSignal) => void | Promise<void>, wait = DEBOUNCE_MS): void {
    if (timer) clearTimeout(timer)
    cancelInflight()
    pending.value = true
    timer = setTimeout(() => {
      timer = null
      const controller = new AbortController()
      inflight = controller
      const done = (): void => {
        if (inflight === controller) {
          inflight = null
          pending.value = false
        }
      }
      void Promise.resolve()
        .then(() => task(controller.signal))
        .catch((error: unknown) => {
          // abort 是正常路径（用户改了筛选），不记错误
          if (controller.signal.aborted) return
          lastError.value = error instanceof Error ? error.message : String(error)
        })
        .finally(done)
    }, wait)
  }

  /** setup store 没有内置 $reset：清瞬态；filter 由 URL 决定，回默认需经 resetFilter */
  function $reset(): void {
    if (timer) clearTimeout(timer)
    timer = null
    cancelInflight()
    route.value = null
    cacheState.value = ''
    dataAsOf.value = ''
    lastError.value = ''
  }

  return {
    filter,
    query,
    cacheState,
    dataAsOf,
    lastError,
    pending,
    droppedKeys,
    syncFromQuery,
    presetOf,
    applyToRoute,
    resetFilter,
    noteResponse,
    noteError,
    cancelInflight,
    schedule,
    $reset,
  }
})
