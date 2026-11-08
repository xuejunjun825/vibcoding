<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { evaluateTestGate, listTestPlans, planSummary } from '@/api/deliver'
import { quickTest } from '@/api/req'
import type { GateDecision, GateItem, PlanSummary } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import GateChecklist from '@/components/GateChecklist.vue'
import StatusPill from '@/components/StatusPill.vue'
import { usePermission } from '@/composables/usePermission'
import { formatDateTime, formatPercent } from '@/utils/format'
import { messageOf } from '@/views/req/platform'

/**
 * P2 · 测试 tab（AC-16）：
 * ① 快速测试（source=MANUAL，无计划时的合法路径，技术方案 D2）
 * ② 版本级测试计划（通过/计划、覆盖率）
 * ③ 门禁条 —— 只渲染 deliver-service TestGateService 的结论，不在前端重算。
 */
const props = defineProps<{ requirementId: number; releaseId?: number | null }>()

const router = useRouter()
const { can } = usePermission()

const gate = ref<GateDecision | null>(null)
const gateLoading = ref(false)
const gateError = ref('')
const quickResult = ref<'PASS' | 'FAIL'>('PASS')
const quickNote = ref('')
const quickSaving = ref(false)
const quickHistory = ref<Array<{ result: string; note: string; at: string }>>([])

interface PlanRow {
  id: number
  code: string
  name: string
  status?: string
  releaseName?: string
  summary?: PlanSummary | null
}

const plans = ref<PlanRow[]>([])
const plansLoading = ref(false)
const plansError = ref('')

const gateItems = computed<GateItem[]>(() => {
  if (!gate.value) return []
  if (gate.value.items && gate.value.items.length) return gate.value.items
  const items: GateItem[] = [
    {
      code: 'GATE',
      name: gate.value.mode === 'STRICT' ? '严格模式测试门禁' : '宽松模式测试门禁',
      passed: gate.value.passed,
      detail: gate.value.reason ?? (gate.value.passed ? '门禁通过' : '存在未通过用例'),
      fixHint: gate.value.passed ? '' : '打开关联测试计划，补齐未通过的用例',
    },
  ]
  for (const blocker of gate.value.blockers ?? []) {
    const name = [blocker.caseCode, blocker.name].filter(Boolean).join(' ') || '未通过用例'
    items.push({
      code: blocker.caseCode ?? `CASE-${blocker.caseId ?? '?'}`,
      name,
      passed: false,
      detail: [blocker.result, blocker.note, blocker.detail].filter(Boolean).join(' · '),
      fixHint: '去执行用例并登记结果',
    })
  }
  return items
})

async function loadGate(): Promise<void> {
  gateLoading.value = true
  gateError.value = ''
  try {
    gate.value = await evaluateTestGate(props.requirementId)
  } catch (error) {
    gate.value = null
    gateError.value = messageOf(error)
  } finally {
    gateLoading.value = false
  }
}

async function loadPlans(): Promise<void> {
  if (!props.releaseId) return
  plansLoading.value = true
  plansError.value = ''
  try {
    const page = await listTestPlans({ releaseId: props.releaseId, page: 1, size: 5 })
    const rows: PlanRow[] = page.items.map((item) => ({ ...item }))
    plans.value = rows
    await Promise.all(
      rows.map(async (plan) => {
        plan.summary = await planSummary(plan.id).catch(() => null)
      }),
    )
  } catch (error) {
    plans.value = []
    plansError.value = messageOf(error)
  } finally {
    plansLoading.value = false
  }
}

async function submitQuick(): Promise<void> {
  quickSaving.value = true
  try {
    await quickTest(props.requirementId, { result: quickResult.value, note: quickNote.value.trim() || undefined })
    quickHistory.value.unshift({
      result: quickResult.value,
      note: quickNote.value.trim(),
      at: new Date().toISOString(),
    })
    quickNote.value = ''
    ElMessage.success('快速测试已登记（source=MANUAL）')
    await loadGate()
  } catch (error) {
    ElMessage.error(`登记失败：${messageOf(error)}`)
  } finally {
    quickSaving.value = false
  }
}

