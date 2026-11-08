<script setup lang="ts">
import { computed } from 'vue'

/**
 * 错误态（DESIGN §1.4）：区域内联卡片，不整页跳转、不弹 toast。
 * 结构 = 图标 + 人话 + 错误码（可复制给运维）+ 重试。
 */
const props = withDefaults(
  defineProps<{
    title?: string
    message?: string
    code?: string
    status?: number
    retryable?: boolean
    compact?: boolean
  }>(),
  { title: '', message: '', code: '', status: 0, retryable: true, compact: false },
)

defineEmits<{ (event: 'retry'): void }>()

const heading = computed(() => props.title || (props.status >= 500 ? '服务暂时不可用' : '加载失败'))
const hint = computed(() => {
  if (props.message) return props.message
  if (props.status === 0) return '无法连接服务，请确认网关已启动后重试'
  return '请稍后重试；若持续失败，请把错误码提供给管理员'
})
</script>

<template>
  <div class="err" :class="{ 'err--compact': props.compact }" role="alert">
    <div class="err__icon">
      <svg viewBox="0 0 20 20" aria-hidden="true">
        <circle cx="10" cy="10" r="7.4" fill="none" stroke="currentColor" stroke-width="1.4" />
        <path d="M10 5.8v5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        <circle cx="10" cy="13.6" r="0.9" fill="currentColor" />
      </svg>
    </div>
    <div class="err__body">
      <p class="err__title">{{ heading }}</p>
      <p class="err__msg">{{ hint }}</p>
      <p v-if="code || status" class="err__code">
        错误码：<span class="t-code">{{ code || `HTTP_${status}` }}</span>
      </p>
    </div>
    <div class="err__actions">
      <el-button v-if="props.retryable" @click="$emit('retry')">重试</el-button>
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.err {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-left: 2px solid var(--color-danger);
  border-radius: var(--r-card);
}
.err--compact {
  padding: 12px;
}
.err__icon {
  flex: none;
  color: var(--color-danger);
}
.err__icon svg {
  width: 20px;
  height: 20px;
}
.err__body {
  flex: 1;
  min-width: 0;
}
.err__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}
.err__msg {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.6;
}
.err__code {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-weak);
}
.err__actions {
  flex: none;
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
