<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createTestPlan, listTestCases, listTestPlans } from '@/api/deliver'
import { planApi } from '@/api/quality'
import { listDictionaryUsers } from '@/api/dictionaries'
import { listReleases } from '@/api/req'
import type { ReleaseRow, TestCaseRow } from '@/api/types'
import type { DictionaryUser } from '@/api/dictionaries'
import { usePermission } from '@/composables/usePermission'
import StatusPill from '@/components/StatusPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { formatDate, formatNumber, formatPercent } from '@/utils/format'
import '@/views/deliver/shared.css'

/**
 * 测试计划列表（DESIGN Q3 / AC-1）。
 *
 * 列表不自己算任何比率：通过率/覆盖率一律展示服务端字段，缺字段显示“—”（不是 0%）。
 * 状态 tab 的计数是“服务端 total”——每个 tab 一次 `size=1` 查询，不把分页数据拿到前端再数。
 */
interface PlanRow {
  id: number
  code: string
  name: string
  releaseId?: number
  status?: string
  gateMode?: string
  windowStart?: string
  windowEnd?: string
  ownerName?: string
  planned?: number
  executed?: number
  revision?: number
  roundNo?: number
  passRate?: number | null
  coverage?: number | null
  openP0P1?: number | null
}

const STATUS_TABS = [
  { value: '', label: '全部' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'PENDING_REVIEW', label: '待评审' },
  { value: 'APPROVED', label: '已批准' },
  { value: 'IN_PROGRESS', label: '执行中' },
  { value: 'COMPLETED', label: '已完成' },
] as const

const route = useRoute()
const router = useRouter()
const { can } = usePermission()
const canManage = computed(() => can('plan:manage'))
const canApprove = computed(() => can('plan:approve'))

const releases = ref<ReleaseRow[]>([])
const users = ref<DictionaryUser[]>([])
const releaseId = ref(String(route.query.releaseId ?? ''))
const status = ref(String(route.query.status ?? ''))
const keyword = ref('')
const rows = ref<PlanRow[]>([])
const total = ref(0)
const counts = ref<Record<string, number>>({})
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const conflict = ref<{ code?: string; message: string } | null>(null)

