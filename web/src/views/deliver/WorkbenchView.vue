<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listTestPlans } from '@/api/deliver'
import { reportApi, waiverApi, workbenchApi } from '@/api/quality'
import { usePermission } from '@/composables/usePermission'
import type { MyCaseRow } from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import { formatDate, formatDateTime, formatNumber } from '@/utils/format'
import '@/views/deliver/shared.css'

/**
 * 工作台（DESIGN Q1 / AC-9.5）。
 *
 * 规则：
 * - 四个数字卡的 0 也要显示（灰色 0），不隐藏——隐藏会让“今天没事”和“数据没加载”看起来一样；
 * - 数字全部取服务端 total，不在前端数数组长度（分页会让本地计数说谎）；
 * - 我的用例按计划分组，组内给「去执行」，未分配用例给「去分配」（仅负责人可见）。
 */
interface CaseGroup {
  planId: number
  planName: string
  planCode: string
  roundNo: number | null
  roundId: number | null
  dueDate: string | null
  overdue: boolean
  notRun: number
  failed: number
  blocked: number
  total: number
  rows: MyCaseRow[]
}

interface UnassignedGroup {
  planId: number
  planName: string
  planCode: string
  rows: MyCaseRow[]
}

const router = useRouter()
const { can } = usePermission()

const loading = ref(false)
const myCases = ref<MyCaseRow[]>([])
const myCaseTotal = ref(0)
const overdueTotal = ref(0)
const counts = ref({ pendingPlans: 0, signingReports: 0, pendingWaivers: 0, myPending: 0 })

const today = new Date()
const greeting = computed(() => {
  const hour = today.getHours()
  if (hour < 6) return '夜里好'
  if (hour < 12) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})
const todayText = computed(() =>
  today.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }),
)

const cards = computed(() => [
  {
    key: 'plans',
    label: '待批准计划',
    value: counts.value.pendingPlans,
    hint: '批准后才能开始执行',
    to: { name: 'test-plans', query: { status: 'PENDING_REVIEW' } },
  },
  {
    key: 'reports',
    label: '待会签报告',
    value: counts.value.signingReports,
    hint: '三级会签严格顺序',
    to: { name: 'test-reports', query: { status: 'SIGNING' } },
  },
  {
    key: 'waivers',
    label: '待审批豁免',
    value: counts.value.pendingWaivers,
    hint: '1–30 天，QG-04 不可豁免',
    to: { name: 'quality-gates', query: { tab: 'waivers' } },
  },
  {
    key: 'cases',
    label: '我的待执行用例',
    value: counts.value.myPending,
    hint: overdueTotal.value ? `其中 ${overdueTotal.value} 条已超期` : '今天没有超期项',
    to: { name: 'workbench', query: { focus: 'cases' } },
  },
])

const assignedCases = computed(() => myCases.value.filter((row) => !row.unassigned))

const groups = computed<CaseGroup[]>(() => {
  const map = new Map<string, CaseGroup>()
  for (const row of assignedCases.value) {
    const key = `${row.planId}-${row.roundId ?? 'none'}`
    let group = map.get(key)
    if (!group) {
      group = {
        planId: row.planId,
        planName: row.planName || `计划 #${row.planId}`,
        planCode: row.planCode || '',
        roundNo: row.roundNo ?? null,
        roundId: row.roundId ?? null,
        dueDate: row.dueDate ?? null,
        overdue: false,
        notRun: 0,
        failed: 0,
        blocked: 0,
        total: 0,
        rows: [],
      }
      map.set(key, group)
    }
    group.rows.push(row)
    group.total += 1
    if (row.overdue) group.overdue = true
    const result = String(row.result ?? 'NOT_RUN')
    if (result === 'NOT_RUN') group.notRun += 1
    else if (result === 'FAIL') group.failed += 1
    else if (result === 'BLOCKED') group.blocked += 1
    if (!group.dueDate && row.dueDate) group.dueDate = row.dueDate
  }
  return [...map.values()]
})

