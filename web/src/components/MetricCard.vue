<script setup lang="ts">
import { computed } from 'vue'
import type { MetricDefinition } from '@/api/types'
import { formatNumber } from '@/utils/format'

/**
 * 指标卡（DESIGN §5 / AC-18）：
 * 每个数字都带可展开的「口径」（定义版本 + 公式 + 备注），
 * 并且 clickable 时一定给下钻 —— 聚合数字不可点是反模式。
 */
const props = withDefaults(
  defineProps<{
    label: string
    value: number | string | null | undefined
    unit?: string
    digits?: number
    definition?: MetricDefinition | null
    hint?: string
    to?: string
    /** 允许下钻但由父组件接管（emit drill） */
    drillable?: boolean
    tone?: 'default' | 'warning' | 'danger'
    loading?: boolean
  }>(),
  {
    unit: '',
    digits: 0,
    definition: null,
    hint: '',
    to: '',
    drillable: false,
    tone: 'default',
    loading: false,
  },
)

const emit = defineEmits<{ (event: 'drill'): void }>()

const displayValue = computed(() => {
  if (props.loading) return '—'
  if (props.value === null || props.value === undefined || props.value === '') return '—'
  return formatNumber(props.value, props.digits)
})

const clickable = computed(() => Boolean(props.to) || props.drillable)

function activate(): void {
  if (!clickable.value) return
  emit('drill')
}
</script>

<template>
  <div
    class="metric"
    :class="[`metric--${props.tone}`, { 'metric--clickable': clickable }]"
    :role="clickable ? 'button' : undefined"
    :tabindex="clickable ? 0 : undefined"
    @click="activate"
    @keydown.enter="activate"
  >
    <div class="metric__head">
      <span class="metric__label">{{ props.label }}</span>
      <el-popover
        v-if="props.definition"
        placement="top"
        trigger="hover"
        :width="300"
        popper-class="metric-pop"
      >
        <template #reference>
          <button type="button" class="metric__info" aria-label="查看指标口径" @click.stop>
            <svg viewBox="0 0 16 16" aria-hidden="true">
              <circle cx="8" cy="8" r="6.2" fill="none" stroke="currentColor" stroke-width="1.3" />
              <path d="M8 7.2v4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
              <circle cx="8" cy="4.9" r="0.8" fill="currentColor" />
            </svg>
          </button>
        </template>
        <div class="def">
          <p class="def__title">{{ props.definition.name }}</p>
          <p class="def__formula">{{ props.definition.formula }}</p>
          <p v-if="props.definition.caliberNote" class="def__note">
            {{ props.definition.caliberNote }}
          </p>
          <div class="def__foot">
            <span>口径版本 v{{ props.definition.version ?? 1 }}</span>
            <span v-if="props.definition.updatedAt">{{ props.definition.updatedAt }}</span>
          </div>
        </div>
      </el-popover>
    </div>

    <div class="metric__value">
      {{ displayValue }}<span v-if="props.unit && displayValue !== '—'" class="metric__unit">{{ props.unit }}</span>
    </div>

    <div class="metric__foot">
      <span v-if="props.hint" class="metric__hint">{{ props.hint }}</span>
      <slot />
      <span v-if="clickable" class="metric__drill">查看明细</span>
    </div>
  </div>
</template>

<style scoped>
.metric {
  min-width: 0;
  padding: 14px 16px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  transition:
    border-color var(--t-color) var(--ease),
    box-shadow var(--t-color) var(--ease);
}
.metric--clickable {
  cursor: pointer;
}
.metric--clickable:hover {
  border-color: #c9d6ee;
  box-shadow: var(--shadow-card-hover);
}
.metric--clickable:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.metric__head {
  display: flex;
  align-items: center;
  gap: 4px;
}
.metric__label {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.metric__info {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text-weak);
  cursor: help;
}
.metric__info:hover {
  color: var(--text-body);
}
.metric__info svg {
  width: 13px;
  height: 13px;
}
.metric__value {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 600;
  line-height: 30px;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.metric--warning .metric__value {
  color: var(--warning-ink);
}
.metric--danger .metric__value {
  color: var(--danger-ink);
}
.metric__unit {
  margin-left: 3px;
  font-size: 12px;
  font-weight: 400;
  color: var(--text-muted-aa);
}
.metric__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  min-height: 16px;
  font-size: 12px;
  color: var(--text-weak);
}
.metric__drill {
  margin-left: auto;
  color: var(--color-primary);
  opacity: 0;
  transition: opacity var(--t-color) var(--ease);
}
.metric--clickable:hover .metric__drill,
.metric--clickable:focus-visible .metric__drill {
  opacity: 1;
}
.def__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}
.def__formula {
  margin-top: 6px;
  padding: 6px 8px;
  background: var(--bg-table-head);
  border: 1px solid var(--divider);
  border-radius: var(--r-ctl);
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-body);
  word-break: break-all;
}
.def__note {
  margin-top: 6px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
}
.def__foot {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 11px;
  color: var(--text-weak);
}
</style>
