<script setup lang="ts">
import { computed } from 'vue'
import { useDictStore, type StatusKind } from '@/stores/dict'

/**
 * 状态 pill（DESIGN §7.3）：高 20、padding 0 8、12/500、6px 方角浅底 + 同色文字。
 * 枚举未知时不猜：中文原样显示，英文去下划线 —— 界面永不出现裸枚举码。
 */
const props = withDefaults(
  defineProps<{
    kind?: StatusKind
    value?: string | null
    /** 覆盖文案（如“已提交 · 冻结”） */
    label?: string
    size?: 'sm' | 'md'
  }>(),
  { kind: 'requirement', value: '', size: 'md' },
)

const dict = useDictStore()

const meta = computed(() => dict.meta(props.kind, props.value))
const text = computed(() => props.label ?? meta.value.label)
const isNotRun = computed(
  () => props.kind === 'execution' && String(props.value ?? '').toUpperCase() === 'NOT_RUN',
)
</script>

<template>
  <span
    class="pill"
    :class="[`pill--${meta.tone}`, { 'pill--dashed': isNotRun, 'pill--sm': size === 'sm' }]"
    :title="props.label ? dict.label(kind, value) : undefined"
  >
    {{ text }}
  </span>
</template>

<style scoped>
.pill {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: var(--r-ctl);
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}
.pill--sm {
  height: 18px;
  padding: 0 6px;
  font-size: 11px;
}
.pill--dashed {
  border-style: dashed;
  border-color: #c9cdd4;
  background: transparent;
  color: var(--text-muted-aa);
}
.pill--primary {
  background: var(--color-primary-light);
  color: var(--color-primary);
}
.pill--success {
  background: var(--color-success-bg);
  color: var(--success-ink);
}
.pill--warning {
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.pill--danger {
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}
.pill--info {
  background: var(--color-info-bg);
  color: var(--info-ink);
}
.pill--neutral {
  background: var(--color-info-bg);
  color: var(--text-muted-aa);
}
</style>
