<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { assignVendor, deliverRequirement, getApprovalInstance, getRequirement, actOnRequirement, listApprovalMine, listRequirementChanges } from '@/api/req'
import { listDictionaryUsers, listDictionaryVendors } from '@/api/dictionaries'
import type { DictionaryUser, DictionaryVendor } from '@/api/dictionaries'
import type {
  ApprovalInstanceDetail,
  ApprovalTimelineEvent,
  ApprovalTodoRow,
  EstimateBlock,
  RequirementDetail,
  RequirementDetailResponse,
} from '@/api/types'
import type { ChangeType } from '@/api/req'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import ErrorState from '@/components/ErrorState.vue'
import PriorityDot from '@/components/PriorityDot.vue'
import ScopeChip from '@/components/ScopeChip.vue'
import StatusPill from '@/components/StatusPill.vue'
import { usePermission } from '@/composables/usePermission'
import {
  ESTIMATE_EVENT,
  estimateGateTip,
  estimateStatusOf,
  gateActionOfType,
} from '@/views/req/estimate'
import ApprovalTab from '@/views/req/tabs/ApprovalTab.vue'
import ChildrenTab from '@/views/req/tabs/ChildrenTab.vue'
import DefectsTab from '@/views/req/tabs/DefectsTab.vue'
import IntegrationTab from '@/views/req/tabs/IntegrationTab.vue'
import DocumentsTab from '@/views/req/tabs/DocumentsTab.vue'
import OverviewTab from '@/views/req/tabs/OverviewTab.vue'
import TasksTab from '@/views/req/tabs/TasksTab.vue'
import TestingTab from '@/views/req/tabs/TestingTab.vue'
import TraceTab from '@/views/req/tabs/TraceTab.vue'
import ChangeTab from '@/views/req/tabs/ChangeTab.vue'
import ChangeCreateDrawer from '@/components/req/ChangeCreateDrawer.vue'
import RequirementEditDrawer from '@/components/req/RequirementEditDrawer.vue'
import { changePermissionsOf, changeTypeOfField, fieldLabel } from '@/views/req/change'
import {
  canOpenEdit,
  editDisabledHint,
  editDisabledReason,
  editabilityOf,
  reasonText,
} from '@/views/req/editability'
import DecomposeDialog from '@/views/req/DecomposeDialog.vue'
import {
  ACTION_IMPACT,
  DANGER_ACTIONS,
  messageOf,
  metaOf,
  normalizeRequirementDetail,
  normalizeTimeline,
  typeLabel,
  usePlatformDict,
} from '@/views/req/platform'
import { useAuthStore } from '@/stores/auth'
import { useDictStore } from '@/stores/dict'

/**
 * P2 需求详情（7 tab）。动作区只认服务端 allowedActions（唯一真相，AC-3）：
 * 按钮不做本地权限二次过滤；403 就地 toast 并刷新详情同步按钮，不跳全局 403 页；
 * 409 用常驻条展示，不弹 toast、不假装成功。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const dict = useDictStore()

const id = computed(() => Number(route.params.id))
const detail = ref<RequirementDetail | null>(null)
const loading = ref(false)
const loadError = ref('')
const errorStatus = ref<number | undefined>()

const approval = ref<ApprovalInstanceDetail | null>(null)
const approvalLoading = ref(false)
const approvalError = ref('')
/** 403 = 无权看审批进度：页签内降级，绝不进 approvalError（那会渲染成"加载失败+重试"） */
const approvalForbidden = ref(false)
const estimateForbidden = ref(false)

const TAB_KEYS = ['overview', 'children', 'tasks', 'testing', 'defects', 'approval', 'change', 'trace', 'integration', 'documents'] as const
type TabKey = (typeof TAB_KEYS)[number]
const activeTab = ref<TabKey>(TAB_KEYS.includes(String(route.query.tab) as TabKey) ? (String(route.query.tab) as TabKey) : 'overview')
const tabCounts = reactive({ children: 0, tasks: 0, changePending: 0 })

/**
 * 常驻动作错误条（400/409）：`code` 决定文案与语气；`reasonCode`/`field` 只由
 * `FIELD_LOCKED` 带（TECHNICAL §3.4）；`changeFields` 供「发起变更」预填。
 */
const actionBanner = ref<{
  text: string
  code?: string
  reasonCode?: string
  field?: string
  changeFields?: string[]
} | null>(null)
const busy = ref(false)

// —— 变更单入口（r40）：一个 720 抽屉，四个触发点共用 ——
const changeDrawerOpen = ref(false)
const changePresetType = ref<ChangeType | null>(null)
const changePresetFields = ref<string[]>([])
const changeApprovedCount = ref(0)
const editOpen = ref(false)
const changeTabs = ref<InstanceType<typeof ChangeTab> | null>(null)

const changePermissions = computed(() => changePermissionsOf(detail.value))
/** 发起变更：渲染但无权限时禁用 + 原因（DESIGN §4.5），应用权限不在这里复刻 */
const changeRequestDenied = computed(() => !changePermissions.value.changeRequest)

