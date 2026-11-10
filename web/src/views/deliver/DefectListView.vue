<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  actOnDefect,
  createDefect,
  defectBoardItems,
  exportDefectsCsv,
  listDefects,
} from '@/api/deliver'
import { listReleases, searchRequirements } from '@/api/req'
import {
  listDictionaryDomains,
  listDictionaryUsers,
  listDictionaryVendors,
} from '@/api/dictionaries'
import type { DefectRow, ReleaseRow, RequirementRow, Severity } from '@/api/types'
import type { DictionaryDomain, DictionaryUser, DictionaryVendor } from '@/api/dictionaries'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import { useDictStore } from '@/stores/dict'
import { formatDate, formatDateTime } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PriorityDot from '@/components/PriorityDot.vue'
import ScopeChip from '@/components/ScopeChip.vue'
import StatusPill from '@/components/StatusPill.vue'
import '@/views/deliver/shared.css'

/**
 * 缺陷列表（DESIGN P5 / AC-13、AC-18）。
 * - 摘要视图是“权限驱动的裁剪”：没有处理权限的角色只看到状态/严重级/预计修复，
 *   描述、复现步骤、处理人一律不渲染（不是用 CSS 藏起来）。
 * - 行内「指派/流转」受 defect:handle 控制；流转本身在详情页的动作条完成。
 */
const route = useRoute()
const router = useRouter()
const perm = usePermission()
const dict = useDictStore()

const STATUSES = ['NEW', 'IN_PROGRESS', 'PENDING_VERIFY', 'CLOSED'] as const
const SEVERITIES: Severity[] = ['P0', 'P1', 'P2', 'P3']

const defaults = {
  status: '',
  severity: '',
  domainId: '',
  vendorId: '',
  releaseId: '',
  handlerId: '',
}

const {
  rows,
  total,
  loading,
  error,
  page,
  size,
  filters,
  isEmpty,
  isFiltered,
  load,
  refresh,
  setPage,
  setFilter,
  setFilters,
} = useListQuery<DefectRow>(
  (query) => {
    const metric = query.filters.metric
    // 看板下钻必须与卡片同源：metric 存在时走 defectBoardItems，服务端保证口径一致
    if (metric) {
      return defectBoardItems({ ...query.filters, metric, page: query.page, size: query.size })
    }
    return listDefects({
      ...query.filters,
      page: query.page,
      size: query.size,
    })
  },
  defaults,
  { size: 20 },
)

const canHandle = computed(() => perm.can('defect:handle'))
const canPickPeople = computed(() => perm.can('org:manage'))
const forcedSummary = computed(() => !canHandle.value && !perm.can('defect:board'))
const summaryMode = computed(
  () => forcedSummary.value || route.query.summary === '1',
)

const releases = ref<ReleaseRow[]>([])
const users = ref<DictionaryUser[]>([])
const domains = ref<DictionaryDomain[]>([])
const vendors = ref<DictionaryVendor[]>([])
const loadingOptions = ref(false)
const today = new Date().toISOString().slice(0, 10)

const canFilterDomain = computed(() => perm.can('org:manage'))

async function loadOptions(): Promise<void> {
  loadingOptions.value = true
  try {
    const [releasePage, userPage, domainList, vendorList] = await Promise.all([
      listReleases({ page: 1, size: 100 }),
      canPickPeople.value ? listDictionaryUsers() : Promise.resolve(null),
      canFilterDomain.value ? listDictionaryDomains() : Promise.resolve([]),
      canFilterDomain.value ? listDictionaryVendors() : Promise.resolve([]),
    ])
    releases.value = releasePage.items ?? []
    users.value = userPage ?? []
    domains.value = domainList
    vendors.value = vendorList
  } catch {
    // 选项失败不阻断列表：只是筛选少几个下拉，错误在列表本身会显示
    releases.value = []
  } finally {
    loadingOptions.value = false
  }
}

/* ---------------- 新建 ---------------- */

const createVisible = ref(false)
const submitting = ref(false)
const reqLoading = ref(false)
const reqOptions = ref<RequirementRow[]>([])
const form = reactive({
  title: '',
  description: '',
  reproSteps: '',
  severity: 'P2' as Severity,
  priority: 'P2',
  requirementId: undefined as number | undefined,
  releaseId: undefined as number | undefined,
  handlerId: undefined as number | undefined,
  expectedFixDate: '',
})

