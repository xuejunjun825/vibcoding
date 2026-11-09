<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 生命周期步进条（DESIGN §6.1）。
 *
 * 只负责“画”：步骤的状态由页面按服务端 `allowedNext` 推导，步进器本身不提供跳转、不内置
 * 任何状态迁移表——服务端说能做哪个动作，页面上才有哪个按钮（无状态下拉）。
 * 宽度 < 1000 时切紧凑条（单行 `当前状态 · 3/7` + 下一步按钮），不折行、不横向滚动。
 */
type StepState = 'done' | 'current' | 'pending' | 'blocked'

interface StepperStep {
  key: string
  label: string
  state: StepState
}

const props = withDefaults(
  defineProps<{
    steps: StepperStep[]
    /** 紧凑态右侧的“下一步”文案；留空则不渲染按钮 */
    nextLabel?: string
    nextDisabled?: boolean
    /** 强制紧凑（抽屉等窄容器内使用） */
    compact?: boolean
  }>(),
  { nextLabel: '', nextDisabled: false, compact: false },
)

const emit = defineEmits<{ (event: 'next'): void }>()

const root = ref<HTMLElement | null>(null)
const tooNarrow = ref(false)
let observer: ResizeObserver | null = null

const compact = computed(() => props.compact || tooNarrow.value)

const currentStep = computed(() => props.steps.find((step) => step.state === 'current'))
const doneCount = computed(() => props.steps.filter((step) => step.state === 'done').length)
const progressLabel = computed(
  () => `${Math.min(doneCount.value + (currentStep.value ? 1 : 0), props.steps.length)}/${props.steps.length}`,
)
const currentLabel = computed(() => currentStep.value?.label ?? props.steps.at(-1)?.label ?? '—')

const STATE_TEXT: Record<StepState, string> = {
  done: '已完成',
  current: '当前状态',
  pending: '未开始',
  blocked: '受阻',
}

function stateText(state: StepState): string {
  return STATE_TEXT[state] ?? state
}

onMounted(() => {
  if (!root.value) return
  if (typeof ResizeObserver === 'undefined') {
    tooNarrow.value = window.innerWidth < 1000
    return
  }
  observer = new ResizeObserver((entries) => {
    const width = entries[0]?.contentRect.width ?? 0
    // 7 步 × 120 + 6 × 24 = 984，容器再窄就换紧凑条
    tooNarrow.value = width > 0 && width < 1000
  })
  observer.observe(root.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <div ref="root" class="lp">
    <div v-if="compact" class="lp__compact">
      <span class="lp__compact-text">
        当前状态 · <strong class="lp__compact-state">{{ currentLabel }}</strong>
        <span class="lp__compact-count">{{ progressLabel }}</span>
      </span>
      <el-button
        v-if="nextLabel"
        size="small"
        type="primary"
        :disabled="nextDisabled"
        @click="emit('next')"
      >
        {{ nextLabel }}
      </el-button>
    </div>

    <ol v-else class="lp__steps" role="list">
      <li
        v-for="(step, index) in steps"
        :key="step.key"
        class="lp__step"
        :class="`is-${step.state}`"
        :aria-current="step.state === 'current' ? 'step' : undefined"
        :aria-label="`${step.label}：${stateText(step.state)}`"
      >
        <span class="lp__mark" aria-hidden="true">
          <svg v-if="step.state === 'done'" viewBox="0 0 12 12" class="lp__check">
            <path d="M2.5 6.2 5 8.6l4.5-5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <span v-else-if="step.state === 'current'" class="lp__dot" />
          <span v-else-if="step.state === 'blocked'" class="lp__dot lp__dot--blocked" />
          <span v-else class="lp__ring" />
        </span>
        <span class="lp__label">{{ step.label }}</span>
        <span v-if="index < steps.length - 1" class="lp__link" aria-hidden="true" />
      </li>
    </ol>
  </div>
</template>

<style scoped>
.lp {
  width: 100%;
  min-width: 0;
}

/* ---------------- 紧凑条 ---------------- */

.lp__compact {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  height: 44px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
}

.lp__compact-text {
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  color: var(--text-body);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lp__compact-state {
  color: var(--text-title);
  font-weight: 600;
}

.lp__compact-count {
  margin-left: 8px;
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}

/* ---------------- 完整步进条 ---------------- */

.lp__steps {
  display: flex;
  margin: 0;
  padding: 0;
  list-style: none;
}

.lp__step {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 120px;
  width: 120px;
  height: 44px;
  padding: 0 8px;
  border-radius: var(--r-ctl);
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 16px;
}

.lp__step + .lp__step {
  margin-left: 24px;
}

/* 连接线：步宽 24 的间隙里画 24×2 的线 */
.lp__step:not(:last-child)::after {
  content: '';
  position: absolute;
  top: 21px;
  right: -24px;
  width: 24px;
  height: 2px;
  background: var(--border);
}

.lp__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 16px;
  width: 16px;
  height: 16px;
}

.lp__check {
  width: 14px;
  height: 14px;
  color: var(--color-success);
}

.lp__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-light);
}

.lp__dot--blocked {
  background: var(--color-danger);
  box-shadow: 0 0 0 3px var(--color-danger-bg);
}

.lp__ring {
  width: 8px;
  height: 8px;
  border: 1px solid var(--border-strong);
  border-radius: 50%;
  background: var(--bg-card);
}

.lp__label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* done：已完成，降权但仍可读 */
.is-done {
  color: var(--text-muted-aa);
}

.is-done::after {
  background: var(--color-success-bg) !important;
}

/* current：当前步骤是唯一强对比 */
.is-current {
  background: var(--color-primary-light);
  color: var(--primary-ink, var(--color-primary));
  font-weight: 600;
}

/* pending：未开始，弱化 */
.is-pending {
  color: var(--text-weak);
}

/* blocked：受阻，语义红但只用文字与圆点，不铺整条红底 */
.is-blocked {
  color: var(--danger-ink);
}

.is-blocked::after {
  background: var(--color-danger-bg) !important;
}

@media (prefers-reduced-motion: no-preference) {
  .lp__step {
    transition:
      background-color var(--t-color) var(--ease),
      color var(--t-color) var(--ease);
  }
}
</style>
