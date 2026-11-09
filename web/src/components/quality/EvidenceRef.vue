<script setup lang="ts">
/**
 * 报告数字的“可点开证据”引用（DESIGN §6 / Q7）。
 *
 * 只承载“这个数字背后有哪些明细”的入口，不做任何换算：
 * 数字本身一律来自报告快照（服务端已格式化），分页与筛选交给抽屉里的接口。
 * 不可下钻时（例如历史数据未保留 id 列表）退化为普通文本 + 说明，不用假按钮。
 */
withDefaults(
  defineProps<{
    value: string | number | null | undefined
    /** 单位或补充，如“条”“%” */
    suffix?: string
    /** 明细规模；有值时按钮文案为“value（count 条）” */
    count?: number | null
    title?: string
    /** 明确禁用（无权限 / 无明细） */
    disabled?: boolean
    disabledReason?: string
  }>(),
  { suffix: '', count: null, title: '查看明细', disabled: false, disabledReason: '' },
)

const emit = defineEmits<{ open: [] }>()

function onOpen(): void {
  emit('open')
}
</script>

<template>
  <el-tooltip
    v-if="!disabled"
    placement="top"
    :content="title"
    :show-after="300"
  >
    <button type="button" class="evidence-ref" @click="onOpen">
      <span class="evidence-ref__value">{{ value ?? '—' }}{{ suffix }}</span>
      <span v-if="count !== null && count !== undefined" class="evidence-ref__count">
        （{{ count }} 条）
      </span>
    </button>
  </el-tooltip>
  <span v-else class="evidence-ref evidence-ref--flat" :title="disabledReason || title">
    {{ value ?? '—' }}{{ suffix }}
  </span>
</template>

<style scoped>
.evidence-ref {
  display: inline-flex;
  align-items: baseline;
  gap: 2px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-primary);
  font: inherit;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  border-bottom: 1px dashed transparent;
  transition:
    color var(--t-color) var(--ease),
    border-color var(--t-color) var(--ease);
}
.evidence-ref:hover {
  color: var(--color-primary-hover);
  border-bottom-color: currentColor;
}
.evidence-ref:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
  border-radius: 2px;
}
.evidence-ref__value {
  font-weight: 500;
}
.evidence-ref__count {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.evidence-ref--flat {
  cursor: default;
  color: var(--text-title);
  font-weight: 500;
}
</style>
