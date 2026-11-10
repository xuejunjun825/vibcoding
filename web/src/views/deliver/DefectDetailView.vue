<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { actOnDefect, getDefect } from '@/api/deliver'
import { listDictionaryUsers } from '@/api/dictionaries'
import type { DictionaryUser } from '@/api/dictionaries'
import type { ApprovalTimelineEvent, DefectDetail } from '@/api/types'
import { usePermission } from '@/composables/usePermission'
import { useAuthStore } from '@/stores/auth'
import { useDictStore } from '@/stores/dict'
import { formatDate, formatDateTime } from '@/utils/format'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PriorityDot from '@/components/PriorityDot.vue'
import ScopeChip from '@/components/ScopeChip.vue'
import StatusPill from '@/components/StatusPill.vue'
import '@/views/deliver/shared.css'

/**
 * 缺陷详情（DESIGN P5）。
 * - 动作条完全由服务端 allowedActions 驱动，前端不维护状态机副本；
 * - 摘要视图（无 defect:handle）只保留状态 / 严重级 / 预计修复时间；
 * - 流转失败 409 用常驻条，而不是 toast。
 */
const route = useRoute()
const router = useRouter()
const perm = usePermission()
const dict = useDictStore()
const auth = useAuthStore()

const defect = ref<DefectDetail | null>(null)
const loading = ref(false)
const error = ref<{ message: string; code?: string; status?: number } | null>(null)
const conflict = ref('')
const users = ref<DictionaryUser[]>([])

const canHandle = computed(() => perm.can('defect:handle'))
const summaryMode = computed(() => !canHandle.value)
const canAudit = computed(() => perm.can('audit:view'))

const STEPS = [
  { key: 'NEW', label: '新建' },
  { key: 'IN_PROGRESS', label: '处理中' },
  { key: 'PENDING_VERIFY', label: '待验证' },
  { key: 'CLOSED', label: '关闭' },
]

const stepIndex = computed(() => {
  const status = defect.value?.status
  if (String(status) === 'REOPENED') return 1
  return STEPS.findIndex((step) => step.key === status)
})

const actions = computed(() => defect.value?.allowedActions ?? [])
const dangerous = new Set(['verify_reject', 'reopen', 'close', 'close_duplicate'])

const events = ref<ApprovalTimelineEvent[]>([])

function pushEvent(action: string, comment?: string): void {
  events.value = [
    {
      action,
      operator: auth.user?.name ?? '我',
      comment: comment || null,
      at: new Date().toISOString(),
      origin: 'SIGN',
    },
    ...events.value,
  ]
}

async function load(): Promise<void> {
  const id = Number(route.params.id)
  if (!id) return
  loading.value = true
  error.value = null
  try {
    defect.value = await getDefect(id)
  } catch (err) {
    error.value = {
      message: (err as Error).message || '缺陷加载失败',
      code: (err as { code?: string }).code,
      status: (err as { status?: number }).status,
    }
  } finally {
    loading.value = false
  }
}

/* ---------------- 动作 ---------------- */

const dialog = reactive({
  visible: false,
  action: '',
  label: '',
  requireReason: false,
  submitting: false,
})

const assignVisible = ref(false)
const assignForm = reactive({ assigneeId: undefined as number | undefined, comment: '' })

function startAction(action: string): void {
  if (action === 'assign') {
    assignVisible.value = true
    return
  }
  dialog.action = action
  dialog.label = dict.actionLabel(action) || action
  dialog.requireReason = dangerous.has(action)
  dialog.visible = true
}

async function submitAction(payload: { reason: string; acknowledged: boolean }): Promise<void> {
  const current = defect.value
  if (!current) return
  dialog.submitting = true
  conflict.value = ''
  try {
    const updated = await actOnDefect(current.id, {
      action: dialog.action,
      comment: payload.reason || undefined,
    })
    defect.value = updated
    pushEvent(dialog.action, payload.reason)
    dialog.visible = false
    ElMessage.success(`${dialog.label}成功`)
  } catch (err) {
    const apiError = err as { status?: number; message?: string; code?: string; detail?: unknown }
    if (apiError.status === 409) {
      conflict.value = apiError.message || '当前状态不允许该操作（409）'
    } else {
      ElMessage.error(apiError.message || '操作失败')
    }
  } finally {
    dialog.submitting = false
  }
}

