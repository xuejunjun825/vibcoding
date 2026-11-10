<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { defectBoard } from '@/api/deliver'
import { listReleases } from '@/api/req'
import { listDictionaryDomains, listDictionaryVendors } from '@/api/dictionaries'
import type { BoardMetrics, MetricDefinition, ReleaseRow } from '@/api/types'
import type { DictionaryDomain, DictionaryVendor } from '@/api/dictionaries'
import { usePermission } from '@/composables/usePermission'
import { formatDateTime } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import MetricCard from '@/components/MetricCard.vue'
import ScopeChip from '@/components/ScopeChip.vue'
import '@/views/deliver/shared.css'

/**
 * 质量看板（DESIGN P5 / AC-18）。
 * 四张卡片的数字全部来自服务端 /metrics/defects，定义（公式/版本）随包下发；
 * 点击卡片带着“同一套筛选”下钻到列表，绝不在下钻时偷偷换筛选。
 */
const route = useRoute()
const router = useRouter()
const perm = usePermission()

const metrics = ref<BoardMetrics | null>(null)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)

const filters = reactive({
  domainId: String(route.query.domainId ?? ''),
  vendorId: String(route.query.vendorId ?? ''),
  releaseId: String(route.query.releaseId ?? ''),
  dateFrom: String(route.query.dateFrom ?? ''),
  dateTo: String(route.query.dateTo ?? ''),
})

const releases = ref<ReleaseRow[]>([])
const domains = ref<DictionaryDomain[]>([])
const vendors = ref<DictionaryVendor[]>([])
const canFilterDomain = computed(() => perm.can('org:manage'))

const dateRange = computed<[string, string] | null>({
  get() {
    return filters.dateFrom && filters.dateTo ? [filters.dateFrom, filters.dateTo] : null
  },
  set(value) {
    filters.dateFrom = value?.[0] ?? ''
    filters.dateTo = value?.[1] ?? ''
    void apply()
  },
})

function definitionOf(keyword: string): MetricDefinition | null {
  const list = metrics.value?.definitions ?? []
  const hit =
    list.find((item) => item.code?.toLowerCase().includes(keyword)) ??
    list.find((item) => item.name?.includes(keyword))
  return hit ?? null
}

const defOpenHigh = computed(() => definitionOf('open') ?? definitionOf('未关闭'))
const defDensity = computed(() => definitionOf('density') ?? definitionOf('密度'))
const defReopen = computed(() => definitionOf('reopen') ?? definitionOf('重开'))
const defMttr = computed(() => definitionOf('mttr') ?? definitionOf('修复时长'))

const raw = computed(() => metrics.value?.metrics)

