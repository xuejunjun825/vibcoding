<script setup lang="ts">
/**
 * 报告详情（DESIGN Q7 / AC-4）。
 *
 * 报告版本不可变：页面只读快照 + 会签 + 导出；要改内容只能“重新生成”，
 * 新版本产生后旧版本由服务端置 SUPERSEDED，这里显示只读横幅并禁用会签。
 * 会签严格顺序由服务端判定，前端只在“当前节点且 canSign”时给签署入口。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { reportApi } from '@/api/quality'
import { ApiError } from '@/api/client'
import type {
  ReportDetail,
  ReportVersionDetail,
  ReportVersionRow,
  SignoffProgress as SignoffProgressModel,
  SignoffStep,
} from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import ErrorState from '@/components/ErrorState.vue'
import ReportReader from '@/components/quality/ReportReader.vue'
import SignoffProgress from '@/components/quality/SignoffProgress.vue'
import ConclusionBar from '@/components/quality/ConclusionBar.vue'
import DigestChip from '@/components/quality/DigestChip.vue'
import { formatDateTime, formatPercent } from '@/utils/format'
import '@/views/deliver/shared.css'

interface ReportDetailModel extends ReportDetail {
  report: ReportDetail['report'] & {
    planName?: string
    releaseName?: string
    releaseCode?: string
    expiresAt?: string | null
    validityDays?: number
    generatedByName?: string
    lastRejectComment?: string
  }
  excludedPlans?: Array<{ planId: number; code?: string; name?: string; status?: string }>
  includedPlans?: Array<{ planId: number; code?: string; name?: string; status?: string }>
  uncoveredRequirements?: Array<{ requirementId: number; code?: string; title?: string }>
}

interface DiffModel {
  from?: number
  to?: number
  passRate?: { before?: number | null; after?: number | null }
  coverage?: { before?: number | null; after?: number | null }
  conclusion?: { before?: string | null; after?: string | null }
  addedFails?: Array<{ caseCode?: string; title?: string; result?: string }>
  closedDefects?: Array<{ code?: string; title?: string }>
}

const route = useRoute()
const router = useRouter()
const reportId = computed(() => Number(route.params.id))

const data = ref<ReportDetailModel | null>(null)
const versions = ref<ReportVersionRow[]>([])
const selectedVersion = ref(1)
const versionDetail = ref<ReportVersionDetail | null>(null)
const signoff = ref<SignoffProgressModel | null>(null)
const diff = ref<DiffModel | null>(null)
const diffOpen = ref(false)
const loading = ref(false)
const versionLoading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const actionError = ref<{ message: string; code?: string } | null>(null)

const signOpen = ref(false)
const signStep = ref<SignoffStep | null>(null)
const signDecision = ref<'APPROVE' | 'REJECT'>('APPROVE')
const signConclusion = ref('')
const signComment = ref('')
const signing = ref(false)
const signError = ref<{ message: string; code?: string } | null>(null)

const regenOpen = ref(false)
const regenNote = ref('')
const regenerating = ref(false)
const regenError = ref<{ message: string; code?: string } | null>(null)
const exporting = ref<'xlsx' | 'pdf' | null>(null)

const report = computed(() => data.value?.report ?? null)
const isCurrent = computed(() => selectedVersion.value === report.value?.currentVersion)
const selectedVersionRow = computed<
  (ReportVersionRow & { signedCount?: number; totalSignoffs?: number }) | null
>(() => {
  const found = versions.value.find((item) => item.versionNo === selectedVersion.value)
  return (found as (ReportVersionRow & { signedCount?: number; totalSignoffs?: number }) | undefined) ?? null
})
const readonly = computed(() => {
  if (!isCurrent.value) return true
  const status = String(versionDetail.value?.status ?? report.value?.status ?? '').toUpperCase()
  return status === 'SUPERSEDED' || status === 'REJECTED'
})
const versionStatus = computed(
  () => String(versionDetail.value?.status ?? selectedVersionRow.value?.status ?? report.value?.status ?? ''),
)
const snapshot = computed(() => versionDetail.value?.snapshot ?? null)
const suggestedConclusion = computed(() => {
  const risk = (snapshot.value?.sections as Record<string, any> | undefined)?.riskConclusion
  return String(risk?.conclusion ?? risk?.suggested ?? snapshot.value?.conclusion ?? '')
})
const effectiveConclusion = computed(
  () => versionDetail.value?.conclusion || selectedVersionRow.value?.conclusion || snapshot.value?.conclusion || '',
)
const waiverCount = computed(() => {
  const residual = (snapshot.value?.sections as Record<string, any> | undefined)?.residual
  return Number(residual?.waiverCount ?? 0)
})
const signoffWarning = computed(() => {
  if (readonly.value) return ''
  if (versionStatus.value === 'SIGNED') return ''
  return '未完成三级会签，本版本不能作为发布门禁依据。'
})
const selectedScopeLabel = computed(() => {
  if (!report.value) return ''
  if (report.value.scope === 'PLAN') return report.value.planName || '计划范围'
  return report.value.releaseName || report.value.releaseCode || '版本范围'
})
const expiryText = computed(() => {
  if (!report.value?.expiresAt) return '未设置'
  return report.value.expiresAt
})
const daysLeft = computed(() => {
  const raw = report.value?.expiresAt
  if (!raw) return null
  const end = new Date(raw).getTime()
  if (Number.isNaN(end)) return null
  return Math.ceil((end - Date.now()) / 86400000)
})

const CONCLUSION_RANK: Record<string, number> = { FAIL: 1, CONDITIONAL: 2, PASS: 3 }
const CONCLUSION_LABEL: Record<string, string> = { PASS: '通过', CONDITIONAL: '有条件通过', FAIL: '不通过' }
const conclusionOptions = computed(() => {
  const suggested = suggestedConclusion.value
  const limit = CONCLUSION_RANK[suggested] ?? 3
  return ['PASS', 'CONDITIONAL', 'FAIL'].map((value) => ({
    value,
    label: CONCLUSION_LABEL[value],
    disabled: (CONCLUSION_RANK[value] ?? 0) > limit,
    hint: (CONCLUSION_RANK[value] ?? 0) > limit ? `不能高于系统建议「${CONCLUSION_LABEL[suggested] ?? suggested}」` : '',
  }))
})
const canSubmitSign = computed(() => {
  if (signDecision.value === 'REJECT') return signComment.value.trim().length > 0
  if (!signConclusion.value) return false
  if (signConclusion.value === suggestedConclusion.value) return true
  return signComment.value.trim().length > 0
})

function messageOf(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.code) {
      case 'SIGNOFF_ORDER_VIOLATION':
        return '会签必须按顺序进行：前一位签署人完成后才轮到你。'
      case 'CONCLUSION_UPGRADE_FORBIDDEN':
        return '不能把结论上调到高于系统建议的等级；如确需更高结论，请重新生成报告。'
      case 'REPORT_NOT_SIGNED':
        return '报告尚未完成会签，不能作为发布门禁依据。'
      case 'REPORT_EXPIRED':
        return '报告已过有效期，请重新生成。'
      case 'REPORT_NO_PLAN':
        return '该版本没有已批准的测试计划，无法生成报告。'
      default:
        return err.message || err.code || '请求失败'
    }
  }
  return err instanceof Error ? err.message : '请求失败'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const detail = (await reportApi.detail(reportId.value)) as ReportDetailModel
    data.value = detail
    versions.value = await reportApi.versions(reportId.value)
    const target = detail.report?.currentVersion ?? versions.value[0]?.versionNo ?? 1
    selectedVersion.value = target
    await loadVersion(target)
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

async function loadVersion(versionNo: number): Promise<void> {
  versionLoading.value = true
  actionError.value = null
  diff.value = null
  try {
    versionDetail.value = await reportApi.version(reportId.value, versionNo)
    signoff.value =
      versionNo === report.value?.currentVersion ? (data.value?.signoffProgress ?? null) : null
    if (versionNo > 1) {
      try {
        diff.value = (await reportApi.diff(reportId.value, versionNo - 1, versionNo)) as DiffModel
      } catch {
        diff.value = null
      }
    }
  } catch (err) {
    actionError.value = { message: messageOf(err), code: err instanceof ApiError ? err.code : undefined }
  } finally {
    versionLoading.value = false
  }
}

function selectVersion(versionNo: number): void {
  if (versionNo === selectedVersion.value) return
  selectedVersion.value = versionNo
  void loadVersion(versionNo)
}

function openSign(step: SignoffStep): void {
  signStep.value = step
  signDecision.value = 'APPROVE'
  signConclusion.value = suggestedConclusion.value || 'PASS'
  signComment.value = ''
  signError.value = null
  signOpen.value = true
}

async function submitSign(): Promise<void> {
  if (!signStep.value || !canSubmitSign.value || signing.value) return
  signing.value = true
  signError.value = null
  try {
    await reportApi.signoff(reportId.value, selectedVersion.value, {
      decision: signDecision.value,
      comment: signComment.value.trim() || undefined,
      ...(signDecision.value === 'APPROVE' ? { conclusion: signConclusion.value } : {}),
    } as any)
    ElMessage.success(signDecision.value === 'APPROVE' ? '已签署' : '已驳回该版本')
    signOpen.value = false
    await load()
  } catch (err) {
    signError.value = { message: messageOf(err), code: err instanceof ApiError ? err.code : undefined }
  } finally {
    signing.value = false
  }
}

async function exportFile(format: 'xlsx' | 'pdf'): Promise<void> {
  if (exporting.value) return
  exporting.value = format
  actionError.value = null
  try {
    await reportApi.exportFile(reportId.value, selectedVersion.value, format)
  } catch (err) {
    actionError.value = {
      message: messageOf(err),
      code: err instanceof ApiError ? err.code : undefined,
    }
  } finally {
    exporting.value = null
  }
}

function openRegen(): void {
  regenNote.value = ''
  regenError.value = null
  regenOpen.value = true
}

async function submitRegen(): Promise<void> {
  if (!report.value || regenerating.value) return
  regenerating.value = true
  regenError.value = null
  try {
    const detail = await reportApi.generate({
      scope: report.value.scope as 'PLAN' | 'RELEASE',
      planId: report.value.scope === 'PLAN' ? report.value.planId : undefined,
      releaseId: report.value.scope === 'RELEASE' ? report.value.releaseId : undefined,
      note: regenNote.value.trim() || undefined,
    })
    ElMessage.success(`已生成 v${detail.currentVersion}`)
    regenOpen.value = false
    if (detail.report.id === reportId.value) {
      await load()
    } else {
      await router.push({ name: 'test-report-detail', params: { id: detail.report.id } })
    }
  } catch (err) {
    regenError.value = { message: messageOf(err), code: err instanceof ApiError ? err.code : undefined }
  } finally {
    regenerating.value = false
  }
}

function formatDelta(before?: number | null, after?: number | null, percent = false): string {
  if (before == null || after == null) return '—'
  const diffValue = after - before
  const sign = diffValue > 0 ? '+' : ''
  const body = percent ? `${sign}${(diffValue * 100).toFixed(1)}pt` : `${sign}${diffValue}`
  return body
}

watch(reportId, () => void load())
onMounted(() => void load())
</script>

<template>
  <div class="dl-page report-detail">
    <ErrorState
      v-if="error && !data"
      :message="error.message"
      :code="error.code"
      :status="error.status"
      @retry="load"
    />

    <template v-else-if="report">
      <header class="dl-head report-head">
        <div class="dl-head__title report-head__title">
          <button type="button" class="dl-head__back" aria-label="返回报告中心" @click="router.push({ name: 'test-reports' })">←</button>
          <div>
            <div class="report-head__line">
              <span class="t-code">{{ report.code }}</span>
              <span class="report-head__version">v{{ selectedVersion }}</span>
              <StatusPill kind="report" :value="versionStatus" />
              <span v-if="!isCurrent" class="report-head__history">历史版本</span>
            </div>
            <p class="report-head__scope">
              {{ selectedScopeLabel }}
              <span class="report-head__dot">·</span>
              {{ report.scope === 'PLAN' ? '计划范围' : '版本范围' }}
              <span class="report-head__dot">·</span>
              数据截止 {{ report.dataCutoff ? formatDateTime(report.dataCutoff) : '—' }}
            </p>
          </div>
        </div>
        <div class="dl-head__actions">
          <el-select
            :model-value="selectedVersion"
            style="width: 132px"
            aria-label="切换报告版本"
            @update:model-value="selectVersion(Number($event))"
          >
            <el-option
              v-for="version in versions"
              :key="version.versionNo"
              :value="version.versionNo"
              :label="`v${version.versionNo} · ${version.status}`"
            />
          </el-select>
          <el-button
            :loading="exporting === 'xlsx'"
            :disabled="!!exporting"
            @click="exportFile('xlsx')"
          >
            导出 XLSX
          </el-button>
          <el-button
            :loading="exporting === 'pdf'"
            :disabled="!!exporting"
            @click="exportFile('pdf')"
          >
            导出 PDF
          </el-button>
          <el-button type="primary" @click="openRegen">重新生成</el-button>
        </div>
      </header>

      <div v-if="readonly" class="readonly-banner report-readonly" role="status">
        <span class="report-readonly__dot" aria-hidden="true" />
        <span v-if="versionStatus === 'REJECTED'">
          只读 · v{{ selectedVersion }} 已被会签驳回，请生成新版本重新走会签
        </span>
        <span v-else-if="!isCurrent">
          只读 · v{{ selectedVersion }} 已被 v{{ report.currentVersion }} 取代
        </span>
        <span v-else>只读 · 报告已归档，不能修改</span>
      </div>

      <div v-else-if="signoffWarning" class="report-warning" role="status">
        <span class="report-warning__icon" aria-hidden="true">!</span>
        <span>{{ signoffWarning }}</span>
      </div>

      <ErrorState
        v-if="actionError"
        :message="actionError.message"
        :code="actionError.code"
        compact
        class="report-action-error"
      />

      <ConclusionBar
        :title="effectiveConclusion ? (CONCLUSION_LABEL[effectiveConclusion] ?? effectiveConclusion) : '尚未得出结论'"
        :tone="effectiveConclusion === 'PASS' ? 'success' : effectiveConclusion === 'FAIL' ? 'danger' : effectiveConclusion === 'CONDITIONAL' ? 'warning' : 'neutral'"
        :kicker="`v${selectedVersion} 结论`"
        :hint="`通过率 ${formatPercent(versionDetail?.passRate ?? report.passRate)} · 覆盖率 ${formatPercent(versionDetail?.coverage ?? report.coverage)} · 豁免 ${waiverCount} 条`"
      >
        <DigestChip
          v-if="versionDetail?.resultDigest || versionDetail?.hash"
          :hash="versionDetail?.resultDigest || versionDetail?.hash"
          label="结果 digest"
        />
      </ConclusionBar>

      <div class="report-meta">
        <div class="report-meta__item">
          <span class="report-meta__label">生成人</span>
          <span>{{ selectedVersionRow?.generatedByName || report.generatedByName || '—' }}</span>
        </div>
        <div class="report-meta__item">
          <span class="report-meta__label">生成时间</span>
          <span>{{ formatDateTime(selectedVersionRow?.generatedAt) }}</span>
        </div>
        <div class="report-meta__item">
          <span class="report-meta__label">有效期至</span>
          <span>{{ expiryText }}</span>
          <span v-if="daysLeft != null" class="report-meta__sub" :class="{ 'is-danger': daysLeft <= 0 }">
            {{ daysLeft <= 0 ? '已过期' : `剩余 ${daysLeft} 天` }}
          </span>
        </div>
        <div class="report-meta__item">
          <span class="report-meta__label">会签</span>
          <span>{{ selectedVersionRow?.signedCount ?? 0 }}/{{ selectedVersionRow?.totalSignoffs ?? 3 }}</span>
        </div>
        <div class="report-meta__item report-meta__item--feed">
          <span class="report-meta__label">数据口径</span>
          <span>计划用例总数为分母（含未执行），与门禁/看板同源</span>
        </div>
      </div>

      <section class="surface surface--pad report-signoff-block">
        <div class="report-block__head">
          <h2 class="t-block-title">三级会签</h2>
          <span class="t-aux">严格顺序：测试负责人 → 质量负责人 → PMO</span>
        </div>
        <SignoffProgress
          v-if="signoff"
          :progress="signoff"
          :readonly="readonly"
          @sign="openSign"
        />
        <p v-else class="report-signoff-empty">
          历史版本的会签记录随版本固化，可在导出文件中查看；此处只展示当前版本进度。
        </p>
      </section>

      <button
        v-if="diff && selectedVersion > 1"
        type="button"
        class="report-diff-toggle"
        :aria-expanded="diffOpen"
        @click="diffOpen = !diffOpen"
      >
        <span>与 v{{ selectedVersion - 1 }} 对比</span>
        <span class="report-diff-toggle__summary">
          通过率 {{ formatDelta(diff.passRate?.before, diff.passRate?.after, true) }}
          · 结论 {{ diff.conclusion?.before ?? '—' }} → {{ diff.conclusion?.after ?? '—' }}
        </span>
        <span class="report-diff-toggle__chevron" :class="{ 'is-open': diffOpen }" aria-hidden="true">⌄</span>
      </button>
      <div v-if="diff && diffOpen" class="report-diff">
        <div class="report-diff__grid">
          <div><span class="report-diff__label">通过率</span>{{ formatPercent(diff.passRate?.before) }} → {{ formatPercent(diff.passRate?.after) }}</div>
          <div><span class="report-diff__label">覆盖率</span>{{ formatPercent(diff.coverage?.before) }} → {{ formatPercent(diff.coverage?.after) }}</div>
          <div><span class="report-diff__label">新增失败</span>{{ diff.addedFails?.length ?? 0 }} 条</div>
          <div><span class="report-diff__label">关闭缺陷</span>{{ diff.closedDefects?.length ?? 0 }} 条</div>
        </div>
        <ul v-if="diff.addedFails?.length" class="report-diff__list">
          <li v-for="item in diff.addedFails.slice(0, 10)" :key="`${item.caseCode}-${item.title}`">
            <span class="t-code">{{ item.caseCode || '—' }}</span>
            <span>{{ item.title || '新增失败用例' }}</span>
          </li>
        </ul>
      </div>

      <ReportReader
        :snapshot="snapshot"
        :report-id="reportId"
        :version-no="selectedVersion"
        :uncovered-requirements="isCurrent ? data?.uncoveredRequirements : undefined"
        :loading="versionLoading"
      />

      <section v-if="data?.excludedPlans?.length" class="surface surface--pad report-excluded">
        <h2 class="t-block-title">未纳入计划</h2>
        <p class="t-aux">以下计划未批准，未计入本报告的通过率与覆盖率；QG-01 会因此判定失败。</p>
        <ul class="report-excluded__list">
          <li v-for="plan in data.excludedPlans" :key="plan.planId">
            <span class="t-code">{{ plan.code || `#${plan.planId}` }}</span>
            <span>{{ plan.name }}</span>
            <StatusPill kind="plan" :value="plan.status" size="sm" />
          </li>
        </ul>
      </section>
    </template>

    <el-dialog v-model="signOpen" title="签署报告" width="520px" :close-on-click-modal="false">
      <div v-if="signError" class="report-dialog-error" role="alert">
        <span v-if="signError.code" class="t-code">{{ signError.code }}</span>
        <span>{{ signError.message }}</span>
      </div>
      <div class="report-sign-role">
        <span class="t-aux">当前节点</span>
        <strong>{{ signStep?.roleName || signStep?.signRole || '—' }}</strong>
      </div>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="签署决定">
          <el-radio-group v-model="signDecision">
            <el-radio-button value="APPROVE">同意 · 通过</el-radio-button>
            <el-radio-button value="REJECT">驳回版本</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="signDecision === 'APPROVE'" label="版本结论" required>
          <el-radio-group v-model="signConclusion">
            <el-radio v-for="option in conclusionOptions" :key="option.value" :value="option.value" :disabled="option.disabled">
              {{ option.label }}
              <span v-if="option.hint" class="report-option-hint">{{ option.hint }}</span>
            </el-radio>
          </el-radio-group>
          <p class="report-form-hint">
            系统建议：{{ CONCLUSION_LABEL[suggestedConclusion] || suggestedConclusion || '未给出' }}；结论只能等于或低于建议。
          </p>
        </el-form-item>
        <el-form-item :label="signDecision === 'REJECT' ? '驳回原因（必填）' : '意见'">
          <el-input
            v-model="signComment"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            :placeholder="signDecision === 'REJECT' ? '说明驳回原因，版本将被置为已驳回，需重新生成' : '可选：签署意见；结论下调时必须说明原因'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="signing" @click="signOpen = false">取消</el-button>
        <el-button
          :type="signDecision === 'REJECT' ? 'danger' : 'primary'"
          :loading="signing"
          :disabled="!canSubmitSign"
          @click="submitSign"
        >
          {{ signDecision === 'REJECT' ? '确认驳回' : '确认签署' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="regenOpen" title="重新生成报告" width="480px" :close-on-click-modal="false">
      <p class="report-form-hint report-regen-hint">
        将基于当前执行与缺陷数据生成新版本 v{{ (report?.currentVersion ?? 0) + 1 }}，当前版本自动置为「已被取代」，历史版本仍可查看与导出。
      </p>
      <div v-if="regenError" class="report-dialog-error" role="alert">
        <span v-if="regenError.code" class="t-code">{{ regenError.code }}</span>
        <span>{{ regenError.message }}</span>
      </div>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="生成说明">
          <el-input v-model="regenNote" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="regenerating" @click="regenOpen = false">取消</el-button>
        <el-button type="primary" :loading="regenerating" @click="submitRegen">生成新版本</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.report-head__title {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.report-head__line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.report-head__version {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-title);
}
.report-head__history {
  font-size: 11px;
  color: var(--text-muted-aa);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  padding: 1px 6px;
}
.report-head__scope {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.report-head__dot {
  margin: 0 4px;
  color: var(--text-weak);
}
.report-readonly {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0;
}
.report-readonly__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-weak);
}
.report-warning {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0;
  padding: 8px 12px;
  border-left: 3px solid var(--color-warning);
  background: var(--color-warning-bg);
  border-radius: 0 var(--r-ctl) var(--r-ctl) 0;
  font-size: 12px;
  color: var(--warning-ink);
}
.report-warning__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--color-warning);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
}
.report-action-error {
  margin: 12px 0;
}
.report-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 28px;
  margin: 12px 0;
  padding: 10px 14px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  font-size: 12px;
  color: var(--text-body);
}
.report-meta__item {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.report-meta__item--feed {
  flex: 1;
  min-width: 220px;
  color: var(--text-muted-aa);
}
.report-meta__label {
  color: var(--text-muted-aa);
}
.report-meta__sub {
  color: var(--text-weak);
}
.report-meta__sub.is-danger {
  color: var(--danger-ink);
}
.report-block__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
}
.report-signoff-block {
  margin: 12px 0;
}
.report-signoff-empty {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.report-diff-toggle {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  margin: 4px 0 12px;
  padding: 8px 14px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  font-size: 12px;
  color: var(--text-body);
  cursor: pointer;
  text-align: left;
  transition: background var(--t-color) var(--ease), border-color var(--t-color) var(--ease);
}
.report-diff-toggle:hover {
  background: var(--bg-hover);
  border-color: var(--border-strong);
}
.report-diff-toggle:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.report-diff-toggle__summary {
  color: var(--text-muted-aa);
}
.report-diff-toggle__chevron {
  margin-left: auto;
  color: var(--text-weak);
  transition: transform var(--t-expand) var(--ease);
}
.report-diff-toggle__chevron.is-open {
  transform: rotate(180deg);
}
.report-diff {
  margin: -4px 0 12px;
  padding: 12px 14px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-top: none;
  border-radius: 0 0 var(--r-card) var(--r-card);
  font-size: 12px;
}
.report-diff__grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 32px;
}
.report-diff__label {
  margin-right: 6px;
  color: var(--text-muted-aa);
}
.report-diff__list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 4px;
}
.report-diff__list li {
  display: flex;
  gap: 8px;
}
.report-excluded {
  margin-top: 16px;
}
.report-excluded__list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 6px;
  font-size: 12px;
}
.report-excluded__list li {
  display: flex;
  align-items: center;
  gap: 8px;
}
.report-dialog-error {
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
.report-sign-role {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 13px;
}
.report-option-hint {
  margin-left: 4px;
  font-size: 11px;
  color: var(--text-weak);
}
.report-form-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.report-regen-hint {
  margin-bottom: 12px;
  line-height: 1.6;
}
</style>
