<script setup lang="ts">
/**
 * 提交快照回执卡（r30 / DESIGN §4.4）：只读证据，不是第二条传送带。
 *
 * - 只有「轮次 + 固化时间 + 12 位摘要 + 段级 stale 印章」，没有删除/重算/重拍入口；
 * - 轮次切换由父级拉取 /flow-snapshots/{round} 后就地回放，本组件不发请求；
 * - stale 由后端比较 digest 后透传（segmentStale/changedSinceSubmit），前端不自行判断。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FlowSnapshot, FlowSnapshotSummary } from '@/api/types'
import { formatDateTime } from '@/utils/format'

const props = defineProps<{
  snapshot?: FlowSnapshot | null
  history?: FlowSnapshotSummary[]
  loading?: boolean
  error?: string
  /** 最新轮次；缺省取 history 末项（回放态判定用） */
  latestRound?: number | null
}>()

const emit = defineEmits<{
  (e: 'replay', round: number): void
  (e: 'retry'): void
}>()

const SEGMENTS: Array<{ key: 'testPlan' | 'signoff' | 'qualityGate' | 'deployAdmission'; label: string }> = [
  { key: 'testPlan', label: '测试计划' },
  { key: 'signoff', label: '报告三签' },
  { key: 'qualityGate', label: '质量门' },
  { key: 'deployAdmission', label: '发布准入' },
]

const rounds = computed(() => props.history ?? [])
const latest = computed(() => {
  if (props.latestRound != null) return props.latestRound
  const list = rounds.value
  return list.length > 0 ? Math.max(...list.map((item) => item.round)) : null
})
const replaying = computed(() => {
  if (!props.snapshot?.round) return false
  return latest.value != null && props.snapshot.round !== latest.value
})

/**
 * 唯一一处成功动效（DESIGN §4.4）：轮次出现/前进时 hash 行 600ms 渐隐高亮。
 * 回放旧轮次不闪——回放不是「新的固化」，闪了会让人以为又提交了一次。
 */
const flash = ref(false)
let flashTimer: number | undefined
watch(
  () => props.snapshot?.round,
  (next, prev) => {
    if (next == null) return
    if (prev != null && next <= prev) return
    flash.value = true
    window.clearTimeout(flashTimer)
    flashTimer = window.setTimeout(() => {
      flash.value = false
    }, 600)
  },
)
onBeforeUnmount(() => window.clearTimeout(flashTimer))

const hashShort = computed(() => (props.snapshot?.payloadHash ?? '').slice(0, 12))
const changedSegments = computed(() =>
  SEGMENTS.filter((seg) => props.snapshot?.segmentStale?.[seg.key]).map((seg) => seg.label),
)

function segValue(key: (typeof SEGMENTS)[number]['key']): string {
  const chain = props.snapshot?.chain
  if (!chain) return '—'
  if (key === 'testPlan') return `${chain.testPlan?.approved ?? 0}/${chain.testPlan?.total ?? 0}`
  if (key === 'signoff') return `${chain.signoff?.approved ?? 0}/${chain.signoff?.total ?? 0}`
  if (key === 'qualityGate') return chain.qualityGate?.result || '—'
  const admission = chain.deployAdmission
  if (!admission) return '—'
  return `${admission.passedCount ?? 0}/${(admission.passedCount ?? 0) + (admission.failedCount ?? 0)}`
}

function levelLabel(): string {
  const raw = props.snapshot?.releaseLevel
  if (raw === 'NORMAL') return '常规'
  if (raw === 'EMERGENCY') return '紧急'
  if (raw === 'ROLLBACK') return '回滚'
  return ''
}

function onSelect(round: number): void {
  if (!round || round === props.snapshot?.round) return
  emit('replay', round)
}

async function copyHash(): Promise<void> {
  const full = props.snapshot?.payloadHash
  if (!full) return
  try {
    await navigator.clipboard.writeText(full)
    ElMessage.success('快照摘要已复制')
  } catch {
    // 剪贴板被浏览器拒绝时退回全选提示：hash 全文仍在 title 与文本里，可手动复制
    ElMessage.warning('浏览器拒绝写入剪贴板，请从提示中手动复制')
  }
}
</script>

