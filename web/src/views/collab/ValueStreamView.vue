<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/client'
import {
  drillStage,
  fetchFlowBoard,
  fetchStages,
  type FlowBoard,
  type FlowDrillRow,
  type FlowQuery,
  type FlowStageCard,
  type FlowStageDefinition,
} from '@/api/collab'
import FlowColumn from '@/components/collab/FlowColumn.vue'
import FlowDrillDrawer from '@/components/collab/FlowDrillDrawer.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { usePermission } from '@/composables/usePermission'
import { todayISO, addDays } from '@/utils/ganttScale'
import '@/views/deliver/shared.css'

/**
 * 价值流看板（DESIGN V1–V4 · AC-08/09）。
 *
 * 指标口径全部由服务端 `FlowMetricService` 给出（与指标字典、报表同一个实现）：
 * 页面只渲染 `dwellAvg / dwellP90 / wip / sampleSize`，不做二次平均、不重算 P90，
 * 也不把「无数据」当 0 画进图里。
 */
const router = useRouter()
const { can } = usePermission()
const canConfig = computed(() => can('FLOW_CONFIG'))

const filters = ref<{ from: string; to: string; reqType: string }>({
  from: addDays(todayISO(), -29),
  to: todayISO(),
  reqType: '',
})

const board = ref<FlowBoard | null>(null)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const stageDefs = ref<FlowStageDefinition[]>([])

const cardsByStage = ref<Record<string, FlowDrillRow[]>>({})
const cardsLoading = ref<Record<string, boolean>>({})

const drill = ref({
  open: false,
  stageCode: '',
  stageName: '',
  rows: [] as FlowDrillRow[],
  total: 0,
  page: 1,
  loading: false,
  error: '',
})

const REQ_TYPE_LABELS: Record<string, string> = {
  REQUIREMENT: '需求',
  TASK: '任务',
  DEFECT: '缺陷',
  STORY: '用户故事',
  BUG: '缺陷',
}

const dateRange = computed<[string, string]>({
  get: (): [string, string] => [filters.value.from, filters.value.to],
  set: (value) => {
    if (!value || value.length !== 2) return
    filters.value.from = value[0]
    filters.value.to = value[1]
  },
})

const reqTypeOptions = computed(() => {
  const set = new Set<string>()
  for (const stage of stageDefs.value) {
    for (const type of (stage.reqTypes ?? '').split(/[,;|]/)) {
      const trimmed = type.trim().toUpperCase()
      if (trimmed) set.add(trimmed)
    }
  }
  return [...set].sort()
})

const boardStages = computed<FlowStageCard[]>(() =>
  [...(board.value?.stages ?? [])].sort((a, b) => a.orderNo - b.orderNo),
)

const bottleneckStage = computed(() => boardStages.value.find((stage) => stage.bottleneck) ?? null)

const sampleTotal = computed(() =>
  boardStages.value.filter((stage) => stage.applicable).reduce((sum, stage) => sum + (stage.sampleSize ?? 0), 0),
)

const applicableCount = computed(() => boardStages.value.filter((stage) => stage.applicable).length)

const efficiencyValue = computed(() => board.value?.efficiency?.value ?? null)
const efficiencyPercent = computed(() =>
  efficiencyValue.value === null ? '—' : `${(efficiencyValue.value * 100).toFixed(1)}%`,
)

const rangeLabel = computed(() =>
  board.value ? `${board.value.window.from} ~ ${board.value.window.to}` : `${filters.value.from} ~ ${filters.value.to}`,
)

const generatedLabel = computed(() => (board.value?.generatedAt ?? '').replace('T', ' ').slice(0, 16))

function query(): FlowQuery {
  return {
    from: filters.value.from || undefined,
    to: filters.value.to || undefined,
    reqType: filters.value.reqType || undefined,
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    board.value = await fetchFlowBoard(query())
    void loadCards()
  } catch (err) {
    const apiError = err instanceof ApiError ? err : null
    board.value = null
    error.value = {
      message: apiError?.message ?? '价值流看板加载失败',
      code: apiError?.code,
      status: apiError?.status,
    }
  } finally {
    loading.value = false
  }
}

/** 每列预览 5 张卡：与下钻同一个接口、同一口径，只是 size 不同 */
async function loadCards(): Promise<void> {
  const stages = boardStages.value.filter((stage) => stage.applicable)
  cardsByStage.value = {}
  cardsLoading.value = Object.fromEntries(stages.map((stage) => [stage.code, true]))
  await Promise.all(
    stages.map(async (stage) => {
      try {
        const page = await drillStage({ ...query(), stageCode: stage.code, page: 1, size: 5 })
        cardsByStage.value = { ...cardsByStage.value, [stage.code]: page.items }
      } catch {
        cardsByStage.value = { ...cardsByStage.value, [stage.code]: [] }
      } finally {
        cardsLoading.value = { ...cardsLoading.value, [stage.code]: false }
      }
    }),
  )
}

