<script setup lang="ts">
/**
 * 分支策略三态 pill：固定 88×22，位置不随文案跳动。
 * 语义纪律：绿只给服务端返回的 MATCH；没有核验记录 / 不可达一律黄「未核验」。
 */
import { computed } from 'vue'
import { POLICY_REASON_LABEL, POLICY_RESULT_LABEL } from '@/api/code'

const props = withDefaults(
  defineProps<{
    result?: string | null
    reason?: string | null
    checkedAt?: string | null
    loading?: boolean
  }>(),
  { result: 'UNVERIFIED', reason: null, checkedAt: null, loading: false },
)

const tone = computed(() => {
  if (props.loading) return 'checking'
  if (props.result === 'MATCH') return 'match'
  if (props.result === 'DRIFT') return 'drift'
  return 'unverified'
})

const text = computed(() => {
  if (props.loading) return '核验中'
  if (props.result && POLICY_RESULT_LABEL[props.result]) return POLICY_RESULT_LABEL[props.result]
  // 未知/缺失的 result 一律按未核验渲染，绝不因为字符串不等而落到绿色
  return '未核验'
})

const tooltip = computed(() => {
  if (props.loading) return '正在读取 GitLab 配置并核验…'
  const reasonText = props.reason ? POLICY_REASON_LABEL[props.reason] ?? props.reason : ''
  if (props.result === 'MATCH') {
    return [reasonText, props.checkedAt ? `核验于 ${props.checkedAt}` : ''].filter(Boolean).join(' · ') || '实际配置与期望一致'
  }
  if (props.result === 'DRIFT') {
    return [reasonText || '实际配置与期望不一致，展开查看差异', props.checkedAt ? `核验于 ${props.checkedAt}` : '']
      .filter(Boolean)
      .join(' · ')
  }
  return reasonText
    ? `未核验：${reasonText}`
    : '未核验：还没有可用的核验记录（不计入一致率）'
})
</script>

<template>
  <span class="policy-pill" :class="`policy-pill--${tone}`" role="status" :title="tooltip" :aria-label="tooltip">
    <span class="policy-pill__dot" aria-hidden="true" />
    <span class="policy-pill__text">{{ text }}</span>
  </span>
</template>

<style scoped>
.policy-pill {
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
  transition: background-color var(--t-color, 120ms) var(--ease, ease);
}

.policy-pill__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}

.policy-pill__text {
  font-weight: 500;
  letter-spacing: 0.2px;
}

.policy-pill--match {
  color: var(--success-ink, #157a38);
  background: var(--color-success-bg, #e8f7ec);
  border-color: color-mix(in srgb, var(--color-success, #1f9d45) 22%, transparent);
}

.policy-pill--drift {
  color: var(--danger-ink, #b3262e);
  background: var(--color-danger-bg, #fdecec);
  border-color: color-mix(in srgb, var(--color-danger, #d9363e) 22%, transparent);
}

.policy-pill--unverified {
  color: var(--warning-ink, #9a5b00);
  background: var(--color-warning-bg, #fff7e6);
  border-color: color-mix(in srgb, var(--color-warning, #c77700) 24%, transparent);
}

.policy-pill--checking {
  color: var(--text-muted-aa, #6b7280);
  background: var(--bg-hover, #f7f9fc);
  border-color: var(--border, #e5e6eb);
}

.policy-pill--checking .policy-pill__dot {
  animation: policy-pill-pulse 900ms ease-in-out infinite;
}

@keyframes policy-pill-pulse {
  0%,
  100% {
    opacity: 0.35;
  }
  50% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .policy-pill--checking .policy-pill__dot {
    animation: none;
  }
}
</style>
