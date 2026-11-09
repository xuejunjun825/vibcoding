<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  addMilestone,
  addRequirementsToRelease,
  getRelease,
  listRequirements,
  listReviews,
  removeRequirementFromRelease,
  requestReleaseApproval,
} from '@/api/req'
import { gateApi, releaseQualityApi } from '@/api/quality'
import {
  getReleaseIntegration,
  putReleaseIntegration,
  type ReleaseIntegrationSettings,
} from '@/api/integration'
import type {
  GateDetail,
  GateSnapshot,
  ReleaseDetail,
  ReleaseQuality,
  ReleaseRow,
  RequirementRow,
  ReviewRow,
} from '@/api/types'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PriorityDot from '@/components/PriorityDot.vue'
import StatusPill from '@/components/StatusPill.vue'
import ConclusionBar from '@/components/quality/ConclusionBar.vue'
import DigestChip from '@/components/quality/DigestChip.vue'
import QualityGatePanel from '@/components/quality/QualityGatePanel.vue'
import { usePermission } from '@/composables/usePermission'
import { formatDate, formatDays, formatDateTime, formatPercent } from '@/utils/format'
import { childInfo, messageOf, requirementPath, typeLabel, usePlatformDict } from '@/views/req/platform'

/**
 * P4 版本工作台：左工作项、右统计/里程碑/风险。
 * 锁定（LOCKED）/已发布（PUBLISHED）后需求范围只读，移出与纳入均禁用并给出原因。
 */
const route = useRoute()
const router = useRouter()
const { can } = usePermission()
const dict = usePlatformDict()

const detail = ref<ReleaseDetail | null>(null)
const loading = ref(true)
const error = ref('')
const actionError = ref('')
const submitting = ref(false)
const releaseId = computed(() => Number(route.params.id))

const release = computed<ReleaseRow | null>(() => detail.value?.release ?? null)
const requirements = computed<RequirementRow[]>(() => {
  const raw = detail.value as (ReleaseDetail & { groups?: Array<{ requirements?: RequirementRow[]; items?: RequirementRow[] }> }) | null
  if (!raw) return []
  if (Array.isArray(raw.requirements) && raw.requirements.length > 0) return raw.requirements
  if (Array.isArray(raw.groups)) {
    return raw.groups.flatMap((group) => group.requirements ?? group.items ?? [])
  }
  return raw.requirements ?? []
})
const milestones = computed<Array<{ id: number; name: string; plannedDate?: string; status?: string }>>(() => {
  const raw = detail.value as (ReleaseDetail & { milestones?: Array<{ id: number; name: string; plannedDate?: string; status?: string }> }) | null
  return raw?.milestones ?? []
})

const scopeLocked = computed(() => release.value != null && ['LOCKED', 'PUBLISHED', 'CANCELLED'].includes(release.value.status))
const lockReason = computed(() => {
  if (!scopeLocked.value) return ''
  return release.value?.status === 'LOCKED' ? '版本已锁定，需求范围不可变更' : '版本已发布或取消，需求范围不可变更'
})
const canPlan = computed(() => can('release:plan'))
const canEditScope = computed(() => canPlan.value && !scopeLocked.value)

const stats = computed(() => {
  const fallback = { total: requirements.value.length, done: 0, estimateDays: 0, actualDays: 0 }
  const source = detail.value?.stats ?? fallback
  return {
    total: source.total ?? fallback.total,
    done: source.done ?? fallback.done,
    estimateDays: source.estimateDays ?? 0,
    actualDays: source.actualDays ?? 0,
  }
})
const donePercent = computed(() => (stats.value.total > 0 ? Math.round((stats.value.done / stats.value.total) * 100) : 0))
const riskItems = computed<RequirementRow[]>(() => detail.value?.riskItems ?? [])

/* ---------------- 质量门 / 发布评审 / 版本质量（M6 T11） ---------------- */

type ReleaseTab = 'overview' | 'gate' | 'review' | 'quality'

const activeTab = ref<ReleaseTab>('overview')
const gateDetail = ref<GateDetail | null>(null)
const gateLoading = ref(false)
const gateError = ref('')
const gateLoaded = ref(false)
const reviewRows = ref<ReviewRow[]>([])
const reviewLoading = ref(false)
const reviewLoaded = ref(false)
const quality = ref<ReleaseQuality | null>(null)
const qualityLoading = ref(false)
const qualityError = ref('')
const qualityLoaded = ref(false)
const qualityFocus = ref<'gate' | 'reports' | 'defects' | 'escape' | null>(null)

const latestSnapshot = computed<GateSnapshot | null>(() => {
  const list = gateDetail.value?.snapshots ?? []
  return list.length ? list[list.length - 1] : null
})
const gateRulesSummary = computed(() => {
  const rules = gateDetail.value?.rules ?? []
  if (!rules.length) return '未配置'
  const failed = rules.filter((rule) => rule.status === 'FAIL').length
  return failed ? `${rules.length - failed}/${rules.length} 通过` : `${rules.length}/${rules.length} 通过`
})
const qualityCounts = computed(() => quality.value?.counts ?? {})
const snapshotHistory = computed(() => [...(gateDetail.value?.snapshots ?? [])].reverse().slice(0, 10))
const latestReview = computed(() => reviewRows.value.find((row) => row.status === 'RECORDED' || row.status === 'CONFIRMED') ?? reviewRows.value[0] ?? null)

