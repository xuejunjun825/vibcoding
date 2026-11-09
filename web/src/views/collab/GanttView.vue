<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/client'
import {
  fetchGantt,
  parseRescheduleConflict,
  reschedule,
  type GanttBoard,
  type GanttRow,
  type OutOfWindowDetail,
  type VersionConflictDetail,
} from '@/api/collab'
import GanttTimeline from '@/components/collab/GanttTimeline.vue'
import RescheduleDialog from '@/components/collab/RescheduleDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { usePermission } from '@/composables/usePermission'
import { useGanttPrefs } from '@/composables/useCollabPrefs'
import {
  VIEWS,
  ZOOMS,
  clampRangeStart,
  defaultWindow,
  diffDays,
  todayISO,
} from '@/utils/ganttScale'
import '@/views/deliver/shared.css'

/**
 * CTeam 甘特（DESIGN G1–G4 · AC-01/02/03/04）。
 *
 * 三条铁律：
 * 1. 改期只有一个写入口（`PATCH /gantt/rows/{type}/{id}`），本页不散写日期；
 * 2. 条上的告警、容量阈值都来自服务端，前端不推断「几天算临期」；
 * 3. 任何非成功回执都不许静默回退——要么显式红条「结果未知」，要么 409 弹窗解释原因。
 */
const router = useRouter()
const { can } = usePermission()
const canEdit = computed(() => can('GANTT_EDIT'))

const prefs = useGanttPrefs()
const view = prefs.view
const zoom = prefs.zoom
const collapsed = prefs.collapsed

const board = ref<GanttBoard | null>(null)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)

const windowRange = ref(defaultWindow(prefs.zoom.value))
const selectedKey = ref('')
const pendingKey = ref('')
const flashKey = ref('')
const submitting = ref(false)

const notice = ref<{ tone: 'success' | 'danger' | 'warn'; text: string } | null>(null)
const versionConflict = ref<(VersionConflictDetail & { rowKey: string; rowTitle: string }) | null>(null)

const dialog = ref({
  open: false,
  row: null as GanttRow | null,
  start: '',
  end: '',
  detail: null as OutOfWindowDetail | null,
})

let noticeTimer: ReturnType<typeof setTimeout> | null = null
let flashTimer: ReturnType<typeof setTimeout> | null = null
let reloadTimer: ReturnType<typeof setTimeout> | null = null
let seq = 0

const rows = computed(() => board.value?.rows ?? [])
const total = computed(() => board.value?.total ?? rows.value.length)
const capacity = computed(() => ((board.value?.capacityTenths ?? 0) / 10).toFixed(1))
const rangeText = computed(() => `${board.value?.rangeStart ?? windowRange.value.from} → ${board.value?.rangeEnd ?? windowRange.value.to}`)

const viewLabel = computed(() => VIEWS.find((item) => item.key === view.value)?.label ?? '版本')
const emptyHint = computed(() => VIEWS.find((item) => item.key === view.value)?.empty ?? '当前视图没有可排期的条目')

function clearTimers(): void {
  if (noticeTimer) {
    clearTimeout(noticeTimer)
    noticeTimer = null
  }
  if (flashTimer) {
    clearTimeout(flashTimer)
    flashTimer = null
  }
  if (reloadTimer) {
    clearTimeout(reloadTimer)
    reloadTimer = null
  }
}

onBeforeUnmount(clearTimers)

function showNotice(tone: 'success' | 'danger' | 'warn', text: string, autoHide = 0): void {
  notice.value = { tone, text }
  if (noticeTimer) clearTimeout(noticeTimer)
  if (autoHide > 0) {
    noticeTimer = setTimeout(() => {
      notice.value = null
    }, autoHide)
  }
}

/** 只把「已展开的需求 id」传给后端（GanttQuery.parseExpanded 只认需求 id） */
function expandedParam(): string | undefined {
  if (!collapsed.value.length) return undefined
  const ids = rows.value
    .filter((row) => row.type === 'REQUIREMENT' && !collapsed.value.includes(`${row.type}:${row.id}`))
    .map((row) => row.id)
  // 有折叠但一条需求都没展开 → 空串，语义是「全部收起」
  return ids.join(',')
}

