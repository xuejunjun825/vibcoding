import { onScopeDispose, ref, watch, type Ref } from 'vue'
import { ApiError } from '@/api/client'
import { DEBOUNCE_MS, useAnalyticsStore } from '@/stores/analytics'

/**
 * 分析域取数原语（TECHNICAL §5.1 / AC-7）。
 *
 * 三页（/portal、/dashboard、编辑器预览）取数逻辑相同：watch `store.query`
 * → 300ms 合并 → abort 旧请求 → 用 `seq` 丢弃过期响应。把它收成一处，
 * 视图里就长不出第二套防抖/竞态代码。
 *
 * 分工：防抖窗口与 `AbortController` 由 store 持有（`store.schedule`），
 * 本层只负责「何时发起」和「谁的结果才算数」。两者合起来保证：
 * 300ms 内连点 N 次筛选 = 1 次请求；先发的响应即使晚到也不覆盖后发的。
 */
export type FeedStatus = 'idle' | 'loading' | 'ready' | 'error'

export interface AnalyticsFeed<T> {
  data: Ref<T | null>
  status: Ref<FeedStatus>
  error: Ref<ApiError | null>
  /** debounce=false 用于「重试」按钮：用户已明确点击，不再等 300ms */
  load: (opts?: { debounce?: boolean }) => void
  reload: () => void
}

/** 服务端把缓存态与数据时间放在 body 里（G-7：不读响应头），各响应类型字段同名 */
interface FeedMeta {
  dataAsOf?: string | null
  cache?: string | null
}

export function useAnalyticsFeed<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  /**
   * `enabled`：视图声明「现在还不能取数」（编辑器尚未保存出报表 id 时为 false）。
   * 放在这里而不是视图里，是为了让「何时允许发请求」也只有一处判断——
   * 否则编辑器又会长出第二套 `if (!canRun) return` 的守卫。
   */
  opts: { immediate?: boolean; enabled?: () => boolean } = {},
): AnalyticsFeed<T> {
  const store = useAnalyticsStore()
  const data = ref(null) as Ref<T | null>
  const status = ref<FeedStatus>('idle')
  const error = ref<ApiError | null>(null)

  /** 单调序号：abort 之外的第二道保险（响应可能在 abort 之前就已落地） */
  let seq = 0
  /** 卸载后仍在排队的 300ms 窗口不得再发请求（否则离开页面还会打后端一番） */
  let disposed = false

  function load(options: { debounce?: boolean } = {}): void {
    if (disposed) return
    if (opts.enabled && !opts.enabled()) return
    const current = ++seq
    status.value = 'loading'
    error.value = null
    store.schedule(async (signal) => {
      // 计时器可能在 scope 销毁之后才到期
      if (disposed) return
      try {
        const payload = await fetcher(signal)
        if (signal.aborted || current !== seq) return
        const meta = (payload ?? {}) as FeedMeta
        data.value = payload
        error.value = null
        status.value = 'ready'
        store.noteResponse({ cache: meta.cache ?? '', dataAsOf: meta.dataAsOf ?? '' })
      } catch (cause) {
        // abort 是正常路径（用户刚改了筛选），既不报错也不清空旧数据
        if (signal.aborted || current !== seq) return
        const apiError =
          cause instanceof ApiError ? cause : new ApiError('UNKNOWN', String(cause), 0, cause)
        error.value = apiError
        status.value = 'error'
        store.noteError(apiError.message)
      }
    }, options.debounce === false ? 0 : DEBOUNCE_MS)
  }

  function reload(): void {
    load({ debounce: false })
  }

  // 唯一的触发源：筛选（= URL）变了就重取，视图无需自己 watch
  watch(() => store.query, () => load(), { deep: true })

  if (opts.immediate !== false) reload()

  onScopeDispose(() => {
    disposed = true
    seq += 1
    // 离开页面后任何在途响应都不许再写 ref，也不许再占着 pending
    store.cancelInflight()
  })

  return { data, status, error, load, reload }
}
