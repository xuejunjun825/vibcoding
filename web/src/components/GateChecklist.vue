<script setup lang="ts">
import { computed } from 'vue'
import type { GateItem } from '@/api/types'

/**
 * 门禁 / 准入共用常驻清单（DESIGN §6.1）。
 * 语义相同、措辞不同，所以合成一个组件：
 * - mode=gate      需求流转前的用例门禁
 * - mode=admission 发布单的 5 项准入
 * 409 不是 toast，而是清单顶部的常驻条 + 逐条定位。
 */
const props = withDefaults(
  defineProps<{
    items: GateItem[]
    mode?: 'admission' | 'gate'
    readonly?: boolean
    title?: string
    /** 最近一次提交被服务端拒绝（409 GATE_BLOCKED / ADMISSION_FAILED） */
    rejected?: boolean
    rejectedText?: string
    checkedAt?: string | null
    /** 快照已过期（发布单需求变更后） */
    stale?: boolean
    /** 显示「强制放行」入口（仅 admission 且有权时） */
    allowForce?: boolean
    forceDisabledReason?: string
  }>(),
  {
    mode: 'gate',
    readonly: false,
    title: '',
    rejected: false,
    rejectedText: '',
    checkedAt: null,
    stale: false,
    allowForce: false,
    forceDisabledReason: '',
  },
)

const emit = defineEmits<{ (event: 'force'): void }>()

const passedCount = computed(() => props.items.filter((item) => item.passed).length)
const failedCount = computed(() => props.items.length - passedCount.value)
const allPassed = computed(() => props.items.length > 0 && failedCount.value === 0)
const heading = computed(
  () => props.title || (props.mode === 'admission' ? '发布准入检查' : '测试门禁'),
)

const rejectMessage = computed(() => {
  if (props.rejectedText) return props.rejectedText
  return `提交被拒绝：${failedCount.value} 项未通过`
})

const checkedLabel = computed(() => {
  if (!props.checkedAt) return ''
  const date = new Date(props.checkedAt)
  if (Number.isNaN(date.getTime())) return props.checkedAt
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getMonth() + 1}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
})
</script>