const unassignedGroups = computed<UnassignedGroup[]>(() => {
  const map = new Map<number, UnassignedGroup>()
  for (const row of myCases.value.filter((item) => item.unassigned)) {
    let group = map.get(row.planId)
    if (!group) {
      group = {
        planId: row.planId,
        planName: row.planName || `计划 #${row.planId}`,
        planCode: row.planCode || '',
        rows: [],
      }
      map.set(row.planId, group)
    }
    group.rows.push(row)
  }
  return [...map.values()]
})

function goExecute(group: CaseGroup): void {
  void router.push({
    name: 'test-plan-execute',
    params: { id: group.planId },
    query: group.roundId ? { round: String(group.roundId) } : {},
  })
}

function goAssign(group: UnassignedGroup): void {
  void router.push({ name: 'test-plan-detail', params: { id: group.planId }, query: { tab: 'assignments' } })
}

function goCard(card: { to: Record<string, unknown> }): void {
  void router.push(card.to as never)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [casesPage, overduePage, plansPage, reportsPage, waiversPage] = await Promise.all([
      workbenchApi.myCases({ page: 1, size: 200, overdue: false }).catch(() => null),
      workbenchApi.myCases({ page: 1, size: 1, overdue: true }).catch(() => null),
      listTestPlans({ status: 'PENDING_REVIEW', page: 1, size: 1 } as never).catch(() => null),
      reportApi.page({ status: 'SIGNING', page: 1, size: 1 }).catch(() => null),
      waiverApi.page({ status: 'PENDING', page: 1, size: 1 }).catch(() => null),
    ])
    myCases.value = casesPage?.items ?? []
    myCaseTotal.value = casesPage?.total ?? 0
    overdueTotal.value = overduePage?.total ?? 0
    counts.value = {
      pendingPlans: plansPage?.total ?? 0,
      signingReports: reportsPage?.total ?? 0,
      pendingWaivers: waiversPage?.total ?? 0,
      myPending: myCaseTotal.value,
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="dl-page wb-page">
    <header class="wb-head">
      <div>
        <h1 class="wb-head__greeting">{{ greeting }}，今天要看的质量项都在这里</h1>
        <p class="wb-head__date">{{ todayText }}</p>
      </div>
      <div class="wb-head__actions">
        <el-button size="small" :disabled="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="wb-cards" v-loading="loading">
      <button
        v-for="card in cards"
        :key="card.key"
        type="button"
        class="wb-card"
        :class="{ 'is-zero': card.value === 0 }"
        @click="goCard(card)"
      >
        <span class="wb-card__label">{{ card.label }}</span>
        <span class="wb-card__value">{{ formatNumber(card.value) }}</span>
        <span class="wb-card__hint">{{ card.hint }}</span>
      </button>
    </section>

    <section class="wb-section" v-loading="loading">
      <header class="wb-section__head">
        <h2 class="wb-section__title">我的待执行用例</h2>
        <span class="wb-section__count">{{ formatNumber(myCaseTotal) }} 条</span>
        <span class="dl-filters__spacer" />
        <el-button size="small" link type="primary" @click="router.push({ name: 'test-plans' })">去测试计划</el-button>
      </header>

      <EmptyState
        v-if="!groups.length"
        title="今天没有待执行用例"
        description="分配给你的用例会出现在这里；也可以去测试计划里认领"
        icon="inbox"
        action-text="去测试计划"
        @action="router.push({ name: 'test-plans' })"
      />

      <article
        v-for="group in groups"
        :key="`${group.planId}-${group.roundId}`"
        class="wb-group"
        :class="{ 'is-overdue': group.overdue }"
      >
        <div class="wb-group__main">
          <div class="wb-group__title">
            <span class="t-code">{{ group.planCode }}</span>
            <span class="wb-group__name">{{ group.planName }}</span>
            <span v-if="group.roundNo" class="wb-group__round">R{{ group.roundNo }}</span>
            <StatusPill v-if="group.overdue" kind="plan" value="IN_PROGRESS" label="已超期" size="sm" />
          </div>
          <div class="wb-group__meta">
            <span v-if="group.dueDate">截止 {{ formatDate(group.dueDate) }}</span>
            <span v-else class="muted">未设截止</span>
            <span>未执行 <b>{{ group.notRun }}</b></span>
            <span :class="{ 'is-danger': group.failed > 0 }">失败 <b>{{ group.failed }}</b></span>
            <span :class="{ 'is-warning': group.blocked > 0 }">阻塞 <b>{{ group.blocked }}</b></span>
            <span class="muted">共 {{ group.total }} 条</span>
          </div>
        </div>
        <div class="wb-group__actions">
          <el-button size="small" type="primary" @click="goExecute(group)">去执行</el-button>
        </div>
      </article>

      <p v-if="groups.length && myCaseTotal > myCases.length" class="wb-note">
        只展示前 {{ myCases.length }} 条（共 {{ formatNumber(myCaseTotal) }} 条），请到执行台按计划筛选。
      </p>
    </section>

    <section v-if="can('plan:assign') && unassignedGroups.length" class="wb-section">
      <header class="wb-section__head">
        <h2 class="wb-section__title">我负责计划里的未分配用例</h2>
        <span class="wb-section__count">{{ unassignedGroups.length }} 个计划</span>
      </header>
      <article v-for="group in unassignedGroups" :key="group.planId" class="wb-group">
        <div class="wb-group__main">
          <div class="wb-group__title">
            <span class="t-code">{{ group.planCode }}</span>
            <span class="wb-group__name">{{ group.planName }}</span>
          </div>
          <div class="wb-group__meta">
            <span>未分配 <b class="is-danger">{{ group.rows.length }}</b> 条</span>
            <span class="muted">未分配的用例不会出现在任何人的待办里</span>
          </div>
        </div>
        <div class="wb-group__actions">
          <el-button size="small" @click="goAssign(group)">去分配</el-button>
        </div>
      </article>
    </section>

    <p class="wb-foot">最后刷新：{{ formatDateTime(new Date().toISOString()) }}</p>
  </div>
</template>

<style scoped>
.wb-page {
  padding-bottom: 32px;
}

.wb-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.wb-head__greeting {
  margin: 0;
  color: var(--text-title);
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
}

.wb-head__date {
  margin: 2px 0 0;
  color: var(--text-muted-aa);
  font-size: 12px;
}

.wb-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.wb-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--t-color) var(--ease), background-color var(--t-color) var(--ease);
}