function openCreate(): void {
  Object.assign(form, {
    title: '',
    description: '',
    reproSteps: '',
    severity: 'P2',
    priority: 'P2',
    requirementId: undefined,
    releaseId: undefined,
    handlerId: undefined,
    expectedFixDate: '',
  })
  reqOptions.value = []
  createVisible.value = true
}

async function searchReqs(keyword: string): Promise<void> {
  if (!keyword.trim()) {
    reqOptions.value = []
    return
  }
  reqLoading.value = true
  try {
    reqOptions.value = await searchRequirements(keyword.trim())
  } catch {
    reqOptions.value = []
  } finally {
    reqLoading.value = false
  }
}

async function submitCreate(): Promise<void> {
  if (!form.title.trim()) {
    ElMessage.warning('请填写缺陷标题')
    return
  }
  if (!form.description.trim()) {
    ElMessage.warning('请填写缺陷描述（复现依据）')
    return
  }
  submitting.value = true
  try {
    const created = await createDefect({
      title: form.title.trim(),
      description: form.description.trim(),
      reproSteps: form.reproSteps.trim() || undefined,
      severity: form.severity,
      priority: form.priority,
      requirementId: form.requirementId,
      releaseId: form.releaseId,
      handlerId: form.handlerId,
      expectedFixDate: form.expectedFixDate || undefined,
    })
    createVisible.value = false
    ElMessage.success(`缺陷 ${created.code} 已创建`)
    await refresh()
    void router.push({ name: 'defect-detail', params: { id: created.id } })
  } catch (err) {
    ElMessage.error((err as Error).message || '创建失败')
  } finally {
    submitting.value = false
  }
}

/* ---------------- 指派 ---------------- */

const assignVisible = ref(false)
const assignTarget = ref<DefectRow | null>(null)
const assignForm = reactive({ assigneeId: undefined as number | undefined, comment: '' })
const assigning = ref(false)

function openAssign(row: DefectRow): void {
  assignTarget.value = row
  assignForm.assigneeId = row.handlerId ?? undefined
  assignForm.comment = ''
  assignVisible.value = true
}

async function submitAssign(): Promise<void> {
  const target = assignTarget.value
  if (!target) return
  if (!assignForm.assigneeId) {
    ElMessage.warning('请选择处理人')
    return
  }
  assigning.value = true
  try {
    await actOnDefect(target.id, {
      action: 'assign',
      assigneeId: assignForm.assigneeId,
      comment: assignForm.comment || undefined,
    })
    assignVisible.value = false
    ElMessage.success('已指派')
    await refresh()
  } catch (err) {
    ElMessage.error((err as Error).message || '指派失败')
  } finally {
    assigning.value = false
  }
}

/* ---------------- 展示 / 导出 ---------------- */

function statusLabel(value: string): string {
  return dict.meta('defect', value).label
}

function metricLabel(metric: string): string {
  const labels: Record<string, string> = {
    openHigh: '未关闭 P0/P1',
    density: '缺陷密度',
    reopenRate: '重开率',
    mttr: '平均修复时长',
  }
  return labels[metric] ?? metric
}

function openDetail(row: DefectRow): void {
  void router.push({ name: 'defect-detail', params: { id: row.id } })
}

function toggleSummary(): void {
  if (forcedSummary.value) return
  const query = { ...route.query }
  if (summaryMode.value) delete query.summary
  else query.summary = '1'
  void router.replace({ query })
}

async function exportCsv(): Promise<void> {
  try {
    await exportDefectsCsv({ ...filters.value })
    ElMessage.success('已导出当前筛选结果')
  } catch (err) {
    ElMessage.error((err as Error).message || '导出失败')
  }
}

function resetFilters(): void {
  void setFilters({ ...defaults })
}

