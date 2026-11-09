<script setup lang="ts">
/**
 * 报告阅读器（DESIGN §1 / Q7）：8 段固定顺序、不可折叠。
 *
 * 数据全部来自服务端不可变快照（`snapshot_json`）：本组件不按当前库数据重算任何数，
 * 不做二次除法；旧版本导出与页面看到的是同一份 sections。
 *
 * 数字可点开证据：cases / defects 明细由报告版本端点分页返回（固定 50/页），
 * 抽屉只读。`quality.ts` 尚未封装这两个端点，故此处直连（见 [OUT-OF-SCOPE]）。
 */
import { computed, reactive, watch } from 'vue'
import { reportApi } from '@/api/quality'
import type {
  ReportCaseDetailRow as CaseDetailRow,
  ReportDefectDetailRow as DefectDetailRow,
} from '@/api/quality'
import { ElMessage } from 'element-plus'
import EvidenceRef from '@/components/quality/EvidenceRef.vue'
import StatusPill from '@/components/StatusPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import { formatPercent } from '@/utils/format'
import type { ReportSnapshot } from '@/api/types'

type Dict = Record<string, any>

const props = withDefaults(
  defineProps<{
    snapshot: ReportSnapshot | null
    reportId?: number | string | null
    versionNo?: number | string | null
    uncoveredRequirements?: Array<{ requirementId?: number; code?: string; title?: string }>
    loading?: boolean
  }>(),
  { reportId: null, versionNo: null, uncoveredRequirements: () => [], loading: false },
)

const sections = computed<Dict>(() => (props.snapshot?.sections as Dict | undefined) ?? {})
const scope = computed<Dict>(() => record(sections.value.scope))
const execution = computed<Dict>(() => record(sections.value.execution))
const rates = computed<Dict>(() => record(sections.value.rates))
const defects = computed<Dict>(() => record(sections.value.defects))
const residual = computed<Dict>(() => record(sections.value.residual))
const env = computed<Dict>(() => record(sections.value.env))
const risk = computed<Dict>(() => record(sections.value.riskConclusion))
const signoff = computed<Dict>(() => record(sections.value.signoff))

const plans = computed<Dict[]>(() => list(scope.value.plans))
const excludedPlans = computed<Dict[]>(() => list(scope.value.excludedPlans))
const totals = computed<Dict>(() => record(execution.value.totals))
const byModule = computed<Dict[]>(() => list(execution.value.byModule))
const byExecutor = computed<Dict[]>(() => list(execution.value.byExecutor))
const bySeverity = computed<Dict[]>(() => list(defects.value.bySeverity))
const byStatus = computed<Dict[]>(() => list(defects.value.byStatus))
const openDefectRows = computed<Dict[]>(() => list(residual.value.openDefects))
const waivers = computed<Dict[]>(() => list(residual.value.waivers))
const envs = computed<Dict[]>(() => list(env.value.envs))
const risks = computed<Dict[]>(() => list(risk.value.risks))
const requiredCases = computed<Dict>(() => record(risk.value.requiredCases))
const signoffLevels = computed<Dict[]>(() => list(signoff.value.levels))
const caseIdCount = computed(() => list(execution.value.caseIds).length)
const defectIdCount = computed(() => list(defects.value.defectIds).length)
const openDefectCount = computed(() => Number(residual.value.openDefectCount ?? openDefectRows.value.length))
const coverage = computed(() => props.snapshot?.coverage ?? nullableNumber(rates.value.coverage))
const passRate = computed(() => props.snapshot?.passRate ?? nullableNumber(rates.value.passRate))
const conclusion = computed(() => String(props.snapshot?.conclusion ?? risk.value.conclusion ?? ''))

const sectionsDef = [
  { key: 'scope', title: '报告范围' },
  { key: 'execution', title: '执行结果' },
  { key: 'rates', title: '通过率与覆盖率' },
  { key: 'defects', title: '缺陷统计' },
  { key: 'residual', title: '遗留风险' },
  { key: 'env', title: '测试环境' },
  { key: 'risk', title: '风险与结论' },
  { key: 'signoff', title: '会签记录' },
]