.wb-card:hover {
  border-color: var(--border-strong);
  background: var(--bg-hover);
}

.wb-card:active {
  background: var(--bg-active, #f2f3f5);
}

.wb-card:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.wb-card__label {
  color: var(--text-body);
  font-size: 12px;
}

.wb-card__value {
  color: var(--text-title);
  font-size: 26px;
  font-weight: 600;
  line-height: 32px;
  font-variant-numeric: tabular-nums;
}

.wb-card.is-zero .wb-card__value {
  color: var(--text-muted-aa);
}

.wb-card__hint {
  color: var(--text-muted-aa);
  font-size: 11px;
  line-height: 16px;
}

.wb-section {
  margin-bottom: 20px;
}

.wb-section__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.wb-section__title {
  margin: 0;
  color: var(--text-title);
  font-size: 14px;
  font-weight: 600;
}

.wb-section__count {
  color: var(--text-muted-aa);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.wb-group {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  margin-bottom: 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  transition: border-color var(--t-color) var(--ease), background-color var(--t-color) var(--ease);
}

.wb-group:hover {
  border-color: var(--border-strong);
  background: var(--bg-hover);
}

.wb-group.is-overdue {
  border-left: 2px solid var(--color-danger);
}

.wb-group__main {
  min-width: 0;
  flex: 1;
}

.wb-group__title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.wb-group__name {
  color: var(--text-title);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wb-group__round {
  padding: 0 6px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  color: var(--text-body);
  font-size: 11px;
  line-height: 16px;
}

.wb-group__meta {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 4px;
  color: var(--text-secondary);
  font-size: 12px;
  flex-wrap: wrap;
}

.wb-group__meta b {
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}

.wb-group__meta .is-danger b,
.wb-group__meta.is-danger b {
  color: var(--danger-ink);
}

.wb-group__meta .is-warning b {
  color: #b26a00;
}

.wb-note {
  margin: 8px 0 0;
  color: var(--text-muted-aa);
  font-size: 11px;
}

.wb-foot {
  margin: 24px 0 0;
  color: var(--text-muted-aa);
  font-size: 11px;
}

.muted {
  color: var(--text-muted-aa);
}

@media (max-width: 1080px) {
  .wb-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
