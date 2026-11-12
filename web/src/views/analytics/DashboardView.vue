<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ChartView from '@/components/analytics/ChartView.vue'
import CrossTable from '@/components/analytics/CrossTable.vue'
import DataStamp from '@/components/analytics/DataStamp.vue'
import FilterBar from '@/components/analytics/FilterBar.vue'
import ErrorState from '@/components/ErrorState.vue'
import type { ChartModel, ChartSeries } from '@/components/analytics/chartModel'
import { fetchDashboard } from '@/api/analytics'
import type { Breakdown, CrossTab, Dashboard, QualityLevel } from '@/api/analytics'
import { useAnalyticsFeed } from '@/composables/useAnalyticsFeed'
import { filterToQuery, useAnalyticsStore } from '@/stores/analytics'
import { formatNumber, formatPercent } from '@/utils/format'
import '@/views/analytics/shared.css'

/**
 * /dashboard 数据看板（T6 · TECHNICAL §6.2 / AC-6、AC-8）。
 *
 * 五块（需求量多维 / 周期与 P90 / 人天投入 / 厂商交付排名 / 交付质量）顺序写死在模板里，
 * 数据为空只换块内空态，不隐藏区块、不改顺序（AC-6 的「顺序不随空数据变化」）。
 * 五块共用**一次** `GET /analytics/dashboard` 响应，所以 `dataAsOf` / `cache` 天然只有一份。
 */
const store = useAnalyticsStore()
const route = useRoute()
const router = useRouter()

store.syncFromQuery(route)
watch(
  () => route.query,
  () => store.syncFromQuery(route),
)

/** 趋势粒度只影响“按周/按月”聚合，PRD 未要求进 URL，故留本地（TECHNICAL §6.2） */
const bucket = ref<'WEEK' | 'MONTH'>('WEEK')
const feed = useAnalyticsFeed<Dashboard>(
  (signal) => fetchDashboard({ ...store.query, bucket: bucket.value }, signal),
  { immediate: true },
)
watch(bucket, () => feed.reload())

const dashboard = computed(() => feed.data.value)
const loading = computed(() => feed.status.value === 'loading')
const failed = computed(() => feed.status.value === 'error')
const bucketLabel = computed(() => (bucket.value === 'WEEK' ? '按周' : '按月'))

/* ------------------------------------------------------------ 需求量多维 */

/** 后端当前只返回一个 breakdown（domainStatus），取第一个即“多维交叉表”这一块 */
const breakdown = computed(() => dashboard.value?.breakdowns?.[0] ?? null)
const breakdownLabel = computed(() => breakdown.value?.label || '需求量 · 域 × 状态')

/**
 * 服务端 `Breakdown` 还没声明 `rowHeaders`（r14 收口遗留，见交付说明的 OUT-OF-SCOPE）：
 * 这里按「若已下发则透传」读取，后端补字段后本视图零改动生效；未下发时退回 `[]`，
 * 交叉表随即退回 `rows[].key`（当前行维是 domain，中文名，没有可见差异）。
 */
type BreakdownWithRowHeaders = Breakdown & { rowHeaders?: string[] }

/**
 * `Breakdown` → `CrossTable` 需要的 `CrossTab` 形状（纯派生，不发请求）。
 *
 * `total` 故意置空：后端 `total.values` 是**每个度量一个总计值**（长度 = 度量数，本页为 1），
 * 并不与 `colHeaders` 逐列对齐；直接塞进表尾会被当成“第一列的值”，把 26 显示到
 * “DELIVERED · 需求总量”下面。总计因此单独用 chip 展示（`totalText`），语义不串。
 */
const matrix = computed<CrossTab | null>(() => {
  const source = breakdown.value as BreakdownWithRowHeaders | null
  if (!source) return null
  return {
    rowHeaders: source.rowHeaders ?? [],
    colHeaders: source.colHeaders,
    rows: source.rows,
    subtotal: source.subtotal,
    total: null,
    groups: source.rows.length,
    truncated: false,
    dataAsOf: dashboard.value?.dataAsOf ?? '',
    cache: dashboard.value?.cache ?? '',
  }
})

