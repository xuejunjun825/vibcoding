<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { defectMetrics, listDefects } from '@/api/deliver'
import type { BoardMetrics, DefectRow } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import MetricCard from '@/components/MetricCard.vue'
import PriorityDot from '@/components/PriorityDot.vue'
import StatusPill from '@/components/StatusPill.vue'
import { formatDate, formatDuration } from '@/utils/format'
import { messageOf } from '@/views/req/platform'

/**
 * P2 · 缺陷 tab：需求下缺陷表 + 4 个同源数字卡（未关闭 P0/P1、缺陷密度、重开率、MTTR）。
 * 数字来自 deliver-service MetricQuery；点卡片带同一筛选进入缺陷列表，不换口径。
 */
const props = defineProps<{ requirementId: number }>()

const router = useRouter()
const defects = ref<DefectRow[]>([])
const metrics = ref<BoardMetrics['metrics'] | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const reopenPercent = computed(() => {
  const value = Number(metrics.value?.reopenRate)
  return Number.isFinite(value) ? Math.round(value * 1000) / 10 : null
})

function filterNow(): Record<string, string> {
  return { requirementId: String(props.requirementId) }
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [page, board] = await Promise.all([
      listDefects({ requirementId: props.requirementId, page: 1, size: 5 }),
      defectMetrics({ requirementId: props.requirementId }).catch(() => null),
    ])
    defects.value = page.items
    metrics.value = board?.metrics ?? null
  } catch (error) {
    defects.value = []
    metrics.value = null
    errorMessage.value = messageOf(error)
  } finally {
    loading.value = false
  }
}

function openBoard(extra: Record<string, string> = {}): void {
  if (!router.hasRoute('defects')) return
  void router.push({ name: 'defects', query: { ...filterNow(), ...extra } })
}

function openDefect(row: DefectRow): void {
  if (!router.hasRoute('defect-detail')) return
  void router.push({ name: 'defect-detail', params: { id: row.id }, query: { from: `/requirements/${props.requirementId}` } })
}

onMounted(load)
</script>

<template>
  <div class="defects">
    <div class="defects__cards">
      <MetricCard
        label="未关闭 P0/P1"
        :value="metrics ? metrics.openHigh : null"
        hint="严重缺陷未关闭，发布准入会直接阻断"
        :tone="metrics && metrics.openHigh > 0 ? 'danger' : 'default'"
        drillable
        :loading="loading"
        @drill="openBoard({ severity: 'P0,P1', openOnly: '1' })"
      />
      <MetricCard
        label="缺陷密度"
        :value="metrics ? metrics.density : null"
        :digits="2"
        unit="个/需求"
        hint="缺陷数 ÷ 需求数"
        drillable
        :loading="loading"
        @drill="openBoard()"
      />
      <MetricCard
        label="重开率"
        :value="reopenPercent"
        unit="%"
        :digits="1"
        hint="重开缺陷 ÷ 缺陷总数"
        drillable
        :loading="loading"
        @drill="openBoard({ reopened: '1' })"
      />
      <MetricCard
        label="MTTR"
        :value="metrics && metrics.mttrMinutes != null ? formatDuration(metrics.mttrMinutes) : null"
        hint="已关闭缺陷平均修复时长"
        drillable
        :loading="loading"
        @drill="openBoard({ status: 'CLOSED' })"
      />
    </div>

    <section class="surface surface--pad">
      <header class="defects__head">
        <h3 class="defects__title">缺陷</h3>
        <span class="defects__hint">最近 5 条，点击行进入缺陷详情</span>
        <span class="filter-bar__spacer" />
        <el-button text type="primary" size="small" @click="openBoard()">查看全部 →</el-button>
      </header>

      <div v-if="loading" class="skeleton skeleton__row" />
      <ErrorState
        v-else-if="errorMessage"
        compact
        title="缺陷数据暂不可用"
        :message="errorMessage"
        @retry="load"
      />
      <EmptyState
        v-else-if="defects.length === 0"
        title="该需求还没有缺陷"
        description="测试执行不通过时可直接转缺陷，缺陷会自动带出域与厂商。"
        icon="inbox"
        compact
      />
      <el-table v-else :data="defects" row-key="id" @row-click="openDefect">
        <el-table-column prop="code" label="编号" width="120">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column label="严重度" width="90">
          <template #default="{ row }"><PriorityDot :value="row.severity" /></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><StatusPill kind="defect" :value="row.status" size="sm" /></template>
        </el-table-column>
        <el-table-column label="重开" width="72" align="right">
          <template #default="{ row }">{{ row.reopenCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="110">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.defects {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.defects__cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.defects__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 10px;
}
.defects__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.defects__hint {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
@media (max-width: 1080px) {
  .defects__cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
