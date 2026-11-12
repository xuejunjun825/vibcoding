<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import CrossTable from '@/components/analytics/CrossTable.vue'
import ChartView from '@/components/analytics/ChartView.vue'
import DataStamp from '@/components/analytics/DataStamp.vue'
// 范围 chips 里的筛选值只译回显（提交值仍是 code），词典本体在 stores/dict.ts
import { valueLabel } from '@/components/analytics/labels'
import PinLimitDialog from '@/components/analytics/PinLimitDialog.vue'
import ShareDialog from '@/components/analytics/ShareDialog.vue'
import SlotBoard from '@/components/reports/SlotBoard.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'
import {
  buildChartModel,
  CHART_VIEW_MODES,
  chartPlanFor,
  type ChartViewType,
} from '@/components/analytics/chartModel'
import { definitionProblems } from '@/components/reports/limits'
import {
  cardLimitCards,
  copyReport,
  createReport,
  exportReport,
  fetchDatasets,
  getReport,
  pinReport,
  REPORT_ERROR_TEXT,
  runReport,
  updateReport,
} from '@/api/analytics'
import type {
  ChartType,
  CrossTab,
  Dataset,
  PortalCardRef,
  ReportDefinition,
  ReportDetail,
} from '@/api/analytics'
import { ApiError } from '@/api/client'
import { useAnalyticsFeed } from '@/composables/useAnalyticsFeed'
import { filterToQuery, PRESET_LABEL, useAnalyticsStore } from '@/stores/analytics'
import '@/views/analytics/shared.css'

/**
 * /reports/:id 报表编辑器（AC-8/11/12/13/14/15）。
 *
 * `/reports/new` 与 `/reports/:id` 共用本组件：AppShell 以 `route.path` 作为 `<component>` 的 key，
 * 所以 `/reports/new` → `/reports/{id}` 会重建实例；若 key 不变（已保存的报表再保存），
 * `load()` 仍必须能随 `route.params.id` 重入——两处都要成立，否则保存后预览空白或重复取数。
 */
const route = useRoute()
const router = useRouter()
const store = useAnalyticsStore()

store.syncFromQuery(route)
watch(
  () => route.query,
  () => store.syncFromQuery(route),
)

const idParam = computed(() => String(route.params.id ?? 'new'))
const isNew = computed(() => idParam.value === 'new')

const datasets = ref<Dataset[]>([])
const datasetsError = ref('')
const report = ref<ReportDetail | null>(null)
const notFound = ref(false)
const name = ref('')
const definition = ref<ReportDefinition>({
  datasetId: '',
  rows: [],
  columns: [],
  measures: [],
  filters: [],
})
const chartType = ref<ChartType>('TABLE')
const chartView = ref<ChartViewType>('TABLE')
const transpose = ref(false)
const dirty = ref(false)
const saving = ref(false)
const exportFormat = ref<'xlsx' | 'csv'>('xlsx')
const formError = ref('')
const loadError = ref<ApiError | null>(null)

const shareOpen = ref(false)
const pinLimitOpen = ref(false)
const pinLimitCards = ref<PortalCardRef[]>([])

let hydrating = false

const dataset = computed(() => datasets.value.find((item) => item.code === definition.value.datasetId) ?? null)
const rowDims = computed(() => definition.value.rows.length)
const colDims = computed(() => definition.value.columns.length)
const plan = computed(() => chartPlanFor(chartView.value, rowDims.value, colDims.value))
const canRun = computed(() => !isNew.value && Boolean(report.value))

/**
 * 预览取数走全仓唯一的取数原语（TECHNICAL §5.1 / D7）：300ms 合并、abort 旧请求、
 * 丢弃过期响应都由 `useAnalyticsFeed` 保证，编辑器只声明「取什么」和「何时允许取」。
 * `load({ debounce: true })` = 槽位/定义变化；`reload()` = 用户点运行/保存后重跑。
 */
