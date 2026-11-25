<script setup lang="ts">
/**
 * 评审中心（r49，交互稿第 5 屏）：`/pdr-reviews`。
 *
 * **为什么列表要自己聚合**：后端只提供 `GET /pdr-reviews/stats` 与 `GET /pdr-reviews/datasets`，
 * 没有 `GET /pdr-reviews` 分页列表，也没有 `GET /pdr-reviews/{id}`。所以这里的口径是：
 * 1. 先按「PDR 需求」分页取需求（`GET /requirements?type=PDR`）——需求本来就带域/时间筛选；
 * 2. 再对当前页逐条 `GET /requirements/{id}/pdr-review` 补评审单与意见（并发 6，`PDR_LIST_SCAN`）；
 * 3. 统计口径全部来自 `GET /pdr-reviews/stats`（分类分布 / 按评审人 / 需修改率三份 groupBy）。
 * 这层「需求 → 评审单」的补数在后端补出列表端点后可以整段删掉（见交付汇报的契约缺口）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listRequirements } from '@/api/req'
import { getPdrReview, getPdrStats, PDR_LIST_SCAN } from '@/api/pdr'
import type { RequirementRow } from '@/api/types'
import type { PdrOpinion, PdrReview, PdrStats } from '@/api/types.pdr'
import StatusPill from '@/components/StatusPill.vue'
import MetricCard from '@/components/MetricCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { useAuthStore } from '@/stores/auth'
import { usePermission } from '@/composables/usePermission'
import { formatDateTime, fromNow } from '@/utils/format'
import { messageOf, usePlatformDict } from '@/views/req/platform'
import {
  pdrCategoryBuckets,
  pdrHours,
  pdrNeedChangeRate,
  pdrReviewerTop,
} from '@/views/req/pdr'

type TabKey = 'mine' | 'initiated' | 'all'

interface PdrListRow {
  requirement: RequirementRow
  review: PdrReview | null
  opinions: PdrOpinion[]
}

const router = useRouter()
const auth = useAuthStore()
const perm = usePermission()
const dict = usePlatformDict()

const me = computed(() => auth.user)
const canSeeAll = computed(() => perm.canAction('req.action.pdr_review_conclude'))

const tab = ref<TabKey>('mine')
const keyword = ref('')
const domainId = ref<number | null>(null)
const statusFilter = ref<string>('')
const range = ref<[string, string] | null>(null)
const page = ref(1)
const size = ref(15)

const rows = ref<PdrListRow[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const scanned = ref(0)

const statsBase = ref<PdrStats | null>(null)
const statsCategory = ref<PdrStats | null>(null)
const statsReviewer = ref<PdrStats | null>(null)
const statsDecision = ref<PdrStats | null>(null)
const statsLoading = ref(false)

const STATUS_OPTIONS = [
  { value: 'IN_REVIEW', label: '评审中' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'NONE', label: '未发起' },
]

const tabs = computed(() => {
  const list: Array<{ key: TabKey; label: string }> = [
    { key: 'mine', label: '待我评审' },
    { key: 'initiated', label: '我发起的' },
  ]
  if (canSeeAll.value) list.push({ key: 'all', label: '全部' })
  return list
})

const visibleTabs = computed(() => tabs.value.map((item) => item))
const activeTab = computed<TabKey>(() =>
  visibleTabs.value.some((item) => item.key === tab.value) ? tab.value : 'mine',
)

const dateParams = computed(() => ({
  from: range.value?.[0] ?? undefined,
  to: range.value?.[1] ?? undefined,
}))

/** 过滤在客户端做：服务端只能筛需求维度（域/时间），评审状态与「待我评审」只能本地判 */
const filtered = computed(() => {
  const current = activeTab.value
  const kw = keyword.value.trim()
  const status = statusFilter.value
  return rows.value.filter((row) => {
    const review = row.review
    if (current === 'mine') {
      if (!review || review.status !== 'IN_REVIEW') return false
      if (!me.value || !review.reviewerIds.includes(me.value.id)) return false
    }
    if (current === 'initiated') {
      if (!review || review.initiatorId !== me.value?.id) return false
    }
    if (status) {
      const value = review?.status ?? 'NONE'
      if (value !== status) return false
    }
    if (kw) {
      const haystack = `${row.requirement.code} ${row.requirement.title}`.toLowerCase()
      if (!haystack.includes(kw.toLowerCase())) return false
    }
    return true
  })
})

