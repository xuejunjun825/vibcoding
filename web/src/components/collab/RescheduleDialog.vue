<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

/**
 * 越父窗口二次确认（DESIGN §1.4 第 4 步 / §1.6 几何 640）。
 *
 * 只做三件事：把父窗口与超出天数摊开、显示一次性确认码的倒计时、把确认结果交回页面。
 * 码是服务端签发的（GanttConfirmTokens），本组件不生成、不复用、关闭即视为作废 ——
 * 过期后只能「重新获取」，避免拿旧码重试把审计写乱。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    rowTitle: string
    planStart: string
    planEnd: string
    parentWindow?: { start?: string | null; end?: string | null }
    violatingDays: number
    expiresAt?: string
    submitting?: boolean
  }>(),
  { parentWindow: () => ({ start: null, end: null }), expiresAt: '', submitting: false },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirm'): void
  (e: 'refresh-token'): void
}>()

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null

function stop(): void {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
}

watch(
  () => props.modelValue,
  (open) => {
    stop()
    if (!open) return
    now.value = Date.now()
    timer = setInterval(() => {
      now.value = Date.now()
    }, 1000)
  },
)

onBeforeUnmount(stop)

const remainingMs = computed(() => {
  if (!props.expiresAt) return null
  const end = Date.parse(props.expiresAt)
  if (Number.isNaN(end)) return null
  return Math.max(0, end - now.value)
})

const expired = computed(() => remainingMs.value !== null && remainingMs.value <= 0)

const countdown = computed(() => {
  if (remainingMs.value === null) return '5 分钟内有效'
  if (remainingMs.value <= 0) return '已过期'
  const total = Math.floor(remainingMs.value / 1000)
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
})

const parentText = computed(() => {
  const start = props.parentWindow?.start ?? '—'
  const end = props.parentWindow?.end ?? '—'
  return `${start} → ${end}`
})

function close(): void {
  emit('update:modelValue', false)
}

function confirm(): void {
  if (expired.value || props.submitting) return
  emit('confirm')
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    width="640px"
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    align-center
    class="resched-dialog"
    title="改期超出父条目窗口"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="resched">
      <p class="resched__lead">
        <strong>{{ rowTitle }}</strong>
        的新日期超出了父条目的计划窗口。确认后仍会保存，但会在父条目上留下一条「越窗」提示。
      </p>

      <dl class="resched__facts">
        <div class="resched__fact">
          <dt>新日期</dt>
          <dd class="resched__mono">{{ planStart }} → {{ planEnd }}</dd>
        </div>
        <div class="resched__fact">
          <dt>父窗口</dt>
          <dd class="resched__mono">{{ parentText }}</dd>
        </div>
        <div class="resched__fact">
          <dt>超出</dt>
          <dd class="resched__warn">{{ violatingDays }} 天</dd>
        </div>
      </dl>

      <ul class="resched__notes">
        <li>仅本条不顺延：子条目保持各自日期，不会被一起拖动。</li>
        <li>确认会写入审计（GANTT_RESCHEDULE），并保留父条目的越窗告警。</li>
        <li>确认码一次性使用，关闭本弹窗即作废。</li>
      </ul>

      <div class="resched__token" :class="{ 'resched__token--expired': expired }">
        <span class="resched__token-label">一次性确认码有效</span>
        <span class="resched__count">{{ countdown }}</span>
        <button v-if="expired" type="button" class="resched__refresh" @click="emit('refresh-token')">
          重新获取确认码
        </button>
      </div>
    </div>

    <template #footer>
      <button type="button" class="resched__btn" :disabled="submitting" @click="close">取消</button>
      <button
        type="button"
        class="resched__btn resched__btn--primary"
        :disabled="expired || submitting"
        @click="confirm"
      >
        {{ submitting ? '保存中…' : '确认改期' }}
      </button>
    </template>
  </el-dialog>
</template>

<style scoped>
.resched {
  display: flex;
  flex-direction: column;
  gap: 16px;
  color: var(--text-body);
  font-size: 13px;
}
.resched__lead {
  margin: 0;
  line-height: 22px;
  color: var(--text-body);
}
.resched__lead strong {
  color: var(--text-title);
  font-weight: 500;
}
.resched__facts {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  background: var(--bg-hover);
  border-radius: var(--r-ctl);
}
.resched__fact {
  display: flex;
  align-items: baseline;
  gap: 12px;
  height: 20px;
}
.resched__fact dt {
  width: 56px;
  color: var(--text-muted-aa);
  font-size: 12px;
}
.resched__fact dd {
  margin: 0;
}
.resched__mono {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--text-title);
}
.resched__warn {
  color: var(--warning-ink);
  font-weight: 600;
}
.resched__notes {
  margin: 0;
  padding-left: 18px;
  color: var(--text-muted-aa);
  font-size: 12px;
  line-height: 20px;
}
.resched__token {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 40px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
}
.resched__token--expired {
  border-color: #e9c989;
  background: var(--color-warning-bg);
}
.resched__token-label {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.resched__count {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.resched__token--expired .resched__count {
  color: var(--warning-ink);
}
.resched__refresh {
  margin-left: auto;
  border: 0;
  background: none;
  padding: 0;
  color: var(--color-primary);
  font-size: 12px;
  cursor: pointer;
}
.resched__refresh:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}
.resched__btn {
  height: 32px;
  padding: 0 16px;
  border-radius: var(--r-ctl);
  border: 1px solid var(--border);
  background: var(--bg-card);
  color: var(--text-body);
  font-size: 13px;
  cursor: pointer;
  transition: border-color var(--t-color) var(--ease), color var(--t-color) var(--ease);
}
.resched__btn:hover:not(:disabled) {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.resched__btn:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}
.resched__btn:disabled {
  color: var(--text-weak);
  cursor: not-allowed;
  background: var(--bg-hover);
}
.resched__btn--primary {
  margin-left: 8px;
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
}
.resched__btn--primary:hover:not(:disabled) {
  background: var(--color-primary-hover);
  border-color: var(--color-primary-hover);
  color: #fff;
}
.resched__btn--primary:disabled {
  background: var(--border-strong);
  border-color: var(--border-strong);
  color: #fff;
}
</style>