<template>
  <section class="surface surface--pad snapshot" :class="{ 'is-flash': flash }" aria-labelledby="flow-snapshot-title">
    <header class="snapshot__head">
      <h2 id="flow-snapshot-title" class="section-title">提交快照</h2>
      <span v-if="snapshot" class="snapshot__seal">已固化</span>
    </header>

    <div v-if="loading" class="snapshot__skeleton" aria-busy="true">
      <span class="skeleton" style="height: 16px; width: 52%" />
      <span class="skeleton" style="height: 12px; width: 78%" />
    </div>

    <p v-else-if="error" class="snapshot__error" role="alert">
      {{ error }}
      <button type="button" class="link" @click="emit('retry')">重试</button>
    </p>

    <p v-else-if="!snapshot" class="snapshot__empty">尚未提交 · 提交成功后才生成快照</p>

    <template v-else>
      <div class="snapshot__top">
        <div class="snapshot__round">
          <span class="snapshot__round-no">第 {{ snapshot.round }} 轮</span>
          <el-select
            v-if="rounds.length > 1"
            :model-value="snapshot.round"
            size="small"
            class="snapshot__select"
            aria-label="选择回放轮次"
            @change="onSelect"
          >
            <el-option
              v-for="item in rounds"
              :key="item.round"
              :value="item.round"
              :label="`第 ${item.round} 轮 · ${formatDateTime(item.capturedAt)}`"
            />
          </el-select>
        </div>
        <span v-if="levelLabel()" class="snapshot__level">{{ levelLabel() }}</span>
      </div>

      <p class="snapshot__meta">
        固化于 {{ formatDateTime(snapshot.capturedAt) }}
        <template v-if="snapshot.definitionVersion"> · 定义 v{{ snapshot.definitionVersion }}</template>
        <template v-if="snapshot.nodeKeys && snapshot.nodeKeys.length > 0">
          · {{ snapshot.nodeKeys.length }} 级审批
        </template>
      </p>

      <button
        type="button"
        class="snapshot__hash"
        :title="`${snapshot.payloadHash}（点击复制全文）`"
        @click="copyHash"
      >
        <span class="snapshot__hash-label">摘要</span>
        <span class="mono snapshot__hash-value">{{ hashShort }}</span>
        <span class="snapshot__hash-copy">复制</span>
      </button>

      <p v-if="replaying" class="snapshot__replay" aria-live="polite">
        正在回放第 {{ snapshot.round }} 轮（只读）：提交时固化的证据不会重算。
      </p>

      <div v-if="snapshot.changedSinceSubmit" class="snapshot__stale" role="status">
        <span class="snapshot__stale-title">提交后链路已变化</span>
        <span class="snapshot__stale-body">
          {{ changedSegments.length > 0 ? changedSegments.join('、') + ' 已变化' : '段摘要与提交时不一致' }}
        </span>
      </div>

      <ul class="snapshot__grid">
        <li v-for="seg in SEGMENTS" :key="seg.key" class="snap-seg">
          <span class="snap-seg__label">{{ seg.label }}</span>
          <span class="snap-seg__value num">{{ segValue(seg.key) }}</span>
          <span v-if="snapshot.segmentStale?.[seg.key]" class="snap-seg__stale">已变化</span>
        </li>
      </ul>

      <p class="snapshot__foot">
        快照只插不改；审批与门禁的判定仍以服务端为准，回放不会改变当前链路。
      </p>
    </template>
  </section>
</template>

<style scoped>
.snapshot {
  --w-hash: 132px;
  --flash-ok: var(--color-success-bg);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.snapshot__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.snapshot__seal {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  border-radius: var(--r-cell);
  background: var(--bg-selected);
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 500;
}
.snapshot__empty,
.snapshot__error {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
}
.snapshot__error {
  color: var(--danger-ink);
}
.snapshot__skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.snapshot__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.snapshot__round {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.snapshot__round-no {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
  white-space: nowrap;
}
.snapshot__select {
  width: 148px;
}
.snapshot__level {
  flex: none;
  padding: 0 6px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  font-size: 12px;
  line-height: 18px;
  color: var(--text-body);
}
.snapshot__meta {
  margin: 0;
  font-size: 12px;
  line-height: 16px;
  color: var(--text-muted-aa);
}
.snapshot__hash {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  max-width: var(--w-hash);
  padding: 4px 6px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-hover);
  cursor: pointer;
  text-align: left;
}
.snapshot__hash:hover {
  border-color: var(--color-primary);
  background: var(--bg-selected);
}
.snapshot__hash:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.snapshot__hash-label {
  flex: none;
  font-size: 11px;
  color: var(--text-muted-aa);
}
.snapshot__hash-value {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--text-body);
  letter-spacing: 0.2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.snapshot__hash-copy {
  flex: none;
  font-size: 11px;
  color: var(--color-primary);
}
.snapshot__replay {
  margin: 0;
  padding: 4px 6px;
  border-left: 2px solid var(--color-primary);
  background: var(--bg-selected);
  font-size: 12px;
  line-height: 16px;
  color: var(--text-body);
}
.snapshot__stale {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px;
  border: 1px solid var(--color-warning);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
}
.snapshot__stale-title {
  font-size: 12px;
  font-weight: 600;
  line-height: 16px;
  color: var(--warning-ink);
}
.snapshot__stale-body {
  font-size: 12px;
  line-height: 16px;
  color: var(--warning-ink);
}
.snapshot__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.snap-seg {
  position: relative;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 6px;
  padding: 4px 6px;
  border: 1px solid var(--divider);
  border-radius: var(--r-cell);
  min-width: 0;
}
.snap-seg__label {
  font-size: 12px;
  color: var(--text-muted-aa);
  white-space: nowrap;
}
.snap-seg__value {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.snap-seg__stale {
  position: absolute;
  top: -6px;
  right: 4px;
  padding: 0 3px;
  border-radius: var(--r-cell);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 10px;
  line-height: 14px;
}
.snapshot__foot {
  margin: 0;
  font-size: 11px;
  line-height: 16px;
  color: var(--text-muted-aa);
}
/* 提交成功后才闪一次：只在快照行，不整卡闪（避免把整页注意力拉走） */
.snapshot.is-flash .snapshot__hash {
  animation: hash-flash 600ms ease-out;
}
@keyframes hash-flash {
  from {
    background: var(--flash-ok);
    border-color: var(--color-success);
  }
  to {
    background: var(--bg-hover);
    border-color: var(--border);
  }
}
@media (prefers-reduced-motion: reduce) {
  .snapshot.is-flash .snapshot__hash {
    animation: none;
  }
}
</style>
