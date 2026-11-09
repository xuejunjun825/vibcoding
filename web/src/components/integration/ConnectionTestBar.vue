<script setup lang="ts">
import { computed } from 'vue'
import type { IntegrationTestResult } from '@/api/integration'
import { formatShortTime } from '@/utils/format'

/**
 * 连通性测试条（DESIGN I1 / §6.3）。
 *
 * 三条约束来自 AC-1.4：
 * - 探测**不写库、不改配置**：结果只是一条内联提示，条上没有"应用"类动作；
 * - 单次尝试、**最长 6s**：超时由调用方的 `Promise.race` 兜住，这里只渲染 `CLIENT_TIMEOUT`；
 * - 结果就地出现在凭据行下方，**不弹 toast**：toast 会遮住刚改的字段，也不利于对照错误码。
 */
const props = withDefaults(
  defineProps<{
    result: IntegrationTestResult | null
    testing?: boolean
    /** 客户端超时上限，仅用于文案 */
    timeoutMs?: number
  }>(),
  { testing: false, timeoutMs: 6000 },
)

const emit = defineEmits<{ (event: 'retry'): void }>()

const tone = computed<'success' | 'warning' | 'danger' | 'info'>(() => {
  if (props.testing) return 'info'
  const result = props.result
  if (!result) return 'info'
  if (result.ok) return 'success'
  const code = String(result.code ?? '').toUpperCase()
  // 超时/网络属于"没结论"，黄；鉴权与 4xx 属于"配置错"，红
  return code === 'TIMEOUT' || code === 'CLIENT_TIMEOUT' || code === 'NETWORK_ERROR' ? 'warning' : 'danger'
})

const head = computed(() => {
  if (props.testing) return '正在探测'
  const result = props.result
  if (!result) return ''
  if (result.ok) return '连通'
  if (tone.value === 'warning') return '未在时限内响应'
  return '连接失败'
})

const detail = computed(() => {
  if (props.testing) return `单次请求，最多 ${Math.round(props.timeoutMs / 1000)}s；期间可继续编辑其他字段`
  const result = props.result
  if (!result) return ''
  if (result.ok) {
    const latency = result.latencyMs != null ? `${result.latencyMs}ms` : '—'
    const at = result.checkedAt ? ` · ${formatShortTime(result.checkedAt)}` : ''
    return `${latency}${at} · 仅探测，未改动已保存配置`
  }
  return result.message || '请检查 base_url、凭据与网络白名单'
})

const codeText = computed(() => {
  const result = props.result
  if (!result || result.ok) return ''
  return result.code || 'TEST_FAILED'
})
</script>

<template>
  <div
    v-if="testing || result"
    class="tbar"
    :class="`tbar--${tone}`"
    role="status"
    aria-live="polite"
  >
    <span class="tbar__icon" aria-hidden="true">
      <span v-if="testing" class="tbar__spin" />
      <svg v-else-if="tone === 'success'" viewBox="0 0 12 12">
        <path d="m2.4 6.2 2.3 2.3 4.9-4.9" fill="none" stroke="currentColor" stroke-width="1.4" />
      </svg>
      <svg v-else viewBox="0 0 12 12">
        <path d="M3.4 3.4 8.6 8.6M8.6 3.4 3.4 8.6" fill="none" stroke="currentColor" stroke-width="1.4" />
      </svg>
    </span>
    <div class="tbar__body">
      <p class="tbar__head">
        {{ head }}
        <span v-if="codeText" class="tbar__code t-code">{{ codeText }}</span>
      </p>
      <p class="tbar__detail">{{ detail }}</p>
    </div>
    <el-button v-if="!testing && result" link size="small" @click="emit('retry')">重试</el-button>
  </div>
</template>

<style scoped>
.tbar {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-height: 40px;
  padding: 6px 10px;
  border-left: 2px solid var(--border-strong);
  border-radius: var(--r-ctl);
  background: var(--bg-page);
}

.tbar--success {
  border-left-color: var(--color-success);
  background: var(--color-success-bg);
}

.tbar--warning {
  border-left-color: var(--color-warning);
  background: var(--color-warning-bg);
}

.tbar--danger {
  border-left-color: var(--color-danger);
  background: var(--color-danger-bg);
}

.tbar__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 18px;
  flex: none;
}

.tbar__icon svg {
  width: 12px;
  height: 12px;
}

.tbar--success .tbar__icon {
  color: var(--success-ink);
}

.tbar--warning .tbar__icon {
  color: var(--warning-ink);
}

.tbar--danger .tbar__icon {
  color: var(--danger-ink);
}

.tbar__spin {
  width: 10px;
  height: 10px;
  border: 1.4px solid var(--border-strong);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: tbar-spin 0.8s linear infinite;
}

@keyframes tbar-spin {
  to {
    transform: rotate(360deg);
  }
}

.tbar__body {
  min-width: 0;
  flex: 1;
}

.tbar__head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.5;
}

.tbar--success .tbar__head {
  color: var(--success-ink);
}

.tbar--warning .tbar__head {
  color: var(--warning-ink);
}

.tbar--danger .tbar__head {
  color: var(--danger-ink);
}

.tbar__code {
  color: var(--text-muted-aa);
  font-size: 11px;
  font-weight: 400;
}

.tbar__detail {
  margin: 1px 0 0;
  color: var(--text-body);
  font-size: 11px;
  line-height: 1.5;
  word-break: break-word;
}

@media (prefers-reduced-motion: reduce) {
  .tbar__spin {
    animation: none;
  }
}
</style>
