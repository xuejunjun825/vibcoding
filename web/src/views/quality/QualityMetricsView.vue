<script setup lang="ts">
/**
 * 质量指标（DESIGN Q12 / AC-8）。
 *
 * 5 个指标全部由服务端计算（MetricFormulas 唯一实现），页面只做展示与切片，
 * 不在前端做任何除法/加权；null 表示“无可计算数据”，显示 — 而不是 0。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { metricApi } from '@/api/quality'
import { listReleases } from '@/api/req'
import { listDictionaryDomains, listDictionaryVendors } from '@/api/dictionaries'
import { ApiError } from '@/api/client'
import type { DictionaryDomain, DictionaryVendor } from '@/api/dictionaries'
import type { QualityMetricValue, QualityMetrics, ReleaseRow } from '@/api/types'
import MetricCard from '@/components/MetricCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import ConclusionBar from '@/components/quality/ConclusionBar.vue'
import { formatNumber, formatPercent } from '@/utils/format'
import '@/views/deliver/shared.css'

const router = useRouter()

const data = ref<QualityMetrics | null>(null)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const releases = ref<ReleaseRow[]>([])
const domains = ref<DictionaryDomain[]>([])
const vendors = ref<DictionaryVendor[]>([])

const releaseId = ref<number | null>(null)
const domainId = ref<number | null>(null)
const vendorId = ref<number | null>(null)
const month = ref('')

const COLORS: Record<string, string> = {
  FPY: 'var(--color-success)',
  REGRESSION_PASS_RATE: 'var(--color-primary)',
  ESCAPE_RATE: 'var(--color-danger)',
  POST_RELEASE_DEFECTS_72H: 'var(--color-warning)',
  ROLLBACK_RATE: 'var(--color-danger)',
}

const drillOpen = ref(false)
const drillMetric = ref<QualityMetricValue | null>(null)

const metrics = computed<QualityMetricValue[]>(() => data.value?.metrics ?? [])
const rateMetrics = computed(() =>
  metrics.value.filter((metric) => String(metric.unit ?? '').includes('%')),
)
const hasData = computed(() =>
  metrics.value.some((metric) => metric.value != null),
)
const currentRelease = computed(
  () => releases.value.find((release) => release.id === releaseId.value) ?? null,
)
const sliceText = computed(() => {
  const parts: string[] = []
  if (domainId.value) parts.push(domains.value.find((item) => item.id === domainId.value)?.name ?? `域 #${domainId.value}`)
  if (vendorId.value) parts.push(vendors.value.find((item) => item.id === vendorId.value)?.name ?? `厂商 #${vendorId.value}`)
  if (currentRelease.value) parts.push(`${currentRelease.value.code} · ${currentRelease.value.name}`)
  if (month.value) parts.push(month.value)
  return parts.length ? parts.join(' / ') : '全部数据'
})
function messageOf(err: unknown): string {
  if (err instanceof ApiError) return err.message || err.code || '请求失败'
  return err instanceof Error ? err.message : '请求失败'
}

function rangeParams(): { from?: string; to?: string } {
  if (!month.value) return {}
  const [year, mon] = month.value.split('-').map(Number)
  if (!year || !mon) return {}
  const from = `${month.value}-01`
  const lastDay = new Date(year, mon, 0).getDate()
  return { from, to: `${month.value}-${String(lastDay).padStart(2, '0')}` }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    data.value = await metricApi.quality({
      releaseId: releaseId.value ?? undefined,
      domainId: domainId.value ?? undefined,
      vendorId: vendorId.value ?? undefined,
      ...rangeParams(),
    })
  } catch (err) {
    error.value = {
      message: messageOf(err),
      code: err instanceof ApiError ? err.code : undefined,
      status: err instanceof ApiError ? err.status : undefined,
    }
  } finally {
    loading.value = false
  }
}

async function loadOptions(): Promise<void> {
  const results = await Promise.allSettled([listReleases({ page: 1, size: 100 }), listDictionaryDomains(), listDictionaryVendors()])
  if (results[0].status === 'fulfilled') releases.value = results[0].value.items ?? []
  if (results[1].status === 'fulfilled') domains.value = results[1].value ?? []
  if (results[2].status === 'fulfilled') vendors.value = results[2].value ?? []
}

function definitionOf(metric: QualityMetricValue) {
  return {
    code: metric.code,
    name: metric.name,
    formula: metric.formula || '—',
    caliberNote: metric.caliberNote || '—',
    version: metric.version ?? undefined,
  }
}

function displayValue(metric: QualityMetricValue): number | string | null {
  if (metric.value == null) return null
  return metric.value
}

function valueText(metric: QualityMetricValue): string {
  if (metric.value == null) return '—'
  if (String(metric.unit ?? '').includes('%')) return formatPercent(metric.value)
  return formatNumber(metric.value, 0)
}

function barWidth(metric: QualityMetricValue): string {
  if (metric.value == null) return '0%'
  const percent = metric.value <= 1 ? metric.value * 100 : metric.value
  return `${Math.max(0, Math.min(100, percent))}%`
}

function openDrill(metric: QualityMetricValue): void {
  drillMetric.value = metric
  drillOpen.value = true
}

function exportCsv(): void {
  if (!metrics.value.length) return
  const header = ['指标编码', '指标名称', '值', '单位', '版本', '公式', '口径']
  const lines = [header, ...metrics.value.map((metric) => [
    metric.code,
    metric.name,
    metric.value == null ? '' : String(metric.value),
    metric.unit ?? '',
    String(metric.version ?? ''),
    metric.formula ?? '',
    (metric.caliberNote ?? '').replace(/[\r\n]+/g, ' '),
  ])]
  const csv = lines.map((row) => row.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(',')).join('\r\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `质量指标_${month.value || '全部'}.csv`
  link.click()
  URL.revokeObjectURL(url)
  ElMessage.success('已导出当前切片指标')
}

function goRelease(): void {
  if (!releaseId.value) return
  void router.push({ name: 'release-detail', params: { id: releaseId.value } })
}

onMounted(async () => {
  await loadOptions()
  await load()
})
</script>

<template>
  <div class="dl-page metrics-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1>质量指标</h1>
      </div>
      <div class="dl-head__actions">
        <el-button :disabled="!metrics.length" @click="exportCsv">导出</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <p class="dl-hint">
      5 项指标由服务端按统一公式计算，切片条件对全部指标同时生效；<strong>无数据时显示 —（不是 0）</strong>。
    </p>

    <div class="filter-bar metrics-filters">
      <el-select v-model="domainId" placeholder="全部业务域" clearable filterable style="width: 160px" @change="load">
        <el-option v-for="domain in domains" :key="domain.id" :value="domain.id" :label="domain.name" />
      </el-select>
      <el-select v-model="vendorId" placeholder="全部厂商" clearable filterable style="width: 160px" @change="load">
        <el-option v-for="vendor in vendors" :key="vendor.id" :value="vendor.id" :label="vendor.name" />
      </el-select>
      <el-select v-model="releaseId" placeholder="全部版本" clearable filterable style="width: 190px" @change="load">
        <el-option v-for="release in releases" :key="release.id" :value="release.id" :label="`${release.code} · ${release.name}`" />
      </el-select>
      <el-date-picker
        v-model="month"
        type="month"
        value-format="YYYY-MM"
        placeholder="全部月份"
        style="width: 150px"
        @change="load"
      />
      <span class="dl-filters__spacer" />
      <span class="metrics-slice">切片：{{ sliceText }}</span>
    </div>

    <ErrorState
      v-if="error"
      :message="error.message"
      :code="error.code"
      :status="error.status"
      @retry="load"
    />
    <div v-else-if="loading && !data" class="metrics-loading" aria-busy="true">
      <div v-for="index in 5" :key="index" class="skeleton metrics-loading__card" />
    </div>
    <template v-else>
      <EmptyState
        v-if="!metrics.length"
        icon="chart"
        title="没有可计算的指标"
        description="当前切片内没有执行记录或缺陷数据；换一个版本/月份再试。"
      />
      <template v-else>
        <div class="metrics-grid">
          <MetricCard
            v-for="metric in metrics"
            :key="metric.code"
            :label="metric.name"
            :value="displayValue(metric)"
            :unit="metric.unit ?? undefined"
            :definition="definitionOf(metric)"
            :tone="metric.value == null ? 'default' : metric.code === 'ESCAPE_RATE' || metric.code === 'ROLLBACK_RATE' ? 'warning' : 'default'"
            drillable
            @drill="openDrill(metric)"
          />
        </div>

        <div v-if="!hasData" class="metrics-nodata">
          当前切片内 5 项指标均无可计算数据（分母为 0）。这不代表质量达标，请先补齐执行与缺陷记录。
        </div>

        <section class="surface surface--pad metrics-chart">
          <div class="metrics-chart__head">
            <h2 class="t-block-title">比率指标对比</h2>
            <span class="t-aux">数值来自服务端；无数据的指标不绘制</span>
          </div>
          <ul v-if="rateMetrics.length" class="metrics-bars">
            <li v-for="metric in rateMetrics" :key="metric.code" class="metrics-bars__row">
              <span class="metrics-bars__label">{{ metric.name }}</span>
              <div class="metrics-bars__track">
                <div
                  v-if="metric.value != null"
                  class="metrics-bars__bar"
                  :style="{ width: barWidth(metric), background: COLORS[metric.code] || 'var(--color-primary)' }"
                />
                <span v-else class="metrics-bars__none">无数据</span>
              </div>
              <span class="metrics-bars__value">{{ valueText(metric) }}</span>
            </li>
          </ul>
          <p v-else class="t-aux metrics-chart__empty">当前切片没有比率类指标。</p>
        </section>

        <section class="surface surface--flush metrics-table-wrap">
          <div class="metrics-table-head">
            <h2 class="t-block-title">指标明细（可读表格）</h2>
            <span class="t-aux">与上方卡片同源，供复制/核对</span>
          </div>
          <table class="dl-table metrics-table">
            <thead>
              <tr>
                <th>指标</th>
                <th>编码</th>
                <th class="is-num">值</th>
                <th>单位</th>
                <th class="is-num">版本</th>
                <th>口径说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="metric in metrics" :key="metric.code">
                <td>{{ metric.name }}</td>
                <td class="t-code">{{ metric.code }}</td>
                <td class="is-num">{{ valueText(metric) }}</td>
                <td>{{ metric.unit || '—' }}</td>
                <td class="is-num">v{{ metric.version ?? '—' }}</td>
                <td class="metrics-table__note">{{ metric.caliberNote || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </template>
    </template>

    <el-drawer v-model="drillOpen" :title="drillMetric?.name || '指标明细'" size="560px">
      <template v-if="drillMetric">
        <ConclusionBar
          :title="valueText(drillMetric)"
          :tone="drillMetric.value == null ? 'neutral' : 'success'"
          :kicker="drillMetric.code"
          :hint="`切片：${sliceText}`"
          dense
        />
        <dl class="metrics-drill">
          <div>
            <dt>指标编码</dt>
            <dd class="t-code">{{ drillMetric.code }}</dd>
          </div>
          <div>
            <dt>公式</dt>
            <dd>{{ drillMetric.formula || '—' }}</dd>
          </div>
          <div>
            <dt>口径说明</dt>
            <dd>{{ drillMetric.caliberNote || '—' }}</dd>
          </div>
          <div>
            <dt>指标版本</dt>
            <dd>v{{ drillMetric.version ?? '—' }}</dd>
          </div>
          <div v-if="drillMetric.drillQuery">
            <dt>下钻查询</dt>
            <dd class="t-code metrics-drill__query">{{ drillMetric.drillQuery }}</dd>
          </div>
        </dl>
        <div class="metrics-drill__actions">
          <el-button v-if="releaseId" @click="goRelease">查看版本详情</el-button>
          <el-button @click="router.push({ name: 'quality-gates' })">查看质量门</el-button>
          <el-button @click="router.push({ name: 'test-reports' })">查看测试报告</el-button>
        </div>
        <p class="t-aux metrics-drill__hint">
          指标由服务端统一计算；明细下钻接口尚未提供，请通过上方入口在版本详情/报告中核对分子与分母。
        </p>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.metrics-slice {
  font-size: 12px;
  color: var(--text-muted-aa);
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}
.metrics-loading {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}
.metrics-loading__card {
  height: 108px;
  border-radius: var(--r-card);
}
.metrics-nodata {
  margin-bottom: 12px;
  padding: 8px 12px;
  border-left: 3px solid var(--color-warning);
  background: var(--color-warning-bg);
  border-radius: 0 var(--r-ctl) var(--r-ctl) 0;
  font-size: 12px;
  color: var(--warning-ink);
}
.metrics-chart {
  margin-bottom: 12px;
}
.metrics-chart__head,
.metrics-table-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.metrics-bars {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 10px;
}
.metrics-bars__row {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr) 80px;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}
.metrics-bars__label {
  color: var(--text-body);
}
.metrics-bars__track {
  position: relative;
  height: 8px;
  border-radius: var(--r-pill);
  background: var(--bg-hover);
  overflow: hidden;
}
.metrics-bars__bar {
  height: 100%;
  border-radius: var(--r-pill);
  transition: width var(--t-expand) var(--ease);
}
.metrics-bars__none {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  padding-left: 4px;
  font-size: 10px;
  color: var(--text-weak);
}
.metrics-bars__value {
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: var(--text-title);
}
.metrics-chart__empty {
  margin: 0;
}
.metrics-table-wrap {
  overflow: hidden;
}
.metrics-table-head {
  padding: 14px 16px 0;
}
.metrics-table .is-num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.metrics-table__note {
  max-width: 420px;
  color: var(--text-muted-aa);
  font-size: 12px;
}
.metrics-drill {
  display: grid;
  gap: 12px;
  margin: 16px 0;
  padding: 0;
  font-size: 12px;
}
.metrics-drill div {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 12px;
}
.metrics-drill dt {
  color: var(--text-muted-aa);
}
.metrics-drill dd {
  margin: 0;
  color: var(--text-body);
  line-height: 1.6;
}
.metrics-drill__query {
  word-break: break-all;
}
.metrics-drill__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.metrics-drill__hint {
  margin-top: 14px;
  line-height: 1.6;
}
@media (max-width: 1200px) {
  .metrics-grid,
  .metrics-loading {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 720px) {
  .metrics-grid,
  .metrics-loading {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
