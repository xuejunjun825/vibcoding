<script setup lang="ts">
/**
 * 结论条（DESIGN §1 / §6·Q13）：高 56，左 3px 语义竖条 + 浅语义底。
 *
 * 三条约束来自设计稿：
 * - 红色只用于 FAIL / 不可逆阻断；黄色只用于“需要人判断”；绿色只用于服务端判定通过；
 * - 主结论 20/600，副说明 12/400 单行省略（title 上挂完整文本，不换行撑高）；
 * - 右侧 slot 只放只读 meta 或次要动作，不放主按钮（主按钮在页面头部）。
 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    title: string
    hint?: string | null
    tone?: 'success' | 'warning' | 'danger' | 'neutral'
    /** 语义前缀，如“未通过”或“预览” */
    kicker?: string | null
    dense?: boolean
  }>(),
  { tone: 'neutral', hint: '', kicker: null, dense: false },
)

const toneClass = computed(() => `conclusion--${props.tone}`)
</script>

<template>
  <div
    class="conclusion"
    :class="[toneClass, { 'conclusion--dense': dense, 'conclusion--none': !title }]"
    role="status"
    aria-live="polite"
  >
    <div class="conclusion__main">
      <span v-if="kicker" class="conclusion__kicker">{{ kicker }}</span>
      <span class="conclusion__title">{{ title }}</span>
      <span v-if="hint" class="conclusion__hint" :title="hint">{{ hint }}</span>
    </div>
    <div class="conclusion__side">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.conclusion {
  display: flex;
  align-items: center;
  gap: 16px;
  min-height: var(--h-conclusion, 56px);
  padding: 8px 16px 8px 13px;
  border-left: 3px solid var(--border-strong);
  border-radius: 0 var(--r-ctl) var(--r-ctl) 0;
  background: var(--color-info-bg);
}
.conclusion--dense {
  min-height: 44px;
  padding-top: 6px;
  padding-bottom: 6px;
}
.conclusion--success {
  border-left-color: var(--color-success);
  background: var(--color-success-bg);
}
.conclusion--warning {
  border-left-color: var(--color-warning);
  background: var(--color-warning-bg);
}
.conclusion--danger {
  border-left-color: var(--color-danger);
  background: var(--color-danger-bg);
}
.conclusion--neutral {
  border-left-color: var(--text-weak);
  background: var(--color-info-bg);
}
.conclusion__main {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
  flex: 1;
}
.conclusion__kicker {
  flex: none;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-muted-aa);
}
.conclusion__title {
  flex: none;
  max-width: 55%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-title);
  line-height: 24px;
}
.conclusion--dense .conclusion__title {
  font-size: 16px;
  line-height: 20px;
}
.conclusion__hint {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-body);
}
.conclusion__side {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  color: var(--text-body);
  font-size: 12px;
}
</style>
