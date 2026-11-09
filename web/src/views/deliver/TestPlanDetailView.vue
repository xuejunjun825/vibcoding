<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getTestPlan } from '@/api/deliver'
import { assignmentApi, planApi, reportApi, roundApi } from '@/api/quality'
import { listUsers, type UserRow } from '@/api/admin'
import { listReleases } from '@/api/req'
import { usePermission } from '@/composables/usePermission'
import { DANGER_ACTIONS, useDictStore } from '@/stores/dict'
import type {
  AssignmentRow,
  AssignmentView,
  PlanChangeLogRow,
  PlanDetail,
  ReportRow,
  RoundDetail,
  RoundView,
  TestPlanDetail,
  ReleaseRow,
} from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LifecycleStepper from '@/components/quality/LifecycleStepper.vue'
import RoundSwitcher from '@/components/quality/RoundSwitcher.vue'
import AssignmentSummary from '@/components/quality/AssignmentSummary.vue'
import { formatDate, formatDateTime, formatNumber, formatPercent } from '@/utils/format'
import '@/views/deliver/shared.css'

/**
 * 测试计划详情（DESIGN Q4 / AC-1、AC-2、AC-3）。
 *
 * 三条硬约束：
 * 1. 生命周期只有动作按钮，没有状态下拉；按钮集合 = 服务端 `allowedNext`（前端不判断合法性）。
 * 2. 409 一律常驻冲突卡（role="alert" 并聚焦），渲染 `unmet[]` 与 `allowedNext`，不改本地状态。
 * 3. 轮次切换只改 `?round=`，所有数据由服务端按 roundId 返回；比率不在此文件里做第二次除法。
 */
interface StepperStep {
  key: string
  label: string
  state: 'done' | 'current' | 'pending' | 'blocked'
}

interface UnmetItem {
  key: string
  label: string
  actual: string
  expected: string
  fixHint: string
}

interface ConflictState {
  title: string
  code?: string
  message: string
  unmet: UnmetItem[]
  allowedNext: string[]
}

const TABS = [
  { key: 'overview', label: '概览' },
  { key: 'cases', label: '用例' },
  { key: 'rounds', label: '轮次' },
  { key: 'assignments', label: '分配' },
  { key: 'changes', label: '变更日志' },
  { key: 'reports', label: '报告' },
]

