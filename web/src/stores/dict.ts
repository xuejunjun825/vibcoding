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
  // M7 集成域（DESIGN §6.9：7 个新 kind；CLOSED/SKIP 的文案只在这里定义一次）
  | 'integration'
  | 'build'
  | 'mr'
  | 'sync'
  | 'admission'
  | 'sonar'
  | 'artifactSource'
  // M8 协作/代码/测试治理域（DESIGN §6 状态词典增量：文案唯一来源）
  | 'policyCheck'
  | 'executorHealth'
  | 'caseReview'
  | 'caseReviewConclusion'
  | 'wikiVisibility'
  | 'mrReviewState'
  // M9 分析域回显（TECHNICAL §5）：与服务端标签注册表 AnalyticsLabels 逐字符串一致
  | 'reqType'
  | 'severity'
  | 'priority'

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

/** 需求类型（与 sys_dict.req_type、服务端注册表 R3 逐字符串一致） */
const REQ_TYPE: Record<string, StatusMeta> = {
  BUS: { label: '业务需求', tone: 'primary' },
  PDR: { label: '产品需求', tone: 'info' },
  RD: { label: '研发需求', tone: 'neutral' },
}

/**
 * 缺陷等级（与 sys_dict.severity、服务端注册表 R5 逐字符串一致）。
 * r14 起统一到 defect.severity 的 P0..P3 —— 旧写法 S1..S4 只在 RoleCatalog 声明过，业务侧零引用。
 */
