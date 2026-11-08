<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  addCasesToPlan,
  defectFromExecution,
  evaluateTestGate,
  executeCase,
  getTestPlan,
  listTestCases,
  planSummary,
  removeCaseFromPlan,
} from '@/api/deliver'
import type {
  ExecutionResult,
  GateDecision,
  PlanCaseRow,
  PlanSummary,
  TestCaseRow,
  TestPlanDetail,
} from '@/api/types'
import { usePermission } from '@/composables/usePermission'
import { useDictStore } from '@/stores/dict'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import GateChecklist from '@/components/GateChecklist.vue'
import PassRateRing from '@/components/PassRateRing.vue'
import StatusPill from '@/components/StatusPill.vue'
import { formatDateTime } from '@/utils/format'
import '@/views/deliver/shared.css'

/**
 * 计划执行（DESIGN P11 / AC-16、AC-17）。
 * - 每条用例钉住 caseVersion，执行只写执行记录，不改用例主表；
 * - 通过率环/覆盖率来自服务端 planSummary，前端不重算；
 * - 门禁按需求评估，服务端是唯一判定者（前端只渲染 items/blockers）。
 */
const route = useRoute()
const router = useRouter()
const { can } = usePermission()
const dict = useDictStore()

const canManage = computed(() => can('plan:manage'))
const planId = computed(() => Number(route.params.id))

const detail = ref<TestPlanDetail | null>(null)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)

const drafts = reactive<Record<number, { result: ExecutionResult; note: string }>>({})
const savingCase = ref<number | null>(null)
const executionIds = reactive<Record<number, number>>({})
const filter = ref<'all' | 'pending' | 'failed'>('all')

const cases = computed(() => detail.value?.cases ?? [])
const summary = computed<PlanSummary | null>(() => detail.value?.summary ?? null)
const plan = computed(() => detail.value?.plan ?? null)

const visibleCases = computed(() => {
  if (filter.value === 'pending') return cases.value.filter((item) => item.result === 'NOT_RUN')
  if (filter.value === 'failed') return cases.value.filter((item) => item.result === 'FAIL')
  return cases.value
})

const pendingCount = computed(() => cases.value.filter((item) => item.result === 'NOT_RUN').length)
const failedCount = computed(() => cases.value.filter((item) => item.result === 'FAIL').length)

const gateMode = computed(() => plan.value?.gateMode ?? 'STRICT')
const gateRule = computed(() =>
  gateMode.value === 'STRICT'
    ? '严格：计划内必须全部通过（未执行 / 失败 / 阻塞 / 跳过 都阻断）'
    : '宽松：仅失败阻断，阻塞与跳过须填写原因',
)

function syncDrafts(): void {
  for (const key of Object.keys(drafts)) delete drafts[Number(key)]
  for (const item of cases.value) {
    drafts[item.caseId] = { result: item.result ?? 'NOT_RUN', note: item.note ?? '' }
  }
}