// —— 可编辑性（r43）：矩阵只回答「能不能直接改」，页面不做本地推断 ——
/** 编辑入口：三数组里有任一 EDIT 才可点；数组缺失 = fail-closed 全只读（AC-6） */
const editAllowed = computed(() => canOpenEdit(detail.value))
const editHint = computed(() => editDisabledHint(detail.value))
const editDeniedReason = computed(() => editDisabledReason(detail.value))
/** 三数组整块缺失时给一条常驻提示，而不是把按钮悄悄变灰让人猜（DESIGN §0.4） */
const editRulesMissing = computed(() => detail.value != null && editabilityOf(detail.value) === null)

/** 横幅前缀：FIELD_LOCKED 与 CHANGE_REQUIRED 是两类事，不能共用一句（DESIGN §3.4） */
const bannerPrefix = computed(() => {
  const code = actionBanner.value?.code
  if (code === 'FIELD_LOCKED') return '字段已锁定：'
  if (code === 'CHANGE_REQUIRED') return '需要走变更单：'
  return '提交被拒绝：'
})
/** FIELD_LOCKED = info（变更单也救不了），CHANGE_REQUIRED = warning（有 CTA） */
const bannerToneClass = computed(() => {
  const code = actionBanner.value?.code
  if (code === 'FIELD_LOCKED') return 'action-banner--locked'
  if (code === 'CHANGE_REQUIRED') return 'action-banner--change'
  return 'action-banner--danger'
})

function openChangeDrawer(type?: ChangeType | null, fields?: string[]): void {
  changePresetType.value = type ?? null
  changePresetFields.value = fields ?? []
  changeDrawerOpen.value = true
}

function openChangeFromConflict(): void {
  const fields = actionBanner.value?.changeFields ?? []
  const type = fields.length ? changeTypeOfField(fields[0]) : null
  openChangeDrawer(type, fields)
}

function onChangeCounts(counts: { pending: number; approved: number }): void {
  tabCounts.changePending = counts.pending
  changeApprovedCount.value = counts.approved
}

async function onEditRequestChange(payload: { type: ChangeType | null; fields: string[] }): Promise<void> {
  editOpen.value = false
  openChangeDrawer(payload.type, payload.fields)
}

/** 概览的字段级「发起变更」：按字段推变更单类型并只预填这一个字段（AC-11） */
function onFieldRequestChange(field: string): void {
  openChangeDrawer(changeTypeOfField(field), [field])
}

/** 编辑按钮的守卫：禁用态下 click 仍可能被键盘/测试触发，首行再判一次 */
function guardEditOpen(): void {
  if (!editAllowed.value) return
  editOpen.value = true
}

async function onChangeCreated(): Promise<void> {
  await refreshAll()
  void changeTabs.value?.refresh()
}

const tabs = computed(() => [
  { key: 'overview', label: '概览' },
  { key: 'children', label: `子需求${tabCounts.children ? ` ${tabCounts.children}` : ''}` },
  { key: 'tasks', label: `任务${tabCounts.tasks ? ` ${tabCounts.tasks}` : ''}` },
  { key: 'testing', label: '测试' },
  { key: 'defects', label: '缺陷' },
  { key: 'approval', label: '审批' },
  { key: 'change', label: '变更', badge: tabCounts.changePending },
  { key: 'trace', label: '追溯' },
  { key: 'integration', label: '集成' },
  { key: 'documents', label: '文档' },
])

const allowedActions = computed(() => detail.value?.allowedActions ?? [])
// 「提交审批」「撤回」固定在概览页底部的动作条，头部不再重复渲染同一个动作入口
const footerActionKeys = new Set(['submit', 'withdraw'])
const headerActions = computed(() => allowedActions.value.filter((action) => !footerActionKeys.has(action)))
const approvalTimeline = computed(() => normalizeTimeline(approval.value?.timeline))

// —— 工时评估单（r26，AC-13） ——
// 动作行口径「渲染看权限、禁用看门」：受门动作在门未过（或无权限）时**仍渲染**但 disabled，
// 并给出一句原因；有权限且门已过、只是当前状态不允许时保持既有行为（隐藏，不编文案）。
const { canAction } = usePermission()
const estimate = computed<EstimateBlock | null>(() => detail.value?.estimate ?? null)
const estimateStatus = computed(() => estimateStatusOf(estimate.value))
const gateOpen = computed(() => estimateStatus.value === 'APPROVED')

/**
 * 评估事件并入「最近动态」（AC-3/AC-4，US-2）：后端把 ESTIMATE_* 写在审计表，
 * 需求页没有动态查询接口，因此用详情里的 estimate 快照合成事件——驳回原因作为 comment 展示，
 * 研发不必再进审批页找「为什么被驳回」。
 */
