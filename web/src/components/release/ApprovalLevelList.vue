<script setup lang="ts">
/**
 * 逐级审批阶梯（r30 / DESIGN §4.3）：版本详情与发布单详情共用同一视图。
 *
 * 只渲染 ApprovalLevelProjector 给出的等级状态：
 * - 未到级（WAITING）连灰色按钮都不给 —— 详情页没有审批动作，审批只在审批中心；
 * - 越级在服务端 403，本组件不做权限推断，也不出现“代批/跳过”入口。
 */
import { computed } from 'vue'
import type { ApprovalLevel } from '@/api/types'
import { formatDateTime } from '@/utils/format'

const props = defineProps<{
  levels: ApprovalLevel[]
  instanceId?: number | null
  definitionVersion?: number | null
  submittedAt?: string | null
  /** 当前节点的 nodeKey（可选；缺省时取第一个 ACTIVE/PENDING 级） */
  currentNode?: string | null
}>()

type Tone = 'pass' | 'active' | 'pending' | 'wait' | 'fail'

const MODE_LABEL: Record<string, string> = { ALL: '会签', ANY: '或签', SEQUENTIAL: '逐级' }

const currentKey = computed(() => {
  if (props.currentNode) return props.currentNode
  const hit = props.levels.find((level) => level.status === 'ACTIVE' || level.status === 'PENDING')
  return hit?.nodeKey ?? ''
})

function toneOf(status?: string | null): Tone {
  if (status === 'APPROVED') return 'pass'
  if (status === 'ACTIVE') return 'active'
  if (status === 'PENDING') return 'pending'
  if (status === 'REJECTED') return 'fail'
  return 'wait'
}

function stampOf(tone: Tone): string {
  if (tone === 'pass') return '已通过'
  if (tone === 'fail') return '已驳回'
  if (tone === 'active') return '待办'
  if (tone === 'pending') return '待办'
  return '未开始'
}

function metaOf(level: ApprovalLevel, tone: Tone): string {
  const approvers = level.approvers?.length ?? 0
  const mode = level.mode ? MODE_LABEL[level.mode] ?? '' : ''
  if (tone === 'fail') return level.comment || '审批未通过，版本退回发起人'
  if (tone === 'pass') {
    const at = level.handledAt ? formatDateTime(level.handledAt) : ''
    const who = approvers > 0 ? `${approvers} 人` : '审批人'
    return [who, at].filter(Boolean).join(' · ')
  }
  if (tone === 'wait') return '上一级通过后开始'
  const people = approvers > 0 ? `待 ${approvers} 人处理` : '待审批人处理'
  return mode ? `${people}（${mode}）` : people
}

const approvedCount = computed(() => props.levels.filter((level) => level.status === 'APPROVED').length)
const rejectedLevel = computed(() => props.levels.find((level) => level.status === 'REJECTED') ?? null)

const headerHint = computed(() => {
  if (props.levels.length === 0) return ''
  if (rejectedLevel.value) return `在「${rejectedLevel.value.nodeName}」被驳回`
  if (approvedCount.value === props.levels.length) return '全部节点已通过'
  return `已通过 ${approvedCount.value}/${props.levels.length}`
})
</script>

<template>
  <div class="levels">
    <p v-if="levels.length === 0" class="levels__empty">尚未发起审批 · 发起后逐级推进</p>

    <template v-else>
      <p class="levels__hint">{{ headerHint }}</p>
      <ol class="levels__list">
        <li
          v-for="level in levels"
          :key="level.nodeKey"
          class="level"
          :class="`level--${toneOf(level.status)}`"
          :aria-current="level.nodeKey === currentKey ? 'step' : undefined"
        >
          <span class="level__no mono" aria-hidden="true">n{{ level.index }}</span>
          <div class="level__body">
            <p class="level__name">
              {{ level.nodeName }}
              <span v-if="level.mode" class="level__mode">{{ MODE_LABEL[level.mode] ?? level.mode }}</span>
            </p>
            <p class="level__meta" :title="metaOf(level, toneOf(level.status))">
              {{ metaOf(level, toneOf(level.status)) }}
            </p>
          </div>
          <span class="level__stamp">{{ stampOf(toneOf(level.status)) }}</span>
        </li>
      </ol>
      <p class="levels__foot">
        共 {{ levels.length }} 级
        <template v-if="definitionVersion"> · 定义 v{{ definitionVersion }}</template>
        <template v-if="instanceId"> · 实例 #{{ instanceId }}</template>
        <template v-if="submittedAt"> · 发起于 {{ formatDateTime(submittedAt) }}</template>
      </p>
    </template>
  </div>
</template>

<style scoped>
.levels {
  --h-level-row: 44px;
  display: flex;
  flex-direction: column;
}
.levels__empty {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
}
.levels__hint {
  margin: 0 0 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
}
.levels__list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.level {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: var(--h-level-row);
  padding: 4px 0 4px 8px;
  border-top: 1px solid var(--divider);
}
.level:first-child {
  border-top: 0;
}
.level__no {
  flex: none;
  width: 24px;
  font-size: 12px;
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}
.level__body {
  flex: 1;
  min-width: 0;
}
.level__name {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  line-height: 18px;
  color: var(--text-title);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.level__mode {
  flex: none;
  padding: 0 4px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  font-size: 11px;
  line-height: 14px;
  color: var(--text-muted-aa);
}
.level__meta {
  margin: 0;
  font-size: 12px;
  line-height: 16px;
  color: var(--text-muted-aa);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.level__stamp {
  flex: none;
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  border-radius: var(--r-ctl);
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
}
/* 当前级：左侧 2px 主色竖条（inset，不占宽度，行内容不跳动） */
.level--active,
.level--pending {
  box-shadow: inset 2px 0 0 var(--color-primary);
  background: var(--bg-selected);
}
.level--active .level__stamp,
.level--pending .level__stamp {
  color: var(--info-ink);
  background: var(--bg-card);
  border: 1px solid var(--color-primary);
}
.level--pass .level__stamp {
  color: var(--success-ink);
  background: var(--color-success-bg);
}
.level--fail .level__stamp {
  color: var(--danger-ink);
  background: var(--color-danger-bg);
}
.level--fail {
  box-shadow: inset 2px 0 0 var(--color-danger);
}
.level--fail .level__meta {
  color: var(--danger-ink);
}
.level--wait .level__name {
  color: var(--text-body);
}
.level--wait .level__stamp {
  color: var(--text-muted-aa);
  background: var(--bg-hover);
}
.levels__foot {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 16px;
  color: var(--text-muted-aa);
}
@media (prefers-reduced-motion: reduce) {
  .level {
    transition: none;
  }
}
</style>