onMounted(() => {
  void loadOptions()
})
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">缺陷</h1>
        <span class="count-badge">{{ total }}</span>
        <ScopeChip v-if="forcedSummary" label="金额与处理人字段受限" tone="info" />
      </div>
      <div class="dl-head__actions">
        <el-button @click="router.push({ name: 'defect-board' })">质量看板</el-button>
        <el-tooltip
          :content="forcedSummary ? '摘要视图由权限固定，无法切换' : '只显示状态 / 严重级 / 预计修复时间'"
          placement="bottom"
        >
          <el-button
            :type="summaryMode ? 'primary' : 'default'"
            :plain="!summaryMode"
            :disabled="forcedSummary"
            @click="toggleSummary"
          >
            摘要视图
          </el-button>
        </el-tooltip>
        <el-button @click="exportCsv">导出 CSV</el-button>
        <el-button type="primary" @click="openCreate">新建缺陷</el-button>
      </div>
    </header>

    <div v-if="summaryMode" class="dl-summary-note">
      <span aria-hidden="true">⚪</span>
      摘要视图：这是授权范围内的结果，描述、复现步骤、附件与处理人字段不提供。
    </div>

    <section class="surface surface--pad">
      <div class="dl-filters">
        <el-select
          :model-value="filters.status"
          placeholder="状态"
          clearable
          @update:model-value="setFilter('status', $event)"
        >
          <el-option v-for="item in STATUSES" :key="item" :label="statusLabel(item)" :value="item" />
        </el-select>
        <el-select
          :model-value="filters.severity"
          placeholder="严重级"
          clearable
          @update:model-value="setFilter('severity', $event)"
        >
          <el-option v-for="item in SEVERITIES" :key="item" :label="item" :value="item" />
        </el-select>
        <el-select
          v-if="canFilterDomain"
          :model-value="filters.domainId"
          placeholder="业务域"
          clearable
          filterable
          @update:model-value="setFilter('domainId', $event)"
        >
          <el-option
            v-for="domain in domains"
            :key="domain.id"
            :label="domain.name"
            :value="String(domain.id)"
          />
        </el-select>
        <el-select
          v-if="canFilterDomain"
          :model-value="filters.vendorId"
          placeholder="厂商"
          clearable
          filterable
          @update:model-value="setFilter('vendorId', $event)"
        >
          <el-option
            v-for="vendor in vendors"
            :key="vendor.id"
            :label="vendor.name"
            :value="String(vendor.id)"
          />
        </el-select>
        <el-select
          :model-value="filters.releaseId"
          placeholder="版本"
          clearable
          :loading="loadingOptions"
          @update:model-value="setFilter('releaseId', $event)"
        >
          <el-option
            v-for="release in releases"
            :key="release.id"
            :label="release.name"
            :value="String(release.id)"
          />
        </el-select>
        <el-select
          v-if="users.length"
          :model-value="filters.handlerId"
          placeholder="处理人"
          clearable
          @update:model-value="setFilter('handlerId', $event)"
        >
          <el-option
            v-for="user in users"
            :key="user.id"
            :label="user.name"
            :value="String(user.id)"
          />
        </el-select>
        <div class="dl-filters__spacer" />
        <el-button link :disabled="!isFiltered" @click="resetFilters">重置筛选</el-button>
      </div>
    </section>

    <div v-if="filters.metric" class="drill-note">
      <span>正在按「{{ metricLabel(filters.metric) }}」口径下钻，明细与服务端看板卡片同源。</span>
      <el-button link type="primary" @click="setFilter('metric', '')">清除指标筛选</el-button>
    </div>

    <section class="surface">
      <ErrorState
        v-if="error"
        :message="error.message"
        :code="error.code"
        :status="error.status"
        @retry="load()"
      />
      <EmptyState
        v-else-if="isEmpty"
        :variant="isFiltered ? 'filtered' : 'empty'"
        :title="isFiltered ? '当前筛选下没有缺陷' : '还没有缺陷'"
        :description="isFiltered ? '放宽筛选条件或切换状态看看' : '从测试执行失败或手工提报开始'"
        icon="inbox"
        :action-text="isFiltered ? '重置筛选' : '新建缺陷'"
        @action="isFiltered ? resetFilters() : openCreate()"
      />
      <template v-else>
        <el-table
          v-loading="loading"
          :data="rows"
          row-key="id"
          class="dl-table"
          @row-click="openDetail"
        >
          <el-table-column prop="code" label="编号" width="116">
            <template #default="{ row }">
              <span class="t-code">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="220">
            <template #default="{ row }">
              <div class="cell-title">
                <span>{{ row.title }}</span>
                <span v-if="row.reopenCount" class="reopen-chip">重开 ×{{ row.reopenCount }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="104">
            <template #default="{ row }">
              <StatusPill kind="defect" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="严重级" width="88">
            <template #default="{ row }">
              <PriorityDot :value="row.severity" />
            </template>
          </el-table-column>
          <template v-if="!summaryMode">
            <el-table-column prop="requirementCode" label="关联需求" width="128">
              <template #default="{ row }">
                <span v-if="row.requirementCode" class="t-code">{{ row.requirementCode }}</span>
                <span v-else class="ink-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="handlerName" label="处理人" width="112">
              <template #default="{ row }">
                <span v-if="row.handlerName">{{ row.handlerName }}</span>
                <span v-else class="ink-muted">未指派</span>
              </template>
            </el-table-column>
            <el-table-column label="发现时间" width="132">
              <template #default="{ row }">{{ formatDateTime(row.foundAt) }}</template>
            </el-table-column>
          </template>
          <el-table-column label="预计修复" width="112">
            <template #default="{ row }">
              <span :class="{ 'ink-danger': row.expectedFixDate && !row.closedAt && row.expectedFixDate < today }">
                {{ row.expectedFixDate ? formatDate(row.expectedFixDate) : '—' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column v-if="canHandle" label="操作" width="132" fixed="right">
            <template #default="{ row }">
              <div class="row-actions" @click.stop>
                <el-button link type="primary" @click="openAssign(row)">指派</el-button>
                <el-button link type="primary" @click="openDetail(row)">流转</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div class="table-foot">
          <el-pagination
            layout="total, prev, pager, next"
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

    <el-dialog v-model="createVisible" title="新建缺陷" width="560px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="120" show-word-limit placeholder="一句话说清现象" />
        </el-form-item>
        <el-form-item label="描述" required>
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit
            placeholder="期望结果 vs 实际结果"
          />
        </el-form-item>
        <el-form-item label="复现步骤">
          <el-input v-model="form.reproSteps" type="textarea" :rows="2" placeholder="1. … 2. …" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="严重级" required>
            <el-select v-model="form.severity">
              <el-option v-for="item in SEVERITIES" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级">
            <el-select v-model="form.priority">
              <el-option v-for="item in SEVERITIES" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
          <el-form-item label="关联需求">
            <el-select
              v-model="form.requirementId"
              filterable
              remote
              clearable
              :remote-method="searchReqs"
              :loading="reqLoading"
              placeholder="输入需求编号/标题搜索"
            >
              <el-option
                v-for="item in reqOptions"
                :key="item.id"
                :label="`${item.code} ${item.title}`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="归属版本">
            <el-select v-model="form.releaseId" clearable placeholder="可不选">
              <el-option
                v-for="release in releases"
                :key="release.id"
                :label="release.name"
                :value="release.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="处理人">
            <el-select v-model="form.handlerId" clearable :disabled="!users.length" placeholder="未指派">
              <el-option
                v-for="user in users"
                :key="user.id"
                :label="user.name"
                :value="user.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="预计修复日期">
            <el-date-picker
              v-model="form.expectedFixDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择日期"
            />
          </el-form-item>
        </div>
        <p class="dl-hint">归属（业务域 / 厂商）由服务端按需求与提交人派生，不接受请求体伪造。</p>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="assignVisible" title="指派处理人" width="440px">
      <el-form label-position="top">
        <el-form-item label="缺陷">
          <span class="t-code">{{ assignTarget?.code }}</span>
          <span class="assign-title">{{ assignTarget?.title }}</span>
        </el-form-item>
        <el-form-item label="处理人" required>
          <el-select v-if="users.length" v-model="assignForm.assigneeId" filterable placeholder="选择处理人">
            <el-option v-for="user in users" :key="user.id" :label="user.name" :value="user.id" />
          </el-select>
          <el-input-number
            v-else
            v-model="assignForm.assigneeId"
            :min="1"
            :controls="false"
            placeholder="处理人 ID"
          />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="assignForm.comment" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="submitAssign">确定指派</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.drill-note {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid #cfe0fb;
  border-radius: var(--r-card);
  background: var(--color-primary-light);
  color: var(--text-body);
  font-size: 12px;
}

.cell-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.reopen-chip {
  flex: none;
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  border-radius: var(--r-pill);
  background: var(--warning-bg);
  color: var(--warning-ink);
  font-size: 11px;
}

.table-foot {
  display: flex;
  justify-content: flex-end;
  padding: 10px 12px;
  border-top: 1px solid var(--divider);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.form-grid :deep(.el-select),
.form-grid :deep(.el-date-editor) {
  width: 100%;
}

.assign-title {
  margin-left: 8px;
  color: var(--text-body);
}

:deep(.el-table__row) {
  cursor: pointer;
}

@media (max-width: 720px) {
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