const totalText = computed(() => formatNumber(breakdown.value?.total?.values?.[0] ?? null, 0))

/* ------------------------------------------------------------------ 趋势 */

const trendPoints = computed(() => dashboard.value?.trend?.points ?? [])
const latest = computed(() => {
  const points = trendPoints.value
  return points.length > 0 ? points[points.length - 1] : null
})

/**
 * `mode='LINE'`，本期/上期两条系列；上期整列都是空（没有上一周期样本）时不加这条系列，
 * 免得图例里留一个永远画不出线的“上期”。
 */
const trendModel = computed<ChartModel>(() => {
  const points = trendPoints.value
  const series: ChartSeries[] = [{ name: '本期', values: points.map((point) => point.current ?? null) }]
  if (points.some((point) => typeof point.previous === 'number')) {
    series.push({ name: '上期', values: points.map((point) => point.previous ?? null) })
  }
  return {
    mode: 'LINE',
    categories: points.map((point) => point.bucket),
    series,
    heat: { x: [], y: [], cells: [] },
    // 后端 `trend.unit` 是 bucket 单位（WEEK/MONTH），不是数量单位，不能直接当后缀；
    // 趋势画的是需求条数，单位固定“个”。
    unit: '个',
  }
})

/** 环比方向：服务端算好 delta（0.021 = +2.1%），前端只加箭头，不二次计算 */
const deltaArrow = computed(() => {
  const delta = latest.value?.delta
  if (delta === null || delta === undefined) return ''
  if (delta > 0) return '↑ '
  if (delta < 0) return '↓ '
  return '→ '
})

/* -------------------------------------------------------- 周期 / 人天 / 排名 */

const segments = computed(() => dashboard.value?.segments ?? [])
const effort = computed(() => dashboard.value?.effort ?? null)
const effortUnit = computed(() => effort.value?.unit || '人天')

/** 后端已按 rank 排好，这里再显式排序一次，保证“按 rank 升序”不依赖数组顺序 */
const rankRows = computed(() =>
  [...(dashboard.value?.vendorRank ?? [])].sort((left, right) => left.rank - right.rank),
)

const qualityCards = computed(() => dashboard.value?.quality?.cards ?? [])

const LEVEL_LABEL: Record<QualityLevel, string> = {
  GOOD: '达标',
  WARN: '预警',
  BAD: '不达标',
  NONE: '无样本',
}

const LEVEL_TAG: Record<QualityLevel, 'success' | 'warning' | 'danger' | 'info'> = {
  GOOD: 'success',
  WARN: 'warning',
  BAD: 'danger',
  NONE: 'info',
}

