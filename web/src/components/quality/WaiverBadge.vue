<script setup lang="ts">
import { computed } from 'vue'
import { formatDate } from '@/utils/format'

/**
 * 规则级豁免徽标（DESIGN §6.5）。
 *
 * 45° 条纹底是“这是一个例外”的唯一视觉语言：绿色 PASS / 红色 FAIL 都不允许出现在豁免上，
 * 因为豁免不代表规则通过，只代表“被人工接受的风险”。
 */
const props = withDefaults(
  defineProps<{
    /** ACTIVE / PENDING / APPROVED / EXPIRED / REVOKED / REJECTED */
    status?: string | null
    expiryAt?: string | null
    /** 服务端算好的剩余天数优先；缺省时按 expiryAt 展示，不参与任何判定 */
    daysLeft?: number | null
    compact?: boolean
  }>(),
  { status: 'ACTIVE', expiryAt: null, daysLeft: null, compact: false },
)

const normalized = computed(() => String(props.status ?? '').toUpperCase())

const remaining = computed<number | null>(() => {
  if (props.daysLeft !== null && props.daysLeft !== undefined) return props.daysLeft
  if (!props.expiryAt) return null
  const target = new Date(props.expiryAt).getTime()
  if (Number.isNaN(target)) return null
  return Math.ceil((target - Date.now()) / 86_400_000)
})

const tone = computed<'active' | 'soon' | 'dead' | 'void'>(() => {
  if (normalized.value === 'REVOKED' || normalized.value === 'REJECTED') return 'void'
  if (normalized.value === 'EXPIRED') return 'dead'
  if (remaining.value !== null && remaining.value < 0) return 'dead'
  if (remaining.value !== null && remaining.value <= 2) return 'soon'
  return 'active'
})

const text = computed(() => {
  switch (tone.value) {
    case 'void':
      return normalized.value === 'REJECTED' ? '豁免已驳回' : '豁免已撤销'
    case 'dead':
      return '豁免已过期'
    default:
      return remaining.value === null ? '豁免 · 无期限' : `豁免 · 剩 ${remaining.value} 天`
  }
})

const title = computed(() => {
  const parts = ['规则级豁免：表示该规则被人工接受，而非通过']
  if (props.expiryAt) parts.push(`到期 ${formatDate(props.expiryAt)}`)
  return parts.join('；')
})
</script>

<template>
  <span class="wb" :class="[`wb--${tone}`, { 'wb--compact': compact }]" :title="title">
    <span class="wb__text">{{ text }}</span>
  </span>
</template>

<style scoped>
.wb {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border: 1px solid #e9c989;
  border-radius: var(--r-ctl);
  background-color: var(--waiver-stripe, #ffefc9);
  background-image: repeating-linear-gradient(
    45deg,
    rgba(199, 119, 0, 0.12) 0,
    rgba(199, 119, 0, 0.12) 4px,
    transparent 4px,
    transparent 8px
  );
  color: var(--warning-ink);
  font-size: 11px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.wb--compact {
  height: 18px;
  padding: 0 6px;
  font-size: 10px;
}

.wb--soon {
  color: var(--danger-ink);
  border-color: #f0c9c9;
}

.wb--dead,
.wb--void {
  border-color: var(--border);
  background-color: var(--color-info-bg);
  background-image: none;
  color: var(--text-muted-aa);
}

.wb--dead {
  color: var(--danger-ink);
}
</style>
