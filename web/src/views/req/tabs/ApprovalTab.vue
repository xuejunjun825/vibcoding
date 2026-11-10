<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { addSign, handleApprovalTask, removeSign, transferTask, urgeTask, withdrawInstance } from '@/api/req'
import { listDictionaryUsers } from '@/api/dictionaries'
import type { DictionaryUser } from '@/api/dictionaries'
import type {
  ApprovalInstanceDetail,
  ApprovalTaskRow,
  ApprovalTimelineEvent,
} from '@/api/types'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import { usePermission } from '@/composables/usePermission'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import { messageOf, normalizeTimeline, summaryText } from '@/views/req/platform'

/**
 * P2 · 审批 tab（AC-6/7/8/9）：时间线 + 当前节点动作。
 * 动作是否出现只由父级 allowedActions/权限决定；本组件再按「任务是否属于我」禁用并给出原因。
 */
const props = defineProps<{
  instance: ApprovalInstanceDetail | null
  loading: boolean
  error: string
  canSubmit: boolean
}>()
const emit = defineEmits<{ (event: 'changed'): void; (event: 'submit'): void }>()

const auth = useAuthStore()
const { can } = usePermission()

const actionError = ref('')
const busy = ref(false)

const instance = computed(() => (props.instance?.instance ?? null) as Record<string, unknown> | null)
const tasks = computed<ApprovalTaskRow[]>(() => props.instance?.tasks ?? [])
const pendingTasks = computed(() => tasks.value.filter((task) => task.status === 'PENDING'))
const myPendingTasks = computed(() => pendingTasks.value.filter((task) => taskUserId(task) === auth.user?.id))
const isInitiator = computed(() => {
  const initiator = props.instance?.instance?.initiator as { id?: number } | number | undefined
  const id = typeof initiator === 'number' ? initiator : initiator?.id
  return id != null && id === auth.user?.id
})
const status = computed(() => String(props.instance?.instance?.status ?? ''))

function taskUserId(task: ApprovalTaskRow): number | undefined {
  const raw = task as ApprovalTaskRow & { approverId?: number }
  return task.assignee?.id ?? raw.approverId
}

function taskUserName(task: ApprovalTaskRow): string {
  const raw = task as ApprovalTaskRow & { approverName?: string }
  return task.assignee?.name ?? raw.approverName ?? (taskUserId(task) != null ? `用户 #${taskUserId(task)}` : '—')
}

const timeline = computed<ApprovalTimelineEvent[]>(() => normalizeTimeline(props.instance?.timeline))

const documentSummary = computed(() => summaryText(props.instance?.document))

const cannotActReason = computed(() => {
  if (status.value !== 'PENDING') return '当前审批已结束，只能查看记录'
  if (myPendingTasks.value.length === 0) return '当前节点不由你审批（或已被他人处理）'
  return ''
})

// —— 同意 / 驳回 ——
const actionOpen = ref(false)
const actionKind = ref<'APPROVE' | 'REJECT'>('APPROVE')
const actionComment = ref('')
const actionFormError = ref('')

function openAction(kind: 'APPROVE' | 'REJECT'): void {
  actionKind.value = kind
  actionComment.value = ''
  actionFormError.value = ''
  actionOpen.value = true
}

async function submitAction(): Promise<void> {
  const comment = actionComment.value.trim()
  if (actionKind.value === 'REJECT' && comment.length < 2) {
    actionFormError.value = '驳回必须填写意见（至少 2 个字），会展示给发起人'
    return
  }
  const task = myPendingTasks.value[0]
  if (!task) return
  busy.value = true
  try {
    await handleApprovalTask(task.id, { action: actionKind.value, comment: comment || undefined })
    ElMessage.success(actionKind.value === 'APPROVE' ? '已同意，流程继续' : '已驳回')
    actionOpen.value = false
    emit('changed')
  } catch (error) {
    actionFormError.value = messageOf(error)
  } finally {
    busy.value = false
  }
}

// —— 加签 ——
const addOpen = ref(false)
const addType = ref<'BEFORE' | 'AFTER'>('AFTER')
const addUserIds = ref<number[]>([])
const addComment = ref('')
const addError = ref('')
const userOptions = ref<DictionaryUser[]>([])
const userLoading = ref(false)

