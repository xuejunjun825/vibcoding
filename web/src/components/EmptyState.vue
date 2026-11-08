<script setup lang="ts">
/**
 * 空态（DESIGN §1.4）：
 * 必须区分「还没有数据」与「当前筛选无结果」——两者的主操作完全不同。
 */
const props = withDefaults(
  defineProps<{
    variant?: 'empty' | 'filtered' | 'forbidden' | 'error'
    title: string
    description?: string
    actionText?: string
    icon?: 'inbox' | 'filter' | 'search' | 'chart' | 'file' | 'lock'
    compact?: boolean
  }>(),
  { variant: 'empty', description: '', actionText: '', icon: 'inbox', compact: false },
)

defineEmits<{ (event: 'action'): void }>()

const ICONS: Record<string, string> = {
  inbox: 'M2.5 9.5 5 3.5h6l2.5 6M2.5 9.5v3h11v-3M6 9.5h4',
  filter: 'M2.5 4h11L9.2 8.6v3.6l-2.4 1.2V8.6L2.5 4Z',
  search: 'M7.2 12.4a5.2 5.2 0 1 0 0-10.4 5.2 5.2 0 0 0 0 10.4ZM13.5 13.5l-2.9-2.9',
  chart: 'M3 13V7M7 13V4M11 13V9M2 13.4h12',
  file: 'M4 2.5h5l3 3v8H4v-11ZM9 2.5v3h3',
  lock: 'M5 7V5a3 3 0 0 1 6 0v2M3.5 7h9v6.5h-9V7Z',
}
</script>

<template>
  <div class="empty" :class="{ 'empty--compact': props.compact }">
    <div class="empty__icon" :class="`empty__icon--${props.variant}`">
      <svg viewBox="0 0 16 16" aria-hidden="true">
        <path
          :d="ICONS[props.icon] ?? ICONS.inbox"
          fill="none"
          stroke="currentColor"
          stroke-width="1.2"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </div>
    <p class="empty__title">{{ props.title }}</p>
    <p v-if="props.description" class="empty__desc">{{ props.description }}</p>
    <div v-if="props.actionText || $slots.action" class="empty__actions">
      <slot name="action">
        <el-button type="primary" @click="$emit('action')">{{ props.actionText }}</el-button>
      </slot>
    </div>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 56px 24px;
  text-align: center;
}
.empty--compact {
  padding: 28px 16px;
}
.empty__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  margin-bottom: 4px;
  border-radius: var(--r-card);
  background: var(--color-info-bg);
  color: var(--text-weak);
}
.empty__icon svg {
  width: 22px;
  height: 22px;
}
.empty__icon--filtered {
  background: var(--color-warning-bg);
  color: #c9a06a;
}
.empty__icon--forbidden {
  background: var(--color-danger-bg);
  color: #d99a9e;
}
.empty__icon--error {
  background: var(--color-danger-bg);
  color: var(--color-danger);
}
.empty__title {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-title);
}
.empty__desc {
  font-size: 12px;
  color: var(--text-muted-aa);
  max-width: 380px;
  line-height: 1.7;
}
.empty__actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}
</style>
