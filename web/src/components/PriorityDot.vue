<script setup lang="ts">
import { computed } from 'vue'
import { useDictStore } from '@/stores/dict'

/**
 * 优先级彩点（UI-STYLE §2）：P0 红 / P1 橙 / P2 蓝 / P3 灰。
 * 颜色之外始终带文字——`dict.priority()` 自 r14 起出「紧急/高/中/低」而不是裸 code，
 * 所以这里的判据是“有文字”，不是“有字母”。色块取 `meta.color`（P0 红…）；
 * 需要语义色时用 `meta.tone`（danger/warning/primary/neutral），两者别混用。
 */
const props = withDefaults(
  defineProps<{
    value?: string | null
    /** 只显示圆点，文字交给外部 */
    dotOnly?: boolean
    size?: number
  }>(),
  { value: 'P2', dotOnly: false, size: 6 },
)

const dict = useDictStore()
const meta = computed(() => dict.priority(props.value))
</script>

<template>
  <span class="prio" :title="meta.hint ? `${meta.label} · ${meta.hint}` : meta.label">
    <span class="prio__dot" :style="{ background: meta.color, width: `${size}px`, height: `${size}px` }" />
    <span v-if="!dotOnly" class="prio__text">{{ meta.label }}</span>
  </span>
</template>

<style scoped>
.prio {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-body);
}
.prio__dot {
  flex: none;
  border-radius: 50%;
}
.prio__text {
  font-weight: 500;
}
</style>
