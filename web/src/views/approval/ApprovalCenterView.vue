<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  addSign,
  getApprovalInstance,
  handleApprovalTask,
  listApprovalDone,
  listApprovalMine,
  listApprovalTodo,
  removeSign,
  transferTask,
  urgeTask,
  withdrawInstance,
} from '@/api/req'
import { listDictionaryUsers } from '@/api/dictionaries'
import type { DictionaryUser } from '@/api/dictionaries'
import {
  CASE_REVIEW_CONCLUSION_LABEL,
  CASE_REVIEW_STATUS_LABEL,
  fetchCaseReviewList,
} from '@/api/deliver'
import type { CaseReviewRow, CaseReviewStatus } from '@/api/deliver'
import type {
  ApprovalInstanceDetail,
  ApprovalTaskRow,
  ApprovalTimelineEvent,
  ApprovalTodoRow,
} from '@/api/types'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import { usePermission } from '@/composables/usePermission'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import { messageOf, normalizeTimeline, summaryText, usePlatformDict } from '@/views/req/platform'

/**
 * P3 审批中心：我的待办 / 已办 / 我发起。
 * 左 360 列表、右详情（时间线 + 被审单据摘要裁剪），底部动作条按「我的待处理任务 + 权限」渲染。
 */
type Tab = 'todo' | 'done' | 'mine' | 'case-reviews'
const TABS: Array<{ key: Tab; label: string }> = [
  { key: 'todo', label: '我的待办' },
  { key: 'done', label: '已办' },
  { key: 'mine', label: '我发起' },
  { key: 'case-reviews', label: '用例评审' },
]
const BIZ_LABELS: Record<string, string> = {
  REQUIREMENT: '需求',
  REQ: '需求',
  RELEASE: '版本',
  DEPLOY_ORDER: '发布单',
  DEPLOY: '发布单',
  DEFECT: '缺陷',
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { can } = usePermission()
const dict = usePlatformDict()

const me = computed(() => auth.user?.id ?? null)
const canReview = computed(() => can('req:review'))
const canCaseReview = computed(() => can('CASE_REVIEW_VIEW'))

/**
 * 用例评审是**只读**台账：它有自己的状态机（SUBMITTED→DONE/INVALIDATED），
 * 从不进通用审批引擎，所以这里没有待办、没有动作条、没有 SLA 计时。
 */
const visibleTabs = computed(() => TABS.filter((item) => item.key !== 'case-reviews' || canCaseReview.value))

function normalizeTab(value: unknown): Tab {
  if (value === 'done' || value === 'mine') return value
  if (value === 'case-reviews' && canCaseReview.value) return 'case-reviews'
  return 'todo'
}

const tab = ref<Tab>(normalizeTab(route.query.tab))
const bizType = ref<string>((route.query.bizType as string) || '')
const rows = ref<ApprovalTodoRow[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const listError = ref('')

/* 用例评审只读台账（独立分页与筛选，不与审批列表混用） */
const caseReviews = ref<CaseReviewRow[]>([])
const caseReviewTotal = ref(0)
const caseReviewLoading = ref(false)
const caseReviewError = ref('')
const caseStatus = ref<'' | CaseReviewStatus>('')

const selected = ref<ApprovalTodoRow | null>(null)
const detail = ref<ApprovalInstanceDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const actionError = ref('')

const filtered = computed(() =>
  bizType.value ? rows.value.filter((row) => bizKey(row) === bizType.value) : rows.value,
)

function bizKey(row: ApprovalTodoRow): string {
  return bizKeyOf(row.bizType)
}

function bizKeyOf(rawType?: string): string {
  const raw = String(rawType ?? '').toUpperCase()
  if (raw.includes('DEPLOY')) return 'DEPLOY'
  if (raw.includes('RELEASE')) return 'RELEASE'
  if (raw.includes('DEFECT')) return 'DEFECT'
  if (raw.includes('REQ')) return 'REQUIREMENT'
  return raw
}

function bizLabelOf(rawType?: string): string {
  return BIZ_LABELS[bizKeyOf(rawType)] ?? rawType ?? '单据'
}

function bizLabel(row: ApprovalTodoRow): string {
  return bizLabelOf(row.bizType)
}

function rowTitle(row: ApprovalTodoRow): string {
  const document = (row as ApprovalTodoRow & { document?: unknown }).document
  return row.summary || summaryText(document) || `${bizLabel(row)} #${row.bizId}`
}

function rowInitiator(row: ApprovalTodoRow): string {
  const raw = row as ApprovalTodoRow & { initiatorName?: string; initiatorId?: number }
  const initiator = row.initiator as unknown
  if (initiator && typeof initiator === 'object' && 'name' in initiator) {
    return String((initiator as { name?: string }).name ?? '')
  }
  if (typeof initiator === 'string' && initiator) return initiator
  if (raw.initiatorName) return raw.initiatorName
  return dict.userName(raw.initiatorId)
}

function isOverdue(row: ApprovalTodoRow): boolean {
  return Boolean(row.dueAt) && new Date(row.dueAt as string).getTime() < Date.now()
}

function rowTime(row: ApprovalTodoRow): string {
  const raw = row as ApprovalTodoRow & { createdAt?: string }
  return formatDateTime(row.arrivedAt ?? raw.createdAt)
}

async function load(): Promise<void> {
  if (tab.value === 'case-reviews') {
    await loadCaseReviews()
    return
  }
  loading.value = true
  listError.value = ''
  try {
    dict.ensure()
    const params = { page: page.value, size }
    const response =
      tab.value === 'todo'
        ? await listApprovalTodo(params)
        : tab.value === 'done'
          ? await listApprovalDone(params)
          : await listApprovalMine(params)
    rows.value = response.items ?? []
    total.value = response.total ?? rows.value.length
    const first = filtered.value[0] ?? null
    selected.value = first
    await loadDetail(first?.instanceId ?? null)
  } catch (error) {
    listError.value = messageOf(error)
    rows.value = []
    total.value = 0
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function loadCaseReviews(): Promise<void> {
  caseReviewLoading.value = true
  caseReviewError.value = ''
  try {
    const response = await fetchCaseReviewList({
      status: caseStatus.value || undefined,
      page: page.value,
      size,
    })
    caseReviews.value = response.items ?? []
    caseReviewTotal.value = response.total ?? caseReviews.value.length
  } catch (error) {
    caseReviewError.value = messageOf(error)
    caseReviews.value = []
    caseReviewTotal.value = 0
  } finally {
    caseReviewLoading.value = false
  }
}

function caseStatusLabel(value?: string | null): string {
  if (!value) return '—'
  return CASE_REVIEW_STATUS_LABEL[value as CaseReviewStatus] ?? value
}

function caseConclusionLabel(value?: string | null): string {
  if (!value) return '—'
  return CASE_REVIEW_CONCLUSION_LABEL[value as keyof typeof CASE_REVIEW_CONCLUSION_LABEL] ?? value
}

function caseStatusTone(value?: string | null): string {
  if (value === 'DONE') return 'cr-tag cr-tag--done'
  if (value === 'INVALIDATED') return 'cr-tag cr-tag--stale'
  return 'cr-tag cr-tag--open'
}

function onCaseStatusChange(): void {
  page.value = 1
  void load()
}

async function loadDetail(instanceId: number | null): Promise<void> {
  detail.value = null
  detailError.value = ''
  actionError.value = ''
  if (instanceId == null) return
  detailLoading.value = true
  try {
    detail.value = await getApprovalInstance(instanceId)
  } catch (error) {
    detailError.value = messageOf(error)
  } finally {
    detailLoading.value = false
  }
}

onMounted(load)
watch(tab, (value) => {
  page.value = 1
  void router.replace({ query: { ...route.query, tab: value } })
  void load()
})
watch(bizType, (value) => {
  void router.replace({ query: { ...route.query, bizType: value || undefined } })
})

function selectRow(row: ApprovalTodoRow): void {
  if (selected.value?.taskId === row.taskId) return
  selected.value = row
  void loadDetail(row.instanceId)
}

const instance = computed(() => detail.value?.instance ?? null)
const tasks = computed<ApprovalTaskRow[]>(() => detail.value?.tasks ?? [])
const timeline = computed<ApprovalTimelineEvent[]>(() => normalizeTimeline(detail.value?.timeline))
const documentEntries = computed<Array<{ label: string; value: string }>>(() => {
  const document = detail.value?.document as unknown
  if (!document || typeof document !== 'object') return []
  const labels: Record<string, string> = {
    code: '编号',
    title: '标题',
    name: '名称',
    type: '类型',
    status: '状态',
    priority: '优先级',
    domainName: '业务域',
    releaseName: '版本',
    env: '环境',
    requirementCode: '关联需求',
    applicantName: '申请人',
    initiatorName: '发起人',
    createdAt: '创建时间',
    estimateDays: '预估人天',
    windowStart: '发布窗口',
  }
  return Object.entries(document as Record<string, unknown>)
    .filter(([key, value]) => labels[key] && value != null && typeof value !== 'object')
    .slice(0, 8)
    .map(([key, value]) => ({ label: labels[key], value: String(value) }))
})

const myTask = computed<ApprovalTaskRow | null>(() => {
  const userId = me.value
  if (userId == null) return null
  return (
    tasks.value.find((task) => {
      const assigneeId = task.assignee?.id ?? (task as ApprovalTaskRow & { approverId?: number }).approverId
      return task.status === 'PENDING' && assigneeId === userId
    }) ?? null
  )
})

const pendingOthers = computed(() =>
  tasks.value.filter((task) => {
    const assigneeId = task.assignee?.id ?? (task as ApprovalTaskRow & { approverId?: number }).approverId
    return task.status === 'PENDING' && assigneeId !== me.value
  }),
)

const instancePending = computed(() => (detail.value?.instance?.status ?? '') === 'PENDING')
const canWithdraw = computed(
  () =>
    instancePending.value &&
    me.value != null &&
    (instance.value?.initiator?.id ?? (instance.value as ApprovalInstanceDetail['instance'] & { initiatorId?: number })?.initiatorId) === me.value,
)

const cannotActReason = computed(() => {
  if (!detail.value) return '先选择一条待办'
  if (!instancePending.value) return '当前审批已结束，只能查看记录'
  if (!canReview.value) return '需要权限：req:review'
  if (!myTask.value) return '当前节点不由你审批（或已被他人处理）'
  return ''
})

/* ---------- 同意 / 驳回 ---------- */
const decisionOpen = ref(false)
const decisionKind = ref<'APPROVE' | 'REJECT'>('APPROVE')
const decisionComment = ref('')
const decisionBusy = ref(false)
const decisionError = ref('')

function openDecision(kind: 'APPROVE' | 'REJECT'): void {
  if (cannotActReason.value) return
  decisionKind.value = kind
  decisionComment.value = ''
  decisionError.value = ''
  decisionOpen.value = true
}

async function submitDecision(): Promise<void> {
  const task = myTask.value
  if (!task) return
  if (decisionKind.value === 'REJECT' && decisionComment.value.trim().length < 2) {
    decisionError.value = '驳回必须填写意见（≥2 字），会随通知发给发起人'
    return
  }
  decisionBusy.value = true
  decisionError.value = ''
  try {
    await handleApprovalTask(task.id, {
      action: decisionKind.value,
      comment: decisionComment.value.trim() || undefined,
    })
    decisionOpen.value = false
    ElMessage.success(decisionKind.value === 'APPROVE' ? '已同意，流程继续' : '已驳回，单据退回发起人')
    await load()
  } catch (error) {
    decisionBusy.value = false
    decisionOpen.value = false
    actionError.value = messageOf(error)
  }
}

/* ---------- 加签 ---------- */
const signOpen = ref(false)
const signType = ref<'BEFORE' | 'AFTER'>('AFTER')
const signUserIds = ref<number[]>([])
const signComment = ref('')
const signBusy = ref(false)
const signError = ref('')
const userOptions = ref<DictionaryUser[]>([])

async function loadUsers(): Promise<void> {
  if (userOptions.value.length > 0) return
  try {
    userOptions.value = await listDictionaryUsers()
  } catch {
    userOptions.value = []
  }
}

function openSign(): void {
  if (cannotActReason.value) return
  signType.value = 'AFTER'
  signUserIds.value = []
  signComment.value = ''
  signError.value = ''
  signOpen.value = true
  void loadUsers()
}

async function submitSign(): Promise<void> {
  const task = myTask.value
  if (!task) return
  if (signUserIds.value.length === 0) {
    signError.value = '至少选择一位加签人'
    return
  }
  signBusy.value = true
  signError.value = ''
  try {
    await addSign(task.id, {
      type: signType.value,
      userIds: signUserIds.value,
      comment: signComment.value.trim() || undefined,
    })
    signOpen.value = false
    ElMessage.success('已加签，被加签人会收到待办')
    await load()
  } catch (error) {
    signError.value = messageOf(error)
  } finally {
    signBusy.value = false
  }
}

/* ---------- 减签 ---------- */
const removeOpen = ref(false)
const removeTarget = ref<number | null>(null)
const removeReason = ref('')
const removeBusy = ref(false)
const removeError = ref('')

function openRemove(): void {
  if (cannotActReason.value) return
  removeTarget.value = pendingOthers.value[0]
    ? (pendingOthers.value[0].assignee?.id ?? (pendingOthers.value[0] as ApprovalTaskRow & { approverId?: number }).approverId ?? null)
    : null
  removeReason.value = ''
  removeError.value = ''
  removeOpen.value = true
}

async function submitRemove(): Promise<void> {
  const task = myTask.value
  if (!task || removeTarget.value == null) {
    removeError.value = '没有可减签的其他审批人'
    return
  }
  if (removeReason.value.trim().length < 2) {
    removeError.value = '减签原因必填（≥2 字），将记录在审批时间线'
    return
  }
  removeBusy.value = true
  removeError.value = ''
  try {
    await removeSign(task.id, { targetUserId: removeTarget.value, reason: removeReason.value.trim() })
    removeOpen.value = false
    ElMessage.success('已减签，已通知被减签人')
    await load()
  } catch (error) {
    removeError.value = messageOf(error)
  } finally {
    removeBusy.value = false
  }
}

/* ---------- 转办 ---------- */
const transferOpen = ref(false)
const transferTo = ref<number | null>(null)
const transferComment = ref('')
const transferBusy = ref(false)
const transferError = ref('')

function openTransfer(): void {
  if (cannotActReason.value) return
  transferTo.value = null
  transferComment.value = ''
  transferError.value = ''
  transferOpen.value = true
  void loadUsers()
}

async function submitTransfer(): Promise<void> {
  const task = myTask.value
  if (!task) return
  if (transferTo.value == null) {
    transferError.value = '请选择转办对象'
    return
  }
  transferBusy.value = true
  transferError.value = ''
  try {
    await transferTask(task.id, { toUserId: transferTo.value, comment: transferComment.value.trim() || undefined })
    transferOpen.value = false
    ElMessage.success('已转办')
    await load()
  } catch (error) {
    transferError.value = messageOf(error)
  } finally {
    transferBusy.value = false
  }
}

async function urge(): Promise<void> {
  const task = myTask.value ?? tasks.value.find((item) => item.status === 'PENDING')
  if (!task) return
  try {
    await urgeTask(task.id)
    ElMessage.success('已催办，当前审批人会收到提醒')
  } catch (error) {
    actionError.value = messageOf(error)
  }
}

const withdrawOpen = ref(false)
const withdrawBusy = ref(false)

async function submitWithdraw(): Promise<void> {
  if (!instance.value) return
  withdrawBusy.value = true
  try {
    await withdrawInstance(instance.value.id)
    withdrawOpen.value = false
    ElMessage.success('已撤回审批')
    await load()
  } catch (error) {
    withdrawOpen.value = false
    actionError.value = messageOf(error)
  } finally {
    withdrawBusy.value = false
  }
}

function goRequirements(): void {
  void router.push({ name: 'requirements' })
}
</script>

<template>
  <div class="approval-center">
    <header class="page-head">
      <div class="page-head__left">
        <h1 class="page-head__title">审批中心</h1>
        <span v-if="tab === 'todo'" class="count-badge count-badge--danger">{{ total }}</span>
      </div>
      <div class="page-head__actions">
        <el-select
          v-if="tab !== 'case-reviews'"
          v-model="bizType"
          size="small"
          placeholder="全部单据类型"
          clearable
          style="width: 150px"
        >
          <el-option label="需求" value="REQUIREMENT" />
          <el-option label="版本" value="RELEASE" />
          <el-option label="发布单" value="DEPLOY" />
          <el-option label="缺陷" value="DEFECT" />
        </el-select>
      </div>
    </header>

    <div class="tabs" role="tablist">
      <button
        v-for="item in visibleTabs"
        :key="item.key"
        type="button"
        role="tab"
        class="tab"
        :class="{ 'is-active': tab === item.key }"
        :aria-selected="tab === item.key"
        @click="tab = item.key"
      >
        {{ item.label }}
        <span v-if="item.key === 'todo' && total > 0" class="tab__count">{{ total }}</span>
      </button>
    </div>

    <div v-if="tab !== 'case-reviews'" class="layout">
      <section class="surface list-pane">
        <div v-if="loading" class="skeleton-list">
          <div v-for="index in 5" :key="index" class="skeleton skeleton__row" />
        </div>
        <ErrorState v-else-if="listError" title="待办加载失败" :message="listError" compact @retry="load" />
        <EmptyState
          v-else-if="filtered.length === 0"
          variant="empty"
          :title="tab === 'todo' ? '暂无待办，去需求池看看' : tab === 'done' ? '还没有已办记录' : '你还没有发起过审批'"
          :description="tab === 'todo' ? '当前没有需要你处理的审批；新的待办会出现在这里。' : '处理或发起单据后，记录会出现在这里。'"
          :action-text="tab === 'todo' ? '去需求池' : ''"
          icon="inbox"
          compact
          @action="goRequirements"
        />
        <ul v-else class="todo-list">
          <li v-for="row in filtered" :key="row.taskId">
            <button
              type="button"
              class="todo-item"
              :class="{ 'is-active': selected?.taskId === row.taskId }"
              @click="selectRow(row)"
            >
              <span class="todo-item__top">
                <span class="biz-tag">{{ bizLabel(row) }}</span>
                <span v-if="isOverdue(row)" class="overdue-dot" title="已超 SLA" />
                <span class="todo-item__time">{{ rowTime(row) }}</span>
              </span>
              <span class="todo-item__title">{{ rowTitle(row) }}</span>
              <span class="todo-item__meta">
                <span>{{ rowInitiator(row) }}</span>
                <span>·</span>
                <span>{{ row.nodeName ?? '当前节点' }}</span>
              </span>
            </button>
          </li>
        </ul>
        <div v-if="total > size" class="pager">
          <el-pagination
            layout="prev, pager, next"
            :current-page="page"
            :page-size="size"
            :total="total"
            @current-change="(value: number) => ((page = value), load())"
          />
        </div>
      </section>

      <section class="detail-pane">
        <div v-if="detailLoading" class="surface surface--pad skeleton" style="height: 420px" />
        <ErrorState v-else-if="detailError" title="审批详情加载失败" :message="detailError" @retry="loadDetail(selected?.instanceId ?? null)" />
        <EmptyState
          v-else-if="!detail || !instance"
          variant="empty"
          title="选择左侧一条待办"
          description="右侧会显示审批时间线与被审单据摘要；没有权限的字段不会渲染。"
          icon="inbox"
        />
        <template v-else>
          <header class="detail-head">
            <div class="detail-head__left">
              <span class="biz-tag">{{ bizLabelOf(detail?.document?.bizType) }}</span>
              <span class="detail-code mono">#{{ instance.id }}</span>
              <StatusPill kind="approval" :value="instance.status" />
              <span class="detail-node">当前节点：{{ instance.currentNode ?? '—' }}</span>
            </div>
            <span class="detail-version">流程版本 v{{ instance.definitionVersion }}</span>
          </header>

          <div v-if="actionError" class="action-banner" role="alert">
            <span>{{ actionError }}</span>
            <button type="button" class="link" @click="actionError = ''">关闭</button>
          </div>

          <div class="detail-columns">
            <section class="surface surface--pad panel">
              <h2 class="panel__title">流转记录</h2>
              <ApprovalTimeline :events="timeline" :active="instancePending" />
            </section>

            <aside class="surface surface--pad panel panel--side">
              <h2 class="panel__title">被审单据</h2>
              <p v-if="summaryText(detail.document)" class="doc-summary">{{ summaryText(detail.document) }}</p>
              <dl v-if="documentEntries.length" class="doc-fields">
                <template v-for="entry in documentEntries" :key="entry.label">
                  <dt>{{ entry.label }}</dt>
                  <dd>{{ entry.value }}</dd>
                </template>
              </dl>
              <p class="doc-note">仅展示与审批判断相关的字段；附件区按权限渲染，无权时提示「无权下载附件」。</p>
            </aside>
          </div>

          <footer class="action-bar">
            <span v-if="cannotActReason" class="action-bar__hint">{{ cannotActReason }}</span>
            <template v-if="canReview && instancePending">
              <el-button size="small" :disabled="!myTask" :title="myTask ? '' : '当前节点不由你审批'" @click="openSign">
                加签
              </el-button>
              <el-button size="small" :disabled="!myTask || pendingOthers.length === 0" :title="myTask ? (pendingOthers.length ? '' : '当前节点没有其他审批人可减签') : '当前节点不由你审批'" @click="openRemove">
                减签
              </el-button>
              <el-button size="small" :disabled="!myTask" :title="myTask ? '' : '当前节点不由你审批'" @click="openTransfer">
                转办
              </el-button>
              <el-button size="small" :disabled="!myTask" @click="urge">催办</el-button>
            </template>
            <el-button v-if="canWithdraw" size="small" @click="withdrawOpen = true">撤回</el-button>
            <span class="filter-bar__spacer" />
            <el-button size="small" :disabled="Boolean(cannotActReason)" :title="cannotActReason" @click="openDecision('REJECT')">
              驳回
            </el-button>
            <el-button type="primary" size="small" :disabled="Boolean(cannotActReason)" :title="cannotActReason" @click="openDecision('APPROVE')">
              同意
            </el-button>
          </footer>
        </template>
      </section>
    </div>

    <section v-else class="surface case-review-pane">
      <header class="case-review-head">
        <div>
          <h2 class="case-review-title">用例评审</h2>
          <p class="case-review-sub">只读台账 · 评审结论不产生待办，也不进通用审批流程</p>
        </div>
        <div class="case-review-filters">
          <el-select v-model="caseStatus" size="small" clearable placeholder="全部状态" style="width: 140px" @change="onCaseStatusChange">
            <el-option label="评审中" value="SUBMITTED" />
            <el-option label="已完成" value="DONE" />
            <el-option label="已过期" value="INVALIDATED" />
          </el-select>
        </div>
      </header>

      <ErrorState
        v-if="caseReviewError"
        title="用例评审加载失败"
        :message="caseReviewError"
        compact
        @retry="loadCaseReviews"
      />
      <div v-else-if="caseReviewLoading" class="skeleton-list">
        <div v-for="index in 5" :key="index" class="skeleton skeleton__row" />
      </div>
      <EmptyState
        v-else-if="!caseReviews.length"
        variant="empty"
        title="用例评审不进审批流程"
        description="这里只是只读台账；发起评审与提交结论都在用例库的评审抽屉里完成。"
        icon="file"
        compact
      />
      <div v-else class="cr-table-wrap">
        <table class="cr-table">
          <thead>
            <tr>
              <th class="cr-col-case">用例</th>
              <th class="cr-col-version">版本</th>
              <th class="cr-col-conclusion">结论</th>
              <th class="cr-col-reviewer">评审人</th>
              <th class="cr-col-time">时间</th>
              <th class="cr-col-status">状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in caseReviews" :key="row.id" :class="{ 'is-stale': row.status === 'INVALIDATED' }">
              <td class="cr-col-case">
                <span class="cr-case-title">{{ row.caseTitle || row.caseCode || `用例 #${row.caseId}` }}</span>
                <span v-if="row.caseCode" class="cr-case-code">{{ row.caseCode }}</span>
              </td>
              <td class="cr-col-version"><span class="mono">v{{ row.caseVersion }}</span></td>
              <td class="cr-col-conclusion">{{ caseConclusionLabel(row.conclusion) }}</td>
              <td class="cr-col-reviewer">{{ row.reviewerName || `用户 #${row.reviewerId}` }}</td>
              <td class="cr-col-time">{{ formatDateTime(row.decidedAt || row.createdAt) }}</td>
              <td class="cr-col-status">
                <span :class="caseStatusTone(row.status)">{{ caseStatusLabel(row.status) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="caseReviewTotal > size" class="pager">
          <el-pagination
            layout="prev, pager, next"
            :current-page="page"
            :page-size="size"
            :total="caseReviewTotal"
            @current-change="(value: number) => ((page = value), load())"
          />
        </div>
      </div>
    </section>

    <el-dialog v-model="decisionOpen" :title="decisionKind === 'APPROVE' ? '同意审批' : '驳回审批'" width="460px" append-to-body>
      <p v-if="decisionKind === 'REJECT'" class="dialog-impact">
        驳回后单据退回至「已提报」，发起人修改后可重新提交；意见会随通知发出。
      </p>
      <el-form label-position="top" size="small">
        <el-form-item :label="decisionKind === 'APPROVE' ? '意见（可选）' : '驳回意见（必填）'" :required="decisionKind === 'REJECT'">
          <el-input
            v-model="decisionComment"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            :placeholder="decisionKind === 'APPROVE' ? '可以补充说明' : '说明为什么退回，至少 2 个字'"
          />
        </el-form-item>
      </el-form>
      <p v-if="decisionError" class="dialog-error">{{ decisionError }}</p>
      <template #footer>
        <el-button @click="decisionOpen = false">取消</el-button>
        <el-button
          :type="decisionKind === 'REJECT' ? 'danger' : 'primary'"
          :loading="decisionBusy"
          @click="submitDecision"
        >
          {{ decisionKind === 'APPROVE' ? '确认同意' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="signOpen" title="加签" width="480px" append-to-body>
      <el-form label-position="top" size="small">
        <el-form-item label="加签位置">
          <el-radio-group v-model="signType">
            <el-radio value="BEFORE">前加签（先于你审批）</el-radio>
            <el-radio value="AFTER">后加签（你通过后继续）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="加签人" required>
          <el-select v-model="signUserIds" multiple filterable placeholder="选择审批人" style="width: 100%">
            <el-option v-for="user in userOptions" :key="user.id" :label="user.name" :value="user.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明（可选）">
          <el-input v-model="signComment" type="textarea" :rows="2" maxlength="120" placeholder="为什么需要加签" />
        </el-form-item>
      </el-form>
      <p v-if="signError" class="dialog-error">{{ signError }}</p>
      <template #footer>
        <el-button @click="signOpen = false">取消</el-button>
        <el-button type="primary" :loading="signBusy" @click="submitSign">确认加签</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="removeOpen" title="减签" width="460px" append-to-body>
      <el-form label-position="top" size="small">
        <el-form-item label="减签对象" required>
          <el-select v-model="removeTarget" placeholder="选择要移除的审批人" style="width: 100%">
            <el-option
              v-for="task in pendingOthers"
              :key="task.id"
              :label="task.assignee?.name ?? dict.userName((task as ApprovalTaskRow & { approverId?: number }).approverId)"
              :value="task.assignee?.id ?? (task as ApprovalTaskRow & { approverId?: number }).approverId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="减签原因" required>
          <el-input v-model="removeReason" type="textarea" :rows="3" maxlength="120" placeholder="减签原因会记录在审批时间线并通知被减签人" />
        </el-form-item>
      </el-form>
      <p class="dialog-note">减签最后一位待办会被拒绝（409），避免审批卡死。</p>
      <p v-if="removeError" class="dialog-error">{{ removeError }}</p>
      <template #footer>
        <el-button @click="removeOpen = false">取消</el-button>
        <el-button type="danger" :loading="removeBusy" @click="submitRemove">确认减签</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="transferOpen" title="转办" width="460px" append-to-body>
      <el-form label-position="top" size="small">
        <el-form-item label="转办给" required>
          <el-select v-model="transferTo" filterable placeholder="选择接手的审批人" style="width: 100%">
            <el-option v-for="user in userOptions" :key="user.id" :label="user.name" :value="user.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明（可选）">
          <el-input v-model="transferComment" type="textarea" :rows="2" maxlength="120" />
        </el-form-item>
      </el-form>
      <p v-if="transferError" class="dialog-error">{{ transferError }}</p>
      <template #footer>
        <el-button @click="transferOpen = false">取消</el-button>
        <el-button type="primary" :loading="transferBusy" @click="submitTransfer">确认转办</el-button>
      </template>
    </el-dialog>

    <ConfirmDialog
      v-model="withdrawOpen"
      title="撤回审批"
      description="撤回后审批实例终止，单据回到可编辑状态。"
      :impact="['发起人与当前审批人都会收到撤回通知', '已产生的审批记录保留在时间线，不可删除']"
      reason-label="撤回原因"
      :reason-required="false"
      confirm-text="确认撤回"
      :loading="withdrawBusy"
      @confirm="submitWithdraw"
      @cancel="withdrawOpen = false"
    />
  </div>
</template>

<style scoped>
.page-head__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text-title);
}
.approval-center {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--border);
}
.tab {
  position: relative;
  border: none;
  background: none;
  padding: 8px 12px 9px;
  font-size: 13px;
  color: var(--text-body);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: color var(--t-color) var(--ease), border-color var(--t-color) var(--ease);
}
.tab:hover {
  color: var(--color-primary);
}
.tab:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
.tab.is-active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}
.tab__count {
  margin-left: 6px;
  font-size: 11px;
  padding: 0 6px;
  border-radius: var(--r-pill);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-variant-numeric: tabular-nums;
}
.layout {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}
.list-pane {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.skeleton-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
}
.todo-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 620px;
  overflow-y: auto;
}
.todo-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 100%;
  padding: 10px 12px;
  border: none;
  border-bottom: 1px solid var(--divider);
  background: transparent;
  text-align: left;
  cursor: pointer;
  border-left: 2px solid transparent;
  transition: background var(--t-color) var(--ease), border-color var(--t-color) var(--ease);
}
.todo-item:hover {
  background: var(--bg-hover);
}
.todo-item:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
.todo-item.is-active {
  background: var(--bg-selected);
  border-left-color: var(--color-primary);
}
.todo-item__top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.biz-tag {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: var(--r-cell);
  background: var(--color-info-bg);
  color: var(--info-ink);
}
.overdue-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-warning);
  flex: none;
}
.todo-item__time {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-muted-aa);
  font-variant-numeric: tabular-nums;
}
.todo-item__title {
  font-size: 13px;
  color: var(--text-title);
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.todo-item__meta {
  display: flex;
  gap: 4px;
  font-size: 11px;
  color: var(--text-muted-aa);
}
.pager {
  padding: 8px;
  border-top: 1px solid var(--divider);
}
.detail-pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  flex-wrap: wrap;
}
.detail-head__left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.detail-code {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.detail-node {
  font-size: 12px;
  color: var(--text-body);
}
.detail-version {
  font-size: 11px;
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
.detail-columns {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(260px, 0.75fr);
  gap: 12px;
  align-items: start;
}
.panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.panel__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.doc-summary {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-body);
}
.doc-fields {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 6px 10px;
  margin: 0;
  font-size: 12px;
}
.doc-fields dt {
  color: var(--text-muted-aa);
}
.doc-fields dd {
  margin: 0;
  color: var(--text-body);
  overflow-wrap: anywhere;
}
.doc-note {
  margin: 0;
  padding-top: 8px;
  border-top: 1px solid var(--divider);
  font-size: 11px;
  color: var(--text-muted-aa);
  line-height: 1.6;
}
.action-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-card);
  background: var(--bg-card);
  box-shadow: 0 -2px 8px rgba(31, 35, 41, 0.04);
}
.action-bar__hint {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.link {
  border: none;
  background: none;
  padding: 0;
  font-size: inherit;
  color: var(--color-primary);
  cursor: pointer;
}
.mono {
  font-family: var(--font-mono);
}
.dialog-impact {
  margin: 0 0 10px;
  padding: 8px 10px;
  border-left: 2px solid var(--color-warning);
  background: var(--color-warning-bg);
  font-size: 12px;
  color: var(--warning-ink);
  line-height: 1.6;
}
.dialog-note {
  margin: 0;
  font-size: 11px;
  color: var(--text-muted-aa);
}
.dialog-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger-ink);
}
@media (max-width: 1100px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .detail-columns {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* 用例评审：只读台账，无待办语义 */
.case-review-pane {
  padding: 14px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 420px;
}

.case-review-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--divider);
  padding-bottom: 10px;
}

