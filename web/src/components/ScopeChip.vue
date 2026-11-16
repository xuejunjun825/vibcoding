<script setup lang="ts">
/**
 * 数据范围 chip（DESIGN §3.4 三态里的 ⚪）：
 * RESTRICTED 的功能入口保留，但必须把“范围”显式写在旁边，
 * 用户点进去之前就知道自己看到的是全量还是子集。
 *
 * r25 起同一枚 chip 也承担「N 个操作需要权限 + 权限码」的原因通道：
 * 两者共用 label/scope，tooltip 按 label 语义分流——
 * 权限码是「需要权限：<码>」，数据范围仍是「当前数据范围：<范围>」；
 * 特殊文案可用可选 `tooltip` 显式覆盖。
 *
 * 无障碍：chip 一律 `tabindex="0"`（DESIGN §6 把它当键盘原因通道：
 * 禁用按钮不进 tab 序，键盘用户必须能聚焦 chip 并读出原因），
 * tooltip 的 trigger 因此显式包含 focus。
 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    label?: string
    /** 具体范围描述，如“华东 · 厂商A”；权限 chip 里是权限码清单 */
    scope?: string
    /** 显式覆盖 tooltip 文案（默认按 label/scope 推导） */
    tooltip?: string
    tone?: 'info' | 'warning'
    clickable?: boolean
  }>(),
  { label: '范围受限', scope: '', tooltip: '', tone: 'info', clickable: false },
)

defineEmits<{ (event: 'click'): void }>()

/** 权限原因 chip：label 形如「1 个操作需要权限」，scope 是权限码清单 */
const isPermissionChip = computed(() => /个操作需要权限/.test(props.label))

const tooltipText = computed(() => {
  if (props.tooltip) return props.tooltip
  if (!props.scope) return '你的角色在该功能上只允许访问授权范围内的数据'
  return isPermissionChip.value ? `需要权限：${props.scope}` : `当前数据范围：${props.scope}`
})
</script>

<template>
  <el-tooltip :content="tooltipText" placement="top" :trigger="['hover', 'focus']">
    <span
      class="scope-chip"
      :class="[`scope-chip--${tone}`, { 'scope-chip--clickable': clickable }]"
      :role="clickable ? 'button' : undefined"
      :tabindex="0"
      @click="clickable && $emit('click')"
      @keydown.enter="clickable && $emit('click')"
      @keydown.space.prevent="clickable && $emit('click')"
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
/* 键盘焦点：沿用全局 2px ring，但保留 chip 自己的圆角（base.css 的 :focus-visible 会改成 2px） */
.scope-chip:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
  border-radius: var(--r-ctl);
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
