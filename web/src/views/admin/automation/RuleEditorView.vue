<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/client'
import { listRoles } from '@/api/admin'
import type { RoleRow } from '@/api/types'
import {
  SCOPE_TYPES,
  createRule,
  currentVersionOf,
  fieldOf,
  getMeta,
  getRule,
  updateRule,
  webhookConfigLabel,
  type AutomationAction,
  type AutomationCondition,
  type AutomationMeta,
  type AutomationRecipient,
  type AutomationRule,
  type AutomationRuleCmd,
} from '@/api/automation'
import { usePermission } from '@/composables/usePermission'
import { useDictStore } from '@/stores/dict'
import AutomationRuleSentence from '@/components/AutomationRuleSentence.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { PRIORITIES, REQUIREMENT_TYPES, usePlatformDict } from '@/views/req/platform'
import '@/views/admin/admin-shared.css'
import './automation.css'

/**
 * 规则编辑器（DESIGN §3.2）。
 *
 * 它**不是表单，是一句因果句的构造器**：四段（什么时候 / 作用域 / 条件 / 动作）
 * 写完，右侧摘要栏实时渲染与列表完全同源的句式。因此这里没有原始 JSON 编辑框 ——
 * 用户写不出服务端不接受的 schema（DESIGN §0.5 反模式 1）。
 *
 * 越权在表单里就先拦：作用域选定后，域/厂商条件行变成只读 `= 作用域`，
 * 用户没有入口写出更宽的范围；服务端 CONDITION_WIDENS_SCOPE 只是兜底。
 * 动作只有 4 型分段控件，禁止动作**无处可写**（不是靠报错拦住）。
 */
const route = useRoute()
const router = useRouter()
const perm = usePermission()
const dict = useDictStore()
const platform = usePlatformDict()

const props = withDefaults(defineProps<{ mode?: 'create' | 'edit'; id?: number }>(), {
  mode: 'create',
})

const isEdit = computed(() => props.mode === 'edit' || route.name === 'automation-rule-edit')
const ruleId = computed(() => Number(props.id ?? route.params.id ?? 0))
const canManage = computed(() => perm.canAction('automation.action.manage'))

const meta = ref<AutomationMeta | null>(null)
const rule = ref<AutomationRule | null>(null)
const loading = ref(true)
const saving = ref(false)
const notFound = ref(false)
const loadError = ref<ApiError | null>(null)
const banner = ref<{ message: string; code?: string; conflict?: boolean } | null>(null)
const roleOptions = ref<RoleRow[]>([])

const domainOptions = computed(() => platform.domains.value)
const vendorOptions = computed(() => platform.vendors.value)
const userOptions = computed(() => platform.users.value)
const statusOptions = computed(() => meta.value?.statuses ?? [])

const APPROVAL_RESULTS = [
  { code: 'APPROVED', label: '审批通过' },
  { code: 'REJECTED', label: '审批驳回' },
  { code: 'WITHDRAWN', label: '审批撤回' },
]

const FIELD_LABELS: Record<string, string> = {
  title: '标题',
  description: '描述',
  priority: '优先级',
  expected_delivery_date: '期望交付日',
  estimate_days: '预估人天',
  release_id: '所属版本',
  parent_id: '父需求',
  owner_user_id: '负责人',
}

const OP_LABELS: Record<string, string> = {
  gt: '>',
  gte: '≥',
  lt: '<',
  lte: '≤',
  eq: '=',
}

const TRIGGER_HINTS: Record<string, string> = {
  REQUIREMENT_CREATED: '新建需求提交后立即判定',
  STATUS_CHANGE: '需求状态流转后判定（可看审批结果）',
  FIELD_CHANGE: '优先级 / 标题 / 描述 / 期望交付日 / 人天 / 版本 / 父需求 / 负责人变更后判定',
  SCHEDULE: '每天 08:30（Asia/Shanghai）扫描作用域',
}

interface EditorAction {
  key: number
  type: string
  recipients: AutomationRecipient[]
  userId: number | null
  tag: string
  configId: number | null
  path: string
}

let actionSeq = 0

const form = reactive({
  name: '',
  enabled: true,
  triggerType: '',
  scopeType: 'DOMAIN',
  scopeId: null as number | null,
  priority: 100,
  types: [] as string[],
  priorityIn: [] as string[],
  statusIn: [] as string[],
  approvalResults: [] as string[],
  estimate: false,
  estimateOp: 'gte',
  estimateValue: 10 as number,
  domainIds: [] as number[],
  vendorIds: [] as number[],
  fields: [] as string[],
  actions: [] as EditorAction[],
})

const errors = reactive<Record<string, string>>({})
const FIELD_ORDER = ['name', 'priority', 'scopeId', 'condition', 'actions']

