<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AdmissionCounts, AdmissionStatus, GateItem } from '@/api/types'

/**
 * 门禁 / 准入共用常驻清单（DESIGN §6.1 / I3）。
 *
 * - mode=gate      需求流转前的用例门禁（两态，旧调用点零改动）
 * - mode=admission 发布单的 9 项准入（四态 PASS / FAIL / SKIP / CLOSED）
 *
 * 语义边界（TECHNICAL §4.6）：
 * - 计数**以服务端 `counts` 为准**；只有历史两态快照没有 counts 时才按 status/passed 回落到两态计数，
 *   前端不实现第三套判定算法；
 * - SKIP 永不计 PASS：跳过黄条明确写“本次提交不校验 z 项，不等于全部通过”；
 * - CLOSED（集成未启用）灰色、不阻断、不可展开；
 * - FAIL 不整行铺红，只在左侧留 2px 红边并定位修复提示。
 *
 * 409 不是 toast，而是清单顶部的常驻条 + 逐条定位。
 */
const props = withDefaults(
  defineProps<{
    items: GateItem[]
    mode?: 'admission' | 'gate'
    readonly?: boolean
    title?: string
    /** 服务端四态计数（服务端唯一算法）；缺省时仅对两态快照回落 */
    counts?: AdmissionCounts | null
    /** 有项因超预算/数据不可用而降级为 SKIP */
    partial?: boolean
    /** 最近一次提交被服务端拒绝（409 GATE_BLOCKED / QUALITY_GATE_FAILED / 400 SKIP_NOT_ACKNOWLEDGED） */
    rejected?: boolean
    rejectedText?: string
    checkedAt?: string | null
    /** 快照已过期（发布单需求变更后） */
    stale?: boolean
    /** 显示「强制放行」入口（仅 admission 且有权时） */
    allowForce?: boolean
    forceDisabledReason?: string
  }>(),
  {
    mode: 'gate',
    readonly: false,
    title: '',
    counts: null,
    partial: false,
    rejected: false,
    rejectedText: '',
    checkedAt: null,
    stale: false,
    allowForce: false,
    forceDisabledReason: '',
  },
)

const emit = defineEmits<{ (event: 'force'): void }>()

const isAdmission = computed(() => props.mode === 'admission')

function statusOf(item: GateItem): AdmissionStatus {
  // M6 历史快照没有 status：回落两态，绝不把无证据的项渲染成 SKIP/CLOSED
  return item.status ?? (item.passed ? 'PASS' : 'FAIL')
}

/**
 * 四态计数：优先服务端 counts；历史快照没有 counts 时按 status 统计。
 * 回落的 skipped/closed 恒为 0（两态快照里不存在这两种状态），因此不会把 PASS 算多。
 */
const counts = computed<AdmissionCounts>(() => {
  if (props.counts) return props.counts
  let passed = 0
  let failed = 0
  let skipped = 0
  let closed = 0
  for (const item of props.items) {
    const status = statusOf(item)
    if (status === 'PASS') passed += 1
    else if (status === 'SKIP') skipped += 1
    else if (status === 'CLOSED') closed += 1
    else failed += 1
  }
  return {
    passed,
    failed,
    skipped,
    closed,
    active: passed + failed + skipped,
    total: props.items.length,
  }
})

const allPassed = computed(() => counts.value.total > 0 && counts.value.passed === counts.value.total)
const blockedByFail = computed(() => counts.value.failed > 0)
const heading = computed(
  () => props.title || (isAdmission.value ? '发布准入检查' : '测试门禁'),
)

const rejectMessage = computed(() => {
  if (props.rejectedText) return props.rejectedText
  if (blockedByFail.value) return `提交被拒绝：${counts.value.failed} 项未通过`
  if (counts.value.skipped > 0) return `提交被拒绝：${counts.value.skipped} 项跳过未确认`
  return '提交被拒绝：准入校验未通过'
})

const footHint = computed(() => {
  if (blockedByFail.value) return `${counts.value.failed} 项未通过，修复后再提交`
  if (counts.value.skipped > 0) return `提交前需确认跳过 ${counts.value.skipped} 项（跳过不等于通过）`
  if (counts.value.closed > 0) return `${counts.value.closed} 项已关闭（集成未启用），不参与本次校验`
  if (allPassed.value) return '全部通过，可提交'
  return '尚未生成检查项，点击「重新校验」后再提交'
})

