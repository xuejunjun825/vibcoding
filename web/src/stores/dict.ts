import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 展示词典（DESIGN §7.3 / §3.5）。
 * 状态“能不能流转”由服务端 allowedActions 决定；这里只负责中文与色调，
 * 因此前端不存在第二套状态机。
 */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info'

export interface StatusMeta {
  label: string
  tone: Tone
}

export type StatusKind =
  | 'requirement'
  | 'defect'
  | 'approval'
  | 'execution'
  | 'release'
  | 'deploy'
  // M6 质量域（TECHNICAL §7.3：10 个新 kind，状态文案唯一来源）
  | 'strategy'
  | 'plan'
  | 'planRound'
  | 'report'
  | 'signoff'
  | 'gate'
  | 'rule'
  | 'waiver'
  | 'review'
  | 'env'

const REQUIREMENT: Record<string, StatusMeta> = {
  // BUS
  SUBMITTED: { label: '已提报', tone: 'primary' },
  APPROVING: { label: '审批中', tone: 'warning' },
  PENDING_REVIEW: { label: '待评审', tone: 'warning' },
  POOLED: { label: '已入池', tone: 'success' },
  // PDR
  PENDING_ANALYSIS: { label: '待分析', tone: 'primary' },
  SCHEDULED: { label: '已排期', tone: 'info' },
  IN_DEV: { label: '开发中', tone: 'primary' },
  DELIVERED: { label: '已交付', tone: 'success' },
  ACCEPTED_CLOSED: { label: '验收关闭', tone: 'success' },
  // RD
  TODO: { label: '待开发', tone: 'info' },
  PENDING_TEST: { label: '待测试', tone: 'warning' },
  TEST_PASSED: { label: '测试通过', tone: 'success' },
  MERGED: { label: '已合入', tone: 'success' },
  DONE: { label: '完成', tone: 'success' },
  // 通用终态
  CLOSED: { label: '已关闭', tone: 'neutral' },
  CANCELLED: { label: '已取消', tone: 'neutral' },
}

const DEFECT: Record<string, StatusMeta> = {
  NEW: { label: '新建', tone: 'primary' },
  IN_PROGRESS: { label: '处理中', tone: 'warning' },
  PENDING_VERIFY: { label: '待验证', tone: 'warning' },
  CLOSED: { label: '关闭', tone: 'success' },
  REOPENED: { label: '重新打开', tone: 'danger' },
}

const APPROVAL: Record<string, StatusMeta> = {
  PENDING: { label: '待审批', tone: 'warning' },
  RUNNING: { label: '审批中', tone: 'warning' },
  APPROVED: { label: '已通过', tone: 'success' },
  REJECTED: { label: '已驳回', tone: 'danger' },
  WITHDRAWN: { label: '已撤回', tone: 'neutral' },
  CANCELLED: { label: '已取消', tone: 'neutral' },
  SKIPPED: { label: '自动跳过', tone: 'neutral' },
}

const EXECUTION: Record<string, StatusMeta> = {
  NOT_RUN: { label: '未执行', tone: 'neutral' },
  PASS: { label: '通过', tone: 'success' },
  FAIL: { label: '失败', tone: 'danger' },
  BLOCKED: { label: '阻塞', tone: 'warning' },
  SKIP: { label: '跳过', tone: 'info' },
}

const RELEASE: Record<string, StatusMeta> = {
  PLAN: { label: '规划中', tone: 'primary' },
  LOCKED: { label: '已锁定', tone: 'warning' },
  PUBLISHED: { label: '已发布', tone: 'success' },
  CANCELLED: { label: '已取消', tone: 'neutral' },
}

const DEPLOY: Record<string, StatusMeta> = {
  DRAFT: { label: '草稿', tone: 'neutral' },
  PENDING_APPROVAL: { label: '待审批', tone: 'warning' },
  APPROVED: { label: '已批准', tone: 'success' },
  REJECTED: { label: '已拒绝', tone: 'danger' },
  EXECUTING: { label: '执行中', tone: 'primary' },
  SUCCESS: { label: '成功', tone: 'success' },
  FAILED: { label: '失败', tone: 'danger' },
  ROLLED_BACK: { label: '已回滚', tone: 'warning' },
}

/** 测试策略：同 Release 多版本，ACTIVE 唯一，旧版 RETIRED */
const STRATEGY: Record<string, StatusMeta> = {
  DRAFT: { label: '草稿', tone: 'neutral' },
  ACTIVE: { label: '生效中', tone: 'success' },
  RETIRED: { label: '已退役', tone: 'neutral' },
}

/** 测试计划（StateTables.PLAN 权威；动作码是连字符 submit-review） */
const PLAN: Record<string, StatusMeta> = {
  DRAFT: { label: '草稿', tone: 'neutral' },
  PENDING_REVIEW: { label: '待审批', tone: 'warning' },
  APPROVED: { label: '已批准', tone: 'info' },
  IN_PROGRESS: { label: '执行中', tone: 'primary' },
  COMPLETED: { label: '已完成', tone: 'success' },
  CLOSED: { label: '已关闭', tone: 'neutral' },
  ARCHIVED: { label: '已归档', tone: 'neutral' },
}

