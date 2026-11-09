<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import type { IntegrationLogRow, IntegrationSystem } from '@/api/integration'

/**
 * 同步日志表（DESIGN I2 / §6.5）。
 *
 * 一条业务调用可能重试 3 次，日志里就是 3 行同 correlation_id 的记录。
 * 如果平铺，人看到的是"三次失败"而不是"一次调用耗尽了重试" —— 所以按
 * correlation_id 聚合成父行（n 次尝试 · 总耗时），展开才看每次尝试。
 *
 * RETRY_EXHAUSTED 默认展开：这一行的失败原因必须在第一屏可见，
 * 让人再点一次才看到错误码是把排障成本转嫁给用户。
 */
const props = withDefaults(
  defineProps<{
    rows: IntegrationLogRow[]
    loading?: boolean
    /** 深链过来的 correlation_id：命中即高亮并强制展开 */
    highlight?: string | null
  }>(),
  { loading: false, highlight: null },
)

interface LogGroup {
  key: string
  correlationId: string
  rows: IntegrationLogRow[]
  latest: IntegrationLogRow
  system: IntegrationSystem
  operation: string
  retryMax: number
  totalMs: number
}

const SYSTEM_LABEL: Record<IntegrationSystem, string> = {
  JENKINS: 'Jenkins',
  GITLAB: 'GitLab',
  SONAR: 'Sonar',
  NEXUS: 'Nexus',
}

const expanded = ref<Record<string, boolean>>({})

const groups = computed<LogGroup[]>(() => {
  const map = new Map<string, IntegrationLogRow[]>()
  for (const row of props.rows) {
    const key = row.correlationId || `row-${row.id}`
    const bucket = map.get(key)
    if (bucket) bucket.push(row)
    else map.set(key, [row])
  }
  const result: LogGroup[] = []
  for (const [key, rows] of map) {
    const latest = rows[rows.length - 1]
    result.push({
      key,
      correlationId: latest.correlationId,
      rows,
      latest,
      system: latest.system,
      operation: latest.operation,
      retryMax: rows.reduce((max, row) => Math.max(max, row.retryCount ?? 0), 0),
      totalMs: rows.reduce((sum, row) => sum + (row.durationMs ?? 0), 0),
    })
  }
  return result
})

watch(
  () => props.rows,
  () => {
    // 新一页数据：默认展开"重试耗尽"和深链命中的聚合
    const next: Record<string, boolean> = {}
    for (const group of groups.value) {
      // 多次尝试的重试耗尽默认展开：失败原因必须在第一屏可见
      if (group.rows.length > 1 && group.latest.result === 'RETRY_EXHAUSTED') next[group.key] = true
      if (props.highlight && group.correlationId === props.highlight) next[group.key] = true
    }
    expanded.value = next
  },
  { immediate: true },
)