async function submitAssign(): Promise<void> {
  const current = defect.value
  if (!current) return
  if (!assignForm.assigneeId) {
    ElMessage.warning('请选择处理人')
    return
  }
  dialog.submitting = true
  conflict.value = ''
  try {
    const updated = await actOnDefect(current.id, {
      action: 'assign',
      assigneeId: assignForm.assigneeId,
      comment: assignForm.comment || undefined,
    })
    defect.value = updated
    pushEvent('assign', assignForm.comment)
    assignVisible.value = false
    ElMessage.success('已转派')
  } catch (err) {
    const apiError = err as { status?: number; message?: string }
    if (apiError.status === 409) conflict.value = apiError.message || '当前状态不允许转派（409）'
    else ElMessage.error(apiError.message || '转派失败')
  } finally {
    dialog.submitting = false
  }
}

/* ---------------- 辅助 ---------------- */

function openRequirement(): void {
  const id = defect.value?.requirementId
  if (!id) return
  const target = router.resolve({ name: 'requirement-detail', params: { id } })
  if (target.matched.some((record) => record.name === 'catch-all')) {
    ElMessage.info('需求详情页尚未注册（T8）')
    return
  }
  void router.push({ name: 'requirement-detail', params: { id } })
}

function openAudit(): void {
  const id = defect.value?.id
  if (!id) return
  void router.push({ name: 'admin-audit', query: { bizType: 'defect', bizId: String(id) } })
}

async function loadUsers(): Promise<void> {
  if (!perm.can('org:manage')) return
  try {
    users.value = await listDictionaryUsers()
  } catch {
    users.value = []
  }
}

onMounted(async () => {
  await Promise.all([load(), loadUsers()])
})

watch(() => route.params.id, load)
</script>