async function openAddSign(): Promise<void> {
  addOpen.value = true
  addType.value = 'AFTER'
  addUserIds.value = []
  addComment.value = ''
  addError.value = ''
  if (userOptions.value.length === 0) {
    userLoading.value = true
    try {
      userOptions.value = await listDictionaryUsers()
    } catch {
      userOptions.value = []
    } finally {
      userLoading.value = false
    }
  }
}

async function submitAddSign(): Promise<void> {
  if (addUserIds.value.length === 0) {
    addError.value = '请选择至少一位审批人'
    return
  }
  const task = myPendingTasks.value[0] ?? pendingTasks.value[0]
  if (!task) return
  busy.value = true
  try {
    await addSign(task.id, { type: addType.value, userIds: addUserIds.value, comment: addComment.value.trim() || undefined })
    ElMessage.success(addType.value === 'BEFORE' ? '已前加签：新审批人先于你审批' : '已后加签：你通过后由新增审批人继续')
    addOpen.value = false
    emit('changed')
  } catch (error) {
    addError.value = messageOf(error)
  } finally {
    busy.value = false
  }
}

// —— 减签 ——
const removeOpen = ref(false)
const removeTarget = ref<number | undefined>()
const removeReason = ref('')
const removeError = ref('')

const removeCandidates = computed(() =>
  pendingTasks.value
    .filter((task) => taskUserId(task) !== auth.user?.id)
    .map((task) => ({ id: taskUserId(task), name: taskUserName(task) }))
    .filter((item): item is { id: number; name: string } => item.id != null),
)

function openRemoveSign(): void {
  removeTarget.value = removeCandidates.value[0]?.id
  removeReason.value = ''
  removeError.value = ''
  removeOpen.value = true
}

async function submitRemoveSign(): Promise<void> {
  if (removeTarget.value == null) {
    removeError.value = '请选择要减签的审批人'
    return
  }
  if (removeReason.value.trim().length < 2) {
    removeError.value = '减签必须填写原因（至少 2 个字）'
    return
  }
  const task = myPendingTasks.value[0] ?? pendingTasks.value[0]
  if (!task) return
  busy.value = true
  try {
    await removeSign(task.id, { targetUserId: removeTarget.value, reason: removeReason.value.trim() })
    ElMessage.success('已通知被减签人')
    removeOpen.value = false
    emit('changed')
  } catch (error) {
    removeError.value = messageOf(error)
  } finally {
    busy.value = false
  }
}

// —— 转办 ——
const transferOpen = ref(false)
const transferTo = ref<number | undefined>()
const transferComment = ref('')
const transferError = ref('')

function openTransfer(): void {
  transferTo.value = undefined
  transferComment.value = ''
  transferError.value = ''
  transferOpen.value = true
  if (userOptions.value.length === 0) void loadUsers()
}

async function loadUsers(): Promise<void> {
  userLoading.value = true
  try {
    userOptions.value = await listDictionaryUsers()
  } catch {
    userOptions.value = []
  } finally {
    userLoading.value = false
  }
}

async function submitTransfer(): Promise<void> {
  if (transferTo.value == null) {
    transferError.value = '请选择转办给谁'
    return
  }
  const task = myPendingTasks.value[0]
  if (!task) return
  busy.value = true
  try {
    await transferTask(task.id, { toUserId: transferTo.value, comment: transferComment.value.trim() || undefined })
    ElMessage.success('已转办，待办将出现在对方审批中心')
    transferOpen.value = false
    emit('changed')
  } catch (error) {
    transferError.value = messageOf(error)
  } finally {
    busy.value = false
  }
}

// —— 催办 / 撤回 ——
async function doUrge(): Promise<void> {
  const task = pendingTasks.value[0]
  if (!task) return
  busy.value = true
  try {
    await urgeTask(task.id)
    ElMessage.success('已催办当前审批人')
    emit('changed')
  } catch (error) {
    actionError.value = messageOf(error)
  } finally {
    busy.value = false
  }
}

const withdrawOpen = ref(false)
const withdrawComment = ref('')
const withdrawError = ref('')

function openWithdraw(): void {
  withdrawComment.value = ''
  withdrawError.value = ''
  withdrawOpen.value = true
}

async function submitWithdraw(): Promise<void> {
  const id = props.instance?.instance?.id
  if (id == null) return
  busy.value = true
  try {
    await withdrawInstance(id, withdrawComment.value.trim() || undefined)
    ElMessage.success('已撤回，单据回到可编辑状态')
    withdrawOpen.value = false
    emit('changed')
  } catch (error) {
    withdrawError.value = messageOf(error)
  } finally {
    busy.value = false
  }
}

