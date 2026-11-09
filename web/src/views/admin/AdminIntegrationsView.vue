<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ConfigTab from './integrations/ConfigTab.vue'
import AdmissionTab from './integrations/AdmissionTab.vue'
import LogsTab from './integrations/LogsTab.vue'

/**
 * 管理端 › 集成配置（DESIGN I2 / §6）。
 *
 * 一页三 tab，tab 与深链参数都放在 query 上：
 * - `?tab=config|admission|logs`：一个页面三份内容，地址可分享、刷新不丢；
 * - `?correlationId=`：从业务页「查看同步记录」跳进来时，日志 tab 直接按该关联 ID 过滤。
 *
 * 职责边界：本文件只决定「显示哪个 tab + 传哪些查询参数」，三份内容各自的加载、错误态
 * 都在 tab 组件里，父层不做数据编排 —— 否则切 tab 会把上一份请求的 loading 带过去。
 */
type TabKey = 'config' | 'admission' | 'logs'

const TABS: Array<{ key: TabKey; label: string; hint: string }> = [
  { key: 'config', label: '集成配置', hint: '四类系统的地址、凭据与启停' },
  { key: 'admission', label: '准入项', hint: '9 项准入的开关与锁定说明' },
  { key: 'logs', label: '同步日志', hint: '出站调用、重试与脱敏摘要' },
]

const route = useRoute()
const router = useRouter()

const tab = computed<TabKey>(() => {
  const raw = String(route.query.tab ?? '')
  return raw === 'admission' || raw === 'logs' ? raw : 'config'
})

const activeHint = computed(() => TABS.find((item) => item.key === tab.value)?.hint ?? '')

/** 深链参数只从路由读一次：日志 tab 内部之后自己维护筛选，不再回写 URL（避免每次翻页都改地址） */
const correlationId = computed(() => {
  const raw = route.query.correlationId
  return typeof raw === 'string' ? raw : ''
})

function select(next: TabKey): void {
  if (next === tab.value) return
  const query: Record<string, string | string[]> = { ...route.query, tab: next }
  if (next !== 'logs') delete query.correlationId
  void router.replace({ query })
}

/** 左右方向键切 tab（WAI-ARIA tabs 模式；Home/End 不是必需的，收益低于噪音） */
function onKeydown(event: KeyboardEvent, index: number): void {
  const delta = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
  if (!delta) return
  event.preventDefault()
  const next = TABS[(index + delta + TABS.length) % TABS.length]
  select(next.key)
  requestAnimationFrame(() => document.getElementById(`intg-tab-${next.key}`)?.focus())
}

function clearCorrelation(): void {
  const query = { ...route.query }
  delete query.correlationId
  void router.replace({ query })
}
</script>

<template>
  <div class="intg">
    <div class="intg__bar">
      <div class="tabs" role="tablist" aria-label="集成配置视图">
        <button
          v-for="(item, index) in TABS"
          :id="`intg-tab-${item.key}`"
          :key="item.key"
          type="button"
          role="tab"
          class="tabs__item"
          :class="{ 'tabs__item--active': tab === item.key }"
          :aria-selected="tab === item.key"
          :aria-controls="`intg-panel-${item.key}`"
          :tabindex="tab === item.key ? 0 : -1"
          @click="select(item.key)"
          @keydown="onKeydown($event, index)"
        >
          {{ item.label }}
        </button>
      </div>
      <div class="intg__spacer" />
      <p class="intg__hint">{{ activeHint }}</p>
    </div>

    <div
      :id="`intg-panel-${tab}`"
      class="intg__panel"
      role="tabpanel"
      :aria-labelledby="`intg-tab-${tab}`"
    >
      <ConfigTab v-if="tab === 'config'" />
      <AdmissionTab v-else-if="tab === 'admission'" />
      <LogsTab v-else :correlation-id="correlationId" @clear-correlation="clearCorrelation" />
    </div>
  </div>
</template>

<style scoped>
.intg {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.intg__bar {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}

.intg__spacer {
  flex: 1;
}

.intg__hint {
  margin: 0 0 8px;
  color: var(--text-muted-aa);
  font-size: 12px;
}

@media (max-width: 960px) {
  .intg__hint {
    display: none;
  }
}
</style>
