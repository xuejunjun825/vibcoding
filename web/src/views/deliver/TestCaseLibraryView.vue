<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  copyTestCase,
  createTestCase,
  deleteTestCase,
  exportTestCasesCsv,
  getTestCase,
  importTestCases,
  listCaseVersions,
  listTestCases,
  saveTestCase,
} from '@/api/deliver'
import type { CaseStep, CaseVersionRow, TestCaseDetail, TestCaseRow } from '@/api/types'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import CaseVersionSwitcher from '@/components/CaseVersionSwitcher.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import ImportWizard from '@/components/ImportWizard.vue'
import PriorityDot from '@/components/PriorityDot.vue'
import '@/views/deliver/shared.css'

/**
 * 用例库（DESIGN P7 / AC-16）。
 * 左模块树（由用例行聚合，≤3 层）+ 右用例表 + 抽屉内版本快照查看。
 * 历史版本一律只读：执行记录钉 case_version，任何编辑都生成新版本。
 */
const { can } = usePermission()

const canEdit = computed(() => can('case:manage'))

interface ModuleNode {
  id: number
  name: string
  count: number
  children: ModuleNode[]
}

const allRows = ref<TestCaseRow[]>([])
const treeLoading = ref(false)

const filters = reactive({
  keyword: '',
  moduleId: '',
  priority: '',
  type: '',
})

const { rows, total, loading, error, page, size, isEmpty, isFiltered, load, refresh, setPage, setFilter, filters: queryFilters } =
  useListQuery<TestCaseRow>(
    (query) =>
      listTestCases({
        keyword: query.filters.keyword || undefined,
        moduleId: query.filters.moduleId ? Number(query.filters.moduleId) : undefined,
        priority: query.filters.priority || undefined,
        type: query.filters.type || undefined,
        page: query.page,
        size: query.size,
      }),
    { keyword: '', moduleId: '', priority: '', type: '' },
    { size: 20 },
  )

// URL / 刷新恢复时必须回填输入框与树选中态（本地副本只负责输入，提交才进 query）
watch(
  () => queryFilters.value,
  (value) => {
    filters.keyword = value.keyword ?? ''
    filters.moduleId = value.moduleId ?? ''
    filters.priority = value.priority ?? ''
    filters.type = value.type ?? ''
  },
  { deep: true, immediate: true },
)

async function loadTree(): Promise<void> {
  treeLoading.value = true
  try {
    const pageData = await listTestCases({ page: 1, size: 500 })
    allRows.value = pageData.items ?? []
  } catch {
    allRows.value = []
  } finally {
    treeLoading.value = false
  }
}

/** 模块树：编号→父编号未知时按“扁平一层”呈现，最多三层。 */
const moduleTree = computed<ModuleNode[]>(() => {
  const map = new Map<number, ModuleNode>()
  for (const row of allRows.value) {
    if (!row.moduleId) continue
    const node = map.get(row.moduleId) ?? {
      id: row.moduleId,
      name: row.moduleName || `模块 ${row.moduleId}`,
      count: 0,
      children: [],
    }
    node.count += 1
    map.set(row.moduleId, node)
  }
  return [...map.values()].sort((a, b) => a.id - b.id)
})

const selectedModuleId = computed(() => (filters.moduleId ? Number(filters.moduleId) : 0))

function selectModule(node: ModuleNode | null): void {
  setFilter('moduleId', node ? String(node.id) : '')
}

function applyKeyword(): void {
  setFilter('keyword', filters.keyword.trim())
  load()
}

function resetFilters(): void {
  filters.keyword = ''
  filters.priority = ''
  filters.type = ''
  setFilter('keyword', '')
  setFilter('priority', '')
  setFilter('type', '')
  setFilter('moduleId', '')
  load()
}

/* ---------------- 抽屉：版本快照 ---------------- */

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<TestCaseDetail | null>(null)
const versions = ref<CaseVersionRow[]>([])
const selectedVersion = ref(1)
const currentVersion = ref(1)
const saving = ref(false)