const feed = useAnalyticsFeed<CrossTab>(
  (signal) => {
    const id = report.value?.id
    // enabled() 已拦住「未保存」的情形；这里只是把该前置条件交给类型系统
    if (!id) throw new ApiError('REPORT_NOT_SAVED', '先保存以生成报表，再运行预览', 0)
    return runReport(id, dirty.value ? { definition: definition.value } : undefined, store.query, signal)
  },
  { immediate: false, enabled: () => canRun.value },
)
const runResult = computed(() => feed.data.value)
const running = computed(() => feed.status.value === 'loading')
const runError = computed(() => {
  const error = feed.error.value
  if (!error) return ''
  return REPORT_ERROR_TEXT[error.code] ?? error.message
})
/** 当前维度下该图表不可用时不改写用户选择，改按表格呈现并把原因写在提示条（AC-13「给原因」） */
const effectiveView = computed<ChartViewType>(() => (plan.value.disabled ? 'TABLE' : chartView.value))
const viewNotice = computed(() => {
  if (chartView.value === 'TABLE' || !plan.value.disabled) return ''
  return `「${viewLabel(chartView.value)}」在当前维度下不可用：${plan.value.reason ?? '维度不匹配'}，已用表格呈现`
})
// 单位由指标口径给出（CrossTab 无 unit 字段），图表轴不自行推断单位
const unitOfMeasure = computed(() => '')

function viewLabel(view: ChartViewType): string {
  return CHART_VIEW_MODES.find((mode) => mode.value === view)?.label ?? view
}

/** 范围 chips（AC-8）：只展示非空筛选，可单个移除；范围条件不随报表保存（G-5） */
const scopeChips = computed(() => {
  const chips: Array<{ key: 'domainId' | 'vendorId' | 'reqType' | 'range'; label: string }> = []
  const filter = store.filter
  if (filter.domainId !== null) chips.push({ key: 'domainId', label: `业务域 #${filter.domainId}` })
  if (filter.vendorId !== null) chips.push({ key: 'vendorId', label: `厂商 #${filter.vendorId}` })
  if (filter.reqType) chips.push({ key: 'reqType', label: `需求类型 ${valueLabel('type', filter.reqType)}` })
  chips.push({ key: 'range', label: `${PRESET_LABEL[filter.preset]} ${filter.from} ~ ${filter.to}` })
  return chips
})

async function loadDatasets(): Promise<void> {
  try {
    const response = await fetchDatasets()
    datasets.value = response.items ?? []
  } catch (cause) {
    datasetsError.value = cause instanceof ApiError ? cause.message : String(cause)
  }
}

function emptyDefinition(datasetId: string): ReportDefinition {
  return { datasetId, rows: [], columns: [], measures: [], filters: [] }
}

/** 选中数据集时给一个可运行的最小定义（1 行维 + 1 值），字段仍全部来自数据集响应 */
function defaultDefinition(code: string): ReportDefinition {
  const fields = datasets.value.find((item) => item.code === code)?.fields ?? []
  const row = fields.find((field) => field.kind === 'DIM' || field.kind === 'TIME')
  const measure = fields.find((field) => field.kind === 'MEASURE')
  return {
    datasetId: code,
    rows: row ? [row.key] : [],
    columns: [],
    measures: measure ? [measure.key] : [],
    filters: [],
  }
}

function applyDefinition(next: ReportDefinition | null | undefined): void {
  hydrating = true
  definition.value = next
    ? {
        datasetId: next.datasetId ?? '',
        rows: [...(next.rows ?? [])],
        columns: [...(next.columns ?? [])],
        measures: [...(next.measures ?? [])],
        filters: (next.filters ?? []).map((filter) => ({ ...filter })),
      }
    : emptyDefinition('')
  // deep watcher 在下一个 tick 才跑：同步复位会让「装载」被误判成用户改动（dirty 永真、离开时弹确认）
  void nextTick(() => {
    hydrating = false
  })
}

