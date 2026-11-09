<script setup lang="ts">
/**
 * 发布评审 Go/No-Go（DESIGN Q10 / AC-10）。
 *
 * 评审记录属于 req 的发布决策域：页面只读取质量门/报告/版本质量摘要，
 * 决策与二次确认走 /releases/{id}/reviews。门禁未评估时只允许“预评审”，
 * GO 被禁用（不能凭口头判断放行）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { gateApi, releaseQualityApi, reportApi } from '@/api/quality'
import { confirmReview, createReview, getRelease, listReviews } from '@/api/req'
import { ApiError } from '@/api/client'
import type {
  GateDetail,
  ReleaseDetail,
  ReleaseQuality,
  ReportRow,
  ReviewDetail,
  ReviewRow,
} from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import PriorityDot from '@/components/PriorityDot.vue'
import ErrorState from '@/components/ErrorState.vue'
import ConclusionBar from '@/components/quality/ConclusionBar.vue'
import QualityGatePanel from '@/components/quality/QualityGatePanel.vue'
import DigestChip from '@/components/quality/DigestChip.vue'
import { formatDate, formatDateTime, formatPercent } from '@/utils/format'
import '@/views/deliver/shared.css'

const route = useRoute()
const router = useRouter()
const releaseId = computed(() => Number(route.params.id))

const releaseDetail = ref<ReleaseDetail | null>(null)
const gate = ref<GateDetail | null>(null)
const quality = ref<ReleaseQuality | null>(null)
const reviews = ref<ReviewRow[]>([])
const latestReview = ref<ReviewDetail | null>(null)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const actionError = ref<{ message: string; code?: string } | null>(null)

const decision = ref<'GO' | 'CONDITIONAL' | 'NO_GO' | ''>('')
const conditions = ref('')
const meetingAt = ref(new Date().toISOString().slice(0, 16))
const creating = ref(false)
const createdReview = ref<ReviewDetail | null>(null)
const confirmOpen = ref(false)
const confirmNote = ref('')
const confirming = ref(false)

const release = computed(() => releaseDetail.value?.release ?? null)
const risks = computed(() => releaseDetail.value?.riskItems ?? [])
const gateStatus = computed(() => String(gate.value?.status ?? 'NOT_EVALUATED'))
const gateEvaluated = computed(
  () => gateStatus.value === 'PASS' || gateStatus.value === 'PASS_WITH_WAIVER',
)
const latestSnapshot = computed(() => {
  const list = gate.value?.snapshots ?? []
  return list.length ? list[list.length - 1] : null
})
const signedReports = computed(() =>
  (quality.value?.reports ?? []).filter((row) => String(row.status).toUpperCase() === 'SIGNED'),
)
const latestReport = computed<ReportRow | null>(() => {
  const list = signedReports.value
  if (!list.length) return null
  return [...list].sort((a, b) => (b.currentVersion ?? 0) - (a.currentVersion ?? 0))[0]
})
const openP0P1 = computed(() => quality.value?.openP0P1 ?? [])
const activeReview = computed<ReviewRow | null>(() => {
  if (latestReview.value) return latestReview.value
  return reviews.value.find((row) => row.status === 'RECORDED' || row.status === 'CONFIRMED') ?? reviews.value[0] ?? null
})
const latestReportExpiry = computed(() => {
  const raw = latestReport.value as (ReportRow & { expiresAt?: string | null }) | null
  return raw?.expiresAt ?? '—'
})
const readonly = computed(() => !!activeReview.value)
const reviewDecision = computed(() => String(activeReview.value?.decision ?? ''))
const reviewConfirmed = computed(() => String(activeReview.value?.status ?? '') === 'CONFIRMED')
const blockReason = computed(() => {
  if (!gateEvaluated.value) return '质量门未评估或未通过，只能做预评审，不能生成 GO 记录'
  if (!latestReport.value) return '没有已会签的测试报告，不能生成 GO 记录'
  return ''
})
const conclusion = computed(() => {
  if (!gateEvaluated.value) return '门禁未通过 · 预评审'
  const report = latestReport.value
  return report?.conclusion === 'PASS' ? '质量门通过 · 可评审 GO' : '质量门通过 · 报告结论待确认'
})
const conclusionTone = computed(() => {
  if (!gateEvaluated.value) return 'warning'
  return latestReport.value?.conclusion === 'PASS' ? 'success' : 'warning'
})
const confirmHint = computed(() => {
  if (!gateEvaluated.value) return '当前为预评审：质量门尚未评估，GO 不可选'
  const parts = [`门禁 ${gateStatus.value === 'PASS_WITH_WAIVER' ? '带豁免通过' : '通过'}`]
  parts.push(`报告 ${latestReport.value ? `v${latestReport.value.currentVersion}` : '缺失'}`)
  parts.push(`未关闭 P0/P1 ${openP0P1.value.length}`)
  return parts.join(' · ')
})
const canSubmit = computed(() => {
  if (!decision.value || creating.value || readonly.value) return false
  if (decision.value === 'GO' && blockReason.value) return false
  if ((decision.value === 'NO_GO' || decision.value === 'CONDITIONAL') && conditions.value.trim().length < 4) return false
  return true
})

function messageOf(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.code) {
      case 'NO_GO_CONFIRM_REQUIRED':
        return 'NO-GO 记录需要 PMO 二次确认后才能生效。'
      case 'QUALITY_GATE_FAILED':
        return '质量门未通过，不能生成 GO 评审记录。'
      case 'SCOPE_DENIED':
        return '只有 PMO 或管理员可以确认 NO-GO 记录。'
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
    const [detail, gateDetail, qualityData, reviewPage] = await Promise.allSettled([
      getRelease(releaseId.value),
      gateApi.byRelease(releaseId.value),
      releaseQualityApi.quality(releaseId.value),
      listReviews(releaseId.value, { page: 1, size: 20 }),
    ])
    if (detail.status === 'fulfilled') releaseDetail.value = detail.value
    else throw detail.reason
    if (gateDetail.status === 'fulfilled') gate.value = gateDetail.value
    if (qualityData.status === 'fulfilled') quality.value = qualityData.value
    if (reviewPage.status === 'fulfilled') reviews.value = reviewPage.value.items ?? []
    const recorded = reviews.value.find((row) => row.status === 'RECORDED' || row.status === 'CONFIRMED')
    if (recorded) latestReview.value = { ...recorded } as ReviewDetail
    if (!latestReport.value) {
      try {
        const page = await reportApi.page({ releaseId: releaseId.value, status: 'SIGNED', page: 1, size: 20 })
        if (page.items?.length) {
          quality.value = {
            releaseId: releaseId.value,
            gateHistory: quality.value?.gateHistory ?? [],
            reports: page.items,
            openP0P1: quality.value?.openP0P1 ?? [],
            escapeDefects: quality.value?.escapeDefects ?? [],
            counts: quality.value?.counts ?? {},
          }
        }
      } catch {
        /* 报告缺失由 blockReason 提示 */
      }
    }
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

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  creating.value = true
  actionError.value = null
  try {
    const detail = await createReview(releaseId.value, {
      meetingAt: new Date(meetingAt.value).toISOString(),
      decision: decision.value as 'GO' | 'CONDITIONAL' | 'NO_GO',
      conditions: conditions.value.trim() || undefined,
      deployOrderId: latestSnapshot.value?.deployOrderId ?? undefined,
      reportId: latestReport.value?.id ?? undefined,
      reportVersionNo: latestReport.value?.currentVersion ?? undefined,
      openDefects: openP0P1.value.map((item) => ({
        id: item.defectId ?? item.id ?? 0,
        code: item.code,
        severity: item.severity,
        title: item.title,
      })),
      risks: risks.value.slice(0, 20).map((item) => ({
        key: `REQ-${item.id}`,
        level: String(item.priority ?? 'MEDIUM'),
        note: item.title,
      })),
    })
    createdReview.value = detail
    ElMessage.success('评审记录已生成')
    if (detail.status === 'RECORDED' && detail.decision === 'NO_GO') {
      confirmOpen.value = true
    } else {
      await load()
    }
  } catch (err) {
    actionError.value = { message: messageOf(err), code: err instanceof ApiError ? err.code : undefined }
  } finally {
    creating.value = false
  }
}