async function loadGatePanel(): Promise<void> {
  if (gateLoaded.value || gateLoading.value) return
  gateLoading.value = true
  gateError.value = ''
  try {
    gateDetail.value = await gateApi.byRelease(releaseId.value)
    gateLoaded.value = true
  } catch (err) {
    gateError.value = messageOf(err)
  } finally {
    gateLoading.value = false
  }
}

async function loadReviewsPanel(): Promise<void> {
  if (reviewLoaded.value || reviewLoading.value) return
  reviewLoading.value = true
  try {
    const page = await listReviews(releaseId.value, { page: 1, size: 20 })
    reviewRows.value = page.items ?? []
    reviewLoaded.value = true
  } catch (err) {
    actionError.value = messageOf(err)
  } finally {
    reviewLoading.value = false
  }
}

async function loadQualityPanel(): Promise<void> {
  if (qualityLoaded.value || qualityLoading.value) return
  qualityLoading.value = true
  qualityError.value = ''
  try {
    quality.value = await releaseQualityApi.quality(releaseId.value)
    qualityLoaded.value = true
  } catch (err) {
    qualityError.value = messageOf(err)
  } finally {
    qualityLoading.value = false
  }
}

function switchTab(tab: ReleaseTab): void {
  activeTab.value = tab
  qualityFocus.value = null
  if (tab === 'gate') void loadGatePanel()
  if (tab === 'review') void loadReviewsPanel()
  if (tab === 'quality') void loadQualityPanel()
}

function focusQuality(section: 'gate' | 'reports' | 'defects' | 'escape'): void {
  qualityFocus.value = section
  const target = document.getElementById(`release-quality-${section}`)
  target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function rulePassText(snapshot: GateSnapshot): string {
  const failed = snapshot.rules.filter((rule) => rule.status === 'FAIL').length
  return failed ? `${snapshot.rules.length - failed}/${snapshot.rules.length} 通过` : `${snapshot.rules.length}/${snapshot.rules.length} 通过`
}

function reviewDecisionTone(decision?: string): 'success' | 'warning' | 'danger' {
  if (decision === 'GO') return 'success'
  if (decision === 'CONDITIONAL') return 'warning'
  return 'danger'
}

const GATE_LABEL: Record<string, string> = {
  PASS: '通过',
  PASS_WITH_WAIVER: '带豁免通过',
  FAIL: '未通过',
  NOT_EVALUATED: '未评估',
}
function gateLabel(status?: string): string {
  return GATE_LABEL[String(status ?? '')] ?? (status || '未评估')
}
function gateTone(status?: string): 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'PASS') return 'success'
  if (status === 'FAIL') return 'danger'
  if (status === 'PASS_WITH_WAIVER') return 'warning'
  return 'neutral'
}
const REVIEW_LABEL: Record<string, string> = { GO: 'GO', CONDITIONAL: 'CONDITIONAL', NO_GO: 'NO-GO' }
function reviewLabel(decision?: string): string {
  return REVIEW_LABEL[String(decision ?? '')] ?? (decision || '—')
}

function openReport(row: { id: number }): void {
  void router.push({ name: 'test-report-detail', params: { id: row.id } })
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    dict.ensure()
    detail.value = await getRelease(releaseId.value)
    void loadIntegration()
  } catch (err) {
    error.value = messageOf(err)
    detail.value = null
  } finally {
    loading.value = false
  }
}

/* ---------------- 集成设置（DESIGN I7 / AC-4.4、AC-5.5） ---------------- */
const integration = ref<ReleaseIntegrationSettings | null>(null)
const integrationLoading = ref(false)
const integrationSaving = ref(false)
const integrationError = ref('')
const integrationAutoAdvance = ref(false)
const integrationSonarKey = ref('')

async function loadIntegration(): Promise<void> {
  integrationLoading.value = true
  integrationError.value = ''
  try {
    integration.value = await getReleaseIntegration(releaseId.value)
    integrationAutoAdvance.value = integration.value.mrAutoAdvance
    integrationSonarKey.value = integration.value.defaultSonarProjectKey ?? ''
  } catch (err) {
    integrationError.value = messageOf(err)
  } finally {
    integrationLoading.value = false
  }
}

async function saveIntegration(): Promise<void> {
  integrationSaving.value = true
  integrationError.value = ''
  try {
    integration.value = await putReleaseIntegration(releaseId.value, {
      mrAutoAdvance: integrationAutoAdvance.value,
      defaultSonarProjectKey: integrationSonarKey.value.trim() || null,
    })
    ElMessage.success('集成设置已保存')
  } catch (err) {
    integrationError.value = messageOf(err)
  } finally {
    integrationSaving.value = false
  }
}

onMounted(load)
watch(releaseId, () => {
  gateLoaded.value = false
  gateDetail.value = null
  reviewLoaded.value = false
  reviewRows.value = []
  qualityLoaded.value = false
  quality.value = null
  activeTab.value = 'overview'
  void load()
})

const addOpen = ref(false)
const addIds = ref<number[]>([])
const addOptions = ref<RequirementRow[]>([])
const addSearching = ref(false)
const addSaving = ref(false)
const addError = ref('')

const existingIds = computed(() => new Set(requirements.value.map((row) => row.id)))

