<script setup lang="ts">
import { computed, reactive, ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { defectFromExecution, executeCase, fetchDataNote, getTestPlan } from '@/api/deliver'
import { planApi, roundApi, envApi } from '@/api/quality'
import { listDictionaryUsers, type DictionaryUser } from '@/api/dictionaries'
import { downloadAttachment } from '@/api/req'
import { usePermission } from '@/composables/usePermission'
import { useDictStore } from '@/stores/dict'
import type { PlanCounts, PlanDetail, RoundDetail, RoundView, TestPlanDetail } from '@/api/types'
import type { TestDataNote } from '@/api/deliver'
import StatusPill from '@/components/StatusPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PassRateRing from '@/components/PassRateRing.vue'
import RoundSwitcher from '@/components/quality/RoundSwitcher.vue'
import { formatDateTime, formatNumber, formatPercent } from '@/utils/format'
import '@/views/deliver/shared.css'

/**
 * 执行台（DESIGN Q5 / AC-2）。
 *
 * 事实来源全部在服务端：
 * - 轮次列表、轮次作用域与轮次计数来自 `roundApi`；
 * - 通过率/覆盖率不在这里做除法，直接显示服务端 `counts.passRate/coverage`；
 * - 登记结果只走 `POST /test-plans/{id}/execute`，由服务端判定“是否有进行中轮次”、
 *   “用例是否在本轮作用域内”，前端不做合法性预判（409 原样呈现）。
 */
interface ExecRow {
  caseId: number
  caseCode: string
  title?: string | null
  caseVersion?: number | null
  result?: string | null
  executedAt?: string | null
  executorId?: number | null
  note?: string | null
}

interface UnmetLike {
  key?: string
  label?: string
  actual?: string
  expected?: string
  fixHint?: string
}

const RESULTS = [
  { value: 'PASS', label: '通过' },
  { value: 'FAIL', label: '失败' },
  { value: 'BLOCKED', label: '阻塞' },
  { value: 'SKIP', label: '跳过' },
]

const BUCKETS = [
  { key: 'all', label: '全部' },
  { key: 'NOT_RUN', label: '未执行' },
  { key: 'FAIL', label: '失败' },
  { key: 'BLOCKED', label: '阻塞' },
  { key: 'PASS', label: '通过' },
]

const route = useRoute()
const router = useRouter()
const dict = useDictStore()
const { can } = usePermission()

const planId = computed(() => Number(route.params.id))
const roundId = computed(() => {
  const raw = route.query.round
  return raw ? Number(raw) : null
})
const mode = computed<'round' | 'total'>(() => (route.query.mode === 'total' ? 'total' : 'round'))

const plan = ref<PlanDetail | null>(null)
const m5 = ref<TestPlanDetail | null>(null)
const dataNote = ref<TestDataNote | null>(null)
const dataNoteOpen = ref(true)
const rounds = ref<RoundView[]>([])
const roundDetail = ref<RoundDetail | null>(null)
const users = ref<DictionaryUser[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const conflict = ref<{ title: string; code?: string; message: string; allowedNext: string[]; unmet: UnmetLike[] } | null>(null)
const bucket = ref('all')
const keyword = ref('')
const drafts = reactive<Record<number, { result: string; note: string }>>({})
const executionIds = reactive<Record<number, number>>({})
const selected = ref<ExecRow[]>([])

const status = computed(() => String(plan.value?.status ?? '').toUpperCase())
const activeRound = computed(() => rounds.value.find((round) => round.active) ?? null)
const selectedRound = computed(() => rounds.value.find((round) => round.id === roundId.value) ?? null)
const roundLocked = computed(() => mode.value === 'round' && selectedRound.value?.status === 'CLOSED')
const counts = computed<PlanCounts | null>(() => {
  if (mode.value === 'round') return roundDetail.value?.counts ?? null
  const summary = m5.value?.summary
  if (!summary) return null
  return {
    total: summary.planned,
    executed: summary.executed,
    passed: summary.passed,
    failed: summary.failed,
    blocked: summary.blocked,
    notRun: summary.notRun,
    passRate: summary.passRate,
    coverage: summary.coverage,
  }
})
const execAllowed = computed(
  () => status.value === 'IN_PROGRESS' && !!activeRound.value && can('plan:execute') && !roundLocked.value,
)

const versionMap = computed(() => {
  const map = new Map<number, number>()
  for (const row of m5.value?.cases ?? []) map.set(row.caseId, row.caseVersion)
  return map
})

const executorName = (id?: number | null): string =>
  id ? users.value.find((user) => user.id === id)?.name || `#${id}` : '—'

const rows = computed<ExecRow[]>(() => {
  if (mode.value === 'round') {
    return (roundDetail.value?.cases ?? []).map((row) => ({
      caseId: row.caseId,
      caseCode: row.caseCode,
      title: row.title,
      caseVersion: versionMap.value.get(row.caseId) ?? null,
      result: row.result ?? 'NOT_RUN',
      executedAt: row.executedAt,
      executorId: row.executorId,
      note: row.note,
    }))
  }
  return (m5.value?.cases ?? []).map((row) => ({
    caseId: row.caseId,
    caseCode: row.caseCode,
    title: row.title,
    caseVersion: row.caseVersion,
    result: row.result ?? 'NOT_RUN',
    executedAt: row.executedAt,
    executorId: null,
    note: row.note,
  }))
})

const filtered = computed(() => {
  const word = keyword.value.trim().toLowerCase()
  return rows.value.filter((row) => {
    const byBucket = bucket.value === 'all' || (row.result || 'NOT_RUN') === bucket.value
    if (!byBucket) return false
    if (!word) return true
    return (
      row.caseCode.toLowerCase().includes(word) ||
      String(row.title ?? '').toLowerCase().includes(word)
    )
  })
})

function bucketCount(key: string): number {
  if (key === 'all') return rows.value.length
  return rows.value.filter((row) => (row.result || 'NOT_RUN') === key).length
}

const progress = computed(() => {
  const total = rows.value.length
  const done = rows.value.filter((row) => (row.result || 'NOT_RUN') !== 'NOT_RUN').length
  return { total, done }
})

function draftOf(row: ExecRow): { result: string; note: string } {
  if (!drafts[row.caseId]) {
    drafts[row.caseId] = { result: row.result || 'NOT_RUN', note: String(row.note ?? '') }
  }
  return drafts[row.caseId]
}

function onResultChange(row: ExecRow, value: string): void {
  draftOf(row).result = value
}

function onNoteInput(row: ExecRow, value: string): void {
  draftOf(row).note = value
}

const dirtyRows = computed(() =>
  rows.value.filter((row) => {
    const draft = drafts[row.caseId]
    if (!draft) return false
    if (draft.result === 'NOT_RUN') return false
    return draft.result !== (row.result || 'NOT_RUN') || draft.note !== String(row.note ?? '')
  }),
)

/* ---------------- 加载 ---------------- */

function raiseConflict(title: string, err: unknown): void {
  const typed = err as { code?: string; message?: string; detail?: Record<string, unknown> }
  const detail = typed.detail ?? {}
  const unmet = Array.isArray(detail.unmet) ? (detail.unmet as UnmetLike[]) : []
  conflict.value = {
    title,
    code: typed.code,
    message: typed.message || '操作未完成',
    allowedNext: Array.isArray(detail.allowedNext) ? (detail.allowedNext as string[]) : (plan.value?.allowedNext ?? []),
    unmet,
  }
}

async function loadAll(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [detail, planDetail, roundList] = await Promise.all([
      getTestPlan(planId.value),
      planApi.detail(planId.value).catch(() => null),
      roundApi.list(planId.value).catch(() => [] as RoundView[]),
    ])
    m5.value = detail
    plan.value = planDetail
    rounds.value = roundList
    await loadRound()
    void loadDataNote()
  } catch (err) {
    error.value = {
      message: (err as Error).message || '执行台加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

/** 执行台只读展示测试数据说明：读不到就整块不渲染，不编造「无数据」结论 */
const dataNoteText = computed(() => (dataNote.value?.dataNote ?? '').trim())
const dataNoteAttachments = computed(() => dataNote.value?.attachments ?? [])
const hasDataNote = computed(() => Boolean(dataNoteText.value) || dataNoteAttachments.value.length > 0)

async function loadDataNote(): Promise<void> {
  try {
    dataNote.value = await fetchDataNote(planId.value)
  } catch {
    dataNote.value = null
  }
}

function downloadNoteAttachment(id: number, name: string): void {
  void downloadAttachment(id, name)
}

async function loadRound(): Promise<void> {
  roundDetail.value = null
  if (!roundId.value) return
  try {
    roundDetail.value = await roundApi.detail(planId.value, roundId.value)
  } catch (err) {
    raiseConflict('轮次数据加载失败', err)
  }
}

function selectRound(id: number): void {
  void router.replace({ query: { ...route.query, round: id } })
}

function setMode(next: 'round' | 'total'): void {
  void router.replace({ query: { ...route.query, mode: next } })
}

function clearDrafts(): void {
  for (const key of Object.keys(drafts)) delete drafts[Number(key)]
}

/* ---------------- 登记结果 ---------------- */

async function saveRow(row: ExecRow): Promise<boolean> {
  const draft = draftOf(row)
  if (draft.result === 'NOT_RUN') return true
  try {
    const result = await executeCase(planId.value, {
      caseId: row.caseId,
      result: draft.result,
      note: draft.note || undefined,
    })
    executionIds[row.caseId] = result.executionId
    return true
  } catch (err) {
    raiseConflict(`用例 ${row.caseCode} 登记失败`, err)
    return false
  }
}

async function saveOne(row: ExecRow): Promise<void> {
  saving.value = true
  try {
    const ok = await saveRow(row)
    if (ok) {
      delete drafts[row.caseId]
      ElMessage.success(`${row.caseCode} 已保存`)
      await refreshCounters()
    }
  } finally {
    saving.value = false
  }
}

async function saveAll(): Promise<void> {
  const pending = [...dirtyRows.value]
  if (!pending.length) {
    ElMessage.info('没有待保存的登记')
    return
  }
  saving.value = true
  let okCount = 0
  let failed = 0
  try {
    for (const row of pending) {
      const ok = await saveRow(row)
      if (ok) {
        delete drafts[row.caseId]
        okCount += 1
      } else {
        failed += 1
      }
    }
    if (okCount) ElMessage.success(`已保存 ${okCount} 条登记`)
    if (failed) ElMessage.warning(`${failed} 条未保存，见下方冲突清单`)
    await refreshCounters()
  } finally {
    saving.value = false
  }
}

async function refreshCounters(): Promise<void> {
  await Promise.all([
    loadRound(),
    getTestPlan(planId.value).then((detail) => {
      m5.value = detail
    }),
    planApi.detail(planId.value).then((detail) => {
      plan.value = detail
    }),
    roundApi.list(planId.value).then((list) => {
      rounds.value = list
    }),
  ])
}

/* ---------------- 批量 ---------------- */

async function batchPass(): Promise<void> {
  const targets = (selected.value.length ? selected.value : filtered.value).filter(
    (row) => (row.result || 'NOT_RUN') === 'NOT_RUN',
  )
  if (!targets.length) {
    ElMessage.info('没有未执行的用例可批量通过')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将把 ${targets.length} 条未执行用例登记为通过，失败行不会被覆盖。`,
      '批量通过',
      { confirmButtonText: '批量通过', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  saving.value = true
  let okCount = 0
  let failed = 0
  try {
    for (const row of targets) {
      try {
        const result = await executeCase(planId.value, { caseId: row.caseId, result: 'PASS' })
        executionIds[row.caseId] = result.executionId
        delete drafts[row.caseId]
        okCount += 1
      } catch (err) {
        failed += 1
        raiseConflict(`用例 ${row.caseCode} 未登记`, err)
      }
    }
    if (okCount) ElMessage.success(`已批量通过 ${okCount} 条`)
    if (failed) ElMessage.warning(`${failed} 条未保存，见上方冲突清单`)
    await refreshCounters()
  } finally {
    saving.value = false
  }
}

async function batchSkip(): Promise<void> {
  const targets = selected.value.filter((row) => (row.result || 'NOT_RUN') === 'NOT_RUN')
  if (!targets.length) {
    ElMessage.info('请先勾选未执行的用例')
    return
  }
  let note = ''
  try {
    const result = await ElMessageBox.prompt('批量跳过需要统一原因（会写入每条执行记录）', '批量跳过', {
      inputPlaceholder: '例如：本轮环境未就绪',
      confirmButtonText: '批量跳过',
      cancelButtonText: '取消',
      inputValidator: (value: string) => (value && value.trim().length >= 2 ? true : '原因至少 2 个字'),
    })
    note = result.value
  } catch {
    return
  }
  saving.value = true
  let okCount = 0
  try {
    for (const row of targets) {
      try {
        const result = await executeCase(planId.value, { caseId: row.caseId, result: 'SKIP', note })
        executionIds[row.caseId] = result.executionId
        delete drafts[row.caseId]
        okCount += 1
      } catch (err) {
        raiseConflict(`用例 ${row.caseCode} 未跳过`, err)
      }
    }
    if (okCount) ElMessage.success(`已跳过 ${okCount} 条`)
    await refreshCounters()
  } finally {
    saving.value = false
  }
}

async function toDefect(row: ExecRow): Promise<void> {
  const executionId = executionIds[row.caseId]
  if (!executionId) {
    ElMessage.warning('请先在本轮保存该用例的失败结果，再转缺陷')
    return
  }
  try {
    const defect = await defectFromExecution(executionId)
    ElMessage.success(`已生成缺陷 ${defect.code ?? ''}`)
    void router.push({ name: 'defect-detail', params: { id: defect.id } })
  } catch (err) {
    raiseConflict('转缺陷未完成', err)
  }
}

/* ---------------- 轮次与状态 ---------------- */

async function startPlan(): Promise<void> {
  try {
    plan.value = await planApi.action(planId.value, 'start', {})
    conflict.value = null
    ElMessage.success('计划已开始执行')
  } catch (err) {
    raiseConflict('开始执行未完成', err)
  }
}

async function openFirstRound(): Promise<void> {
  try {
    const created = await roundApi.open(planId.value, {
      roundType: 'FEATURE',
      name: 'R1',
      scopeMode: 'CURRENT',
    })
    rounds.value = await roundApi.list(planId.value)
    selectRound(created.id)
    ElMessage.success(`第 ${created.roundNo} 轮已开启`)
  } catch (err) {
    raiseConflict('开启轮次未完成', err)
  }
}

const closeDialog = reactive({ visible: false, conclusion: 'PASS', note: '', running: false, unexecuted: [] as number[] })

function openCloseRound(): void {
  closeDialog.conclusion = 'PASS'
  closeDialog.note = ''
  closeDialog.unexecuted = []
  closeDialog.visible = true
}

async function submitCloseRound(): Promise<void> {
  if (!selectedRound.value) return
  closeDialog.running = true
  try {
    await roundApi.close(planId.value, selectedRound.value.id, {
      conclusion: closeDialog.conclusion,
      note: closeDialog.note || undefined,
    })
    closeDialog.visible = false
    ElMessage.success('轮次已关闭')
    await refreshCounters()
  } catch (err) {
    const typed = err as { detail?: Record<string, unknown> }
    const ids = typed.detail?.unexecutedCaseIds
    if (Array.isArray(ids)) closeDialog.unexecuted = ids as number[]
    raiseConflict('关闭轮次未完成', err)
  } finally {
    closeDialog.running = false
  }
}

const envDialog = reactive({ visible: false, envId: undefined as number | undefined, reason: '', running: false })
const envOptions = ref<Array<{ id: number; name: string; status: string }>>([])

async function openEnvDialog(): Promise<void> {
  envDialog.envId = plan.value?.env?.id ?? undefined
  envDialog.reason = ''
  envDialog.visible = true
  if (envOptions.value.length) return
  try {
    const page = await envApi.page({ page: 1, size: 100 })
    envOptions.value = (page.items ?? []).map((env) => ({
      id: env.id,
      name: env.name,
      status: String(env.status ?? ''),
    }))
  } catch {
    envOptions.value = []
  }
}

async function submitEnv(): Promise<void> {
  envDialog.running = true
  try {
    plan.value = await planApi.update(planId.value, {
      envId: envDialog.envId ?? null,
      reason: envDialog.reason || undefined,
    })
    envDialog.visible = false
    ElMessage.success('测试环境已更新')
  } catch (err) {
    raiseConflict('环境变更未完成', err)
  } finally {
    envDialog.running = false
  }
}

watch(roundId, () => {
  clearDrafts()
  void loadRound()
})

watch(
  () => mode.value,
  () => {
    clearDrafts()
    selected.value = []
  },
)

onMounted(async () => {
  await loadAll()
  try {
    users.value = await listDictionaryUsers()
  } catch {
    users.value = []
  }
  if (!roundId.value) {
    const fallback = activeRound.value ?? rounds.value[rounds.value.length - 1]
    if (fallback) selectRound(fallback.id)
  }
})
</script>

<template>
  <div class="dl-page exec-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <button type="button" class="dl-head__back" @click="router.push({ name: 'test-plan-detail', params: { id: planId } })">
          ← 计划详情
        </button>
        <div class="exec-title">
          <h1 class="t-page-title">{{ plan?.code || m5?.plan.code || '计划' }} · {{ plan?.name || m5?.plan.name || '' }}</h1>
          <StatusPill v-if="plan" kind="plan" :value="plan.status" />
        </div>
      </div>
      <div class="dl-head__actions">
        <el-button size="small" :disabled="!plan" @click="openEnvDialog">
          环境 {{ plan?.env?.name || '未设置' }}
        </el-button>
        <el-button size="small" :disabled="!execAllowed || saving" @click="batchSkip">批量跳过</el-button>
        <el-button size="small" type="primary" :disabled="!execAllowed || saving" @click="batchPass">批量通过</el-button>
        <el-button size="small" :disabled="!selectedRound || selectedRound.status === 'CLOSED'" @click="openCloseRound">
          关闭轮次
        </el-button>
      </div>
    </header>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="loadAll" />

    <template v-else>
      <!-- 说明条：没有进行中轮次 / 计划未开始 / 轮次已关闭，各给一条明确出路 -->
      <div v-if="status === 'APPROVED'" class="notice notice--info">
        <span>计划已批准但尚未开始执行，开始后才能登记结果。</span>
        <el-button size="small" type="primary" :disabled="!can('plan:execute')" @click="startPlan">开始执行</el-button>
      </div>
      <div v-else-if="status === 'IN_PROGRESS' && !activeRound" class="notice notice--info">
        <span>还没有进行中的轮次。</span>
        <el-button size="small" type="primary" :disabled="!can('plan:execute')" @click="openFirstRound">开启 R1</el-button>
      </div>
      <div v-else-if="roundLocked" class="readonly-banner" role="status">
        R{{ selectedRound?.roundNo }} 已关闭 · 只读（切到进行中的轮次或累计视图查看）
      </div>

      <div v-if="conflict" class="dl-conflict exec-conflict" role="alert">
        <div class="exec-conflict__head">
          <strong>{{ conflict.title }}</strong>
          <span v-if="conflict.code" class="exec-conflict__code">{{ conflict.code }}</span>
          <span class="exec-conflict__spacer" />
          <button type="button" class="exec-conflict__close" aria-label="关闭提示" @click="conflict = null">×</button>
        </div>
        <p class="exec-conflict__msg">{{ conflict.message }}</p>
        <ul v-if="conflict.unmet.length" class="exec-conflict__list">
          <li v-for="(item, index) in conflict.unmet" :key="`${item.key}-${index}`">
            <span>{{ item.label || item.key }}</span>
            <span>实际 {{ item.actual }}</span>
            <span>要求 {{ item.expected }}</span>
            <span class="exec-conflict__hint">{{ item.fixHint }}</span>
          </li>
        </ul>
        <div v-if="conflict.allowedNext.length" class="exec-conflict__next">
          <span>当前可执行：</span>
          <el-button
            v-for="action in conflict.allowedNext"
            :key="action"
            link
            type="primary"
            size="small"
            @click="action === 'start' ? startPlan() : undefined"
          >
            {{ dict.actionLabel(action, 'plan') }}
          </el-button>
        </div>
      </div>

      <!-- 测试数据说明（只读折叠区） -->
      <section v-if="hasDataNote" class="exec-datanote">
        <button
          type="button"
          class="exec-datanote__bar"
          :aria-expanded="dataNoteOpen"
          @click="dataNoteOpen = !dataNoteOpen"
        >
          <span class="exec-datanote__caret" :class="{ 'is-open': dataNoteOpen }" aria-hidden="true">▸</span>
          <span class="exec-datanote__title">测试数据说明</span>
          <span class="exec-datanote__count">{{ dataNoteAttachments.length }} 个附件</span>
          <span class="exec-datanote__hint">只读 · 在计划详情里维护</span>
        </button>
        <div v-show="dataNoteOpen" class="exec-datanote__body">
          <p v-if="dataNoteText" class="exec-datanote__text">{{ dataNoteText }}</p>
          <ul v-if="dataNoteAttachments.length" class="exec-datanote__files">
            <li v-for="row in dataNoteAttachments" :key="row.id">
              <button type="button" class="exec-datanote__file" @click="downloadNoteAttachment(row.id, row.fileName)">
                {{ row.fileName }}
              </button>
            </li>
          </ul>
        </div>
      </section>

      <!-- 统计行 -->
      <section class="exec-stats" v-loading="loading">
        <div class="exec-stats__ring">
          <PassRateRing :passed="counts?.passed ?? 0" :planned="counts?.total ?? 0" />
          <div class="exec-stats__ring-cap">
            服务端通过率 <b>{{ formatPercent(counts?.passRate ?? null) }}</b>
            <template v-if="counts?.coverage !== null && counts?.coverage !== undefined">
              · 覆盖率 {{ formatPercent(counts.coverage) }}
            </template>
          </div>
        </div>
        <div class="exec-stats__tiles">
          <div class="exec-tile">
            <span class="exec-tile__label">未执行</span>
            <span class="exec-tile__value">{{ formatNumber(counts?.notRun ?? 0) }}</span>
          </div>
          <div class="exec-tile exec-tile--danger">
            <span class="exec-tile__label">失败</span>
            <span class="exec-tile__value">{{ formatNumber(counts?.failed ?? 0) }}</span>
          </div>
          <div class="exec-tile exec-tile--warning">
            <span class="exec-tile__label">阻塞</span>
            <span class="exec-tile__value">{{ formatNumber(counts?.blocked ?? 0) }}</span>
          </div>
          <div class="exec-tile">
            <span class="exec-tile__label">执行进度</span>
            <span class="exec-tile__value">{{ progress.done }}/{{ progress.total }}</span>
          </div>
        </div>
      </section>

      <!-- 轮次条 -->
      <section class="exec-rounds">
        <RoundSwitcher :rounds="rounds" :model-value="roundId" @update:model-value="selectRound" />
        <div class="segmented" role="group" aria-label="用例视图">
          <button type="button" :class="{ 'is-active': mode === 'round' }" @click="setMode('round')">分轮次</button>
          <button type="button" :class="{ 'is-active': mode === 'total' }" @click="setMode('total')">累计</button>
        </div>
        <span class="dl-filters__spacer" />
        <el-input v-model="keyword" size="small" placeholder="搜索用例编号/标题" clearable class="exec-search" />
      </section>

      <!-- 左侧状态分组 + 右侧执行表 -->
      <div class="exec-split">
        <aside class="exec-tree" aria-label="用例状态分组">
          <button
            v-for="item in BUCKETS"
            :key="item.key"
            type="button"
            class="exec-tree__item"
            :class="{ 'is-active': bucket === item.key }"
            @click="bucket = item.key"
          >
            <span>{{ item.label }}</span>
            <span class="exec-tree__count">{{ bucketCount(item.key) }}</span>
          </button>
        </aside>

        <section class="exec-main" v-loading="loading">
          <el-table :data="filtered" row-key="caseId" size="small" height="calc(100vh - 420px)" @selection-change="selected = $event">
            <el-table-column type="selection" width="40" />
            <el-table-column label="用例" min-width="260">
              <template #default="{ row }">
                <div class="exec-case">
                  <span class="t-code">{{ row.caseCode }}</span>
                  <span v-if="row.caseVersion" class="exec-case__ver">v{{ row.caseVersion }}</span>
                  <span class="exec-case__title">{{ row.title || '—' }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="结果" width="130">
              <template #default="{ row }">
                <el-select
                  :model-value="draftOf(row).result"
                  size="small"
                  :disabled="!execAllowed"
                  @update:model-value="onResultChange(row, $event)"
                >
                  <el-option label="未执行" value="NOT_RUN" />
                  <el-option v-for="item in RESULTS" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="180">
              <template #default="{ row }">
                <el-input
                  :model-value="draftOf(row).note"
                  size="small"
                  placeholder="失败必填原因"
                  :disabled="!execAllowed"
                  @update:model-value="onNoteInput(row, $event)"
                />
              </template>
            </el-table-column>
            <el-table-column label="执行人" width="110">
              <template #default="{ row }">{{ executorName(row.executorId) }}</template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template #default="{ row }"><span class="muted">{{ formatDateTime(row.executedAt) }}</span></template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="draftOf(row).result !== 'NOT_RUN'"
                  link
                  type="primary"
                  size="small"
                  :disabled="!execAllowed || saving"
                  @click="saveOne(row)"
                >
                  保存
                </el-button>
                <el-button
                  v-if="(row.result || '') === 'FAIL'"
                  link
                  type="danger"
                  size="small"
                  @click="toDefect(row)"
                >
                  转缺陷
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <EmptyState
            v-if="!filtered.length"
            compact
            :title="rows.length ? '这个筛选下没有用例' : '这一轮还没有用例'"
            :description="rows.length ? '换个状态分组或清空搜索词' : '开启轮次时按作用域固化用例'"
            icon="filter"
          />
        </section>
      </div>

      <!-- sticky 底条 -->
      <div class="exec-sticky dl-sticky" role="status">
        <span>当前 {{ progress.done }}/{{ progress.total }}</span>
        <span v-if="dirtyRows.length" class="exec-sticky__dirty">未保存 {{ dirtyRows.length }}</span>
        <span v-else class="exec-sticky__clean">已全部保存</span>
        <span class="exec-sticky__spacer" />
        <el-button size="small" :disabled="!dirtyRows.length || saving" @click="saveAll" :loading="saving">
          保存全部
        </el-button>
      </div>
    </template>

    <el-dialog v-model="closeDialog.visible" title="关闭轮次" width="480px" :close-on-click-modal="false">
      <p class="dialog-hint">关闭后本轮只读；有未执行用例时服务端会拒绝并列出用例编号。</p>
      <el-form label-position="top">
        <el-form-item label="轮次结论">
          <el-select v-model="closeDialog.conclusion" style="width: 100%">
            <el-option label="通过" value="PASS" />
            <el-option label="有条件通过" value="CONDITIONAL" />
            <el-option label="不通过" value="FAIL" />
            <el-option label="取消" value="CANCELLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="closeDialog.note" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <div v-if="closeDialog.unexecuted.length" class="dl-conflict" role="alert">
        <strong>还有 {{ closeDialog.unexecuted.length }} 条用例未执行</strong>
        <div class="unexecuted-chips">
          <span v-for="id in closeDialog.unexecuted" :key="id" class="unexecuted-chip">#{{ id }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="closeDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="closeDialog.running" @click="submitCloseRound">关闭轮次</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="envDialog.visible" title="测试环境" width="420px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="环境">
          <el-select v-model="envDialog.envId" clearable style="width: 100%" placeholder="选择环境">
            <el-option v-for="env in envOptions" :key="env.id" :label="env.name" :value="env.id" />
          </el-select>
          <p v-if="!envOptions.length" class="dialog-hint">环境台账还没有可用环境，先在「测试环境」页新建。</p>
        </el-form-item>
        <el-form-item label="变更原因">
          <el-input v-model="envDialog.reason" type="textarea" :rows="2" maxlength="120" placeholder="已批准计划必须说明原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="envDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="envDialog.running" @click="submitEnv">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.exec-page {
  padding-bottom: 64px;
}

.exec-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.notice {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
  padding: 0 12px;
  margin-bottom: 12px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--color-primary);
  border-radius: var(--r-card);
  background: var(--color-primary-light);
  color: #1a4fae;
  font-size: 13px;
}

.readonly-banner {
  display: flex;
  align-items: center;
  height: 32px;
  margin-bottom: 12px;
  padding: 0 12px;
  border-radius: var(--r-ctl);
  background: var(--color-info-bg);
  color: var(--text-body);
  font-size: 12px;
}

.exec-conflict {
  margin-bottom: 12px;
}

.exec-conflict__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.exec-conflict__code {
  padding: 0 6px;
  border-radius: var(--r-cell);
  background: rgba(217, 54, 62, 0.08);
  color: var(--danger-ink);
  font-family: var(--font-mono);
  font-size: 11px;
}

.exec-conflict__spacer {
  flex: 1;
}

.exec-conflict__close {
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: var(--r-cell);
  background: transparent;
  color: var(--text-muted-aa);
  font-size: 16px;
  cursor: pointer;
}

.exec-conflict__msg {
  margin: 4px 0 0;
  font-size: 12px;
}

.exec-conflict__list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.exec-conflict__list li {
  display: grid;
  grid-template-columns: 1fr 110px 120px 1fr;
  gap: 8px;
  padding: 4px 0;
  border-top: 1px dashed rgba(217, 54, 62, 0.24);
  font-size: 12px;
}

.exec-conflict__hint {
  color: var(--text-muted-aa);
}

.exec-conflict__next {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  font-size: 12px;
}

/* 测试数据说明：只读折叠区 */
.exec-datanote {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  margin-bottom: 12px;
  overflow: hidden;
}

.exec-datanote__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  background: var(--bg-table-head, #fafbfc);
  cursor: pointer;
  text-align: left;
  transition: background var(--t-color) ease;
}

.exec-datanote__bar:hover {
  background: var(--bg-hover);
}

.exec-datanote__bar:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}

.exec-datanote__caret {
  display: inline-block;
  font-size: 11px;
  color: var(--text-muted-aa);
  transition: transform var(--t-expand) ease;
}

.exec-datanote__caret.is-open {
  transform: rotate(90deg);
}

.exec-datanote__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}

.exec-datanote__count {
  font-size: 12px;
  color: var(--text-body);
  padding: 0 6px;
  border-radius: var(--r-pill);
  background: var(--bg-selected);
}

.exec-datanote__hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted-aa);
}

.exec-datanote__body {
  padding: 10px 12px 12px;
  border-top: 1px solid var(--divider);
}

.exec-datanote__text {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-body);
  white-space: pre-wrap;
  word-break: break-word;
}

.exec-datanote__files {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
}

.exec-datanote__file {
  border: none;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--color-primary);
  cursor: pointer;
}

.exec-datanote__file:hover {
  text-decoration: underline;
}

/* 统计行：环 + 4 个数字块 */
.exec-stats {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 12px;
  margin-bottom: 12px;
}

.exec-stats__ring {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
}

.exec-stats__ring-cap {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: var(--text-muted-aa);
  font-size: 11px;
  line-height: 16px;
}

.exec-stats__ring-cap b {
  color: var(--text-title);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.exec-stats__tiles {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.exec-tile {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
}

.exec-tile--danger .exec-tile__value {
  color: var(--danger-ink);
}

.exec-tile--warning .exec-tile__value {
  color: #b26a00;
}

.exec-tile__label {
  color: var(--text-muted-aa);
  font-size: 12px;
}

.exec-tile__value {
  color: var(--text-title);
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 26px;
}

.exec-rounds {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.exec-search {
  width: 220px;
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
}

.exec-split {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.exec-tree {
  display: flex;
  flex-direction: column;
  padding: 6px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
}

.exec-tree__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 32px;
  padding: 0 8px;
  border: 0;
  border-radius: var(--r-ctl);
  background: transparent;
  color: var(--text-body);
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: background-color var(--t-color) var(--ease);
}

.exec-tree__item:hover {
  background: var(--bg-hover);
}

.exec-tree__item.is-active {
  background: var(--bg-selected);
  color: var(--color-primary);
  font-weight: 600;
}

.exec-tree__count {
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}

.exec-main {
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  overflow: hidden;
}

.exec-case {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.exec-case__ver {
  padding: 0 4px;
  border-radius: var(--r-cell);
  background: var(--bg-table-head);
  color: var(--text-muted-aa);
  font-size: 11px;
  line-height: 16px;
}

.exec-case__title {
  color: var(--text-body);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.exec-sticky {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  height: 48px;
  padding: 0 16px;
  margin-top: 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.04);
  font-size: 13px;
  color: var(--text-body);
}

.exec-sticky__dirty {
  color: #b26a00;
  font-weight: 600;
}

.exec-sticky__clean {
  color: var(--text-muted-aa);
}

.exec-sticky__spacer {
  flex: 1;
}

.muted {
  color: var(--text-muted-aa);
  font-size: 12px;
}

.dialog-hint {
  margin: 0 0 8px;
  color: var(--text-muted-aa);
  font-size: 12px;
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

@media (max-width: 1080px) {
  .exec-stats {
    grid-template-columns: minmax(0, 1fr);
  }
  .exec-split {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
