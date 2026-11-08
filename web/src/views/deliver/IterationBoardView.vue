<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { moveTask, sprintBoard } from '@/api/deliver'
import type { SprintBoard, TaskRow } from '@/api/types'
import { useDictStore } from '@/stores/dict'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import '@/views/deliver/shared.css'

/**
 * 迭代看板（DESIGN P6 / AC-14）。
 * - 4 列 × 人泳道，列头带计数；
 * - 拖拽 drop 先乐观更新，失败回滚并提示「状态未变更：<原因>」；
 * - 键盘可达：每张卡有「移动到…」菜单（Enter 打开，不依赖鼠标）。
 */
const route = useRoute()
const router = useRouter()
const dict = useDictStore()

const board = ref<SprintBoard | null>(null)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)

const DEFAULT_COLUMNS = [
  { key: 'TODO', name: '待办' },
  { key: 'IN_PROGRESS', name: '进行中' },
  { key: 'DONE', name: '完成' },
  { key: 'BLOCKED', name: '阻塞' },
]

const sprintId = computed(() => Number(route.params.id))
const sprintName = computed(() => String(route.query.name ?? `迭代 #${sprintId.value}`))

const columns = computed(() => (board.value?.columns?.length ? board.value.columns : DEFAULT_COLUMNS))

const lanes = computed(() => {
  const list = [...(board.value?.lanes ?? [])]
  const known = new Set(list.map((lane) => lane.id))
  const hasUnassigned = (board.value?.tasks ?? []).some(
    (task) => !task.assigneeId || !known.has(task.assigneeId),
  )
  if (hasUnassigned) list.push({ id: 0, name: '未指派' })
  return list
})

function laneOf(task: TaskRow): number {
  const ids = new Set((board.value?.lanes ?? []).map((lane) => lane.id))
  return task.assigneeId && ids.has(task.assigneeId) ? task.assigneeId : 0
}

function tasksOf(status: string, laneId: number): TaskRow[] {
  return (board.value?.tasks ?? [])
    .filter((task) => task.status === status && laneOf(task) === laneId)
    .sort((a, b) => (a.sortNo ?? 0) - (b.sortNo ?? 0))
}