const triggerHint = computed(() => TRIGGER_HINTS[form.triggerType] ?? '事件触发后判定')
const isStatusTrigger = computed(() => form.triggerType === 'STATUS_CHANGE')
const isFieldTrigger = computed(() => form.triggerType === 'FIELD_CHANGE')
const scopeLocked = computed(() => form.scopeId !== null)
const hasWebhookConfig = computed(() => (meta.value?.webhookConfigs.length ?? 0) > 0)

const scopeLabel = computed(() => {
  if (form.scopeId === null) return form.scopeType === 'VENDOR' ? '未选厂商' : '未选域'
  return form.scopeType === 'VENDOR'
    ? platform.vendorName(form.scopeId)
    : platform.domainName(form.scopeId)
})

function buildCondition(): AutomationCondition {
  const condition: AutomationCondition = {}
  if (form.types.length) condition.types = [...form.types]
  if (form.priorityIn.length) condition.priorityIn = [...form.priorityIn]
  if (form.statusIn.length) condition.statusIn = [...form.statusIn]
  if (isStatusTrigger.value && form.approvalResults.length) {
    condition.approvalResults = [...form.approvalResults]
  }
  if (form.estimate) {
    condition.estimateDays = { op: form.estimateOp, value: Number(form.estimateValue) }
  }
  // 作用域已覆盖的那一维不再写进条件 —— 前端先说人话，服务端 CONDITION_WIDENS_SCOPE 只做兜底
  if (form.scopeType !== 'DOMAIN' && form.domainIds.length) condition.domainIds = [...form.domainIds]
  if (form.scopeType !== 'VENDOR' && form.vendorIds.length) condition.vendorIds = [...form.vendorIds]
  if (isFieldTrigger.value && form.fields.length) condition.fields = [...form.fields]
  return condition
}

function buildActions(): AutomationAction[] {
  return form.actions.map((action) => {
    const base: AutomationAction = { type: action.type }
    if (action.type === 'NOTIFY') base.recipients = [...action.recipients]
    if (action.type === 'ASSIGN_OWNER' && action.userId !== null) base.userId = action.userId
    if (action.type === 'ADD_TAG') base.tag = action.tag.trim()
    if (action.type === 'WEBHOOK') {
      if (action.configId !== null) base.configId = action.configId
      base.path = action.path.trim()
    }
    return base
  })
}

const sentenceRule = computed(() => ({
  triggerType: form.triggerType,
  condition: buildCondition(),
  actions: buildActions(),
  scopeType: form.scopeType,
  scopeId: form.scopeId,
  scopeLabel: scopeLabel.value,
  enabled: form.enabled,
}))

function newAction(type: string): EditorAction {
  return {
    key: ++actionSeq,
    type,
    recipients: [],
    userId: null,
    tag: '',
    configId: null,
    path: '',
  }
}

function addAction() {
  if (form.actions.length >= 10) return
  const type = meta.value?.actions[0]?.code ?? 'NOTIFY'
  form.actions.push(newAction(type))
}

function setActionType(action: EditorAction, type: string) {
  if (action.type === type) return
  action.type = type
  action.recipients = []
  action.userId = null
  action.tag = ''
  action.configId = null
  action.path = ''
}

function moveAction(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= form.actions.length) return
  const copy = [...form.actions]
  const [item] = copy.splice(index, 1)
  copy.splice(target, 0, item)
  form.actions = copy
}

function removeAction(index: number) {
  form.actions.splice(index, 1)
  for (const key of Object.keys(errors)) {
    if (key.startsWith('actions.') && Number(key.split('.')[1]) > index) {
      const next = `actions.${Number(key.split('.')[1]) - 1}`
      errors[next] = errors[key]
      delete errors[key]
    } else if (key === `actions.${index}`) {
      delete errors[key]
    }
  }
}

/* ---------------- 收件人（3 类：用户 / 角色 / 域负责人） ---------------- */
function usersOf(action: EditorAction): number[] {
  return action.recipients
    .filter((item) => item.kind === 'USER' && item.id !== undefined && item.id !== null)
    .map((item) => Number(item.id))
}

function setUsers(action: EditorAction, ids: number[]) {
  action.recipients = [
    ...action.recipients.filter((item) => item.kind !== 'USER'),
    ...ids.map((id) => ({ kind: 'USER', id: Number(id) }) as AutomationRecipient),
  ]
}

function rolesOf(action: EditorAction): string[] {
  return action.recipients
    .filter((item) => item.kind === 'ROLE' && typeof item.code === 'string')
    .map((item) => String(item.code))
}

function setRoles(action: EditorAction, codes: string[]) {
  action.recipients = [
    ...action.recipients.filter((item) => item.kind !== 'ROLE'),
    ...codes.map((code) => ({ kind: 'ROLE', code }) as AutomationRecipient),
  ]
}