const checkedLabel = computed(() => {
  if (!props.checkedAt) return ''
  const date = new Date(props.checkedAt)
  if (Number.isNaN(date.getTime())) return props.checkedAt
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getMonth() + 1}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
})

const expanded = ref<Record<string, boolean>>({})

function hasDetails(item: GateItem): boolean {
  return Boolean(
    item.detail ||
      item.evidence ||
      item.reasonCode ||
      item.fixHint ||
      item.rules?.length ||
      item.snapshotId,
  )
}

function canExpand(item: GateItem): boolean {
  return statusOf(item) !== 'CLOSED' && hasDetails(item)
}

function toggle(item: GateItem): void {
  if (!canExpand(item)) return
  expanded.value = { ...expanded.value, [item.code]: !expanded.value[item.code] }
}

function flagText(item: GateItem, status: AdmissionStatus): string {
  if (item.forced) return '强制放行'
  if (status === 'SKIP') return '跳过'
  if (status === 'CLOSED') return '已关闭'
  return ''
}
</script>

<template>
  <section class="gate" :class="[`gate--${props.mode}`]">
    <header class="gate__head">
      <div class="gate__title-wrap">
        <span class="gate__title">{{ heading }}</span>
        <span class="gate__total">{{ props.items.length }} 项</span>
        <span v-if="props.stale" class="gate__stale">快照已过期，请重新检查</span>
      </div>
      <div class="gate__meta">
        <span v-if="checkedLabel" class="gate__time">检查于 {{ checkedLabel }}</span>
        <slot name="head-extra" />
      </div>
    </header>

    <!-- 计数条：通过 x · 失败 y · 跳过 z · 关闭 w（服务端 counts；aria-live 让读屏跟上重算） -->
    <div v-if="isAdmission && props.items.length" class="gate__counts" role="status" aria-live="polite">
      <span class="gate__count-item gate__count-item--pass">通过 {{ counts.passed }}</span>
      <span class="gate__count-dot" aria-hidden="true">·</span>
      <span class="gate__count-item gate__count-item--fail">失败 {{ counts.failed }}</span>
      <span class="gate__count-dot" aria-hidden="true">·</span>
      <span class="gate__count-item gate__count-item--skip">跳过 {{ counts.skipped }}</span>
      <span class="gate__count-dot" aria-hidden="true">·</span>
      <span class="gate__count-item gate__count-item--closed">关闭 {{ counts.closed }}</span>
      <span class="gate__counts-tail">
        <span v-if="props.partial" class="gate__partial">部分证据不可用，已按跳过记录</span>
      </span>
    </div>

    <!-- 409 常驻条：留在页面上，不消失 -->
    <div v-if="props.rejected" class="gate__rejected" role="alert">
      <svg viewBox="0 0 16 16" aria-hidden="true">
        <circle cx="8" cy="8" r="6.4" fill="none" stroke="currentColor" stroke-width="1.3" />
        <path d="M8 4.6v4.2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        <circle cx="8" cy="10.9" r="0.8" fill="currentColor" />
      </svg>
      <span class="gate__rejected-text">{{ rejectMessage }}</span>
      <span class="gate__rejected-hint">逐项处理后重新提交</span>
    </div>

    <!-- 跳过黄条：跳过不等于通过 -->
    <div v-if="isAdmission && counts.skipped > 0" class="gate__skip-banner" role="status">
      本次提交不校验 {{ counts.skipped }} 项，<b>不等于全部通过</b>；提交时需勾选确认。
    </div>

    <ul v-if="props.items.length" class="gate__list">
      <li
        v-for="(item, index) in props.items"
        :key="item.code"
        class="gate__row"
        :class="[`gate__row--${statusOf(item).toLowerCase()}`, { 'gate__row--forced': item.forced, 'is-open': expanded[item.code] }]"
      >
        <button
          type="button"
          class="gate__main"
          :aria-expanded="canExpand(item) ? Boolean(expanded[item.code]) : undefined"
          :disabled="!canExpand(item)"
          @click="toggle(item)"
        >
          <span class="gate__index" aria-hidden="true">{{ item.order ?? index + 1 }}</span>
          <span class="gate__icon" :class="`gate__icon--${statusOf(item).toLowerCase()}`">
            <svg v-if="statusOf(item) === 'PASS'" viewBox="0 0 16 16" aria-hidden="true">
              <circle cx="8" cy="8" r="7" fill="currentColor" />
              <path d="m4.9 8.2 2.1 2.1 4-4.3" fill="none" stroke="#fff" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <svg v-else-if="statusOf(item) === 'FAIL'" viewBox="0 0 16 16" aria-hidden="true">
              <circle cx="8" cy="8" r="7" fill="currentColor" />
              <path d="M8 4.4v4.4" stroke="#fff" stroke-width="1.6" stroke-linecap="round" />
              <circle cx="8" cy="11.4" r="0.95" fill="#fff" />
            </svg>
            <svg v-else-if="statusOf(item) === 'SKIP'" viewBox="0 0 16 16" aria-hidden="true">
              <circle cx="8" cy="8" r="7" fill="currentColor" />
              <path d="M4.8 8h6.4" stroke="#fff" stroke-width="1.6" stroke-linecap="round" />
            </svg>
            <svg v-else viewBox="0 0 16 16" aria-hidden="true">
              <circle cx="8" cy="8" r="6.2" fill="none" stroke="currentColor" stroke-width="1.3" />
              <path d="M4.6 11.4 11.4 4.6" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
            </svg>
          </span>

          <span class="gate__body">
            <span class="gate__name-line">
              <span class="gate__name">{{ item.name }}</span>
              <span v-if="item.code" class="gate__code">{{ item.code }}</span>
              <span
                v-if="flagText(item, statusOf(item))"
                class="gate__flag"
                :class="`gate__flag--${flagText(item, statusOf(item)) === '跳过' ? 'skip' : 'muted'}`"
              >
                {{ flagText(item, statusOf(item)) }}
              </span>
            </span>
            <span
              v-if="item.detail"
              class="gate__detail"
              :class="{ 'gate__detail--skip': statusOf(item) === 'SKIP', 'gate__detail--closed': statusOf(item) === 'CLOSED' }"
            >
              <template v-if="statusOf(item) === 'SKIP'">跳过：</template>
              <template v-else-if="statusOf(item) === 'CLOSED'">已关闭：</template>
              {{ item.detail }}
            </span>
            <span v-if="statusOf(item) === 'FAIL' && item.fixHint" class="gate__fix">
              <span class="gate__fix-label">修复</span>{{ item.fixHint }}
            </span>
          </span>

          <span class="gate__pill" :class="`gate__pill--${statusOf(item).toLowerCase()}`">
            {{ statusOf(item) }}
          </span>
          <span class="gate__chevron" :class="{ 'is-open': expanded[item.code], 'is-hidden': !canExpand(item) }" aria-hidden="true">
            <svg viewBox="0 0 12 12"><path d="m3.5 4.5 2.5 3 2.5-3" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" /></svg>
          </span>
        </button>

        <div v-if="expanded[item.code] && canExpand(item)" class="gate__expand">
          <dl v-if="item.evidence" class="gate__kv">
            <dt>证据</dt>
            <dd class="mono">{{ item.evidence }}</dd>
          </dl>
          <dl v-if="item.reasonCode" class="gate__kv">
            <dt>原因码</dt>
            <dd class="mono">{{ item.reasonCode }}</dd>
          </dl>
          <dl v-if="item.snapshotId" class="gate__kv">
            <dt>质量门快照</dt>
            <dd class="mono">#{{ item.snapshotId }}</dd>
          </dl>
          <ul v-if="item.rules?.length" class="gate__rules">
            <li v-for="rule in item.rules" :key="rule.code" class="gate__rule">
              <span class="gate__rule-dot" :class="`gate__rule-dot--${String(rule.status).toLowerCase()}`" aria-hidden="true" />
              <span class="gate__rule-name">{{ rule.name || rule.code }}</span>
              <span class="gate__rule-values">
                <span class="mono">{{ rule.actual }}</span>
                <span class="gate__rule-slash">/</span>
                <span class="mono muted">要求 {{ rule.threshold }}</span>
              </span>
              <span v-if="rule.fixHint && String(rule.status).toUpperCase() !== 'PASS'" class="gate__rule-fix">{{ rule.fixHint }}</span>
            </li>
          </ul>
          <p v-if="!item.evidence && !item.reasonCode && !item.rules?.length && !item.snapshotId && item.detail" class="gate__expand-note">
            {{ item.detail }}
          </p>
        </div>
      </li>
    </ul>

    <div v-else class="gate__empty">尚未生成检查项，点击「重新检查」后再提交</div>

    <footer v-if="!props.readonly" class="gate__foot">
      <span class="gate__foot-hint" :class="{ 'gate__foot-hint--warn': counts.skipped > 0 && counts.failed === 0 }">
        {{ footHint }}
      </span>
      <div class="gate__foot-actions">
        <slot name="actions" />
        <el-tooltip
          v-if="props.allowForce"
          :content="props.forceDisabledReason || '强制放行需要填写原因，并通知域负责人'"
          placement="top"
        >
          <span>
            <el-button
              text
              type="warning"
              :disabled="Boolean(props.forceDisabledReason)"
              @click="emit('force')"
            >
              强制放行
            </el-button>
          </span>
        </el-tooltip>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.gate {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
}
.gate__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 44px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--divider);
}
.gate__title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.gate__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.gate__total {
  font-size: 12px;
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}
.gate__stale {
  font-size: 12px;
  color: var(--warning-ink);
}
.gate__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.gate__time {
  font-size: 12px;
  color: var(--text-weak);
  font-variant-numeric: tabular-nums;
}

