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

export type StatusKind = 'requirement' | 'defect' | 'approval' | 'execution' | 'release' | 'deploy'

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

const DICTS: Record<StatusKind, Record<string, StatusMeta>> = {
  requirement: REQUIREMENT,
  defect: DEFECT,
  approval: APPROVAL,
  execution: EXECUTION,
  release: RELEASE,
  deploy: DEPLOY,
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
}

/** 危险动作：二次确认 + 必填原因 */
export const DANGER_ACTIONS = new Set(['close', 'close_duplicate', 'reject', 'withdraw', 'force_release'])

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

  function actionLabel(action: string): string {
    return ACTION_LABELS[action] ?? action.replace(/_/g, ' ')
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
