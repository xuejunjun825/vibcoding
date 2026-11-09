<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listTraceability } from '@/api/deliver'
import { getSonar, type SonarCard } from '@/api/integration'
import type { TraceRow } from '@/api/types'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import TraceMatrix from '@/components/TraceMatrix.vue'
import SonarSnapshotDrawer from '@/components/req/SonarSnapshotDrawer.vue'
import '@/views/deliver/shared.css'

/**
 * 追溯矩阵（DESIGN P9 / AC-15）。
 * 缺口以服务端 gaps/complete 为准；只是把 chips 叠加成客户端二次筛选，
 * 绝不在前端把“有 MR”推成“齐备”（渲染前 TraceMatrix 会二次断言）。
 */
const router = useRouter()
const { can } = usePermission()
const canManageOrg = computed(() => can('org:manage'))

const typeOptions = [
  { value: 'BUS', label: '业务需求' },
  { value: 'PDR', label: '产品需求' },
  { value: 'RD', label: '研发需求' },
]

const GAP_CHIPS = [
  { code: 'MR', label: '缺 MR' },
  { code: 'EXECUTION', label: '缺执行' },
  { code: 'DEFECT', label: '未关闭 P0/P1' },
  { code: 'RELEASE', label: '未发布' },
  { code: 'CASE', label: '未关联用例' },
]

const {
  rows,
  total,
  loading,
  error,
  page,
  size,
  isEmpty,
  isFiltered,
  load,
  setPage,
  setFilter,
  filters,
  reset,
} = useListQuery<TraceRow>(
  (query) =>
    listTraceability({
      type: query.filters.type || undefined,
      domainId: query.filters.domainId ? Number(query.filters.domainId) : undefined,
      vendorId: query.filters.vendorId ? Number(query.filters.vendorId) : undefined,
      gap: String(query.filters.gaps ?? '').trim() ? true : undefined,
      page: query.page,
      size: query.size,
    }),
  { type: '', domainId: '', vendorId: '' },
  { size: 50 },
)

// 缺口选择写进 URL（gaps=a,b），刷新/分享后选择不丢；服务端只按“是否有缺口”过滤
const activeGaps = computed<string[]>(() =>
  String(filters.value.gaps ?? '')
    .split(',')
    .map((code) => code.trim().toUpperCase())
    .filter(Boolean),
)

const gapCounts = computed(() => {
  const counts: Record<string, number> = {}
  for (const row of rows.value) {
    const codes = (row.gaps ?? []).map((gap) => String(gap).toUpperCase())
    for (const code of codes) counts[code] = (counts[code] ?? 0) + 1
    if (row.complete === false && !codes.length) counts.OTHER = (counts.OTHER ?? 0) + 1
  }
  return counts
})

const chipOptions = computed(() => {
  const known = GAP_CHIPS.filter((chip) => gapCounts.value[chip.code] != null || activeGaps.value.includes(chip.code))
  const extra = Object.keys(gapCounts.value)
    .filter((code) => !GAP_CHIPS.some((chip) => chip.code === code))
    .map((code) => ({ code, label: code }))
  return [...known, ...extra]
})

const visibleRows = computed(() => {
  const active = activeGaps.value
  if (!active.length) return rows.value
  return rows.value.filter((row) => {
    const codes = (row.gaps ?? []).map((gap) => String(gap).toUpperCase())
    if (row.complete === false && !codes.length) codes.push('OTHER')
    return active.every((code) => codes.includes(code))
  })
})

function toggleGap(code: string): void {
  const next = activeGaps.value.includes(code)
    ? activeGaps.value.filter((item) => item !== code)
    : [...activeGaps.value, code]
  // setFilter 内部会重新加载（含分页回到第 1 页），这里不再额外 load
  void setFilter('gaps', next.join(','))
}

function clearAll(): void {
  void reset()
}

function openRequirement(requirementId: number): void {
  const target = router.resolve({ name: 'requirement-detail', params: { id: requirementId } })
  if (target.matched.some((record) => record.name === 'catch-all')) {
    ElMessage.info(`需求 ${requirementId}（需求详情页由 T8 提供）`)
    return
  }
  void router.push({ name: 'requirement-detail', params: { id: requirementId } })
}

/* ---------------- 集成节点（BUILD / SONAR_GATE） ---------------- */
const sonarOpen = ref(false)
const sonarCard = ref<SonarCard | null>(null)