const { userName } = usePlatformDict()
const estimateTimeline = computed<ApprovalTimelineEvent[]>(() => {
  const block = estimate.value
  if (!block?.instanceId) return []
  const status = estimateStatus.value
  const submitter = userName(detail.value?.ownerId)
  const approver = block.approverId != null ? userName(block.approverId) : '审批人'
  const events: ApprovalTimelineEvent[] = []
  if (block.submittedAt) {
    events.push({ action: ESTIMATE_EVENT.SUBMIT, operator: submitter, at: block.submittedAt })
  }
  if (!block.decidedAt) return events
  if (status === 'APPROVED' || status === 'STALE') {
    events.push({ action: ESTIMATE_EVENT.APPROVED, operator: approver, at: block.decidedAt })
  } else if (status === 'REJECTED') {
    events.push({
      action: ESTIMATE_EVENT.REJECTED,
      operator: approver,
      at: block.decidedAt,
      comment: block.reason ?? null,
    })
  } else if (status === 'SUPERSEDED') {
    events.push({ action: ESTIMATE_EVENT.SUPERSEDED, operator: approver, at: block.decidedAt })
  }
  return events
})

/** 审批时间线 + 评估事件，按时间升序（从上到下读） */
const timeline = computed<ApprovalTimelineEvent[]>(() => {
  const timeOf = (event: ApprovalTimelineEvent): number => {
    const parsed = Date.parse(event.at)
    return Number.isNaN(parsed) ? 0 : parsed
  }
  return [...approvalTimeline.value, ...estimateTimeline.value].sort((a, b) => timeOf(a) - timeOf(b))
})
const gateAction = computed(() => gateActionOfType(detail.value?.type))
const pinnedGateAction = computed<{ action: string; permitted: boolean } | null>(() => {
  const action = gateAction.value
  if (!action) return null
  // 已在 allowedActions 里的动作照旧由常规按钮渲染，不重复出第二个入口
  if (headerActions.value.includes(action)) return null
  const permitted = canAction(`req.action.${action}`)
  if (permitted && gateOpen.value) return null
  return { action, permitted }
})
const gateActionTip = computed(() => {
  const pinned = pinnedGateAction.value
  if (!pinned) return ''
  // 权限原因与门原因物理分离：两种文案由不同来源生成，绝不互相拼接
  return pinned.permitted ? estimateGateTip(estimateStatus.value) : `需要权限：req.action.${pinned.action}`
})

/** 被门钉住的按钮点击不产生任何请求、不改 URL、不落全局 403 页 —— 只有提示可读。 */
function onPinnedClick(): void {
  return
}

function actionLabel(action: string): string {
  return metaOf(action).label
}

function isDanger(action: string): boolean {
  return DANGER_ACTIONS.has(action) || !!metaOf(action).danger
}

async function load(): Promise<void> {
  if (!Number.isFinite(id.value) || id.value <= 0) return
  loading.value = true
  loadError.value = ''
  errorStatus.value = undefined
  try {
    const raw: RequirementDetailResponse = await getRequirement(id.value)
    // estimate 是 Detail 顶层字段（与 view 平级），normalizeRequirementDetail 只摊平 view，
    // 这里补一次合并，否则评估卡片永远等于 null
    detail.value = { ...normalizeRequirementDetail(raw), estimate: raw.estimate ?? null }
    tabCounts.children = detail.value.children.length
  } catch (error) {
    detail.value = null
    loadError.value = messageOf(error)
    errorStatus.value = (error as { status?: number }).status
  } finally {
    loading.value = false
  }
}

async function loadApproval(): Promise<void> {
  if (!detail.value) return
  approvalLoading.value = true
  approvalError.value = ''
  approvalForbidden.value = false
  try {
    const direct = (detail.value as unknown as { approvalInstanceId?: number }).approvalInstanceId
    if (direct != null) {
      approval.value = await getApprovalInstance(direct, { silentForbidden: true })
      return
    }
    const mine = await listApprovalMine({ page: 1, size: 50 }, { silentForbidden: true })
    const hit = mine.items.find(
      (row: ApprovalTodoRow) => Number(row.bizId) === id.value && String(row.bizType).toUpperCase().includes('REQ'),
    )
    approval.value = hit ? await getApprovalInstance(hit.instanceId, { silentForbidden: true }) : null
  } catch (error) {
    approval.value = null
    const status = (error as { status?: number }).status
    approvalForbidden.value = status === 403
    approvalError.value = approvalForbidden.value ? '' : messageOf(error)
  } finally {
    approvalLoading.value = false
  }
}

async function refreshAll(): Promise<void> {
  await load()
  await Promise.all([loadApproval(), loadEstimateApproval(), loadChangeCounts()])
}

/**
 * 变更角标与 APPROVED 横幅的数据源：与当前 Tab 无关，页头就要正确，
 * 因此父级自己拉两次 count（Tab 内的列表另有自己的请求，口径一致）。
 */
async function loadChangeCounts(): Promise<void> {
  try {
    const [pending, approved] = await Promise.all([
      listRequirementChanges(id.value, { status: 'PENDING', page: 1, size: 1 }),
      listRequirementChanges(id.value, { status: 'APPROVED', page: 1, size: 1 }),
    ])
    tabCounts.changePending = pending.total ?? 0
    changeApprovedCount.value = approved.total ?? 0
  } catch {
    // 角标是辅助信息：拉不到不显示，也不打断页面
    tabCounts.changePending = 0
    changeApprovedCount.value = 0
  }
}

