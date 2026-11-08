import { computed, onMounted, ref, watch, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/client'
import type { PageResponse } from '@/api/types'

/**
 * 列表状态（TECHNICAL §6.5 / AC-20）：
 * filters + page + size + sort 双向同步 URL —— 刷新、复制链接、前进后退都能复现同一屏。
 * 组件里只保留 rows/total/loading，筛选条件永远来自 URL。
 */
export interface ListQuery {
  page: number
  size: number
  sort?: string
  filters: Record<string, string>
}

export interface UseListQuery<T> {
  rows: Ref<T[]>
  total: Ref<number>
  loading: Ref<boolean>
  error: Ref<ApiError | null>
  page: Ref<number>
  size: Ref<number>
  sort: Ref<string>
  filters: Ref<Record<string, string>>
  isEmpty: Ref<boolean>
  isFiltered: Ref<boolean>
  load: () => Promise<void>
  refresh: () => Promise<void>
  setPage: (value: number) => Promise<void>
  setSize: (value: number) => Promise<void>
  setFilter: (key: string, value: string | number | undefined | null) => Promise<void>
  setFilters: (patch: Record<string, string | number | undefined | null>) => Promise<void>
  setSort: (value: string) => Promise<void>
  reset: () => Promise<void>
}

interface Options {
  size?: number
  /** 关闭 URL 同步（如弹窗内的一次性列表） */
  syncUrl?: boolean
  /** 挂载时自动加载，默认 true */
  immediate?: boolean
}

function toPositiveInt(raw: unknown, fallback: number): number {
  const value = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(value) && value > 0 ? Math.floor(value) : fallback
}

export function useListQuery<T>(
  fetcher: (query: ListQuery) => Promise<PageResponse<T>>,
  defaults: Record<string, string> = {},
  options: Options = {},
): UseListQuery<T> {
  const route = useRoute()
  const router = useRouter()
  const defaultSize = options.size ?? 20
  const syncUrl = options.syncUrl !== false

  const rows = ref([]) as Ref<T[]>
  const total = ref(0)
  const loading = ref(false)
  const error = ref<ApiError | null>(null)
  const page = ref(1)
  const size = ref(defaultSize)
  const sort = ref('')
  const filters = ref<Record<string, string>>({ ...defaults })

  let requestId = 0
  let writtenSignature = ''

  const query = computed<ListQuery>(() => ({
    page: page.value,
    size: size.value,
    sort: sort.value || undefined,
    filters: { ...filters.value },
  }))

  const isEmpty = computed(() => !loading.value && rows.value.length === 0)
  const isFiltered = computed(() =>
    Object.entries(filters.value).some(([key, value]) => value !== '' && value !== (defaults[key] ?? '')),
  )

  function signature(): string {
    return JSON.stringify({ page: page.value, size: size.value, sort: sort.value, filters: filters.value })
  }

  function urlQuery(): Record<string, string> {
    const result: Record<string, string> = {}
    for (const [key, value] of Object.entries(filters.value)) {
      if (value !== undefined && value !== null && value !== '') result[key] = String(value)
    }
    if (page.value > 1) result.page = String(page.value)
    if (size.value !== defaultSize) result.size = String(size.value)
    if (sort.value) result.sort = sort.value
    return result
  }

  function readFromUrl(): boolean {
    const raw = route.query
    const nextFilters: Record<string, string> = { ...defaults }
    for (const [key, value] of Object.entries(raw)) {
      if (key === 'page' || key === 'size' || key === 'sort') continue
      const single = Array.isArray(value) ? value[value.length - 1] : value
      if (typeof single === 'string' && single !== '') nextFilters[key] = single
    }
    const nextPage = toPositiveInt(raw.page, 1)
    const nextSize = toPositiveInt(raw.size, defaultSize)
    const nextSort = typeof raw.sort === 'string' ? raw.sort : ''

    const changed =
      JSON.stringify(nextFilters) !== JSON.stringify(filters.value) ||
      nextPage !== page.value ||
      nextSize !== size.value ||
      nextSort !== sort.value

    if (changed) {
      filters.value = nextFilters
      page.value = nextPage
      size.value = nextSize
      sort.value = nextSort
    }
    return changed
  }

  async function writeUrl(): Promise<void> {
    if (!syncUrl) return
    const next = urlQuery()
    if (JSON.stringify(next) === JSON.stringify(route.query)) return
    writtenSignature = signature()
    await router.replace({ query: next })
  }

  async function load(): Promise<void> {
    const current = ++requestId
    loading.value = true
    error.value = null
    try {
      const response = await fetcher(query.value)
      if (current !== requestId) return
      rows.value = response?.items ?? []
      total.value = response?.total ?? rows.value.length
    } catch (caught) {
      if (current !== requestId) return
      error.value =
        caught instanceof ApiError
          ? caught
          : new ApiError('UNKNOWN', '加载失败，请重试', 0, caught)
      // 保留旧数据：翻页失败时用户不该看到一片空白
    } finally {
      if (current === requestId) loading.value = false
    }
  }

  async function apply(): Promise<void> {
    await writeUrl()
    await load()
  }

  async function setPage(value: number): Promise<void> {
    if (value === page.value) return
    page.value = Math.max(1, Math.floor(value))
    await apply()
  }

  async function setSize(value: number): Promise<void> {
    if (value === size.value) return
    size.value = Math.max(1, Math.floor(value))
    page.value = 1
    await apply()
  }

  async function setFilter(key: string, value: string | number | undefined | null): Promise<void> {
    const next = value === undefined || value === null ? '' : String(value)
    if ((filters.value[key] ?? '') === next) return
    filters.value = { ...filters.value, [key]: next }
    page.value = 1
    await apply()
  }

  async function setFilters(
    patch: Record<string, string | number | undefined | null>,
  ): Promise<void> {
    const next = { ...filters.value }
    for (const [key, value] of Object.entries(patch)) {
      next[key] = value === undefined || value === null ? '' : String(value)
    }
    filters.value = next
    page.value = 1
    await apply()
  }

  async function setSort(value: string): Promise<void> {
    if (value === sort.value) return
    sort.value = value
    page.value = 1
    await apply()
  }

  async function reset(): Promise<void> {
    filters.value = { ...defaults }
    page.value = 1
    size.value = defaultSize
    sort.value = ''
    await apply()
  }

  watch(
    () => route.query,
    () => {
      if (!syncUrl) return
      // 自己写 URL 触发的变化不再回读，避免 replace → watch → fetch 的循环
      if (signature() === writtenSignature) return
      if (readFromUrl()) void load()
    },
  )

  if (options.immediate !== false) {
    onMounted(() => {
      readFromUrl()
      void load()
    })
  }

  return {
    rows,
    total,
    loading,
    error,
    page,
    size,
    sort,
    filters,
    isEmpty,
    isFiltered,
    load,
    refresh: load,
    setPage,
    setSize,
    setFilter,
    setFilters,
    setSort,
    reset,
  }
}