/** 天数空值：`null` → 「—」，不写成「— 天」（AC-6 的 null 语义） */
function days(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${formatNumber(value, 1)} 天`
}

/* -------------------------------------------------------------- 交互出口 */

/** AC-8：把当前筛选原样带进编辑器，范围条件只随本次运行生效（不持久化，G-5） */
function openInReports(): void {
  void router.push({
    path: '/reports/new',
    query: { dataset: 'requirement', ...filterToQuery(store.filter) },
  })
}
</script>

<template>
  <div class="an-page" data-page="dashboard">
    <header class="an-head">
      <div>
        <h1 class="an-head__title">数据看板</h1>
        <p class="an-head__sub">需求量、周期、投入、厂商与质量共用同一份筛选与同一次请求</p>
      </div>
      <div class="an-head__actions">
        <DataStamp :data-as-of="dashboard?.dataAsOf" :cache="dashboard?.cache" :loading="loading" />
        <el-radio-group v-model="bucket" size="small" aria-label="趋势粒度">
          <el-radio-button value="WEEK">按周</el-radio-button>
          <el-radio-button value="MONTH">按月</el-radio-button>
        </el-radio-group>
        <el-button size="small" :loading="loading" @click="feed.reload()">刷新</el-button>
        <el-button size="small" type="primary" plain @click="openInReports">在报表中打开</el-button>
      </div>
    </header>

    <div class="an-filters">
      <FilterBar :fields="['domain', 'vendor', 'preset', 'reqType']" />
    </div>

    <ErrorState
      v-if="failed"
      :message="feed.error.value?.message"
      :code="feed.error.value?.code"
      :status="feed.error.value?.status"
      @retry="feed.reload()"
    />

    <div v-else v-loading="loading && !dashboard" class="dash" :aria-busy="loading">
      <!-- 1 需求量多维：交叉表 + 趋势（同一块的两种“量”视角） -->
      <section class="an-card" data-block="breakdown">
        <h2 class="an-card__title">需求量多维</h2>
        <div class="dash__meta">
          <span class="an-hint">{{ breakdownLabel }}</span>
          <span class="dash__chip">总计 {{ totalText }}</span>
        </div>

        <CrossTable v-if="matrix" :tab="matrix" />
        <p v-else class="an-empty">暂无数据</p>

        <div class="dash__trend">
          <div class="dash__trend-head">
            <span class="dash__trend-title">需求量趋势（{{ bucketLabel }}）</span>
            <span v-if="latest" class="an-hint">
              {{ latest.bucket }} · 本期 {{ formatNumber(latest.current, 0) }} · 上期
              {{ formatNumber(latest.previous, 0) }} · 环比
              <strong class="dash__delta">{{ deltaArrow }}{{ formatPercent(latest.delta, 1) }}</strong>
            </span>
            <span v-else class="an-hint">暂无趋势数据</span>
          </div>
          <ChartView :model="trendModel" :height="240" />
        </div>
      </section>

      <!-- 2 周期与 P90 -->
      <section class="an-card" data-block="cycle">
        <h2 class="an-card__title">周期与 P90</h2>
        <p class="an-hint">按需求结束周期分 5 段；段内均值与 P90 无样本时显示「—」</p>
        <div class="an-grid an-grid--kpi">
          <div
            v-for="segment in segments"
            :key="segment.key"
            class="dash__tile"
            :class="{ 'is-tail': segment.tail }"
          >
            <div class="dash__tile-head">
              <span class="dash__tile-label">{{ segment.label }}</span>
              <el-tag v-if="segment.tail" size="small" type="warning" effect="plain">长尾</el-tag>
            </div>
            <p class="dash__tile-value">
              {{ formatNumber(segment.count, 0) }}<em class="dash__unit">个</em>
            </p>
            <p class="dash__tile-foot">
              均值 {{ days(segment.avg) }} · P90 {{ days(segment.p90) }}
            </p>
          </div>
          <p v-if="segments.length === 0" class="an-empty">暂无数据</p>
        </div>
      </section>

      <!-- 3 人天投入 -->
      <section class="an-card" data-block="effort">
        <h2 class="an-card__title">人天投入</h2>
        <div class="an-grid an-grid--kpi">
          <div class="dash__tile">
            <span class="dash__tile-label">预估</span>
            <p class="dash__tile-value">
              {{ formatNumber(effort?.estimate, 1) }}
              <em v-if="effort?.estimate !== null && effort?.estimate !== undefined" class="dash__unit">
                {{ effortUnit }}
              </em>
            </p>
          </div>
          <div class="dash__tile">
            <span class="dash__tile-label">实际</span>
            <p class="dash__tile-value">
              {{ formatNumber(effort?.actual, 1) }}
              <em v-if="effort?.actual !== null && effort?.actual !== undefined" class="dash__unit">
                {{ effortUnit }}
              </em>
            </p>
          </div>
          <div class="dash__tile">
            <span class="dash__tile-label">偏差</span>
            <p class="dash__tile-value">
              {{ formatNumber(effort?.deviation, 1) }}
              <em v-if="effort?.deviation !== null && effort?.deviation !== undefined" class="dash__unit">
                {{ effortUnit }}
              </em>
            </p>
            <p class="dash__tile-foot">实际 − 预估（服务端计算）</p>
          </div>
          <div class="dash__tile">
            <span class="dash__tile-label">缺预估</span>
            <p class="dash__tile-value">
              {{ formatNumber(effort?.missingEstimate, 0) }}<em class="dash__unit">个</em>
            </p>
            <p class="dash__tile-foot">无预估工时的需求数</p>
          </div>
        </div>
        <p class="an-hint">空值一律「—」，不补 0。</p>
      </section>

      <!-- 4 厂商交付排名 -->
      <section class="an-card" data-block="vendor">
        <h2 class="an-card__title">厂商交付排名</h2>
        <p class="an-hint">按完成数排名；本厂商所在行高亮，名次不变</p>
        <div class="an-table__scroll">
          <table class="an-table">
            <thead>
              <tr>
                <th>排名</th>
                <th>厂商</th>
                <th class="dash__num">完成</th>
                <th class="dash__num">准时率</th>
                <th class="dash__num">平均周期</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in rankRows"
                :key="`${row.rank}-${row.vendorId ?? row.vendorName}`"
                :class="{ 'an-table__row--mine': row.mine }"
              >
                <td class="dash__rank">{{ row.rank }}</td>
                <td>
                  <span class="dash__vendor">{{ row.vendorName }}</span>
                  <el-tag v-if="row.mine" size="small" effect="plain" class="dash__mine-tag">
                    本厂商
                  </el-tag>
                </td>
                <td class="dash__num">{{ formatNumber(row.completed, 0) }}</td>
                <td class="dash__num">{{ formatPercent(row.onTimeRate, 1) }}</td>
                <td class="dash__num">{{ days(row.avgCycle) }}</td>
              </tr>
              <tr v-if="rankRows.length === 0">
                <td colspan="5" class="an-empty">暂无数据</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 5 交付质量 -->
      <section class="an-card" data-block="quality">
        <h2 class="an-card__title">交付质量</h2>
        <p class="an-hint">等级由服务端按样本判定；无样本的指标标「无样本」，不参与达标判断</p>
        <div class="an-grid an-grid--kpi">
          <div v-for="card in qualityCards" :key="card.key" class="dash__tile">
            <div class="dash__tile-head">
              <span class="dash__tile-label">{{ card.label }}</span>
              <el-tag size="small" :type="LEVEL_TAG[card.level]" effect="plain">
                {{ LEVEL_LABEL[card.level] }}
              </el-tag>
            </div>
            <p class="dash__tile-value" :class="{ 'is-muted': card.level === 'NONE' }">
              {{ formatNumber(card.value, card.digits) }}
              <em v-if="card.value !== null && card.value !== undefined" class="dash__unit">
                {{ card.unit }}
              </em>
            </p>
            <p class="dash__tile-foot">样本 {{ formatNumber(card.sample, 0) }} 条</p>
          </div>
          <p v-if="qualityCards.length === 0" class="an-empty">暂无数据</p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.dash {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 180px;
}

.dash__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: -4px;
}

.dash__chip {
  padding: 1px 8px;
  font-size: 12px;
  line-height: 20px;
  color: var(--text-body);
  background: var(--bg-selected);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-variant-numeric: tabular-nums;
}

/* ---------- 趋势 ---------- */

.dash__trend {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-top: 4px;
  border-top: 1px dashed var(--divider);
}

.dash__trend-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.dash__trend-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}

.dash__delta {
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}

/* ---------- KPI 小卡（周期 / 人天 / 质量共用） ---------- */

.dash__tile {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding: 10px 12px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  transition: border-color var(--t-color) var(--ease), box-shadow var(--t-color) var(--ease);
}

.dash__tile:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-card-hover);
}

.dash__tile.is-tail {
  border-color: var(--color-warning);
  background: var(--color-warning-bg);
}

.dash__tile-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 20px;
}

.dash__tile-label {
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
}

.dash__tile-value {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  line-height: 28px;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}

.dash__tile-value.is-muted {
  color: var(--text-weak);
}

.dash__unit {
  margin-left: 3px;
  font-size: 12px;
  font-style: normal;
  font-weight: 400;
  color: var(--text-muted-aa);
}

.dash__tile-foot {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}

/* ---------- 排名表 ---------- */

.dash__num {
  text-align: right;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.dash__rank {
  width: 56px;
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}

.dash__vendor {
  margin-right: 6px;
}

.dash__mine-tag {
  vertical-align: 1px;
}
</style>