async function loadStageDefs(): Promise<void> {
  try {
    stageDefs.value = await fetchStages()
  } catch {
    stageDefs.value = []
  }
}

onMounted(async () => {
  await Promise.all([loadStageDefs(), load()])
})

watch(
  () => [filters.value.from, filters.value.to, filters.value.reqType],
  () => {
    void load()
  },
)

function resetFilters(): void {
  filters.value = { from: addDays(todayISO(), -29), to: todayISO(), reqType: '' }
}

function openDrill(stage: FlowStageCard): void {
  if (!stage.applicable) return
  drill.value = {
    open: true,
    stageCode: stage.code,
    stageName: stage.name,
    rows: [],
    total: 0,
    page: 1,
    loading: true,
    error: '',
  }
  void loadDrill(1)
}

async function loadDrill(page: number): Promise<void> {
  drill.value.loading = true
  drill.value.error = ''
  try {
    const result = await drillStage({ ...query(), stageCode: drill.value.stageCode, page, size: 8 })
    drill.value.rows = result.items
    drill.value.total = result.total
    drill.value.page = page
  } catch (err) {
    drill.value.error = err instanceof ApiError ? err.message : '下钻加载失败'
  } finally {
    drill.value.loading = false
  }
}

function onPageChange(page: number): void {
  void loadDrill(page)
}

function openRequirement(row: FlowDrillRow): void {
  if (router.hasRoute('requirement-detail')) {
    void router.push({ name: 'requirement-detail', params: { id: row.id } })
    return
  }
  void router.push({ name: 'requirements', query: { keyword: row.code } })
}

function goStages(): void {
  void router.push({ name: 'value-stream-stages' })
}
</script>

