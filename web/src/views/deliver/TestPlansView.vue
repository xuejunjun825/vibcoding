<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createTestPlan, listTestPlans } from '@/api/deliver'
import { listUsers } from '@/api/admin'
import { listReleases } from '@/api/req'
import type { ReleaseRow } from '@/api/types'
import type { UserRow } from '@/api/admin'
import { usePermission } from '@/composables/usePermission'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import '@/views/deliver/shared.css'

/**
 * 测试计划（DESIGN P11 / AC-16、AC-17）。
 * 计划挂在 Release 下，用例纳入时钉版本；执行与门禁在计划执行页完成。
 */
interface PlanRow {
  id: number
  code: string
  name: string
  status?: string
  releaseName?: string
}

const route = useRoute()
const router = useRouter()
const { can } = usePermission()
const canManage = computed(() => can('plan:manage'))

const releases = ref<ReleaseRow[]>([])
const users = ref<UserRow[]>([])
const releaseId = ref(String(route.query.releaseId ?? ''))
const rows = ref<PlanRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await listTestPlans({
      releaseId: releaseId.value || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = data.items ?? []
    total.value = data.total ?? 0
  } catch (err) {
    error.value = {
      message: (err as Error).message || '测试计划加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

async function loadOptions(): Promise<void> {
  try {
    const pageData = await listReleases({ page: 1, size: 100 })
    releases.value = pageData.items ?? []
  } catch {
    releases.value = []
  }
  if (!canManage.value) return
  try {
    const userPage = await listUsers({ page: 1, size: 200 })
    users.value = userPage.items ?? []
  } catch {
    users.value = []
  }
}

function changeRelease(value: string): void {
  releaseId.value = value
  page.value = 1
  void router.replace({ query: { ...route.query, releaseId: value || undefined } })
  void load()
}

function openPlan(row: PlanRow): void {
  void router.push({ name: 'test-plan-execute', params: { id: row.id } })
}

function setPage(next: number): void {
  page.value = next
  void load()
}

/* ---------------- 新建计划 ---------------- */

const createVisible = ref(false)
const creating = ref(false)
const form = reactive({
  releaseId: '',
  name: '',
  ownerId: undefined as number | undefined,
  windowStart: '',
  windowEnd: '',
})

function openCreate(): void {
  form.releaseId = releaseId.value || (releases.value[0] ? String(releases.value[0].id) : '')
  form.name = ''
  form.ownerId = undefined
  form.windowStart = ''
  form.windowEnd = ''
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  if (!form.releaseId) {
    ElMessage.warning('请选择版本')
    return
  }
  if (!form.name.trim()) {
    ElMessage.warning('请填写计划名称')
    return
  }
  creating.value = true
  try {
    const created = await createTestPlan({
      releaseId: Number(form.releaseId),
      name: form.name.trim(),
      ownerId: form.ownerId,
      windowStart: form.windowStart || undefined,
      windowEnd: form.windowEnd || undefined,
    })
    createVisible.value = false
    ElMessage.success(`计划 ${created.code} 已创建，接下来纳入用例`)
    void router.push({ name: 'test-plan-execute', params: { id: created.id } })
  } catch (err) {
    ElMessage.error((err as Error).message || '创建失败')
  } finally {
    creating.value = false
  }
}

onMounted(async () => {
  await loadOptions()
  await load()
})
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">测试计划</h1>
        <span class="dl-meta">{{ total }} 个计划 · 用例纳入时钉住当前版本</span>
      </div>
      <div class="dl-head__actions">
        <el-button type="primary" :disabled="!canManage" @click="openCreate">新建计划</el-button>
      </div>
    </header>

    <section class="surface surface--pad">
      <div class="dl-filters">
        <el-select
          :model-value="releaseId"
          placeholder="全部版本"
          clearable
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
        <el-button link :loading="loading" @click="load">刷新</el-button>
      </div>
    </section>

    <section class="surface">
      <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />
      <EmptyState
        v-else-if="!loading && !rows.length"
        :title="releaseId ? '这个版本还没有测试计划' : '还没有测试计划'"
        description="计划 = 版本下的一组用例，执行结果用于门禁判定"
        icon="file"
        :action-text="canManage ? '新建计划' : undefined"
        @action="openCreate"
      />
      <template v-else>
        <el-table v-loading="loading" :data="rows" row-key="id" @row-click="openPlan">
          <el-table-column prop="code" label="编号" width="130">
            <template #default="{ row }"><span class="t-code">{{ row.code }}</span></template>
          </el-table-column>
          <el-table-column prop="name" label="计划" min-width="240" show-overflow-tooltip />
          <el-table-column label="版本" width="200">
            <template #default="{ row }">{{ row.releaseName || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">{{ row.status || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <div class="row-actions" @click.stop>
                <el-button link type="primary" @click="openPlan(row)">执行</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div class="dl-pager">
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
      </template>
    </section>

    <el-dialog v-model="createVisible" title="新建测试计划" width="520px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="版本" required>
          <el-select v-model="form.releaseId" filterable style="width: 100%">
            <el-option v-for="release in releases" :key="release.id" :label="release.name" :value="String(release.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="计划名称" required>
          <el-input v-model="form.name" maxlength="80" placeholder="如：支付域 2026Q4 回归" />
        </el-form-item>
        <el-form-item label="执行负责人">
          <el-select v-model="form.ownerId" clearable filterable style="width: 100%">
            <el-option v-for="user in users" :key="user.id" :label="`${user.name}（${user.username}）`" :value="user.id" />
          </el-select>
        </el-form-item>
        <div class="date-grid">
          <el-form-item label="开始">
            <el-date-picker v-model="form.windowStart" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="结束">
            <el-date-picker v-model="form.windowEnd" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建并去纳入用例</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dl-pager {
  display: flex;
  justify-content: flex-end;
  padding: 8px 12px;
}

.date-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.date-grid :deep(.el-date-editor) {
  width: 100%;
}
</style>