function stamp(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function duration(ms?: number | null): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

function summary(text?: string | null): string {
  if (!text) return '—'
  return text.length > 48 ? `${text.slice(0, 48)}…` : text
}

function summarize(group: LogGroup): string {
  if (group.rows.length === 1) return `${group.retryMax}/5`
  return `${group.rows.length} 次 · ${group.retryMax}/5`
}

function expandKey(group: LogGroup): string {
  return `log-attempts-${group.key.replace(/[^a-zA-Z0-9_-]/g, '-')}`
}

function toggle(group: LogGroup): void {
  expanded.value = { ...expanded.value, [group.key]: !expanded.value[group.key] }
}

function fallbackCopy(value: string): boolean {
  try {
    const area = document.createElement('textarea')
    area.value = value
    area.setAttribute('readonly', 'readonly')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    area.remove()
    return ok
  } catch {
    return false
  }
}

async function copyCorrelation(value: string): Promise<void> {
  try {
    if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(value)
    else if (!fallbackCopy(value)) throw new Error('copy unavailable')
    ElMessage.success('已复制 correlation_id')
  } catch {
    ElMessage.warning('浏览器不允许读取剪贴板，请手动选择复制')
  }
}
</script>

<template>
  <div class="logtable" :class="{ 'logtable--loading': loading }">
    <div class="logtable__head" role="row">
      <span role="columnheader">时间</span>
      <span role="columnheader">类型</span>
      <span role="columnheader">操作</span>
      <span role="columnheader">结果</span>
      <span role="columnheader" class="logtable__num">HTTP</span>
      <span role="columnheader" class="logtable__num">耗时</span>
      <span role="columnheader" class="logtable__num">重试</span>
      <span role="columnheader">请求摘要</span>
      <span role="columnheader">关联</span>
    </div>

    <div class="logtable__body" role="rowgroup">
      <div
        v-for="group in groups"
        :key="group.key"
        class="logtable__group"
        :class="{ 'logtable__group--hit': highlight && group.correlationId === highlight }"
      >
        <div class="logtable__row logtable__row--parent" role="row">
          <span class="logtable__cell logtable__time" role="cell">{{ stamp(group.latest.createdAt) }}</span>
          <span class="logtable__cell" role="cell">
            <span class="logtable__system">{{ SYSTEM_LABEL[group.system] || group.system }}</span>
          </span>
          <span class="logtable__cell" role="cell">
            <button
              v-if="group.rows.length > 1"
              type="button"
              class="logtable__expand"
              :aria-expanded="!!expanded[group.key]"
              :aria-controls="expandKey(group)"
              :aria-label="`${expanded[group.key] ? '收起' : '展开'} ${group.operation} 的 ${group.rows.length} 次尝试`"
              @click="toggle(group)"
            >
              <svg viewBox="0 0 10 10" width="9" height="9" aria-hidden="true" focusable="false">
                <path d="M3 1.5 6.8 5 3 8.5" fill="none" stroke="currentColor" stroke-width="1.3" />
              </svg>
            </button>
            <span class="logtable__op">
              <span class="logtable__op-name" :title="group.operation">{{ group.operation }}</span>
              <span v-if="group.rows.length > 1" class="logtable__op-meta">
                {{ group.rows.length }} 次尝试 · 总耗时 {{ duration(group.totalMs) }}
              </span>
            </span>
          </span>
          <span class="logtable__cell" role="cell">
            <StatusPill kind="sync" :value="group.latest.result" size="sm" />
            <span v-if="group.latest.errorCode" class="logtable__errcode">{{ group.latest.errorCode }}</span>
          </span>
          <span class="logtable__cell logtable__num" role="cell">{{ group.latest.httpStatus ?? '—' }}</span>
          <span class="logtable__cell logtable__num logtable__num--wide" role="cell">
            {{ group.rows.length > 1 ? duration(group.totalMs) : duration(group.latest.durationMs) }}
          </span>
          <span class="logtable__cell logtable__num logtable__num--wide" role="cell">{{ summarize(group) }}</span>
          <span class="logtable__cell logtable__summary" role="cell">
            <span :title="group.latest.requestSummary || ''">{{ summary(group.latest.requestSummary) }}</span>
          </span>
          <span class="logtable__cell" role="cell">
            <button
              type="button"
              class="logtable__corr t-code"
              :title="`correlation_id：${group.correlationId}（点击复制）`"
              @click="copyCorrelation(group.correlationId)"
            >
              {{ group.correlationId.slice(0, 8) }}
            </button>
          </span>
        </div>

        <div
          v-if="group.rows.length > 1 && expanded[group.key]"
          :id="expandKey(group)"
          class="logtable__attempts"
          role="group"
          :aria-label="`${group.operation} 的尝试明细`"
        >
          <div v-for="(attempt, index) in group.rows" :key="attempt.id" class="logtable__row logtable__row--child" role="row">
            <span class="logtable__cell logtable__time" role="cell">{{ stamp(attempt.createdAt) }}</span>
            <span class="logtable__cell" role="cell">
              <span class="logtable__attempt">#{{ index + 1 }}</span>
            </span>
            <span class="logtable__cell logtable__op" role="cell">
              <span class="logtable__op-name" :title="`${attempt.method || ''} ${attempt.targetUrl || ''}`.trim()">
                {{ attempt.method || '—' }} {{ attempt.targetUrl ? attempt.targetUrl : '' }}
              </span>
            </span>
            <span class="logtable__cell" role="cell">
              <StatusPill kind="sync" :value="attempt.result" size="sm" />
            </span>
            <span class="logtable__cell logtable__num" role="cell">{{ attempt.httpStatus ?? '—' }}</span>
            <span class="logtable__cell logtable__num logtable__num--wide" role="cell">{{ duration(attempt.durationMs) }}</span>
            <span class="logtable__cell logtable__num logtable__num--wide" role="cell">{{ attempt.retryCount }}/5</span>
            <span class="logtable__cell logtable__summary" role="cell">
              <span :title="attempt.responseSummary || attempt.requestSummary || ''">
                {{ summary(attempt.responseSummary || attempt.requestSummary) }}
              </span>
            </span>
            <span class="logtable__cell logtable__corr-text" role="cell">{{ attempt.errorCode || '' }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.logtable {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
}

.logtable--loading {
  opacity: 0.72;
  transition: opacity var(--t-color) var(--ease);
}

.logtable__head,
.logtable__row {
  display: grid;
  grid-template-columns: 140px 88px 160px 96px 64px 80px 56px minmax(200px, 1fr) 88px;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
}

.logtable__head {
  height: 36px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-table-head);
  color: var(--text-muted-aa);
  font-size: 12px;
}

.logtable__row {
  min-height: 36px;
  border-bottom: 1px solid var(--divider);
  font-size: 12px;
  transition: background var(--t-color) var(--ease);
}

.logtable__row:hover {
  background: var(--bg-hover);
}

.logtable__group:last-child .logtable__row:last-child {
  border-bottom: 0;
}

.logtable__group--hit > .logtable__row--parent {
  background: var(--bg-selected);
}

.logtable__row--parent {
  background: var(--bg-table-head);
}

.logtable__row--parent:hover {
  background: var(--bg-hover);
}

.logtable__cell {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
}

.logtable__time {
  color: var(--text-body);
  font-variant-numeric: tabular-nums;
}

.logtable__system {
  color: var(--text-body);
}

.logtable__op {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.logtable__op-name {
  overflow: hidden;
  color: var(--text-body);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.logtable__op-meta {
  color: var(--text-muted-aa);
  font-size: 11px;
}

.logtable__expand {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  flex: none;
  padding: 0;
  border: 0;
  border-radius: var(--r-cell);
  background: none;
  color: var(--text-secondary);
  cursor: pointer;
  transition: transform var(--t-expand) var(--ease);
}

.logtable__expand[aria-expanded='true'] {
  transform: rotate(90deg);
}

.logtable__expand:hover {
  background: var(--bg-hover);
  color: var(--color-primary);
}

.logtable__expand:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}

.logtable__errcode {
  overflow: hidden;
  color: var(--danger-ink);
  font-family: var(--font-mono);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.logtable__num {
  justify-content: flex-end;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.logtable__num--wide {
  min-width: 0;
}

.logtable__summary {
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
}

.logtable__summary span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.logtable__corr {
  padding: 0 4px;
  border: 1px solid transparent;
  border-radius: var(--r-cell);
  background: none;
  color: var(--text-muted-aa);
  font-family: var(--font-mono);
  font-size: 11px;
  cursor: pointer;
}

.logtable__corr:hover {
  border-color: var(--border);
  background: var(--bg-card);
  color: var(--color-primary);
}

.logtable__corr:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}

.logtable__attempts {
  background: var(--bg-page);
}

.logtable__row--child {
  padding-left: 28px;
  background: var(--bg-page);
}

.logtable__row--child:hover {
  background: var(--bg-hover);
}

.logtable__attempt {
  color: var(--text-weak);
  font-variant-numeric: tabular-nums;
}

.logtable__corr-text {
  color: var(--danger-ink);
  font-family: var(--font-mono);
  font-size: 10px;
}

@media (max-width: 1200px) {
  .logtable__head,
  .logtable__row {
    grid-template-columns: 120px 76px 140px 88px 56px 70px 52px minmax(160px, 1fr) 76px;
  }
}
</style>