/**
 * 轮次：状态（PLANNING/RUNNING/CLOSED）与关闭结论（PASS/FAIL/CONDITIONAL/CANCELLED）
 * 共用一张表 —— 两者取值不重叠，抽屉里 `label('planRound', round.conclusion)` 直接可用。
 * IN_PROGRESS / DONE 是 R1 归集前旧数据的写法，保留别名不丢中文。
 */
const PLAN_ROUND: Record<string, StatusMeta> = {
  PLANNING: { label: '待开始', tone: 'info' },
  RUNNING: { label: '进行中', tone: 'primary' },
  IN_PROGRESS: { label: '进行中', tone: 'primary' },
  CLOSED: { label: '已关闭', tone: 'neutral' },
  DONE: { label: '已完成', tone: 'success' },
  PASS: { label: '通过', tone: 'success' },
  FAIL: { label: '不通过', tone: 'danger' },
  CONDITIONAL: { label: '有条件通过', tone: 'warning' },
  CANCELLED: { label: '已取消', tone: 'neutral' },
}

/** 报告：状态 + 版本结论共用 */
const REPORT: Record<string, StatusMeta> = {
  SIGNING: { label: '会签中', tone: 'warning' },
  SIGNED: { label: '已生效', tone: 'success' },
  REJECTED: { label: '已驳回', tone: 'danger' },
  SUPERSEDED: { label: '已被替代', tone: 'neutral' },
  PASS: { label: '通过', tone: 'success' },
  FAIL: { label: '不通过', tone: 'danger' },
  CONDITIONAL: { label: '有条件通过', tone: 'warning' },
}

/** 会签：三级顺序（测试负责人 → 质量 → 发布经理），逐级放行 */
const SIGNOFF: Record<string, StatusMeta> = {
  PENDING: { label: '待签署', tone: 'neutral' },
  APPROVED: { label: '已签署', tone: 'success' },
  SIGNED: { label: '已签署', tone: 'success' },
  REJECTED: { label: '已驳回', tone: 'danger' },
}

/** 质量门整体结论（NOT_EVALUATED = 从未求值，fail-closed 不当作通过） */
const GATE: Record<string, StatusMeta> = {
  PASS: { label: '通过', tone: 'success' },
  PASS_WITH_WAIVER: { label: '豁免通过', tone: 'warning' },
  FAIL: { label: '不通过', tone: 'danger' },
  NOT_EVALUATED: { label: '未求值', tone: 'neutral' },
}

/** 单条规则结果：SKIPPED 只允许出现在 enabled=false 的集成门禁上 */
const RULE: Record<string, StatusMeta> = {
  PASS: { label: '通过', tone: 'success' },
  FAIL: { label: '不通过', tone: 'danger' },
  WAIVED: { label: '已豁免', tone: 'warning' },
  SKIPPED: { label: '未启用', tone: 'neutral' },
}

/** 豁免：StateTables 用 ACTIVE，DESIGN 文案写 APPROVED，两种都收 */
const WAIVER: Record<string, StatusMeta> = {
  PENDING: { label: '待审批', tone: 'warning' },
  ACTIVE: { label: '生效中', tone: 'info' },
  APPROVED: { label: '已批准', tone: 'success' },
  REJECTED: { label: '已拒绝', tone: 'danger' },
  EXPIRED: { label: '已过期', tone: 'neutral' },
  REVOKED: { label: '已撤销', tone: 'neutral' },
}

/** Go/No-Go：RECORDED/CONFIRMED 是记录状态，GO/CONDITIONAL/NO_GO 才是决策 */
const REVIEW: Record<string, StatusMeta> = {
  RECORDED: { label: '已记录', tone: 'info' },
  CONFIRMED: { label: '已确认', tone: 'success' },
  GO: { label: 'GO', tone: 'success' },
  CONDITIONAL: { label: '有条件 GO', tone: 'warning' },
  NO_GO: { label: 'NO-GO', tone: 'danger' },
}

/** 环境台账：MAINTENANCE 期间允许执行但要提示，DISABLED 不可被新计划引用 */
const ENV: Record<string, StatusMeta> = {
  AVAILABLE: { label: '可用', tone: 'success' },
  MAINTENANCE: { label: '维护中', tone: 'warning' },
  DISABLED: { label: '已停用', tone: 'neutral' },
}

const DICTS: Record<StatusKind, Record<string, StatusMeta>> = {
  requirement: REQUIREMENT,
  defect: DEFECT,
  approval: APPROVAL,
  execution: EXECUTION,
  release: RELEASE,
  deploy: DEPLOY,
  strategy: STRATEGY,
  plan: PLAN,
  planRound: PLAN_ROUND,
  report: REPORT,
  signoff: SIGNOFF,
  gate: GATE,
  rule: RULE,
  waiver: WAIVER,
  review: REVIEW,
  env: ENV,
}