async function onNode(requirementId: number, key: string): Promise<void> {
  if (key === 'sonar') {
    sonarCard.value = null
    sonarOpen.value = true
    try {
      // 只读本地快照（后端不因这次 GET 去访问 Sonar）
      sonarCard.value = await getSonar(requirementId)
      if (!sonarCard.value) ElMessage.info('该需求还没有 Sonar 快照，先在需求集成页刷新质量门')
    } catch (err) {
      ElMessage.warning(err instanceof Error ? err.message : '读取 Sonar 快照失败')
    }
    return
  }
  if (key === 'build') {
    // TraceRow 目前没有 deployOrderId，只能带到发布单列表按需求定位（见 OUT-OF-SCOPE）
    void router.push({ name: 'deploy-orders', query: { requirementId: String(requirementId) } })
  }
}
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">追溯矩阵</h1>
        <span class="dl-meta">{{ total }} 条需求 · 需求 → 任务 → MR → 构建 → 执行 → 缺陷 → Sonar → 发布</span>
      </div>
      <div class="dl-head__actions">
        <el-button link :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="surface surface--pad">
      <div class="dl-filters">
        <el-select
          :model-value="filters.type"
          placeholder="需求类型"
          clearable
          style="width: 150px"
          @update:model-value="(value: string) => setFilter('type', value ?? '')"
        >
          <el-option v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-input
          :model-value="filters.domainId"
          placeholder="域 ID"
          clearable
          style="width: 110px"
          @update:model-value="(value: string) => setFilter('domainId', value)"
        />
        <el-input
          :model-value="filters.vendorId"
          placeholder="厂商 ID"
          clearable
          style="width: 120px"
          @update:model-value="(value: string) => setFilter('vendorId', value)"
        />
        <div class="dl-filters__spacer" />
        <span class="dl-hint">
          {{ canManageOrg ? '可按域 / 厂商精确筛选' : '仅展示授权范围内的需求' }}
        </span>
      </div>
      <div class="dl-chips gap-chips">
        <span class="dl-hint">缺口</span>
        <button
          v-for="chip in chipOptions"
          :key="chip.code"
          type="button"
          class="dl-chip"
          :class="{ 'is-active': activeGaps.includes(chip.code), '--warn': chip.code !== 'MR' }"
          :aria-pressed="activeGaps.includes(chip.code)"
          @click="toggleGap(chip.code)"
        >
          {{ chip.label }}
          <span v-if="gapCounts[chip.code]" class="chip-count">{{ gapCounts[chip.code] }}</span>
        </button>
        <button v-if="activeGaps.length" type="button" class="dl-chip dl-chip--warn" @click="clearAll">清空</button>
      </div>
      <p v-if="activeGaps.length" class="dl-hint gap-hint">
        缺口筛选会请求服务端只返回有缺口的需求，再按所选缺口在本页做二次收窄；单元格结论始终来自服务端。
      </p>
    </section>

    <section class="surface trace-surface">
      <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />
      <EmptyState
        v-else-if="!loading && isEmpty"
        :variant="isFiltered ? 'filtered' : 'empty'"
        :title="isFiltered ? '没有匹配的需求' : '还没有可追溯的需求'"
        :description="isFiltered ? '减少缺口条件或换需求类型' : '需求进入交付后，任务/MR/执行/缺陷会自动出现在矩阵里'"
        icon="chart"
        :action-text="isFiltered ? '清空筛选' : undefined"
        @action="clearAll"
      />
      <TraceMatrix
        v-else
        :rows="visibleRows"
        :loading="loading"
        highlight-gaps
        @open="openRequirement"
        @node="onNode"
      />
      <div class="trace-foot">
        <span class="dl-hint">
          当前页 {{ visibleRows.length }} / {{ rows.length }} 行 · 缺口行以「! 缺口」呈现，绝不显示为齐备；构建 / Sonar 无快照显示「无快照」，不计入覆盖
        </span>
        <el-pagination
          layout="prev, pager, next"
          :current-page="page"
          :page-size="size"
          :total="total"
          background
          small
          @current-change="setPage"
        />
      </div>
    </section>

    <SonarSnapshotDrawer v-model="sonarOpen" :card="sonarCard" />
  </div>
</template>

<style scoped>
.gap-chips {
  margin-top: 10px;
}

.gap-hint {
  margin: 6px 0 0;
}

.trace-surface {
  margin-top: 12px;
  overflow: hidden;
}

.trace-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border-top: 1px solid var(--divider);
}
</style>
