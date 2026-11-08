<script setup lang="ts">
/**
 * 数据范围 chip（DESIGN §3.4 三态里的 ⚪）：
 * RESTRICTED 的功能入口保留，但必须把“范围”显式写在旁边，
 * 用户点进去之前就知道自己看到的是全量还是子集。
 */
withDefaults(
  defineProps<{
    label?: string
    /** 具体范围描述，如“华东 · 厂商A” */
    scope?: string
    tone?: 'info' | 'warning'
    clickable?: boolean
  }>(),
  { label: '范围受限', scope: '', tone: 'info', clickable: false },
)

defineEmits<{ (event: 'click'): void }>()
</script>

<template>
  <el-tooltip
    :content="scope ? `当前数据范围：${scope}` : '你的角色在该功能上只允许访问授权范围内的数据'"
    placement="top"
  >
    <span
      class="scope-chip"
      :class="[`scope-chip--${tone}`, { 'scope-chip--clickable': clickable }]"
      :tabindex="clickable ? 0 : undefined"
      @click="clickable && $emit('click')"
      @keydown.enter="clickable && $emit('click')"
    >
      <svg viewBox="0 0 16 16" aria-hidden="true" class="scope-chip__icon">
        <circle cx="8" cy="8" r="6.2" fill="none" stroke="currentColor" stroke-width="1.4" />
        <path d="M8 1.8c2 2 2 10.4 0 12.4M2.2 8h11.6" fill="none" stroke="currentColor" stroke-width="1.2" />
      </svg>
      <span>{{ label }}</span>
      <span v-if="scope" class="scope-chip__scope">{{ scope }}</span>
    </span>
  </el-tooltip>
</template>

<style scoped>
.scope-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 20px;
  padding: 0 7px 0 5px;
  border-radius: var(--r-ctl);
  background: var(--color-info-bg);
  color: var(--text-muted-aa);
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}
.scope-chip--warning {
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.scope-chip--clickable {
  cursor: pointer;
  transition: background var(--t-color) var(--ease);
}
.scope-chip--clickable:hover {
  background: #e9ebef;
}
.scope-chip--warning.scope-chip--clickable:hover {
  background: #ffefc9;
}
.scope-chip__icon {
  width: 12px;
  height: 12px;
  flex: none;
}
.scope-chip__scope {
  color: inherit;
  opacity: 0.85;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