/** 展示用的阶梯（只决定“进度画到哪”，不决定任何动作是否可点） */
const LADDER = ['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'IN_PROGRESS', 'COMPLETED', 'CLOSED', 'ARCHIVED']

const CRITERIA_LABELS: Record<string, string> = {
  passRate: '通过率',
  coverage: '覆盖率',
  openP0P1: '未关闭 P0/P1 缺陷数',
  roundClosed: '轮次已关闭',
  executed: '已执行用例数',
  total: '计划用例数',
  notRun: '未执行用例数',
  failed: '失败用例数',
  blocked: '阻塞用例数',
  strategyActive: '策略已生效',
}

const route = useRoute()
const router = useRouter()
const dict = useDictStore()
const { can } = usePermission()

const planId = computed(() => Number(route.params.id))
const tab = computed(() => {
  const value = String(route.query.tab ?? 'overview')
  return TABS.some((item) => item.key === value) ? value : 'overview'
})
const roundId = computed(() => {
  const raw = route.query.round
  return raw ? Number(raw) : null
})

const plan = ref<PlanDetail | null>(null)
const m5 = ref<TestPlanDetail | null>(null)
const rounds = ref<RoundView[]>([])
const roundDetail = ref<RoundDetail | null>(null)
const assignments = ref<AssignmentView[]>([])
const changes = ref<PlanChangeLogRow[]>([])
const changeTotal = ref(0)
const reports = ref<ReportRow[]>([])
const users = ref<UserRow[]>([])
const releases = ref<ReleaseRow[]>([])
const loading = ref(false)
const tabLoading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const conflict = ref<ConflictState | null>(null)
const conflictEl = ref<HTMLElement | null>(null)
const casesMode = ref<'round' | 'total'>('round')

const status = computed(() => String(plan.value?.status ?? '').toUpperCase())
const allowedNext = computed(() => plan.value?.allowedNext ?? [])
const summary = computed(() => plan.value?.summary ?? null)
const exitItems = computed(() => plan.value?.criteria?.exitItems ?? [])
const activeRound = computed(() => rounds.value.find((round) => round.active) ?? null)
const selectedRound = computed(() => rounds.value.find((round) => round.id === roundId.value) ?? null)
const roundLocked = computed(() => selectedRound.value?.status === 'CLOSED')
const releaseName = computed(
  () => releases.value.find((item) => item.id === plan.value?.releaseId)?.name ?? '—',
)

const steps = computed<StepperStep[]>(() => {
  const index = LADDER.indexOf(status.value)
  return LADDER.map((key, position) => ({
    key,
    label: dict.label('plan', key),
    state:
      index < 0
        ? 'pending'
        : position < index
          ? 'done'
          : position === index
            ? 'current'
            : 'pending',
  }))
})

const conclusionTone = computed(() => {
  if (conflict.value) return 'danger'
  if (status.value === 'COMPLETED' || status.value === 'CLOSED' || status.value === 'ARCHIVED') return 'success'
  if (status.value === 'IN_PROGRESS') return 'primary'
  return 'neutral'
})

const conclusionText = computed(() => {
  if (!plan.value) return ''
  const rate = formatPercent(summary.value?.passRate ?? null)
  const threshold = exitItems.value.find((item) => item.key === 'passRate')?.value
  const parts = [
    dict.label('plan', status.value) || status.value,
    `准出条件 ${exitItems.value.length} 项`,
    threshold === undefined ? `通过率 ${rate}` : `通过率 ${rate}（阈值 ${threshold}%）`,
  ]
  if (plan.value.rounds?.activeRoundNo) parts.push(`第 ${plan.value.rounds.activeRoundNo} 轮进行中`)
  if (plan.value.rounds?.conclusion) parts.push(`上轮结论 ${dict.label('planRound', plan.value.rounds.conclusion)}`)
  return parts.join(' · ')
})

function actionPermission(action: string): string {
  if (action === 'approve' || action === 'reject') return 'plan:approve'
  if (action === 'start' || action === 'complete') return 'plan:execute'
  if (action === 'close' || action === 'archive') return 'plan:manage'
  return 'plan:edit'
}

function canAction(action: string): boolean {
  return can(actionPermission(action))
}

function criteriaActual(key: string): string {
  const counts = summary.value
  if (!counts) return '—'
  switch (key) {
    case 'passRate':
      return formatPercent(counts.passRate ?? null)
    case 'coverage':
      return formatPercent(counts.coverage ?? null)
    case 'total':
      return formatNumber(counts.total)
    case 'executed':
      return formatNumber(counts.executed)
    case 'notRun':
      return formatNumber(counts.notRun)
    case 'failed':
      return formatNumber(counts.failed)
    case 'blocked':
      return formatNumber(counts.blocked)
    default:
      return '—'
  }
}

function unmetFor(key: string): UnmetItem | undefined {
  return conflict.value?.unmet.find((item) => item.key === key)
}

function criteriaLabel(key: string): string {
  return CRITERIA_LABELS[key] ?? key
}

function switchTab(key: string): void {
  void router.replace({ query: { ...route.query, tab: key } })
}

function selectRound(id: number): void {
  void router.replace({ query: { ...route.query, round: id } })
}

/* ---------------- 加载 ---------------- */

function toConflict(title: string, err: unknown): void {
  const typed = err as { code?: string; message?: string; detail?: unknown; status?: number }
  const detail = (typed.detail ?? {}) as Record<string, unknown>
  const rawUnmet = Array.isArray(detail.unmet) ? (detail.unmet as UnmetItem[]) : []
  conflict.value = {
    title,
    code: typed.code,
    message: typed.message || '操作未完成',
    unmet: rawUnmet.map((item) => ({
      key: String(item.key ?? ''),
      label: item.label ?? criteriaLabel(String(item.key ?? '')),
      actual: item.actual ?? '—',
      expected: item.expected ?? '—',
      fixHint: item.fixHint ?? '',
    })),
    allowedNext: allowedNext.value,
  }
  void nextTick(() => conflictEl.value?.focus())
}

async function loadPlan(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    plan.value = await planApi.detail(planId.value)
  } catch (err) {
    error.value = {
      message: (err as Error).message || '计划加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

async function loadRounds(): Promise<void> {
  try {
    rounds.value = await roundApi.list(planId.value)
  } catch {
    rounds.value = []
  }
}

async function loadTab(): Promise<void> {
  tabLoading.value = true
  try {
    if (tab.value === 'overview' || tab.value === 'changes') {
      const page = await planApi.changeLogs(planId.value, { page: 1, size: tab.value === 'changes' ? 20 : 5 })
      changes.value = page.items ?? []
      changeTotal.value = page.total ?? 0
    }
    if (tab.value === 'cases') {
      if (casesMode.value === 'total') {
        m5.value = await getTestPlan(planId.value)
      } else if (roundId.value) {
        roundDetail.value = await roundApi.detail(planId.value, roundId.value)
      }
    }
    if (tab.value === 'assignments') {
      assignments.value = await assignmentApi.list(planId.value, roundId.value)
    }
    if (tab.value === 'reports') {
      const page = await reportApi.page({ planId: planId.value, page: 1, size: 20 })
      reports.value = page.items ?? []
    }
  } catch (err) {
    toConflict('数据加载失败', err)
  } finally {
    tabLoading.value = false
  }
}

async function loadOptions(): Promise<void> {
  try {
    releases.value = (await listReleases({ page: 1, size: 100 })).items ?? []
  } catch {
    releases.value = []
  }
  if (!can('plan:assign')) return
  try {
    users.value = (await listUsers({ page: 1, size: 200 })).items ?? []
  } catch {
    users.value = []
  }
}

async function refreshAll(): Promise<void> {
  await loadPlan()
  await loadRounds()
  await loadTab()
}

/* ---------------- 动作（唯一来源 allowedNext） ---------------- */

const actionDialog = reactive({
  visible: false,
  action: '',
  reason: '',
  comment: '',
  running: false,
})

const ACTION_NEEDS_REASON = new Set(['reject'])
const ACTION_NEEDS_COMMENT_ONLY = new Set(['approve'])

function runAction(action: string): void {
  if (DANGER_ACTIONS.has(action) || ACTION_NEEDS_REASON.has(action)) {
    actionDialog.action = action
    actionDialog.reason = ''
    actionDialog.comment = ''
    actionDialog.visible = true
    return
  }
  void submitAction(action, {})
}

async function submitAction(action: string, payload: Record<string, unknown>): Promise<void> {
  try {
    actionDialog.running = true
    plan.value = await planApi.action(planId.value, action, payload)
    conflict.value = null
    actionDialog.visible = false
    ElMessage.success(`${dict.actionLabel(action, 'plan')}已完成`)
    await Promise.all([loadRounds(), loadTab()])
  } catch (err) {
    actionDialog.visible = false
    toConflict(`${dict.actionLabel(action, 'plan')}未完成`, err)
  } finally {
    actionDialog.running = false
  }
}

function confirmAction(): void {
  const action = actionDialog.action
  const payload: Record<string, unknown> = {}
  if (ACTION_NEEDS_REASON.has(action)) {
    if (actionDialog.comment.trim().length === 0) {
      ElMessage.warning('驳回必须填写原因')
      return
    }
    payload.comment = actionDialog.comment.trim()
  } else if (ACTION_NEEDS_COMMENT_ONLY.has(action)) {
    payload.comment = actionDialog.comment.trim() || undefined
  } else if (action === 'close') {
    payload.reason = actionDialog.reason.trim() || undefined
  }
  void submitAction(action, payload)
}

/* ---------------- 轮次 ---------------- */

const roundDialog = reactive({
  visible: false,
  mode: 'open' as 'open' | 'close',
  roundId: 0,
  roundType: 'FEATURE',
  name: '',
  scopeMode: 'CURRENT',
  note: '',
  conclusion: 'PASS',
  running: false,
  unexecuted: [] as Array<{ caseId?: number; caseCode?: string; title?: string; id?: number }>,
})

function openRoundDialog(): void {
  roundDialog.mode = 'open'
  roundDialog.roundType = rounds.value.length ? 'REGRESSION' : 'FEATURE'
  roundDialog.name = `R${rounds.value.length + 1}`
  roundDialog.scopeMode = 'CURRENT'
  roundDialog.note = ''
  roundDialog.unexecuted = []
  roundDialog.visible = true
}

function openRoundFromFailures(): void {
  roundDialog.mode = 'open'
  roundDialog.roundType = 'REGRESSION'
  roundDialog.name = `R${rounds.value.length + 1}`
  roundDialog.scopeMode = 'PREVIOUS_FAILED'
  roundDialog.note = '基于上一轮失败用例'
  roundDialog.unexecuted = []
  roundDialog.visible = true
}

function openCloseDialog(round: RoundView): void {
  roundDialog.mode = 'close'
  roundDialog.roundId = round.id
  roundDialog.conclusion = 'PASS'
  roundDialog.note = ''
  roundDialog.unexecuted = []
  roundDialog.visible = true
}

async function submitRound(): Promise<void> {
  roundDialog.running = true
  try {
    if (roundDialog.mode === 'open') {
      const created = await roundApi.open(planId.value, {
        roundType: roundDialog.roundType,
        name: roundDialog.name || undefined,
        scopeMode: roundDialog.scopeMode,
        note: roundDialog.note || undefined,
      } as unknown as Parameters<typeof roundApi.open>[1])
      roundDialog.visible = false
      await Promise.all([loadRounds(), loadPlan()])
      selectRound(created.id)
      ElMessage.success(`第 ${created.roundNo} 轮已开启`)
    } else {
      await roundApi.close(planId.value, roundDialog.roundId, {
        conclusion: roundDialog.conclusion,
        note: roundDialog.note || undefined,
      })
      roundDialog.visible = false
      await Promise.all([loadRounds(), loadPlan()])
      ElMessage.success('轮次已关闭')
    }
  } catch (err) {
    const typed = err as { detail?: Record<string, unknown>; code?: string }
    const ids = typed.detail?.unexecutedCaseIds
    if (Array.isArray(ids)) {
      roundDialog.unexecuted = (ids as number[]).map((id) => ({ caseId: id, caseCode: `#${id}` }))
    }
    toConflict(roundDialog.mode === 'open' ? '开启轮次未完成' : '关闭轮次未完成', err)
  } finally {
    roundDialog.running = false
  }
}

/* ---------------- 分配 ---------------- */

const assignmentSaving = ref(false)

async function saveAssignments(rows: AssignmentRow[]): Promise<void> {
  assignmentSaving.value = true
  try {
    await assignmentApi.replace(
      planId.value,
      { roundId: roundId.value ?? undefined, rows },
      roundId.value ?? undefined,
    )
    assignments.value = await assignmentApi.list(planId.value, roundId.value)
    await loadPlan()
    conflict.value = null
    ElMessage.success('分配已保存')
  } catch (err) {
    toConflict('分配未保存', err)
  } finally {
    assignmentSaving.value = false
  }
}

/* ---------------- 报告 ---------------- */

const reportGenerating = ref(false)

async function generateReport(): Promise<void> {
  reportGenerating.value = true
  try {
    const created = await reportApi.generate({ scope: 'PLAN', planId: planId.value })
    ElMessage.success('报告版本已生成')
    void router.push({ name: 'test-report-detail', params: { id: created.report.id } })
  } catch (err) {
    toConflict('生成报告未完成', err)
  } finally {
    reportGenerating.value = false
  }
}

/* ---------------- 概览字段 ---------------- */

watch(
  () => [tab.value, roundId.value, casesMode.value],
  () => {
    if (!plan.value) return
    if (tab.value === 'cases' && casesMode.value === 'round' && !roundId.value) {
      const fallback = activeRound.value ?? rounds.value[0]
      if (fallback) {
        selectRound(fallback.id)
        return
      }
    }
    void loadTab()
  },
)

watch(
  () => route.params.id,
  () => {
    void refreshAll()
  },
)

onMounted(async () => {
  await loadOptions()
  await refreshAll()
})
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <button type="button" class="dl-head__back" @click="router.push({ name: 'test-plans' })">← 测试计划</button>
        <div class="detail-title">
          <h1 class="t-page-title">{{ plan?.code || '计划' }} · {{ plan?.name || '' }}</h1>
          <StatusPill v-if="plan" kind="plan" :value="plan.status" />
          <span v-if="plan" class="detail-title__rev">r{{ plan.revision }}</span>
          <span v-if="plan?.strategy" class="detail-title__chip">策略 v{{ plan.strategy.versionNo }}</span>
          <span v-if="plan" class="detail-title__chip">{{ plan.gateMode === 'LOOSE' ? '宽松门禁' : '严格门禁' }}</span>
        </div>
      </div>
      <div class="dl-head__actions">
        <el-button
          v-for="action in allowedNext"
          :key="action"
          :type="DANGER_ACTIONS.has(action) ? 'danger' : action === 'start' || action === 'complete' ? 'primary' : 'default'"
          :plain="DANGER_ACTIONS.has(action)"
          :disabled="!canAction(action) || actionDialog.running"
          :title="canAction(action) ? '' : `缺少 ${actionPermission(action)} 权限`"
          @click="runAction(action)"
        >
          {{ dict.actionLabel(action, 'plan') }}
        </el-button>
        <el-button v-if="status === 'IN_PROGRESS'" @click="openRoundDialog">开新轮次</el-button>
        <el-button :disabled="!can('plan:edit')" @click="switchTab('changes')">变更</el-button>
      </div>
    </header>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="refreshAll" />

    <template v-else>
      <section class="surface surface--pad detail-lifecycle" v-loading="loading">
        <LifecycleStepper :steps="steps" />
      </section>

      <div
        v-if="conflict"
        ref="conflictEl"
        class="dl-conflict detail-conflict"
        role="alert"
        tabindex="-1"
      >
        <div class="detail-conflict__head">
          <strong>{{ conflict.title }}</strong>
          <span v-if="conflict.code" class="detail-conflict__code">{{ conflict.code }}</span>
          <span class="detail-conflict__spacer" />
          <button type="button" class="detail-conflict__close" aria-label="关闭提示" @click="conflict = null">×</button>
        </div>
        <p class="detail-conflict__msg">{{ conflict.message }}</p>
        <ul v-if="conflict.unmet.length" class="detail-conflict__list">
          <li v-for="item in conflict.unmet" :key="item.key" class="detail-conflict__item">
            <span class="detail-conflict__name">{{ item.label }}</span>
            <span class="detail-conflict__num">实际 {{ item.actual }}</span>
            <span class="detail-conflict__num">要求 {{ item.expected }}</span>
            <span class="detail-conflict__hint">{{ item.fixHint }}</span>
          </li>
        </ul>
        <div v-if="conflict.allowedNext.length" class="detail-conflict__next">
          <span>当前可执行：</span>
          <el-button
            v-for="action in conflict.allowedNext"
            :key="action"
            link
            type="primary"
            size="small"
            @click="runAction(action)"
          >
            {{ dict.actionLabel(action, 'plan') }}
          </el-button>
        </div>
      </div>

      <div v-if="plan" class="conclusion" :class="`conclusion--${conclusionTone}`" role="status">
        <span class="conclusion__text">{{ conclusionText }}</span>
        <span v-if="conflict" class="conclusion__flag">有未满足项，见上方清单</span>
      </div>

      <section class="surface">
        <div class="tabs" role="tablist" aria-label="计划详情">
          <button
            v-for="item in TABS"
            :key="item.key"
            type="button"
            role="tab"
            class="tabs__item"
            :class="{ 'is-active': tab === item.key }"
            :aria-selected="tab === item.key"
            @click="switchTab(item.key)"
          >
            {{ item.label }}
            <span v-if="item.key === 'cases' && summary" class="tabs__count">{{ formatNumber(summary.total) }}</span>
            <span v-if="item.key === 'rounds' && rounds.length" class="tabs__count">{{ rounds.length }}</span>
            <span v-if="item.key === 'changes' && changeTotal" class="tabs__count">{{ changeTotal }}</span>
            <span v-if="item.key === 'reports' && reports.length" class="tabs__count">{{ reports.length }}</span>
          </button>
        </div>

        <div v-loading="tabLoading" class="tab-body">
          <!-- 概览 -->
          <div v-if="tab === 'overview' && plan" class="overview">
            <div class="overview__fields">
              <div class="dl-field-grid">
                <div class="detail-field">
                  <span class="detail-field__label">版本</span>
                  <span class="detail-field__value">{{ releaseName }}</span>
                </div>
                <div class="detail-field">
                  <span class="detail-field__label">负责人</span>
                  <span class="detail-field__value">{{ plan.ownerName || '—' }}</span>
                </div>
                <div class="detail-field">
                  <span class="detail-field__label">计划窗口</span>
                  <span class="detail-field__value">{{ formatDate(plan.windowStart) }} → {{ formatDate(plan.windowEnd) }}</span>
                </div>
                <div class="detail-field">
                  <span class="detail-field__label">测试环境</span>
                  <span class="detail-field__value">{{ plan.env?.name || '—' }}</span>
                </div>
                <div class="detail-field">
                  <span class="detail-field__label">测试策略</span>
                  <span class="detail-field__value">
                    <template v-if="plan.strategy">v{{ plan.strategy.versionNo }} · {{ dict.label('strategy', plan.strategy.status) }}</template>
                    <template v-else>—</template>
                  </span>
                </div>
                <div class="detail-field">
                  <span class="detail-field__label">轮次</span>
                  <span class="detail-field__value">{{ plan.rounds?.total ?? 0 }} 轮<template v-if="plan.rounds?.activeRoundNo"> · 当前第 {{ plan.rounds.activeRoundNo }} 轮</template></span>
                </div>
                <div class="detail-field">
                  <span class="detail-field__label">用例 / 已执行</span>
                  <span class="detail-field__value">{{ formatNumber(summary?.total ?? 0) }} / {{ formatNumber(summary?.executed ?? 0) }}</span>
                </div>
                <div class="detail-field">
                  <span class="detail-field__label">分配</span>
                  <span class="detail-field__value">
                    {{ plan.assignment?.rows ?? 0 }} 人 · 已分配 {{ plan.assignment?.plannedTotal ?? 0 }}/{{ plan.assignment?.caseTotal ?? 0 }}
                  </span>
                </div>
              </div>
              <div class="overview__actions">
                <el-button size="small" @click="switchTab('cases')">查看用例</el-button>
                <el-button size="small" @click="switchTab('assignments')">去分配</el-button>
                <el-button size="small" @click="router.push({ name: 'test-plan-execute', params: { id: planId } })">去执行</el-button>
              </div>
            </div>

            <aside class="criteria-card">
              <header class="criteria-card__head">
                <span>准出条件</span>
                <span class="criteria-card__count">{{ exitItems.length }} 项</span>
              </header>
              <p v-if="!exitItems.length" class="criteria-card__empty">未设置准出条件，提交完成时按默认条件判定</p>
              <ul v-else class="criteria-list">
                <li
                  v-for="(item, index) in exitItems"
                  :key="`${item.key}-${index}`"
                  class="criteria-item"
                  :class="{ 'is-unmet': unmetFor(item.key) }"
                >
                  <span class="criteria-item__label">{{ criteriaLabel(item.key) }}</span>
                  <span class="criteria-item__rule">{{ item.op }} {{ item.value }}<template v-if="item.key === 'passRate' || item.key === 'coverage'">%</template></span>
                  <span class="criteria-item__actual">
                    实际 {{ criteriaActual(item.key) }}
                  </span>
                  <span class="criteria-item__state">
                    <template v-if="unmetFor(item.key)">未满足</template>
                    <template v-else>待服务端判定</template>
                  </span>
                </li>
              </ul>
              <footer class="criteria-card__foot">
                判定只在提交完成时由服务端做（同一份 exit_criteria 语义），页面不预先下结论。
              </footer>
            </aside>
          </div>

          <!-- 用例 -->
          <div v-else-if="tab === 'cases'" class="cases-tab">
            <div class="cases-tab__bar">
              <div class="segmented" role="group" aria-label="用例视图">
                <button type="button" :class="{ 'is-active': casesMode === 'round' }" @click="casesMode = 'round'">分轮次</button>
                <button type="button" :class="{ 'is-active': casesMode === 'total' }" @click="casesMode = 'total'">累计</button>
              </div>
              <RoundSwitcher
                v-if="casesMode === 'round' && rounds.length"
                :rounds="rounds"
                :model-value="roundId"
                @update:model-value="selectRound"
              />
              <span class="dl-filters__spacer" />
              <el-button link @click="loadTab">刷新</el-button>
            </div>

            <div v-if="casesMode === 'round' && roundLocked" class="readonly-banner" role="status">
              R{{ selectedRound?.roundNo }} 已关闭 · 只读
            </div>

            <el-table
              v-if="casesMode === 'round'"
              :data="roundDetail?.cases ?? []"
              row-key="caseId"
              size="small"
            >
              <el-table-column prop="caseCode" label="用例编号" width="130">
                <template #default="{ row }"><span class="t-code">{{ row.caseCode }}</span></template>
              </el-table-column>
              <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
              <el-table-column label="最新结果" width="110">
                <template #default="{ row }">
                  <StatusPill v-if="row.result" kind="execution" :value="row.result" size="sm" />
                  <span v-else class="muted">未执行</span>
                </template>
              </el-table-column>
              <el-table-column label="执行人" width="120">
                <template #default="{ row }">
                  {{ users.find((user) => user.id === row.executorId)?.name || (row.executorId ? `#${row.executorId}` : '—') }}
                </template>
              </el-table-column>
              <el-table-column label="执行时间" width="170">
                <template #default="{ row }"><span class="muted">{{ formatDateTime(row.executedAt) }}</span></template>
              </el-table-column>
              <el-table-column prop="note" label="备注" min-width="160" show-overflow-tooltip />
            </el-table>

            <el-table v-else :data="m5?.cases ?? []" row-key="caseId" size="small">
              <el-table-column prop="caseCode" label="用例编号" width="130">
                <template #default="{ row }"><span class="t-code">{{ row.caseCode }}</span></template>
              </el-table-column>
              <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
              <el-table-column label="钉住版本" width="100">
                <template #default="{ row }"><span class="muted">v{{ row.caseVersion }}</span></template>
              </el-table-column>
              <el-table-column label="最新结果" width="110">
                <template #default="{ row }">
                  <StatusPill v-if="row.result" kind="execution" :value="row.result" size="sm" />
                  <span v-else class="muted">未执行</span>
                </template>
              </el-table-column>
              <el-table-column label="执行时间" width="170">
                <template #default="{ row }"><span class="muted">{{ formatDateTime(row.executedAt) }}</span></template>
              </el-table-column>
              <el-table-column prop="note" label="备注" min-width="160" show-overflow-tooltip />
            </el-table>

            <EmptyState
              v-if="casesMode === 'round' && !roundDetail?.cases?.length"
              compact
              title="这一轮还没有用例"
              description="开启轮次时按作用域固化用例"
              icon="file"
            />
            <EmptyState
              v-else-if="casesMode === 'total' && !m5?.cases?.length"
              compact
              title="还没有纳入用例"
              description="在新建计划时选择，或用例页批量纳入"
              icon="file"
            />
          </div>

          <!-- 轮次 -->
          <div v-else-if="tab === 'rounds'" class="rounds-tab">
            <div class="rounds-tab__bar">
              <span class="dl-hint">同一计划同时只能有一轮进行中；关闭时若仍有未执行用例会被拒绝。</span>
              <div class="dl-filters__spacer" />
              <el-button size="small" :disabled="!can('plan:execute')" @click="openRoundDialog">开新轮次</el-button>
              <el-button size="small" :disabled="!can('plan:execute')" @click="openRoundFromFailures">从失败建新轮</el-button>
            </div>

            <EmptyState v-if="!rounds.length" title="还没有轮次" description="开始执行或手动开启 R1" icon="chart" />

            <article
              v-for="round in rounds"
              :key="round.id"
              class="round-card"
              :class="{ 'is-active': round.active }"
            >
              <header class="round-card__head">
                <span class="round-card__no">R{{ round.roundNo }}</span>
                <span class="round-card__type">{{ round.roundType }}</span>
                <span class="round-card__name">{{ round.name || '' }}</span>
                <StatusPill kind="planRound" :value="round.status" size="sm" />
                <span v-if="round.conclusion" class="round-card__conclusion">
                  结论 <StatusPill kind="planRound" :value="round.conclusion" size="sm" />
                </span>
                <span class="dl-filters__spacer" />
                <el-button
                  v-if="round.status !== 'CLOSED'"
                  size="small"
                  :disabled="!can('plan:execute')"
                  @click="openCloseDialog(round)"
                >
                  关闭轮次
                </el-button>
                <el-button size="small" link type="primary" @click="selectRound(round.id); switchTab('cases')">
                  查看用例
                </el-button>
              </header>
              <div class="round-card__stats">
                <span>用例 <b>{{ round.caseTotal }}</b></span>
                <span>执行 <b>{{ round.executed }}</b></span>
                <span>通过 <b>{{ round.passed }}</b></span>
                <span>失败 <b class="is-danger">{{ round.failed }}</b></span>
                <span>阻塞 <b>{{ round.blocked }}</b></span>
                <span>通过率 <b>{{ formatPercent(round.passRate) }}</b></span>
                <span>覆盖率 <b>{{ formatPercent(round.coverage) }}</b></span>
                <span class="muted">{{ formatDate(round.startAt) }} → {{ formatDate(round.endAt) }}</span>
              </div>
            </article>
          </div>

          <!-- 分配 -->
          <div v-else-if="tab === 'assignments'" class="assignments-tab">
            <div class="assignments-tab__bar">
              <RoundSwitcher :rounds="rounds" :model-value="roundId" @update:model-value="selectRound" />
              <span class="dl-filters__spacer" />
              <span class="dl-hint">合计 = 计划用例数才能保存（差额由服务端复核）</span>
            </div>
            <AssignmentSummary
              :rows="assignments"
              :case-total="summary?.total ?? 0"
              :executors="users.map((user) => ({ id: user.id, name: user.name }))"
              :editable="can('plan:assign') && !roundLocked"
              :saving="assignmentSaving"
              @save="saveAssignments"
            />
          </div>

          <!-- 变更日志 -->
          <div v-else-if="tab === 'changes'" class="changes-tab">
            <el-table :data="changes" row-key="id" size="small">
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="change-diff">
                    <div>
                      <div class="change-diff__label">变更前</div>
                      <pre class="dl-json">{{ row.beforeJson || '—' }}</pre>
                    </div>
                    <div>
                      <div class="change-diff__label">变更后</div>
                      <pre class="dl-json">{{ row.afterJson || '—' }}</pre>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="Revision" width="100">
                <template #default="{ row }"><span class="num">r{{ row.revision }}</span></template>
              </el-table-column>
              <el-table-column prop="changeType" label="类型" width="120" />
              <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
              <el-table-column label="审批人" width="120">
                <template #default="{ row }">{{ row.approverName || '—' }}</template>
              </el-table-column>
              <el-table-column label="操作人" width="120">
                <template #default="{ row }">{{ row.operatorName || '—' }}</template>
              </el-table-column>
              <el-table-column label="时间" width="170">
                <template #default="{ row }"><span class="muted">{{ formatDateTime(row.createdAt) }}</span></template>
              </el-table-column>
            </el-table>
            <EmptyState v-if="!changes.length" title="还没有变更记录" description="计划内容变更会产生 revision 与留痕" icon="file" />
          </div>

          <!-- 报告 -->
          <div v-else class="reports-tab">
            <div class="reports-tab__bar">
              <span class="dl-hint">报告版本不可变；生成后旧版仍可导出，不随新数据变化。</span>
              <div class="dl-filters__spacer" />
              <el-button
                size="small"
                type="primary"
                :disabled="!can('report:generate') || status === 'DRAFT'"
                :loading="reportGenerating"
                @click="generateReport"
              >
                生成报告
              </el-button>
            </div>
            <el-table :data="reports" row-key="id" size="small">
              <el-table-column prop="code" label="编号" width="140">
                <template #default="{ row }"><span class="t-code">{{ row.code }}</span></template>
              </el-table-column>
              <el-table-column label="当前版本" width="100">
                <template #default="{ row }">v{{ row.currentVersion }}</template>
              </el-table-column>
              <el-table-column label="状态" width="130">
                <template #default="{ row }"><StatusPill kind="report" :value="row.status" size="sm" /></template>
              </el-table-column>
              <el-table-column label="结论" width="120">
                <template #default="{ row }">
                  <StatusPill v-if="row.conclusion" kind="report" :value="row.conclusion" size="sm" />
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="通过率" width="100" align="right">
                <template #default="{ row }"><span class="num">{{ formatPercent(row.passRate) }}</span></template>
              </el-table-column>
              <el-table-column label="覆盖率" width="100" align="right">
                <template #default="{ row }"><span class="num">{{ formatPercent(row.coverage) }}</span></template>
              </el-table-column>
              <el-table-column label="生成时间" width="170">
                <template #default="{ row }"><span class="muted">{{ formatDateTime(row.createdAt) }}</span></template>
              </el-table-column>
              <el-table-column label="操作" width="90" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="router.push({ name: 'test-report-detail', params: { id: row.id } })">打开</el-button>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState v-if="!reports.length" title="还没有测试报告" description="计划批准后可生成第一个版本" icon="file" />
          </div>
        </div>
      </section>
    </template>

    <!-- 危险动作确认（原因必填与否按动作决定） -->
    <el-dialog v-model="actionDialog.visible" :title="dict.actionLabel(actionDialog.action, 'plan')" width="480px" :close-on-click-modal="false">
      <p class="dialog-hint">
        {{ actionDialog.action === 'reject' ? '驳回后计划回到草稿，必须填写原因。' : '该动作会改变计划状态，请在原因中说明背景。' }}
      </p>
      <el-input
        v-if="actionDialog.action === 'reject'"
        v-model="actionDialog.comment"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        placeholder="驳回原因（必填）"
      />
      <el-input
        v-else
        v-model="actionDialog.reason"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        placeholder="变更原因（可选，会写入变更日志）"
      />
      <template #footer>
        <el-button @click="actionDialog.visible = false">取消</el-button>
        <el-button type="danger" :loading="actionDialog.running" @click="confirmAction">
          {{ dict.actionLabel(actionDialog.action, 'plan') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 轮次抽屉：开轮 / 关轮 -->
    <el-dialog v-model="roundDialog.visible" :title="roundDialog.mode === 'open' ? '开启轮次' : '关闭轮次'" width="520px" :close-on-click-modal="false">
      <template v-if="roundDialog.mode === 'open'">
        <el-form label-position="top">
          <div class="date-grid">
            <el-form-item label="轮次类型">
              <el-select v-model="roundDialog.roundType" style="width: 100%">
                <el-option label="新功能验证" value="FEATURE" />
                <el-option label="回归" value="REGRESSION" />
                <el-option label="冒烟" value="SMOKE" />
              </el-select>
            </el-form-item>
            <el-form-item label="轮次名称">
              <el-input v-model="roundDialog.name" maxlength="40" />
            </el-form-item>
          </div>
          <el-form-item label="用例作用域">
            <el-radio-group v-model="roundDialog.scopeMode">
              <el-radio value="CURRENT">当前范围</el-radio>
              <el-radio value="PREVIOUS_FAILED">上一轮失败</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="roundDialog.note" type="textarea" :rows="2" maxlength="200" />
          </el-form-item>
        </el-form>
      </template>
      <template v-else>
        <el-form label-position="top">
          <el-form-item label="轮次结论">
            <el-select v-model="roundDialog.conclusion" style="width: 100%">
              <el-option label="通过" value="PASS" />
              <el-option label="有条件通过" value="CONDITIONAL" />
              <el-option label="不通过" value="FAIL" />
              <el-option label="取消" value="CANCELLED" />
            </el-select>
          </el-form-item>
          <el-form-item label="说明">
            <el-input v-model="roundDialog.note" type="textarea" :rows="3" maxlength="200" show-word-limit />
          </el-form-item>
        </el-form>
        <div v-if="roundDialog.unexecuted.length" class="dl-conflict" role="alert">
          <strong>还有 {{ roundDialog.unexecuted.length }} 条用例未执行，无法关闭</strong>
          <div class="unexecuted-chips">
            <span v-for="item in roundDialog.unexecuted" :key="item.caseId" class="unexecuted-chip">{{ item.caseCode || item.caseId }}</span>
          </div>
        </div>
      </template>
      <template #footer>
        <el-button @click="roundDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="roundDialog.running" @click="submitRound">
          {{ roundDialog.mode === 'open' ? '开启' : '关闭轮次' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.detail-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
}

.detail-title__rev {
  color: var(--text-muted-aa);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.detail-title__chip {
  height: 20px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  color: var(--text-body);
  font-size: 11px;
  line-height: 18px;
}

.detail-lifecycle {
  padding: 12px;
}

/* ---------------- 结论条 56px ---------------- */

.conclusion {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: var(--h-conclusion, 56px);
  padding: 0 16px;
  border: 1px solid var(--border);
  border-left-width: 3px;
  border-radius: var(--r-card);
  background: var(--color-info-bg);
  font-size: 13px;
  color: var(--text-body);
}

.conclusion--primary {
  border-color: #cfe0fb;
  border-left-color: var(--color-primary);
  background: var(--color-primary-light);
  color: #1a4fae;
}

.conclusion--success {
  border-color: #cbe8d5;
  border-left-color: var(--color-success);
  background: var(--color-success-bg);
  color: var(--success-ink);
}

.conclusion--danger {
  border-color: #f7d4d5;
  border-left-color: var(--color-danger);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}

.conclusion__text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conclusion__flag {
  margin-left: auto;
  font-weight: 600;
}

/* ---------------- 冲突卡 ---------------- */

.detail-conflict {
  margin: 0;
  outline: none;
}

.detail-conflict:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.detail-conflict__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-conflict__code {
  padding: 0 6px;
  border-radius: var(--r-cell);
  background: rgba(217, 54, 62, 0.08);
  color: var(--danger-ink);
  font-family: var(--font-mono);
  font-size: 11px;
}

.detail-conflict__spacer {
  flex: 1;
}

.detail-conflict__close {
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: var(--r-cell);
  background: transparent;
  color: var(--text-muted-aa);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
}

.detail-conflict__close:hover {
  background: rgba(31, 35, 41, 0.06);
}

.detail-conflict__msg {
  margin: 4px 0 0;
  color: var(--text-body);
  font-size: 12px;
}

.detail-conflict__list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.detail-conflict__item {
  display: grid;
  grid-template-columns: 150px 110px 140px 1fr;
  gap: 8px;
  padding: 6px 0;
  border-top: 1px dashed rgba(217, 54, 62, 0.24);
  font-size: 12px;
}

.detail-conflict__name {
  color: var(--text-title);
  font-weight: 600;
}

.detail-conflict__num {
  color: var(--text-body);
  font-variant-numeric: tabular-nums;
}

.detail-conflict__hint {
  color: var(--text-muted-aa);
}

.detail-conflict__next {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-body);
}

/* ---------------- tab ---------------- */

.tabs {
  display: flex;
  gap: 2px;
  padding: 0 12px;
  border-bottom: 1px solid var(--border);
}

.tabs__item {
  position: relative;
  height: 40px;
  padding: 0 14px;
  border: 0;
  background: transparent;
  color: var(--text-body);
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: background-color var(--t-color) var(--ease);
}

.tabs__item:hover {
  background: var(--bg-hover);
}

.tabs__item:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}

.tabs__item.is-active {
  color: var(--color-primary);
  font-weight: 600;
}

.tabs__item.is-active::after {
  content: '';
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: -1px;
  height: 2px;
  background: var(--color-primary);
}

.tabs__count {
  margin-left: 6px;
  color: var(--text-muted-aa);
  font-weight: 400;
  font-variant-numeric: tabular-nums;
}

.tab-body {
  padding: 16px;
  min-height: 240px;
}

/* ---------------- 概览 ---------------- */

.overview {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 16px;
}

@media (max-width: 1180px) {
  .overview {
    grid-template-columns: minmax(0, 1fr);
  }
}

.detail-field {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 0;
  border-bottom: 1px solid var(--divider);
  min-width: 0;
}

.detail-field__label {
  color: var(--text-muted-aa);
  font-size: 12px;
}

.detail-field__value {
  color: var(--text-title);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.overview__actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.criteria-card {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  overflow: hidden;
}

.criteria-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 40px;
  padding: 0 12px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-table-head);
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}

.criteria-card__count {
  color: var(--text-muted-aa);
  font-weight: 400;
  font-size: 12px;
}

.criteria-card__empty {
  margin: 0;
  padding: 16px 12px;
  color: var(--text-muted-aa);
  font-size: 12px;
}

.criteria-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.criteria-item {
  display: grid;
  grid-template-columns: 1fr auto;
  row-gap: 2px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--divider);
  font-size: 12px;
}

.criteria-item__label {
  color: var(--text-title);
}

.criteria-item__rule {
  color: var(--text-body);
  font-variant-numeric: tabular-nums;
}

.criteria-item__actual {
  color: var(--text-muted-aa);
}

.criteria-item__state {
  justify-self: end;
  color: var(--text-muted-aa);
}

.criteria-item.is-unmet {
  border-left: 2px solid var(--color-danger);
  background: #fffbfb;
}

.criteria-item.is-unmet .criteria-item__state {
  color: var(--danger-ink);
  font-weight: 600;
}

.criteria-card__foot {
  padding: 8px 12px;
  color: var(--text-muted-aa);
  font-size: 11px;
  line-height: 16px;
}

/* ---------------- 用例 / 通用 ---------------- */

.cases-tab__bar,
.rounds-tab__bar,
.assignments-tab__bar,
.reports-tab__bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.segmented {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-table-head);
}

.segmented button {
  height: 26px;
  padding: 0 12px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--text-body);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
}

.segmented button.is-active {
  background: var(--bg-card);
  color: var(--color-primary);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}

.readonly-banner {
  display: flex;
  align-items: center;
  height: 32px;
  margin-bottom: 8px;
  padding: 0 12px;
  border-radius: var(--r-ctl);
  background: var(--color-info-bg);
  color: var(--text-body);
  font-size: 12px;
}

/* ---------------- 轮次卡 ---------------- */

.round-card {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  padding: 12px;
  margin-bottom: 10px;
  transition: border-color var(--t-color) var(--ease);
}

.round-card:hover {
  border-color: var(--border-strong);
}

.round-card.is-active {
  border-left: 2px solid var(--color-primary);
}

.round-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.round-card__no {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.round-card__type {
  color: var(--text-muted-aa);
  font-size: 11px;
}

.round-card__name {
  color: var(--text-body);
  font-size: 12px;
}

.round-card__conclusion {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--text-muted-aa);
  font-size: 12px;
}

.round-card__stats {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 8px;
  color: var(--text-body);
  font-size: 12px;
}

.round-card__stats b {
  font-variant-numeric: tabular-nums;
  color: var(--text-title);
}

.round-card__stats b.is-danger {
  color: var(--danger-ink);
}

.muted {
  color: var(--text-muted-aa);
  font-size: 12px;
}

.num {
  font-variant-numeric: tabular-nums;
}

/* ---------------- 变更日志 ---------------- */

.change-diff {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  padding: 8px 12px;
}

.change-diff__label {
  margin-bottom: 4px;
  color: var(--text-muted-aa);
  font-size: 11px;
}

.change-diff .dl-json {
  margin: 0;
  max-height: 200px;
  overflow: auto;
}

/* ---------------- 弹窗 ---------------- */

.dialog-hint {
  margin: 0 0 8px;
  color: var(--text-body);
  font-size: 12px;
}

.date-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 12px;
}

.unexecuted-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.unexecuted-chip {
  padding: 0 6px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--bg-card);
  color: var(--text-body);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 18px;
}
</style>
