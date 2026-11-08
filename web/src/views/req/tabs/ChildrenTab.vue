<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import type { RequirementDetail, RequirementRow } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import PriorityDot from '@/components/PriorityDot.vue'
import StatusPill from '@/components/StatusPill.vue'
import { usePermission } from '@/composables/usePermission'
import { formatDays } from '@/utils/format'
import { childInfo, requirementPath, typeLabel, usePlatformDict } from '@/views/req/platform'

/**
 * P2 · 子需求 tab：表格 + 只读汇总条（完成率与子项人天之和，父需求不可编辑）。
 */
const props = defineProps<{ detail: RequirementDetail }>()
const emit = defineEmits<{ (event: 'decompose'): void }>()

const router = useRouter()
const { can } = usePermission()
const { userName } = usePlatformDict()

const children = computed<RequirementRow[]>(() => props.detail.children ?? [])
const rollup = computed(() => childInfo(props.detail))
const percent = computed(() =>
  rollup.value.childCount ? Math.round((rollup.value.doneCount / rollup.value.childCount) * 100) : 0,
)
const estimateTotal = computed(() => children.value.reduce((sum, row) => sum + (row.estimateDays ?? 0), 0))
const actualTotal = computed(() => children.value.reduce((sum, row) => sum + (row.actualDays ?? 0), 0))

function openRow(row: RequirementRow): void {
  void router.push(requirementPath(row.id))
}
</script>

<template>
  <section class="children">
    <div class="children__rollup" role="status">
      <span>
        完成率
        <strong>{{ rollup.doneCount }}/{{ rollup.childCount }}</strong>
        <span class="children__percent">{{ percent }}%</span>
      </span>
      <span class="children__sep" aria-hidden="true">·</span>
      <span>
        子项预估
        <strong>{{ formatDays(estimateTotal) }}</strong>
      </span>
      <span class="children__sep" aria-hidden="true">·</span>
      <span>
        子项实际
        <strong>{{ formatDays(actualTotal) }}</strong>
      </span>
      <span class="filter-bar__spacer" />
      <el-button v-if="can('req:split')" size="small" @click="emit('decompose')">拆解子需求</el-button>
    </div>

    <EmptyState
      v-if="children.length === 0"
      title="还没有子需求"
      description="把这条需求拆成可独立交付的子项，父需求的人天与完成率会自动汇总。"
      action-text="拆解子需求"
      icon="file"
      compact
      @action="emit('decompose')"
    />

    <el-table v-else :data="children" row-key="id" class="children__table" @row-click="openRow">
      <el-table-column prop="code" label="编号" width="130">
        <template #default="{ row }">
          <span class="mono">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ typeLabel(row.type) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }"><StatusPill kind="requirement" :value="row.status" size="sm" /></template>
      </el-table-column>
      <el-table-column label="优先级" width="86">
        <template #default="{ row }"><PriorityDot :value="row.priority" /></template>
      </el-table-column>
      <el-table-column label="负责人" width="120">
        <template #default="{ row }">{{ row.ownerName ?? userName(row.ownerId) }}</template>
      </el-table-column>
      <el-table-column label="预估" width="92" align="right">
        <template #default="{ row }">{{ formatDays(row.estimateDays) }}</template>
      </el-table-column>
      <el-table-column label="实际" width="92" align="right">
        <template #default="{ row }">{{ formatDays(row.actualDays) }}</template>
      </el-table-column>
      <el-table-column label="" width="80" align="right">
        <template #default>
          <span class="row-actions">打开 →</span>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.children {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.children__rollup {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-page);
  font-size: 12px;
  color: var(--text-body);
}
.children__rollup strong {
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.children__percent {
  margin-left: 6px;
  color: var(--color-primary);
  font-weight: 500;
}
.children__sep {
  color: var(--border-strong);
}
.children__table {
  cursor: pointer;
}
.mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
</style>