function openPlan(planId: number): void {
  if (!router.hasRoute('test-plan-execute')) {
    ElMessage.info('计划执行页（T9）尚未交付')
    return
  }
  void router.push({ name: 'test-plan-execute', params: { id: planId } })
}

onMounted(() => {
  void loadGate()
  void loadPlans()
})
</script>

<template>
  <div class="testing">
    <section class="surface surface--pad">
      <header class="testing__head">
        <h3 class="testing__title">快速测试</h3>
        <span class="testing__hint">无测试计划时的合法路径，结果写入执行记录（source=MANUAL）</span>
      </header>
      <div class="quick">
        <el-radio-group v-model="quickResult" size="small">
          <el-radio-button value="PASS">通过</el-radio-button>
          <el-radio-button value="FAIL">不通过</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="quickNote"
          placeholder="备注（不通过时建议写清原因，门禁清单会引用）"
          maxlength="200"
          style="flex: 1; min-width: 220px"
        />
        <el-button
          type="primary"
          size="small"
          :disabled="!can('test:quick')"
          :title="can('test:quick') ? '' : '需要权限：test:quick'"
          :loading="quickSaving"
          @click="submitQuick"
        >
          登记结果
        </el-button>
      </div>
      <p v-if="!can('test:quick')" class="testing__hint">当前账号只有查看权限，不能登记执行结果。</p>
      <ul v-if="quickHistory.length" class="quick__history">
        <li v-for="(record, index) in quickHistory" :key="index">
          <StatusPill kind="execution" :value="record.result" size="sm" />
          <span class="quick__note">{{ record.note || '无备注' }}</span>
          <span class="quick__time">{{ formatDateTime(record.at) }}</span>
        </li>
      </ul>
    </section>

    <section class="surface surface--pad">
      <header class="testing__head">
        <h3 class="testing__title">关联测试计划</h3>
        <span class="testing__hint">按需求所属版本关联</span>
      </header>
      <div v-if="!releaseId" class="testing__hint">需求尚未排入版本，没有版本级测试计划。</div>
      <div v-else-if="plansLoading" class="skeleton skeleton__row" />
      <ErrorState
        v-else-if="plansError"
        compact
        title="测试计划暂不可用"
        :message="plansError"
        @retry="loadPlans"
      />
      <EmptyState
        v-else-if="plans.length === 0"
        title="本版本还没有测试计划"
        description="测试负责人可在「测试计划」中纳入用例并执行；没有计划时可用上方快速测试放行。"
        icon="file"
        compact
      />
      <el-table v-else :data="plans" row-key="id">
        <el-table-column prop="name" label="计划" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><StatusPill kind="release" :value="row.status" size="sm" /></template>
        </el-table-column>
        <el-table-column label="通过 / 计划" width="120" align="right">
          <template #default="{ row }">
            {{ row.summary ? `${row.summary.passed} / ${row.summary.planned}` : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="覆盖率" width="96" align="right">
          <template #default="{ row }">{{ row.summary ? formatPercent(row.summary.coverage) : '—' }}</template>
        </el-table-column>
        <el-table-column label="通过率" width="96" align="right">
          <template #default="{ row }">{{ row.summary ? formatPercent(row.summary.passRate) : '—' }}</template>
        </el-table-column>
        <el-table-column label="" width="90" align="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openPlan(row.id)">去执行 →</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="surface surface--pad">
      <header class="testing__head">
        <h3 class="testing__title">测试门禁</h3>
        <span class="testing__hint">结论来自 deliver-service，前端只渲染不重算</span>
      </header>
      <div v-if="gateLoading" class="skeleton skeleton__row" />
      <ErrorState
        v-else-if="gateError"
        compact
        title="门禁结论暂不可用"
        :message="gateError"
        @retry="loadGate"
      />
      <EmptyState
        v-else-if="!gate"
        title="还没有门禁结论"
        description="在关联测试计划执行后，门禁会按严格/宽松模式给出结论。"
        icon="chart"
        compact
      />
      <GateChecklist v-else :items="gateItems" mode="gate" />
    </section>
  </div>
</template>

<style scoped>
.testing {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.testing__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.testing__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.testing__hint {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.quick {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.quick__history {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.quick__history li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-body);
}
.quick__note {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.quick__time {
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}
</style>
