<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  closeSprint,
  createSprint,
  listSprints,
  sprintBoard,
} from '@/api/deliver'
import { listReleases } from '@/api/req'
import type { ReleaseRow, SprintBoard } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import '@/views/deliver/shared.css'

/**
 * 迭代列表（DESIGN P6 / AC-14）。
 * 迭代挂在版本下（sprint.release_id），所以先选版本再选迭代；
 * 关闭迭代是危险动作：有未完成任务时必须显式选择“迁移到别的迭代”或“退回待办”。
 */
const route = useRoute()
const router = useRouter()

const releases = ref<ReleaseRow[]>([])
const releaseId = ref(String(route.query.releaseId ?? ''))
const sprints = ref<Array<{ id: number; name: string }>>([])
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)

async function loadReleases(): Promise<void> {
  try {
    const page = await listReleases({ page: 1, size: 100 })
    releases.value = page.items ?? []
    if (!releaseId.value && releases.value.length) {
      releaseId.value = String(releases.value[0].id)
      void router.replace({ query: { ...route.query, releaseId: releaseId.value } })
    }
  } catch (err) {
    error.value = { message: (err as Error).message || '版本列表加载失败' }
  }
}

async function loadSprints(): Promise<void> {
  if (!releaseId.value) {
    sprints.value = []
    return
  }
  loading.value = true
  error.value = null
  try {
    sprints.value = await listSprints(releaseId.value)
  } catch (err) {
    error.value = {
      message: (err as Error).message || '迭代列表加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

function changeRelease(value: string): void {
  releaseId.value = value
  void router.replace({ query: { ...route.query, releaseId: value } })
  void loadSprints()
}

/* ---------------- 新建迭代 ---------------- */

const createVisible = ref(false)
const creating = ref(false)
const form = reactive({
  name: '',
  goal: '',
  startDate: '',
  endDate: '',
  capacityDays: undefined as number | undefined,
})

function openCreate(): void {
  Object.assign(form, { name: '', goal: '', startDate: '', endDate: '', capacityDays: undefined })
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请填写迭代名称')
    return
  }
  if (!releaseId.value) {
    ElMessage.warning('请先选择版本')
    return
  }
  creating.value = true
  try {
    await createSprint({
      releaseId: Number(releaseId.value),
      name: form.name.trim(),
      goal: form.goal || undefined,
      startDate: form.startDate || undefined,
      endDate: form.endDate || undefined,
      capacityDays: form.capacityDays,
    })
    createVisible.value = false
    ElMessage.success('迭代已创建')
    await loadSprints()
  } catch (err) {
    ElMessage.error((err as Error).message || '创建失败')
  } finally {
    creating.value = false
  }
}

/* ---------------- 关闭迭代 ---------------- */

const closeVisible = ref(false)
const closing = ref(false)
const closeTarget = ref<{ id: number; name: string } | null>(null)
const closeBoard = ref<SprintBoard | null>(null)
const closeMode = ref<'migrate' | 'backlog'>('backlog')
const migrateTo = ref<number | undefined>(undefined)

const unfinished = computed(() =>
  (closeBoard.value?.tasks ?? []).filter((task) => task.status !== 'DONE'),
)
const doneCount = computed(() => (closeBoard.value?.tasks ?? []).length - unfinished.value.length)
const migrateOptions = computed(() =>
  sprints.value.filter((sprint) => sprint.id !== closeTarget.value?.id),
)

async function openClose(sprint: { id: number; name: string }): Promise<void> {
  closeTarget.value = sprint
  closeMode.value = 'backlog'
  migrateTo.value = migrateOptions.value[0]?.id
  closeBoard.value = null
  closeVisible.value = true
  try {
    closeBoard.value = await sprintBoard(sprint.id)
  } catch {
    closeBoard.value = { columns: [], lanes: [], tasks: [] }
  }
}

async function submitClose(): Promise<void> {
  const target = closeTarget.value
  if (!target) return
  if (unfinished.value.length && closeMode.value === 'migrate' && !migrateTo.value) {
    ElMessage.warning('请选择要迁移到的迭代')
    return
  }
  closing.value = true
  try {
    await closeSprint(target.id, closeMode.value === 'migrate' ? { migrateToSprintId: migrateTo.value } : {})
    closeVisible.value = false
    ElMessage.success('迭代已关闭')
    await loadSprints()
  } catch (err) {
    const apiError = err as { status?: number; message?: string }
    ElMessage.error(
      apiError.status === 409
        ? apiError.message || '仍有未完成任务，请选择迁移或退回待办（409）'
        : apiError.message || '关闭失败',
    )
  } finally {
    closing.value = false
  }
}

function openBoard(sprint: { id: number }): void {
  void router.push({ name: 'iteration-board', params: { id: sprint.id } })
}

onMounted(async () => {
  await loadReleases()
  await loadSprints()
})
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">迭代</h1>
        <span class="dl-meta">按版本组织的冲刺，看板按人泳道</span>
      </div>
      <div class="dl-head__actions">
        <el-button type="primary" :disabled="!releaseId" @click="openCreate">新建迭代</el-button>
      </div>
    </header>

    <section class="surface surface--pad">
      <div class="dl-filters">
        <el-select
          :model-value="releaseId"
          placeholder="选择版本"
          filterable
          style="width: 240px"
          @update:model-value="changeRelease"
        >
          <el-option
            v-for="release in releases"
            :key="release.id"
            :label="release.name"
            :value="String(release.id)"
          />
        </el-select>
        <div class="dl-filters__spacer" />
        <el-button link :loading="loading" @click="loadSprints">刷新</el-button>
      </div>
    </section>

    <section class="surface">
      <ErrorState
        v-if="error"
        :message="error.message"
        :code="error.code"
        :status="error.status"
        @retry="loadSprints"
      />
      <EmptyState
        v-else-if="!loading && !sprints.length"
        :title="releaseId ? '这个版本还没有迭代' : '先选择一个版本'"
        :description="releaseId ? '新建迭代后即可把任务拖到看板上' : '迭代必须在版本下创建'"
        icon="inbox"
        :action-text="releaseId ? '新建迭代' : undefined"
        @action="openCreate"
      />
      <template v-else>
        <el-table v-loading="loading" :data="sprints" row-key="id">
          <el-table-column prop="name" label="迭代" min-width="220">
            <template #default="{ row }">
              <span class="t-block-title">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="id" label="编号" width="120">
            <template #default="{ row }">
              <span class="t-code">SPRINT-{{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button link type="primary" @click="openBoard(row)">打开看板</el-button>
                <el-button link @click="openClose(row)">关闭迭代</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </section>

    <el-dialog v-model="createVisible" title="新建迭代" width="480px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="60" placeholder="如：支付域 2026Q4 迭代 2" />
        </el-form-item>
        <el-form-item label="目标">
          <el-input v-model="form.goal" type="textarea" :rows="2" maxlength="300" />
        </el-form-item>
        <div class="date-grid">
          <el-form-item label="开始">
            <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="结束">
            <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
        </div>
        <el-form-item label="产能（人天）">
          <el-input-number v-model="form.capacityDays" :min="0" :max="999" :controls="false" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="closeVisible" title="关闭迭代" width="560px" :close-on-click-modal="false">
      <p class="close-desc">
        迭代 <strong>{{ closeTarget?.name }}</strong> 共
        {{ closeBoard?.tasks.length ?? 0 }} 个任务，已完成 {{ doneCount }} 个，未完成
        {{ unfinished.length }} 个。
      </p>
      <div v-if="unfinished.length" class="close-panel">
        <p class="dl-hint">关闭前必须处理未完成任务：迁移到其他迭代，或退回待办（保持当前状态）。</p>
        <el-radio-group v-model="closeMode" class="close-modes">
          <el-radio value="migrate">迁移到其他迭代</el-radio>
          <el-radio value="backlog">退回待办（不迁移）</el-radio>
        </el-radio-group>
        <el-select
          v-if="closeMode === 'migrate'"
          v-model="migrateTo"
          placeholder="选择目标迭代"
          filterable
          style="width: 100%"
        >
          <el-option
            v-for="sprint in migrateOptions"
            :key="sprint.id"
            :label="sprint.name"
            :value="sprint.id"
          />
        </el-select>
        <ul class="unfinished-list">
          <li v-for="task in unfinished.slice(0, 8)" :key="task.id">
            <span class="t-code">{{ task.code || `TASK-${task.id}` }}</span>
            <span>{{ task.title }}</span>
          </li>
          <li v-if="unfinished.length > 8" class="ink-muted">
            另有 {{ unfinished.length - 8 }} 个未完成任务
          </li>
        </ul>
      </div>
      <p v-else class="dl-hint">所有任务已完成，关闭后迭代归档、不可再写入。</p>
      <template #footer>
        <el-button @click="closeVisible = false">取消</el-button>
        <el-button type="danger" :loading="closing" @click="submitClose">确认关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.date-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.date-grid :deep(.el-date-editor) {
  width: 100%;
}

.close-desc {
  margin: 0 0 10px;
  color: var(--text-body);
  font-size: 13px;
}

.close-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-table-head);
}

.close-modes {
  display: flex;
  gap: 16px;
}

.unfinished-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--text-body);
}

.unfinished-list li {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