function resetRun(): void {
  feed.data.value = null
  feed.error.value = null
}

async function load(): Promise<void> {
  formError.value = ''
  store.noteError('')
  if (isNew.value) {
    report.value = null
    notFound.value = false
    name.value = ''
    const requested = String(route.query.dataset ?? '')
    const code = datasets.value.some((item) => item.code === requested)
      ? requested
      : (datasets.value[0]?.code ?? '')
    applyDefinition(code ? defaultDefinition(code) : null)
    chartType.value = 'TABLE'
    chartView.value = 'TABLE'
    dirty.value = false
    resetRun()
    return
  }

  const id = Number(idParam.value)
  if (!Number.isInteger(id) || id <= 0) {
    notFound.value = true
    return
  }
  // 保存后 replace 到 /reports/{id}：AppShell 的 <component :key="route.path"> 会重建组件，
  // 由新实例的 load() 取数并预览；同路径（已保存的报表）则本实例自己重跑，避免漏跑
  if (report.value && report.value.id === id) return

  try {
    const detail = await getReport(id)
    report.value = detail
    notFound.value = false
    name.value = detail.name
    applyDefinition(detail.definition)
    chartType.value = detail.chartType ?? 'TABLE'
    chartView.value = detail.chartType ?? 'TABLE'
    dirty.value = false
    resetRun()
    feed.reload()
  } catch (cause) {
    const error = cause instanceof ApiError ? cause : null
    if (error?.status === 404 || error?.code === 'REPORT_NOT_FOUND') {
      notFound.value = true
      return
    }
    loadError.value = error
  }
}

/**
 * 共享成功后回填 detail 的范围字段：不重跑 load()（它会重设 definition/dirty 与预览，
 * 共享不该打断正在编辑的表格），只把服务端最新 scope/scopeRef/权限合并回来，
 * 否则再次打开弹层显示的是旧范围（列表页 afterShared → list.refresh() 的等价物）。
 */
async function refreshShared(): Promise<void> {
  ElMessage.success('共享范围已更新')
  const current = report.value
  if (!current) return
  try {
    const detail = await getReport(current.id)
    if (report.value?.id !== current.id) return
    report.value = {
      ...report.value,
      scope: detail.scope,
      scopeRef: detail.scopeRef ?? null,
      canShare: detail.canShare,
      canWrite: detail.canWrite,
      updatedAt: detail.updatedAt,
    }
  } catch {
    // 回填失败不否定已保存的共享结果：保留原值，下次进入编辑器加载的仍是服务端真值
  }
}

function changeDataset(code: string): void {
  definition.value = defaultDefinition(code)
  chartType.value = 'TABLE'
  chartView.value = 'TABLE'
  resetRun()
}

async function save(): Promise<void> {
  formError.value = ''
  if (report.value && report.value.canWrite === false) {
    formError.value = '你没有修改该报表的权限'
    return
  }
  const trimmed = name.value.trim()
  if (!trimmed) {
    formError.value = '请填写报表名称'
    return
  }
  const problems = definitionProblems(definition.value)
  if (problems.length) {
    formError.value = problems.join('；')
    return
  }
  saving.value = true
  try {
    const body = {
      name: trimmed,
      datasetId: definition.value.datasetId,
      definition: definition.value,
      chartType: chartType.value,
    }
    if (isNew.value) {
      const created = await createReport(body)
      report.value = created
      name.value = created.name
      dirty.value = false
      const target = `/reports/${created.id}`
      // 路径换了 → AppShell 的 key 变 → 组件重建，新实例 load() 会自己取数，这里不能重复发请求
      const remounts = router.currentRoute.value.path !== target
      // replace 必须带上范围 query，否则 chips 被清空、预览参数与 URL 不一致
      await router.replace({ path: target, query: filterToQuery(store.filter) })
      if (!remounts) feed.reload()
    } else if (report.value) {
      const updated = await updateReport(report.value.id, body)
      report.value = updated
      name.value = updated.name
      dirty.value = false
      feed.reload()
    }
    ElMessage.success('已保存')
  } catch (cause) {
    const error = cause instanceof ApiError ? cause : null
    formError.value = error ? REPORT_ERROR_TEXT[error.code] ?? error.message : String(cause)
  } finally {
    saving.value = false
  }
}