async function load(silent = false): Promise<void> {
  const current = ++seq
  if (!silent) loading.value = true
  error.value = null
  try {
    const next = await fetchGantt({
      view: view.value,
      zoom: zoom.value,
      from: windowRange.value.from,
      to: windowRange.value.to,
      expanded: expandedParam(),
      limit: 300,
    })
    if (current !== seq) return
    board.value = next
    windowRange.value = { from: next.rangeStart, to: next.rangeEnd }
    if (selectedKey.value && !next.rows.some((row) => `${row.type}:${row.id}` === selectedKey.value)) {
      selectedKey.value = ''
    }
  } catch (err) {
    if (current !== seq) return
    const apiError = err instanceof ApiError ? err : null
    error.value = {
      message: apiError?.message ?? (err instanceof Error ? err.message : '甘特加载失败'),
      code: apiError?.code,
      status: apiError?.status,
    }
  } finally {
    if (current === seq) loading.value = false
  }
}

onMounted(() => {
  windowRange.value = clampRangeStart(windowRange.value.from, windowRange.value.to, todayISO())
  void load()
})

watch(
  [view, zoom],
  () => {
    windowRange.value = defaultWindow(zoom.value)
    selectedKey.value = ''
    versionConflict.value = null
    void load()
  },
)

function applyRange(): void {
  if (diffDays(windowRange.value.from, windowRange.value.to) < 0) {
    const swapped = { from: windowRange.value.to, to: windowRange.value.from }
    windowRange.value = swapped
    ElMessage.warning('起止日期已对调')
  }
  void load()
}

function backToToday(): void {
  windowRange.value = defaultWindow(zoom.value)
  void load()
}

function onRangePicker(value: [string, string] | null): void {
  if (!value) return
  windowRange.value = { from: value[0], to: value[1] }
  applyRange()
}

function toggle(row: GanttRow): void {
  prefs.toggle(`${row.type}:${row.id}`)
  void load(true)
}

function select(row: GanttRow): void {
  selectedKey.value = `${row.type}:${row.id}`
}

function openRow(row: GanttRow): void {
  selectedKey.value = `${row.type}:${row.id}`
  const name = row.type === 'RELEASE' ? 'release-detail' : row.type === 'SPRINT' ? 'iteration-detail' : 'requirement-detail'
  const idParam = row.type === 'RELEASE' ? { releaseId: row.id } : row.type === 'SPRINT' ? { iterationId: row.id } : { id: row.id }
  const target = router.hasRoute(name) ? { name, params: idParam } : null
  if (target) void router.push(target)
}

function patchRow(result: { id: number; type: string; planStart: string; planEnd: string; version: string }): void {
  const row = rows.value.find((item) => item.id === result.id && item.type === result.type)
  if (!row) return
  row.planStart = result.planStart
  row.planEnd = result.planEnd
  row.version = result.version
}

function markFlash(row: GanttRow): void {
  flashKey.value = `${row.type}:${row.id}`
  if (flashTimer) clearTimeout(flashTimer)
  flashTimer = setTimeout(() => {
    flashKey.value = ''
  }, 700)
}

