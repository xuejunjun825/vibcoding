<script setup lang="ts">
/**
 * 执行器健康 pill：固定 88×22。
 * 五态（在线/滞后/离线/从未上报/已停用）全部由服务端 ExecutorHealth 派生，
 * 阈值只在后端一处；页面不做 24/72 小时计算。
 */
import { computed } from 'vue'
import { EXECUTOR_HEALTH_HINT, EXECUTOR_HEALTH_LABEL } from '@/api/deliver'

const props = withDefaults(
  defineProps<{
    health?: string | null
    lastReportAt?: string | null
  }>(),
  { health: 'NEVER', lastReportAt: null },
)

const tone = computed(() => {
  switch (props.health) {
    case 'ONLINE':
      return 'online'
    case 'STALE':
      return 'stale'
    case 'OFFLINE':
      return 'offline'
    case 'DISABLED':
      return 'disabled'
    default:
      return 'never'
  }
})

const text = computed(() => EXECUTOR_HEALTH_LABEL[props.health ?? ''] ?? '未知')

const hint = computed(() => {
  const base = EXECUTOR_HEALTH_HINT[props.health ?? ''] ?? '服务端未返回健康状态'
  return props.lastReportAt ? `${base} · 最近上报 ${props.lastReportAt}` : base
})
</script>

<template>
  <span class="exec-pill" :class="`exec-pill--${tone}`" role="status" :title="hint" :aria-label="`${text}：${hint}`">
    <span class="exec-pill__dot" aria-hidden="true" />
    {{ text }}
  </span>
</template>

<style scoped>
.exec-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  box-sizing: border-box;
  width: 88px;
  height: 22px;
  padding: 0 8px;
  border-radius: var(--r-pill, 999px);
  border: 1px solid transparent;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
  flex: none;
}

.exec-pill__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}

.exec-pill--online {
  color: var(--success-ink, #157a38);
  background: var(--color-success-bg, #e8f7ec);
  border-color: color-mix(in srgb, var(--color-success, #1f9d45) 22%, transparent);
}

.exec-pill--stale {
  color: var(--warning-ink, #9a5b00);
  background: var(--color-warning-bg, #fff7e6);
  border-color: color-mix(in srgb, var(--color-warning, #c77700) 24%, transparent);
}

.exec-pill--offline {
  color: var(--danger-ink, #b3262e);
  background: var(--color-danger-bg, #fdecec);
  border-color: color-mix(in srgb, var(--color-danger, #d9363e) 22%, transparent);
}

.exec-pill--never {
  color: var(--info-ink, #4e5969);
  background: var(--color-info-bg, #f2f3f5);
  border-color: var(--border, #e5e6eb);
}

.exec-pill--disabled {
  color: #8a919c;
  background: #fafbfc;
  border: 1px dashed var(--border-strong, #c9cdd4);
}

.exec-pill--disabled .exec-pill__dot {
  background: transparent;
  border: 1px solid currentColor;
  box-sizing: border-box;
}
</style>
