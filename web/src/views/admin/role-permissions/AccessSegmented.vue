<script setup lang="ts">
import { nextTick, ref } from 'vue'
import type { AccessLevel } from '@/api/types'

/**
 * 三档控件：允许 / 受限 / 拒绝（DESIGN §0.2 手感基线，164 × 30）。
 *
 * 三档在树、页头、动作行出现三次，抽成受控组件是为了让尺寸与 aria 语义只有一份：
 * 三处各写一遍 radio，焦点环与 52px 分项宽度会漂。语义上是 radiogroup，
 * 键盘 ←→（以及 ↑↓）在组内移动并**立即选中**（I5）；禁用态用于「锁定码」，
 * 按 DESIGN §0.2 **仍可聚焦**（opacity .55），这样键盘与读屏能读到为什么不能改。
 */
const props = defineProps<{
  modelValue: AccessLevel
  disabled?: boolean
  ariaLabel?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [AccessLevel] }>()

const OPTIONS: Array<{ value: AccessLevel; label: string }> = [
  { value: 'ALLOW', label: '允许' },
  { value: 'RESTRICTED', label: '受限' },
  { value: 'DENY', label: '拒绝' },
]

const group = ref<HTMLDivElement | null>(null)

function pick(value: AccessLevel): void {
  if (props.disabled || value === props.modelValue) return
  emit('update:modelValue', value)
}

function onKeydown(event: KeyboardEvent): void {
  const step =
    event.key === 'ArrowRight' || event.key === 'ArrowDown'
      ? 1
      : event.key === 'ArrowLeft' || event.key === 'ArrowUp'
        ? -1
        : 0
  if (step === 0) return
  event.preventDefault()
  const index = OPTIONS.findIndex((option) => option.value === props.modelValue)
  const next = OPTIONS[(index + step + OPTIONS.length) % OPTIONS.length]
  if (!props.disabled) pick(next.value)
  // 焦点跟着选中项走：禁用时仍可聚焦，读屏能读出被锁住的是哪一档
  void nextTick(() => {
    group.value?.querySelector<HTMLButtonElement>(`[data-value="${next.value}"]`)?.focus()
  })
}
</script>

<template>
  <div
    ref="group"
    class="seg"
    :class="`seg--${modelValue.toLowerCase()}`"
    role="radiogroup"
    aria-orientation="horizontal"
    :aria-label="ariaLabel || '访问档位'"
    :aria-disabled="disabled ? 'true' : 'false'"
    :data-disabled="disabled ? 'true' : undefined"
    @keydown="onKeydown"
  >
    <button
      v-for="option in OPTIONS"
      :key="option.value"
      type="button"
      class="seg__item"
      :class="{ 'is-on': modelValue === option.value }"
      role="radio"
      :data-value="option.value"
      :aria-checked="modelValue === option.value ? 'true' : 'false'"
      :aria-disabled="disabled ? 'true' : 'false'"
      :tabindex="modelValue === option.value ? 0 : -1"
      @click="pick(option.value)"
    >
      {{ option.label }}
    </button>
  </div>
</template>

<style scoped>
.seg {
  display: inline-flex;
  align-items: center;
  box-sizing: border-box;
  width: var(--w-three-state, 164px);
  height: var(--h-three-state, 30px);
  padding: 2px;
  gap: 2px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--color-info-bg);
}
.seg__item {
  flex: 1 1 0;
  height: 100%;
  min-width: 0;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: var(--text-muted-aa);
  font-size: 13px;
  font-weight: 500;
  line-height: 1;
  cursor: pointer;
  transition:
    background 120ms var(--ease),
    color 120ms var(--ease);
}
.seg__item:hover:not([aria-disabled='true']):not(.is-on) {
  background: var(--bg-hover);
  color: var(--text-body);
}
.seg__item:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
/* 选中档位按 DESIGN §0.2 的三档配色，三处控件共用这一份 */
.seg--allow .seg__item.is-on {
  background: #e8f7ec;
  color: #157a38;
  border-color: #cdebd7;
}
.seg--restricted .seg__item.is-on {
  background: #fff7e6;
  color: #9a5b00;
  border-color: #f0ddb8;
}
.seg--deny .seg__item.is-on {
  background: #fff;
  color: var(--text-body);
  border-color: var(--border-strong);
}
.seg[data-disabled='true'] {
  opacity: 0.55;
}
.seg[data-disabled='true'] .seg__item {
  cursor: not-allowed;
}
</style>