const kpiReviewCount = computed(() => statsBase.value?.reviewCount ?? 0)
const kpiOpinionCount = computed(() => statsBase.value?.opinionCount ?? 0)
const kpiNeedChangeRate = computed(() => pdrNeedChangeRate(statsDecision.value))
const kpiNeedChangeText = computed(() => {
  const rate = kpiNeedChangeRate.value
  return rate == null ? '—' : `${(rate * 100).toFixed(1)}%`
})
const kpiAvgHours = computed(() => pdrHours(statsBase.value?.avgReviewHours))
const kpiP90Hint = computed(() =>
  statsBase.value?.p90ReviewHours != null ? `P90 ${pdrHours(statsBase.value.p90ReviewHours)}` : '',
)

const categoryBars = computed(() => pdrCategoryBuckets(statsCategory.value))
const maxCategoryCount = computed(() => Math.max(1, ...categoryBars.value.map((item) => item.count)))
const reviewerTop = computed(() => pdrReviewerTop(statsReviewer.value, 5))

/** 并发补评审单：小并发池，避免 30 个请求同时打满 req-service */
async function eachLimit<T>(items: T[], limit: number, task: (item: T) => Promise<void>): Promise<void> {
  let cursor = 0
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor
      cursor += 1
      await task(items[index])
    }
  })
  await Promise.all(workers)
}

async function loadStats(): Promise<void> {
  statsLoading.value = true
  const query = { domainId: domainId.value ?? undefined, ...dateParams.value }
  try {
    const [category, reviewer, decision] = await Promise.all([
      getPdrStats({ ...query, groupBy: 'CATEGORY' }),
      getPdrStats({ ...query, groupBy: 'REVIEWER' }),
      getPdrStats({ ...query, groupBy: 'DECISION' }),
    ])
    statsCategory.value = category
    statsReviewer.value = reviewer
    statsDecision.value = decision
    statsBase.value = category
  } catch {
    statsCategory.value = null
    statsReviewer.value = null
    statsDecision.value = null
    statsBase.value = null
  } finally {
    statsLoading.value = false
  }
}

async function loadList(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const response = await listRequirements({
      type: 'PDR',
      keyword: keyword.value.trim() || undefined,
      domainId: domainId.value ?? undefined,
      dateFrom: dateParams.value.from,
      dateTo: dateParams.value.to,
      view: 'flat',
      page: page.value,
      size: size.value,
    })
    total.value = response.total
    const base: PdrListRow[] = response.items.map((requirement) => ({
      requirement,
      review: null,
      opinions: [],
    }))
    // 可选探测：单条评审单读不到（403/404）不该整页失败，退化成「未发起」
    await eachLimit(base, PDR_LIST_SCAN.concurrency, async (row) => {
      try {
        const detail = await getPdrReview(row.requirement.id, { silentForbidden: true })
        row.review = detail.review
        row.opinions = detail.opinions ?? []
      } catch {
        row.review = null
        row.opinions = []
      }
    })
    rows.value = base
    scanned.value = base.length
  } catch (err) {
    error.value = messageOf(err)
    rows.value = []
  } finally {
    loading.value = false
  }
}

function reload(): void {
  void Promise.all([loadList(), loadStats()])
}

function applyFilter(): void {
  page.value = 1
  reload()
}

function resetFilter(): void {
  keyword.value = ''
  domainId.value = null
  statusFilter.value = ''
  range.value = null
  page.value = 1
  reload()
}

function changeTab(key: TabKey): void {
  tab.value = key
}

function openRow(row: PdrListRow): void {
  // 详情页以「需求 id」定位评审单（后端没有按评审单号取详情），tab 直接落在评审
  void router.push(`/requirements/${row.requirement.id}?tab=pdr`)
}

function reviewerText(row: PdrListRow): string {
  const ids = row.review?.reviewerIds ?? []
  if (ids.length === 0) return '—'
  const names = ids.slice(0, 3).map((id) => {
    const name = dict.userName(id)
    return name.startsWith('用户 #') ? `#${id}` : name
  })
  return ids.length > 3 ? `${names.join('、')} 等 ${ids.length} 人` : names.join('、')
}

