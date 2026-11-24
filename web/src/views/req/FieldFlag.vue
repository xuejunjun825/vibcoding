<script setup lang="ts">
/**
 * 字段级可编辑性标记（r43，DESIGN §3.2 / §6）：概览 8 个字段行共用一份渲染。
 *
 * 三态只由 `reasonCode` 推出（`editability.ts:decisionOfReason`），组件本身不认识
 * status/type/子需求条数——任何「这里应该锁」的本地判断都是第二份矩阵。
 * 观感约束：12px / 500 纯文本，不画胶囊、不加底色（§0），只有「需变更」带文字链。
 */
import { computed, onBeforeUnmount, ref } from 'vue'
import type { RequirementFieldRule } from '@/api/types'
import { decisionOfReason, reasonMarker, reasonText } from '@/views/req/editability'

const props = defineProps<{
  /** 该字段的矩阵规则；EDIT / 缺省时不渲染任何标记 */
  rule?: RequirementFieldRule | null
  /** 无 `permissions.changeRequest` 时按钮可见但禁用（隐藏会让人误以为字段可直接改） */
  changeDisabled?: boolean
}>()

const emit = defineEmits<{ (event: 'requestChange'): void }>()

const CHANGE_DISABLED_TIP = '无发起变更权限（需要需求管理权限）'
const HOVER_DELAY = 120

const decision = computed(() => (props.rule ? decisionOfReason(props.rule.reasonCode) : null))
const tip = computed(() => reasonText(props.rule?.reasonCode))
const marker = computed(() => reasonMarker(props.rule?.reasonCode))

/** tooltip 受控：hover 120ms 后出、键盘 focus 立即出（DESIGN §6） */
const tipVisible = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

function clearTimer(): void {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

function openTip(delay = HOVER_DELAY): void {
  clearTimer()
  if (delay <= 0) {
    tipVisible.value = true
    return
  }
  timer = setTimeout(() => {
    timer = null
    tipVisible.value = true
  }, delay)
}

function closeTip(): void {
  clearTimer()
  tipVisible.value = false
}

function onRequest(): void {
  if (props.changeDisabled) return
  emit('requestChange')
}

onBeforeUnmount(clearTimer)
</script>

<template>
  <el-tooltip
    v-if="decision === 'LOCKED'"
    :visible="tipVisible"
    :content="tip"
    placement="top"
    popper-class="flag-tip"
    :show-arrow="false"
  >
    <span
      class="flag flag--locked"
      role="note"
      tabindex="0"
      :aria-label="tip"
      @mouseenter="openTip()"
      @mouseleave="closeTip"
      @focus="openTip(0)"
      @blur="closeTip"
    >🔒 {{ marker }}</span>
  </el-tooltip>

  <span v-else-if="decision === 'CHANGE_REQUIRED'" class="flag flag--change">
    <span class="flag__text" role="note" :aria-label="tip">需变更</span>
    <button
      type="button"
      class="flag__action"
      :disabled="changeDisabled"
      :aria-disabled="changeDisabled ? 'true' : 'false'"
      :title="changeDisabled ? CHANGE_DISABLED_TIP : '为这个字段发起变更单'"
      @click.stop="onRequest"
    >
      发起变更
    </button>
  </span>
</template>

<style scoped>
.flag {
  display: inline-flex;
  align-items: center;
  gap: var(--flag-gap, 4px);
  margin-left: var(--flag-gap, 4px);
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
  vertical-align: 1px;
}

.flag--locked {
  color: var(--text-muted-aa);
  cursor: help;
  border-radius: var(--r-cell);
}

.flag--change {
  color: var(--warning-ink);
}

.flag__action {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: none;
  color: var(--color-primary);
  font: inherit;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  transition: color var(--t-color) var(--ease);
}

.flag__action:hover {
  color: var(--color-primary-hover);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.flag__action:disabled {
  color: var(--text-muted-aa);
  cursor: not-allowed;
  text-decoration: none;
}

.flag:focus-visible,
.flag__action:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

/* popper 挂在 body 上，scoped 选择器够不到；只给这一处 tooltip 开 320px 上限（DESIGN §0.7） */
:global(.flag-tip) {
  max-width: var(--tooltip-max, 320px);
  font-size: 12px;
  line-height: 1.5;
}
</style>
