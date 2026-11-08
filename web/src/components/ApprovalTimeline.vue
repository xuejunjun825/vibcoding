<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ApprovalTimelineEvent } from '@/api/types'
import { useDictStore } from '@/stores/dict'
import { formatShortTime } from '@/utils/format'

/**
 * 审批时间线（DESIGN §6.2）：
 * 左轨竖线 2px + 8px 圆点（当前节点带外环），内容 = 动作 + 人 + 时间 + 意见。
 * 加签 / 减签 / 转办是独立条目，永远展开 —— 藏起来会让审批轨迹失去审计价值。
 */
const props = withDefaults(
  defineProps<{
    events: ApprovalTimelineEvent[]
    /** 实例仍在流转中：最后一个事件后追加「等待后续节点」 */
    active?: boolean
    pendingLabel?: string
    dense?: boolean
  }>(),
  { active: false, pendingLabel: '等待后续审批节点', dense: false },
)

const expanded = ref<Set<number>>(new Set())

function toggle(index: number): void {
  const next = new Set(expanded.value)
  if (next.has(index)) next.delete(index)
  else next.add(index)
  expanded.value = next
}

const rows = computed(() => props.events ?? [])

const dict = useDictStore()

/** 动作优先用中文词典；未收录的动作原样显示，避免“看不懂的英文”和“猜错的中文”两头踩 */
function actionText(event: ApprovalTimelineEvent): string {
  return dict.actionLabel(event.action) || event.action
}

function nodeState(index: number): 'done' | 'current' {
  if (!props.active) return 'done'
  return index === rows.value.length - 1 ? 'current' : 'done'
}

const ORIGIN_LABELS: Record<string, string> = {
  SIGN: '',
  ADD_SIGN: '加签',
  REMOVE_SIGN: '减签',
  TRANSFER: '转办',
  WITHDRAW: '撤回',
  URGE: '催办',
  COMMENT: '意见',
}

function originLabel(origin?: string | null): string {
  if (!origin) return ''
  return ORIGIN_LABELS[origin] ?? origin
}

function isStructural(origin?: string | null): boolean {
  return origin === 'ADD_SIGN' || origin === 'REMOVE_SIGN' || origin === 'TRANSFER'
}

function needsToggle(comment?: string | null): boolean {
  return Boolean(comment && comment.length > 90)
}
</script>

<template>
  <ol class="tl" :class="{ 'tl--dense': props.dense }">
    <li
      v-for="(event, index) in rows"
      :key="`${event.at}-${index}`"
      class="tl__item"
      :class="[`tl__item--${nodeState(index)}`, { 'tl__item--structural': isStructural(event.origin) }]"
    >
      <span class="tl__dot" aria-hidden="true" />
      <div class="tl__content">
        <div class="tl__head-line">
          <span class="tl__action" :title="event.action">{{ actionText(event) }}</span>
          <span v-if="originLabel(event.origin) && originLabel(event.origin) !== actionText(event)" class="tl__origin">
            {{ originLabel(event.origin) }}
          </span>
          <span class="tl__operator">{{ event.operator }}</span>
          <span class="tl__time">{{ formatShortTime(event.at) }}</span>
        </div>
        <p
          v-if="event.comment"
          class="tl__comment"
          :class="{ 'tl__comment--clamp': needsToggle(event.comment) && !expanded.has(index) }"
        >
          {{ event.comment }}
        </p>
        <button
          v-if="needsToggle(event.comment)"
          type="button"
          class="tl__toggle"
          @click="toggle(index)"
        >
          {{ expanded.has(index) ? '收起' : '展开' }}
        </button>
      </div>
    </li>

    <li v-if="props.active" class="tl__item tl__item--pending">
      <span class="tl__dot tl__dot--pending" aria-hidden="true" />
      <div class="tl__content">
        <span class="tl__pending-label">{{ props.pendingLabel }}</span>
      </div>
    </li>

    <li v-if="rows.length === 0 && !props.active" class="tl__empty">尚无审批记录</li>
  </ol>
</template>

<style scoped>
.tl {
  margin: 0;
  padding: 4px 0;
  list-style: none;
}
.tl__item {
  position: relative;
  padding: 0 0 16px 24px;
  border-left: 2px solid var(--border);
  margin-left: 4px;
}
.tl--dense .tl__item {
  padding-bottom: 12px;
}
.tl__item:last-child {
  border-left-color: transparent;
  padding-bottom: 0;
}
.tl__dot {
  position: absolute;
  left: -5px;
  top: 5px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-primary);
}
.tl__item--current .tl__dot {
  box-shadow: 0 0 0 3px rgba(43, 111, 229, 0.18);
}
.tl__dot--pending {
  background: var(--bg-card);
  border: 1px solid var(--border-strong);
  left: -5px;
}
.tl__item--pending {
  border-left-color: transparent;
  padding-bottom: 0;
}
.tl__item--structural .tl__dot {
  background: var(--color-warning);
}
.tl__content {
  padding-left: 8px;
  min-width: 0;
}
.tl__head-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  line-height: 18px;
}
.tl__action {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-title);
}
.tl__origin {
  display: inline-flex;
  align-items: center;
  height: 16px;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 11px;
  font-weight: 500;
}
.tl__operator {
  font-size: 12px;
  color: var(--text-body);
}
.tl__time {
  font-size: 12px;
  color: var(--text-weak);
  font-variant-numeric: tabular-nums;
  margin-left: auto;
}
.tl__comment {
  margin-top: 4px;
  font-size: 12px;
  line-height: 19px;
  color: var(--text-body);
  white-space: pre-wrap;
  word-break: break-word;
}
.tl__comment--clamp {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.tl__toggle {
  margin-top: 2px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-primary);
  font-size: 12px;
  cursor: pointer;
}
.tl__toggle:hover {
  color: var(--color-primary-hover);
}
.tl__pending-label {
  font-size: 12px;
  color: var(--text-weak);
}
.tl__empty {
  padding: 8px 0;
  font-size: 12px;
  color: var(--text-weak);
  list-style: none;
}
</style>