onMounted(() => {
  void dict.ensure()
  reload()
})
</script>

<template>
  <div class="pdr-list">
    <header class="page-head">
      <div class="page-head__left">
        <h1 class="t-page-title">评审中心</h1>
        <p class="t-aux">PDR 产品需求评审：谁在评、评了什么、改了多少</p>
      </div>
      <div class="page-head__actions">
        <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
      </div>
    </header>

    <!-- 4 个 KPI：口径来自 /pdr-reviews/stats -->
    <section class="surface pdr-list__kpis">
      <MetricCard label="评审单" :value="kpiReviewCount" unit="单" :loading="statsLoading" hint="按筛选范围内已发起的评审单" />
      <MetricCard
        label="评审意见"
        :value="kpiOpinionCount"
        unit="条"
        :loading="statsLoading"
        :hint="kpiReviewCount ? `${(kpiOpinionCount / Math.max(1, kpiReviewCount)).toFixed(1)} 条/单` : ''"
      />
      <MetricCard
        label="需修改率"
        :value="kpiNeedChangeRate == null ? null : kpiNeedChangeText"
        :loading="statsLoading"
        tone="warning"
        hint="按意见口径：(需修改 + 驳回) / 意见总数"
      />
      <MetricCard
        label="平均评审时长"
        :value="kpiAvgHours"
        :loading="statsLoading"
        :hint="kpiP90Hint || '发起 → 关闭'"
      />
    </section>

    <!-- 分类分布 + 评审人 Top5（交互稿第 5 屏下半部分） -->
    <section class="pdr-list__panels">
      <div class="surface surface--pad panel">
        <div class="panel__head">
          <span class="t-block-title">意见分类分布</span>
          <span class="t-aux">九类，与后端 PdrCategoryCatalog 同序</span>
        </div>
        <ul v-if="categoryBars.length" class="bars">
          <li v-for="item in categoryBars" :key="item.code" class="bar">
            <span class="bar__label">{{ item.label }}</span>
            <span class="bar__track">
              <span class="bar__fill" :style="{ width: `${(item.count / maxCategoryCount) * 100}%` }" />
            </span>
            <span class="bar__count t-code">{{ item.count }}</span>
          </li>
        </ul>
        <p v-else class="t-aux panel__empty">筛选范围内还没有评审意见。</p>
      </div>

      <div class="surface surface--pad panel">
        <div class="panel__head">
          <span class="t-block-title">按评审人 Top5</span>
          <span class="t-aux">按意见数</span>
        </div>
        <ol v-if="reviewerTop.length" class="rank">
          <li v-for="(item, index) in reviewerTop" :key="item.key" class="rank__row">
            <span class="rank__index">{{ index + 1 }}</span>
            <span class="rank__name">{{ item.label || `评审人 #${item.key}` }}</span>
            <span class="rank__bar">
              <span
                class="rank__fill"
                :style="{ width: `${(item.count / Math.max(1, reviewerTop[0].count)) * 100}%` }"
              />
            </span>
            <span class="rank__count t-code">{{ item.count }}</span>
          </li>
        </ol>
        <p v-else class="t-aux panel__empty">筛选范围内还没有评审人数据。</p>
      </div>
    </section>

    <!-- 筛选 -->
    <div class="filter-bar">
      <el-input
        v-model="keyword"
        size="small"
        placeholder="需求编号 / 标题"
        clearable
        style="width: 200px"
        @keyup.enter="applyFilter"
        @clear="applyFilter"
      />
      <el-select v-model="domainId" size="small" placeholder="全部业务域" clearable style="width: 150px" @change="applyFilter">
        <el-option v-for="domain in dict.domains.value" :key="domain.id" :label="domain.name" :value="domain.id" />
      </el-select>
      <el-select v-model="statusFilter" size="small" placeholder="全部状态" clearable style="width: 130px" @change="applyFilter">
        <el-option v-for="option in STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-date-picker
        v-model="range"
        type="daterange"
        size="small"
        value-format="YYYY-MM-DD"
        start-placeholder="更新起"
        end-placeholder="更新止"
        style="width: 240px"
        @change="applyFilter"
      />
      <span class="filter-bar__spacer" />
      <el-button size="small" @click="applyFilter">查询</el-button>
      <el-button size="small" link @click="resetFilter">重置</el-button>
    </div>

    <!-- Tab：待我评审 / 我发起的 / 全部（有汇总权才显示） -->
    <div class="pdr-list__tabs">
      <button
        v-for="item in visibleTabs"
        :key="item.key"
        type="button"
        class="chip-count"
        :class="{ 'is-active': activeTab === item.key }"
        @click="changeTab(item.key)"
      >
        {{ item.label }}
      </button>
      <span class="t-aux pdr-list__tabs-hint">
        当前页 {{ filtered.length }} 条（本页共聚合 {{ scanned }} 张需求）
      </span>
    </div>

    <ErrorState v-if="error" title="评审单加载失败" :message="error" @retry="reload" />

    <section v-else class="surface pdr-list__table">
      <div v-if="loading" class="pdr-list__skeleton">
        <div v-for="index in 6" :key="index" class="skeleton skeleton__row" />
      </div>
      <EmptyState
        v-else-if="filtered.length === 0"
        variant="empty"
        :title="activeTab === 'mine' ? '没有待你评审的评审单' : activeTab === 'initiated' ? '你还没有发起过评审' : '没有符合条件的评审单'"
        description="评审单由 PDR 需求详情页的「评审」tab 发起；评审人从项目组成员里选。"
        icon="inbox"
      />
      <el-table v-else :data="filtered" size="small" style="width: 100%" @row-click="openRow">
        <el-table-column label="需求编号" width="130">
          <template #default="{ row }">
            <span class="t-code pdr-list__code">{{ row.requirement.code }}</span>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="240">
          <template #default="{ row }">
            <span class="pdr-list__title">{{ row.requirement.title }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusPill v-if="row.review" kind="pdrReview" :value="row.review.status" size="sm" />
            <span v-else class="t-aux">未发起</span>
          </template>
        </el-table-column>
        <el-table-column label="评审人" min-width="170">
          <template #default="{ row }">
            <span class="t-body">{{ reviewerText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="意见数" width="90" align="right">
          <template #default="{ row }">
            <span class="t-code">{{ row.opinions.length }}</span>
          </template>
        </el-table-column>
        <el-table-column label="截止" width="140">
          <template #default="{ row }">
            <span class="t-aux">{{ row.review?.dueAt ? formatDateTime(row.review.dueAt) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="120">
          <template #default="{ row }">
            <span class="t-aux">{{ row.requirement.updatedAt ? fromNow(row.requirement.updatedAt) : '—' }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && filtered.length > 0" class="pdr-list__pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          small
          @current-change="() => reload()"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.pdr-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.pdr-list__kpis {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
}
.pdr-list__panels {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 12px;
}
.panel__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.panel__empty {
  margin: 4px 0 0;
}
.bars {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.bar {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr) 40px;
  align-items: center;
  gap: 8px;
}
.bar__label {
  font-size: 12px;
  color: var(--text-body);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bar__track {
  height: 8px;
  border-radius: 4px;
  background: var(--bg-hover);
  overflow: hidden;
}
.bar__fill {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: var(--color-primary);
}
.bar__count {
  text-align: right;
}
.rank {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.rank__row {
  display: grid;
  grid-template-columns: 16px 88px minmax(0, 1fr) 34px;
  align-items: center;
  gap: 8px;
}
.rank__index {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.rank__name {
  font-size: 12px;
  color: var(--text-body);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rank__bar {
  height: 6px;
  border-radius: 3px;
  background: var(--bg-hover);
  overflow: hidden;
}
.rank__fill {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: var(--color-info);
}
.rank__count {
  text-align: right;
}
.pdr-list__tabs {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.pdr-list__tabs-hint {
  margin-left: auto;
}
.pdr-list__table {
  padding: 4px 12px 12px;
}
.pdr-list__skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 0;
}
.pdr-list__code {
  color: var(--text-secondary);
}
.pdr-list__title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pdr-list__pager {
  display: flex;
  justify-content: flex-end;
  padding-top: 8px;
}
:deep(.el-table__row) {
  cursor: pointer;
  height: 46px;
}
@media (max-width: 1100px) {
  .pdr-list__kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .pdr-list__panels {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
