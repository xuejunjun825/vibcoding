<script setup lang="ts">
import { computed } from 'vue'
import { useDictStore } from '@/stores/dict'
import {
  actionLabel,
  triggerLabel,
  type AutomationAction,
  type AutomationCondition,
  type AutomationMeta,
} from '@/api/automation'

/**
 * 规则句式 —— 全站唯一渲染点（DESIGN §0.1 / §3.2）。
 *
 * 规则的可读形态是**一句因果句**：`当 [触发][作用域] 且 [条件] → [动作]`。
 * 列表首列、编辑器右侧摘要、编辑回显三处都渲染这个组件；任何一处自己拼字符串，
 * 用户就会在两个页面看到两种规则。
 *
 * 三条反模式（DESIGN §0.5）在这里被结构性挡掉：
 * 1. 不渲染原始 JSON —— 只有 chip；
 * 2. 条件为空时写 12px「无条件：…」，不显示「全部需求」大字；
 * 3. 停用（`enabled === false`）只降 token 字色，不整行降透明度、不灰掉。
 */
export interface RuleSentenceInput {
  triggerType?: string | null
  condition?: AutomationCondition | null
  actions?: AutomationAction[] | null
  scopeType?: string | null
  scopeId?: number | null
  scopeLabel?: string | null
  enabled?: boolean | null
}

const props = withDefaults(
  defineProps<{
    rule: RuleSentenceInput
    /** list = 表格首列；preview = 编辑器摘要栏（同文案，仅间距略大） */
    size?: 'list' | 'preview'
    /** 紧凑（≤1280 时把最近执行并到句尾用） */
    dense?: boolean
    meta?: AutomationMeta | null
    /** 可选解析器：编辑器与列表都从同一份字典取值，缺省时退化为 `#id` */
    userName?: (id?: number | null) => string
    domainName?: (id?: number | null) => string
    vendorName?: (id?: number | null) => string
  }>(),
  { size: 'list', dense: false, meta: null },
)

const dict = useDictStore()

/** 条件 chip 最多 3 枚，其余折成 +N（title 列全，信息不丢） */
const CONDITION_LIMIT = 3

interface Token {
  text: string
  title: string
}

function joinLabel(codes: string[], label: (code: string) => string): string {
  return codes.map((code) => label(code)).join('/')
}

const conditionTokens = computed<Token[]>(() => {
  const condition = props.rule.condition ?? {}
  const tokens: Token[] = []
  const types = condition.types ?? []
  if (types.length) {
    tokens.push({
      text: `类型 ${joinLabel(types, (code) => dict.label('reqType', code))}`,
      title: types.join('、'),
    })
  }
  const priorityIn = condition.priorityIn ?? []
  if (priorityIn.length) {
    // 优先级直接用码（P0/P1）：这是用户认知里的名字，不是待翻译的枚举
    tokens.push({ text: `优先级 ${priorityIn.join('/')}`, title: priorityIn.join('、') })
  }
  const statusIn = condition.statusIn ?? []
  if (statusIn.length) {
    tokens.push({
      text: `状态 ${joinLabel(statusIn, (code) => dict.label('requirement', code))}`,
      title: statusIn.map((code) => dict.label('requirement', code)).join('、'),
    })
  }
  const approvalResults = condition.approvalResults ?? []
  if (approvalResults.length) {
    const map: Record<string, string> = { APPROVED: '通过', REJECTED: '驳回', WITHDRAWN: '撤回' }
    tokens.push({
      text: `审批 ${approvalResults.map((code) => map[code] ?? code).join('/')}`,
      title: approvalResults.join('、'),
    })
  }
  const estimate = condition.estimateDays
  if (estimate && Number.isFinite(Number(estimate.value))) {
    const opMap: Record<string, string> = { gt: '>', gte: '≥', lt: '<', lte: '≤', eq: '=' }
    const symbol = opMap[estimate.op] ?? estimate.op
    tokens.push({ text: `人天 ${symbol}${estimate.value}`, title: `预估人天 ${symbol} ${estimate.value}` })
  }
  const domainIds = condition.domainIds ?? []
  if (domainIds.length) {
    const names = domainIds.map((id) => (props.domainName ? props.domainName(id) : `域 #${id}`))
    tokens.push({ text: `域 ${names.join('/')}`, title: names.join('、') })
  }
  const vendorIds = condition.vendorIds ?? []
  if (vendorIds.length) {
    const names = vendorIds.map((id) => (props.vendorName ? props.vendorName(id) : `厂商 #${id}`))
    tokens.push({ text: `厂商 ${names.join('/')}`, title: names.join('、') })
  }
  const fields = condition.fields ?? []
  if (fields.length) {
    const map: Record<string, string> = {
      priority: '优先级',
      owner_user_id: '负责人',
      estimate_days: '预估人天',
      release_id: '所属版本',
    }
    tokens.push({
      text: `字段 ${joinLabel(fields, (code) => map[code] ?? code)}`,
      title: fields.join('、'),
    })
  }
  return tokens
})