async function saveAs(): Promise<void> {
  formError.value = ''
  // 副本只复制**已保存**的定义：有未保存修改时先确认，避免「改了半天，副本却是旧版」
  if (report.value && dirty.value) {
    const ok = window.confirm('有未保存的修改；另存复制的是已保存的版本，是否继续？')
    if (!ok) return
  }
  saving.value = true
  try {
    let created: ReportDetail
    if (report.value) {
      // 已保存的报表走服务端 copy：`X-副本` / `-副本-2` 的唯一化与 PRIVATE 范围由后端保证（AC-14）
      created = await copyReport(report.value.id)
      dirty.value = false
    } else {
      if (!definition.value.datasetId) {
        formError.value = '请选择数据集'
        return
      }
      created = await createReport({
        name: `${name.value.trim() || '未命名报表'}-副本`,
        datasetId: definition.value.datasetId,
        definition: definition.value,
        chartType: chartType.value,
      })
    }
    ElMessage.success('已另存为副本')
    // 路径变化 → 实例重建 → 新实例 load() 重载副本；范围条件一并带过去
    await router.push({ path: `/reports/${created.id}`, query: filterToQuery(store.filter) })
  } catch (cause) {
    const error = cause instanceof ApiError ? cause : null
    formError.value = error ? REPORT_ERROR_TEXT[error.code] ?? error.message : String(cause)
  } finally {
    saving.value = false
  }
}

async function doExport(): Promise<void> {
  if (!report.value) {
    formError.value = '先保存以生成报表，再导出'
    return
  }
  formError.value = ''
  try {
    await exportReport(report.value.id, { ...store.query, format: exportFormat.value })
    ElMessage.success(`已导出 ${exportFormat.value.toUpperCase()}`)
  } catch (cause) {
    const error = cause instanceof ApiError ? cause : null
    formError.value = error ? REPORT_ERROR_TEXT[error.code] ?? error.message : String(cause)
  }
}

/** 导出默认 XLSX；下拉可切 CSV。超过上限（REPORT_EXPORT_LIMIT）时页内提示、不产生文件。 */
function onExportDefault(): void {
  exportFormat.value = 'xlsx'
  void doExport()
}

function onExportCommand(command: string | number | object): void {
  exportFormat.value = command === 'csv' ? 'csv' : 'xlsx'
  void doExport()
}

async function doPin(): Promise<void> {
  if (!report.value) return
  try {
    const result = await pinReport(report.value.id)
    ElMessage.success(`已固定到工作台（${result.roleCode}，共 ${result.cards.length} 张）`)
  } catch (cause) {
    const error = cause instanceof ApiError ? cause : null
    if (error?.code === 'PORTAL_CARD_LIMIT') {
      pinLimitCards.value = cardLimitCards(error).slice(0, 4)
      pinLimitOpen.value = true
      return
    }
    formError.value = error ? REPORT_ERROR_TEXT[error.code] ?? error.message : String(cause)
  }
}

function removeScope(key: 'domainId' | 'vendorId' | 'reqType' | 'range'): void {
  if (key === 'range') {
    store.applyToRoute(router, { preset: '30d' })
    return
  }
  const patch = key === 'reqType' ? { reqType: null } : { [key]: null }
  store.applyToRoute(router, patch)
}

function changeView(value: string | number | boolean): void {
  chartView.value = String(value) as ChartViewType
  if (chartView.value !== 'HEATMAP') chartType.value = chartView.value as ChartType
}