watch(
  () => props.instance?.instance?.id,
  () => {
    actionError.value = ''
  },
)
</script>

<template>
  <div class="approval-tab">
    <div v-if="loading" class="skeleton skeleton__row" />
    <ErrorState v-else-if="error" compact title="审批信息暂不可用" :message="error" />

    <EmptyState
      v-else-if="!instance"
      :title="canSubmit ? '尚未提交审批' : '还没有审批记录'"
      :description="canSubmit ? '提交审批后会按发布的审批流生成节点，所有操作都会留痕。' : '该需求还没有进入审批流程。'"
      icon="lock"
      compact
    >
      <template v-if="canSubmit" #action>
        <el-button type="primary" size="small" @click="emit('submit')">提交审批</el-button>
      </template>
    </EmptyState>

    <template v-else>
      <div v-if="actionError" class="action-banner" role="alert">
        <span>{{ actionError }}</span>
        <el-button text size="small" @click="actionError = ''">关闭</el-button>
      </div>

      <section class="surface surface--pad approval-tab__summary">
        <div class="approval-tab__summary-main">
          <div class="approval-tab__row">
            <StatusPill kind="approval" :value="status" />
            <span class="approval-tab__doc">{{ documentSummary }}</span>
          </div>
          <div class="approval-tab__meta">
            <span>流程版本 v{{ instance.definitionVersion ?? 1 }}</span>
            <span>当前节点：{{ instance.currentNode ?? '—' }}</span>
            <span v-if="instance.startedAt">发起于 {{ formatDateTime(String(instance.startedAt)) }}</span>
          </div>
        </div>
        <div class="approval-tab__ops">
          <el-button
            type="primary"
            size="small"
            :disabled="!!cannotActReason || !can('req:review')"
            :title="cannotActReason || (can('req:review') ? '' : '需要权限：req:review')"
            @click="openAction('APPROVE')"
          >
            同意
          </el-button>
          <el-button
            size="small"
            :disabled="!!cannotActReason || !can('req:review')"
            :title="cannotActReason || (can('req:review') ? '' : '需要权限：req:review')"
            @click="openAction('REJECT')"
          >
            驳回
          </el-button>
          <el-dropdown trigger="click">
            <el-button size="small" :disabled="!!cannotActReason" :title="cannotActReason">更多 ▾</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="openAddSign">加签</el-dropdown-item>
                <el-dropdown-item :disabled="removeCandidates.length === 0" @click="openRemoveSign">减签</el-dropdown-item>
                <el-dropdown-item @click="openTransfer">转办</el-dropdown-item>
                <el-dropdown-item :disabled="pendingTasks.length === 0" @click="doUrge">催办</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button v-if="isInitiator && status === 'PENDING'" size="small" text @click="openWithdraw">撤回</el-button>
        </div>
      </section>

      <section class="surface surface--pad">
        <header class="approval-tab__head">
          <h3 class="approval-tab__title">流转记录</h3>
          <span class="approval-tab__hint">加签 / 减签 / 转办是独立结构条目，不折叠</span>
        </header>
        <ApprovalTimeline :events="timeline" :active="status === 'PENDING'" />
        <div v-if="tasks.length" class="approval-tab__tasks">
          <h4 class="approval-tab__subtitle">节点任务</h4>
          <ul>
            <li v-for="task in tasks" :key="task.id">
              <span class="approval-tab__node">{{ task.nodeName }}</span>
              <StatusPill kind="approval" :value="task.status" size="sm" />
              <span class="approval-tab__assignee">{{ taskUserName(task) }}</span>
              <span v-if="task.comment" class="approval-tab__comment">{{ task.comment }}</span>
            </li>
          </ul>
        </div>
      </section>
    </template>

    <el-dialog v-model="actionOpen" :title="actionKind === 'APPROVE' ? '同意并继续流程' : '驳回单据'" width="480px" append-to-body>
      <p v-if="actionKind === 'REJECT'" class="dialog-impact">驳回后单据退回「已提报」，发起人可修改后重新提交；本次意见会写入流转记录。</p>
      <el-input
        v-model="actionComment"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        :placeholder="actionKind === 'REJECT' ? '必填：驳回原因' : '意见（可选）'"
      />
      <p v-if="actionFormError" class="dialog-error">{{ actionFormError }}</p>
      <template #footer>
        <el-button @click="actionOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitAction">
          {{ actionKind === 'APPROVE' ? '确认同意' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="addOpen" title="加签" width="480px" append-to-body>
      <el-radio-group v-model="addType" size="small">
        <el-radio-button value="BEFORE">前加签</el-radio-button>
        <el-radio-button value="AFTER">后加签</el-radio-button>
      </el-radio-group>
      <p class="dialog-hint">前加签：新审批人先于你审批；后加签：你通过后由新增审批人继续，已处理任务不可加签。</p>
      <el-select
        v-model="addUserIds"
        multiple
        filterable
        :loading="userLoading"
        placeholder="选择审批人（可多选）"
        style="width: 100%"
      >
        <el-option v-for="user in userOptions" :key="user.id" :label="user.name" :value="user.id" />
      </el-select>
      <el-input v-model="addComment" type="textarea" :rows="2" placeholder="加签说明（可选）" style="margin-top: 8px" />
      <p v-if="addError" class="dialog-error">{{ addError }}</p>
      <template #footer>
        <el-button @click="addOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitAddSign">确认加签</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="removeOpen" title="减签" width="480px" append-to-body>
      <p class="dialog-impact">减签会移除该审批人的待办并通知对方；减签最后一位审批人会被平台拒绝（409）。</p>
      <el-select v-model="removeTarget" placeholder="选择要减签的审批人" style="width: 100%">
        <el-option v-for="item in removeCandidates" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-input v-model="removeReason" type="textarea" :rows="2" placeholder="必填：减签原因" style="margin-top: 8px" />
      <p v-if="removeError" class="dialog-error">{{ removeError }}</p>
      <template #footer>
        <el-button @click="removeOpen = false">取消</el-button>
        <el-button type="danger" :loading="busy" @click="submitRemoveSign">确认减签</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="transferOpen" title="转办" width="480px" append-to-body>
      <el-select v-model="transferTo" filterable placeholder="转办给谁" style="width: 100%">
        <el-option v-for="user in userOptions" :key="user.id" :label="user.name" :value="user.id" />
      </el-select>
      <el-input v-model="transferComment" type="textarea" :rows="2" placeholder="转办说明（可选）" style="margin-top: 8px" />
      <p v-if="transferError" class="dialog-error">{{ transferError }}</p>
      <template #footer>
        <el-button @click="transferOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitTransfer">确认转办</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="withdrawOpen" title="撤回审批" width="480px" append-to-body>
      <p class="dialog-impact">撤回后实例终止，单据回到「已提报」；已产生的审批记录保留可查。</p>
      <el-input v-model="withdrawComment" type="textarea" :rows="3" placeholder="撤回说明（可选）" />
      <p v-if="withdrawError" class="dialog-error">{{ withdrawError }}</p>
      <template #footer>
        <el-button @click="withdrawOpen = false">取消</el-button>
        <el-button type="danger" :loading="busy" @click="submitWithdraw">确认撤回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.approval-tab {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.approval-tab__summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.approval-tab__row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.approval-tab__doc {
  font-size: 13px;
  color: var(--text-title);
}
.approval-tab__meta {
  display: flex;
  gap: 14px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted-aa);
  flex-wrap: wrap;
}
.approval-tab__ops {
  display: flex;
  align-items: center;
  gap: 6px;
}
.approval-tab__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 12px;
}
.approval-tab__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-title);
}
.approval-tab__hint {
  font-size: 12px;
  color: var(--text-muted-aa);
}
.approval-tab__tasks {
  margin-top: 16px;
  border-top: 1px solid var(--divider);
  padding-top: 12px;
}
.approval-tab__subtitle {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-body);
}
.approval-tab__tasks ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.approval-tab__tasks li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-body);
}
.approval-tab__node {
  min-width: 96px;
  color: var(--text-title);
}
.approval-tab__comment {
  color: var(--text-muted-aa);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.action-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--color-danger);
  border-left-width: 2px;
  border-radius: var(--r-ctl);
  background: var(--color-danger-bg);
  color: var(--danger-ink);
  font-size: 12px;
}
.dialog-impact {
  margin: 0 0 10px;
  padding: 8px 10px;
  border-radius: var(--r-ctl);
  background: var(--color-warning-bg);
  color: var(--warning-ink);
  font-size: 12px;
  line-height: 1.6;
}
.dialog-hint {
  margin: 8px 0;
  font-size: 12px;
  color: var(--text-muted-aa);
}
.dialog-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger-ink);
}
</style>