const shownConditions = computed(() => conditionTokens.value.slice(0, CONDITION_LIMIT))
const foldedConditions = computed(() => conditionTokens.value.slice(CONDITION_LIMIT))
const foldedTitle = computed(() => foldedConditions.value.map((token) => token.text).join('；'))

const triggerText = computed(() => triggerLabel(props.meta, props.rule.triggerType))

const scopeText = computed(() => {
  const label = props.rule.scopeLabel
  if (label) return label
  const id = props.rule.scopeId
  if (id === null || id === undefined) return '未选择作用域'
  if (props.rule.scopeType === 'VENDOR') {
    return props.vendorName ? props.vendorName(id) : `厂商 #${id}`
  }
  return props.domainName ? props.domainName(id) : `域 #${id}`
})

function recipientText(action: AutomationAction): string {
  const recipients = action.recipients ?? []
  if (!recipients.length) return '未设收件人'
  const parts = recipients.map((recipient) => {
    if (recipient.kind === 'DOMAIN_OWNER') return '域负责人'
    if (recipient.kind === 'ROLE') return recipient.code ? `角色 ${recipient.code}` : '角色'
    if (recipient.kind === 'USER') {
      return recipient.id !== undefined && recipient.id !== null && props.userName
        ? props.userName(recipient.id)
        : `用户 #${recipient.id ?? '?'}`
    }
    return recipient.code ?? recipient.kind
  })
  return parts.join('、')
}

function actionText(action: AutomationAction): string {
  const label = actionLabel(props.meta, action.type)
  if (action.type === 'NOTIFY') return `${label} ${recipientText(action)}`
  if (action.type === 'ASSIGN_OWNER') {
    const name =
      action.userId !== undefined && action.userId !== null && props.userName
        ? props.userName(action.userId)
        : `用户 #${action.userId ?? '?'}`
    return `${label} ${name}`
  }
  if (action.type === 'ADD_TAG') return `${label} ${action.tag || '未填标签'}`
  if (action.type === 'WEBHOOK') {
    const config = props.meta?.webhookConfigs?.find((item) => item.id === action.configId)
    const configName = config?.name ?? `配置 #${action.configId ?? '?'}`
    const path = action.path || '/'
    return `${label} ${configName} ${path}`
  }
  return label
}

const actionTokens = computed<Token[]>(() =>
  (props.rule.actions ?? []).map((action) => ({
    text: actionText(action),
    title: actionText(action),
  })),
)

const muted = computed(() => props.rule.enabled === false)
const noCondition = computed(() => conditionTokens.value.length === 0)
const noAction = computed(() => actionTokens.value.length === 0)
</script>

<template>
  <p
    class="rule-sentence"
    :class="{
      'rule-sentence--preview': size === 'preview',
      'rule-sentence--dense': dense,
      'is-muted': muted,
    }"
  >
    <span class="rule-sentence__kw">当</span>
    <span class="rule-sentence__chip rule-sentence__chip--trigger">{{ triggerText }}</span>
    <span class="rule-sentence__chip rule-sentence__chip--scope" :title="scopeText">{{ scopeText }}</span>

    <span v-if="noCondition" class="rule-sentence__note">
      无条件：作用域内的全部需求（每次触发都会执行）
    </span>

    <template v-else>
      <span class="rule-sentence__kw">且</span>
      <span
        v-for="token in shownConditions"
        :key="token.text"
        class="rule-sentence__chip rule-sentence__chip--cond"
        :title="token.title"
        >{{ token.text }}</span
      >
      <span
        v-if="foldedConditions.length"
        class="rule-sentence__chip rule-sentence__chip--more"
        :title="foldedTitle"
        >+{{ foldedConditions.length }}</span
      >
    </template>

    <span class="rule-sentence__arrow" role="img" aria-label="则执行">→</span>

    <template v-if="noAction">
      <span class="rule-sentence__chip rule-sentence__chip--empty">还没有动作</span>
    </template>
    <template v-else>
      <span
        v-for="(token, index) in actionTokens"
        :key="`${index}-${token.text}`"
        class="rule-sentence__chip rule-sentence__chip--action"
        :title="token.title"
        >{{ token.text }}</span
      >
    </template>
  </p>
</template>
