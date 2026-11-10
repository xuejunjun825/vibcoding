<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { envApi } from '@/api/quality'
import { listDictionaryUsers, type DictionaryUser } from '@/api/dictionaries'
import { usePermission } from '@/composables/usePermission'
import type { EnvPayload, EnvRow } from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { formatDate } from '@/utils/format'
import '@/views/deliver/shared.css'

/**
 * 测试环境台账（DESIGN Q11 / AC-9）。
 *
 * 被进行中计划引用的环境不能删除（409 ENV_IN_USE + detail.planIds），页面只给「停用」出路；
 * 同域重名 409 显示在字段下方，不做全局 toast（用户要知道是哪一栏错了）。
 */
const ENV_TYPES = [
  { value: 'DEV', label: '开发' },
  { value: 'TEST', label: '测试' },
  { value: 'STAGING', label: '预发' },
  { value: 'PROD', label: '生产' },
]

interface EnvFormState {
  name: string
  envType: string
  baseUrl: string
  status: string
  ownerId: number | null
  note: string
}

interface DrawerState {
  visible: boolean
  mode: 'create' | 'edit'
  id: number
  saving: boolean
}

const { can } = usePermission()

const rows = ref<EnvRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const statusFilter = ref('')
const typeFilter = ref('')
const keyword = ref('')
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const users = ref<DictionaryUser[]>([])
const inUse = ref<{ planIds: number[]; envName: string } | null>(null)
const fieldError = ref('')

const drawer = ref<DrawerState>({ visible: false, mode: 'create', id: 0, saving: false })
const form = ref<EnvFormState>({
  name: '',
  envType: 'TEST',
  baseUrl: '',
  status: 'AVAILABLE',
  ownerId: null,
  note: '',
})

const canManage = computed(() => can('env:manage'))

function resetForm(): void {
  form.value = { name: '', envType: 'TEST', baseUrl: '', status: 'AVAILABLE', ownerId: null, note: '' }
  fieldError.value = ''
}

function openCreate(): void {
  resetForm()
  inUse.value = null
  drawer.value = { visible: true, mode: 'create', id: 0, saving: false }
}