<template>
  <div class="dl-page flow-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">价值流</h1>
        <span class="dl-meta">
          {{ rangeLabel }} · {{ applicableCount }} 个适用阶段 ·
          <template v-if="generatedLabel">生成于 {{ generatedLabel }}</template>
        </span>
      </div>
      <div class="dl-head__actions">
        <el-button v-if="canConfig" @click="goStages">阶段配置</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="flow-filters surface surface--pad">
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="~"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        :clearable="false"
        size="small"
      />
      <el-select v-model="filters.reqType" size="small" placeholder="需求类型（全部）" clearable style="width: 168px">
        <el-option
          v-for="type in reqTypeOptions"
          :key="type"
          :value="type"
          :label="REQ_TYPE_LABELS[type] ?? type"
        />
      </el-select>
      <button type="button" class="flow-link" @click="resetFilters">重置为近 30 天</button>
      <span class="flow-filters__hint">统计范围 = 你的授权域/厂商；下方数值来自审计事件，不在这里二次计算。</span>
    </section>

    <ErrorState
      v-if="error"
      :message="error.message"
      :code="error.code"
      :status="error.status"
      @retry="load()"
    />

    <template v-else>
      <section class="flow-kpi surface" aria-label="价值流关键指标">
        <div class="flow-kpi__item">
          <span class="flow-kpi__label">流动效率</span>
          <strong class="flow-kpi__value t-num">{{ loading ? '…' : efficiencyPercent }}</strong>
          <span class="flow-kpi__hint">
            (开发 + 测试) ÷ (提报 → 交付)
            <template v-if="board"> · 计入 {{ board.efficiency?.included ?? 0 }} / 排除 {{ board.efficiency?.excluded ?? 0 }}</template>
          </span>
        </div>
        <div class="flow-kpi__item">
          <span class="flow-kpi__label">瓶颈阶段</span>
          <strong class="flow-kpi__value">{{ bottleneckStage?.name ?? '样本不足' }}</strong>
          <span class="flow-kpi__hint">
            <template v-if="bottleneckStage">平均停留 {{ bottleneckStage.dwellAvg ?? '—' }} h · 样本 {{ bottleneckStage.sampleSize }}</template>
            <template v-else>每阶段样本 ≥5 才参与瓶颈判定</template>
          </span>
        </div>
        <div class="flow-kpi__item">
          <span class="flow-kpi__label">覆盖样本</span>
          <strong class="flow-kpi__value t-num">{{ loading ? '…' : sampleTotal }}</strong>
          <span class="flow-kpi__hint">各适用阶段的样本数之和（同一需求可被多阶段计入）</span>
        </div>
        <div class="flow-kpi__item">
          <span class="flow-kpi__label">无数据需求</span>
          <strong class="flow-kpi__value t-num" :class="{ 'is-warn': (board?.noDataCount ?? 0) > 0 }">
            {{ loading ? '…' : (board?.noDataCount ?? 0) }}
          </strong>
          <span class="flow-kpi__hint">缺少审计事件的需求不参与均值/P90，也不按 0 兜底</span>
        </div>
      </section>

      <section v-if="loading && !board" class="flow-board flow-board--loading" aria-busy="true">
        <div v-for="n in 7" :key="n" class="flow-skeleton-col" />
      </section>

      <EmptyState
        v-else-if="board && !boardStages.length"
        title="还没有配置价值流阶段"
        description="阶段定义决定看板的列与指标口径；至少要有 2 个阶段。"
        icon="chart"
        :action-text="canConfig ? '去配置阶段' : ''"
        @action="goStages"
      />

      <EmptyState
        v-else-if="board && !applicableCount"
        variant="filtered"
        title="当前筛选下没有适用的阶段"
        description="换一个时间窗口或需求类型；不适用的阶段不显示指标，也不参与瓶颈。"
        icon="filter"
        action-text="重置筛选"
        @action="resetFilters"
      />

      <section v-else-if="board" class="flow-board" aria-label="价值流阶段列">
        <FlowColumn
          v-for="stage in boardStages"
          :key="stage.code"
          :stage="stage"
          :cards="cardsByStage[stage.code] ?? []"
          :cards-loading="cardsLoading[stage.code] ?? false"
          :selected="drill.open && drill.stageCode === stage.code"
          @drill="() => openDrill(stage)"
          @open-requirement="openRequirement"
        />
      </section>

      <p class="flow-footnote">
        停留时长取自 `audit_log` 的状态流转事件；缺口在阶段时间线里显示为「无数据」，不补 0。
        瓶颈判定与指标字典预览共用 `FlowMetricFormulas`，所以看板、字典、报表三处读数应当一致。
      </p>
    </template>

    <FlowDrillDrawer
      v-model="drill.open"
      :stage-code="drill.stageCode"
      :stage-name="drill.stageName"
      :rows="drill.rows"
      :total="drill.total"
      :page="drill.page"
      :size="8"
      :loading="drill.loading"
      :error="drill.error"
      :window="board?.window ?? undefined"
      @page-change="onPageChange"
    />
  </div>
</template>

<style scoped>
.flow-page {
  --flow-col: 160px;
  --flow-gap: 12px;
  --flow-head: 44px;
  --flow-metric: 72px;
}
.flow-filters {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  padding: 10px 16px;
}
.flow-filters__hint {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-weak);
}
.flow-link {
  border: 0;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--color-primary);
  cursor: pointer;
}
.flow-link:hover {
  text-decoration: underline;
}
.flow-kpi {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  height: 56px;
  margin-bottom: 12px;
  border-radius: var(--r-card);
  overflow: hidden;
}
.flow-kpi__item {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 1px;
  min-width: 0;
  padding: 0 16px;
  border-right: 1px solid var(--divider);
}
.flow-kpi__item:last-child {
  border-right: 0;
}
.flow-kpi__label {
  font-size: 11px;
  color: var(--text-muted-aa);
}
.flow-kpi__value {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-title);
  line-height: 1.2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.flow-kpi__value.is-warn {
  color: var(--warning-ink);
}
.flow-kpi__hint {
  font-size: 10px;
  color: var(--text-weak);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.flow-board {
  display: flex;
  gap: var(--flow-gap);
  overflow-x: auto;
  padding-bottom: 8px;
  min-height: 320px;
}
.flow-board--loading {
  gap: var(--flow-gap);
}
.flow-skeleton-col {
  flex: none;
  width: var(--flow-col);
  height: 320px;
  border-radius: var(--r-card);
  background: var(--bg-hover);
  animation: flow-pulse 1.2s ease-in-out infinite;
}
@keyframes flow-pulse {
  0%,
  100% {
    opacity: 0.5;
  }
  50% {
    opacity: 1;
  }
}
.flow-footnote {
  margin: 12px 0 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--text-weak);
}
@media (max-width: 900px) {
  .flow-kpi {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    height: auto;
  }
  .flow-kpi__item {
    min-height: 56px;
    border-right: 0;
    border-bottom: 1px solid var(--divider);
  }
}
</style>
