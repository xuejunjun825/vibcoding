<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  deployStats,
  evaluateAdmission,
  getDeployOrder,
  recordExecution,
  submitDeployOrder,
} from '@/api/req'
import { ApiError } from '@/api/client'
import type { AdmissionSnapshot, DeployOrderDetail, GateItem } from '@/api/types'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import ConclusionBar from '@/components/quality/ConclusionBar.vue'
import QualityGatePanel from '@/components/quality/QualityGatePanel.vue'
import { usePermission } from '@/composables/usePermission'
import { formatDateTime, formatDuration, formatPercent } from '@/utils/format'
import { messageOf } from '@/views/req/platform'

/**
 * P4 发布单详情：6 项准入常驻清单（不折叠、不用 toast），第 6 项质量门可展开规则卡；
 * 409 原地展示逐条规则差额；强制放行入口已退役，历史记录只读展示，执行结果单独登记。
 */
const ENVS: Record<string, string> = { DEV: '开发', TEST: '测试', STAGING: '预发', PROD: '生产' }
const RESULTS = [
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'ROLLED_BACK', label: '回滚' },
] as const

const route = useRoute()
const router = useRouter()
const { can } = usePermission()

const detail = ref<DeployOrderDetail | null>(null)
const admission = ref<AdmissionSnapshot | null>(null)
const loading = ref(true)
const evaluating = ref(false)
const error = ref('')
const rejected = ref(false)
const rejectedText = ref('')
const actionError = ref('')