async function submitReschedule(
  row: GanttRow,
  start: string,
  end: string,
  confirmToken?: string | null,
): Promise<void> {
  pendingKey.value = `${row.type}:${row.id}`
  submitting.value = true
  versionConflict.value = null
  try {
    const result = await reschedule(row.type, row.id, {
      planStart: start,
      planEnd: end,
      version: row.version ?? null,
      confirmToken: confirmToken ?? null,
    })
    patchRow(result)
    markFlash(row)
    showNotice('success', `已保存 ${row.code ?? ''} · 已写入审计 GANTT_RESCHEDULE${result.auditId ? ` #${result.auditId}` : ''}`.trim(), 1600)
    if (reloadTimer) clearTimeout(reloadTimer)
    reloadTimer = setTimeout(() => {
      void load(true)
    }, 1200)
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    if (apiError?.status === 409) {
      const conflict = parseRescheduleConflict(apiError.detail)
      if (conflict?.kind === 'OUT_OF_PARENT_WINDOW') {
        dialog.value = { open: true, row, start, end, detail: conflict }
        return
      }
      if (conflict?.kind === 'VERSION_CONFLICT') {
        versionConflict.value = {
          ...conflict,
          rowKey: `${row.type}:${row.id}`,
          rowTitle: row.title,
        }
        return
      }
      showNotice('danger', `改期被拒绝：${apiError.message || '409 冲突'}（未保存，请刷新后重试）`)
      return
    }
    if (!apiError || apiError.isNetworkError || apiError.status >= 500 || apiError.status === 0) {
      showNotice('danger', '结果未知，请刷新后确认：请求可能已到达服务端，刷新前不要重复拖动同一条。')
      return
    }
    showNotice('danger', `未保存：${apiError.message}`)
  } finally {
    submitting.value = false
    pendingKey.value = ''
  }
}

function onCommit(payload: { row: GanttRow; start: string; end: string }): void {
  if (!canEdit.value) {
    showNotice('warn', '只读：需要 GANTT_EDIT 权限才能改期')
    return
  }
  if (submitting.value) return
  notice.value = null
  void submitReschedule(payload.row, payload.start, payload.end)
}

async function confirmOutOfWindow(): Promise<void> {
  const current = dialog.value
  if (!current.row || !current.detail) return
  const token = current.detail.confirmToken
  closeDialog(false)
  await submitReschedule(current.row, current.start, current.end, token)
}

function closeDialog(open: boolean): void {
  if (open) return
  if (submitting.value) return
  dialog.value = { open: false, row: null, start: '', end: '', detail: null }
}

function refreshToken(): void {
  const row = dialog.value.row
  if (!row) return
  dialog.value = { open: false, row: null, start: '', end: '', detail: null }
  showNotice('warn', '确认码已失效：请重新拖动该条，服务端会签发新的确认码')
}

function dismissConflict(): void {
  versionConflict.value = null
  void load(true)
}

function retryAfterConflict(): void {
  versionConflict.value = null
  void load()
}

const parentWindow = computed(() => dialog.value.detail?.parentWindow ?? { start: null, end: null })
</script>

<template>
  <div class="dl-page gantt-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">甘特</h1>
        <span class="dl-meta">
          {{ viewLabel }}视图 · 共 {{ total }} 行 · 并发上限 {{ capacity }} 人天/天 · 窗口 {{ rangeText }}
        </span>
      </div>
      <div class="dl-head__actions">
        <el-radio-group v-model="view" size="small" aria-label="甘特视图">
          <el-radio-button v-for="item in VIEWS" :key="item.key" :value="item.key">{{ item.label }}</el-radio-button>
        </el-radio-group>
        <el-radio-group v-model="zoom" size="small" aria-label="时间缩放">
          <el-radio-button v-for="item in ZOOMS" :key="item.key" :value="item.key">{{ item.label }}</el-radio-button>
        </el-radio-group>
        <el-button size="small" :loading="loading" @click="load()">刷新</el-button>
      </div>
    </header>

    <p class="dl-hint">
      实心条是计划（承诺），下方 6px 浅条是已发生的实际期——没有审计就不画实际条，也不用「今天」补。
      拖拽左端/主体改期、拖右端改结束；←/→ 平移 1 天，Shift + ←/→ 平移 7 天，Enter 打开详情。
    </p>

    <div class="dl-filters">
      <el-date-picker
        :model-value="[windowRange.from, windowRange.to]"
        type="daterange"
        size="small"
        unlink-panels
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        range-separator="→"
        style="width: 260px"
        @update:model-value="onRangePicker"
      />
      <el-button size="small" text @click="backToToday">回到今天</el-button>
      <div class="dl-filters__spacer" />
      <span class="dl-meta">行内「⋯」打开条目详情 · 告警由服务端计算</span>
    </div>

    <div v-if="!canEdit" class="gantt-readonly" role="status">
      只读：需要 GANTT_EDIT 权限才能改期；可以查看与下钻。
    </div>

    <div v-if="notice" class="gantt-notice" :class="`gantt-notice--${notice.tone}`" role="alert">
      <span>{{ notice.text }}</span>
      <button type="button" class="gantt-notice__close" aria-label="关闭提示" @click="notice = null">×</button>
    </div>

    <div v-if="versionConflict" class="gantt-conflict" role="alert">
      <span class="gantt-conflict__text">
        「{{ versionConflict.rowTitle }}」已被他人改过（版本 {{ versionConflict.currentVersion ?? '未知' }}），本次改动未保存。
      </span>
      <span class="gantt-conflict__actions">
        <button type="button" class="gantt-conflict__btn" @click="retryAfterConflict">刷新并重试</button>
        <button type="button" class="gantt-conflict__btn gantt-conflict__btn--ghost" @click="dismissConflict">放弃</button>
      </span>
    </div>

    <div v-if="board?.truncated" class="gantt-truncated" role="status">
      已按 300 行截断（共 {{ board.total }} 行）：收起部分需求或缩小时间窗口可看全。
    </div>

    <div v-if="board?.boardWarnings?.length" class="gantt-boardwarn" role="status">
      <span v-for="(warning, index) in board.boardWarnings" :key="index" class="gantt-boardwarn__item">
        {{ warning.message }}
      </span>
    </div>

    <ErrorState
      v-if="error"
      :message="error.message"
      :code="error.code"
      :status="error.status"
      @retry="load()"
    />

    <GanttTimeline
      v-else
      :rows="rows"
      :zoom="zoom"
      :range-start="windowRange.from"
      :range-end="windowRange.to"
      :selected-key="selectedKey"
      :pending-key="pendingKey"
      :flash-key="flashKey"
      :collapsed="collapsed"
      :read-only="!canEdit"
      :loading="loading"
      :truncated="board?.truncated ?? false"
      @toggle="toggle"
      @select="select"
      @open="openRow"
      @commit="onCommit"
    >
      <template #empty>
        <EmptyState
          v-if="!loading"
          :title="emptyHint"
          description="换个视图或把时间窗口拉宽；已排期的需求会以计划条出现。"
          icon="chart"
          compact
        />
      </template>
    </GanttTimeline>

    <RescheduleDialog
      :model-value="dialog.open"
      :row-title="dialog.row?.title ?? ''"
      :plan-start="dialog.start"
      :plan-end="dialog.end"
      :parent-window="parentWindow"
      :violating-days="dialog.detail?.violatingDays ?? 0"
      :expires-at="dialog.detail?.expiresAt ?? ''"
      :submitting="submitting"
      @update:model-value="closeDialog"
      @confirm="confirmOutOfWindow"
      @refresh-token="refreshToken"
    />
  </div>
</template>

<style scoped>
.gantt-page {
  --gantt-plan-fill: var(--color-primary);
  --gantt-actual-fill: #9dc0f2;
  --gantt-weekend: #fafbfc;
  --gantt-grid: #f2f3f5;
  --gantt-today: #2b6fe5;
}
.gantt-readonly {
  margin: 0 0 12px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--warning-ink);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
}
.gantt-notice {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 32px;
  margin: 0 0 12px;
  padding: 0 12px;
  border-radius: var(--r-ctl);
  font-size: 12px;
  animation: gantt-notice-in 160ms var(--ease);
}
@keyframes gantt-notice-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.gantt-notice--success {
  background: #e8f7ec;
  border: 1px solid #bfe3c8;
  color: var(--success-ink);
}
.gantt-notice--danger {
  background: var(--color-danger-bg);
  border: 1px solid #f7d4d5;
  color: var(--danger-ink);
}
.gantt-notice--warn {
  background: var(--color-warning-bg);
  border: 1px solid #e9c989;
  color: var(--warning-ink);
}
.gantt-notice__close {
  margin-left: auto;
  border: 0;
  background: none;
  font-size: 15px;
  line-height: 1;
  color: inherit;
  cursor: pointer;
  opacity: 0.7;
}
.gantt-notice__close:hover {
  opacity: 1;
}
.gantt-conflict {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 40px;
  margin: 0 0 12px;
  padding: 0 12px;
  border: 1px solid #e9c989;
  border-left: 3px solid #d99a1a;
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  font-size: 12px;
  color: var(--warning-ink);
}
.gantt-conflict__text {
  flex: 1 1 auto;
}
.gantt-conflict__actions {
  display: flex;
  gap: 8px;
}
.gantt-conflict__btn {
  height: 26px;
  padding: 0 10px;
  border: 1px solid #d99a1a;
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  color: var(--warning-ink);
  font-size: 12px;
  cursor: pointer;
}
.gantt-conflict__btn:hover {
  background: #fff;
}
.gantt-conflict__btn--ghost {
  border-color: var(--border);
  color: var(--text-secondary);
}
.gantt-truncated {
  margin: 0 0 12px;
  padding: 6px 12px;
  border-radius: var(--r-ctl);
  background: var(--bg-table-head);
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gantt-boardwarn {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 0 12px;
}
.gantt-boardwarn__item {
  padding: 4px 10px;
  border-radius: var(--r-pill);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 11px;
}
</style>
