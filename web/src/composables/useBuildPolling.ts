import { computed, onUnmounted, ref, toValue, watch, type ComputedRef, type Ref } from 'vue'
import { isActiveBuild, listBuilds, type BuildView } from '@/api/integration'

/**
 * 构建轮询（M7 · TECHNICAL §8.2 / DESIGN §1.4）。
 *
 * 规则只有三条，但每条都关乎"别把服务打穿、别让卡留在旧状态"：
 * 1. **只在有活动构建时开表**：`QUEUED`/`RUNNING` 存在才每 5s 拉一次；
 *    一旦全部终态（SUCCESS/FAILURE/ABORTED）立刻 `clearInterval`（终态不会再变，
 *    继续轮询只是把 req-service 与 Jenkins 一起拖慢）；
 * 2. **随卸载停止**：`onUnmounted` 里清定时器 + 摘 visibilitychange 监听，
 *    页面切走还在跑的定时器是真实的泄漏（同一单反复进出会叠出多条链）；
 * 3. **页面不可见就暂停**：`document.hidden` 时 tick 直接返回（不发请求），
 *    重新可见时立刻补拉一次 —— 用户回到页面看到的不是 10 分钟前的状态。
 *
 * 轮询本身是"交互读"路径上的后台刷新：失败只降级（保留上一次的 builds，`error` 置位供卡上提示），
 * 不清空列表、不抛异常 —— 与 `client.ts` 的 409 约定一致，页面负责渲染而不是这里弹 toast。
 */
export interface UseBuildPollingOptions {
  /** 轮询间隔，默认 5000ms（DESIGN：5s） */
  intervalMs?: number
  /** 最近 N 条，默认 5（构建卡只显示 5 条） */
  limit?: number
  /** 首次是否立刻拉取，默认 true */
  immediate?: boolean
}

export interface UseBuildPolling {
  /** 最近 N 条构建（新到旧，顺序由服务端决定，前端不排序） */
  builds: Ref<BuildView[]>
  loading: Ref<boolean>
  /** 上一次拉取的错误（降级提示用；成功后清空） */
  error: Ref<unknown>
  /** 定时器是否在跑：卡上可据此显示"自动刷新中" */
  polling: Ref<boolean>
  /** 当前活动构建（QUEUED/RUNNING），没有则 null */
  activeBuild: ComputedRef<BuildView | null>
  /** 立即拉一次（触发构建 202 后、点刷新时调用） */
  refresh: () => Promise<void>
  /** 手动开表（通常不需要：有活动构建时 refresh 会自动开） */
  start: () => void
  /** 手动停表（离开构建卡、或用户关掉自动刷新） */
  stop: () => void
}

export function useBuildPolling(
  orderId: number | Ref<number>,
  options: UseBuildPollingOptions = {},
): UseBuildPolling {
  const intervalMs = options.intervalMs ?? 5000
  const limit = options.limit ?? 5

  const builds = ref<BuildView[]>([])
  const loading = ref(false)
  const error = ref<unknown>(null)
  const polling = ref(false)

  const activeBuild = computed<BuildView | null>(
    () => builds.value.find((build) => isActiveBuild(build.status)) ?? null,
  )

  let timer: ReturnType<typeof setInterval> | null = null
  let inFlight = false
  let disposed = false

  function stop(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
    polling.value = false
  }

  function start(): void {
    // 没有活动构建就不开表：终态/无构建时轮询是没有意义的负载
    if (disposed || timer !== null || !activeBuild.value) return
    timer = setInterval(() => {
      if (document.hidden) return // 不可见就暂停发请求，定时器保留
      void refresh()
    }, intervalMs)
    polling.value = true
  }

  /** 每次拉取后按最新状态决定开/停：终态立即停，无需等下一个 tick */
  function syncTimer(): void {
    if (activeBuild.value) start()
    else stop()
  }

  async function refresh(): Promise<void> {
    if (disposed) return
    const id = toValue(orderId)
    if (!Number.isFinite(id) || id <= 0) {
      builds.value = []
      syncTimer()
      return
    }
    // 同一时刻只允许一个在途请求：慢网络下 tick 叠加会拿到乱序结果
    if (inFlight) return
    inFlight = true
    loading.value = true
    try {
      builds.value = await listBuilds(id, limit)
      error.value = null
    } catch (err) {
      // 降级：保留旧列表（卡不闪成空态），只把错误交给页面渲染
      error.value = err
    } finally {
      inFlight = false
      loading.value = false
      syncTimer()
    }
  }

  function onVisibilityChange(): void {
    if (document.hidden || disposed) return
    void refresh() // 回到页面立刻补拉一次，再让定时器接手
  }

  document.addEventListener('visibilitychange', onVisibilityChange)

  watch(
    () => toValue(orderId),
    (next, prev) => {
      if (next === prev) return
      builds.value = [] // 换了单据：先清空，避免上一单的构建挂在这一单上
      stop()
      void refresh()
    },
  )

  if (options.immediate !== false) void refresh()

  onUnmounted(() => {
    disposed = true
    stop()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return { builds, loading, error, polling, activeBuild, refresh, start, stop }
}
