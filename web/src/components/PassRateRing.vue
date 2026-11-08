<script setup lang="ts">
import { computed } from 'vue'
import { formatNumber } from '@/utils/format'

/**
 * 通过率环（DESIGN §1.2 几何自洽）：外径 96、stroke 8 → 内净空 80，
 * 环内只放 20px 数字 + 11px 标签，绝不放第二行说明。
 */
const props = withDefaults(
  defineProps<{
    passed: number
    planned: number
    coverage?: number | null
    label?: string
  }>(),
  { coverage: null, label: '通过率' },
)

const RADIUS = 40
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

const rate = computed(() => {
  if (!props.planned || props.planned <= 0) return null
  return Math.min(1, Math.max(0, props.passed / props.planned))
})

const dashOffset = computed(() =>
  rate.value === null ? CIRCUMFERENCE : CIRCUMFERENCE * (1 - rate.value),
)

const display = computed(() =>
  rate.value === null ? '—' : `${Math.round(rate.value * 100)}%`,
)
</script>

<template>
  <div class="ring">
    <svg class="ring__svg" viewBox="0 0 96 96" role="img" :aria-label="`${props.label} ${display}`">
      <circle class="ring__track" cx="48" cy="48" :r="RADIUS" fill="none" stroke-width="8" />
      <circle
        class="ring__value"
        cx="48"
        cy="48"
        :r="RADIUS"
        fill="none"
        stroke-width="8"
        stroke-linecap="butt"
        :stroke-dasharray="CIRCUMFERENCE"
        :stroke-dashoffset="dashOffset"
        transform="rotate(-90 48 48)"
      />
    </svg>
    <div class="ring__center">
      <span class="ring__number">{{ display }}</span>
      <span class="ring__label">{{ props.label }}</span>
    </div>
    <p class="ring__caption">
      已通过 {{ formatNumber(props.passed) }} / 计划 {{ formatNumber(props.planned) }}
      <span v-if="props.coverage !== null" class="ring__coverage">
        · 覆盖率 {{ Math.round(Number(props.coverage) * 100) }}%
      </span>
    </p>
  </div>
</template>

<style scoped>
.ring {
  position: relative;
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.ring__svg {
  width: 96px;
  height: 96px;
  display: block;
}
.ring__track {
  stroke: var(--divider);
}
.ring__value {
  stroke: var(--color-primary);
  transition: stroke-dashoffset var(--t-overlay) var(--ease);
}
.ring__center {
  position: absolute;
  margin-top: 0;
  width: 96px;
  height: 96px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  pointer-events: none;
}
.ring__number {
  font-size: 20px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.ring__label {
  font-size: 11px;
  line-height: 14px;
  color: var(--text-muted-aa);
}
.ring__caption {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}
.ring__coverage {
  color: var(--text-secondary);
}
</style>
