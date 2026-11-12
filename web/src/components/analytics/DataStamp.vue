<script setup lang="ts">
import { computed } from 'vue'

/**
 * 数据时间戳 + 缓存态（TECHNICAL §5.1 / AC-4）：三页同一口径，
 * 避免各自内联出三种格式。`dataAsOf` 由服务端放在 body 里给（G-7），
 * 这里只做展示格式化，不参与任何计算。
 *
 * 刷新期间**保留**上一次的时间戳与缓存态：这行是「数据可信度」的常驻信息，
 * 不能因为正在重取而消失（否则用户看到的是一段空白，而不是「正在更新」）。
 */
const props = withDefaults(
  defineProps<{
    dataAsOf?: string | null
    /** 线上是 `CacheState | string`（`''` = 未返回），故比 `CacheState` 宽一档 */
    cache?: string | null
    loading?: boolean
  }>(),
  { dataAsOf: '', cache: '', loading: false },
)

const stamp = computed(() => {
  const raw = (props.dataAsOf ?? '').trim()
  if (!raw) return ''
  const matched = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/.exec(raw)
  return matched ? `${matched[1]} ${matched[2]}` : raw
})

const cacheText = computed(() => (props.cache === 'HIT' || props.cache === 'MISS' ? props.cache : ''))
// r14 AC-4：徽标上屏只出中文；HIT/MISS 仅留作样式类与语义判断，不再出现在界面文本里
const cacheLabel = computed(() => (cacheText.value === 'HIT' ? '缓存命中' : cacheText.value === 'MISS' ? '缓存未命中' : ''))

const title = computed(() => {
  const head = stamp.value ? `数据截至 ${stamp.value}` : '数据截至时间未返回'
  const tail = cacheText.value
    ? ` · 缓存${cacheText.value === 'HIT' ? '命中' : '未命中'}`
    : ' · 缓存态未返回'
  return `${head}${tail}${props.loading ? '（正在更新）' : ''}`
})
</script>

<template>
  <span class="stamp" :title="title" data-testid="data-stamp">
    <span v-if="stamp" class="stamp__time">数据截至 {{ stamp }}</span>
    <span v-else class="stamp__time stamp__time--muted">数据截至 —</span>

    <span
      v-if="cacheText"
      class="stamp__cache"
      :class="cacheText === 'HIT' ? 'stamp__cache--hit' : 'stamp__cache--miss'"
      data-testid="data-stamp-cache"
    >
      {{ cacheLabel }}
    </span>

    <span v-if="props.loading" class="stamp__loading" data-testid="data-stamp-loading">
      <i class="stamp__dot" aria-hidden="true" />更新中
    </span>
  </span>
</template>

<style scoped>
.stamp {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted-aa);
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
  cursor: default;
}

.stamp__time {
  color: var(--text-muted-aa);
}

.stamp__time--muted {
  opacity: 0.75;
}

.stamp__cache {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  border-radius: var(--r-pill);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.stamp__cache--hit {
  background: var(--color-success-bg);
  color: var(--success-ink);
}

.stamp__cache--miss {
  background: var(--color-info-bg);
  color: var(--text-secondary);
}

.stamp__loading {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--text-secondary);
}

.stamp__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--color-primary);
  animation: stamp-pulse 1s var(--ease, ease-in-out) infinite;
}

@keyframes stamp-pulse {
  0%,
  100% {
    opacity: 0.25;
  }
  50% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .stamp__dot {
    animation: none;
    opacity: 0.8;
  }
}
</style>