const chartModel = computed(() => buildChartModel(runResult.value ?? { rowHeaders: [], colHeaders: [], rows: [], total: null }, plan.value, unitOfMeasure.value))

watch(
  definition,
  () => {
    if (hydrating) return
    dirty.value = true
    // 槽位/维度变化只是「候选」：300ms 内的连续改动由取数原语合并成一次请求
    feed.load({ debounce: true })
  },
  { deep: true },
)

// 范围 chips 变化即重跑（AC-8：URL 与运行参数同步）由 `useAnalyticsFeed` 内部
// watch(store.query) 负责，编辑器不再持有第二套 queryKey 比对。

watch(chartView, () => {
  if (chartView.value === 'HEATMAP') return
  if (chartType.value !== chartView.value) chartType.value = chartView.value as ChartType
})

watch(idParam, () => void load())

function onBeforeUnload(event: BeforeUnloadEvent): void {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(async () => {
  await loadDatasets()
  await load()
  window.addEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
  // 在途请求与排队中的防抖窗口由 useAnalyticsFeed 的 onScopeDispose 收尾
})

onBeforeRouteLeave(() => {
  if (!dirty.value) return true
  return window.confirm('有未保存的修改，确定离开？')
})

// route.query.dataset 只参与「新建」的默认数据集（保存后由 watch(idParam) 负责重载），故不 watch 整个 query
</script>

<template>
  <div class="an-page" data-page="report-editor">
    <ErrorState v-if="notFound" title="报表不存在" message="它可能已被删除，或你无权查看。" :status="404" :retryable="false">
      <template #actions>
        <el-button size="small" @click="router.push('/reports')">返回列表</el-button>
      </template>
    </ErrorState>

    <template v-else>
      <header class="an-head">
        <div class="ed__title-wrap">
          <el-input v-if="isNew || report?.canWrite !== false" v-model="name" class="ed__name" placeholder="报表名称" maxlength="60" />
          <h1 v-else class="an-head__title">{{ report?.name }}</h1>
          <el-tag v-if="report?.canShare === false" size="small" type="info" effect="plain">只读共享</el-tag>
          <el-tag v-if="dirty" size="small" type="warning" effect="plain">未保存</el-tag>
        </div>
        <div class="an-head__actions">
          <DataStamp :data-as-of="runResult?.dataAsOf" :cache="runResult?.cache" :loading="running" />
          <el-button size="small" data-testid="save-report" :loading="saving" :disabled="report?.canWrite === false" @click="save">保存</el-button>
          <el-button size="small" :loading="saving" :disabled="report?.canWrite === false" @click="saveAs">另存为</el-button>
          <el-dropdown split-button size="small" @click="onExportDefault" @command="onExportCommand">
            <span>导出</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="xlsx">导出 XLSX</el-dropdown-item>
                <el-dropdown-item command="csv">导出 CSV（对外请用 XLSX）</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-tooltip :disabled="canRun" content="先保存以生成报表" placement="top">
            <span>
              <el-button size="small" type="primary" data-testid="run-report" :disabled="!canRun" :loading="running" @click="feed.reload()">运行</el-button>
            </span>
          </el-tooltip>
          <el-button size="small" :disabled="!report" @click="doPin">固定到工作台</el-button>
          <el-button v-if="report?.canShare" size="small" data-testid="share-report" @click="shareOpen = true">共享</el-button>
        </div>
      </header>

      <div class="an-filters ed__scope">
        <span class="an-hint">范围（不随报表保存）：</span>
        <el-tag v-for="chip in scopeChips" :key="chip.key" size="small" closable effect="plain" @close="removeScope(chip.key)">
          {{ chip.label }}
        </el-tag>
        <span v-if="exportFormat === 'csv'" class="an-hint">CSV 为纯文本导出，对外请用 XLSX。</span>
      </div>

      <el-alert v-if="formError" type="error" :closable="false" show-icon :title="formError" class="ed__alert" />
      <el-alert v-if="datasetsError" type="warning" :closable="false" show-icon :title="`数据集不可用：${datasetsError}`" class="ed__alert" />
      <el-alert v-if="loadError" type="error" :closable="false" show-icon :title="loadError.message" class="ed__alert" />

      <div class="an-filters">
        <span class="an-hint">数据集</span>
        <el-select :model-value="definition.datasetId" size="small" class="ed__dataset" placeholder="选择数据集" @update:model-value="(value: string) => changeDataset(value)">
          <el-option v-for="item in datasets" :key="item.code" :label="item.label" :value="item.code" />
        </el-select>
        <el-switch
          v-model="transpose"
          size="small"
          active-text="转置"
          :disabled="effectiveView !== 'TABLE'"
          :title="effectiveView === 'TABLE' ? '行列互换（不重新取数）' : '图表视图下不支持转置'"
        />
        <span class="an-filters__spacer" />
        <span class="an-hint">图表</span>
        <el-radio-group :model-value="chartView" size="small" @update:model-value="changeView">
          <el-radio-button
            v-for="mode in CHART_VIEW_MODES"
            :key="mode.value"
            :value="mode.value"
            :disabled="chartPlanFor(mode.value, rowDims, colDims).disabled"
            :title="chartPlanFor(mode.value, rowDims, colDims).reason ?? mode.label"
          >
            {{ mode.label }}
          </el-radio-button>
        </el-radio-group>
        <span v-if="plan.reason" class="an-hint ed__reason">{{ plan.reason }}</span>
      </div>

      <div class="an-3col ed">
        <div class="ed__slots">
          <SlotBoard v-model="definition" :dataset="dataset" />
        </div>

        <section class="an-card ed__preview" v-loading="running">
          <div class="ed__preview-head">
            <h2 class="an-card__title">预览</h2>
            <span class="an-hint">{{ runResult ? `${runResult.rows.length} 行 × ${runResult.colHeaders.length} 列` : '尚未运行' }}</span>
          </div>

          <EmptyState
            v-if="!canRun"
            variant="empty"
            title="先保存以生成报表"
            description="保存后自动运行一次；之后调整槽位会在 300ms 内合并成一次运行。"
            icon="chart"
            compact
          />
          <ErrorState v-else-if="runError" :message="runError" :retryable="true" compact @retry="feed.reload()" />
          <template v-else-if="runResult">
            <p v-if="viewNotice" class="an-hint ed__notice" data-testid="chart-fallback-notice">{{ viewNotice }}</p>
            <ChartView
              v-if="effectiveView !== 'TABLE'"
              :model="chartModel"
              :stacked="plan.stacked"
              :height="320"
            />
            <CrossTable v-else :tab="runResult" :transpose="transpose" dense />
          </template>
          <p v-else class="an-hint">调整左侧槽位后自动运行；结果最多展示 5000 行。</p>
        </section>
      </div>
    </template>

    <ShareDialog v-model="shareOpen" :report="report" @saved="refreshShared" />
    <PinLimitDialog v-model="pinLimitOpen" :cards="pinLimitCards" />
  </div>
</template>

<style scoped>
.ed {
  align-items: start;
}

.ed__slots {
  grid-column: 1 / span 2;
  min-width: 0;
}

.ed__preview {
  min-height: 320px;
  gap: 8px;
}

.ed__preview-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

/* 图表不可用时的降级说明：不静默改写用户选择，把原因留在预览区 */
.ed__notice {
  margin: 0;
  color: var(--color-warning, #e6a23c);
}

.ed__title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.ed__name {
  width: 320px;
}

.ed__scope {
  gap: 6px;
  flex-wrap: wrap;
}

.ed__dataset {
  width: 180px;
}

.ed__reason {
  color: var(--color-warning, #d97706);
}

.ed__alert {
  margin-bottom: 8px;
}
</style>
