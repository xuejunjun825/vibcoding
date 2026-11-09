import { ref, watch, type Ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import type { GanttView, GanttZoom } from '@/utils/ganttScale'

/**
 * 协作域的个人偏好（TECHNICAL §4.3）：甘特的视图/缩放/折叠集合、Wiki 的展开集合与最近页面。
 *
 * 只放「换个浏览器就没了也无所谓」的状态；任何影响判定的东西（权限、可见性、指标）
 * 一律回服务端取。键里带 tenantId + userId：同一台机器上换账号不会串味。
 * localStorage 不可用（隐私模式 / SSR）时静默降级为内存态。
 */

const PREFIX = 'collab'

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = window.localStorage.getItem(key)
    if (!raw) return fallback
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

function writeJson(key: string, value: unknown): void {
  try {
    window.localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* 隐私模式下写入被拒：本次会话内仍然生效（内存态） */
  }
}

function scopeKey(auth: ReturnType<typeof useAuthStore>, area: 'gantt' | 'wiki'): string {
  const tenant = auth.tenantId || auth.user?.tenantId || 'anon'
  const user = auth.user?.id ?? 'anon'
  return `${PREFIX}:${area}:${tenant}:${user}`
}

interface GanttPrefs {
  view: GanttView
  zoom: GanttZoom
  /** 被折叠的节点键 `TYPE:id`；空集合 = 全部展开（与后端 `expanded` 留空同义） */
  collapsed: string[]
}

interface WikiPrefs {
  expanded: string[]
  spaceId: number | null
  pageId: number | null
}

const GANTT_DEFAULT: GanttPrefs = { view: 'RELEASE', zoom: 'MONTH', collapsed: [] }
const WIKI_DEFAULT: WikiPrefs = { expanded: [], spaceId: null, pageId: null }

export function useGanttPrefs(): {
  view: Ref<GanttView>
  zoom: Ref<GanttZoom>
  collapsed: Ref<string[]>
  isCollapsed: (key: string) => boolean
  toggle: (key: string) => void
  collapseAll: (keys: string[]) => void
  expandAll: () => void
} {
  const auth = useAuthStore()
  const key = scopeKey(auth, 'gantt')
  const cached = readJson<GanttPrefs>(key, GANTT_DEFAULT)

  const view = ref<GanttView>(cached.view ?? GANTT_DEFAULT.view)
  const zoom = ref<GanttZoom>(cached.zoom ?? GANTT_DEFAULT.zoom)
  const collapsed = ref<string[]>(Array.isArray(cached.collapsed) ? cached.collapsed : [])

  watch(
    [view, zoom, collapsed],
    () => writeJson(key, { view: view.value, zoom: zoom.value, collapsed: collapsed.value }),
    { deep: true },
  )

  function isCollapsed(nodeKey: string): boolean {
    return collapsed.value.includes(nodeKey)
  }

  function toggle(nodeKey: string): void {
    collapsed.value = isCollapsed(nodeKey)
      ? collapsed.value.filter((item) => item !== nodeKey)
      : [...collapsed.value, nodeKey]
  }

  function collapseAll(keys: string[]): void {
    collapsed.value = [...new Set(keys)]
  }

  function expandAll(): void {
    collapsed.value = []
  }

  return { view, zoom, collapsed, isCollapsed, toggle, collapseAll, expandAll }
}

export function useWikiPrefs(): {
  expanded: Ref<string[]>
  spaceId: Ref<number | null>
  pageId: Ref<number | null>
  isExpanded: (key: string) => boolean
  toggle: (key: string) => void
  expandTo: (keys: string[]) => void
  remember: (spaceId: number | null, pageId: number | null) => void
} {
  const auth = useAuthStore()
  const key = scopeKey(auth, 'wiki')
  const cached = readJson<WikiPrefs>(key, WIKI_DEFAULT)

  const expanded = ref<string[]>(Array.isArray(cached.expanded) ? cached.expanded : [])
  const spaceId = ref<number | null>(cached.spaceId ?? null)
  const pageId = ref<number | null>(cached.pageId ?? null)

  watch(
    [expanded, spaceId, pageId],
    () => writeJson(key, { expanded: expanded.value, spaceId: spaceId.value, pageId: pageId.value }),
    { deep: true },
  )

  function isExpanded(nodeKey: string): boolean {
    return expanded.value.includes(nodeKey)
  }

  function toggle(nodeKey: string): void {
    expanded.value = isExpanded(nodeKey)
      ? expanded.value.filter((item) => item !== nodeKey)
      : [...expanded.value, nodeKey]
  }

  /** 打开某页时把它的祖先路径展开（否则树里看不见当前页） */
  function expandTo(keys: string[]): void {
    expanded.value = [...new Set([...expanded.value, ...keys])]
  }

  function remember(nextSpaceId: number | null, nextPageId: number | null): void {
    spaceId.value = nextSpaceId
    pageId.value = nextPageId
  }

  return { expanded, spaceId, pageId, isExpanded, toggle, expandTo, remember }
}