/* ---------------- 抽屉：证据明细 ---------------- */

const drill = reactive({
  open: false,
  kind: 'cases' as 'cases' | 'defects',
  title: '',
  result: '',
  severity: '',
  page: 1,
  size: 50,
  total: 0,
  rows: [] as Array<CaseDetailRow | DefectDetailRow>,
  loading: false,
  error: '',
})

const drillTitle = computed(() => {
  const label = drill.kind === 'cases' ? '用例执行明细' : '缺陷明细'
  return `${label} · v${props.versionNo ?? '—'}`
})
const caseRows = computed(() => drill.rows as CaseDetailRow[])
const defectRows = computed(() => drill.rows as DefectDetailRow[])

function record(value: unknown): Dict {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Dict) : {}
}

function list(value: unknown): Dict[] {
  return Array.isArray(value) ? value.filter((item): item is Dict => !!item && typeof item === 'object') : []
}

function nullableNumber(value: unknown): number | null {
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}

function percent(value: unknown): string {
  return formatPercent(nullableNumber(value), 1)
}

function severityLabel(value: unknown): string {
  const text = String(value ?? '').toUpperCase()
  return text || '—'
}

function openCases(result = ''): void {
  if (!props.reportId || !props.versionNo) {
    ElMessage.info('该版本未提供明细入口')
    return
  }
  drill.kind = 'cases'
  drill.result = result
  drill.severity = ''
  drill.page = 1
  drill.open = true
  void loadDrill()
}

function openDefects(filter: { severity?: string } = {}): void {
  if (!props.reportId || !props.versionNo) {
    ElMessage.info('该版本未提供明细入口')
    return
  }
  drill.kind = 'defects'
  drill.result = ''
  drill.severity = filter.severity ?? ''
  drill.page = 1
  drill.open = true
  void loadDrill()
}

async function loadDrill(): Promise<void> {
  if (!props.reportId || !props.versionNo) return
  drill.loading = true
  drill.error = ''
  try {
    const page =
      drill.kind === 'cases'
        ? await reportApi.cases(props.reportId, props.versionNo, {
            page: drill.page,
            size: drill.size,
            ...(drill.result ? { result: drill.result } : {}),
          })
        : await reportApi.defects(props.reportId, props.versionNo, {
            page: drill.page,
            size: drill.size,
            ...(drill.severity ? { severity: drill.severity } : {}),
          })
    drill.rows = page.items ?? []
    drill.total = page.total ?? 0
  } catch (err) {
    drill.rows = []
    drill.error = err instanceof Error ? err.message : '明细加载失败'
  } finally {
    drill.loading = false
  }
}

function onDrillPage(page: number): void {
  drill.page = page
  void loadDrill()
}

watch(
  () => [props.reportId, props.versionNo],
  () => {
    drill.open = false
    drill.rows = []
  },
)