const isHistorical = computed(
  () => detail.value?.readonly === true || selectedVersion.value < currentVersion.value,
)

const editForm = reactive({
  title: '',
  precondition: '',
  type: '',
  priority: '',
  tags: '',
  automated: false,
  scriptRef: '',
})

const steps = ref<CaseStep[]>([])

async function openCase(row: TestCaseRow): Promise<void> {
  drawerVisible.value = true
  detail.value = null
  versions.value = []
  detailLoading.value = true
  try {
    versions.value = await listCaseVersions(row.id)
    const current = row.currentVersion ?? versions.value.find((item) => item.current)?.versionNo ?? 1
    currentVersion.value = current
    selectedVersion.value = current
    await loadVersion(row.id, current)
  } catch (err) {
    ElMessage.error((err as Error).message || '用例详情加载失败')
    drawerVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

async function loadVersion(caseId: number, version: number): Promise<void> {
  detailLoading.value = true
  try {
    const data = await getTestCase(caseId, version)
    detail.value = data
    editForm.title = data.title
    editForm.precondition = data.precondition ?? ''
    editForm.type = data.type ?? ''
    editForm.priority = data.priority ?? ''
    editForm.tags = (data.tags ?? []).join(', ')
    editForm.automated = data.automated === true
    editForm.scriptRef = data.scriptRef ?? ''
    steps.value = (data.steps ?? []).map((step) => ({ action: step.action, expected: step.expected }))
  } catch (err) {
    ElMessage.error((err as Error).message || '版本加载失败')
  } finally {
    detailLoading.value = false
  }
}

watch(selectedVersion, (version) => {
  if (!detail.value || version === detail.value.version) return
  void loadVersion(detail.value.id, version)
})

function addStep(): void {
  steps.value.push({ action: '', expected: '' })
}

function removeStep(index: number): void {
  steps.value.splice(index, 1)
}

function moveStep(index: number, delta: number): void {
  const target = index + delta
  if (target < 0 || target >= steps.value.length) return
  const [item] = steps.value.splice(index, 1)
  steps.value.splice(target, 0, item)
}

async function saveNewVersion(): Promise<void> {
  const current = detail.value
  if (!current) return
  if (!editForm.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  if (!steps.value.length || steps.value.some((step) => !step.action.trim())) {
    ElMessage.warning('至少一条步骤，且操作不能为空')
    return
  }
  try {
    await ElMessageBox.confirm(
      `保存将生成 v${currentVersion.value + 1}，历史执行记录仍按 v${current.version} 快照重放。`,
      '生成新版本',
      { confirmButtonText: '生成新版本', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  saving.value = true
  try {
    const updated = await saveTestCase(current.id, {
      title: editForm.title.trim(),
      precondition: editForm.precondition || undefined,
      type: editForm.type || undefined,
      priority: editForm.priority || undefined,
      tags: editForm.tags ? editForm.tags.split(',').map((tag) => tag.trim()).filter(Boolean) : undefined,
      automated: editForm.automated,
      scriptRef: editForm.scriptRef || undefined,
      steps: steps.value,
      baseVersion: current.version,
    })
    detail.value = updated
    versions.value = await listCaseVersions(current.id)
    currentVersion.value = updated.version
    selectedVersion.value = updated.version
    ElMessage.success(`已生成 v${updated.version}`)
    await refresh()
    await loadTree()
  } catch (err) {
    const apiError = err as { status?: number; message?: string }
    if (apiError.status === 409) {
      ElMessage.warning(apiError.message || '该版本已被他人修改，请刷新后重试（409）')
    } else {
      ElMessage.error(apiError.message || '保存失败')
    }
  } finally {
    saving.value = false
  }
}

function closeDrawer(): void {
  drawerVisible.value = false
  detail.value = null
}

/* ---------------- 新建 / 复制 / 删除 / 导入导出 ---------------- */

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  title: '',
  moduleId: undefined as number | undefined,
  type: 'FUNCTIONAL',
  priority: 'P2',
  precondition: '',
  requirementId: undefined as number | undefined,
  automated: false,
  scriptRef: '',
})
const createSteps = ref<CaseStep[]>([{ action: '', expected: '' }])

function openCreate(): void {
  createForm.title = ''
  createForm.precondition = ''
  createForm.requirementId = undefined
  createForm.automated = false
  createForm.scriptRef = ''
  createForm.moduleId = selectedModuleId.value || undefined
  createSteps.value = [{ action: '', expected: '' }]
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  if (!createForm.title.trim()) {
    ElMessage.warning('请填写用例标题')
    return
  }
  if (!createSteps.value.length || createSteps.value.some((step) => !step.action.trim())) {
    ElMessage.warning('至少一条步骤，且操作不能为空')
    return
  }
  creating.value = true
  try {
    await createTestCase({
      title: createForm.title.trim(),
      moduleId: createForm.moduleId,
      type: createForm.type,
      priority: createForm.priority,
      precondition: createForm.precondition || undefined,
      requirementId: createForm.requirementId,
      automated: createForm.automated,
      scriptRef: createForm.scriptRef || undefined,
      steps: createSteps.value,
    })
    createVisible.value = false
    ElMessage.success('用例已创建')
    await refresh()
    await loadTree()
  } catch (err) {
    ElMessage.error((err as Error).message || '创建失败')
  } finally {
    creating.value = false
  }
}

async function copyRow(row: TestCaseRow): Promise<void> {
  let title = ''
  try {
    const result = await ElMessageBox.prompt('复制后的用例标题', '复制用例', {
      inputValue: `${row.title}（副本）`,
      confirmButtonText: '复制',
      cancelButtonText: '取消',
      inputValidator: (value: string) => (value?.trim() ? true : '标题不能为空'),
    })
    title = result.value.trim()
  } catch {
    return
  }
  try {
    await copyTestCase(row.id, title)
    ElMessage.success('已复制')
    await refresh()
    await loadTree()
  } catch (err) {
    ElMessage.error((err as Error).message || '复制失败')
  }
}

const deleteVisible = ref(false)
const deleteTarget = ref<TestCaseRow | null>(null)
const deleting = ref(false)

function askDelete(row: TestCaseRow): void {
  deleteTarget.value = row
  deleteVisible.value = true
}

async function confirmDelete(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return
  deleting.value = true
  try {
    await deleteTestCase(target.id)
    deleteVisible.value = false
    ElMessage.success('用例已删除（软删除，历史执行保留）')
    await refresh()
    await loadTree()
  } catch (err) {
    ElMessage.error((err as Error).message || '删除失败')
  } finally {
    deleting.value = false
  }
}

const importVisible = ref(false)

async function validateImport(file: File): Promise<{
  ok: number
  failed: number
  errors: Array<{ row: number; message: string }>
}> {
  const formData = new FormData()
  formData.append('file', file)
  return importTestCases(formData)
}

async function onImported(): Promise<void> {
  importVisible.value = false
  await refresh()
  await loadTree()
}

async function exportCsv(): Promise<void> {
  try {
    await exportTestCasesCsv({
      moduleId: filters.moduleId ? Number(filters.moduleId) : undefined,
      keyword: filters.keyword || undefined,
      priority: filters.priority || undefined,
      type: filters.type || undefined,
    })
  } catch (err) {
    ElMessage.error((err as Error).message || '导出失败')
  }
}

const typeOptions = [
  { value: 'FUNCTIONAL', label: '功能' },
  { value: 'INTERFACE', label: '接口' },
  { value: 'UI', label: '界面' },
  { value: 'PERFORMANCE', label: '性能' },
  { value: 'SECURITY', label: '安全' },
]

function typeLabel(type?: string): string {
  return typeOptions.find((item) => item.value === type)?.label ?? type ?? '—'
}

onMounted(() => {
  void loadTree()
})
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">用例库</h1>
        <span class="dl-meta">{{ total }} 条用例</span>
      </div>
      <div class="dl-head__actions">
        <el-button :disabled="!canEdit" @click="importVisible = true">导入</el-button>
        <el-button :disabled="!canEdit" @click="exportCsv">导出 CSV</el-button>
        <el-button type="primary" :disabled="!canEdit" @click="openCreate">新建用例</el-button>
      </div>
    </header>

    <div class="dl-split">
      <aside class="dl-aside surface">
        <div class="dl-aside__title">
          <span>模块</span>
          <button type="button" class="link-btn" @click="selectModule(null)">全部</button>
        </div>
        <div v-if="treeLoading" class="dl-hint">加载中…</div>
        <ul class="dl-tree">
          <li>
            <button
              type="button"
              class="dl-tree__item"
              :class="{ 'is-active': !filters.moduleId }"
              @click="selectModule(null)"
            >
              <span>全部用例</span>
              <span class="dl-tree__count">{{ allRows.length }}</span>
            </button>
          </li>
          <li v-for="node in moduleTree" :key="node.id">
            <button
              type="button"
              class="dl-tree__item"
              :class="{ 'is-active': selectedModuleId === node.id }"
              @click="selectModule(node)"
            >
              <span>{{ node.name }}</span>
              <span class="dl-tree__count">{{ node.count }}</span>
            </button>
          </li>
          <li v-if="!treeLoading && !moduleTree.length" class="dl-tree__empty">
            暂无可归组的模块；新建用例时填写模块 ID 即可归组
          </li>
        </ul>
      </aside>

      <section class="dl-main">
        <div class="surface surface--pad">
          <div class="dl-filters">
            <el-input
              v-model="filters.keyword"
              placeholder="搜索编号 / 标题"
              clearable
              style="width: 220px"
              @keyup.enter="applyKeyword"
              @clear="applyKeyword"
            />
            <el-select v-model="filters.priority" placeholder="优先级" clearable style="width: 120px" @change="setFilter('priority', filters.priority)">
              <el-option v-for="p in ['P0', 'P1', 'P2', 'P3']" :key="p" :label="p" :value="p" />
            </el-select>
            <el-select v-model="filters.type" placeholder="类型" clearable style="width: 130px" @change="setFilter('type', filters.type)">
              <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <div class="dl-filters__spacer" />
            <el-button link @click="resetFilters">重置</el-button>
            <el-button link :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>

        <section class="surface">
          <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />
          <EmptyState
            v-else-if="!loading && isEmpty"
            :variant="isFiltered ? 'filtered' : 'empty'"
            :title="isFiltered ? '没有匹配的用例' : '用例库还没有内容'"
            :description="isFiltered ? '调整模块、类型或关键词再试' : '先新建或用 Excel 批量导入'"
            icon="file"
            :action-text="isFiltered ? '清空筛选' : canEdit ? '新建用例' : undefined"
            @action="isFiltered ? resetFilters() : openCreate()"
          />
          <el-table v-else v-loading="loading" :data="rows" row-key="id" @row-click="openCase">
            <el-table-column prop="code" label="编号" width="110">
              <template #default="{ row }">
                <span class="t-code">{{ row.code || `TC-${row.id}` }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="title" label="标题" min-width="260" show-overflow-tooltip />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ typeLabel(row.type) }}</template>
            </el-table-column>
            <el-table-column label="优先级" width="90">
              <template #default="{ row }"><PriorityDot :value="row.priority" /></template>
            </el-table-column>
            <el-table-column label="版本" width="80">
              <template #default="{ row }">
                <span class="t-code">v{{ row.currentVersion ?? 1 }}</span>
              </template>
            </el-table-column>
            <el-table-column label="自动化" width="90">
              <template #default="{ row }">
                <span :class="row.automated ? 'ink-success' : 'ink-muted'">{{ row.automated ? '是' : '否' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="关联需求" width="130">
              <template #default="{ row }">
                <span class="t-code">{{ row.requirementId ? `REQ-${row.requirementId}` : '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <div class="row-actions" @click.stop>
                  <el-button link type="primary" @click="openCase(row)">查看</el-button>
                  <el-button link :disabled="!canEdit" @click="copyRow(row)">复制</el-button>
                  <el-button link type="danger" :disabled="!canEdit" @click="askDelete(row)">删除</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="total > size" class="dl-pager">
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
      </section>
    </div>

    <el-drawer v-model="drawerVisible" size="640px" :with-header="false" @closed="closeDrawer">
      <div v-if="detailLoading && !detail" class="dl-hint">加载用例快照…</div>
      <template v-else-if="detail">
        <div class="dl-drawer-head">
          <div>
            <div class="dl-drawer-title">{{ detail.title }}</div>
            <div class="dl-meta">
              <span class="t-code">{{ detail.code || `TC-${detail.id}` }}</span>
              <span>· {{ typeLabel(detail.type) }}</span>
            </div>
          </div>
          <el-button link @click="drawerVisible = false">关闭</el-button>
        </div>

        <div class="dl-toolbar">
          <span class="dl-toolbar__title">版本</span>
          <CaseVersionSwitcher
            v-model="selectedVersion"
            :versions="versions"
            :current-version="currentVersion"
            :disabled="detailLoading"
          />
        </div>

        <div v-if="isHistorical" class="readonly-banner">
          只读 · 正在查看 v{{ detail.version }} 快照；历史执行按该版本重放，编辑不会回写此版本。
        </div>

        <el-form label-position="top" class="case-form">
          <el-form-item label="标题" required>
            <el-input v-model="editForm.title" :disabled="isHistorical" maxlength="120" />
          </el-form-item>
          <div class="dl-field-grid">
            <el-form-item label="类型">
              <el-select v-model="editForm.type" :disabled="isHistorical" style="width: 100%">
                <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="优先级">
              <el-select v-model="editForm.priority" :disabled="isHistorical" style="width: 100%">
                <el-option v-for="p in ['P0', 'P1', 'P2', 'P3']" :key="p" :label="p" :value="p" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="前置条件">
            <el-input v-model="editForm.precondition" type="textarea" :rows="2" :disabled="isHistorical" />
          </el-form-item>
          <el-form-item label="关联需求 ID">
            <span class="t-code">{{ detail.requirementId ? `REQ-${detail.requirementId}` : '未关联' }}</span>
          </el-form-item>
          <div class="dl-toolbar">
            <span class="dl-toolbar__title">步骤</span>
            <el-button v-if="!isHistorical" link type="primary" @click="addStep">添加步骤</el-button>
          </div>
          <table class="dl-steps-editor">
            <thead>
              <tr>
                <th style="width: 40px">#</th>
                <th>操作</th>
                <th>预期</th>
                <th style="width: 96px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(step, index) in steps" :key="index">
                <td class="dl-steps-editor__no">{{ index + 1 }}</td>
                <td>
                  <el-input v-model="step.action" :disabled="isHistorical" placeholder="执行动作" />
                </td>
                <td>
                  <el-input v-model="step.expected" :disabled="isHistorical" placeholder="预期结果" />
                </td>
                <td class="dl-steps-editor__ops">
                  <template v-if="!isHistorical">
                    <el-button link :disabled="index === 0" @click="moveStep(index, -1)">上移</el-button>
                    <el-button link :disabled="index === steps.length - 1" @click="moveStep(index, 1)">下移</el-button>
                    <el-button link type="danger" @click="removeStep(index)">删除</el-button>
                  </template>
                </td>
              </tr>
              <tr v-if="!steps.length">
                <td colspan="4" class="dl-hint">该版本没有步骤</td>
              </tr>
            </tbody>
          </table>
          <div class="dl-field-grid" style="margin-top: 12px">
            <el-form-item label="标签（逗号分隔）">
              <el-input v-model="editForm.tags" :disabled="isHistorical" />
            </el-form-item>
            <el-form-item label="自动化脚本引用">
              <el-input v-model="editForm.scriptRef" :disabled="isHistorical" placeholder="repo/path::case" />
            </el-form-item>
          </div>
          <el-checkbox v-model="editForm.automated" :disabled="isHistorical">自动化用例</el-checkbox>
        </el-form>

        <div class="drawer-footer">
          <span class="dl-hint">
            {{ isHistorical ? '历史版本不可编辑' : '保存即生成新版本，历史执行仍按旧版本快照' }}
          </span>
          <div>
            <el-button @click="drawerVisible = false">取消</el-button>
            <el-button type="primary" :loading="saving" :disabled="isHistorical || !canEdit" @click="saveNewVersion">
              保存为新版本
            </el-button>
          </div>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="createVisible" title="新建用例" width="640px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="标题" required>
          <el-input v-model="createForm.title" maxlength="120" placeholder="一句话说明验证目标" />
        </el-form-item>
        <div class="dl-field-grid">
          <el-form-item label="模块 ID">
            <el-input-number v-model="createForm.moduleId" :min="1" :controls="false" style="width: 100%" />
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="createForm.type" style="width: 100%">
              <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级">
            <el-select v-model="createForm.priority" style="width: 100%">
              <el-option v-for="p in ['P0', 'P1', 'P2', 'P3']" :key="p" :label="p" :value="p" />
            </el-select>
          </el-form-item>
          <el-form-item label="关联需求 ID">
            <el-input-number v-model="createForm.requirementId" :min="1" :controls="false" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="前置条件">
          <el-input v-model="createForm.precondition" type="textarea" :rows="2" />
        </el-form-item>
        <div class="dl-toolbar">
          <span class="dl-toolbar__title">步骤</span>
          <el-button link type="primary" @click="createSteps.push({ action: '', expected: '' })">添加步骤</el-button>
        </div>
        <table class="dl-steps-editor">
          <thead>
            <tr>
              <th style="width: 40px">#</th>
              <th>操作</th>
              <th>预期</th>
              <th style="width: 60px" />
            </tr>
          </thead>
          <tbody>
            <tr v-for="(step, index) in createSteps" :key="index">
              <td class="dl-steps-editor__no">{{ index + 1 }}</td>
              <td><el-input v-model="step.action" /></td>
              <td><el-input v-model="step.expected" /></td>
              <td class="dl-steps-editor__ops">
                <el-button link type="danger" @click="createSteps.splice(index, 1)">删</el-button>
              </td>
            </tr>
          </tbody>
        </table>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <ConfirmDialog
      v-model="deleteVisible"
      title="删除用例"
      :description="`将软删除 ${deleteTarget?.code || ''} ${deleteTarget?.title || ''}`"
      :impact="['历史执行记录与版本快照保留', '已纳入测试计划的计划行不受影响', '删除后不可在用例库检索到']"
      :reason-required="false"
      confirm-text="确认删除"
      tone="danger"
      :loading="deleting"
      @confirm="confirmDelete"
    />

    <ImportWizard
      v-model="importVisible"
      title="导入用例"
      template-name="用例导入模板.xlsx"
      accept=".xlsx,.xls,.csv"
      :validate="validateImport"
      @imported="onImported"
    />
  </div>
</template>

<style scoped>
.dl-pager {
  display: flex;
  justify-content: flex-end;
  padding: 8px 12px;
}

.link-btn {
  border: 0;
  background: none;
  color: var(--color-primary);
  font-size: 12px;
  cursor: pointer;
}

.link-btn:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}

.dl-tree__empty {
  padding: 8px 10px;
  color: var(--text-muted-aa);
  font-size: 12px;
  line-height: 1.6;
}

.case-form {
  padding-top: 4px;
}

.drawer-footer {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0 0;
  margin-top: 16px;
  border-top: 1px solid var(--border);
  background: var(--bg-card);
}
</style>
