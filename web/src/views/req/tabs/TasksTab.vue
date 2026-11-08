<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { TaskRow } from '@/api/types'
import { listTasks } from '@/api/deliver'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import { formatDate } from '@/utils/format'
import { messageOf } from '@/views/req/platform'

/**
 * P2 · 任务 tab：任务表 + 完成率 + 所属迭代，空态引导「拆任务」。
 * 任务数据在 deliver-service；该服务未就绪时只显示可重试的错误态，不伪造数据。
 */
const props = defineProps<{ requirementId: number }>()
const emit = defineEmits<{ (event: 'count', value: number): void }>()

const router = useRouter()
const tasks = ref<TaskRow[]>([])
const loading = ref(false)
const errorMessage = ref('')

const done = computed(() => tasks.value.filter((task) => task.status === 'DONE').length)
const percent = computed(() => (tasks.value.length ? Math.round((done.value / tasks.value.length) * 100) : 0))

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    tasks.value = await listTasks(props.requirementId)
    emit('count', tasks.value.length)
  } catch (error) {
    tasks.value = []
    errorMessage.value = messageOf(error)
  } finally {
    loading.value = false
  }
}

function openIteration(): void {
  if (!router.hasRoute('iterations')) return
  void router.push({ name: 'iterations', query: { requirementId: String(props.requirementId) } })
}

onMounted(load)
</script>

<template>
  <section class="tasks">
    <div class="tasks__bar">
      <span>完成率 <strong>{{ done }}/{{ tasks.length }}</strong> <em>{{ percent }}%</em></span>
      <span class="filter-bar__spacer" />
      <el-button size="small" :disabled="!router.hasRoute('iterations')" :title="router.hasRoute('iterations') ? '' : '迭代看板随 T9 交付'" @click="openIteration">
        查看所属迭代
      </el-button>
    </div>

    <div v-if="loading" class="skeleton skeleton__row" />

    <ErrorState
      v-else-if="errorMessage"
      compact
      title="任务数据暂不可用"
      :message="errorMessage"
      @retry="load"
    />

    <EmptyState
      v-else-if="tasks.length === 0"
      title="还没有任务"
      description="研发任务由研发负责人在需求下拆出，完成后需求才能进入测试与交付。"
      action-text="刷新"
      icon="inbox"
      compact
      @action="load"
    />

    <el-table v-else :data="tasks" row-key="id">
      <el-table-column prop="code" label="编号" width="120">
        <template #default="{ row }"><span class="mono">{{ row.code ?? `T-${row.id}` }}</span></template>
      </el-table-column>
      <el-table-column prop="title" label="任务" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template #default="{ row }"><StatusPill kind="requirement" :value="row.status" size="sm" /></template>
      </el-table-column>
      <el-table-column label="负责人" width="120">
        <template #default="{ row }">{{ row.assigneeName ?? '未指派' }}</template>
      </el-table-column>
      <el-table-column label="预估工时" width="100" align="right">
        <template #default="{ row }">{{ row.estimateHours != null ? `${row.estimateHours} h` : '—' }}</template>
      </el-table-column>
      <el-table-column label="计划完成" width="110">
        <template #default="{ row }">{{ formatDate(row.dueDate) }}</template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.tasks {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.tasks__bar {
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
.tasks__bar strong {
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.tasks__bar em {
  margin-left: 6px;
  font-style: normal;
  color: var(--color-primary);
}
.mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
</style>