async function load(): Promise<void> {
  if (!planId.value) return
  loading.value = true
  error.value = null
  try {
    detail.value = await getTestPlan(planId.value)
    syncDrafts()
  } catch (err) {
    error.value = {
      message: (err as Error).message || '计划加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

async function refreshSummary(): Promise<void> {
  try {
    const data = await planSummary(planId.value)
    if (detail.value) detail.value.summary = data
  } catch {
    /* 汇总刷新失败不覆盖已有数据 */
  }
}

/* ---------------- 执行登记 ---------------- */

async function saveExecution(item: PlanCaseRow): Promise<void> {
  const draft = drafts[item.caseId]
  if (!draft) return
  if (draft.result === 'BLOCKED' || draft.result === 'SKIP') {
    if (!draft.note.trim()) {
      ElMessage.warning('阻塞 / 跳过必须填写原因（门禁宽松模式也会校验）')
      return
    }
  }
  savingCase.value = item.caseId
  try {
    const { executionId } = await executeCase(planId.value, {
      caseId: item.caseId,
      result: draft.result,
      note: draft.note.trim() || undefined,
    })
    executionIds[item.caseId] = executionId
    item.result = draft.result
    item.note = draft.note
    item.executedAt = new Date().toISOString()
    await refreshSummary()
    ElMessage.success(`已登记 ${item.caseCode}：${dict.label('execution', draft.result)}`)
  } catch (err) {
    const apiError = err as { status?: number; message?: string }
    ElMessage.error(
      apiError.status === 409
        ? apiError.message || '计划已锁定或执行被拒绝（409）'
        : apiError.message || '执行登记失败',
    )
  } finally {
    savingCase.value = null
  }
}

function onResultChange(item: PlanCaseRow): void {
  const draft = drafts[item.caseId]
  if (!draft) return
  if (draft.result === 'NOT_RUN') {
    draft.result = item.result
    ElMessage.info('「未执行」不是执行结果，请选择 通过 / 失败 / 阻塞 / 跳过')
    return
  }
  void saveExecution(item)
}

async function convertToDefect(item: PlanCaseRow): Promise<void> {
  const executionId = executionIds[item.caseId]
  if (!executionId) {
    ElMessage.warning('请先登记失败结果，再转缺陷（转缺陷需要本次执行记录的 ID）')
    return
  }
  try {
    const defect = await defectFromExecution(executionId)
    ElMessage.success(`已创建缺陷 ${defect.code}`)
    const target = router.resolve({ name: 'defect-detail', params: { id: defect.id } })
    if (!target.matched.some((record) => record.name === 'catch-all')) {
      void router.push({ name: 'defect-detail', params: { id: defect.id } })
    }
  } catch (err) {
    ElMessage.error((err as Error).message || '转缺陷失败')
  }
}

const removeTarget = ref<PlanCaseRow | null>(null)
const removing = ref(false)

async function confirmRemove(): Promise<void> {
  const target = removeTarget.value
  if (!target) return
  removing.value = true
  try {
    await removeCaseFromPlan(planId.value, target.caseId)
    removeTarget.value = null
    await load()
    ElMessage.success('已从计划移除（用例库不受影响）')
  } catch (err) {
    ElMessage.error((err as Error).message || '移除失败')
  } finally {
    removing.value = false
  }
}

/* ---------------- 纳入用例 ---------------- */

const addVisible = ref(false)
const addLoading = ref(false)
const addKeyword = ref('')
const candidates = ref<TestCaseRow[]>([])
const selectedCaseIds = ref<number[]>([])

async function searchCases(): Promise<void> {
  addLoading.value = true
  try {
    const data = await listTestCases({ keyword: addKeyword.value || undefined, page: 1, size: 50 })
    const existing = new Set(cases.value.map((item) => item.caseId))
    candidates.value = (data.items ?? []).filter((item) => !existing.has(item.id))
  } catch {
    candidates.value = []
  } finally {
    addLoading.value = false
  }
}

function openAdd(): void {
  selectedCaseIds.value = []
  addKeyword.value = ''
  addVisible.value = true
  void searchCases()
}

async function submitAdd(): Promise<void> {
  if (!selectedCaseIds.value.length) {
    ElMessage.warning('请选择要纳入的用例')
    return
  }
  try {
    await addCasesToPlan(planId.value, selectedCaseIds.value)
    addVisible.value = false
    ElMessage.success(`已纳入 ${selectedCaseIds.value.length} 条用例（钉住当前版本）`)
    await load()
  } catch (err) {
    ElMessage.error((err as Error).message || '纳入失败')
  }
}

/* ---------------- 门禁评估 ---------------- */

const requirementId = ref<number | undefined>(
  route.query.requirementId ? Number(route.query.requirementId) : undefined,
)
const gateDecision = ref<GateDecision | null>(null)
const gateLoading = ref(false)
const gateError = ref('')

async function checkGate(): Promise<void> {
  if (!requirementId.value) {
    ElMessage.warning('门禁按需求评估，请填写需求 ID')
    return
  }
  gateLoading.value = true
  gateError.value = ''
  try {
    gateDecision.value = await evaluateTestGate(requirementId.value)
  } catch (err) {
    gateDecision.value = null
    gateError.value = (err as Error).message || '门禁评估失败'
  } finally {
    gateLoading.value = false
  }
}

function backToList(): void {
  void router.push({ name: 'test-plans' })
}

onMounted(async () => {
  await load()
  if (requirementId.value) await checkGate()
})
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <button type="button" class="dl-head__back" @click="backToList">← 测试计划</button>
        <h1 class="t-page-title">{{ plan?.name || `计划 #${planId}` }}</h1>
        <span v-if="plan" class="t-code">{{ plan.code }}</span>
        <StatusPill v-if="plan?.status" kind="execution" :value="plan.status" size="sm" />
      </div>
      <div class="dl-head__actions">
        <el-button :disabled="!canManage" @click="openAdd">纳入用例</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <div class="dl-meta plan-meta">
      <span>版本：{{ plan?.releaseId ? `#${plan.releaseId}` : '—' }}</span>
      <span>负责人：{{ plan?.ownerName || '—' }}</span>
      <span>窗口：{{ plan?.windowStart || '—' }} ~ {{ plan?.windowEnd || '—' }}</span>
    </div>

    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />
    <EmptyState
      v-else-if="!loading && !detail"
      title="计划不存在或无权访问"
      description="确认计划属于你有权限的业务域"
      icon="lock"
      action-text="返回计划列表"
      @action="backToList"
    />
    <template v-else>
      <section class="surface surface--pad plan-top">
        <PassRateRing
          :passed="summary?.passed ?? 0"
          :planned="summary?.planned ?? 0"
          :coverage="summary?.coverage ?? 0"
        />
        <div class="plan-stats">
          <div class="dl-stat">
            <span class="dl-stat__label">已执行</span>
            <span class="dl-stat__value">{{ summary?.executed ?? 0 }} / {{ summary?.planned ?? 0 }}</span>
          </div>
          <div class="dl-stat">
            <span class="dl-stat__label">失败</span>
            <span class="dl-stat__value ink-danger">{{ summary?.failed ?? 0 }}</span>
          </div>
          <div class="dl-stat">
            <span class="dl-stat__label">阻塞 / 跳过</span>
            <span class="dl-stat__value ink-warning">{{ (summary?.blocked ?? 0) + (summary?.skip ?? 0) }}</span>
          </div>
          <div class="dl-stat">
            <span class="dl-stat__label">未执行</span>
            <span class="dl-stat__value">{{ summary?.notRun ?? 0 }}</span>
          </div>
          <div class="dl-stat">
            <span class="dl-stat__label">覆盖率</span>
            <span class="dl-stat__value">{{ Math.round(Number(summary?.coverage ?? 0) * 100) }}%</span>
          </div>
        </div>
        <div class="plan-gate">
          <div class="dl-mode" :class="gateMode === 'STRICT' ? 'is-strict' : 'is-loose'">
            门禁 · {{ gateMode === 'STRICT' ? '严格' : '宽松' }}
          </div>
          <p class="dl-hint">{{ gateRule }}</p>
          <div class="gate-check">
            <el-input-number
              v-model="requirementId"
              :min="1"
              :controls="false"
              placeholder="需求 ID"
              style="width: 110px"
            />
            <el-button :loading="gateLoading" @click="checkGate">评估该需求门禁</el-button>
          </div>
          <p v-if="gateError" class="ink-danger gate-error">{{ gateError }}</p>
        </div>
      </section>

      <section v-if="gateDecision" class="surface surface--pad">
        <GateChecklist
          :items="gateDecision.items ?? []"
          mode="gate"
          :rejected="gateDecision.passed === false"
          :rejected-text="gateDecision.reason || '门禁未通过：计划内仍有未通过用例'"
        />
        <div v-if="gateDecision.blockers?.length" class="blockers">
          <div class="dl-toolbar__title">阻断项（服务端判定）</div>
          <ul>
            <li v-for="(blocker, index) in gateDecision.blockers" :key="index">
              <span class="t-code">{{ blocker.caseCode || `#${blocker.caseId}` }}</span>
              <span>{{ blocker.name || blocker.note || blocker.detail || '未通过' }}</span>
              <StatusPill v-if="blocker.result" kind="execution" :value="blocker.result" size="sm" />
            </li>
          </ul>
        </div>
      </section>

      <section class="surface plan-table">
        <div class="dl-toolbar">
          <div class="dl-chips">
            <button type="button" class="dl-chip" :class="{ 'is-active': filter === 'all' }" @click="filter = 'all'">
              全部 {{ cases.length }}
            </button>
            <button type="button" class="dl-chip" :class="{ 'is-active': filter === 'pending' }" @click="filter = 'pending'">
              未执行 {{ pendingCount }}
            </button>
            <button type="button" class="dl-chip" :class="{ 'is-active': filter === 'failed', '--warn': failedCount > 0 }" @click="filter = 'failed'">
              失败 {{ failedCount }}
            </button>
          </div>
          <div class="dl-filters__spacer" />
          <span class="dl-hint">执行只写记录，不改用例主表；用例版本钉在纳入计划的那一刻</span>
        </div>

        <EmptyState
          v-if="!loading && !cases.length"
          title="计划里还没有用例"
          description="从用例库纳入用例后即可开始执行；纳入时会钉住当前版本"
          icon="file"
          compact
          :action-text="canManage ? '纳入用例' : undefined"
          @action="openAdd"
        />

        <table v-else class="exec-table">
          <thead>
            <tr>
              <th style="width: 120px">用例</th>
              <th>标题</th>
              <th style="width: 70px">版本</th>
              <th style="width: 140px">结果</th>
              <th style="width: 220px">备注</th>
              <th style="width: 150px">执行时间</th>
              <th style="width: 170px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in visibleCases" :key="item.caseId" :class="{ 'is-failed': item.result === 'FAIL' }">
              <td><span class="t-code">{{ item.caseCode }}</span></td>
              <td class="exec-title">
                {{ item.title || '—' }}
                <span v-if="item.result === 'FAIL'" class="fail-flag">失败</span>
              </td>
              <td><span class="t-code">v{{ item.caseVersion }}</span></td>
              <td>
                <el-select
                  v-model="drafts[item.caseId].result"
                  size="small"
                  :disabled="!canManage"
                  style="width: 110px"
                  @change="onResultChange(item)"
                >
                  <el-option v-for="result in ['NOT_RUN', 'PASS', 'FAIL', 'BLOCKED', 'SKIP']" :key="result" :label="dict.label('execution', result)" :value="result" />
                </el-select>
              </td>
              <td>
                <el-input
                  v-model="drafts[item.caseId].note"
                  size="small"
                  maxlength="200"
                  :disabled="!canManage"
                  placeholder="阻塞 / 跳过必填原因"
                />
              </td>
              <td class="t-aux">{{ item.executedAt ? formatDateTime(item.executedAt) : '—' }}</td>
              <td>
                <div class="row-actions exec-actions">
                  <el-button
                    link
                    type="primary"
                    :loading="savingCase === item.caseId"
                    :disabled="!canManage"
                    @click="saveExecution(item)"
                  >
                    保存
                  </el-button>
                  <el-button
                    v-if="item.result === 'FAIL'"
                    link
                    type="danger"
                    :disabled="!canManage"
                    @click="convertToDefect(item)"
                  >
                    转缺陷
                  </el-button>
                  <el-button link :disabled="!canManage" @click="removeTarget = item">移除</el-button>
                </div>
              </td>
            </tr>
            <tr v-if="!visibleCases.length">
              <td colspan="7" class="dl-hint" style="padding: 16px">当前筛选下没有用例</td>
            </tr>
          </tbody>
        </table>

        <div class="dl-sticky">
          <div class="sticky-stats">
            <span>通过率 <strong>{{ Math.round(Number(summary?.passRate ?? 0) * 100) }}%</strong></span>
            <span>覆盖率 <strong>{{ Math.round(Number(summary?.coverage ?? 0) * 100) }}%</strong></span>
            <span class="t-aux">未执行 {{ summary?.notRun ?? 0 }}</span>
          </div>
          <el-button type="primary" :loading="loading" @click="load">刷新汇总</el-button>
        </div>
      </section>
    </template>

    <el-dialog v-model="addVisible" title="纳入用例" width="720px" :close-on-click-modal="false">
      <div class="dl-filters">
        <el-input
          v-model="addKeyword"
          placeholder="搜索编号 / 标题"
          clearable
          style="width: 240px"
          @keyup.enter="searchCases"
        />
        <el-button :loading="addLoading" @click="searchCases">搜索</el-button>
      </div>
      <el-table
        v-loading="addLoading"
        :data="candidates"
        row-key="id"
        max-height="360"
        @selection-change="(rows: TestCaseRow[]) => (selectedCaseIds = rows.map((row) => row.id))"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column prop="code" label="编号" width="110">
          <template #default="{ row }"><span class="t-code">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column label="当前版本" width="90">
          <template #default="{ row }"><span class="t-code">v{{ row.currentVersion ?? 1 }}</span></template>
        </el-table-column>
      </el-table>
      <p class="dl-hint">纳入时会钉住用例当前版本；之后用例升级不影响本计划的执行口径。</p>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedCaseIds.length" @click="submitAdd">
          纳入 {{ selectedCaseIds.length }} 条
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      :model-value="Boolean(removeTarget)"
      title="从计划移除用例"
      width="440px"
      @update:model-value="(value: boolean) => !value && (removeTarget = null)"
    >
      <p class="dl-hint">
        将从本计划移除 {{ removeTarget?.caseCode }}（v{{ removeTarget?.caseVersion }}）。用例库与历史执行记录不受影响。
      </p>
      <template #footer>
        <el-button @click="removeTarget = null">取消</el-button>
        <el-button type="danger" :loading="removing" @click="confirmRemove">确认移除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.plan-meta {
  display: flex;
  gap: 18px;
  margin: -4px 0 12px;
  flex-wrap: wrap;
}

.plan-top {
  display: grid;
  grid-template-columns: 140px 1fr 300px;
  gap: 24px;
  align-items: center;
}

.plan-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(90px, 1fr));
  gap: 12px 12px;
}

.plan-gate {
  border-left: 1px solid var(--divider);
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.dl-mode {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: var(--r-pill);
  font-size: 12px;
  width: fit-content;
}

.dl-mode.is-strict {
  background: var(--danger-bg);
  color: var(--danger-ink);
}

.dl-mode.is-loose {
  background: var(--warning-bg);
  color: var(--warning-ink);
}

.gate-check {
  display: flex;
  gap: 8px;
  align-items: center;
}

.gate-error {
  margin: 0;
  font-size: 12px;
}

.blockers {
  margin-top: 12px;
  border-top: 1px solid var(--divider);
  padding-top: 10px;
}

.blockers ul {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--text-body);
}

.blockers li {
  display: flex;
  gap: 8px;
  align-items: center;
}

.plan-table {
  margin-top: 12px;
}

.exec-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.exec-table th {
  height: 40px;
  padding: 0 12px;
  background: var(--bg-table-head);
  color: var(--text-muted-aa);
  font-size: 12px;
  font-weight: 500;
  text-align: left;
  border-bottom: 1px solid var(--border);
}

.exec-table td {
  height: 44px;
  padding: 6px 12px;
  border-bottom: 1px solid var(--divider);
  vertical-align: middle;
}

.exec-table tr.is-failed td {
  background: #fffafa;
}

.exec-title {
  color: var(--text-title);
}

.fail-flag {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: var(--r-pill);
  background: var(--danger-bg);
  color: var(--danger-ink);
  font-size: 11px;
}

.exec-actions {
  opacity: 1;
}

.dl-sticky {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  background: var(--bg-card);
  border-top: 1px solid var(--border);
}

.sticky-stats {
  display: flex;
  gap: 18px;
  font-size: 12px;
  color: var(--text-body);
}

.sticky-stats strong {
  color: var(--text-title);
  font-size: 14px;
}
</style>