async function searchCandidates(keyword: string): Promise<void> {
  addSearching.value = true
  try {
    const page = await listRequirements({
      keyword: keyword || undefined,
      releaseId: undefined,
      page: 1,
      size: 20,
      view: 'flat',
    })
    addOptions.value = (page.items ?? []).filter((row) => !existingIds.value.has(row.id))
  } catch (err) {
    addError.value = messageOf(err)
    addOptions.value = []
  } finally {
    addSearching.value = false
  }
}

function openAdd(): void {
  addOpen.value = true
  addIds.value = []
  addOptions.value = []
  addError.value = ''
  void searchCandidates('')
}

async function submitAdd(): Promise<void> {
  if (addIds.value.length === 0 || addSaving.value) return
  addSaving.value = true
  addError.value = ''
  try {
    await addRequirementsToRelease(releaseId.value, addIds.value)
    ElMessage.success(`已纳入 ${addIds.value.length} 条需求`)
    addOpen.value = false
    await load()
  } catch (err) {
    addError.value = messageOf(err)
  } finally {
    addSaving.value = false
  }
}

const removeTarget = ref<RequirementRow | null>(null)
const removing = ref(false)
const removeOpen = ref(false)

function askRemove(row: RequirementRow): void {
  if (!canEditScope.value) return
  removeTarget.value = row
  removeOpen.value = true
}

async function confirmRemove(): Promise<void> {
  const target = removeTarget.value
  if (!target) return
  removing.value = true
  try {
    await removeRequirementFromRelease(releaseId.value, target.id)
    ElMessage.success(`已移出 ${target.code}`)
    removeOpen.value = false
    await load()
  } catch (err) {
    removeOpen.value = false
    actionError.value = messageOf(err)
  } finally {
    removing.value = false
  }
}

const milestoneOpen = ref(false)
const milestoneName = ref('')
const milestoneDate = ref('')
const milestoneSaving = ref(false)
const milestoneError = ref('')

async function submitMilestone(): Promise<void> {
  if (milestoneName.value.trim().length < 2 || !milestoneDate.value) {
    milestoneError.value = '请填写里程碑名称（≥2 字）与计划日期'
    return
  }
  milestoneSaving.value = true
  milestoneError.value = ''
  try {
    await addMilestone(releaseId.value, { name: milestoneName.value.trim(), plannedDate: milestoneDate.value })
    ElMessage.success('里程碑已添加')
    milestoneOpen.value = false
    milestoneName.value = ''
    milestoneDate.value = ''
    await load()
  } catch (err) {
    milestoneError.value = messageOf(err)
  } finally {
    milestoneSaving.value = false
  }
}

const approvalOpen = ref(false)
const approvalSaving = ref(false)

async function submitApproval(): Promise<void> {
  approvalSaving.value = true
  actionError.value = ''
  try {
    const result = await requestReleaseApproval(releaseId.value)
    approvalSaving.value = false
    approvalOpen.value = false
    ElMessage.success(
      result?.instanceId ? `发布审批已发起（实例 #${result.instanceId}）` : '发布审批已发起',
    )
    await load()
  } catch (err) {
    approvalSaving.value = false
    approvalOpen.value = false
    actionError.value = messageOf(err)
  }
}

function openRequirement(row: RequirementRow): void {
  void router.push(requirementPath(row.id))
}

function statusOfMilestone(status?: string): string {
  if (status === 'DONE') return '已完成'
  if (status === 'MISSED') return '已延期'
  return '未到期'
}
</script>