// —— 工时评估单实例（AC-6）：按 detail.estimate.instanceId 直取 ——
// 不能用 listApprovalMine() 的 bizType.includes('REQ') 那条路径：WBS_ESTIMATE 不含 REQ，
// 旧过滤永远匹配不到这张单。审批动作仍只在审批中心，这里只读。
const estimateInstance = ref<ApprovalInstanceDetail | null>(null)
const estimateLoading = ref(false)
const estimateError = ref('')

async function loadEstimateApproval(): Promise<void> {
  const instanceId = estimate.value?.instanceId
  if (instanceId == null) {
    estimateInstance.value = null
    estimateError.value = ''
    estimateForbidden.value = false
    estimateLoading.value = false
    return
  }
  estimateLoading.value = true
  estimateError.value = ''
  estimateForbidden.value = false
  try {
    estimateInstance.value = await getApprovalInstance(instanceId, { silentForbidden: true })
  } catch (error) {
    estimateInstance.value = null
    const status = (error as { status?: number }).status
    estimateForbidden.value = status === 403
    estimateError.value = estimateForbidden.value ? '' : messageOf(error)
  } finally {
    estimateLoading.value = false
  }
}

/** 「查看评估单」：切到审批页签，把只读评估块滚进视野并聚焦其标题。 */
async function openEstimateApproval(): Promise<void> {
  setTab('approval')
  await nextTick()
  const block = document.querySelector<HTMLElement>('[data-test="estimate-block"]')
  if (!block) return
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  block.scrollIntoView({ block: 'start', behavior: reduce ? 'auto' : 'smooth' })
  block.querySelector<HTMLElement>('.estimate-block__title')?.focus({ preventScroll: true })
}

function setTab(tab: TabKey): void {
  activeTab.value = tab
  void router.replace({ query: { ...route.query, tab } })
}

function handleActionError(error: unknown): void {
  const status = (error as { status?: number }).status
  const code = (error as { code?: string }).code
  if (status === 409) {
    // 评估门的 409 与其它 409 共用既有分支（常驻 banner + 就地刷新），
    // 但把「哪张评估单挡住了」补进文案，避免只看到一句泛化报错
    const gateSuffix = code === 'ESTIMATE_NOT_APPROVED' ? `（${estimateGateTip(estimateStatus.value)}）` : ''
    const detailBag = (error as { detail?: { fields?: unknown; reasonCode?: unknown; field?: unknown } }).detail
    const declaredFields = Array.isArray(detailBag?.fields)
      ? detailBag.fields.filter((item): item is string => typeof item === 'string')
      : []
    // r43：两类 409 话术不同。FIELD_LOCKED = 这个字段矩阵上根本不允许改（终态/不可变/父汇总），
    // 连「发起变更」都不该给——给了也是必然被拒的假 CTA（AC-12/AC-13）。
    if (code === 'FIELD_LOCKED') {
      const reasonCode = typeof detailBag?.reasonCode === 'string' ? detailBag.reasonCode : undefined
      const field = typeof detailBag?.field === 'string' ? detailBag.field : undefined
      actionBanner.value = {
        text: field ? `${reasonText(reasonCode)}：${fieldLabel(field)}` : reasonText(reasonCode),
        code,
        reasonCode,
        field,
      }
      void refreshAll()
      return
    }
    actionBanner.value = {
      text: `${messageOf(error)}${gateSuffix}`,
      code,
      changeFields: code === 'CHANGE_REQUIRED' && declaredFields.length ? declaredFields : undefined,
    }
    void refreshAll()
    return
  }
  // 403：按钮已过期（动作矩阵拒绝 / 域越界）——就地提示并重拉详情，按钮随 allowedActions 收敛
  if (status === 403) {
    ElMessage.warning('无权执行该操作，请联系产品经理')
    void refreshAll()
    return
  }
  ElMessage.error(messageOf(error))
}

async function runSimpleAction(action: string, comment?: string): Promise<void> {
  busy.value = true
  actionBanner.value = null
  try {
    await actOnRequirement(id.value, { action, comment })
    ElMessage.success(`${actionLabel(action)}成功`)
    await refreshAll()
  } catch (error) {
    handleActionError(error)
  } finally {
    busy.value = false
  }
}

// —— 危险动作确认 ——
const confirmOpen = ref(false)
const pendingAction = ref('')
const confirmTitle = computed(() => `确认${actionLabel(pendingAction.value)}？`)
const confirmImpact = computed(() => ACTION_IMPACT[pendingAction.value] ?? [])

function requestAction(action: string): void {
  if (action === 'deliver') {
    void openDeliver()
    return
  }
  if (action === 'assign') {
    void openAssign()
    return
  }
  if (isDanger(action)) {
    pendingAction.value = action
    confirmOpen.value = true
    return
  }
  void runSimpleAction(action)
}

