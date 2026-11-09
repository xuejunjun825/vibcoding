<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import type { RoundView } from '@/api/types'
import { formatPercent } from '@/utils/format'

/**
 * 轮次切换条（DESIGN §6.6）。
 *
 * 切轮次只改 URL 的 `?round=`，不改变页面数据之外的东西——所以这个组件只 emit 选中的 roundId，
 * 由页面把 `router.replace({query})` 交给路由，刷新/分享都能回到同一轮。
 * 已关闭轮次带锁仍可查看（只读），键盘 ←→ Home/End 在轮次间移动。
 */
const props = withDefaults(
  defineProps<{ rounds: RoundView[]; modelValue: number | null; disabled?: boolean }>(),
  { disabled: false },
)

const emit = defineEmits<{ (event: 'update:modelValue', value: number): void }>()

const tabs = ref<HTMLButtonElement[]>([])
const overflow = computed(() => props.rounds.length > 8)

const selectedIndex = computed(() =>
  props.rounds.findIndex((round) => round.id === props.modelValue),
)

function label(round: RoundView): string {
  const type = round.roundType ? ` ${round.roundType}` : ''
  return `R${round.roundNo}${type} ${formatPercent(round.passRate)}`
}

function select(index: number): void {
  const round = props.rounds[index]
  if (!round) return
  if (round.id !== props.modelValue) emit('update:modelValue', round.id)
}

function onKeydown(event: KeyboardEvent): void {
  if (!props.rounds.length) return
  const current = selectedIndex.value < 0 ? 0 : selectedIndex.value
  let next = current
  if (event.key === 'ArrowRight') next = Math.min(current + 1, props.rounds.length - 1)
  else if (event.key === 'ArrowLeft') next = Math.max(current - 1, 0)
  else if (event.key === 'Home') next = 0
  else if (event.key === 'End') next = props.rounds.length - 1
  else return
  event.preventDefault()
  select(next)
  void nextTick(() => tabs.value[next]?.focus())
}

function setTabRef(el: Element | undefined, index: number): void {
  if (el instanceof HTMLButtonElement) tabs.value[index] = el
}
</script>

<template>
  <div class="rs" :class="{ 'rs--overflow': overflow }" role="tablist" aria-label="测试轮次" @keydown="onKeydown">
    <button
      v-for="(round, index) in rounds"
      :key="round.id"
      :ref="(el) => setTabRef(el as Element | undefined, index)"
      type="button"
      role="tab"
      class="rs__tab"
      :class="{ 'is-current': round.id === modelValue, 'is-closed': round.status === 'CLOSED' }"
      :aria-selected="round.id === modelValue"
      :aria-label="label(round)"
      :tabindex="round.id === modelValue ? 0 : -1"
      :disabled="disabled"
      :title="`R${round.roundNo} · ${round.status}${round.conclusion ? ` · 结论 ${round.conclusion}` : ''}`"
      @click="select(index)"
    >
      <span class="rs__no">R{{ round.roundNo }}</span>
      <span class="rs__rate">{{ formatPercent(round.passRate) }}</span>
      <svg v-if="round.status === 'CLOSED'" class="rs__lock" viewBox="0 0 12 12" aria-label="已关闭">
        <path
          d="M3.5 5V3.9a2.5 2.5 0 0 1 5 0V5M2.8 5h6.4v4.2H2.8z"
          fill="none"
          stroke="currentColor"
          stroke-width="1.1"
          stroke-linecap="round"
        />
      </svg>
      <span v-else-if="round.active" class="rs__live" aria-label="进行中" />
    </button>
  </div>
</template>

<style scoped>
.rs {
  display: flex;
  gap: 4px;
  min-width: 0;
  border-bottom: 1px solid var(--border);
}

.rs--overflow {
  overflow-x: auto;
  scrollbar-width: none;
  mask-image: linear-gradient(90deg, transparent 0, #000 16px, #000 calc(100% - 16px), transparent 100%);
}

.rs--overflow::-webkit-scrollbar {
  display: none;
}

.rs__tab {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  min-width: 104px;
  height: 32px;
  padding: 0 10px;
  border: 0;
  border-radius: var(--r-ctl) var(--r-ctl) 0 0;
  background: transparent;
  color: var(--text-body);
  font-family: inherit;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  transition: background-color var(--t-color) var(--ease);
}

.rs__tab:hover:not(:disabled) {
  background: var(--bg-hover);
}

.rs__tab:active:not(:disabled) {
  background: var(--bg-selected);
}

.rs__tab:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.rs__tab.is-current {
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-weight: 600;
}

/* 选中态 2px 下划线压在容器 1px 边框上 */
.rs__tab.is-current::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--color-primary);
}

.rs__tab:disabled {
  color: var(--text-weak);
  cursor: not-allowed;
}

.rs__no {
  font-variant-numeric: tabular-nums;
}

.rs__rate {
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
  font-weight: 400;
}

.rs__tab.is-current .rs__rate {
  color: var(--color-primary);
}

.rs__lock {
  width: 12px;
  height: 12px;
  color: var(--text-weak);
}

.rs__live {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-success);
}
</style>