<template>
  <div class="release-detail">
    <ErrorState v-if="error" title="版本加载失败" :message="error" @retry="load" />

    <template v-else>
      <header class="page-head">
        <div class="page-head__left">
          <div class="head-stack">
            <div class="crumbs">
            <button type="button" class="link" @click="router.push({ name: 'releases' })">版本计划</button>
            <span class="crumbs__sep">/</span>
            <span>{{ release ? release.code : '…' }}</span>
          </div>
          <div class="title-row">
            <h1 class="page-head__title">{{ release?.name ?? '版本详情' }}</h1>
            <StatusPill v-if="release" kind="release" :value="release.status" />
            <span v-if="release?.gateMode" class="mode-tag" :class="{ 'is-loose': release.gateMode === 'LOOSE' }">
              {{ release.gateMode === 'LOOSE' ? '宽松门禁' : '严格门禁' }}
            </span>
            <span v-if="scopeLocked" class="lock-tag">🔒 {{ lockReason }}</span>
          </div>
          <p v-if="release" class="meta-line">
            <span>{{ dict.domainName(release.domainId) }}</span>
            <span>{{ formatDate(release.startDate) }} → {{ formatDate(release.endDate) }}</span>
            <span v-if="release.plannedReleaseDate">计划发布 {{ formatDate(release.plannedReleaseDate) }}</span>
          </p>
          </div>
        </div>
        <div class="page-head__actions">
          <el-button
            size="small"
            :disabled="!canEditScope"
            :title="canEditScope ? '' : scopeLocked ? lockReason : '需要权限：release:plan'"
            @click="openAdd"
          >
            纳入需求
          </el-button>
          <el-button
            size="small"
            :disabled="!canPlan"
            :title="canPlan ? '' : '需要权限：release:plan'"
            @click="(milestoneOpen = true), (milestoneError = '')"
          >
            添加里程碑
          </el-button>
          <el-button
            type="primary"
            size="small"
            :loading="submitting"
            :disabled="!canPlan || release?.status !== 'PLAN'"
            :title="
              release?.status !== 'PLAN'
                ? '仅规划中的版本可发起发布审批'
                : canPlan
                  ? ''
                  : '需要权限：release:plan'
            "
            @click="approvalOpen = true"
          >
            提交发布审批
          </el-button>
        </div>
      </header>

      <div v-if="actionError" class="action-banner" role="alert">
        <span>{{ actionError }}</span>
        <button type="button" class="link" @click="actionError = ''">关闭</button>
      </div>

      <nav class="release-tabs" role="tablist" aria-label="版本详情视图">
        <button
          type="button"
          role="tab"
          class="release-tab"
          :class="{ 'is-active': activeTab === 'overview' }"
          :aria-selected="activeTab === 'overview'"
          @click="switchTab('overview')"
        >
          概览
        </button>
        <button
          type="button"
          role="tab"
          class="release-tab"
          :class="{ 'is-active': activeTab === 'gate' }"
          :aria-selected="activeTab === 'gate'"
          @click="switchTab('gate')"
        >
          质量门
          <span
            v-if="gateDetail"
            class="release-tab__dot"
            :class="`is-${gateTone(gateDetail.status)}`"
            aria-hidden="true"
          />
        </button>
        <button
          type="button"
          role="tab"
          class="release-tab"
          :class="{ 'is-active': activeTab === 'review' }"
          :aria-selected="activeTab === 'review'"
          @click="switchTab('review')"
        >
          发布评审
          <span v-if="latestReview" class="release-tab__badge">{{ reviewLabel(latestReview.decision) }}</span>
        </button>
        <button
          type="button"
          role="tab"
          class="release-tab"
          :class="{ 'is-active': activeTab === 'quality' }"
          :aria-selected="activeTab === 'quality'"
          @click="switchTab('quality')"
        >
          版本质量
        </button>
      </nav>

      <div v-if="activeTab === 'overview'" class="release-tab-panel">

      <div class="stats">
        <div class="stat">
          <span class="stat__label">需求总数</span>
          <span class="stat__value">{{ stats.total }}</span>
        </div>
        <div class="stat">
          <span class="stat__label">已完成</span>
          <span class="stat__value">{{ stats.done }}</span>
        </div>
        <div class="stat">
          <span class="stat__label">预估人天</span>
          <span class="stat__value">{{ formatDays(stats.estimateDays) }}</span>
        </div>
        <div class="stat">
          <span class="stat__label">实际人天</span>
          <span class="stat__value">{{ formatDays(stats.actualDays) }}</span>
        </div>
        <div class="stat stat--wide">
          <span class="stat__label">完成度 {{ donePercent }}%</span>
          <div class="bar" role="progressbar" :aria-valuenow="donePercent" aria-valuemin="0" aria-valuemax="100">
            <span :style="{ width: `${donePercent}%` }" />
          </div>
        </div>
      </div>

      <div class="columns">
        <section class="surface column-main">
          <header class="section-head">
            <h2 class="section-title">工作项</h2>
            <span class="count-badge">{{ requirements.length }}</span>
          </header>

          <div v-if="loading" class="skeleton" style="height: 220px" />
          <EmptyState
            v-else-if="requirements.length === 0"
            title="版本里还没有需求"
            description="把需求纳入版本后，测试门禁与发布准入都会基于这里的范围计算。"
            action-text="纳入需求"
            icon="inbox"
            @action="openAdd"
          />
          <el-table v-else :data="requirements" size="small" row-key="id" @row-click="openRequirement">
            <el-table-column label="编号" width="128">
              <template #default="{ row }">
                <span class="mono code-link">{{ row.code }}</span>
              </template>
            </el-table-column>
            <el-table-column label="标题" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.title }}</template>
            </el-table-column>
            <el-table-column label="类型" width="84">
              <template #default="{ row }">{{ typeLabel(row.type) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="96">
              <template #default="{ row }">
                <StatusPill kind="requirement" :value="row.status" size="sm" />
              </template>
            </el-table-column>
            <el-table-column label="优先级" width="76">
              <template #default="{ row }"><PriorityDot :value="row.priority" /></template>
            </el-table-column>
            <el-table-column label="子需求" width="64">
              <template #default="{ row }">
                <span v-if="childInfo(row).childCount > 0" class="muted">
                  {{ childInfo(row).doneCount }}/{{ childInfo(row).childCount }}
                </span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="预估/实际" width="92">
              <template #default="{ row }">
                <span class="num">{{ row.estimateDays ?? '—' }} / {{ row.actualDays ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="64" align="right">
              <template #default="{ row }">
                <el-button
                  class="row-action"
                  link
                  type="danger"
                  size="small"
                  :disabled="!canEditScope"
                  :title="canEditScope ? '移出本版本' : scopeLocked ? lockReason : '需要权限：release:plan'"
                  @click.stop="askRemove(row)"
                >
                  移出
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <aside class="column-side">
          <section class="surface surface--pad side-block">
            <h2 class="section-title">里程碑</h2>
            <ul v-if="milestones.length > 0" class="milestones">
              <li v-for="item in milestones" :key="item.id">
                <span class="dot" :class="{ 'is-done': item.status === 'DONE' }" />
                <div>
                  <p class="milestone__name">{{ item.name }}</p>
                  <p class="milestone__meta">{{ formatDate(item.plannedDate) }} · {{ statusOfMilestone(item.status) }}</p>
                </div>
              </li>
            </ul>
            <p v-else class="muted small">还没有里程碑；把关键节点写进来，团队就知道何时该冻结范围。</p>
          </section>

          <section class="surface surface--pad side-block">
            <h2 class="section-title">风险项 <span class="count-badge count-badge--danger">{{ riskItems.length }}</span></h2>
            <ul v-if="riskItems.length > 0" class="risks">
              <li v-for="item in riskItems" :key="item.id">
                <button type="button" class="link" @click="openRequirement(item)">
                  {{ item.code }} · {{ item.title }}
                </button>
                <StatusPill kind="requirement" :value="item.status" size="sm" />
              </li>
            </ul>
            <p v-else class="muted small">没有识别到风险项。</p>
          </section>

          <section class="surface surface--pad side-block" aria-labelledby="release-integration-title">
            <h2 id="release-integration-title" class="section-title">集成设置</h2>
            <p class="muted small">
              版本级默认值：需求没单独配置时继承这里。改动只影响后续同步，不追溯已生成的快照。
            </p>
            <div class="intg-row">
              <span class="intg-label">MR 合并自动推进</span>
              <el-switch
                v-model="integrationAutoAdvance"
                size="small"
                :disabled="integrationLoading || integrationSaving || !can('release:plan')"
                aria-label="MR 合并后自动推进需求状态"
              />
            </div>
            <p class="muted small intg-hint">
              默认关闭：MR 合入后由人工在需求「集成」页确认推进；打开后仅在状态机允许时自动前进。
            </p>
            <div class="intg-field">
              <span class="intg-label">默认 Sonar 项目 key</span>
              <el-input
                v-model="integrationSonarKey"
                size="small"
                placeholder="如 supply-order-service"
                :disabled="integrationLoading || integrationSaving || !can('release:plan')"
              />
            </div>
            <p v-if="integrationError" class="intg-error" role="alert">{{ integrationError }}</p>
            <div class="intg-actions">
              <el-button
                size="small"
                type="primary"
                :loading="integrationSaving"
                :disabled="integrationLoading || !can('release:plan')"
                @click="saveIntegration"
              >
                保存
              </el-button>
              <span v-if="!can('release:plan')" class="muted small">需要权限：release:plan</span>
            </div>
          </section>
        </aside>
      </div>
      </div>

      <div v-else-if="activeTab === 'gate'" class="release-tab-panel">
        <div class="tab-toolbar">
          <p class="tab-toolbar__hint">门禁结果只读展示；规则配置、预评估与豁免申请在质量门详情中操作。</p>
          <div class="tab-toolbar__actions">
            <el-button size="small" :loading="gateLoading" @click="loadGatePanel">刷新</el-button>
            <el-button
              size="small"
              type="primary"
              @click="router.push({ name: 'quality-gate-detail', params: { releaseId } })"
            >
              打开质量门详情
            </el-button>
          </div>
        </div>

        <ErrorState v-if="gateError" title="质量门加载失败" :message="gateError" compact @retry="loadGatePanel" />
        <div v-else-if="gateLoading && !gateDetail" class="skeleton" style="height: 280px" />
        <template v-else-if="gateDetail">
          <ConclusionBar
            :tone="gateTone(gateDetail.status)"
            :title="gateDetail.status === 'NOT_EVALUATED' ? '质量门未评估' : gateLabel(gateDetail.status)"
            :hint="
              gateDetail.status === 'NOT_EVALUATED'
                ? '发布单提交时会按 5 条规则实时求值并固化快照'
                : `规则 ${gateRulesSummary} · ${gateDetail.evaluatedAt ? `评估于 ${formatDateTime(gateDetail.evaluatedAt)}` : '尚未固化快照'}`
            "
          >
            <DigestChip :hash="latestSnapshot?.hash" label="最新快照" />
          </ConclusionBar>

          <QualityGatePanel
            mode="gate"
            :title="`门禁规则 · 规则集 v${gateDetail.rulesetVersion ?? 1}`"
            :rules="gateDetail.rules"
            :waivers="gateDetail.waivers"
            :result="gateDetail.status"
            :evaluated-at="gateDetail.evaluatedAt"
            :snapshot-id="latestSnapshot?.snapshotId"
            :hash="latestSnapshot?.hash"
            readonly
          />

          <section class="surface surface--pad">
            <header class="section-head">
              <h2 class="section-title">快照历史</h2>
              <span class="t-aux">快照不可变，回放用于核对当时的规则与报告版本</span>
            </header>
            <EmptyState
              v-if="snapshotHistory.length === 0"
              compact
              title="还没有固化快照"
              description="发布单提交时才会固化质量门快照。"
              icon="file"
            />
            <el-table v-else :data="snapshotHistory" size="small">
              <el-table-column label="评估时间" width="150">
                <template #default="{ row }">{{ formatDateTime(row.computedAt) }}</template>
              </el-table-column>
              <el-table-column label="结果" width="128">
                <template #default="{ row }"><StatusPill kind="gate" :value="row.result" size="sm" /></template>
              </el-table-column>
              <el-table-column label="规则" width="110">
                <template #default="{ row }">{{ rulePassText(row) }}</template>
              </el-table-column>
              <el-table-column label="报告版本" width="100">
                <template #default="{ row }">
                  <span class="muted">{{ row.reportVersionId ? `#${row.reportVersionId}` : '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="快照 hash" min-width="130">
                <template #default="{ row }"><DigestChip :hash="row.hash" /></template>
              </el-table-column>
              <el-table-column label="快照" width="90" align="right">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    size="small"
                    @click="router.push({ name: 'quality-gate-detail', params: { releaseId }, query: { snapshot: String(row.snapshotId) } })"
                  >
                    #{{ row.snapshotId }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </template>
      </div>

      <div v-else-if="activeTab === 'review'" class="release-tab-panel">
        <div class="tab-toolbar">
          <p class="tab-toolbar__hint">Go/No-Go 记录由 PMO 在发布评审页生成；该页会引用质量门快照与已会签报告。</p>
          <el-button
            size="small"
            type="primary"
            @click="router.push({ name: 'release-go-no-go', params: { id: releaseId } })"
          >
            进入发布评审
          </el-button>
        </div>

        <div v-if="reviewLoading" class="skeleton" style="height: 200px" />
        <EmptyState
          v-else-if="reviewRows.length === 0"
          title="还没有发布评审记录"
          description="质量门通过后，由 PMO 在发布评审页生成 Go/No-Go 记录。"
          action-text="进入发布评审"
          icon="inbox"
          @action="router.push({ name: 'release-go-no-go', params: { id: releaseId } })"
        />
        <template v-else>
          <ConclusionBar
            v-if="latestReview"
            :tone="reviewDecisionTone(latestReview.decision)"
            :title="`${reviewLabel(latestReview.decision)} · ${latestReview.status === 'CONFIRMED' ? '已确认' : '待确认'}`"
            :hint="`评审时间 ${formatDateTime(latestReview.meetingAt)}${latestReview.conditions ? ` · 附条件/风险：${latestReview.conditions}` : ''}`"
          />
          <section class="surface surface--flush">
            <el-table :data="reviewRows" size="small">
              <el-table-column label="评审时间" width="150">
                <template #default="{ row }">{{ formatDateTime(row.meetingAt) }}</template>
              </el-table-column>
              <el-table-column label="决策" width="128">
                <template #default="{ row }">{{ reviewLabel(row.decision) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">{{ row.status === 'CONFIRMED' ? '已确认' : '待确认' }}</template>
              </el-table-column>
              <el-table-column label="附条件/风险" min-width="200" show-overflow-tooltip>
                <template #default="{ row }">{{ row.conditions || '—' }}</template>
              </el-table-column>
              <el-table-column label="门禁已评估" width="100">
                <template #default="{ row }">{{ row.gateEvaluated ? '是' : '否（预评审）' }}</template>
              </el-table-column>
              <el-table-column label="生成时间" width="150">
                <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
              </el-table-column>
            </el-table>
          </section>
        </template>
      </div>

      <div v-else class="release-tab-panel">
        <ErrorState v-if="qualityError" title="版本质量加载失败" :message="qualityError" compact @retry="loadQualityPanel" />
        <div v-else-if="qualityLoading && !quality" class="skeleton" style="height: 260px" />
        <template v-else-if="quality">
          <div class="quality-cards">
            <button type="button" class="quality-card" @click="focusQuality('defects')">
              <span class="quality-card__label">未关闭 P0/P1</span>
              <strong class="quality-card__value">{{ quality.openP0P1.length }}</strong>
            </button>
            <button type="button" class="quality-card" @click="focusQuality('reports')">
              <span class="quality-card__label">报告版本</span>
              <strong class="quality-card__value">{{ quality.reports.length }}</strong>
            </button>
            <button type="button" class="quality-card" @click="focusQuality('gate')">
              <span class="quality-card__label">门禁记录</span>
              <strong class="quality-card__value">{{ quality.gateHistory.length }}</strong>
            </button>
            <button type="button" class="quality-card" @click="focusQuality('escape')">
              <span class="quality-card__label">逃逸缺陷</span>
              <strong class="quality-card__value">{{ quality.escapeDefects.length }}</strong>
            </button>
            <button type="button" class="quality-card" @click="focusQuality('escape')">
              <span class="quality-card__label">回滚次数</span>
              <strong class="quality-card__value">{{ qualityCounts.rollback ?? 0 }}</strong>
            </button>
          </div>

          <div class="quality-rates">
            <span>FPY <strong>{{ formatPercent(qualityCounts.fpy ?? null) }}</strong></span>
            <span>逃逸率 <strong>{{ formatPercent(qualityCounts.escape ?? null) }}</strong></span>
            <span>发布后缺陷 <strong>{{ qualityCounts.postRelease ?? 0 }}</strong></span>
            <span>回滚率 <strong>{{ formatPercent(qualityCounts.rollback ?? null) }}</strong></span>
          </div>

          <section id="release-quality-gate" class="surface surface--pad">
            <header class="section-head">
              <h2 class="section-title">质量门历史</h2>
              <span class="count-badge">{{ quality.gateHistory.length }}</span>
            </header>
            <EmptyState v-if="quality.gateHistory.length === 0" compact title="没有门禁快照" description="发布单提交后按 5 条规则固化。" icon="file" />
            <el-table v-else :data="quality.gateHistory" size="small">
              <el-table-column label="评估时间" width="150">
                <template #default="{ row }">{{ formatDateTime(row.computedAt) }}</template>
              </el-table-column>
              <el-table-column label="结果" width="128">
                <template #default="{ row }"><StatusPill kind="gate" :value="row.result" size="sm" /></template>
              </el-table-column>
              <el-table-column label="规则" width="110">
                <template #default="{ row }">{{ rulePassText(row) }}</template>
              </el-table-column>
              <el-table-column label="快照 hash" min-width="140">
                <template #default="{ row }"><DigestChip :hash="row.hash" /></template>
              </el-table-column>
            </el-table>
          </section>

          <section id="release-quality-reports" class="surface surface--pad">
            <header class="section-head">
              <h2 class="section-title">已会签报告</h2>
              <span class="count-badge">{{ quality.reports.length }}</span>
            </header>
            <EmptyState v-if="quality.reports.length === 0" compact title="没有报告版本" description="在测试报告中心生成并完成三级会签。" icon="file" />
            <el-table v-else :data="quality.reports" size="small" @row-click="openReport">
              <el-table-column label="编号" width="140">
                <template #default="{ row }"><span class="mono code-link">{{ row.code }}</span></template>
              </el-table-column>
              <el-table-column label="当前版本" width="90">
                <template #default="{ row }">v{{ row.currentVersion }}</template>
              </el-table-column>
              <el-table-column label="结论" width="120">
                <template #default="{ row }"><StatusPill kind="report" :value="row.conclusion" size="sm" /></template>
              </el-table-column>
              <el-table-column label="通过率" width="96">
                <template #default="{ row }">{{ formatPercent(row.passRate) }}</template>
              </el-table-column>
              <el-table-column label="覆盖率" width="96">
                <template #default="{ row }">{{ formatPercent(row.coverage) }}</template>
              </el-table-column>
              <el-table-column label="生成时间" min-width="150">
                <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
              </el-table-column>
            </el-table>
          </section>

          <section id="release-quality-defects" class="surface surface--pad">
            <header class="section-head">
              <h2 class="section-title">未关闭 P0/P1</h2>
              <span class="count-badge count-badge--danger">{{ quality.openP0P1.length }}</span>
            </header>
            <EmptyState v-if="quality.openP0P1.length === 0" compact title="没有未关闭 P0/P1" description="QG-04 通过，仍需在发布后跟踪生产缺陷。" icon="chart" />
            <el-table v-else :data="quality.openP0P1" size="small">
              <el-table-column label="编号" width="130">
                <template #default="{ row }"><span class="mono">{{ row.code ?? `#${row.defectId ?? row.id}` }}</span></template>
              </el-table-column>
              <el-table-column label="标题" min-width="200" show-overflow-tooltip>
                <template #default="{ row }">{{ row.title || '—' }}</template>
              </el-table-column>
              <el-table-column label="严重级" width="90">
                <template #default="{ row }">{{ row.severity || '—' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">{{ row.status || '—' }}</template>
              </el-table-column>
            </el-table>
          </section>

          <section id="release-quality-escape" class="surface surface--pad">
            <header class="section-head">
              <h2 class="section-title">逃逸到生产的缺陷</h2>
              <span class="count-badge">{{ quality.escapeDefects.length }}</span>
            </header>
            <EmptyState v-if="quality.escapeDefects.length === 0" compact title="没有逃逸缺陷" description="观察窗内的生产缺陷会在创建时判定并落库。" icon="chart" />
            <el-table v-else :data="quality.escapeDefects" size="small">
              <el-table-column label="编号" width="130">
                <template #default="{ row }"><span class="mono">{{ row.code ?? `#${row.defectId ?? row.id}` }}</span></template>
              </el-table-column>
              <el-table-column label="标题" min-width="200" show-overflow-tooltip>
                <template #default="{ row }">{{ row.title || '—' }}</template>
              </el-table-column>
              <el-table-column label="严重级" width="90">
                <template #default="{ row }">{{ row.severity || '—' }}</template>
              </el-table-column>
              <el-table-column label="发现时间" width="150">
                <template #default="{ row }">{{ formatDateTime(row.foundAt) }}</template>
              </el-table-column>
            </el-table>
          </section>
        </template>
      </div>
    </template>

    <el-dialog v-model="addOpen" title="纳入需求" width="560px" append-to-body>
      <p class="dialog-hint">只列出尚未纳入本版本的需求；已锁定的版本将拒绝变更。</p>
      <el-select
        v-model="addIds"
        multiple
        filterable
        remote
        reserve-keyword
        :remote-method="searchCandidates"
        :loading="addSearching"
        placeholder="输入编号或标题搜索"
        style="width: 100%"
      >
        <el-option
          v-for="row in addOptions"
          :key="row.id"
          :label="`${row.code} · ${row.title}`"
          :value="row.id"
        />
      </el-select>
      <p v-if="addError" class="dialog-error">{{ addError }}</p>
      <template #footer>
        <el-button @click="addOpen = false">取消</el-button>
        <el-button type="primary" :loading="addSaving" :disabled="addIds.length === 0" @click="submitAdd">
          纳入 {{ addIds.length || '' }} 条
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="milestoneOpen" title="添加里程碑" width="440px" append-to-body>
      <el-form label-position="top" size="small">
        <el-form-item label="名称" required>
          <el-input v-model="milestoneName" maxlength="40" placeholder="如：范围冻结 / 提测 / 发布" />
        </el-form-item>
        <el-form-item label="计划日期" required>
          <el-date-picker v-model="milestoneDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
        </el-form-item>
      </el-form>
      <p v-if="milestoneError" class="dialog-error">{{ milestoneError }}</p>
      <template #footer>
        <el-button @click="milestoneOpen = false">取消</el-button>
        <el-button type="primary" :loading="milestoneSaving" @click="submitMilestone">添加</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="approvalOpen" title="提交发布审批" width="440px" append-to-body>
      <p class="dialog-hint">
        提交后将按版本的审批流推进；审批通过前版本仍可调整需求范围，审批通过后自动锁定。
      </p>
      <ul class="dialog-impact">
        <li>审批人：由该业务域的发布审批流决定</li>
        <li>影响范围：本版本 {{ stats.total }} 条需求与后续发布单</li>
        <li>撤回：审批中可由发起人撤回，已通过后不可撤回</li>
      </ul>
      <template #footer>
        <el-button @click="approvalOpen = false">取消</el-button>
        <el-button type="primary" :loading="approvalSaving" @click="submitApproval">提交审批</el-button>
      </template>
    </el-dialog>

    <ConfirmDialog
      v-model="removeOpen"
      title="移出需求"
      :description="removeTarget ? `把 ${removeTarget.code} · ${removeTarget.title} 移出本版本？` : ''"
      :impact="['该需求将回到未排期状态，不再参与本版本的测试门禁与发布准入']"
      :reason-required="false"
      confirm-text="移出"
      tone="danger"
      :loading="removing"
      @confirm="confirmRemove"
      @cancel="removeOpen = false"
    />
  </div>
</template>

<style scoped>
.release-detail {
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
.mode-tag {
  font-size: 11px;
  color: var(--info-ink);
  background: var(--color-info-bg);
  border-radius: var(--r-pill);
  padding: 1px 8px;
}
.mode-tag.is-loose {
  color: var(--warning-ink);
  background: var(--color-warning-bg);
}
.lock-tag {
  font-size: 12px;
  color: var(--warning-ink);
  background: var(--color-warning-bg);
  border-radius: var(--r-ctl);
  padding: 2px 8px;
}
.meta-line {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
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
.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr)) 1.4fr;
  gap: 8px;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
}
.stat__label {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.stat__value {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.stat--wide {
  justify-content: center;
}
.bar {
  height: 8px;
  border-radius: var(--r-pill);
  background: var(--divider);
  overflow: hidden;
}
.bar span {
  display: block;
  height: 100%;
  background: var(--color-primary);
  transition: width var(--t-expand) var(--ease);
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
.section-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--divider);
}
.section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.column-side {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.side-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.intg-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.intg-label {
  font-size: 12px;
  color: var(--text-body);
}
.intg-hint {
  margin-top: -4px;
  line-height: 16px;
}
.intg-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.intg-error {
  margin: 0;
  color: var(--danger-ink);
  font-size: 12px;
}
.intg-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.milestones,
.risks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.milestones li {
  display: flex;
  gap: 8px;
}
.dot {
  width: 8px;
  height: 8px;
  margin-top: 5px;
  border-radius: 50%;
  border: 1px solid var(--border-strong);
  flex: none;
}
.dot.is-done {
  background: var(--color-primary);
  border-color: var(--color-primary);
}
.milestone__name {
  margin: 0;
  font-size: 13px;
  color: var(--text-body);
}
.milestone__meta {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.risks li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
}
.muted {
  color: var(--text-muted-aa);
}
.small {
  font-size: 12px;
  margin: 0;
}
.mono {
  font-family: var(--font-mono);
}
.num {
  font-variant-numeric: tabular-nums;
}
.code-link {
  color: var(--color-primary);
  white-space: nowrap;
}
.row-action {
  opacity: 0;
  transition: opacity var(--t-color) var(--ease);
}
:deep(.el-table__row:hover) .row-action {
  opacity: 1;
}
.dialog-hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.6;
}
.dialog-impact {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--text-body);
  line-height: 1.9;
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
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .quality-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.release-tabs {
  display: flex;
  gap: 2px;
  border-bottom: 1px solid var(--border);
}
.release-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: none;
  border-bottom: 2px solid transparent;
  background: none;
  font-size: 13px;
  color: var(--text-body);
  cursor: pointer;
  transition: color var(--t-color), border-color var(--t-color);
}
.release-tab:hover {
  color: var(--color-primary);
  background: var(--bg-hover);
}
.release-tab.is-active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}
.release-tab__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-weak);
}
.release-tab__dot.is-success {
  background: var(--color-success);
}
.release-tab__dot.is-warning {
  background: var(--color-warning);
}
.release-tab__dot.is-danger {
  background: var(--color-danger);
}
.release-tab__badge {
  padding: 0 6px;
  border-radius: var(--r-pill);
  background: var(--color-info-bg);
  color: var(--text-body);
  font-size: 11px;
  font-weight: 500;
  line-height: 16px;
}
.release-tab-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.tab-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.tab-toolbar__hint {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.6;
}
.tab-toolbar__actions {
  display: flex;
  gap: 8px;
  flex: none;
}
.quality-cards {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}
.quality-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--t-color), background var(--t-color);
}
.quality-card:hover {
  border-color: var(--color-primary);
  background: var(--bg-hover);
}
.quality-card__label {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.quality-card__value {
  font-family: var(--font-mono);
  font-size: 22px;
  font-weight: 600;
  color: var(--text-title);
  font-variant-numeric: tabular-nums;
}
.quality-rates {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  font-size: 12px;
  color: var(--text-muted-aa);
}
.quality-rates strong {
  margin-left: 4px;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--text-title);
}
</style>
