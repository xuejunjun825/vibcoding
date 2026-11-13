<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { assignVendor, deliverRequirement, getApprovalInstance, getRequirement, actOnRequirement, listApprovalMine } from '@/api/req'
import { listDictionaryUsers, listDictionaryVendors } from '@/api/dictionaries'
import type { DictionaryUser, DictionaryVendor } from '@/api/dictionaries'
import type {
  ApprovalInstanceDetail,
  ApprovalTodoRow,
  RequirementDetail,
} from '@/api/types'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import ErrorState from '@/components/ErrorState.vue'
import PriorityDot from '@/components/PriorityDot.vue'
import ScopeChip from '@/components/ScopeChip.vue'
import StatusPill from '@/components/StatusPill.vue'
import ApprovalTab from '@/views/req/tabs/ApprovalTab.vue'
import ChildrenTab from '@/views/req/tabs/ChildrenTab.vue'
import DefectsTab from '@/views/req/tabs/DefectsTab.vue'
import IntegrationTab from '@/views/req/tabs/IntegrationTab.vue'
import DocumentsTab from '@/views/req/tabs/DocumentsTab.vue'
import OverviewTab from '@/views/req/tabs/OverviewTab.vue'
import TasksTab from '@/views/req/tabs/TasksTab.vue'
import TestingTab from '@/views/req/tabs/TestingTab.vue'
import TraceTab from '@/views/req/tabs/TraceTab.vue'
import DecomposeDialog from '@/views/req/DecomposeDialog.vue'
import {
  ACTION_IMPACT,
  DANGER_ACTIONS,
  messageOf,
  metaOf,
  normalizeRequirementDetail,
  normalizeTimeline,
  typeLabel,
} from '@/views/req/platform'
import { useAuthStore } from '@/stores/auth'
import { useDictStore } from '@/stores/dict'

/**
 * P2 需求详情（7 tab）。动作区只认服务端 allowedActions（唯一真相，AC-3）：
 * 按钮不做本地权限二次过滤；403 就地 toast 并刷新详情同步按钮，不跳 /403；
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

const TAB_KEYS = ['overview', 'children', 'tasks', 'testing', 'defects', 'approval', 'trace', 'integration', 'documents'] as const
type TabKey = (typeof TAB_KEYS)[number]
const activeTab = ref<TabKey>(TAB_KEYS.includes(String(route.query.tab) as TabKey) ? (String(route.query.tab) as TabKey) : 'overview')
const tabCounts = reactive({ children: 0, tasks: 0 })

const actionBanner = ref<{ text: string; code?: string } | null>(null)
const busy = ref(false)

const tabs = computed(() => [
  { key: 'overview', label: '概览' },
  { key: 'children', label: `子需求${tabCounts.children ? ` ${tabCounts.children}` : ''}` },
  { key: 'tasks', label: `任务${tabCounts.tasks ? ` ${tabCounts.tasks}` : ''}` },
  { key: 'testing', label: '测试' },
  { key: 'defects', label: '缺陷' },
  { key: 'approval', label: '审批' },
  { key: 'trace', label: '追溯' },
  { key: 'integration', label: '集成' },
  { key: 'documents', label: '文档' },
])

const allowedActions = computed(() => detail.value?.allowedActions ?? [])
// 「提交审批」「撤回」固定在概览页底部的动作条，头部不再重复渲染同一个动作入口
const footerActionKeys = new Set(['submit', 'withdraw'])
const headerActions = computed(() => allowedActions.value.filter((action) => !footerActionKeys.has(action)))
const approvalTimeline = computed(() => normalizeTimeline(approval.value?.timeline))

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
    detail.value = normalizeRequirementDetail(await getRequirement(id.value))
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
  try {
    const direct = (detail.value as unknown as { approvalInstanceId?: number }).approvalInstanceId
    if (direct != null) {
      approval.value = await getApprovalInstance(direct)
      return
    }
    const mine = await listApprovalMine({ page: 1, size: 50 })
    const hit = mine.items.find(
      (row: ApprovalTodoRow) => Number(row.bizId) === id.value && String(row.bizType).toUpperCase().includes('REQ'),
    )
    approval.value = hit ? await getApprovalInstance(hit.instanceId) : null
  } catch (error) {
    approval.value = null
    approvalError.value = messageOf(error)
  } finally {
    approvalLoading.value = false
  }
}

async function refreshAll(): Promise<void> {
  await load()
  await loadApproval()
}

function setTab(tab: TabKey): void {
  activeTab.value = tab
  void router.replace({ query: { ...route.query, tab } })
}

function handleActionError(error: unknown): void {
  const status = (error as { status?: number }).status
  const code = (error as { code?: string }).code
  if (status === 409) {
    actionBanner.value = { text: messageOf(error), code }
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
  await loadApproval()
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
            <span v-if="allowedActions.length === 0" class="detail__no-action">当前状态无可执行动作</span>
          </div>
        </div>
      </header>

      <div v-if="actionBanner" class="action-banner" role="alert">
        <div>
          <strong>提交被拒绝：</strong>{{ actionBanner.text }}
          <span v-if="actionBanner.code" class="action-banner__code">{{ actionBanner.code }}</span>
        </div>
        <el-button text size="small" @click="actionBanner = null">关闭</el-button>
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
        </button>
      </nav>

      <section class="detail__body">
        <OverviewTab
          v-if="activeTab === 'overview'"
          :detail="detail"
          :approval="approval"
          :approval-loading="approvalLoading"
          :timeline="approvalTimeline"
          @open-approval="setTab('approval')"
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
          :can-submit="canSubmit"
          @changed="refreshAll"
          @submit="runSimpleAction('submit')"
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
.action-banner__code {
  margin-left: 8px;
  font-family: var(--font-mono);
  color: var(--text-muted-aa);
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