/** 后端支持 status/keyword/ownerId，前端类型里还没有这些字段时用宽松签名透传。 */
const queryPlans = (params: Record<string, unknown>) =>
  listTestPlans(params as Parameters<typeof listTestPlans>[0])

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await queryPlans({
      releaseId: releaseId.value || undefined,
      status: status.value || undefined,
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = (data.items ?? []) as unknown as PlanRow[]
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

/** 计数 = 每个状态的 total（size=1），失败不阻塞列表。 */
async function loadCounts(): Promise<void> {
  const results = await Promise.all(
    STATUS_TABS.map((tab) =>
      queryPlans({
        releaseId: releaseId.value || undefined,
        status: tab.value || undefined,
        keyword: keyword.value.trim() || undefined,
        page: 1,
        size: 1,
      }).catch(() => null),
    ),
  )
  const next: Record<string, number> = {}
  STATUS_TABS.forEach((tab, index) => {
    next[tab.value || 'ALL'] = results[index]?.total ?? 0
  })
  counts.value = next
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
    users.value = await listDictionaryUsers()
  } catch {
    users.value = []
  }
}

function switchStatus(value: string): void {
  status.value = value
  page.value = 1
  void router.replace({ query: { ...route.query, status: value || undefined } })
  void load()
}

function changeRelease(value: string | undefined): void {
  releaseId.value = value ?? ''
  page.value = 1
  void router.replace({ query: { ...route.query, releaseId: releaseId.value || undefined } })
  void Promise.all([load(), loadCounts()])
}

function search(): void {
  page.value = 1
  void Promise.all([load(), loadCounts()])
}

function openPlan(row: PlanRow): void {
  void router.push({ name: 'test-plan-detail', params: { id: row.id } })
}

function executePlan(row: PlanRow): void {
  void router.push({ name: 'test-plan-execute', params: { id: row.id } })
}

function setPage(next: number): void {
  page.value = next
  void load()
}

/* ---------------- 新建计划（三步抽屉） ---------------- */

const createVisible = ref(false)
const creating = ref(false)
const step = ref(0)
const form = reactive({
  releaseId: '',
  name: '',
  ownerId: undefined as number | undefined,
  windowStart: '',
  windowEnd: '',
})
const caseKeyword = ref('')
const casePool = ref<TestCaseRow[]>([])
const caseTotal = ref(0)
const casePage = ref(1)
const selectedCases = ref<TestCaseRow[]>([])
const casesLoading = ref(false)
const criteria = reactive({
  passRate: true,
  passRateValue: 95,
  openP0P1: true,
  openP0P1Value: 0,
  roundClosed: true,
  coverage: false,
  coverageValue: 100,
})

function resetCreate(): void {
  step.value = 0
  form.releaseId = releaseId.value || (releases.value[0] ? String(releases.value[0].id) : '')
  form.name = ''
  form.ownerId = undefined
  const release = releases.value.find((item) => String(item.id) === form.releaseId)
  form.windowStart = release?.startDate ?? ''
  form.windowEnd = release?.endDate ?? ''
  caseKeyword.value = ''
  casePage.value = 1
  selectedCases.value = []
  conflict.value = null
}

async function openCreate(): Promise<void> {
  resetCreate()
  createVisible.value = true
  await loadCasePool()
}

async function loadCasePool(): Promise<void> {
  casesLoading.value = true
  try {
    const data = await listTestCases({
      keyword: caseKeyword.value.trim() || undefined,
      page: casePage.value,
      size: 10,
    })
    casePool.value = data.items ?? []
    caseTotal.value = data.total ?? 0
  } catch {
    casePool.value = []
    caseTotal.value = 0
  } finally {
    casesLoading.value = false
  }
}

function onCaseSelection(selection: TestCaseRow[]): void {
  selectedCases.value = selection
}

function changeCasePage(next: number): void {
  casePage.value = next
  void loadCasePool()
}

function changeFormRelease(value: string): void {
  form.releaseId = value
  const release = releases.value.find((item) => String(item.id) === value)
  if (release) {
    form.windowStart = release.startDate ?? form.windowStart
    form.windowEnd = release.endDate ?? form.windowEnd
  }
}

function buildExitCriteria(): string | null {
  const items: Array<{ key: string; op: string; value: number }> = []
  if (criteria.passRate) items.push({ key: 'passRate', op: '>=', value: criteria.passRateValue })
  if (criteria.openP0P1) items.push({ key: 'openP0P1', op: '==', value: criteria.openP0P1Value })
  if (criteria.roundClosed) items.push({ key: 'roundClosed', op: '==', value: 1 })
  if (criteria.coverage) items.push({ key: 'coverage', op: '>=', value: criteria.coverageValue })
  return items.length ? JSON.stringify({ items }) : null
}

function nextStep(): void {
  if (step.value === 0) {
    if (!form.releaseId) {
      ElMessage.warning('请选择版本')
      return
    }
    if (!form.name.trim()) {
      ElMessage.warning('请填写计划名称')
      return
    }
    if (!form.windowStart || !form.windowEnd) {
      ElMessage.warning('请填写计划窗口')
      return
    }
    if (form.windowStart > form.windowEnd) {
      ElMessage.warning('开始日期不能晚于结束日期')
      return
    }
  }
  step.value = Math.min(step.value + 1, 2)
}

async function submitCreate(): Promise<void> {
  if (!form.releaseId || !form.name.trim()) {
    step.value = 0
    return
  }
  creating.value = true
  conflict.value = null
  try {
    const created = await createTestPlan({
      releaseId: Number(form.releaseId),
      name: form.name.trim(),
      ownerId: form.ownerId,
      windowStart: form.windowStart || undefined,
      windowEnd: form.windowEnd || undefined,
      caseIds: selectedCases.value.map((item) => item.id),
    })
    const exitCriteria = buildExitCriteria()
    if (exitCriteria) {
      // 准出条件走变更接口（创建接口只吃基本信息）：仍是草稿，不带 reason
      await planApi.update(created.id, { exitCriteria }).catch(() => null)
    }
    createVisible.value = false
    ElMessage.success(`计划 ${created.code} 已创建`)
    void router.push({ name: 'test-plan-detail', params: { id: created.id } })
  } catch (err) {
    conflict.value = {
      code: (err as { code?: string }).code,
      message: (err as Error).message || '创建失败',
    }
    if ((err as { code?: string }).code === 'PLAN_INCOMPLETE') step.value = 0
  } finally {
    creating.value = false
  }
}

onMounted(async () => {
  await loadOptions()
  await Promise.all([load(), loadCounts()])
})
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1 class="t-page-title">测试计划</h1>
        <span class="dl-meta">{{ formatNumber(total) }} 个计划 · 用例纳入时钉住当前版本</span>
      </div>
      <div class="dl-head__actions">
        <el-button type="primary" :disabled="!canManage" :title="canManage ? '' : '缺少 plan:manage 权限'" @click="openCreate">
          新建计划
        </el-button>
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
          <el-option v-for="release in releases" :key="release.id" :label="release.name" :value="String(release.id)" />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="搜索计划编号 / 名称，回车"
          clearable
          style="width: 240px"
          @keyup.enter="search"
          @clear="search"
        />
        <div class="dl-filters__spacer" />
        <el-button link :loading="loading" @click="search">刷新</el-button>
      </div>

      <div class="tabs" role="tablist" aria-label="计划状态">
        <button
          v-for="tab in STATUS_TABS"
          :key="tab.value"
          type="button"
          role="tab"
          class="tabs__item"
          :class="{ 'is-active': status === tab.value }"
          :aria-selected="status === tab.value"
          @click="switchStatus(tab.value)"
        >
          {{ tab.label }}
          <span class="tabs__count">{{ counts[tab.value || 'ALL'] ?? 0 }}</span>
        </button>
      </div>
    </section>

    <section class="surface">
      <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />
      <EmptyState
        v-else-if="!loading && !rows.length"
        :title="status || releaseId || keyword ? '没有符合条件的计划' : '还没有测试计划'"
        :variant="status || releaseId || keyword ? 'filtered' : 'empty'"
        description="计划 = 版本下的一组用例，执行结果用于门禁判定"
        icon="file"
        :action-text="canManage && !status && !keyword ? '新建计划' : undefined"
        @action="openCreate"
      />
      <template v-else>
        <el-table v-loading="loading" :data="rows" row-key="id" @row-click="openPlan">
          <el-table-column prop="code" label="编号" width="120">
            <template #default="{ row }"><span class="t-code">{{ row.code }}</span></template>
          </el-table-column>
          <el-table-column label="计划" min-width="220">
            <template #default="{ row }">
              <div class="plan-name">
                <span class="plan-name__text">{{ row.name }}</span>
                <span v-if="row.revision > 1" class="plan-name__rev">r{{ row.revision }}</span>
                <span
                  v-if="row.status === 'PENDING_REVIEW' && canApprove"
                  class="plan-name__chip"
                >待我批准</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="170">
            <template #default="{ row }">{{ releases.find((item) => item.id === row.releaseId)?.name || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }"><StatusPill kind="plan" :value="row.status" size="sm" /></template>
          </el-table-column>
          <el-table-column label="轮次" width="80" align="right">
            <template #default="{ row }">{{ row.roundNo ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="通过率" width="92" align="right">
            <template #default="{ row }"><span class="num">{{ formatPercent(row.passRate) }}</span></template>
          </el-table-column>
          <el-table-column label="覆盖率" width="92" align="right">
            <template #default="{ row }"><span class="num">{{ formatPercent(row.coverage) }}</span></template>
          </el-table-column>
          <el-table-column label="执行" width="100" align="right">
            <template #default="{ row }">
              <span class="num">{{ row.executed ?? 0 }}/{{ row.planned ?? 0 }}</span>
            </template>
          </el-table-column>
          <el-table-column label="未关闭 P0/P1" width="120" align="right">
            <template #default="{ row }">
              <span class="num" :class="{ 'is-danger': (row.openP0P1 ?? 0) > 0 }">{{ row.openP0P1 ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="负责人" width="110">
            <template #default="{ row }">{{ row.ownerName || '—' }}</template>
          </el-table-column>
          <el-table-column label="窗口" width="180">
            <template #default="{ row }">
              <span class="muted">{{ formatDate(row.windowStart) }} → {{ formatDate(row.windowEnd) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <div class="row-actions" @click.stop>
                <el-button link type="primary" @click="openPlan(row)">打开</el-button>
                <el-button link @click="executePlan(row)">执行</el-button>
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

    <el-drawer v-model="createVisible" :size="720" :close-on-click-modal="false" class="plan-drawer">
      <template #header>
        <div class="dl-drawer-head">
          <div>
            <div class="dl-drawer-title">新建测试计划</div>
            <div class="dl-hint" style="margin: 2px 0 0">三步：基本信息 → 用例范围 → 准出条件</div>
          </div>
        </div>
      </template>

      <el-steps :active="step" simple class="plan-steps">
        <el-step title="基本信息" />
        <el-step title="用例范围" />
        <el-step title="准出条件" />
      </el-steps>

      <div v-if="conflict" class="dl-conflict" role="alert" tabindex="-1">
        <strong>创建未完成</strong>
        <span v-if="conflict.code" class="dl-conflict__code">{{ conflict.code }}</span>
        <p>{{ conflict.message }}</p>
      </div>

      <div v-show="step === 0" class="plan-form">
        <el-form label-position="top">
          <el-form-item label="版本" required>
            <el-select :model-value="form.releaseId" filterable style="width: 100%" @update:model-value="changeFormRelease">
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
            <el-form-item label="开始" required>
              <el-date-picker v-model="form.windowStart" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
            <el-form-item label="结束" required>
              <el-date-picker v-model="form.windowEnd" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </div>
        </el-form>
      </div>

      <div v-show="step === 1" class="plan-cases">
        <div class="dl-filters">
          <el-input v-model="caseKeyword" placeholder="搜索用例编号 / 标题" clearable style="width: 240px" @keyup.enter="loadCasePool" @clear="loadCasePool" />
          <div class="dl-filters__spacer" />
          <span class="dl-meta">已选 {{ selectedCases.length }} 条</span>
        </div>
        <el-table
          v-loading="casesLoading"
          :data="casePool"
          row-key="id"
          size="small"
          height="360"
          @selection-change="onCaseSelection"
        >
          <el-table-column type="selection" width="44" reserve-selection />
          <el-table-column prop="code" label="编号" width="120" />
          <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
          <el-table-column prop="priority" label="优先级" width="90" />
          <el-table-column prop="version" label="版本" width="70" />
        </el-table>
        <div class="dl-pager">
          <el-pagination
            layout="prev, pager, next"
            :current-page="casePage"
            :page-size="10"
            :total="caseTotal"
            background
            small
            @current-change="changeCasePage"
          />
        </div>
        <p class="dl-hint">纳入了 {{ selectedCases.length }} 条用例；不选用例也可以先建计划，稍后在详情页纳入。</p>
      </div>

      <div v-show="step === 2" class="plan-criteria">
        <p class="dl-hint">准出条件在提交完成时由服务端求值；这里只写“要什么”，判定永远不在前端做。</p>
        <div class="criterion">
          <el-checkbox v-model="criteria.passRate">通过率 ≥</el-checkbox>
          <el-input-number v-model="criteria.passRateValue" :min="0" :max="100" :step="1" size="small" />
          <span class="criterion__unit">%</span>
        </div>
        <div class="criterion">
          <el-checkbox v-model="criteria.openP0P1">未关闭 P0/P1 缺陷数 =</el-checkbox>
          <el-input-number v-model="criteria.openP0P1Value" :min="0" :step="1" size="small" />
        </div>
        <div class="criterion">
          <el-checkbox v-model="criteria.coverage">覆盖率 ≥</el-checkbox>
          <el-input-number v-model="criteria.coverageValue" :min="0" :max="100" :step="1" size="small" />
          <span class="criterion__unit">%</span>
        </div>
        <div class="criterion">
          <el-checkbox v-model="criteria.roundClosed">轮次已关闭</el-checkbox>
        </div>
      </div>

      <template #footer>
        <div class="drawer-footer">
          <el-button @click="createVisible = false">取消</el-button>
          <div class="drawer-footer__right">
            <el-button v-if="step > 0" @click="step -= 1">上一步</el-button>
            <el-button v-if="step < 2" @click="nextStep">下一步</el-button>
            <el-button v-else type="primary" :loading="creating" @click="submitCreate">创建计划</el-button>
          </div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 2px;
  margin-top: 12px;
  border-bottom: 1px solid var(--border);
}

.tabs__item {
  position: relative;
  height: 34px;
  padding: 0 12px;
  border: 0;
  background: transparent;
  color: var(--text-body);
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: background-color var(--t-color) var(--ease);
}

.tabs__item:hover {
  background: var(--bg-hover);
}

.tabs__item:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.tabs__item.is-active {
  color: var(--color-primary);
  font-weight: 600;
}

.tabs__item.is-active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--color-primary);
}

.tabs__count {
  margin-left: 4px;
  color: var(--text-muted-aa);
  font-weight: 400;
  font-variant-numeric: tabular-nums;
}

.plan-name {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.plan-name__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plan-name__rev {
  flex: 0 0 auto;
  color: var(--text-muted-aa);
  font-size: 11px;
}

.plan-name__chip {
  flex: 0 0 auto;
  height: 18px;
  padding: 0 6px;
  border-radius: var(--r-cell);
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-size: 11px;
  line-height: 18px;
}

.num {
  font-variant-numeric: tabular-nums;
}

.num.is-danger {
  color: var(--danger-ink);
  font-weight: 600;
}

.muted {
  color: var(--text-muted-aa);
  font-size: 12px;
}

.plan-steps {
  margin-bottom: 16px;
}

.plan-form,
.plan-cases {
  padding-top: 4px;
}

.date-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.criterion {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid var(--divider);
}

.criterion__unit {
  color: var(--text-muted-aa);
  font-size: 12px;
}

.drawer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.drawer-footer__right {
  display: flex;
  gap: 8px;
}

.dl-conflict {
  margin-bottom: 12px;
  outline: none;
}
</style>
