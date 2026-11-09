import { ref } from 'vue'

/**
 * 质量域跨页刷新信号（TECHNICAL §7.3）。
 *
 * 为什么是版本号而不是事件总线：计划、轮次、报告、门禁、指标五处数字都来自服务端同一份
 * 聚合（`QualityAggregator`），任何一处动作都可能让别的页面过期。页面只需 `watch(tick)`
 * 重新拉取，不必知道是谁改的，也不存在“漏订阅某个事件”的漂移。
 */
const tick = ref(0)

export function useQualityRefresh(): { tick: typeof tick; bump: () => void } {
  function bump(): void {
    tick.value += 1
  }
  return { tick, bump }
}