<template>
  <section class="gate" :class="[`gate--${props.mode}`]">
    <header class="gate__head">
      <div class="gate__title-wrap">
        <span class="gate__title">{{ heading }}</span>
        <span class="gate__count" :class="{ 'gate__count--fail': failedCount > 0 }">
          {{ passedCount }}/{{ props.items.length }} 通过
        </span>
        <span v-if="props.stale" class="gate__stale">快照已过期，请重新检查</span>
      </div>
      <div class="gate__meta">
        <span v-if="checkedLabel" class="gate__time">检查于 {{ checkedLabel }}</span>
        <slot name="head-extra" />
      </div>
    </header>

    <!-- 409 常驻条：留在页面上，不消失 -->
    <div v-if="props.rejected" class="gate__rejected" role="alert">
      <svg viewBox="0 0 16 16" aria-hidden="true">
        <circle cx="8" cy="8" r="6.4" fill="none" stroke="currentColor" stroke-width="1.3" />
        <path d="M8 4.6v4.2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        <circle cx="8" cy="10.9" r="0.8" fill="currentColor" />
      </svg>
      <span class="gate__rejected-text">{{ rejectMessage }}</span>
      <span class="gate__rejected-hint">逐项处理后重新提交</span>
    </div>

    <ul v-if="props.items.length" class="gate__list">
      <li
        v-for="item in props.items"
        :key="item.code"
        class="gate__row"
        :class="{ 'gate__row--fail': !item.passed, 'gate__row--forced': item.forced }"
      >
        <span class="gate__icon" :class="item.passed ? 'gate__icon--pass' : 'gate__icon--fail'">
          <svg v-if="item.passed" viewBox="0 0 16 16" aria-hidden="true">
            <circle cx="8" cy="8" r="7" fill="currentColor" />
            <path d="m4.9 8.2 2.1 2.1 4-4.3" fill="none" stroke="#fff" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <svg v-else viewBox="0 0 16 16" aria-hidden="true">
            <circle cx="8" cy="8" r="7" fill="currentColor" />
            <path d="M8 4.4v4.4" stroke="#fff" stroke-width="1.6" stroke-linecap="round" />
            <circle cx="8" cy="11.4" r="0.95" fill="#fff" />
          </svg>
        </span>

        <div class="gate__body">
          <div class="gate__name-line">
            <span class="gate__name">{{ item.name }}</span>
            <span v-if="item.code" class="gate__code">{{ item.code }}</span>
            <span v-if="item.forced" class="gate__forced" :title="item.detail || '已被强制放行'">
              强制放行
            </span>
          </div>
          <p v-if="item.detail" class="gate__detail">{{ item.detail }}</p>
          <p v-if="!item.passed && item.fixHint" class="gate__fix">
            <span class="gate__fix-label">修复</span>{{ item.fixHint }}
          </p>
        </div>

        <span class="gate__pill" :class="item.passed ? 'gate__pill--pass' : 'gate__pill--fail'">
          {{ item.passed ? 'PASS' : 'FAIL' }}
        </span>
      </li>
    </ul>

    <div v-else class="gate__empty">尚未生成检查项，点击「重新检查」后再提交</div>

    <footer v-if="!props.readonly" class="gate__foot">
      <span class="gate__foot-hint">
        <template v-if="allPassed">全部通过，可提交</template>
        <template v-else>未通过项已定位在上方，处理后再提交</template>
      </span>
      <div class="gate__foot-actions">
        <slot name="actions" />
        <el-tooltip
          v-if="props.allowForce"
          :content="props.forceDisabledReason || '强制放行需要填写原因，并通知域负责人'"
          placement="top"
        >
          <span>
            <el-button
              text
              type="warning"
              :disabled="Boolean(props.forceDisabledReason)"
              @click="emit('force')"
            >
              强制放行
            </el-button>
          </span>
        </el-tooltip>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.gate {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
}
.gate__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 44px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--divider);
}
.gate__title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.gate__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.gate__count {
  font-size: 12px;
  color: var(--text-muted-aa);
  background: var(--color-info-bg);
  border-radius: var(--r-pill);
  padding: 1px 8px;
  font-variant-numeric: tabular-nums;
}
.gate__count--fail {
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}
.gate__stale {
  font-size: 12px;
  color: var(--warning-ink);
}
.gate__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.gate__time {
  font-size: 12px;
  color: var(--text-weak);
  font-variant-numeric: tabular-nums;
}
.gate__rejected {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: var(--color-danger-bg);
  border-bottom: 1px solid #f7d4d5;
  color: var(--danger-ink);
  font-size: 12px;
}
.gate__rejected svg {
  width: 14px;
  height: 14px;
  flex: none;
}
.gate__rejected-text {
  font-weight: 600;
}
.gate__rejected-hint {
  color: #a35a5e;
}
.gate__list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.gate__row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-height: 40px;
  padding: 10px 16px 10px 14px;
  border-left: 2px solid transparent;
  border-bottom: 1px solid var(--divider);
  transition: background var(--t-color) var(--ease);
}
.gate__row:last-child {
  border-bottom: none;
}
.gate__row:hover {
  background: var(--bg-hover);
}
.gate__row--fail {
  border-left-color: var(--color-danger);
  background: #fffbfb;
}
.gate__row--fail:hover {
  background: #fff5f5;
}
.gate__icon {
  flex: none;
  width: 16px;
  height: 16px;
  margin-top: 2px;
}
.gate__icon svg {
  width: 16px;
  height: 16px;
  display: block;
}
.gate__icon--pass {
  color: var(--color-success);
}
.gate__icon--fail {
  color: var(--color-danger);
}
.gate__body {
  flex: 1;
  min-width: 0;
}
.gate__name-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.gate__name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-title);
  line-height: 18px;
}
.gate__code {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-weak);
  letter-spacing: 0.02em;
}
.gate__forced {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 7px;
  border: 1px solid #e9c989;
  border-radius: var(--r-ctl);
  background: repeating-linear-gradient(
    45deg,
    #fff7e6,
    #fff7e6 4px,
    #ffefc9 4px,
    #ffefc9 8px
  );
  color: var(--warning-ink);
  font-size: 11px;
  font-weight: 500;
}
.gate__detail {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 18px;
}
.gate__fix {
  margin-top: 4px;
  font-size: 12px;
  color: var(--danger-ink);
  line-height: 18px;
}
.gate__fix-label {
  display: inline-flex;
  align-items: center;
  height: 16px;
  margin-right: 6px;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--color-danger-bg);
  font-size: 11px;
  font-weight: 500;
}
.gate__pill {
  flex: none;
  width: 64px;
  height: 20px;
  margin-top: 1px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-ctl);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.gate__pill--pass {
  background: var(--color-success-bg);
  color: var(--success-ink);
}
.gate__pill--fail {
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}
.gate__empty {
  padding: 20px 16px;
  font-size: 12px;
  color: var(--text-weak);
}
.gate__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px;
  border-top: 1px solid var(--divider);
  background: var(--bg-table-head);
  flex-wrap: wrap;
}
.gate__foot-hint {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gate__foot-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