const id = computed(() => Number(route.params.id))
const items = computed<GateItem[]>(() => admission.value?.items ?? detail.value?.admission?.items ?? [])
const failedItems = computed(() => items.value.filter((item) => !item.passed))
const allPassed = computed(() => items.value.length > 0 && failedItems.value.length === 0)
const missingChecklist = computed(() => items.value.length === 0)
const submitBlockReason = computed(() => {
  if (!can('deploy:create')) return '需要权限：deploy:create'
  if (missingChecklist.value) return '尚未完成准入校验，请先执行校验'
  if (!allPassed.value) return `准入未通过（${failedItems.value.length} 项），先修复再提交`
  if (detail.value?.status === 'SUCCESS' || detail.value?.status === 'ROLLED_BACK') return '已完成执行，无需再次提交'
  return ''
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    detail.value = await getDeployOrder(id.value)
    admission.value = detail.value.admission ?? null
    if (!admission.value) {
      await evaluate(true)
    }
  } catch (err) {
    error.value = messageOf(err)
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function evaluate(silent = false): Promise<void> {
  evaluating.value = true
  rejected.value = false
  actionError.value = ''
  try {
    admission.value = await evaluateAdmission(id.value)
    if (!silent) ElMessage.success('准入校验已刷新')
  } catch (err) {
    if (!silent) actionError.value = messageOf(err)
  } finally {
    evaluating.value = false
  }
}

onMounted(load)
watch(id, load)

const submitting = ref(false)

async function submit(): Promise<void> {
  if (submitBlockReason.value) return
  submitting.value = true
  rejected.value = false
  rejectedText.value = ''
  actionError.value = ''
  try {
    await submitDeployOrder(id.value)
    ElMessage.success('发布申请已提交，进入审批')
    await load()
  } catch (err) {
    if (err instanceof ApiError && err.isConflict) {
      // 先重算再置位：evaluate() 会清掉上一次的拒绝条，顺序反了会让 409 一闪而过
      await evaluate(true)
      rejected.value = true
      rejectedText.value = err.message || '准入校验未通过，提交被拒绝'
      gateFailedRules.value = failedRulesOf(err)
    } else {
      actionError.value = messageOf(err)
    }
  } finally {
    submitting.value = false
  }
}

interface FailedRuleDetail {
  code: string
  name?: string
  actual?: string
  threshold?: string
  fixHint?: string
}

const gateFailedRules = ref<FailedRuleDetail[]>([])

function failedRulesOf(err: ApiError): FailedRuleDetail[] {
  const detail = err.detail as { failedRules?: FailedRuleDetail[]; rules?: FailedRuleDetail[] } | undefined
  const list = detail?.failedRules ?? detail?.rules ?? []
  return list.map((rule) => ({ ...rule, code: rule.code ?? 'QG' }))
}

const executionOpen = ref(false)
const execResult = ref<'SUCCESS' | 'FAILED' | 'ROLLED_BACK'>('SUCCESS')
const execStart = ref('')
const execEnd = ref('')
const execNote = ref('')
const execDefectId = ref<string>('')
const execSaving = ref(false)
const execError = ref('')

function openExecution(): void {
  const execution = detail.value?.execution
  execResult.value = execution?.result ?? 'SUCCESS'
  execStart.value = execution?.startedAt ?? ''
  execEnd.value = execution?.endedAt ?? ''
  execNote.value = execution?.note ?? ''
  execDefectId.value = execution?.defectId ? String(execution.defectId) : ''
  execError.value = ''
  executionOpen.value = true
}

const execDuration = computed(() => {
  if (!execStart.value || !execEnd.value) return ''
  const start = new Date(execStart.value).getTime()
  const end = new Date(execEnd.value).getTime()
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return ''
  return formatDuration((end - start) / 60000)
})

async function submitExecution(): Promise<void> {
  if (!execStart.value || !execEnd.value) {
    execError.value = '请填写实际开始与结束时间'
    return
  }
  if (execEnd.value < execStart.value) {
    execError.value = '结束时间不能早于开始时间'
    return
  }
  if (execResult.value !== 'SUCCESS' && execNote.value.trim().length < 2) {
    execError.value = '失败或回滚必须填写备注，说明现象与处理'
    return
  }
  execSaving.value = true
  execError.value = ''
  try {
    await recordExecution(id.value, {
      result: execResult.value,
      startedAt: execStart.value,
      endedAt: execEnd.value,
      note: execNote.value.trim() || undefined,
      defectId: execDefectId.value ? Number(execDefectId.value) : undefined,
    })
    executionOpen.value = false
    ElMessage.success('执行结果已登记')
    await load()
  } catch (err) {
    execError.value = messageOf(err)
  } finally {
    execSaving.value = false
  }
}

const stats = ref<{ successRate?: number; avgDurationMinutes?: number; failReasons?: Array<{ reason: string; count: number }> } | null>(null)

async function loadStats(): Promise<void> {
  if (!detail.value) return
  try {
    stats.value = await deployStats({ releaseId: detail.value.releaseId })
  } catch {
    stats.value = null
  }
}

watch(detail, () => {
  void loadStats()
})

function envLabel(value?: string): string {
  return value ? (ENVS[value] ?? value) : '—'
}

function stepsText(value: unknown): string {
  if (value == null || value === '') return '未填写'
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.map((step, index) => `${index + 1}. ${String(step)}`).join('\n')
  return JSON.stringify(value)
}

function goBack(): void {
  void router.push({ name: 'deploy-orders' })
}

const goNoGoTone = computed<'success' | 'warning' | 'danger' | 'neutral'>(() => {
  const decision = detail.value?.review?.decision
  if (decision === 'GO') return 'success'
  if (decision === 'CONDITIONAL') return 'warning'
  if (decision === 'NO_GO') return 'danger'
  return 'neutral'
})
const goNoGoLabel = computed(() => {
  const decision = detail.value?.review?.decision
  if (decision === 'GO') return 'GO · 允许发布'
  if (decision === 'CONDITIONAL') return 'CONDITIONAL · 附条件发布'
  if (decision === 'NO_GO') return 'NO-GO · 禁止发布'
  return '尚未生成 Go/No-Go 评审'
})
const goNoGoHint = computed(() => {
  const review = detail.value?.review
  if (!review) return '质量门通过后由 PMO 在发布评审页生成决策记录'
  return `评审时间 ${formatDateTime(review.meetingAt)}${review.conditions ? ` · ${review.conditions}` : ''}`
})
const legacyAdmission = computed(() => Boolean(admission.value?.legacy))
</script>

<template>
  <div class="deploy-detail">
    <ErrorState v-if="error" title="发布单加载失败" :message="error" @retry="load" />

    <template v-else-if="detail">
      <header class="page-head">
        <div class="page-head__left">
          <div class="head-stack">
            <div class="crumbs">
            <button type="button" class="link" @click="goBack">发布单</button>
            <span class="crumbs__sep">/</span>
            <span>{{ detail.code }}</span>
          </div>
          <div class="title-row">
            <h1 class="page-head__title mono">{{ detail.code }}</h1>
            <StatusPill kind="deploy" :value="detail.status" />
            <span class="env-tag" :class="`env-${String(detail.env).toLowerCase()}`">{{ envLabel(detail.env) }}环境</span>
            <span class="artifact">制品 {{ detail.artifactVersion || '—' }}</span>
          </div>
          <p class="meta-line">
            <span>版本 {{ detail.releaseName || `#${detail.releaseId}` }}</span>
            <span>窗口 {{ formatDateTime(detail.windowStart) }} → {{ formatDateTime(detail.windowEnd) }}</span>
            <span v-if="detail.ownerName">负责人 {{ detail.ownerName }}</span>
          </p>
          </div>
        </div>
        <div class="page-head__actions">
          <el-button size="small" :loading="evaluating" @click="evaluate(false)">重新校验</el-button>
          <el-button
            size="small"
            :disabled="!can('deploy:execute')"
            :title="can('deploy:execute') ? '' : '需要权限：deploy:execute'"
            @click="openExecution"
          >
            登记执行结果
          </el-button>
          <el-button
            type="primary"
            size="small"
            :loading="submitting"
            :disabled="Boolean(submitBlockReason)"
            :title="submitBlockReason"
            @click="submit"
          >
            {{ detail.status === 'FAILED' ? '重新发起' : '提交发布申请' }}
          </el-button>
        </div>
      </header>

      <el-tooltip
        v-if="detail.forcedRelease"
        content="历史强制放行，规则级豁免已替代"
        placement="bottom"
      >
        <div class="legacy-force" role="status">
          <span class="legacy-force__badge">历史强制放行</span>
          <span class="legacy-force__reason">{{ detail.forcedRelease.reason }}</span>
          <span class="muted small">
            {{ detail.forcedRelease.by }} · {{ formatDateTime(detail.forcedRelease.at) }}
          </span>
        </div>
      </el-tooltip>

      <ConclusionBar :tone="goNoGoTone" :title="goNoGoLabel" :hint="goNoGoHint" dense>
        <el-button
          v-if="detail.releaseId"
          size="small"
          @click="router.push({ name: 'release-go-no-go', params: { id: detail.releaseId } })"
        >
          查看发布评审
        </el-button>
      </ConclusionBar>

      <div v-if="actionError" class="action-banner" role="alert">
        <span>{{ actionError }}</span>
        <button type="button" class="link" @click="actionError = ''">关闭</button>
      </div>

      <div class="columns">
        <section class="surface surface--flush column-main">
          <div v-if="rejected && gateFailedRules.length > 0" class="gate-conflict" role="alert">
            <header class="gate-conflict__head">
              <strong>质量门未通过，发布申请被拒绝</strong>
              <span class="muted small">{{ rejectedText }}</span>
            </header>
            <ul class="gate-conflict__list">
              <li v-for="rule in gateFailedRules" :key="rule.code" class="gate-conflict__row">
                <span class="mono gate-conflict__code">{{ rule.code }}</span>
                <span class="gate-conflict__name">{{ rule.name || '门禁规则' }}</span>
                <span class="gate-conflict__diff">
                  <span class="ink-danger">{{ rule.actual || '未通过' }}</span>
                  <span class="muted"> / 要求 {{ rule.threshold || '—' }}</span>
                </span>
                <span v-if="rule.fixHint" class="muted small gate-conflict__fix">{{ rule.fixHint }}</span>
                <el-button
                  v-if="detail.releaseId"
                  link
                  type="primary"
                  size="small"
                  @click="router.push({ name: 'quality-gate-detail', params: { releaseId: detail.releaseId } })"
                >
                  去修复
                </el-button>
              </li>
            </ul>
          </div>

          <QualityGatePanel
            mode="admission"
            title="发布准入检查（6 项）"
            :items="items"
            :rejected="rejected"
            :rejected-text="rejectedText"
            :checked-at="admission?.checkedAt ?? null"
            :stale="Boolean(admission?.stale)"
            :legacy="legacyAdmission"
          />
          <div class="checklist-foot">
            <span class="muted small">
              准入由服务端按当前数据实时计算；第 6 项质量门只透传 deliver 的规则结果，客户端提交的 PASS/FAIL 一律无效。
            </span>
            <span v-if="missingChecklist" class="muted small">尚未拿到校验结果，请点「重新校验」。</span>
          </div>
        </section>

        <aside class="column-side">
          <section class="surface surface--pad side-block">
            <h2 class="section-title">发布基本信息</h2>
            <dl class="fields">
              <dt>发布步骤</dt>
              <dd>{{ stepsText(detail.steps) }}</dd>
              <dt>回滚方案</dt>
              <dd>{{ detail.rollbackPlan || '未填写' }}</dd>
              <dt>影响范围</dt>
              <dd>{{ detail.impact || '未填写' }}</dd>
            </dl>
          </section>

          <section class="surface surface--pad side-block">
            <h2 class="section-title">执行记录</h2>
            <template v-if="detail.execution">
              <div class="exec-row">
                <span class="muted small">结果</span>
                <StatusPill kind="deploy" :value="detail.execution.result" size="sm" />
              </div>
              <div class="exec-row">
                <span class="muted small">开始</span>
                <span class="num">{{ formatDateTime(detail.execution.startedAt) }}</span>
              </div>
              <div class="exec-row">
                <span class="muted small">结束</span>
                <span class="num">{{ formatDateTime(detail.execution.endedAt) }}</span>
              </div>
              <div v-if="detail.execution.note" class="exec-note">{{ detail.execution.note }}</div>
              <p v-if="detail.execution.defectId" class="muted small">关联缺陷 #{{ detail.execution.defectId }}</p>
            </template>
            <p v-else class="muted small">还没有登记执行结果；发布完成后回填，统计才会准确。</p>
          </section>

          <section class="surface surface--pad side-block">
            <h2 class="section-title">本版本发布统计</h2>
            <div class="mini-stats">
              <div>
                <span class="muted small">成功率</span>
                <b>{{ stats?.successRate != null ? formatPercent(stats.successRate) : '—' }}</b>
              </div>
              <div>
                <span class="muted small">平均耗时</span>
                <b>{{ stats?.avgDurationMinutes != null ? formatDuration(stats.avgDurationMinutes) : '—' }}</b>
              </div>
            </div>
            <ul v-if="stats?.failReasons?.length" class="reasons">
              <li v-for="reason in stats.failReasons" :key="reason.reason">
                <span>{{ reason.reason }}</span>
                <b>{{ reason.count }}</b>
              </li>
            </ul>
            <p v-else class="muted small">暂无失败原因统计。</p>
          </section>
        </aside>
      </div>
    </template>

    <el-dialog v-model="executionOpen" title="登记执行结果" width="520px" append-to-body>
      <el-form label-position="top" size="small">
        <el-form-item label="执行结果" required>
          <el-radio-group v-model="execResult">
            <el-radio-button v-for="item in RESULTS" :key="item.value" :value="item.value">{{ item.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="实际开始" required>
            <el-date-picker v-model="execStart" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="开始时间" />
          </el-form-item>
          <el-form-item label="实际结束" required>
            <el-date-picker v-model="execEnd" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="结束时间" />
          </el-form-item>
        </div>
        <p v-if="execDuration" class="duration">耗时 {{ execDuration }}（自动计算）</p>
        <el-form-item :label="execResult === 'SUCCESS' ? '备注（可选）' : '备注（必填）'">
          <el-input v-model="execNote" type="textarea" :rows="3" maxlength="300" :placeholder="execResult === 'SUCCESS' ? '补充说明' : '说明现象、影响与处理'" />
        </el-form-item>
        <el-form-item v-if="execResult !== 'SUCCESS'" label="关联缺陷（可选）">
          <el-input v-model="execDefectId" placeholder="缺陷 ID，如 1024" />
        </el-form-item>
      </el-form>
      <p v-if="execError" class="dialog-error">{{ execError }}</p>
      <template #footer>
        <el-button @click="executionOpen = false">取消</el-button>
        <el-button type="primary" :loading="execSaving" @click="submitExecution">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.deploy-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/* 头部左侧是“面包屑/标题/元信息”的纵向堆栈；全局 .page-head__left 是横排 */
.head-stack {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-width: 0;
  max-width: 100%;
}
.page-head__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text-title);
}
.crumbs {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-muted-aa);
  margin-bottom: 4px;
  white-space: nowrap;
}
.crumbs__sep {
  color: var(--text-weak);
}
.link {
  border: none;
  background: none;
  padding: 0;
  font-size: inherit;
  color: var(--color-primary);
  cursor: pointer;
}
.link:hover {
  text-decoration: underline;
}
.link:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}
.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.env-tag {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: var(--r-pill);
  background: var(--color-info-bg);
  color: var(--info-ink);
}
.env-prod {
  background: var(--color-danger-bg);
  color: var(--danger-ink);
}
.env-staging {
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.artifact {
  font-size: 12px;
  color: var(--text-muted-aa);
  font-family: var(--font-mono);
}
.meta-line {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.legacy-force {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid #e9c989;
  border-left: 3px solid var(--color-warning);
  border-radius: var(--r-ctl);
  background: repeating-linear-gradient(
    45deg,
    #ffefc9,
    #ffefc9 8px,
    #fff7e6 8px,
    #fff7e6 16px
  );
  color: var(--warning-ink);
  font-size: 12px;
}
.legacy-force__badge {
  flex: none;
  padding: 1px 8px;
  border: 1px solid #e9c989;
  border-radius: var(--r-pill);
  background: #fff;
  font-weight: 600;
}
.legacy-force__reason {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gate-conflict {
  margin: 12px;
  border: 1px solid var(--color-danger);
  border-left: 3px solid var(--color-danger);
  border-radius: var(--r-ctl);
  background: var(--color-danger-bg);
}
.gate-conflict__head {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 12px;
  border-bottom: 1px solid rgba(217, 54, 62, 0.18);
  color: var(--danger-ink);
  font-size: 13px;
}
.gate-conflict__list {
  margin: 0;
  padding: 4px 0;
  list-style: none;
}
.gate-conflict__row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--text-body);
}
.gate-conflict__code {
  flex: none;
  width: 64px;
  color: var(--danger-ink);
  font-weight: 600;
}
.gate-conflict__name {
  flex: none;
  min-width: 120px;
  color: var(--text-title);
}
.gate-conflict__diff {
  flex: none;
}
.gate-conflict__fix {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.forced-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--color-warning);
  border-left: 3px solid var(--color-warning);
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
}
.action-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--color-danger);
  border-left: 3px solid var(--color-danger);
  border-radius: var(--r-ctl);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 13px;
}
.columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 12px;
  align-items: start;
}
.column-main {
  min-width: 0;
  overflow: hidden;
}
.checklist-foot {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 14px;
  border-top: 1px solid var(--divider);
}
.column-side {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.side-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.fields {
  display: grid;
  grid-template-columns: 68px minmax(0, 1fr);
  gap: 6px 10px;
  margin: 0;
  font-size: 12px;
}
.fields dt {
  color: var(--text-muted-aa);
}
.fields dd {
  margin: 0;
  color: var(--text-body);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.exec-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
}
.exec-note {
  padding: 8px;
  border-radius: var(--r-cell);
  background: var(--bg-hover);
  font-size: 12px;
  color: var(--text-body);
  line-height: 1.6;
  white-space: pre-wrap;
}
.mini-stats {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.mini-stats div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.mini-stats b {
  font-size: 16px;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.reasons {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--text-body);
}
.reasons li {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.form-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.duration {
  margin: -4px 0 10px;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.muted {
  color: var(--text-muted-aa);
}
.small {
  font-size: 12px;
}
.mono {
  font-family: var(--font-mono);
}
.num {
  font-variant-numeric: tabular-nums;
}
.dialog-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger-ink);
}
@media (max-width: 1180px) {
  .columns {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