function scrollTo(key: string): void {
  document.getElementById(`report-section-${key}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<template>
  <div v-if="loading" class="reader-skeleton" aria-busy="true">
    <div v-for="index in 8" :key="index" class="skeleton skeleton__row" />
  </div>

  <div v-else-if="!snapshot" class="reader-empty">
    <EmptyState
      variant="empty"
      icon="file"
      title="没有可显示的快照"
      description="该版本可能尚未生成完成，或快照已被清理；请重新生成报告版本。"
    />
  </div>

  <div v-else class="reader">
    <article class="reader__paper" aria-label="测试报告正文">
      <header class="paper__head">
        <div class="paper__crumb">
          <span class="t-code">{{ snapshot.resultDigest ? '快照已固化' : '快照' }}</span>
          <span class="paper__dot">·</span>
          <span>数据截止 {{ scope.dataCutoff || '—' }}</span>
        </div>
        <h2 class="paper__title">测试报告</h2>
        <p class="paper__subtitle">
          {{ scope.type === 'PLAN' ? '计划范围' : '版本范围' }}
          <template v-if="scope.releaseName"> · {{ scope.releaseName }}</template>
          <template v-if="scope.planCount"> · {{ scope.planCount }} 个计划</template>
        </p>
      </header>

      <!-- 1 报告范围 -->
      <section id="report-section-scope" class="paper-section">
        <h3 class="paper-section__title">
          <span class="paper-section__no t-code">01</span>报告范围
        </h3>
        <dl class="paper-facts">
          <div><dt>范围</dt><dd>{{ scope.type === 'PLAN' ? '测试计划' : '版本' }}</dd></div>
          <div><dt>版本</dt><dd>{{ scope.releaseCode || '—' }} {{ scope.releaseName || '' }}</dd></div>
          <div><dt>数据截止</dt><dd>{{ scope.dataCutoff || '—' }}</dd></div>
          <div><dt>纳入计划</dt><dd>{{ scope.planCount ?? plans.length }}</dd></div>
        </dl>
        <table v-if="plans.length" class="paper-table">
          <thead>
            <tr>
              <th>计划</th><th>状态</th><th class="is-num">计划用例</th>
              <th class="is-num">已执行</th><th class="is-num">通过</th><th class="is-num">通过率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(plan, index) in plans" :key="plan.planId ?? index">
              <td>{{ plan.name || plan.planCode || `#${plan.planId}` }}</td>
              <td>{{ plan.status || '—' }}</td>
              <td class="is-num">{{ plan.total ?? 0 }}</td>
              <td class="is-num">{{ plan.executed ?? 0 }}</td>
              <td class="is-num">{{ plan.passed ?? 0 }}</td>
              <td class="is-num">{{ percent(plan.passRate) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="excludedPlans.length" class="paper-note">
          未纳入 {{ excludedPlans.length }} 个计划（未批准 / 归档）：
          {{ excludedPlans.map((plan) => plan.name || `#${plan.planId}`).join('、') }}
        </p>
        <p v-if="uncoveredRequirements.length" class="paper-note paper-note--warn">
          {{ uncoveredRequirements.length }} 条需求未覆盖测试计划：
          {{ uncoveredRequirements.map((item) => item.code || `#${item.requirementId}`).join('、') }}
        </p>
      </section>

      <!-- 2 执行结果 -->
      <section id="report-section-execution" class="paper-section">
        <h3 class="paper-section__title">
          <span class="paper-section__no t-code">02</span>执行结果
        </h3>
        <div class="paper-stats">
          <div class="paper-stat"><span>计划用例</span><strong>{{ totals.planned ?? 0 }}</strong></div>
          <div class="paper-stat">
            <span>已执行</span>
            <strong>
              <EvidenceRef
                :value="totals.executed ?? 0"
                :count="caseIdCount || null"
                title="查看用例执行明细"
                @open="openCases()"
              />
            </strong>
          </div>
          <div class="paper-stat">
            <span>通过</span>
            <strong class="ink-success">
              <EvidenceRef
                :value="totals.passed ?? 0"
                title="查看通过的用例"
                @open="openCases('PASS')"
              />
            </strong>
          </div>
          <div class="paper-stat">
            <span>失败</span>
            <strong :class="{ 'ink-danger': Number(totals.failed ?? 0) > 0 }">
              <EvidenceRef
                :value="totals.failed ?? 0"
                title="查看失败的用例"
                @open="openCases('FAIL')"
              />
            </strong>
          </div>
          <div class="paper-stat"><span>阻塞</span><strong>{{ totals.blocked ?? 0 }}</strong></div>
          <div class="paper-stat"><span>跳过</span><strong>{{ totals.skipped ?? 0 }}</strong></div>
          <div class="paper-stat"><span>未执行</span><strong>{{ totals.notRun ?? 0 }}</strong></div>
        </div>
        <p v-if="execution.truncated" class="paper-note">
          明细 id 列表超过上限，已截断；完整明细请点开数字查看分页列表。
        </p>
        <table v-if="byModule.length" class="paper-table">
          <thead>
            <tr>
              <th>模块</th><th class="is-num">计划</th><th class="is-num">通过</th>
              <th class="is-num">失败</th><th class="is-num">阻塞</th><th class="is-num">未执行</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in byModule" :key="row.moduleId ?? index">
              <td>{{ row.moduleName || '未分模块' }}</td>
              <td class="is-num">{{ row.planned ?? 0 }}</td>
              <td class="is-num">{{ row.PASS ?? 0 }}</td>
              <td class="is-num">{{ row.FAIL ?? 0 }}</td>
              <td class="is-num">{{ row.BLOCKED ?? 0 }}</td>
              <td class="is-num">{{ row.NOT_RUN ?? 0 }}</td>
            </tr>
          </tbody>
        </table>
        <details v-if="byExecutor.length" class="paper-details">
          <summary>按执行人（{{ byExecutor.length }}）</summary>
          <table class="paper-table">
            <thead>
              <tr><th>执行人</th><th class="is-num">已执行</th><th class="is-num">通过</th><th class="is-num">失败</th></tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in byExecutor" :key="row.executorId ?? index">
                <td>{{ row.executorName || '—' }}</td>
                <td class="is-num">{{ row.executed ?? 0 }}</td>
                <td class="is-num">{{ row.PASS ?? 0 }}</td>
                <td class="is-num">{{ row.FAIL ?? 0 }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </section>

      <!-- 3 通过率 -->
      <section id="report-section-rates" class="paper-section">
        <h3 class="paper-section__title">
          <span class="paper-section__no t-code">03</span>通过率与覆盖率
        </h3>
        <div class="paper-rates">
          <div class="paper-rate">
            <span class="paper-rate__label">通过率</span>
            <strong class="paper-rate__value">{{ percent(passRate) }}</strong>
            <span class="paper-rate__formula">{{ rates.passRateFormula || 'passed / planned' }}</span>
          </div>
          <div class="paper-rate">
            <span class="paper-rate__label">覆盖率</span>
            <strong class="paper-rate__value">{{ percent(coverage) }}</strong>
            <span class="paper-rate__formula">{{ rates.coverageFormula || 'executed / planned' }}</span>
          </div>
          <div class="paper-rate">
            <span class="paper-rate__label">已执行通过率</span>
            <strong class="paper-rate__value">{{ percent(rates.executedPassRate) }}</strong>
            <span class="paper-rate__formula">passed / executed</span>
          </div>
        </div>
        <p class="paper-note">
          分母口径由服务端 QualityAggregator 统一：通过率 = 通过 / 计划用例总数（非“已执行”），
          因此 0 计划用例时显示 “—”，不是 0%。
        </p>
      </section>

      <!-- 4 缺陷统计 -->
      <section id="report-section-defects" class="paper-section">
        <h3 class="paper-section__title">
          <span class="paper-section__no t-code">04</span>缺陷统计
        </h3>
        <div class="paper-stats">
          <div class="paper-stat"><span>缺陷总数</span><strong>{{ defects.total ?? 0 }}</strong></div>
          <div class="paper-stat"><span>未关闭</span><strong>{{ defects.open ?? 0 }}</strong></div>
          <div class="paper-stat">
            <span>未关闭 P0/P1</span>
            <strong :class="{ 'ink-danger': Number(defects.openP0P1 ?? 0) > 0 }">
              {{ defects.openP0P1 ?? 0 }}
            </strong>
          </div>
          <div class="paper-stat"><span>P0</span><strong>{{ defects.p0 ?? 0 }}</strong></div>
          <div class="paper-stat"><span>P1</span><strong>{{ defects.p1 ?? 0 }}</strong></div>
          <div class="paper-stat"><span>逃逸</span><strong>{{ defects.escaped ?? 0 }}</strong></div>
          <div class="paper-stat"><span>逃逸率</span><strong>{{ percent(defects.escapeRate) }}</strong></div>
        </div>
        <div v-if="bySeverity.length" class="paper-chips">
          <span v-for="row in bySeverity" :key="row.k" class="paper-chip">
            {{ severityLabel(row.k) }} <strong>{{ row.n ?? 0 }}</strong>
          </span>
        </div>
        <div v-if="byStatus.length" class="paper-chips">
          <span v-for="row in byStatus" :key="row.status || row.k" class="paper-chip">
            {{ row.status || row.k }} <strong>{{ row.n ?? row.count ?? 0 }}</strong>
          </span>
        </div>
        <button type="button" class="paper-link" @click="openDefects()">
          查看本版本缺陷明细（{{ defectIdCount || defects.total || 0 }} 条）
        </button>
      </section>

      <!-- 5 遗留风险 -->
      <section id="report-section-residual" class="paper-section">
        <h3 class="paper-section__title">
          <span class="paper-section__no t-code">05</span>遗留风险
        </h3>
        <p class="paper-note">
          未关闭缺陷
          <EvidenceRef
            :value="openDefectCount"
            :disabled="!openDefectRows.length"
            :disabled-reason="openDefectRows.length ? '' : '该快照未保留未关闭缺陷列表'"
            title="查看未关闭缺陷"
            @open="openDefects()"
          />
          条 · 生效豁免 {{ residual.waiverCount ?? waivers.length }} 条
        </p>
        <table v-if="openDefectRows.length" class="paper-table">
          <thead>
            <tr><th>编号</th><th>标题</th><th>严重度</th><th>状态</th><th>来源</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in openDefectRows" :key="row.id ?? index">
              <td class="t-code">{{ row.code || `#${row.id}` }}</td>
              <td>{{ row.title || '—' }}</td>
              <td>{{ severityLabel(row.severity) }}</td>
              <td>{{ row.status || '—' }}</td>
              <td>{{ row.source || '—' }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="paper-note">本快照没有未关闭缺陷。</p>
        <table v-if="waivers.length" class="paper-table">
          <thead>
            <tr><th>规则</th><th>状态</th><th>原因</th><th>到期</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in waivers" :key="row.id ?? index">
              <td class="t-code">{{ row.ruleCode }}</td>
              <td>{{ row.status }}</td>
              <td>{{ row.reason }}</td>
              <td>{{ row.expiryAt || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <!-- 6 测试环境 -->
      <section id="report-section-env" class="paper-section">
        <h3 class="paper-section__title">
          <span class="paper-section__no t-code">06</span>测试环境
        </h3>
        <table v-if="envs.length" class="paper-table">
          <thead>
            <tr><th>环境</th><th>类型</th><th>地址</th><th>状态</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in envs" :key="row.envId ?? index">
              <td>{{ row.name || '—' }}</td>
              <td>{{ row.envType || '—' }}</td>
              <td class="t-code">{{ row.baseUrl || '—' }}</td>
              <td>{{ row.status || '—' }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="paper-note">本快照未关联测试环境。</p>
      </section>

      <!-- 7 风险与结论 -->
      <section id="report-section-risk" class="paper-section">
        <h3 class="paper-section__title">
          <span class="paper-section__no t-code">07</span>风险与结论
        </h3>
        <div class="paper-conclusion" :class="`is-${String(conclusion).toLowerCase() === 'pass' ? 'pass' : String(conclusion).toLowerCase() === 'fail' ? 'fail' : 'warn'}`">
          <span>服务端建议结论</span>
          <strong>{{ risk.conclusion || conclusion || '—' }}</strong>
          <span v-if="risk.suggested && risk.suggested !== risk.conclusion" class="paper-conclusion__suggested">
            （建议 {{ risk.suggested }}）
          </span>
        </div>
        <dl class="paper-facts">
          <div><dt>通过率阈值</dt><dd>{{ percent(risk.passRateThreshold) }}</dd></div>
          <div><dt>当前通过率</dt><dd>{{ percent(risk.passRate ?? passRate) }}</dd></div>
          <div><dt>当前覆盖率</dt><dd>{{ percent(risk.coverage ?? coverage) }}</dd></div>
          <div><dt>未关闭 P0/P1</dt><dd>{{ risk.openP0P1 ?? 0 }}</dd></div>
          <div v-if="requiredCases.enabled" class="paper-facts__wide">
            <dt>必需用例</dt>
            <dd>
              通过 {{ requiredCases.passed ?? 0 }} / {{ requiredCases.total ?? 0 }}
              （要求 {{ percent(requiredCases.requiredPassRate) }}）
            </dd>
          </div>
        </dl>
        <ul v-if="risks.length" class="paper-risks">
          <li v-for="(item, index) in risks" :key="item.key || index">
            <span class="paper-risk__level" :class="`is-${String(item.level || '').toLowerCase()}`">
              {{ item.level || '—' }}
            </span>
            <span>{{ item.message || item.note || '—' }}</span>
          </li>
        </ul>
        <p v-else class="paper-note">未识别到策略风险项。</p>
      </section>

      <!-- 8 会签记录 -->
      <section id="report-section-signoff" class="paper-section">
        <h3 class="paper-section__title">
          <span class="paper-section__no t-code">08</span>会签记录
        </h3>
        <ol class="paper-signoff">
          <li v-for="(level, index) in signoffLevels" :key="level.role || index">
            <span class="paper-signoff__no t-num">{{ level.orderNo ?? index + 1 }}</span>
            <span class="paper-signoff__role">{{ level.roleLabel || level.role }}</span>
            <span class="paper-signoff__decision">
              {{ level.decision || '未签' }}
            </span>
          </li>
        </ol>
        <p v-if="!signoffLevels.length" class="paper-note">本快照没有会签记录。</p>
        <p class="paper-note">
          会签进度 {{ signoff.progress ?? 0 }}/{{ signoff.total ?? 3 }}；未完成会签的版本不能作为发布门禁依据。
        </p>
      </section>

      <footer class="paper__foot">
        <span>快照 hash：</span>
        <span class="t-code">{{ snapshot.resultDigest || snapshot.hash || '—' }}</span>
      </footer>
    </article>

    <nav class="reader__toc" aria-label="报告目录">
      <span class="reader__toc-title">目录</span>
      <button
        v-for="(item, index) in sectionsDef"
        :key="item.key"
        type="button"
        class="reader__toc-item"
        @click="scrollTo(item.key)"
      >
        <span class="t-code">{{ String(index + 1).padStart(2, '0') }}</span>
        {{ item.title }}
      </button>
    </nav>

    <el-drawer v-model="drill.open" :title="drillTitle" size="560px">
      <div class="drill">
        <div class="drill__bar">
          <span v-if="drill.kind === 'cases' && drill.result" class="drill__filter">
            结果：{{ drill.result }}
            <button type="button" @click="openCases()">清除</button>
          </span>
          <span v-if="drill.kind === 'defects' && drill.severity" class="drill__filter">
            严重度：{{ drill.severity }}
            <button type="button" @click="openDefects()">清除</button>
          </span>
          <span class="drill__count">共 {{ drill.total }} 条 · 只读快照筛选</span>
        </div>

        <div v-if="drill.loading" class="drill__loading">
          <div v-for="index in 6" :key="index" class="skeleton skeleton__row" />
        </div>
        <p v-else-if="drill.error" class="drill__error">{{ drill.error }}</p>
        <template v-else>
          <table v-if="drill.kind === 'cases'" class="drill-table">
            <thead>
              <tr><th>用例</th><th>标题</th><th>模块</th><th>结果</th><th>执行人</th></tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in caseRows" :key="row.planCaseId ?? index">
                <td class="t-code">{{ row.caseCode || `#${row.caseId}` }}<span v-if="row.caseVersion" class="drill__ver">v{{ row.caseVersion }}</span></td>
                <td :title="row.title">{{ row.title || '—' }}</td>
                <td>{{ row.moduleName || '—' }}</td>
                <td>
                  <StatusPill kind="execution" :value="row.result || 'NOT_RUN'" size="sm" />
                </td>
                <td>{{ row.executorName || '—' }}</td>
              </tr>
              <tr v-if="!drill.rows.length">
                <td colspan="5" class="drill__empty">没有符合条件的明细。</td>
              </tr>
            </tbody>
          </table>
          <table v-else class="drill-table">
            <thead>
              <tr><th>编号</th><th>标题</th><th>严重度</th><th>状态</th><th>发现时间</th></tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in defectRows" :key="row.id ?? index">
                <td class="t-code">{{ row.code || `#${row.id}` }}</td>
                <td :title="row.title">{{ row.title || '—' }}</td>
                <td>{{ severityLabel(row.severity) }}</td>
                <td>{{ row.status || '—' }}</td>
                <td>{{ row.foundAt || '—' }}</td>
              </tr>
              <tr v-if="!drill.rows.length">
                <td colspan="5" class="drill__empty">没有符合条件的明细。</td>
              </tr>
            </tbody>
          </table>

          <el-pagination
            v-if="drill.total > drill.size"
            class="drill__pager"
            layout="prev, pager, next, total"
            :total="drill.total"
            :page-size="drill.size"
            :current-page="drill.page"
            background
            @current-change="onDrillPage"
          />
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.reader {
  display: flex;
  align-items: flex-start;
  gap: 24px;
}
.reader__paper {
  flex: 1;
  min-width: 0;
  max-width: var(--paper-max, 820px);
  padding: var(--paper-pad, 50px);
  border: 1px solid var(--paper-edge, #e5e6eb);
  border-radius: 2px;
  background: var(--bg-card);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}
.reader__toc {
  flex: none;
  width: 200px;
  position: sticky;
  top: 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
}
.reader__toc-title {
  padding: 4px 8px;
  font-size: 11px;
  color: var(--text-weak);
}
.reader__toc-item {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 28px;
  padding: 0 8px;
  border: 0;
  border-radius: var(--r-ctl);
  background: transparent;
  color: var(--text-body);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  transition: background var(--t-color) var(--ease);
}
.reader__toc-item:hover {
  background: var(--bg-hover);
  color: var(--color-primary);
}
.reader__toc-item:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
@media (max-width: 1279px) {
  .reader__toc {
    display: none;
  }
}
.paper__head {
  padding-bottom: 16px;
  border-bottom: 2px solid var(--text-title);
}
.paper__crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--text-muted-aa);
}
.paper__dot {
  color: var(--text-weak);
}
.paper__title {
  margin: 8px 0 2px;
  font-size: 24px;
  font-weight: 600;
  color: var(--text-title);
  letter-spacing: 0.02em;
}
.paper__subtitle {
  margin: 0;
  font-size: 12px;
  color: var(--text-body);
}
.paper-section {
  padding-top: 24px;
  break-inside: avoid;
}
.paper-section + .paper-section {
  margin-top: 24px;
  border-top: 1px solid var(--divider);
}
.paper-section__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.paper-section__no {
  width: 24px;
  flex: none;
  color: var(--color-primary);
  font-size: 12px;
}
.paper-facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 8px 16px;
  margin: 0 0 10px;
}
.paper-facts__wide {
  grid-column: 1 / -1;
}
.paper-facts dt {
  font-size: 11px;
  color: var(--text-muted-aa);
}
.paper-facts dd {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-title);
}
.paper-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(88px, 1fr));
  gap: 1px;
  margin-bottom: 10px;
  border: 1px solid var(--divider);
  background: var(--divider);
}
.paper-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  background: var(--bg-card);
}
.paper-stat span {
  font-size: 11px;
  color: var(--text-muted-aa);
}
.paper-stat strong {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.paper-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.paper-table th {
  height: 32px;
  padding: 0 8px;
  background: var(--bg-table-head);
  border-bottom: 1px solid var(--border);
  color: var(--text-muted-aa);
  font-weight: 500;
  text-align: left;
}
.paper-table td {
  height: 40px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--divider);
  color: var(--text-body);
  vertical-align: middle;
}
.paper-table .is-num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.paper-table tr:last-child td {
  border-bottom: 0;
}
.paper-note {
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 17px;
  color: var(--text-muted-aa);
}
.paper-note--warn {
  color: var(--warning-ink);
}
.paper-rates {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.paper-rate {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
}
.paper-rate__label {
  font-size: 11px;
  color: var(--text-muted-aa);
}
.paper-rate__value {
  font-size: 24px;
  font-weight: 600;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.paper-rate__formula {
  font-size: 11px;
  color: var(--text-weak);
  font-family: var(--font-mono);
}
.paper-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.paper-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 22px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  font-size: 11px;
  color: var(--text-body);
}
.paper-chip strong {
  color: var(--text-title);
}
.paper-link {
  margin-top: 8px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-primary);
  font-size: 12px;
  cursor: pointer;
}
.paper-link:hover {
  text-decoration: underline;
}
.paper-conclusion {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 10px;
  padding: 10px 12px;
  border-left: 3px solid var(--text-weak);
  background: var(--color-info-bg);
  font-size: 12px;
  color: var(--text-body);
}
.paper-conclusion strong {
  font-size: 16px;
  color: var(--text-title);
}
.paper-conclusion.is-pass {
  border-left-color: var(--color-success);
  background: var(--color-success-bg);
}
.paper-conclusion.is-fail {
  border-left-color: var(--color-danger);
  background: var(--color-danger-bg);
}
.paper-conclusion.is-warn {
  border-left-color: var(--color-warning);
  background: var(--color-warning-bg);
}
.paper-conclusion__suggested {
  color: var(--text-muted-aa);
}
.paper-risks {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.paper-risks li {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  color: var(--text-body);
}
.paper-risk__level {
  flex: none;
  font-size: 11px;
  font-weight: 500;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--color-info-bg);
  color: var(--text-muted-aa);
}
.paper-risk__level.is-high,
.paper-risk__level.is-critical {
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}
.paper-risk__level.is-medium {
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.paper-signoff {
  margin: 0;
  padding: 0;
  list-style: none;
}
.paper-signoff li {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 32px;
  border-bottom: 1px solid var(--divider);
  font-size: 12px;
}
.paper-signoff__no {
  width: 18px;
  color: var(--text-weak);
}
.paper-signoff__role {
  flex: 1;
  color: var(--text-title);
}
.paper-signoff__decision {
  color: var(--text-muted-aa);
}
.paper__foot {
  margin-top: 32px;
  padding-top: 8px;
  border-top: 1px solid var(--divider);
  font-size: 10px;
  color: var(--text-weak);
  word-break: break-all;
}
.reader-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
}
.reader-empty {
  padding: 40px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
}
.drill__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
  font-size: 12px;
}
.drill__filter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 22px;
  padding: 0 8px;
  border-radius: var(--r-ctl);
  background: var(--color-primary-light);
  color: var(--color-primary);
}
.drill__filter button {
  border: 0;
  background: transparent;
  color: inherit;
  font-size: 12px;
  cursor: pointer;
  text-decoration: underline;
}
.drill__count {
  margin-left: auto;
  color: var(--text-muted-aa);
}
.drill__loading {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.drill__error {
  font-size: 12px;
  color: var(--danger-ink);
}
.drill-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.drill-table th {
  height: 28px;
  padding: 0 6px;
  background: var(--bg-table-head);
  border-bottom: 1px solid var(--border);
  color: var(--text-muted-aa);
  font-weight: 500;
  text-align: left;
  white-space: nowrap;
}
.drill-table td {
  height: 36px;
  padding: 4px 6px;
  border-bottom: 1px solid var(--divider);
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.drill__ver {
  margin-left: 4px;
  color: var(--text-weak);
}
.drill__empty {
  text-align: center;
  color: var(--text-muted-aa);
  height: 60px;
}
.drill__pager {
  margin-top: 12px;
  justify-content: flex-end;
}
@media print {
  .reader__toc {
    display: none;
  }
  .reader__paper {
    max-width: none;
    border: 0;
    padding: 0;
    box-shadow: none;
  }
  .paper-section {
    break-inside: avoid;
  }
}
</style>