function hasDomainOwner(action: EditorAction): boolean {
  return action.recipients.some((item) => item.kind === 'DOMAIN_OWNER')
}

function toggleDomainOwner(action: EditorAction, on: boolean) {
  action.recipients = action.recipients.filter((item) => item.kind !== 'DOMAIN_OWNER')
  if (on) action.recipients.push({ kind: 'DOMAIN_OWNER' })
}

/* ---------------- 条件行开关 ---------------- */
type RowKey = 'types' | 'priorityIn' | 'statusIn' | 'approvalResults' | 'estimate' | 'domainIds' | 'vendorIds' | 'fields'

function toggleRow(key: RowKey, on: boolean) {
  if (key === 'estimate') {
    form.estimate = on
    return
  }
  if (on) {
    if (key === 'types') form.types = REQUIREMENT_TYPES.map((item) => item.value as string)
    if (key === 'priorityIn') form.priorityIn = [...PRIORITIES]
    if (key === 'statusIn') form.statusIn = [...statusOptions.value]
    if (key === 'approvalResults') form.approvalResults = APPROVAL_RESULTS.map((item) => item.code)
    if (key === 'fields') form.fields = (meta.value?.fields ?? []).slice()
    return
  }
  if (key === 'types') form.types = []
  if (key === 'priorityIn') form.priorityIn = []
  if (key === 'statusIn') form.statusIn = []
  if (key === 'approvalResults') form.approvalResults = []
  if (key === 'domainIds') form.domainIds = []
  if (key === 'vendorIds') form.vendorIds = []
  if (key === 'fields') form.fields = []
}

/* ---------------- 校验：blur 单行 + 提交全量 + 服务端字段错误 ---------------- */
function setError(key: string, message: string) {
  if (message) errors[key] = message
  else delete errors[key]
}

function validateName(): boolean {
  const value = form.name.trim()
  const message = !value ? '请填写规则名称' : value.length > 100 ? '名称不超过 100 个字符' : ''
  setError('name', message)
  return !message
}

function validatePriority(): boolean {
  const value = Number(form.priority)
  const message =
    !Number.isInteger(value) || value < 0 || value > 999 ? '优先级需为 0–999 的整数' : ''
  setError('priority', message)
  return !message
}

function validateScope(): boolean {
  const message = form.scopeId === null ? '请选择作用域' : ''
  setError('scopeId', message)
  return !message
}

function validateAction(action: EditorAction, index: number): boolean {
  const key = `actions.${index}`
  let message = ''
  if (action.type === 'NOTIFY' && action.recipients.length === 0) {
    message = '通知动作至少需要一个收件人'
  } else if (action.type === 'ASSIGN_OWNER' && action.userId === null) {
    message = '请选择要指派给谁'
  } else if (action.type === 'ADD_TAG') {
    const tag = action.tag.trim()
    if (!tag) message = '请填写标签'
    else if (tag.length > 32) message = '标签不超过 32 个字符'
  } else if (action.type === 'WEBHOOK') {
    if (action.configId === null) message = '请选择集成配置'
    else if (!action.path.trim()) message = '请填写回调路径'
    else if (!action.path.trim().startsWith('/') || action.path.includes('://') || action.path.includes('..')) {
      message = '回调路径需以 / 开头，且不包含 :// 或 ..'
    }
  }
  setError(key, message)
  return !message
}

function validateAll(): boolean {
  validateName()
  validatePriority()
  validateScope()
  if (!form.actions.length) setError('actions', '至少添加一个动作')
  else {
    delete errors.actions
    form.actions.forEach((action, index) => validateAction(action, index))
  }
  const failed = FIELD_ORDER.some((key) => Boolean(errors[key]))
  if (failed || Object.keys(errors).some((key) => key.startsWith('actions.'))) {
    focusFirstError()
    return false
  }
  return true
}

function focusFirstError() {
  void (async () => {
    const key =
      FIELD_ORDER.find((item) => errors[item]) ??
      Object.keys(errors).find((item) => errors[item] && item.startsWith('actions.'))
    if (!key) return
    const id = key === 'scopeId' ? 'field-scope' : key.startsWith('actions.') ? `action-${key.split('.')[1]}` : `field-${key}`
    await Promise.resolve()
    const host = document.getElementById(id)
    if (!host) return
    host.scrollIntoView({ block: 'center', behavior: 'smooth' })
    const focusable = host.querySelector<HTMLElement>('input, textarea, select, button')
    focusable?.focus()
  })()
}