/* 计数条：固定 40 高，任何状态都占位，避免重算后页面跳动 */
.gate__counts {
  display: flex;
  align-items: center;
  gap: 6px;
  height: var(--h-count-strip, 40px);
  padding: 0 16px;
  border-bottom: 1px solid var(--divider);
  background: var(--bg-table-head);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.gate__count-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
}
.gate__count-item--pass {
  color: var(--success-ink);
}
.gate__count-item--fail {
  color: var(--danger-ink);
}
.gate__count-item--skip {
  color: var(--warning-ink);
}
.gate__count-item--closed {
  color: var(--text-weak);
}
.gate__count-dot {
  color: var(--text-weak);
}
.gate__counts-tail {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.gate__partial {
  font-size: 11px;
  color: var(--warning-ink);
}

.gate__rejected {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: var(--color-danger-bg);
  border-bottom: 1px solid #f7d4d5;
  color: var(--danger-ink);
  font-size: 12px;
}
.gate__rejected svg {
  width: 14px;
  height: 14px;
  flex: none;
}
.gate__rejected-text {
  font-weight: 600;
}
.gate__rejected-hint {
  color: #a35a5e;
}
.gate__skip-banner {
  padding: 8px 16px;
  border-bottom: 1px solid #f0dcae;
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
}
.gate__skip-banner b {
  font-weight: 600;
}

.gate__list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.gate__row {
  border-left: 2px solid transparent;
  border-bottom: 1px solid var(--divider);
  transition: background var(--t-color) var(--ease);
}
.gate__row:last-child {
  border-bottom: none;
}
.gate__row:hover {
  background: var(--bg-hover);
}
.gate__row--fail {
  border-left-color: var(--color-danger);
  background: #fffbfb;
}
.gate__row--fail:hover {
  background: #fff5f5;
}
.gate__row--skip {
  border-left-color: var(--color-warning);
}
.gate__row--closed {
  background: var(--bg-hover);
}
.gate__row.is-open {
  background: var(--bg-hover);
}

/* 整行是按钮：键盘可达；禁用时不响应（CLOSED 不可展开） */
.gate__main {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  min-height: var(--h-admission-row, 48px);
  padding: 10px 16px 10px 10px;
  border: 0;
  background: transparent;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
}
.gate__main:disabled {
  cursor: default;
}
.gate__main:focus-visible {
  outline: 2px solid var(--focus-ring, #2f6fed);
  outline-offset: -2px;
}
.gate__index {
  flex: none;
  width: 18px;
  margin-top: 2px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-weak);
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.gate__icon {
  flex: none;
  width: 16px;
  height: 16px;
  margin-top: 2px;
}
.gate__icon svg {
  width: 16px;
  height: 16px;
  display: block;
}
.gate__icon--pass {
  color: var(--color-success);
}
.gate__icon--fail {
  color: var(--color-danger);
}
.gate__icon--skip {
  color: var(--color-warning);
}
.gate__icon--closed {
  color: var(--text-weak);
}
.gate__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.gate__name-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.gate__name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-title);
  line-height: 18px;
}
.gate__code {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-weak);
  letter-spacing: 0.02em;
}
.gate__flag {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 7px;
  border: 1px solid #e9c989;
  border-radius: var(--r-ctl);
  background: repeating-linear-gradient(
    45deg,
    #fff7e6,
    #fff7e6 4px,
    #ffefc9 4px,
    #ffefc9 8px
  );
  color: var(--warning-ink);
  font-size: 11px;
  font-weight: 500;
}
.gate__flag--muted {
  border-color: var(--border);
  background: var(--bg-hover);
  color: var(--text-muted-aa);
  background-image: none;
}
.gate__detail {
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 18px;
}
.gate__detail--skip {
  color: var(--warning-ink);
}
.gate__detail--closed {
  color: var(--text-weak);
}
.gate__fix {
  margin-top: 2px;
  font-size: 12px;
  color: var(--danger-ink);
  line-height: 18px;
}
.gate__fix-label {
  display: inline-flex;
  align-items: center;
  height: 16px;
  margin-right: 6px;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--color-danger-bg);
  font-size: 11px;
  font-weight: 500;
}
.gate__pill {
  flex: none;
  width: var(--w-admission-pill, 64px);
  height: 20px;
  margin-top: 1px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-ctl);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.gate__pill--pass {
  background: var(--color-success-bg);
  color: var(--success-ink);
}
.gate__pill--fail {
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}
.gate__pill--skip {
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.gate__pill--closed {
  background: var(--color-info-bg);
  color: var(--text-muted-aa);
}
.gate__chevron {
  flex: none;
  width: 12px;
  height: 12px;
  margin-top: 4px;
  color: var(--text-weak);
  transition: transform var(--t-expand, 0.16s) var(--ease);
}
.gate__chevron.is-open {
  transform: rotate(180deg);
}
.gate__chevron.is-hidden {
  visibility: hidden;
}
.gate__chevron svg {
  width: 12px;
  height: 12px;
  display: block;
}

/* 展开区：不改变行高上限，内容自己撑开 */
.gate__expand {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 4px 16px 12px 54px;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gate__kv {
  display: flex;
  gap: 8px;
  margin: 0;
}
.gate__kv dt {
  flex: none;
  width: 72px;
  color: var(--text-weak);
}
.gate__kv dd {
  margin: 0;
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--text-body);
}
.gate__rules {
  margin: 4px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.gate__rule {
  display: grid;
  grid-template-columns: 10px minmax(110px, 1fr) auto;
  gap: 4px 8px;
  align-items: baseline;
  padding: 4px 0;
  border-top: 1px dashed var(--divider);
}
.gate__rule:first-child {
  border-top: 0;
}
.gate__rule-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-weak);
  transform: translateY(-1px);
}
.gate__rule-dot--pass {
  background: var(--color-success);
}
.gate__rule-dot--fail {
  background: var(--color-danger);
}
.gate__rule-dot--skip {
  background: var(--color-warning);
}
.gate__rule-name {
  color: var(--text-title);
}
.gate__rule-values {
  display: inline-flex;
  gap: 4px;
  font-variant-numeric: tabular-nums;
}
.gate__rule-slash {
  color: var(--text-weak);
}
.gate__rule-fix {
  grid-column: 2 / -1;
  color: var(--danger-ink);
}
.gate__expand-note {
  margin: 0;
  color: var(--text-muted-aa);
}

.gate__empty {
  padding: 20px 16px;
  font-size: 12px;
  color: var(--text-weak);
}
.gate__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px;
  border-top: 1px solid var(--divider);
  background: var(--bg-table-head);
  flex-wrap: wrap;
}
.gate__foot-hint {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gate__foot-hint--warn {
  color: var(--warning-ink);
}
.gate__foot-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
