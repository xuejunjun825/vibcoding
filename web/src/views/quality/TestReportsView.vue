<script setup lang="ts">
/**
 * 测试报告中心（DESIGN Q6 / AC-4）。
 *
 * 报告是“不可变版本”的入口：列表只读，生成动作由服务端校验
 * （RELEASE 无已批准计划 → 409 REPORT_NO_PLAN，PLAN 未批准 → 409 PLAN_NOT_APPROVED）。
 * 这里不提供任何“编辑报告”入口 —— 要改只能生成新版本。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { reportApi } from '@/api/quality'
import { listTestPlans } from '@/api/deliver'
import { listReleases } from '@/api/req'
import { ApiError } from '@/api/client'
import StatusPill from '@/components/StatusPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { formatPercent } from '@/utils/format'
import '@/views/deliver/shared.css'
import type { ReleaseRow, ReportRow } from '@/api/types'

interface ReportListRow extends ReportRow {
  planName?: string
  releaseName?: string
  releaseCode?: string
  signedCount?: number
  totalSignoffs?: number
  pendingRoleLabel?: string
  generatedByName?: string
  expiresAt?: string | null
  validityDays?: number
}

interface PlanOption {
  id: number
  code: string
  name: string
  status?: string
}

const router = useRouter()

const TABS = [
  { value: '', label: '全部' },
  { value: 'SIGNING', label: '会签中' },
  { value: 'SIGNED', label: '已会签' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'SUPERSEDED', label: '已被取代' },
]

const rows = ref<ReportListRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const statusFilter = ref('')
const scopeFilter = ref('')
const releaseFilter = ref<number | null>(null)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const releases = ref<ReleaseRow[]>([])

const generateOpen = ref(false)
const generating = ref(false)
const generateError = ref<{ message: string; code?: string } | null>(null)
const genScope = ref<'RELEASE' | 'PLAN'>('RELEASE')
const genReleaseId = ref<number | null>(null)
const genPlanId = ref<number | null>(null)
const genNote = ref('')
const plans = ref<PlanOption[]>([])
const plansLoading = ref(false)

const canGenerate = computed(() => !!genReleaseId.value && (genScope.value === 'RELEASE' || !!genPlanId.value))
const activeTabLabel = computed(
  () => TABS.find((tab) => tab.value === statusFilter.value)?.label ?? '全部',
)
const approvedPlanCount = computed(
  () => plans.value.filter((plan) => String(plan.status ?? '').toUpperCase() === 'APPROVED').length,
)

function messageOf(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 'REPORT_NO_PLAN') return '该版本没有已批准的测试计划，无法生成版本范围报告。先批准至少一个计划。'
    if (err.code === 'PLAN_NOT_APPROVED') return '该测试计划尚未批准，不能纳入报告。'
    return err.message || err.code || '请求失败'
  }
  return err instanceof Error ? err.message : '请求失败'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await reportApi.page({
      releaseId: releaseFilter.value ?? undefined,
      scope: scopeFilter.value || undefined,
      status: statusFilter.value || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = (data.items ?? []) as ReportListRow[]
    total.value = data.total ?? 0
  } catch (err) {
    error.value = {
      message: messageOf(err),
      code: err instanceof ApiError ? err.code : undefined,
      status: err instanceof ApiError ? err.status : undefined,
    }
  } finally {
    loading.value = false
  }
}

async function loadReleases(): Promise<void> {
  try {
    const data = await listReleases({ page: 1, size: 100 })
    releases.value = data.items ?? []
  } catch {
    releases.value = []
  }
}

async function loadPlans(): Promise<void> {
  if (!genReleaseId.value) {
    plans.value = []
    return
  }
  plansLoading.value = true
  try {
    const data = await listTestPlans({ releaseId: genReleaseId.value, page: 1, size: 100 })
    plans.value = data.items ?? []
  } catch {
    plans.value = []
  } finally {
    plansLoading.value = false
  }
}

function selectTab(value: string): void {
  if (statusFilter.value === value) return
  statusFilter.value = value
  page.value = 1
  void load()
}

function changePage(next: number): void {
  page.value = next
  void load()
}

function scopeLabel(row: ReportListRow): string {
  if (row.scope === 'PLAN') return row.planName || '计划范围'
  return row.releaseName || row.releaseCode || '版本范围'
}

function signoffText(row: ReportListRow): string {
  const signed = row.signedCount ?? 0
  const totalSign = row.totalSignoffs ?? 3
  if (row.status === 'SIGNED') return `已会签 ${signed}/${totalSign}`
  if (row.status === 'REJECTED') return '已驳回'
  const pending = row.pendingRoleLabel ? `待 ${row.pendingRoleLabel}` : '待签'
  return `${signed}/${totalSign} · ${pending}`
}

function openGenerate(): void {
  generateError.value = null
  generateOpen.value = true
  genScope.value = 'RELEASE'
  genReleaseId.value = releaseFilter.value
  genPlanId.value = null
  genNote.value = ''
  if (genReleaseId.value) void loadPlans()
}

async function submitGenerate(): Promise<void> {
  if (!canGenerate.value || generating.value) return
  generating.value = true
  generateError.value = null
  try {
    const detail = await reportApi.generate({
      scope: genScope.value,
      releaseId: genReleaseId.value ?? undefined,
      planId: genScope.value === 'PLAN' ? (genPlanId.value ?? undefined) : undefined,
      note: genNote.value.trim() || undefined,
    })
    ElMessage.success(`已生成报告 v${detail.currentVersion}`)
    generateOpen.value = false
    await router.push({ name: 'test-report-detail', params: { id: detail.report.id } })
  } catch (err) {
    generateError.value = { message: messageOf(err), code: err instanceof ApiError ? err.code : undefined }
  } finally {
    generating.value = false
  }
}

function openDetail(row: ReportRow): void {
  void router.push({ name: 'test-report-detail', params: { id: row.id } })
}

watch(genScope, () => {
  genPlanId.value = null
  if (genScope.value === 'PLAN') void loadPlans()
})
watch(genReleaseId, () => {
  genPlanId.value = null
  if (genScope.value === 'PLAN') void loadPlans()
})

onMounted(() => {
  void loadReleases()
  void load()
})
</script>

<template>
  <div class="dl-page report-list">
    <header class="dl-head">
      <div class="dl-head__title">
        <h1>测试报告</h1>
        <span class="count-badge">{{ total }}</span>
      </div>
      <div class="dl-head__actions">
        <el-button type="primary" @click="openGenerate">生成报告</el-button>
      </div>
    </header>

    <p class="dl-hint">
      报告一经生成即固化快照（8 段内容 + 结果 digest），后续执行与缺陷数据变化不会改写历史版本；
      要更新只能生成新版本，旧版本自动标记为「已被取代」。
    </p>

    <section class="surface surface--flush">
      <div class="report-tabs" role="tablist" aria-label="报告状态">
        <button
          v-for="tab in TABS"
          :key="tab.value"
          type="button"
          role="tab"
          class="chip-count"
          :class="{ 'is-active': statusFilter === tab.value }"
          :aria-selected="statusFilter === tab.value"
          @click="selectTab(tab.value)"
        >
          {{ tab.label }}
        </button>
      </div>

      <div class="filter-bar">
        <el-select
          v-model="releaseFilter"
          placeholder="全部版本"
          clearable
          filterable
          style="width: 200px"
          @change="page = 1; load()"
        >
          <el-option
            v-for="release in releases"
            :key="release.id"
            :value="release.id"
            :label="`${release.code} · ${release.name}`"
          />
        </el-select>
        <el-select
          v-model="scopeFilter"
          placeholder="全部范围"
          clearable
          style="width: 130px"
          @change="page = 1; load()"
        >
          <el-option value="RELEASE" label="版本范围" />
          <el-option value="PLAN" label="计划范围" />
        </el-select>
        <span class="dl-filters__spacer" />
        <span class="report-list__hint">当前：{{ activeTabLabel }}</span>
      </div>

      <ErrorState
        v-if="error"
        :message="error.message"
        :code="error.code"
        :status="error.status"
        @retry="load"
      />
      <div v-else-if="loading && !rows.length" class="report-list__loading" aria-busy="true">
        <div v-for="index in 6" :key="index" class="skeleton skeleton__row" />
      </div>
      <EmptyState
        v-else-if="!rows.length"
        variant="empty"
        icon="file"
        title="还没有测试报告"
        description="选择一个有已批准测试计划的版本生成报告；报告会冻结 8 段快照并进入三级会签。"
        action-text="生成报告"
        @action="openGenerate"
      />
      <table v-else class="dl-table report-table">
        <thead>
          <tr>
            <th>编号</th>
            <th>范围</th>
            <th class="is-num">当前版本</th>
            <th>结论</th>
            <th class="is-num">通过率</th>
            <th class="is-num">覆盖率</th>
            <th>会签</th>
            <th>有效期至</th>
            <th>生成人</th>
            <th class="is-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.id"
            class="report-row"
            tabindex="0"
            @click="openDetail(row)"
            @keydown.enter.prevent="openDetail(row)"
          >
            <td class="t-code">{{ row.code }}</td>
            <td>
              <span class="report-scope" :title="scopeLabel(row)">{{ scopeLabel(row) }}</span>
              <span class="report-scope__type">{{ row.scope === 'PLAN' ? '计划' : '版本' }}</span>
            </td>
            <td class="is-num">v{{ row.currentVersion }}</td>
            <td><StatusPill kind="report" :value="row.status" size="sm" /></td>
            <td class="is-num">{{ formatPercent(row.passRate) }}</td>
            <td class="is-num">{{ formatPercent(row.coverage) }}</td>
            <td>
              <span
                class="report-signoff"
                :class="{
                  'is-done': row.status === 'SIGNED',
                  'is-rejected': row.status === 'REJECTED',
                }"
              >
                {{ signoffText(row) }}
              </span>
            </td>
            <td class="report-expiry">{{ row.expiresAt || '—' }}</td>
            <td>{{ row.generatedByName || '—' }}</td>
            <td class="is-actions">
              <span class="row-actions">
                <el-button link type="primary" @click.stop="openDetail(row)">查看</el-button>
              </span>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="total > size" class="report-pager">
        <el-pagination
          layout="prev, pager, next, total"
          :total="total"
          :page-size="size"
          :current-page="page"
          background
          @current-change="changePage"
        />
      </div>
    </section>

    <el-dialog v-model="generateOpen" title="生成测试报告" width="520px" :close-on-click-modal="false">
      <div v-if="generateError" class="report-generate-error" role="alert">
        <span v-if="generateError.code" class="t-code">{{ generateError.code }}</span>
        <span>{{ generateError.message }}</span>
      </div>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="报告范围">
          <el-radio-group v-model="genScope">
            <el-radio-button value="RELEASE">版本范围</el-radio-button>
            <el-radio-button value="PLAN">计划范围</el-radio-button>
          </el-radio-group>
          <p class="report-form-hint">
            版本范围汇总该版本下所有已批准计划；计划范围只纳入指定计划。
          </p>
        </el-form-item>
        <el-form-item label="版本" required>
          <el-select v-model="genReleaseId" placeholder="选择版本" filterable style="width: 100%">
            <el-option
              v-for="release in releases"
              :key="release.id"
              :value="release.id"
              :label="`${release.code} · ${release.name}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="genScope === 'PLAN'" label="测试计划" required>
          <el-select
            v-model="genPlanId"
            :loading="plansLoading"
            placeholder="选择已批准的测试计划"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="plan in plans"
              :key="plan.id"
              :value="plan.id"
              :label="`${plan.code} · ${plan.name}`"
              :disabled="String(plan.status ?? '').toUpperCase() !== 'APPROVED'"
            >
              <span class="t-code report-plan-code">{{ plan.code }}</span>
              <span>{{ plan.name }}</span>
              <span v-if="String(plan.status ?? '').toUpperCase() !== 'APPROVED'" class="report-plan-tag">
                未批准
              </span>
            </el-option>
          </el-select>
          <p v-if="genReleaseId && !plansLoading" class="report-form-hint">
            已批准计划 {{ approvedPlanCount }} 个；未批准计划不可纳入报告。
          </p>
        </el-form-item>
        <el-form-item label="生成说明">
          <el-input
            v-model="genNote"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="可选：本次报告的说明（会写进快照的生成记录）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="generating" @click="generateOpen = false">取消</el-button>
        <el-tooltip
          :disabled="canGenerate"
          content="先选择版本；计划范围还需选择已批准计划"
          placement="top"
        >
          <span>
            <el-button
              type="primary"
              :loading="generating"
              :disabled="!canGenerate"
              @click="submitGenerate"
            >
              生成报告
            </el-button>
          </span>
        </el-tooltip>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.report-list__hint {
  font-size: 12px;
  color: var(--text-weak);
}
.report-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 16px 0;
  border-bottom: 1px solid var(--divider);
}
.report-table tbody tr {
  cursor: pointer;
}
.report-table tbody tr:hover {
  background: var(--bg-hover);
}
.report-table tbody tr:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
.report-table .is-num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.report-table .is-actions {
  width: 64px;
  text-align: right;
}
.report-scope {
  display: inline-block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.report-scope__type {
  margin-left: 6px;
  font-size: 11px;
  color: var(--text-weak);
}
.report-signoff {
  font-size: 12px;
  color: var(--warning-ink);
}
.report-signoff.is-done {
  color: var(--success-ink);
}
.report-signoff.is-rejected {
  color: var(--danger-ink);
}
.report-expiry {
  white-space: nowrap;
  color: var(--text-body);
}
.report-list__loading {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
}
.report-pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--divider);
}
.report-generate-error {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px 10px;
  border-left: 3px solid var(--color-danger);
  background: var(--color-danger-bg);
  border-radius: 0 var(--r-ctl) var(--r-ctl) 0;
  font-size: 12px;
  color: var(--text-body);
}
.report-form-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.report-plan-code {
  margin-right: 8px;
  color: var(--text-muted-aa);
}
.report-plan-tag {
  margin-left: 8px;
  font-size: 11px;
  color: var(--text-weak);
}
</style>