function openEdit(row: EnvRow): void {
  resetForm()
  inUse.value = null
  form.value = {
    name: row.name,
    envType: row.envType || 'TEST',
    baseUrl: row.baseUrl || '',
    status: row.status || 'AVAILABLE',
    ownerId: row.ownerId ?? null,
    note: row.note || '',
  }
  drawer.value = { visible: true, mode: 'edit', id: row.id, saving: false }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await envApi.page({
      status: statusFilter.value || undefined,
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = data.items ?? []
    total.value = data.total ?? 0
  } catch (err) {
    error.value = {
      message: (err as Error).message || '环境台账加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

function payload(): EnvPayload {
  return {
    name: form.value.name.trim(),
    envType: form.value.envType,
    baseUrl: form.value.baseUrl.trim() || undefined,
    status: form.value.status,
    ownerId: form.value.ownerId,
    note: form.value.note.trim() || undefined,
  }
}

async function submit(): Promise<void> {
  fieldError.value = ''
  if (!form.value.name.trim()) {
    fieldError.value = '环境名称必填'
    return
  }
  if (form.value.baseUrl && !/^https?:\/\//.test(form.value.baseUrl)) {
    fieldError.value = '地址需以 http:// 或 https:// 开头'
    return
  }
  drawer.value.saving = true
  try {
    if (drawer.value.mode === 'create') {
      await envApi.create(payload())
      ElMessage.success('环境已创建')
    } else {
      await envApi.update(drawer.value.id, payload())
      ElMessage.success('环境已保存')
    }
    drawer.value.visible = false
    await load()
  } catch (err) {
    const typed = err as { code?: string; message?: string; detail?: Record<string, unknown> }
    if (typed.code === 'ENV_IN_USE') {
      const planIds = Array.isArray(typed.detail?.planIds) ? (typed.detail?.planIds as number[]) : []
      inUse.value = { planIds, envName: form.value.name }
    } else if (typed.code === 'ENV_NAME_TAKEN' || typed.code === 'DUPLICATE_ENV_NAME') {
      fieldError.value = typed.message || '同域内环境名重复'
    } else {
      ElMessage.error(typed.message || '保存未完成')
    }
  } finally {
    drawer.value.saving = false
  }
}

async function disable(row: EnvRow): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `停用后新建的轮次不能再选择 ${row.name}，历史执行记录不受影响。`,
      '停用环境',
      { confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await envApi.update(row.id, {
      name: row.name,
      envType: row.envType || 'TEST',
      baseUrl: row.baseUrl || undefined,
      status: 'DISABLED',
      ownerId: row.ownerId ?? null,
      note: row.note || undefined,
    })
    ElMessage.success('环境已停用')
    await load()
  } catch (err) {
    const typed = err as { message?: string; detail?: Record<string, unknown>; code?: string }
    if (typed.code === 'ENV_IN_USE') {
      const planIds = Array.isArray(typed.detail?.planIds) ? (typed.detail?.planIds as number[]) : []
      inUse.value = { planIds, envName: row.name }
      return
    }
    ElMessage.error(typed.message || '停用未完成')
  }
}

async function remove(row: EnvRow): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除 ${row.name}？该操作不可撤销。`, '删除环境', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await envApi.remove(row.id)
    ElMessage.success('环境已删除')
    await load()
  } catch (err) {
    const typed = err as { code?: string; message?: string; detail?: Record<string, unknown> }
    if (typed.code === 'ENV_IN_USE') {
      const planIds = Array.isArray(typed.detail?.planIds) ? (typed.detail?.planIds as number[]) : []
      inUse.value = { planIds, envName: row.name }
    } else {
      ElMessage.error(typed.message || '删除未完成')
    }
  }
}

function resetFilters(): void {
  statusFilter.value = ''
  typeFilter.value = ''
  keyword.value = ''
  page.value = 1
  void load()
}

function applyFilter(): void {
  page.value = 1
  void load()
}

onMounted(async () => {
  await load()
  try {
    users.value = await listDictionaryUsers()
  } catch {
    users.value = []
  }
})
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">测试环境</h1>
        <span class="dl-meta">{{ total }} 个环境</span>
      </div>
      <div class="dl-head__actions">
        <el-button type="primary" :disabled="!canManage" @click="openCreate">新建环境</el-button>
      </div>
    </header>

    <div class="dl-filters">
      <el-select v-model="typeFilter" placeholder="类型" clearable style="width: 140px">
        <el-option v-for="item in ENV_TYPES" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="statusFilter" placeholder="状态" clearable style="width: 140px">
        <el-option label="可用" value="AVAILABLE" />
        <el-option label="维护中" value="MAINTENANCE" />
        <el-option label="已停用" value="DISABLED" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="搜索名称 / 地址"
        clearable
        style="width: 200px"
        @keyup.enter="applyFilter"
      />
      <el-button @click="applyFilter">查询</el-button>
      <el-button text @click="resetFilters">重置</el-button>
    </div>

    <ErrorState
      v-if="error"
      :message="error.message"
      :code="error.code"
      :status="error.status"
      @retry="load"
    />

    <section v-else class="surface" v-loading="loading">
      <div v-if="inUse" class="dl-conflict env-inuse" role="alert">
        <div class="env-inuse__head">
          <strong>{{ inUse.envName }} 被 {{ inUse.planIds.length }} 个进行中计划引用，不能删除</strong>
          <span class="env-inuse__spacer" />
          <button type="button" class="env-inuse__close" aria-label="关闭提示" @click="inUse = null">×</button>
        </div>
        <p class="env-inuse__msg">只能「停用」：停用后新轮次不可再选择该环境，历史执行记录保留。</p>
        <div class="env-inuse__plans">
          <span class="env-inuse__label">引用计划：</span>
          <span v-for="id in inUse.planIds" :key="id" class="env-inuse__chip">#{{ id }}</span>
        </div>
      </div>

      <el-table :data="rows" row-key="id" size="small">
        <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            {{ ENV_TYPES.find((item) => item.value === row.envType)?.label || row.envType || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="地址" min-width="220" show-overflow-tooltip>
          <template #default="{ row }"><span class="t-code">{{ row.baseUrl || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="负责人" width="120">
          <template #default="{ row }">{{ row.ownerName || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><StatusPill kind="env" :value="row.status" size="sm" /></template>
        </el-table-column>
        <el-table-column label="引用计划" width="110">
          <template #default="{ row }">
            <span :class="{ 'is-muted': !row.planIds?.length }">{{ row.planIds?.length ?? 0 }} 个</span>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="160" show-overflow-tooltip />
        <el-table-column label="更新" width="130">
          <template #default="{ row }"><span class="is-muted">{{ formatDate(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" :disabled="!canManage" @click="openEdit(row)">编辑</el-button>
              <el-button
                link
                :disabled="!canManage || row.status === 'DISABLED'"
                @click="disable(row)"
              >
                停用
              </el-button>
              <el-button
                link
                type="danger"
                :disabled="!canManage || (row.planIds?.length ?? 0) > 0"
                :title="(row.planIds?.length ?? 0) > 0 ? '被进行中计划引用，只能停用' : '删除'"
                @click="remove(row)"
              >
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-if="!rows.length && !loading"
        :variant="statusFilter || keyword ? 'filtered' : 'empty'"
        :title="statusFilter || keyword ? '没有匹配的环境' : '还没有测试环境'"
        :description="statusFilter || keyword ? '换个筛选条件试试' : '建一个测试环境，计划与门禁才能指向它'"
        :action-text="statusFilter || keyword ? '清除筛选' : '新建环境'"
        icon="file"
        @action="statusFilter || keyword ? resetFilters() : openCreate()"
      />

      <div v-if="total > size" class="env-pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next, total"
          @current-change="load"
        />
      </div>
    </section>

    <el-drawer
      v-model="drawer.visible"
      :title="drawer.mode === 'create' ? '新建环境' : '编辑环境'"
      :size="480"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item label="名称" required :error="!form.name && fieldError ? fieldError : ''">
          <el-input v-model="form.name" maxlength="40" placeholder="例如 TEST-2 北京" />
        </el-form-item>
        <div class="env-grid">
          <el-form-item label="类型">
            <el-select v-model="form.envType" style="width: 100%">
              <el-option v-for="item in ENV_TYPES" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="form.status" style="width: 100%">
              <el-option label="可用" value="AVAILABLE" />
              <el-option label="维护中" value="MAINTENANCE" />
              <el-option label="已停用" value="DISABLED" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="地址">
          <el-input v-model="form.baseUrl" placeholder="https://test2.example.com" maxlength="200" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="form.ownerId" clearable filterable style="width: 100%" placeholder="选择负责人">
            <el-option v-for="user in users" :key="user.id" :label="user.name" :value="user.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>

      <p v-if="fieldError && form.name" class="field-error" role="alert">{{ fieldError }}</p>

      <div v-if="inUse" class="dl-conflict" role="alert">
        <strong>被 {{ inUse.planIds.length }} 个进行中计划引用，不能删除</strong>
        <div class="env-inuse__plans">
          <span v-for="id in inUse.planIds" :key="id" class="env-inuse__chip">#{{ id }}</span>
        </div>
        <p class="field-error">只能改为「已停用」。</p>
      </div>

      <template #footer>
        <div class="drawer-foot">
          <el-button @click="drawer.visible = false">取消</el-button>
          <el-button type="primary" :loading="drawer.saving" @click="submit">保存</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.dl-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.env-inuse {
  margin: 12px;
}

.env-inuse__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.env-inuse__spacer {
  flex: 1;
}

.env-inuse__close {
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: var(--r-cell);
  background: transparent;
  color: var(--text-muted-aa);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  transition: background var(--t-color) var(--ease);
}

.env-inuse__close:hover {
  background: var(--bg-hover);
}

.env-inuse__close:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.env-inuse__msg {
  margin: 4px 0 8px;
  font-size: 12px;
}

.env-inuse__label {
  color: var(--text-body);
  font-size: 12px;
}

.env-inuse__plans {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.env-inuse__chip {
  padding: 0 6px;
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  background: var(--bg-card);
  color: var(--text-body);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 18px;
}

.env-pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px;
  border-top: 1px solid var(--divider);
}

.env-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 12px;
}

.field-error {
  margin: 0 0 8px;
  color: var(--danger);
  font-size: 12px;
}

.drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.is-muted {
  color: var(--text-muted-aa);
  font-size: 12px;
}
</style>