function applyServerError(caught: ApiError) {
  if (caught.status === 409) {
    const current = currentVersionOf(caught.detail)
    banner.value = {
      message: `规则已被他人修改（服务端当前 v${current ?? '?'}），请刷新后重试`,
      code: caught.code,
      conflict: true,
    }
    return
  }
  const field = fieldOf(caught.detail)
  if (field) {
    const key = normalizeField(field)
    setError(key, caught.message)
    banner.value = { message: caught.message, code: caught.code }
    focusFirstError()
    return
  }
  banner.value = { message: caught.message || '保存失败，请重试', code: caught.code }
}

function normalizeField(field: string): string {
  if (field === 'name' || field.endsWith('.name')) return 'name'
  if (field === 'priority' || field.endsWith('.priority')) return 'priority'
  if (field.startsWith('scope')) return 'scopeId'
  const match = /^actions(?:\.|\[)(\d+)/.exec(field)
  if (match) return `actions.${match[1]}`
  return 'condition'
}

/* ---------------- 载入 / 保存 ---------------- */
function hydrate(loaded: AutomationRule) {
  rule.value = loaded
  form.name = loaded.name
  form.enabled = loaded.enabled
  form.triggerType = loaded.triggerType || (meta.value?.triggers[0]?.code ?? '')
  form.scopeType = loaded.scopeType || 'DOMAIN'
  form.scopeId = loaded.scopeId ?? null
  form.priority = loaded.priority
  const condition = loaded.condition ?? {}
  form.types = condition.types ? [...condition.types] : []
  form.priorityIn = condition.priorityIn ? [...condition.priorityIn] : []
  form.statusIn = condition.statusIn ? [...condition.statusIn] : []
  form.approvalResults = condition.approvalResults ? [...condition.approvalResults] : []
  form.estimate = Boolean(condition.estimateDays)
  form.estimateOp = condition.estimateDays?.op ?? 'gte'
  form.estimateValue = condition.estimateDays?.value ?? 10
  form.domainIds = condition.domainIds ? [...condition.domainIds] : []
  form.vendorIds = condition.vendorIds ? [...condition.vendorIds] : []
  form.fields = condition.fields ? [...condition.fields] : []
  form.actions = (loaded.actions ?? []).map((action) => ({
    key: ++actionSeq,
    type: action.type,
    recipients: action.recipients ? action.recipients.map((item) => ({ ...item })) : [],
    userId: action.userId ?? null,
    tag: action.tag ?? '',
    configId: action.configId ?? null,
    path: action.path ?? '',
  }))
}

async function loadRule() {
  if (!isEdit.value) return
  loading.value = true
  try {
    const loaded = await getRule(ruleId.value)
    hydrate(loaded)
    notFound.value = false
    loadError.value = null
  } catch (caught) {
    const error = asApiError(caught)
    if (error.status === 404 || error.code === 'AUTOMATION_RULE_NOT_FOUND') notFound.value = true
    else loadError.value = error
  } finally {
    loading.value = false
  }
}

async function reloadRule() {
  banner.value = null
  await loadRule()
}

function asApiError(caught: unknown): ApiError {
  return caught instanceof ApiError
    ? caught
    : new ApiError('UNKNOWN', '加载失败，请重试', 0, caught)
}

async function save() {
  if (saving.value || !canManage.value) return
  banner.value = null
  if (!validateAll()) return
  saving.value = true
  try {
    const cmd: AutomationRuleCmd = {
      name: form.name.trim(),
      enabled: form.enabled,
      triggerType: form.triggerType,
      scopeType: form.scopeType,
      scopeId: form.scopeId,
      priority: Number(form.priority),
      condition: buildCondition(),
      actions: buildActions(),
    }
    if (isEdit.value) cmd.version = rule.value?.version ?? 0
    const saved = isEdit.value
      ? await updateRule(ruleId.value, cmd)
      : await createRule(cmd)
    ElMessage.success(isEdit.value ? '规则已保存' : '规则已创建')
    await router.push({ path: '/automation/rules', query: { flash: String(saved.id) } })
  } catch (caught) {
    applyServerError(asApiError(caught))
  } finally {
    saving.value = false
  }
}

function cancel() {
  void router.push('/automation/rules')
}

function chooseScope(type: string) {
  if (form.scopeType === type) return
  form.scopeType = type
  form.scopeId = null
  form.domainIds = []
  form.vendorIds = []
}

onMounted(async () => {
  void platform.ensure()
  try {
    meta.value = await getMeta()
  } catch (caught) {
    loadError.value = asApiError(caught)
    loading.value = false
    return
  }
  if (isEdit.value) {
    await loadRule()
  } else {
    form.triggerType = meta.value.triggers[0]?.code ?? 'REQUIREMENT_CREATED'
    form.actions.push(newAction(meta.value.actions[0]?.code ?? 'NOTIFY'))
    loading.value = false
  }
  try {
    roleOptions.value = await listRoles()
  } catch {
    roleOptions.value = []
  }
})
</script>

<template>
  <div class="auto-page">
    <div class="auto-crumb">
      <router-link class="auto-link" to="/automation/rules">自动化规则</router-link>
      <span> / </span>
      <span>{{ isEdit ? '编辑规则' : '新建规则' }}</span>
    </div>

    <div class="auto-head">
      <div class="auto-title-row">
        <h2 class="auto-title">{{ isEdit ? rule?.name || '编辑规则' : '新建规则' }}</h2>
        <span v-if="isEdit && rule" class="rule-version" title="规则版本（每次保存 +1）">v{{ rule.version }}</span>
      </div>
      <span class="auto-count">{{ triggerHint }}</span>
    </div>

    <div v-if="!canManage" class="readonly-banner" role="status">
      没有「管理自动化规则」的操作权限，当前为只读查看。
    </div>

    <div v-if="banner" class="auto-banner" role="alert">
      <span class="auto-banner__code">{{ banner.code }}</span>
      <span>{{ banner.message }}</span>
      <span class="auto-banner__spacer" />
      <el-button v-if="banner.conflict" size="small" @click="reloadRule">刷新</el-button>
      <el-button v-else size="small" text @click="banner = null">知道了</el-button>
    </div>

    <ErrorState
      v-if="loadError"
      :message="loadError.message"
      :code="loadError.code"
      :status="loadError.status"
      @retry="loadRule"
    />

    <EmptyState
      v-else-if="notFound"
      variant="forbidden"
      icon="search"
      title="规则不存在或已被移除"
      description="它可能已被停用后清理，或者链接里的编号不正确。"
      action-text="返回列表"
      @action="cancel"
    />

    <div v-else-if="loading" class="auto-form" aria-busy="true">
      <div class="auto-form__main">
        <div v-for="index in 3" :key="index" class="auto-skel__block" />
      </div>
      <aside class="auto-summary">
        <div class="auto-skel__bar auto-skel__bar--sentence" />
        <div class="auto-skel__bar auto-skel__bar--sentence" />
      </aside>
    </div>

    <form v-else class="auto-form" @submit.prevent="save">
      <div class="auto-form__main">
        <!-- 基础信息 -->
        <section class="auto-section" id="field-name">
          <h3 class="auto-section__title">基础信息</h3>
          <div class="auto-field">
            <label class="auto-field__label" for="rule-name">名称</label>
            <el-input
              id="rule-name"
              v-model="form.name"
              maxlength="100"
              show-word-limit
              placeholder="例如：P0/P1 新建通知域负责人"
              :disabled="!canManage"
              @blur="validateName"
            />
            <p v-if="errors.name" class="auto-field__err">{{ errors.name }}</p>
          </div>
          <div class="auto-field" id="field-priority">
            <label class="auto-field__label" for="rule-priority">
              优先级
              <span class="auto-hint">数值小者先执行；同优先级按创建先后</span>
            </label>
            <el-input-number
              id="rule-priority"
              v-model="form.priority"
              :min="0"
              :max="999"
              :step="10"
              :disabled="!canManage"
              @blur="validatePriority"
            />
            <p v-if="errors.priority" class="auto-field__err">{{ errors.priority }}</p>
          </div>
          <div class="auto-field auto-field--row">
            <label class="auto-field__label" for="rule-enabled">启用</label>
            <el-switch
              id="rule-enabled"
              v-model="form.enabled"
              :disabled="!canManage"
              :aria-label="`启用规则 ${form.name || '未命名规则'}`"
            />
            <span class="auto-hint">停用后，下一次事件起不再执行；已产生的日志与审计保留。</span>
          </div>
        </section>

        <!-- ① 什么时候 -->
        <section class="auto-section">
          <h3 class="auto-section__title">① 什么时候</h3>
          <div class="trigger-grid" role="radiogroup" aria-label="触发类型">
            <button
              v-for="trigger in meta?.triggers ?? []"
              :key="trigger.code"
              type="button"
              class="trigger-card"
              :class="{ 'is-on': form.triggerType === trigger.code }"
              role="radio"
              :aria-checked="form.triggerType === trigger.code"
              :disabled="!canManage"
              @click="form.triggerType = trigger.code"
            >
              <span class="trigger-card__title">{{ trigger.label }}</span>
              <span class="trigger-card__desc">{{ TRIGGER_HINTS[trigger.code] ?? '' }}</span>
            </button>
          </div>
        </section>

        <!-- ② 作用域 -->
        <section class="auto-section" id="field-scope">
          <h3 class="auto-section__title">② 作用域</h3>
          <div class="auto-field">
            <div class="seg" role="radiogroup" aria-label="作用域类型">
              <button
                v-for="scope in SCOPE_TYPES"
                :key="scope.code"
                type="button"
                class="seg__btn"
                :class="{ 'is-on': form.scopeType === scope.code }"
                role="radio"
                :aria-checked="form.scopeType === scope.code"
                :disabled="!canManage"
                @click="chooseScope(scope.code)"
              >
                {{ scope.label }}
              </button>
            </div>
            <div class="auto-field__inline">
              <el-select
                v-model="form.scopeId"
                filterable
                clearable
                :placeholder="form.scopeType === 'VENDOR' ? '选择厂商' : '选择业务域'"
                :disabled="!canManage"
                style="width: 280px"
                @change="validateScope"
              >
                <el-option
                  v-for="item in form.scopeType === 'VENDOR' ? vendorOptions : domainOptions"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
              <span class="auto-hint">规则只能看见并作用于这个范围</span>
            </div>
            <p v-if="errors.scopeId" class="auto-field__err">{{ errors.scopeId }}</p>
          </div>
        </section>

        <!-- ③ 条件 -->
        <section class="auto-section">
          <h3 class="auto-section__title">③ 条件</h3>
          <p class="auto-hint">全部条件之间是「且」，同一行内是「或」；不勾选即不限制。</p>
          <p v-if="errors.condition" class="auto-field__err">{{ errors.condition }}</p>

          <div class="cond-list">
            <div class="cond-row">
              <el-checkbox
                :model-value="form.types.length > 0"
                :disabled="!canManage"
                @change="(value: string | number | boolean) => toggleRow('types', Boolean(value))"
              >
                需求类型
              </el-checkbox>
              <div class="cond-row__control">
                <el-checkbox-group v-if="form.types.length" v-model="form.types" :disabled="!canManage">
                  <el-checkbox v-for="item in REQUIREMENT_TYPES" :key="item.value" :value="item.value">
                    {{ item.label }}
                  </el-checkbox>
                </el-checkbox-group>
                <span v-else class="auto-hint">不限</span>
              </div>
            </div>

            <div class="cond-row">
              <el-checkbox
                :model-value="form.priorityIn.length > 0"
                :disabled="!canManage"
                @change="(value: string | number | boolean) => toggleRow('priorityIn', Boolean(value))"
              >
                优先级
              </el-checkbox>
              <div class="cond-row__control">
                <el-checkbox-group v-if="form.priorityIn.length" v-model="form.priorityIn" :disabled="!canManage">
                  <el-checkbox v-for="item in PRIORITIES" :key="item" :value="item">{{ item }}</el-checkbox>
                </el-checkbox-group>
                <span v-else class="auto-hint">不限</span>
              </div>
            </div>

            <div class="cond-row">
              <el-checkbox
                :model-value="form.statusIn.length > 0"
                :disabled="!canManage"
                @change="(value: string | number | boolean) => toggleRow('statusIn', Boolean(value))"
              >
                状态
              </el-checkbox>
              <div class="cond-row__control">
                <el-select
                  v-if="form.statusIn.length"
                  v-model="form.statusIn"
                  multiple
                  filterable
                  collapse-tags
                  collapse-tags-tooltip
                  :disabled="!canManage"
                  style="width: 100%"
                >
                  <el-option
                    v-for="status in statusOptions"
                    :key="status"
                    :label="dict.label('requirement', status)"
                    :value="status"
                  />
                </el-select>
                <span v-else class="auto-hint">不限</span>
              </div>
            </div>

            <div class="cond-row">
              <el-checkbox
                :model-value="form.approvalResults.length > 0"
                :disabled="!canManage || !isStatusTrigger"
                :title="isStatusTrigger ? '' : '当前触发类型不使用该条件'"
                @change="(value: string | number | boolean) => toggleRow('approvalResults', Boolean(value))"
              >
                审批结果
              </el-checkbox>
              <div class="cond-row__control">
                <el-checkbox-group
                  v-if="form.approvalResults.length && isStatusTrigger"
                  v-model="form.approvalResults"
                  :disabled="!canManage"
                >
                  <el-checkbox v-for="item in APPROVAL_RESULTS" :key="item.code" :value="item.code">
                    {{ item.label }}
                  </el-checkbox>
                </el-checkbox-group>
                <span v-else class="auto-hint">
                  {{ isStatusTrigger ? '不限' : '仅「状态流转」触发时可用' }}
                </span>
              </div>
            </div>

            <div class="cond-row">
              <el-checkbox
                :model-value="form.estimate"
                :disabled="!canManage"
                @change="(value: string | number | boolean) => toggleRow('estimate', Boolean(value))"
              >
                预估人天
              </el-checkbox>
              <div class="cond-row__control auto-field__inline">
                <template v-if="form.estimate">
                  <el-select v-model="form.estimateOp" :disabled="!canManage" style="width: 84px">
                    <el-option
                      v-for="op in meta?.estimateOps ?? []"
                      :key="op"
                      :label="OP_LABELS[op] ?? op"
                      :value="op"
                    />
                  </el-select>
                  <el-input-number v-model="form.estimateValue" :min="0" :max="1000" :disabled="!canManage" />
                </template>
                <span v-else class="auto-hint">不限</span>
              </div>
            </div>

            <div class="cond-row">
              <el-checkbox
                :model-value="form.domainIds.length > 0"
                :disabled="!canManage || form.scopeType === 'DOMAIN'"
                @change="(value: string | number | boolean) => toggleRow('domainIds', Boolean(value))"
              >
                域
              </el-checkbox>
              <div class="cond-row__control">
                <span v-if="form.scopeType === 'DOMAIN'" class="cond-eq">= 作用域</span>
                <el-select
                  v-else-if="form.domainIds.length"
                  v-model="form.domainIds"
                  multiple
                  filterable
                  collapse-tags
                  :disabled="!canManage"
                  style="width: 100%"
                >
                  <el-option v-for="item in domainOptions" :key="item.id" :label="item.name" :value="item.id" />
                </el-select>
                <span v-else class="auto-hint">不限</span>
              </div>
            </div>

            <div class="cond-row">
              <el-checkbox
                :model-value="form.vendorIds.length > 0"
                :disabled="!canManage || form.scopeType === 'VENDOR'"
                @change="(value: string | number | boolean) => toggleRow('vendorIds', Boolean(value))"
              >
                厂商
              </el-checkbox>
              <div class="cond-row__control">
                <span v-if="form.scopeType === 'VENDOR'" class="cond-eq">= 作用域</span>
                <el-select
                  v-else-if="form.vendorIds.length"
                  v-model="form.vendorIds"
                  multiple
                  filterable
                  collapse-tags
                  :disabled="!canManage"
                  style="width: 100%"
                >
                  <el-option v-for="item in vendorOptions" :key="item.id" :label="item.name" :value="item.id" />
                </el-select>
                <span v-else class="auto-hint">不限</span>
              </div>
            </div>

            <div class="cond-row">
              <el-checkbox
                :model-value="form.fields.length > 0"
                :disabled="!canManage || !isFieldTrigger"
                :title="isFieldTrigger ? '' : '当前触发类型不使用该条件'"
                @change="(value: string | number | boolean) => toggleRow('fields', Boolean(value))"
              >
                字段
              </el-checkbox>
              <div class="cond-row__control">
                <el-checkbox-group v-if="form.fields.length && isFieldTrigger" v-model="form.fields" :disabled="!canManage">
                  <el-checkbox v-for="field in meta?.fields ?? []" :key="field" :value="field">
                    {{ FIELD_LABELS[field] ?? field }}
                  </el-checkbox>
                </el-checkbox-group>
                <span v-else class="auto-hint">
                  {{ isFieldTrigger ? '不限' : '仅「字段变更」触发时可用' }}
                </span>
              </div>
            </div>
          </div>
          <p v-if="!scopeLocked" class="auto-hint">先选作用域，越权条件会在表单里被锁成「= 作用域」。</p>
        </section>

        <!-- ④ 动作 -->
        <section class="auto-section">
          <h3 class="auto-section__title">④ 动作</h3>
          <p v-if="errors.actions" class="auto-field__err">{{ errors.actions }}</p>

          <div class="action-list">
            <div
              v-for="(action, index) in form.actions"
              :id="`action-${index}`"
              :key="action.key"
              class="action-card"
            >
              <div class="action-card__head">
                <div class="seg seg--sm" role="radiogroup" :aria-label="`动作 ${index + 1}`">
                  <button
                    v-for="type in meta?.actions ?? []"
                    :key="type.code"
                    type="button"
                    class="seg__btn"
                    :class="{ 'is-on': action.type === type.code }"
                    role="radio"
                    :aria-checked="action.type === type.code"
                    :disabled="!canManage"
                    @click="setActionType(action, type.code)"
                  >
                    {{ type.label }}
                  </button>
                </div>
                <span class="action-card__ops">
                  <button
                    type="button"
                    class="icon-btn"
                    title="上移"
                    aria-label="上移"
                    :disabled="index === 0 || !canManage"
                    @click="moveAction(index, -1)"
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    class="icon-btn"
                    title="下移"
                    aria-label="下移"
                    :disabled="index === form.actions.length - 1 || !canManage"
                    @click="moveAction(index, 1)"
                  >
                    ↓
                  </button>
                  <button
                    type="button"
                    class="icon-btn"
                    title="移除"
                    :disabled="!canManage"
                    @click="removeAction(index)"
                  >
                    移除
                  </button>
                </span>
              </div>

              <div v-if="action.type === 'NOTIFY'" class="action-card__body recipient-row">
                <el-select
                  :model-value="usersOf(action)"
                  multiple
                  filterable
                  collapse-tags
                  placeholder="选用户"
                  :disabled="!canManage"
                  style="min-width: 180px; flex: 1"
                  @update:model-value="(value: number[]) => setUsers(action, value)"
                >
                  <el-option
                    v-for="user in userOptions"
                    :key="user.id"
                    :label="user.name || user.username"
                    :value="user.id"
                  />
                </el-select>
                <el-select
                  :model-value="rolesOf(action)"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  collapse-tags
                  placeholder="选角色（可输入 code）"
                  :disabled="!canManage"
                  style="min-width: 180px; flex: 1"
                  @update:model-value="(value: string[]) => setRoles(action, value)"
                >
                  <el-option
                    v-for="role in roleOptions"
                    :key="role.code"
                    :label="`${role.name}（${role.code}）`"
                    :value="role.code"
                  />
                </el-select>
                <el-checkbox
                  :model-value="hasDomainOwner(action)"
                  :disabled="!canManage"
                  @change="(value: string | number | boolean) => toggleDomainOwner(action, Boolean(value))"
                >
                  域负责人
                </el-checkbox>
              </div>

              <div v-else-if="action.type === 'ASSIGN_OWNER'" class="action-card__body">
                <el-select
                  v-model="action.userId"
                  filterable
                  placeholder="指派给"
                  :disabled="!canManage"
                  style="width: 280px"
                >
                  <el-option
                    v-for="user in userOptions"
                    :key="user.id"
                    :label="user.name || user.username"
                    :value="user.id"
                  />
                </el-select>
                <span class="auto-hint">目标必须落在作用域内，否则这次动作记 TARGET_INVALID。</span>
              </div>

              <div v-else-if="action.type === 'ADD_TAG'" class="action-card__body">
                <el-input
                  v-model="action.tag"
                  maxlength="32"
                  show-word-limit
                  placeholder="例如：大需求"
                  :disabled="!canManage"
                  style="width: 280px"
                />
                <span class="auto-hint">重复打标是幂等的：已存在即视为成功，不报错。</span>
              </div>

              <div v-else-if="action.type === 'WEBHOOK'" class="action-card__body auto-field__inline">
                <template v-if="hasWebhookConfig">
                  <el-select
                    v-model="action.configId"
                    placeholder="集成配置"
                    :disabled="!canManage"
                    style="width: 220px"
                  >
                    <el-option
                      v-for="config in meta?.webhookConfigs ?? []"
                      :key="config.id"
                      :label="webhookConfigLabel(config)"
                      :value="config.id"
                    />
                  </el-select>
                  <el-input
                    v-model="action.path"
                    placeholder="/hooks/requirement"
                    :disabled="!canManage"
                    style="width: 220px"
                  />
                  <span class="auto-hint">域名与密钥来自集成配置，规则里不保存 URL 与密钥。</span>
                </template>
                <template v-else>
                  <el-select disabled placeholder="没有可用的集成配置" style="width: 220px" />
                  <el-input disabled placeholder="/hooks/requirement" style="width: 220px" />
                  <span class="auto-hint">
                    先到
                    <router-link class="auto-link" to="/admin/integrations">平台管理 → 集成配置</router-link>
                    登记并启用一个带密钥的配置。
                  </span>
                </template>
              </div>

              <p v-if="errors[`actions.${index}`]" class="auto-field__err">
                {{ errors[`actions.${index}`] }}
              </p>
            </div>
          </div>

          <button
            type="button"
            class="auto-add"
            :disabled="form.actions.length >= 10 || !canManage"
            @click="addAction"
          >
            + 添加动作
            <span class="auto-hint">最多 10 个；按顺序执行，数据库动作先于通知与外呼</span>
          </button>
        </section>
      </div>

      <aside class="auto-summary">
        <h3 class="auto-summary__title">规则摘要</h3>
        <AutomationRuleSentence
          :rule="sentenceRule"
          size="preview"
          :meta="meta"
          :user-name="platform.userName"
          :domain-name="platform.domainName"
          :vendor-name="platform.vendorName"
        />
        <p class="auto-summary__note">
          保存后立即生效。执行是异步的，命中与否看「执行日志」——条件没命中也会留一行原因。
        </p>
      </aside>

      <footer class="auto-footer">
        <el-button :disabled="saving" @click="cancel">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canManage || saving" @click="save">
          保存
        </el-button>
      </footer>
    </form>
  </div>
</template>