async function load(): Promise<void> {
  if (!sprintId.value) return
  loading.value = true
  error.value = null
  try {
    board.value = await sprintBoard(sprintId.value)
  } catch (err) {
    error.value = {
      message: (err as Error).message || '看板加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

/* ---------------- 拖拽 ---------------- */

const dragging = ref<TaskRow | null>(null)
const overCell = ref('')
const flashId = ref<number | null>(null)

function cellKey(status: string, laneId: number): string {
  return `${status}:${laneId}`
}

function onDragStart(task: TaskRow, event: DragEvent): void {
  dragging.value = task
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(task.id))
  }
}

function onDragOver(status: string, laneId: number, event: DragEvent): void {
  event.preventDefault()
  overCell.value = cellKey(status, laneId)
}

function onDragLeave(status: string, laneId: number): void {
  if (overCell.value === cellKey(status, laneId)) overCell.value = ''
}

function onDrop(status: string, laneId: number, event: DragEvent): void {
  event.preventDefault()
  const task = dragging.value
  dragging.value = null
  overCell.value = ''
  if (!task) return
  void commitMove(task, status, laneId)
}

async function commitMove(task: TaskRow, status: string, laneId: number): Promise<void> {
  if (task.status === status) return
  const previous = task.status
  const targetIndex = tasksOf(status, laneId).length
  task.status = status
  task.sortNo = targetIndex
  try {
    const updated = await moveTask(task.id, { status, sortNo: targetIndex })
    Object.assign(task, updated, { status })
    flashId.value = task.id
    window.setTimeout(() => {
      if (flashId.value === task.id) flashId.value = null
    }, 600)
  } catch (err) {
    task.status = previous
    task.sortNo = undefined
    ElMessage.error(`状态未变更：${(err as Error).message || '服务端拒绝（可能已锁定）'}`)
  }
}

function onMenuMove(task: TaskRow, command: string): void {
  void commitMove(task, command, laneOf(task))
}

function openTask(task: TaskRow): void {
  const target = router.resolve({ name: 'requirement-detail', params: { id: task.requirementId } })
  if (target.matched.some((record) => record.name === 'catch-all')) {
    ElMessage.info(`任务 ${task.code || task.id} 关联需求 ${task.requirementId}（需求详情页由 T8 提供）`)
    return
  }
  void router.push({ name: 'requirement-detail', params: { id: task.requirementId } })
}

function backToList(): void {
  void router.push({ name: 'iterations' })
}

onMounted(load)
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <button type="button" class="dl-head__back" @click="backToList">← 迭代</button>
        <h1 class="t-page-title">{{ sprintName }}</h1>
        <span class="dl-meta">
          {{ board?.tasks.length ?? 0 }} 个任务 · {{ lanes.length }} 条泳道
        </span>
      </div>
      <div class="dl-head__actions">
        <el-button @click="backToList">关闭迭代 / 迁移任务</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <ErrorState
      v-if="error"
      :message="error.message"
      :code="error.code"
      :status="error.status"
      @retry="load"
    />
    <EmptyState
      v-else-if="!loading && !board"
      title="迭代不存在或无权访问"
      description="确认迭代属于当前业务域，且你有该域的交付权限"
      icon="lock"
      action-text="返回迭代列表"
      @action="backToList"
    />
    <section v-else class="surface surface--pad board" v-loading="loading">
      <p class="dl-hint board-hint">
        拖拽卡片到目标列即可改状态；键盘用户可用卡片右上「移动到…」菜单。失败会回滚并提示原因。
      </p>
      <div v-for="lane in lanes" :key="lane.id" class="dl-lane">
        <div class="dl-lane__head">
          <span class="dl-avatar">{{ lane.name.slice(0, 1) }}</span>
          <span>{{ lane.name }}</span>
        </div>
        <div class="dl-cols">
          <div
            v-for="column in columns"
            :key="column.key"
            class="dl-col"
            :class="{ 'is-over': overCell === cellKey(column.key, lane.id) }"
            @dragover="onDragOver(column.key, lane.id, $event)"
            @dragleave="onDragLeave(column.key, lane.id)"
            @drop="onDrop(column.key, lane.id, $event)"
          >
            <div class="dl-col__head">
              <span>{{ column.name }}</span>
              <span>{{ tasksOf(column.key, lane.id).length }}</span>
            </div>
            <div class="dl-cards">
              <div
                v-for="task in tasksOf(column.key, lane.id)"
                :key="task.id"
                class="dl-card"
                :class="{
                  'is-dragging': dragging?.id === task.id,
                  'is-flash': flashId === task.id,
                }"
                draggable="true"
                tabindex="0"
                role="button"
                :aria-label="`${task.code || ''} ${task.title}，当前${dict.label('requirement', task.status) || task.status}`"
                @dragstart="onDragStart(task, $event)"
                @dragend="dragging = null"
                @dblclick="openTask(task)"
                @keydown.enter.prevent="openTask(task)"
              >
                <div class="dl-card__title">{{ task.title }}</div>
                <div class="dl-card__meta">
                  <span class="t-code">{{ task.code || `TASK-${task.id}` }}</span>
                  <span v-if="task.estimateHours">{{ task.estimateHours }}h</span>
                  <StatusPill kind="requirement" :value="task.status" size="sm" />
                </div>
                <div class="dl-card__move">
                  <el-dropdown trigger="click" @command="onMenuMove(task, $event)">
                    <button type="button" class="move-btn" aria-label="移动到其他状态">移动到…</button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item
                          v-for="column in columns"
                          :key="column.key"
                          :command="column.key"
                          :disabled="column.key === task.status"
                        >
                          {{ column.name }}
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </div>
              <div v-if="!tasksOf(column.key, lane.id).length" class="dl-col__empty">
                拖到这里
              </div>
            </div>
          </div>
        </div>
      </div>
      <EmptyState
        v-if="!loading && !board?.tasks.length"
        variant="empty"
        title="这个迭代还没有任务"
        description="在需求详情里拆任务，或在迭代列表选择其他迭代"
        icon="inbox"
        compact
        action-text="返回迭代列表"
        @action="backToList"
      />
    </section>
  </div>
</template>

<style scoped>
.board {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.board-hint {
  margin: 0;
}

.dl-col__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 56px;
  border: 1px dashed var(--border);
  border-radius: var(--r-ctl);
  color: var(--text-weak);
  font-size: 12px;
}

.move-btn {
  height: 22px;
  padding: 0 6px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  background: var(--bg-card);
  color: var(--text-muted-aa);
  font-size: 11px;
  cursor: pointer;
}

.move-btn:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.move-btn:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
</style>