/** 比例型指标的服务端形态可能是 0.083 或 8.3，仅做展示单位判定，不重算数值。 */
function ratioUnit(value: number | string | undefined): string {
  const num = Number(value)
  return Number.isFinite(num) && num > 1 ? '%' : ''
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    metrics.value = await defectBoard({
      domainId: filters.domainId || undefined,
      vendorId: filters.vendorId || undefined,
      releaseId: filters.releaseId || undefined,
      dateFrom: filters.dateFrom || undefined,
      dateTo: filters.dateTo || undefined,
    })
  } catch (err) {
    error.value = {
      message: (err as Error).message || '质量看板加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

function syncUrl(): void {
  const query: Record<string, string> = {}
  for (const [key, value] of Object.entries(filters)) {
    if (value) query[key] = value
  }
  void router.replace({ query })
}

async function apply(): Promise<void> {
  syncUrl()
  await load()
}

function drill(metric: string): void {
  // 下钻只带筛选，不带“转化后的口径”：列表与卡片必须能对上
  void router.push({ name: 'defects', query: { ...route.query, metric } })
}

async function loadOptions(): Promise<void> {
  try {
    const [releasePage, domainList, vendorList] = await Promise.all([
      listReleases({ page: 1, size: 100 }),
      canFilterDomain.value ? listDictionaryDomains() : Promise.resolve([]),
      canFilterDomain.value ? listDictionaryVendors() : Promise.resolve([]),
    ])
    releases.value = releasePage.items ?? []
    domains.value = domainList
    vendors.value = vendorList
  } catch {
    ElMessage.warning('筛选选项加载失败，看板数字不受影响')
  }
}

function reset(): void {
  filters.domainId = ''
  filters.vendorId = ''
  filters.releaseId = ''
  filters.dateFrom = ''
  filters.dateTo = ''
  void apply()
}

onMounted(async () => {
  await Promise.all([load(), loadOptions()])
})
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">质量看板</h1>
        <span class="dl-meta">缺陷四指标 · 口径随接口下发</span>
        <ScopeChip v-if="!canFilterDomain" label="仅统计授权范围" tone="info" />
      </div>
      <div class="dl-head__actions">
        <el-button @click="router.push({ name: 'defects' })">缺陷列表</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="surface surface--pad">
      <div class="dl-filters">
        <el-select
          v-if="canFilterDomain"
          v-model="filters.domainId"
          placeholder="业务域"
          clearable
          filterable
          @change="apply"
        >
          <el-option
            v-for="domain in domains"
            :key="domain.id"
            :label="domain.name"
            :value="String(domain.id)"
          />
        </el-select>
        <el-select
          v-if="canFilterDomain"
          v-model="filters.vendorId"
          placeholder="厂商"
          clearable
          filterable
          @change="apply"
        >
          <el-option
            v-for="vendor in vendors"
            :key="vendor.id"
            :label="vendor.name"
            :value="String(vendor.id)"
          />
        </el-select>
        <el-select v-model="filters.releaseId" placeholder="版本" clearable @change="apply">
          <el-option
            v-for="release in releases"
            :key="release.id"
            :label="release.name"
            :value="String(release.id)"
          />
        </el-select>
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="发现起"
          end-placeholder="发现止"
          style="width: 240px"
        />
        <div class="dl-filters__spacer" />
        <el-button link @click="reset">重置</el-button>
      </div>
    </section>

    <ErrorState
      v-if="error"
      :message="error.message"
      :code="error.code"
      :status="error.status"
      @retry="load"
    />
    <EmptyState
      v-else-if="!loading && !metrics"
      title="当前范围内没有可统计的缺陷"
      description="换一个业务域 / 版本，或先去缺陷列表提报"
      icon="chart"
      action-text="去缺陷列表"
      @action="router.push({ name: 'defects' })"
    />
    <template v-else>
      <div class="board-cards">
        <MetricCard
          label="未关闭 P0/P1"
          :value="raw?.openHigh ?? null"
          :digits="0"
          :definition="defOpenHigh"
          :loading="loading"
          tone="danger"
          drillable
          hint="下钻：同筛选的未关闭高优缺陷"
          @drill="drill('openHigh')"
        />
        <MetricCard
          label="缺陷密度"
          :value="raw?.density ?? null"
          :digits="2"
          :definition="defDensity"
          :loading="loading"
          drillable
          hint="下钻：同筛选的缺陷明细"
          @drill="drill('density')"
        />
        <MetricCard
          label="重开率"
          :value="raw?.reopenRate ?? null"
          :digits="2"
          :unit="ratioUnit(raw?.reopenRate)"
          :definition="defReopen"
          :loading="loading"
          tone="warning"
          drillable
          hint="下钻：同筛选的缺陷明细"
          @drill="drill('reopenRate')"
        />
        <MetricCard
          label="平均修复时长"
          :value="raw?.mttrMinutes ?? null"
          unit="分钟"
          :digits="0"
          :definition="defMttr"
          :loading="loading"
          drillable
          hint="下钻：同筛选的缺陷明细"
          @drill="drill('mttr')"
        />
      </div>

      <section class="surface">
        <div class="surface-head">
          <span class="dl-toolbar__title">指标口径</span>
          <span class="dl-hint">定义版本随接口下发，前端不复制公式（AC-18）</span>
        </div>
        <el-table :data="metrics?.definitions ?? []" size="small">
          <el-table-column prop="code" label="编码" width="168">
            <template #default="{ row }">
              <span class="t-code">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" width="132" />
          <el-table-column prop="formula" label="公式" min-width="240">
            <template #default="{ row }">
              <span class="t-code">{{ row.formula }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="version" label="版本" width="72">
            <template #default="{ row }">v{{ row.version ?? 1 }}</template>
          </el-table-column>
          <el-table-column prop="caliberNote" label="口径说明" min-width="220" show-overflow-tooltip />
          <el-table-column label="更新于" width="140">
            <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
          </el-table-column>
        </el-table>
        <div v-if="!metrics?.definitions?.length" class="board-foot">
          <span class="dl-hint">接口未返回口径定义：请检查 deliver-service /metrics/defects 是否已上线。</span>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.board-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.surface-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 12px 12px 8px;
}

.board-foot {
  padding: 10px 12px;
}

@media (max-width: 1180px) {
  .board-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