/** 流转动作中文标签（与 TECHNICAL §3.3 的状态表一一对应） */
export const ACTION_LABELS: Record<string, string> = {
  submit: '提交审批',
  approve: '通过',
  reject: '驳回',
  withdraw: '撤回',
  review_start: '发起评审',
  review_pass: '评审通过',
  review_reject: '评审退回',
  close: '关闭',
  schedule: '排期',
  start_dev: '开始开发',
  deliver: '提交交付',
  accept: '验收通过',
  submit_test: '提交测试',
  test_pass: '测试通过',
  merge: '合入',
  finish: '完成',
  start: '开始处理',
  submit_verify: '提交验证',
  verify_pass: '验证通过',
  verify_reject: '验证不通过',
  reopen: '重新打开',
  close_duplicate: '重复关闭',
  assign: '转派',
  force_release: '强制放行',
  add_sign: '加签',
  remove_sign: '减签',
  transfer: '转办',
  urge: '催办',
  skip: '跳过',
  // —— M6 质量域（StateTables 的动作码是连字符，别写成下划线）——
  'submit-review': '提交审批',
  'close-round': '关闭轮次',
  'force-release': '强制放行',
  'assign-cases': '分配用例',
  sign: '签署',
  supersede: '作废并替代',
  pass: '判定通过',
  pass_with_waiver: '豁免通过',
  fail: '判定不通过',
  replay: '回放快照',
  evaluate: '重新求值',
  activate: '激活',
  retire: '退役',
  generate: '生成报告',
  export: '导出',
  apply: '申请豁免',
  expire: '置为过期',
  revoke: '撤销',
  confirm: '二次确认',
  complete: '完成计划',
  archive: '归档',
}

/**
 * 同名动作在不同对象的语义不同（缺陷的 start 是“开始处理”，计划/轮次是“开始执行”）。
 * 页面按 `actionLabel(action, kind)` 取词，不传 kind 时回落到全局表。
 */
const ACTION_OVERRIDES: Partial<Record<StatusKind, Record<string, string>>> = {
  plan: { start: '开始执行', close: '关闭计划', approve: '批准计划', reject: '驳回计划' },
  planRound: { start: '开始轮次', close: '关闭轮次' },
  strategy: { activate: '激活版本', retire: '退役版本' },
  report: { generate: '生成版本', sign: '签署', reject: '驳回版本' },
  gate: { evaluate: '重新求值', replay: '回放' },
  waiver: { approve: '批准豁免', reject: '拒绝豁免', revoke: '撤销豁免' },
  review: { confirm: '二次确认', create: '记录评审' },
}

/** 危险动作：二次确认 + 必填原因（force-release 已退役，仍按危险动作渲染只读提示） */
export const DANGER_ACTIONS = new Set([
  'close',
  'close_duplicate',
  'reject',
  'withdraw',
  'force_release',
  'force-release',
  'revoke',
  'archive',
])

const PRIORITY: Record<string, { label: string; color: string; hint: string }> = {
  P0: { label: 'P0', color: '#D9363E', hint: '阻塞' },
  P1: { label: 'P1', color: '#C77700', hint: '紧急' },
  P2: { label: 'P2', color: '#2B6FE5', hint: '普通' },
  P3: { label: 'P3', color: '#A9AEB8', hint: '低' },
}

export const useDictStore = defineStore('dict', () => {
  /** 服务端可下发补充词典（/admin/dicts），这里保留合并入口 */
  const overrides = ref<Partial<Record<StatusKind, Record<string, StatusMeta>>>>({})
  const version = ref(0)

  function meta(kind: StatusKind, value: string | null | undefined): StatusMeta {
    const key = (value ?? '').toUpperCase()
    const found = overrides.value[kind]?.[key] ?? DICTS[kind][key]
    if (found) return found
    // 未知枚举不猜：中文原样输出，英文去掉下划线（避免界面出现裸枚举码）
    if (value && /[\u4e00-\u9fa5]/.test(value)) return { label: value, tone: 'neutral' }
    return { label: (value ?? '—').replace(/_/g, ' '), tone: 'neutral' }
  }

  function label(kind: StatusKind, value: string | null | undefined): string {
    return meta(kind, value).label
  }

  function tone(kind: StatusKind, value: string | null | undefined): Tone {
    return meta(kind, value).tone
  }

  /**
   * 动作码 → 中文。质量域的动作码是连字符（submit-review），两类分隔符都要吃，
   * 否则未登记的动作会在按钮上露出英文。
   */
  function actionLabel(action: string, kind?: StatusKind): string {
    const scoped = kind ? ACTION_OVERRIDES[kind]?.[action] : undefined
    return scoped ?? ACTION_LABELS[action] ?? action.replace(/[_-]/g, ' ')
  }

  function priority(value: string | null | undefined) {
    return PRIORITY[(value ?? '').toUpperCase()] ?? { label: value ?? '—', color: '#A9AEB8', hint: '' }
  }

  function merge(kind: StatusKind, dictionary: Record<string, StatusMeta>): void {
    overrides.value = {
      ...overrides.value,
      [kind]: { ...(overrides.value[kind] ?? {}), ...dictionary },
    }
    version.value += 1
  }

  return { meta, label, tone, actionLabel, priority, merge, overrides, version }
})