.case-review-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-title);
}

.case-review-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}

.cr-table-wrap {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cr-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.cr-table th {
  text-align: left;
  font-weight: 500;
  font-size: 12px;
  color: var(--text-muted-aa);
  background: var(--bg-table-head);
  padding: 8px 10px;
  border-bottom: 1px solid var(--border);
}

.cr-table td {
  padding: 9px 10px;
  border-bottom: 1px solid var(--divider);
  color: var(--text-body);
  vertical-align: top;
}

.cr-table tbody tr {
  transition: background var(--t-color) ease;
}

.cr-table tbody tr:hover {
  background: var(--bg-hover);
}

.cr-table tbody tr.is-stale {
  background: #fffdf8;
  box-shadow: inset 3px 0 0 var(--color-warning);
}

.cr-col-case {
  width: 34%;
}

.cr-col-version {
  width: 72px;
}

.cr-col-conclusion {
  width: 96px;
}

.cr-col-reviewer {
  width: 140px;
}

.cr-col-time {
  width: 150px;
}

.cr-col-status {
  width: 96px;
}

.cr-case-title {
  display: block;
  color: var(--text-title);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cr-case-code {
  display: block;
  margin-top: 2px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-muted-aa);
}

.cr-tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: var(--r-pill);
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
}

.cr-tag--open {
  background: var(--color-primary-light);
  color: var(--color-primary);
}

.cr-tag--done {
  background: var(--color-success-bg);
  color: var(--success-ink);
}

.cr-tag--stale {
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
</style>
