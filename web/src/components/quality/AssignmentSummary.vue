<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AssignmentRow, AssignmentView } from '@/api/types'
import { formatDate } from '@/utils/format'

/**
 * 分配合计组件（DESIGN Q4 分配 tab）。
 *
 * 合计差额 `已分配 − 用例总数` 由页面/服务端口径决定，组件只展示并禁用“保存”：
 * 差额不为 0 时保存按钮不可点，避免把四舍五入争议带到服务端。真正的校验仍是
 * `PUT /assignments` 的 `ASSIGNMENT_MISMATCH`（400 + {diff, unassigned}）。
 */
const props = withDefaults(
  defineProps<{
    rows: AssignmentView[]
    caseTotal: number
    executors: Array<{ id: number; name: string }>
    editable?: boolean
    saving?: boolean
  }>(),
  { editable: false, saving: false },
)

const emit = defineEmits<{ (event: 'save', rows: AssignmentRow[]): void }>()

interface DraftRow {
  key: number
  executorId: number | null
  moduleId: number | null
  moduleName: string
  plannedCount: number
  dueDate: string
}

let seq = 0
const draft = ref<DraftRow[]>([])

function toDraft(rows: AssignmentView[]): DraftRow[] {
  return rows.map((row) => ({
    key: ++seq,
    executorId: row.executorId,
    moduleId: row.moduleId ?? null,
    moduleName: row.moduleName ?? '',
    plannedCount: row.plannedCount,
    dueDate: row.dueDate ?? '',
  }))
}

watch(
  () => props.rows,
  (rows) => {
    draft.value = toDraft(rows ?? [])
  },
  { immediate: true, deep: false },
)

const total = computed(() => draft.value.reduce((sum, row) => sum + (Number(row.plannedCount) || 0), 0))
const diff = computed(() => total.value - (Number(props.caseTotal) || 0))
const balanced = computed(() => diff.value === 0)
const hasEmptyExecutor = computed(() => draft.value.some((row) => row.executorId === null))

const hint = computed(() => {
  if (balanced.value) return `已分配 ${total.value} / ${props.caseTotal}`
  if (diff.value > 0) return `已分配 ${total.value} / ${props.caseTotal} · 多 ${diff.value} 条`
  return `已分配 ${total.value} / ${props.caseTotal} · 还差 ${Math.abs(diff.value)} 条`
})

function addRow(): void {
  draft.value.push({
    key: ++seq,
    executorId: null,
    moduleId: null,
    moduleName: '',
    plannedCount: 0,
    dueDate: '',
  })
}

function removeRow(index: number): void {
  draft.value.splice(index, 1)
}

function save(): void {
  if (!balanced.value || hasEmptyExecutor.value) return
  emit(
    'save',
    draft.value.map((row) => ({
      executorId: row.executorId as number,
      moduleId: row.moduleId ?? undefined,
      plannedCount: Number(row.plannedCount) || 0,
      dueDate: row.dueDate || undefined,
    })),
  )
}

function executorName(id: number): string {
  return props.executors.find((user) => user.id === id)?.name ?? `#${id}`
}
</script>

<template>
  <div class="as">
    <el-table :data="draft" row-key="key" size="small" class="as__table">
      <el-table-column label="执行人" min-width="160">
        <template #default="{ row }">
          <el-select
            v-if="editable"
            v-model="row.executorId"
            filterable
            placeholder="选择执行人"
            size="small"
            class="as__control"
          >
            <el-option v-for="user in executors" :key="user.id" :label="user.name" :value="user.id" />
          </el-select>
          <span v-else>{{ row.executorId ? executorName(row.executorId) : '—' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="模块" min-width="150">
        <template #default="{ row }">
          <el-input
            v-if="editable"
            v-model="row.moduleName"
            size="small"
            placeholder="全部模块"
            class="as__control"
          />
          <span v-else>{{ row.moduleName || '全部模块' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="计划条数" width="130" align="right">
        <template #default="{ row }">
          <el-input-number
            v-if="editable"
            v-model="row.plannedCount"
            :min="0"
            :step="1"
            step-strictly
            size="small"
            controls-position="right"
            class="as__control"
          />
          <span v-else class="as__num">{{ row.plannedCount }}</span>
        </template>
      </el-table-column>

      <el-table-column label="截止日" width="150">
        <template #default="{ row }">
          <el-date-picker
            v-if="editable"
            v-model="row.dueDate"
            type="date"
            value-format="YYYY-MM-DD"
            size="small"
            class="as__control"
          />
          <span v-else>{{ row.dueDate ? formatDate(row.dueDate) : '—' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="已执行" width="90" align="right">
        <template #default="{ row }">
          <span class="as__num">{{ row.executedCount ?? 0 }}</span>
        </template>
      </el-table-column>

      <el-table-column v-if="editable" label="" width="60" align="right">
        <template #default="{ $index }">
          <el-button link type="danger" size="small" @click="removeRow($index)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!draft.length" class="as__empty">还没有分配记录，分配后执行人才能在执行台看到这些用例</div>

    <div class="as__footer" :class="{ 'is-mismatch': !balanced }">
      <div class="as__total" role="status">
        <span class="as__total-text" :class="{ 'is-danger': !balanced }">{{ hint }}</span>
        <span v-if="hasEmptyExecutor && editable" class="as__warn">有行未选执行人</span>
      </div>
      <div v-if="editable" class="as__actions">
        <el-button size="small" @click="addRow">加一行</el-button>
        <el-button
          size="small"
          type="primary"
          :loading="saving"
          :disabled="!balanced || hasEmptyExecutor"
          @click="save"
        >
          保存分配
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.as {
  min-width: 0;
}

.as__table {
  width: 100%;
}

.as__control {
  width: 100%;
}

.as__num {
  font-variant-numeric: tabular-nums;
  color: var(--text-body);
}

.as__empty {
  padding: 24px 12px;
  color: var(--text-muted-aa);
  font-size: 12px;
  text-align: center;
}

.as__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-table-head);
}

.as__footer.is-mismatch {
  border-color: #f7d4d5;
  background: #fffbfb;
}

.as__total {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  font-size: 12px;
  color: var(--text-body);
  font-variant-numeric: tabular-nums;
}

.as__total-text.is-danger {
  color: var(--danger-ink);
  font-weight: 600;
}

.as__warn {
  color: var(--warning-ink);
}

.as__actions {
  display: flex;
  gap: 8px;
}
</style>