function openConfirm(): void {
  confirmNote.value = ''
  confirmOpen.value = true
}

async function submitConfirm(): Promise<void> {
  const record = createdReview.value ?? activeReview.value
  if (!record || confirming.value) return
  confirming.value = true
  actionError.value = null
  try {
    await confirmReview(releaseId.value, record.id, confirmNote.value.trim() || undefined)
    ElMessage.success('评审记录已确认')
    confirmOpen.value = false
    createdReview.value = null
    await load()
  } catch (err) {
    actionError.value = { message: messageOf(err), code: err instanceof ApiError ? err.code : undefined }
  } finally {
    confirming.value = false
  }
}

function printPage(): void {
  window.print()
}

function back(): void {
  void router.push({ name: 'release-detail', params: { id: releaseId.value } })
}

onMounted(() => void load())
</script>

<template>
  <div class="dl-page gng-page">
    <ErrorState v-if="error" :message="error.message" :code="error.code" :status="error.status" @retry="load" />

    <template v-else>
      <header class="dl-head gng-head">
        <div class="dl-head__title gng-head__title">
          <button type="button" class="dl-head__back" aria-label="返回版本详情" @click="back">←</button>
          <div>
            <div class="gng-head__line">
              <h1>{{ release?.name || `release #${releaseId}` }}</h1>
              <StatusPill v-if="release" kind="release" :value="release.status" />
            </div>
            <p class="gng-head__sub">
              {{ release?.code }} · 发布评审 Go/No-Go
              <span v-if="release?.plannedReleaseDate">· 计划发布 {{ formatDate(release.plannedReleaseDate) }}</span>
            </p>
          </div>
        </div>
        <div class="dl-head__actions gng-actions">
          <el-button @click="printPage">打印 / 导出 PDF</el-button>
          <el-button v-if="!readonly" type="primary" :loading="creating" :disabled="!canSubmit" @click="submit">
            生成评审记录
          </el-button>
        </div>
      </header>

      <div v-if="readonly" class="gng-record-bar" role="status">
        <StatusPill kind="review" :value="reviewDecision" size="sm" />
        <span class="gng-record-bar__text">
          已生成评审记录 #{{ activeReview?.id }} · {{ formatDateTime(activeReview?.createdAt) }}
          <span v-if="reviewConfirmed" class="gng-record-bar__ok">已确认</span>
          <span v-else class="gng-record-bar__pending">待确认</span>
        </span>
        <el-button
          v-if="!reviewConfirmed && reviewDecision === 'NO_GO'"
          size="small"
          type="primary"
          plain
          @click="openConfirm"
        >
          二次确认
        </el-button>
        <span class="gng-record-bar__hint">记录生成后页面只读；如需变更请新建版本评审。</span>
      </div>

      <ConclusionBar
        :title="conclusion"
        :tone="conclusionTone"
        :hint="confirmHint"
        kicker="发布决策输入"
      >
        <DigestChip v-if="latestSnapshot?.hash" :hash="latestSnapshot.hash" label="门禁快照" />
      </ConclusionBar>

      <ErrorState
        v-if="actionError"
        :message="actionError.message"
        :code="actionError.code"
        compact
        class="gng-action-error"
      />

      <div class="gng-layout">
        <div class="gng-main">
          <QualityGatePanel
            v-if="gate"
            mode="gate"
            title="质量门（5 条规则）"
            :rules="gate.rules"
            :result="gate.status"
            :evaluated-at="gate.evaluatedAt"
            :snapshot-id="latestSnapshot?.snapshotId"
            :hash="latestSnapshot?.hash"
            readonly
          />
          <section v-else class="surface surface--pad gng-empty-gate">
            <h2 class="t-block-title">质量门</h2>
            <p class="t-aux">尚未配置质量门。质量门未评估时只能做预评审。</p>
          </section>

          <section class="surface surface--pad gng-block">
            <h2 class="t-block-title">测试报告摘要</h2>
            <div v-if="latestReport" class="gng-report">
              <div class="gng-report__head">
                <span class="t-code">{{ latestReport.code }}</span>
                <StatusPill kind="report" :value="latestReport.status" size="sm" />
                <span class="t-aux">v{{ latestReport.currentVersion }}</span>
              </div>
              <div class="gng-report__grid">
                <div><span class="gng-report__label">结论</span>{{ latestReport.conclusion || '—' }}</div>
                <div><span class="gng-report__label">通过率</span>{{ formatPercent(latestReport.passRate) }}</div>
                <div><span class="gng-report__label">覆盖率</span>{{ formatPercent(latestReport.coverage) }}</div>
                <div><span class="gng-report__label">有效期至</span>{{ latestReportExpiry }}</div>
              </div>
              <el-button link type="primary" @click="router.push({ name: 'test-report-detail', params: { id: latestReport.id } })">
                查看完整报告
              </el-button>
            </div>
            <p v-else class="t-aux">没有已会签的测试报告；GO 记录不可生成。</p>
          </section>

          <section class="surface surface--pad gng-block">
            <div class="gng-block__head">
              <h2 class="t-block-title">未关闭 P0/P1</h2>
              <span class="gng-count" :class="{ 'is-danger': openP0P1.length > 0 }">{{ openP0P1.length }}</span>
            </div>
            <ul v-if="openP0P1.length" class="gng-defects">
              <li v-for="(item, index) in openP0P1" :key="item.defectId ?? item.id ?? index">
                <span class="t-code">{{ item.code || `#${item.defectId ?? item.id}` }}</span>
                <span class="gng-defects__title">{{ item.title || '未关闭缺陷' }}</span>
                <span class="gng-sev">{{ item.severity || item.status || '—' }}</span>
              </li>
            </ul>
            <p v-else class="t-aux">没有未关闭的 P0/P1；QG-04 满足。</p>
          </section>

          <section class="surface surface--pad gng-block">
            <div class="gng-block__head">
              <h2 class="t-block-title">策略风险清单</h2>
              <span class="gng-count">{{ risks.length }}</span>
            </div>
            <ul v-if="risks.length" class="gng-risks">
              <li v-for="item in risks.slice(0, 20)" :key="item.id">
                <span class="t-code">{{ item.code }}</span>
                <span class="gng-risks__title">{{ item.title }}</span>
                <PriorityDot :value="item.priority" />
              </li>
            </ul>
            <p v-else class="t-aux">版本没有标记为风险的未完成需求。</p>
          </section>
        </div>

        <aside class="gng-side">
          <div class="gng-stat">
            <span class="gng-stat__label">测试报告</span>
            <span class="gng-stat__value">{{ latestReport ? `v${latestReport.currentVersion}` : '—' }}</span>
            <span class="gng-stat__sub">{{ latestReport ? formatPercent(latestReport.passRate, 1) + ' 通过率' : '无已会签报告' }}</span>
          </div>
          <div class="gng-stat">
            <span class="gng-stat__label">未关闭 P0/P1</span>
            <span class="gng-stat__value" :class="{ 'is-danger': openP0P1.length > 0 }">{{ openP0P1.length }}</span>
            <span class="gng-stat__sub">QG-04 要求 = 0</span>
          </div>
          <div class="gng-stat">
            <span class="gng-stat__label">风险需求</span>
            <span class="gng-stat__value">{{ risks.length }}</span>
            <span class="gng-stat__sub">来自版本风险标记</span>
          </div>
          <div class="gng-stat">
            <span class="gng-stat__label">观察窗</span>
            <span class="gng-stat__value">72h</span>
            <span class="gng-stat__sub">发布成功后 72 小时生产缺陷回溯窗口</span>
          </div>
        </aside>
      </div>

      <section v-if="!readonly" class="gng-decide" :class="{ 'is-preview': !gateEvaluated }">
        <div class="gng-decide__choices" role="radiogroup" aria-label="发布决策">
          <label class="gng-choice" :class="{ 'is-active': decision === 'GO', 'is-disabled': !!blockReason }">
            <input
              v-model="decision"
              type="radio"
              value="GO"
              :disabled="!!blockReason || creating"
            />
            <span class="gng-choice__name">GO</span>
            <span class="gng-choice__hint">按计划发布</span>
          </label>
          <label class="gng-choice" :class="{ 'is-active': decision === 'CONDITIONAL' }">
            <input v-model="decision" type="radio" value="CONDITIONAL" :disabled="creating" />
            <span class="gng-choice__name">CONDITIONAL</span>
            <span class="gng-choice__hint">带条件发布</span>
          </label>
          <label class="gng-choice" :class="{ 'is-active': decision === 'NO_GO' }">
            <input v-model="decision" type="radio" value="NO_GO" :disabled="creating" />
            <span class="gng-choice__name">NO-GO</span>
            <span class="gng-choice__hint">暂不发布</span>
          </label>
        </div>
        <div class="gng-decide__body">
          <el-input
            v-model="conditions"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            :placeholder="decision === 'NO_GO' || decision === 'CONDITIONAL' ? '附加条件 / 风险说明（必填，至少 4 个字）' : '附加说明（可选）'"
          />
          <div class="gng-decide__foot">
            <span v-if="blockReason" class="gng-decide__reason">{{ blockReason }}</span>
            <span v-else-if="!decision" class="gng-decide__reason">请选择决策</span>
            <span v-else class="gng-decide__meta">
              会议时间
              <el-date-picker
                v-model="meetingAt"
                type="datetime"
                value-format="YYYY-MM-DDTHH:mm"
                size="small"
                style="width: 190px"
              />
            </span>
            <el-button type="primary" :loading="creating" :disabled="!canSubmit" @click="submit">
              生成评审记录
            </el-button>
          </div>
        </div>
      </section>
      <div v-else class="gng-decide gng-decide--readonly">
        <span>评审记录已生成，决策：{{ reviewDecision }}</span>
        <span class="t-aux">记录内容与质量门/报告快照绑定，页面只读。</span>
      </div>
    </template>

    <el-dialog v-model="confirmOpen" title="NO-GO 二次确认" width="520px" :close-on-click-modal="false">
      <p class="gng-confirm-hint">
        NO-GO 记录需要 PMO 二次确认后才生效。确认后该版本的发布决策固定为 NO-GO，需重新走评审才能变更。
      </p>
      <el-input v-model="confirmNote" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="确认意见（可选）" />
      <template #footer>
        <el-button :disabled="confirming" @click="confirmOpen = false">稍后确认</el-button>
        <el-button type="danger" :loading="confirming" @click="submitConfirm">确认 NO-GO</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.gng-head__title {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.gng-head__line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.gng-head__line h1 {
  margin: 0;
}
.gng-head__sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gng-record-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 12px 0;
  padding: 8px 12px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-left: 3px solid var(--color-primary);
  border-radius: 0 var(--r-card) var(--r-card) 0;
  font-size: 12px;
}
.gng-record-bar__text {
  color: var(--text-body);
}
.gng-record-bar__ok {
  margin-left: 6px;
  color: var(--success-ink);
}
.gng-record-bar__pending {
  margin-left: 6px;
  color: var(--warning-ink);
}
.gng-record-bar__hint {
  margin-left: auto;
  color: var(--text-weak);
}
.gng-action-error {
  margin: 12px 0;
}
.gng-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 250px;
  gap: 16px;
  margin: 12px 0;
  align-items: start;
}
.gng-main {
  display: grid;
  gap: 12px;
}
.gng-block__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.gng-count {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--text-muted-aa);
}
.gng-count.is-danger {
  color: var(--danger-ink);
  font-weight: 600;
}
.gng-report__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.gng-report__grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  margin-bottom: 6px;
  font-size: 12px;
}
.gng-report__label {
  margin-right: 6px;
  color: var(--text-muted-aa);
}
.gng-defects,
.gng-risks {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 6px;
  font-size: 12px;
}
.gng-defects li,
.gng-risks li {
  display: flex;
  align-items: center;
  gap: 8px;
}
.gng-defects__title,
.gng-risks__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gng-sev {
  padding: 1px 6px;
  border-radius: var(--r-pill);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 11px;
}
.gng-side {
  display: grid;
  gap: 10px;
}
.gng-stat {
  display: grid;
  gap: 2px;
  padding: 12px 14px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-card);
}
.gng-stat__label {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gng-stat__value {
  font-size: 22px;
  font-weight: 600;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.gng-stat__value.is-danger {
  color: var(--danger-ink);
}
.gng-stat__sub {
  font-size: 11px;
  color: var(--text-weak);
  line-height: 1.5;
}
.gng-decide {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  gap: 16px;
  padding: 12px 16px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.04);
}
.gng-decide.is-preview {
  background: var(--color-warning-bg);
  border-color: #f0d9a8;
}
.gng-decide--readonly {
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-body);
}
.gng-decide__choices {
  display: flex;
  gap: 8px;
}
.gng-choice {
  display: grid;
  gap: 2px;
  min-width: 118px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-ctl);
  cursor: pointer;
  background: var(--bg-card);
  transition: border-color var(--t-color) var(--ease), background var(--t-color) var(--ease);
}
.gng-choice input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}
.gng-choice:hover {
  border-color: var(--border-strong);
}
.gng-choice.is-active {
  border-color: var(--color-primary);
  background: var(--bg-selected);
}
.gng-choice.is-disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
.gng-choice:focus-within {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.gng-choice__name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-title);
}
.gng-choice__hint {
  font-size: 11px;
  color: var(--text-muted-aa);
}
.gng-decide__body {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 6px;
}
.gng-decide__foot {
  display: flex;
  align-items: center;
  gap: 12px;
}
.gng-decide__reason {
  font-size: 12px;
  color: var(--warning-ink);
}
.gng-decide__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.gng-confirm-hint {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-body);
}
@media (max-width: 1080px) {
  .gng-layout {
    grid-template-columns: 1fr;
  }
  .gng-side {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .gng-decide {
    flex-direction: column;
  }
}
@media print {
  .gng-actions,
  .gng-decide,
  .gng-record-bar,
  .dl-head__back {
    display: none !important;
  }
  .gng-layout {
    grid-template-columns: 1fr;
  }
}
</style>