async function onConfirmed(payload: { reason: string; acknowledged: boolean }): Promise<void> {
  const action = pendingAction.value
  confirmOpen.value = false
  await runSimpleAction(action, payload.reason)
}

// —— 提交交付 ——
const deliverOpen = ref(false)
const deliverMr = ref('')
const deliverEstimate = ref<number | undefined>()
const deliverActual = ref<number | undefined>()
const deliverOwner = ref<number | undefined>()
const deliverError = ref('')
const users = ref<DictionaryUser[]>([])

async function ensureUsers(): Promise<void> {
  if (users.value.length) return
  try {
    users.value = await listDictionaryUsers()
  } catch {
    users.value = []
  }
}

async function openDeliver(): Promise<void> {
  deliverOpen.value = true
  deliverError.value = ''
  deliverMr.value = detail.value?.delivery?.mrUrl ?? ''
  deliverEstimate.value = detail.value?.delivery?.estimateDays ?? detail.value?.estimateDays ?? undefined
  deliverActual.value = detail.value?.delivery?.actualDays ?? detail.value?.actualDays ?? undefined
  deliverOwner.value = detail.value?.ownerId ?? undefined
  await ensureUsers()
}

async function submitDeliver(): Promise<void> {
  if (!deliverEstimate.value || deliverEstimate.value <= 0) {
    deliverError.value = '预估人天必须大于 0（发布准入第 4 项依赖它）'
    return
  }
  if (detail.value?.type === 'RD' && deliverMr.value.trim().length === 0) {
    deliverError.value = '研发需求必须填写 MR 链接（发布准入第 3 项依赖它）'
    return
  }
  busy.value = true
  try {
    await deliverRequirement(id.value, {
      estimateDays: deliverEstimate.value,
      actualDays: deliverActual.value,
      mrUrl: deliverMr.value.trim(),
      ownerUserId: deliverOwner.value,
    })
    ElMessage.success('交付信息已登记')
    deliverOpen.value = false
    await refreshAll()
  } catch (error) {
    deliverError.value = messageOf(error)
  } finally {
    busy.value = false
  }
}

// —— 转派 / 分配厂商 ——
const assignOpen = ref(false)
const assignVendorId = ref<number | undefined>()
const assignOwner = ref<number | undefined>()
const assignReason = ref('')
const assignError = ref('')
const vendors = ref<DictionaryVendor[]>([])

async function openAssign(): Promise<void> {
  assignOpen.value = true
  assignError.value = ''
  assignReason.value = ''
  assignVendorId.value = detail.value?.vendorId ?? undefined
  assignOwner.value = detail.value?.ownerId ?? undefined
  await ensureUsers()
  if (vendors.value.length === 0) {
    try {
      vendors.value = await listDictionaryVendors()
    } catch {
      vendors.value = []
    }
  }
}

async function submitAssign(): Promise<void> {
  if (assignVendorId.value == null) {
    assignError.value = '请选择承接厂商'
    return
  }
  if (assignReason.value.trim().length < 2) {
    assignError.value = '转派必须填写原因（会写入审计日志）'
    return
  }
  busy.value = true
  try {
    await assignVendor(id.value, {
      vendorId: assignVendorId.value,
      ownerUserId: assignOwner.value,
      reason: assignReason.value.trim(),
    })
    ElMessage.success('已转派，厂商范围随之更新')
    assignOpen.value = false
    await refreshAll()
  } catch (error) {
    assignError.value = messageOf(error)
  } finally {
    busy.value = false
  }
}

// —— 拆解 ——
const decomposeOpen = ref(false)

function onDecomposed(): void {
  void load()
  setTab('children')
}

const statusLabel = computed(() => (detail.value ? dict.label('requirement', detail.value.status) : ''))
const canSubmit = computed(() => allowedActions.value.includes('submit'))
// 底部提示随状态更新，避免「已提交审批」后还提示「提交审批前请确认…」
const footerHint = computed(() => {
  switch (detail.value?.status) {
    case 'SUBMITTED':
      return '下一步动作由状态机决定；提交审批前请确认域、厂商与预估人天已填写。'
    case 'APPROVING':
      return '已提交审批，等待产品经理在审批中心通过；通过前可在本页撤回。'
    case 'PENDING_REVIEW':
      return '审批已通过，等待产品经理评审入池；评审结果会写入流转记录。'
    default:
      return '下一步动作由状态机决定。'
  }
})

watch(
  () => route.params.id,
  () => {
    void refreshAll()
  },
)

onMounted(async () => {
  await load()
  await Promise.all([loadApproval(), loadEstimateApproval()])
})
</script>