const SEVERITY: Record<string, StatusMeta> = {
  P0: { label: '致命', tone: 'danger' },
  P1: { label: '严重', tone: 'warning' },
  P2: { label: '一般', tone: 'info' },
  P3: { label: '轻微', tone: 'neutral' },
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

/**
 * M7 集成域七张表（DESIGN §6.9，逐字对齐）。
 * 这七张表的键是**服务端枚举**：`integration`/`build`/`mr`/`sync`/`sonar`/`artifactSource`
 * 由 Java 枚举下发，`admission` 由 `AdmissionItemPolicy` 的四态下发 —— 前端不自造第三套字符串。
 */
const INTEGRATION: Record<string, StatusMeta> = {
  ENABLED: { label: '已启用', tone: 'success' },
  DISABLED: { label: '已关闭', tone: 'warning' },
  UNCONFIGURED: { label: '未配置', tone: 'info' },
}

const BUILD: Record<string, StatusMeta> = {
  QUEUED: { label: '排队中', tone: 'info' },
  RUNNING: { label: '运行中', tone: 'primary' },
  SUCCESS: { label: '成功', tone: 'success' },
  FAILURE: { label: '失败', tone: 'danger' },
  ABORTED: { label: '已中止', tone: 'info' },
}

const MR: Record<string, StatusMeta> = {
  OPENED: { label: '打开', tone: 'info' },
  MERGED: { label: '已合入', tone: 'success' },
  CLOSED: { label: '已关闭', tone: 'info' },
  // GitLab 有 LOCKED 态：不猜成「打开」，否则锁定的 MR 会被当成可推进
  LOCKED: { label: '已锁定', tone: 'warning' },
}

const SYNC: Record<string, StatusMeta> = {
  SUCCESS: { label: '成功', tone: 'success' },
  FAILED: { label: '失败', tone: 'danger' },
  IGNORED: { label: '已忽略', tone: 'info' },
  RETRY_EXHAUSTED: { label: '重试耗尽', tone: 'warning' },
}

const ADMISSION: Record<string, StatusMeta> = {
  PASS: { label: '通过', tone: 'success' },
  FAIL: { label: '失败', tone: 'danger' },
  SKIP: { label: '跳过', tone: 'warning' },
  CLOSED: { label: '已关闭', tone: 'info' },
}

const SONAR: Record<string, StatusMeta> = {
  OK: { label: '通过', tone: 'success' },
  ERROR: { label: '未达标', tone: 'danger' },
  UNBOUND: { label: '未绑定', tone: 'info' },
  UNAVAILABLE: { label: '不可用', tone: 'danger' },
}

const ARTIFACT_SOURCE: Record<string, StatusMeta> = {
  NEXUS: { label: 'Nexus', tone: 'info' },
  MANUAL: { label: '手工', tone: 'warning' },
}

/** M8-C1 分支策略核验：未核验永远是黄的，绝不落绿（AC-11） */
const POLICY_CHECK: Record<string, StatusMeta> = {
  MATCH: { label: '一致', tone: 'success' },
  DRIFT: { label: '有差异', tone: 'warning' },
  UNVERIFIED: { label: '未核验', tone: 'warning' },
}

/** M8-C2 执行器健康：五态由 ExecutorHealth.of 派生，页面不判 24/72 小时 */
const EXECUTOR_HEALTH: Record<string, StatusMeta> = {
  ONLINE: { label: '在线', tone: 'success' },
  STALE: { label: '滞后', tone: 'warning' },
  OFFLINE: { label: '离线', tone: 'danger' },
  NEVER: { label: '从未上报', tone: 'neutral' },
  DISABLED: { label: '已停用', tone: 'neutral' },
}

/** M8-T1 用例评审：改版即失效 */
const CASE_REVIEW: Record<string, StatusMeta> = {
  SUBMITTED: { label: '评审中', tone: 'primary' },
  DONE: { label: '已完成', tone: 'success' },
  INVALIDATED: { label: '已过期', tone: 'warning' },
}

const CASE_REVIEW_CONCLUSION: Record<string, StatusMeta> = {
  PASS: { label: '通过', tone: 'success' },
  NEED_CHANGE: { label: '需修改', tone: 'warning' },
  REJECT: { label: '驳回', tone: 'danger' },
}

/** M8-W0 Wiki 空间可见范围（六出口同源，见 WikiVisibility） */
const WIKI_VISIBILITY: Record<string, StatusMeta> = {
  INHERIT: { label: '继承域范围', tone: 'info' },
  DOMAIN: { label: '仅本域', tone: 'primary' },
  VENDOR: { label: '仅同厂商', tone: 'neutral' },
}

/** M8-C3 MR 评审态（来自 GitLab MR notes 的聚合，不由前端推断） */
const MR_REVIEW_STATE: Record<string, StatusMeta> = {
  PENDING: { label: '待评审', tone: 'warning' },
  APPROVED: { label: '已批准', tone: 'success' },
  CHANGES_REQUESTED: { label: '已要求修改', tone: 'danger' },
}

/**
 * 优先级词条（UI-STYLE §2：P0 红 / P1 橙 / P2 蓝 / P3 灰）。
 * r14 起 `label` 用中文：分析域与该组件回显都不许再露出 P0..P3 裸码；
 * 提交值仍是 code，`tone` 让回显层取色也走同一份词典。
 */
const PRIORITY: Record<string, { label: string; color: string; hint: string; tone: Tone }> = {
  P0: { label: '紧急', color: '#D9363E', hint: '阻塞', tone: 'danger' },
  P1: { label: '高', color: '#C77700', hint: '紧急', tone: 'warning' },
  P2: { label: '中', color: '#2B6FE5', hint: '普通', tone: 'primary' },
  P3: { label: '低', color: '#A9AEB8', hint: '低', tone: 'neutral' },
}

/** priority 也是分析域可作行/列维度的取值族：由 PRIORITY 派生，不复制第二份文案 */
const PRIORITY_DICT: Record<string, StatusMeta> = Object.fromEntries(
  Object.entries(PRIORITY).map(([code, item]) => [code, { label: item.label, tone: item.tone }]),
)

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
  integration: INTEGRATION,
  build: BUILD,
  mr: MR,
  sync: SYNC,
  admission: ADMISSION,
  sonar: SONAR,
  artifactSource: ARTIFACT_SOURCE,
  policyCheck: POLICY_CHECK,
  executorHealth: EXECUTOR_HEALTH,
  caseReview: CASE_REVIEW,
  caseReviewConclusion: CASE_REVIEW_CONCLUSION,
  wikiVisibility: WIKI_VISIBILITY,
  mrReviewState: MR_REVIEW_STATE,
  // 分析域取值回显（TECHNICAL §5）：三族都必须与服务端注册表逐字符串相等
  reqType: REQ_TYPE,
  severity: SEVERITY,
  priority: PRIORITY_DICT,
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
  // —— M7 集成域动作（服务端动作码，连字符形态；前端只取词）——
  'trigger-build': '触发构建',
  'refresh-build': '刷新构建',
  'test-config': '测试连接',
  'sync-mr': '同步 MR',
  'refresh-sonar': '刷新质量门',
  'confirm-merge': '确认推进',
  'promote-artifact': '晋级',
  'bind-artifact': '绑定制品',
  search: '检索',
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

  /**
   * 取值是否已登记。回显层必须用 has() 区分「未登记」与「空」：
   * meta() 的未知兜底会把未登记码去下划线后照常显示，拿它判断登记状态会漏判。
   */
  function has(kind: StatusKind, value: string | null | undefined): boolean {
    const key = (value ?? '').toUpperCase()
    return Boolean(overrides.value[kind]?.[key] ?? DICTS[kind]?.[key])
  }

  function tone(kind: StatusKind, value: string | null | undefined): Tone {
    return meta(kind, value).tone
  }

  /**
   * `statusText` / `statusTone` 是 `label` / `tone` 的同一实现（M7 设计稿与蓝图用这组名字）。
   * 两个名字指向同一张表，不存在第二份词典；新代码用 statusText，旧代码继续用 label。
   */
  function statusText(kind: StatusKind, value: string | null | undefined): string {
    return meta(kind, value).label
  }

  function statusTone(kind: StatusKind, value: string | null | undefined): Tone {
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

  return { meta, label, has, tone, statusText, statusTone, actionLabel, priority, merge, overrides, version }
})