<template>
  <div class="dl-page">
    <header class="dl-head">
      <div class="dl-head__title">
        <button type="button" class="dl-head__back" @click="router.push({ name: 'defects' })">
          ← 缺陷
        </button>
        <h1 class="t-page-title">{{ defect?.title || '缺陷详情' }}</h1>
        <StatusPill v-if="defect" kind="defect" :value="defect.status" />
        <PriorityDot v-if="defect" :value="defect.severity" />
        <span v-if="defect?.reopenCount" class="reopen-chip">重开 ×{{ defect.reopenCount }}</span>
        <ScopeChip v-if="summaryMode" label="摘要视图" tone="info" />
      </div>
      <div class="dl-head__actions">
        <el-button v-if="canAudit" @click="openAudit">审计日志</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <div v-if="summaryMode" class="dl-summary-note">
      <span aria-hidden="true">⚪</span>
      摘要视图：描述、复现步骤、附件与处理人字段不在你的授权范围内。
    </div>

    <div v-if="conflict" class="dl-conflict">
      <span aria-hidden="true">!</span>
      <span>{{ conflict }}</span>
    </div>

    <ErrorState
      v-if="error"
      :message="error.message"
      :code="error.code"
      :status="error.status"
      @retry="load"
    />
    <EmptyState
      v-else-if="!loading && !defect"
      title="缺陷不存在或已不可见"
      description="它可能属于其他业务域 / 厂商，或已被合并"
      icon="lock"
      action-text="返回缺陷列表"
      @action="router.push({ name: 'defects' })"
    />

    <template v-else-if="defect">
      <section v-if="canHandle && actions.length" class="surface surface--pad">
        <div class="dl-actions">
          <span class="dl-toolbar__title">可执行动作</span>
          <el-button
            v-for="action in actions"
            :key="action"
            :type="dangerous.has(action) ? 'danger' : 'primary'"
            :plain="dangerous.has(action)"
            @click="startAction(action)"
          >
            {{ dict.actionLabel(action) || action }}
          </el-button>
        </div>
        <p class="dl-hint action-hint">
          动作选项来自服务端 allowedActions；状态机与门禁判定不在前端复制。
        </p>
      </section>

      <div class="detail-grid">
        <section class="surface surface--pad">
          <h2 class="t-block-title">基本信息</h2>
          <div class="dl-field-grid meta-grid">
            <div class="dl-field">
              <div class="dl-field__label">编号</div>
              <div class="dl-field__value t-code">{{ defect.code }}</div>
            </div>
            <div class="dl-field">
              <div class="dl-field__label">优先级</div>
              <div class="dl-field__value">
                <PriorityDot :value="defect.priority || defect.severity" />
              </div>
            </div>
            <div class="dl-field">
              <div class="dl-field__label">预计修复</div>
              <div class="dl-field__value">
                {{ defect.expectedFixDate ? formatDate(defect.expectedFixDate) : '未设置' }}
              </div>
            </div>
            <div v-if="!summaryMode" class="dl-field">
              <div class="dl-field__label">处理人</div>
              <div class="dl-field__value">{{ defect.handlerName || '未指派' }}</div>
            </div>
            <div v-if="!summaryMode" class="dl-field">
              <div class="dl-field__label">提报人</div>
              <div class="dl-field__value">{{ defect.reporterName || '—' }}</div>
            </div>
            <div class="dl-field">
              <div class="dl-field__label">发现时间</div>
              <div class="dl-field__value">{{ formatDateTime(defect.foundAt) }}</div>
            </div>
            <div v-if="defect.closedAt" class="dl-field">
              <div class="dl-field__label">关闭时间</div>
              <div class="dl-field__value">{{ formatDateTime(defect.closedAt) }}</div>
            </div>
            <div v-if="defect.requirementCode" class="dl-field">
              <div class="dl-field__label">关联需求</div>
              <div class="dl-field__value">
                <el-button link type="primary" @click="openRequirement">
                  {{ defect.requirementCode }}
                </el-button>
              </div>
            </div>
            <div v-if="defect.testCaseCode" class="dl-field">
              <div class="dl-field__label">来源用例</div>
              <div class="dl-field__value">
                <span class="t-code">{{ defect.testCaseCode }}</span>
                <span v-if="defect.testCaseVersion" class="ink-muted">
                  · v{{ defect.testCaseVersion }}
                </span>
              </div>
            </div>
          </div>

          <template v-if="!summaryMode">
            <h2 class="t-block-title section-gap">描述</h2>
            <p class="prose">{{ defect.description || '未填写' }}</p>
            <h2 class="t-block-title section-gap">复现步骤</h2>
            <p class="prose">{{ defect.reproSteps || '未填写' }}</p>
            <h2 class="t-block-title section-gap">附件</h2>
            <ul v-if="defect.attachments?.length" class="attach-list">
              <li v-for="item in defect.attachments" :key="item.id">
                <span class="t-code">{{ item.fileName }}</span>
                <span class="ink-muted">{{ item.size ? `${item.size} B` : '' }}</span>
              </li>
            </ul>
            <p v-else class="dl-hint">无附件</p>
          </template>
        </section>

        <aside class="detail-side">
          <section class="surface surface--pad">
            <h2 class="t-block-title">状态轨迹</h2>
            <div class="dl-steps">
              <div
                v-for="(step, index) in STEPS"
                :key="step.key"
                class="dl-step"
                :class="{
                  'is-done': index < stepIndex,
                  'is-current': index === stepIndex,
                }"
              >
                {{ step.label }}
              </div>
            </div>
            <p v-if="String(defect.status) === 'REOPENED'" class="dl-hint reopen-hint">
              缺陷被重新打开后回到「处理中」，再次关闭才算闭环。
            </p>
          </section>

          <section class="surface surface--pad">
            <h2 class="t-block-title">本次操作记录</h2>
            <ApprovalTimeline
              v-if="events.length"
              :events="events"
              active
              pending-label="等待下一步动作"
              dense
            />
            <p v-else class="dl-hint">
              进入本页后尚未操作。完整流转见审计日志（按 bizType=defect + bizId 反查）。
            </p>
          </section>
        </aside>
      </div>
    </template>

    <ConfirmDialog
      v-model="dialog.visible"
      :title="dialog.label"
      :description="`当前状态：${defect ? dict.meta('defect', defect.status).label : '—'}。执行后会写入时间线与审计。`"
      :impact="dialog.requireReason ? ['该动作会改变缺陷结论，需填写原因'] : []"
      :reason-required="dialog.requireReason"
      :reason-label="dialog.requireReason ? '原因（必填）' : '备注（可选）'"
      :confirm-text="dialog.label"
      :tone="dialog.requireReason ? 'danger' : 'primary'"
      :loading="dialog.submitting"
      @confirm="submitAction"
    />

    <el-dialog v-model="assignVisible" title="转派处理人" width="440px">
      <el-form label-position="top">
        <el-form-item label="处理人" required>
          <el-select v-if="users.length" v-model="assignForm.assigneeId" filterable placeholder="选择处理人">
            <el-option v-for="user in users" :key="user.id" :label="user.name" :value="user.id" />
          </el-select>
          <el-input-number
            v-else
            v-model="assignForm.assigneeId"
            :min="1"
            :controls="false"
            placeholder="处理人 ID"
          />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="assignForm.comment" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.submitting" @click="submitAssign">
          确定转派
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.reopen-chip {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  border-radius: var(--r-pill);
  background: var(--warning-bg);
  color: var(--warning-ink);
  font-size: 11px;
}

.action-hint {
  margin-top: 8px;
}

.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 12px;
  align-items: start;
}

.detail-side {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.meta-grid {
  margin-top: 8px;
}

.section-gap {
  margin-top: 20px;
}

.prose {
  margin: 6px 0 0;
  color: var(--text-body);
  font-size: 13px;
  line-height: 22px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.attach-list {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
}

.reopen-hint {
  margin-top: 10px;
}

@media (max-width: 1180px) {
  .detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