<template>
  <div class="detail">
    <ErrorState
      v-if="loadError && !loading"
      :title="errorStatus === 404 ? '需求不存在' : '需求加载失败'"
      :message="loadError"
      :status="errorStatus"
      @retry="refreshAll"
    />

    <div v-else-if="loading && !detail" class="skeleton skeleton__row" />

    <template v-else-if="detail">
      <header class="detail__head">
        <nav class="detail__crumbs" aria-label="面包屑">
          <RouterLink to="/requirements">需求池</RouterLink>
          <span>/</span>
          <span>{{ typeLabel(detail.type) }}</span>
          <span>/</span>
          <span>{{ statusLabel }}</span>
        </nav>
        <div class="detail__title-row">
          <div class="detail__title-main">
            <span class="detail__code mono">{{ detail.code }}</span>
            <h1 class="detail__title">{{ detail.title }}</h1>
            <StatusPill kind="requirement" :value="detail.status" />
            <PriorityDot :value="detail.priority" />
            <ScopeChip
              v-if="auth.scope?.label"
              :label="auth.scope.label"
              :scope="auth.scope.type ?? undefined"
              tone="info"
            />
          </div>
          <div class="detail__actions">
            <el-button
              v-for="action in headerActions"
              :key="action"
              size="small"
              :type="action === 'submit' ? 'primary' : 'default'"
              :disabled="busy"
              @click="requestAction(action)"
            >
              {{ actionLabel(action) }}
            </el-button>
            <!-- 变更入口（r40）：发起=渲染但无权限时禁用+原因；编辑抽屉里被锁分组另有一个入口 -->
            <el-button
              class="btn--disabled-reason"
              size="small"
              data-test="change-request"
              :disabled="changeRequestDenied"
              :title="changeRequestDenied ? '无发起变更权限（需要需求管理权限）' : '改动内容/人天/版本/父需求都走变更单'"
              @click="openChangeDrawer()"
            >
              发起变更
            </el-button>
            <el-button
              class="btn--disabled-reason"
              size="small"
              data-test="requirement-edit"
              :disabled="!editAllowed"
              :title="editDeniedReason"
              @click="guardEditOpen"
            >
              编辑
            </el-button>
            <!-- 只变灰的按钮等于让用户自己猜；禁用时必须紧邻一句原因（DESIGN §3.1） -->
            <span
              v-if="!editAllowed"
              class="detail__edit-hint"
              data-test="edit-disabled-hint"
              aria-live="polite"
            >{{ editHint }}</span>
            <!-- 受工时评估门约束的动作：无条件渲染、门未过时 disabled（r25 三色），
                 点击不发请求不改 URL；原因由 estimateGateTip / 权限前缀各自生成 -->
            <el-button
              v-if="pinnedGateAction"
              class="btn--disabled-reason"
              :data-test="`action-${pinnedGateAction.action}`"
              size="small"
              disabled
              :title="gateActionTip"
              @click="onPinnedClick"
            >
              {{ actionLabel(pinnedGateAction.action) }}
            </el-button>
            <span v-if="pinnedGateAction" class="detail__gate-hint" data-test="gate-hint" aria-live="polite">
              {{ gateActionTip }}
            </span>
            <span v-if="allowedActions.length === 0 && !pinnedGateAction" class="detail__no-action">
              当前状态无可执行动作
            </span>
          </div>
        </div>
      </header>

      <!-- 三数组整块缺失（旧后端/灰度窗口）：明说「按只读处理」，不静默全灰（DESIGN §0.4） -->
      <p v-if="editRulesMissing" class="edit-rules-missing" role="status" data-test="edit-rules-missing">
        未获取到可编辑性规则，字段暂按只读处理（刷新页面可重试）
      </p>

      <div
        v-if="actionBanner"
        class="action-banner"
        :class="bannerToneClass"
        role="alert"
        data-test="action-banner"
      >
        <div>
          <strong>{{ bannerPrefix }}</strong>{{ actionBanner.text }}
          <span v-if="actionBanner.code" class="action-banner__code">{{ actionBanner.code }}</span>
        </div>
        <div class="action-banner__actions">
          <el-button
            v-if="actionBanner.code === 'CHANGE_REQUIRED'"
            type="primary"
            size="small"
            data-test="change-banner-cta"
            @click="openChangeFromConflict"
          >
            发起变更（已预填）
          </el-button>
          <el-button text size="small" @click="actionBanner = null">关闭</el-button>
        </div>
      </div>

      <div v-if="changeApprovedCount > 0" class="change-approved-banner" data-test="change-approved-banner">
        <span>
          <strong>{{ changeApprovedCount }} 张变更单已通过审批，尚未应用</strong>
          —— 需求字段仍是申请前的值，进「变更」页显式应用后才写入。
        </span>
        <button type="button" class="change-approved-banner__link" @click="setTab('change')">去查看</button>
      </div>

      <nav class="detail__tabs" role="tablist">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          role="tab"
          class="detail__tab"
          :class="{ 'is-active': activeTab === tab.key }"
          :aria-selected="activeTab === tab.key"
          @click="setTab(tab.key as TabKey)"
        >
          {{ tab.label }}
          <span v-if="tab.badge" class="detail__tab-badge" data-test="change-tab-badge">{{ tab.badge }}</span>
        </button>
      </nav>

      <section class="detail__body">
        <OverviewTab
          v-if="activeTab === 'overview'"
          :detail="detail"
          :approval="approval"
          :approval-loading="approvalLoading"
          :timeline="timeline"
          :estimate="estimate"
          @open-approval="openEstimateApproval"
          @submitted="refreshAll"
          @request-change="onFieldRequestChange"
        />
        <ChildrenTab v-else-if="activeTab === 'children'" :detail="detail" @decompose="decomposeOpen = true" />
        <TasksTab
          v-else-if="activeTab === 'tasks'"
          :requirement-id="detail.id"
          @count="(value: number) => (tabCounts.tasks = value)"
        />
        <TestingTab v-else-if="activeTab === 'testing'" :requirement-id="detail.id" :release-id="detail.releaseId" />
        <DefectsTab v-else-if="activeTab === 'defects'" :requirement-id="detail.id" />
        <ApprovalTab
          v-else-if="activeTab === 'approval'"
          :instance="approval"
          :loading="approvalLoading"
          :error="approvalError"
          :forbidden="approvalForbidden"
          :estimate-forbidden="estimateForbidden"
          :can-submit="canSubmit"
          :estimate="estimate"
          :estimate-instance="estimateInstance"
          :estimate-loading="estimateLoading"
          :estimate-error="estimateError"
          @changed="refreshAll"
          @submit="runSimpleAction('submit')"
          @retry-estimate="loadEstimateApproval"
        />
        <ChangeTab
          v-else-if="activeTab === 'change'"
          ref="changeTabs"
          :requirement-id="detail.id"
          @create="openChangeDrawer()"
          @changed="refreshAll"
          @counts="onChangeCounts"
        />
        <TraceTab
          v-else-if="activeTab === 'trace'"
          :requirement-id="detail.id"
          :release-id="detail.releaseId"
          @open="(tab: string) => setTab(tab as TabKey)"
        />
        <IntegrationTab
          v-else-if="activeTab === 'integration'"
          :requirement-id="detail.id"
          :fallback-mr-url="detail.delivery?.mrUrl ?? ''"
        />
        <DocumentsTab v-else-if="activeTab === 'documents'" :requirement-id="detail.id" />
      </section>

      <footer v-if="activeTab === 'overview'" class="detail__footer">
        <span class="detail__footer-hint">
          {{ footerHint }}
        </span>
        <el-button v-if="canSubmit" type="primary" size="small" :disabled="busy" @click="runSimpleAction('submit')">
          提交审批
        </el-button>
        <el-button
          v-if="allowedActions.includes('withdraw')"
          size="small"
          :disabled="busy"
          @click="requestAction('withdraw')"
        >
          撤回
        </el-button>
      </footer>
    </template>

    <ConfirmDialog
      v-model="confirmOpen"
      :title="confirmTitle"
      :description="`即将对 ${detail?.code ?? ''} 执行该动作，结果会写入流转记录。`"
      :impact="confirmImpact"
      :confirm-text="actionLabel(pendingAction)"
      :tone="isDanger(pendingAction) ? 'danger' : 'primary'"
      :loading="busy"
      @confirm="onConfirmed"
    />

    <DecomposeDialog v-model="decomposeOpen" :parent="detail" @created="onDecomposed" />

    <ChangeCreateDrawer
      v-model="changeDrawerOpen"
      :requirement-id="id"
      :detail="detail"
      :preset-type="changePresetType"
      :preset-fields="changePresetFields"
      @created="onChangeCreated"
    />

    <RequirementEditDrawer
      v-model="editOpen"
      :detail="detail"
      @changed="refreshAll"
      @request-change="onEditRequestChange"
      @refresh="refreshAll"
    />

    <el-dialog v-model="deliverOpen" title="提交交付" width="520px" append-to-body>
      <el-form label-position="top" size="small" @submit.prevent>
        <el-form-item label="MR 链接" :required="detail?.type === 'RD'">
          <el-input v-model="deliverMr" placeholder="https://git.example.com/…/merge_requests/12" />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="预估人天" required>
            <el-input-number v-model="deliverEstimate" :min="0.5" :step="0.5" :precision="1" controls-position="right" />
          </el-form-item>
          <el-form-item label="实际人天">
            <el-input-number v-model="deliverActual" :min="0" :step="0.5" :precision="1" controls-position="right" />
          </el-form-item>
        </div>
        <el-form-item label="交付负责人">
          <el-select v-model="deliverOwner" filterable clearable placeholder="选择负责人" style="width: 100%">
            <el-option v-for="user in users" :key="user.id" :label="user.name" :value="user.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="dialog-hint">人天与 MR 是发布准入第 3、4 项的直接依据，缺失会阻断发布。</p>
      <p v-if="deliverError" class="dialog-error">{{ deliverError }}</p>
      <template #footer>
        <el-button @click="deliverOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitDeliver">确认交付</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="assignOpen" title="转派 / 分配厂商" width="520px" append-to-body>
      <el-form label-position="top" size="small" @submit.prevent>
        <el-form-item label="承接厂商" required>
          <el-select v-model="assignVendorId" filterable placeholder="选择厂商" style="width: 100%">
            <el-option v-for="vendor in vendors" :key="vendor.id" :label="vendor.name" :value="vendor.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="assignOwner" filterable clearable placeholder="选择负责人" style="width: 100%">
            <el-option v-for="user in users" :key="user.id" :label="user.name" :value="user.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="转派原因" required>
          <el-input v-model="assignReason" type="textarea" :rows="3" maxlength="200" placeholder="原因写入审计日志，厂商侧可见" />
        </el-form-item>
      </el-form>
      <p v-if="assignError" class="dialog-error">{{ assignError }}</p>
      <template #footer>
        <el-button @click="assignOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitAssign">确认转派</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.detail__crumbs {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.detail__crumbs a {
  color: var(--text-muted-aa);
  text-decoration: none;
}
.detail__crumbs a:hover {
  color: var(--color-primary);
}
.detail__title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-top: 6px;
  flex-wrap: wrap;
}
.detail__title-main {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}
.detail__code {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  color: var(--text-muted-aa);
  background: var(--bg-page);
  border: 1px solid var(--border);
  border-radius: var(--r-cell);
  padding: 1px 6px;
}
.detail__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-title);
  line-height: 1.4;
}
.detail__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.detail__no-action {
  font-size: 12px;
  color: var(--text-muted-aa);
}
/* 受门动作：保持 r25 的禁用三色（不叠 opacity、不加 pointer-events，title/内联提示仍可读） */
.btn--disabled-reason.is-disabled,
.btn--disabled-reason.is-disabled:hover {
  background: var(--el-disabled-bg-color);
  border-color: var(--el-disabled-border-color);
  color: var(--el-disabled-text-color);
  opacity: 1;
  cursor: not-allowed;
}
/* 门/权限原因单独占一行、右对齐，避免与动作按钮抢宽度 */
.detail__gate-hint {
  flex: 1 0 100%;
  text-align: right;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted-aa);
}
.detail__tabs {
  display: flex;
  gap: 2px;
  border-bottom: 1px solid var(--border);
  overflow-x: auto;
}
.detail__tab {
  height: 34px;
  padding: 0 12px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--text-body);
  font-size: 13px;
  cursor: pointer;
  transition: color var(--t-color) var(--ease), border-color var(--t-color) var(--ease);
  white-space: nowrap;
}
.detail__tab:hover {
  color: var(--text-title);
}
.detail__tab.is-active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}
.detail__tab:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
.detail__body {
  min-height: 260px;
}
.detail__footer {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  background: linear-gradient(to top, var(--bg-page) 70%, transparent);
}
.detail__footer-hint {
  flex: 1;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.action-banner {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--color-danger);
  border-left: 3px solid var(--color-danger);
  border-radius: var(--r-cell);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
  line-height: 1.6;
}
/* 两类 409 语气必须不同：受控字段还有 CTA（warning），锁定字段没有（info，见 §3.4） */
.action-banner--change {
  border-color: var(--color-warning);
  border-left-color: var(--color-warning);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
}
.action-banner--locked {
  border-color: var(--color-info);
  border-left-color: var(--color-info);
  background: var(--color-info-bg);
  color: var(--info-ink);
}
/* 编辑按钮的禁用原因：12px 灰、不换行，紧贴按钮右侧 */
.detail__edit-hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted-aa);
  white-space: nowrap;
}
/* 三数组整块缺失时的常驻提示（与动作横幅同形，语气取 info） */
.edit-rules-missing {
  margin: 0 0 8px;
  padding: 6px 12px;
  border: 1px solid var(--color-info);
  border-left: 3px solid var(--color-info);
  border-radius: var(--r-cell);
  background: var(--color-info-bg);
  color: var(--info-ink);
  font-size: 12px;
  line-height: 1.6;
}
.action-banner__code {
  margin-left: 8px;
  font-family: var(--font-mono);
  color: var(--text-muted-aa);
}
.action-banner__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
/* 变更 Tab 角标：只表示「待审批」张数，16 高、11/600、warning 底色 */
.detail__tab-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: var(--change-badge-size);
  height: var(--change-badge-size);
  margin-left: 4px;
  padding: 0 5px;
  border-radius: var(--r-pill);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
}
/* 「审批已通过，尚未应用」常驻提示：primary 语义，不是成功色——通过 ≠ 生效 */
.change-approved-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: var(--change-banner-pad);
  border: 1px solid var(--color-primary);
  border-radius: var(--r-cell);
  background: var(--bg-selected);
  color: var(--color-primary);
  font-size: 13px;
  line-height: 1.6;
}
.change-approved-banner__link {
  flex-shrink: 0;
  border: 0;
  background: none;
  padding: 0;
  color: var(--color-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  text-decoration: underline;
}
.form-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.dialog-hint {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted-aa);
  line-height: 1.6;
}
.dialog-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger-ink);
}
</style>
