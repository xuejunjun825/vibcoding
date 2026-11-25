/**
 * PDR 评审的视图层纯函数（文案/派生/错误映射）。
 *
 * 只做三件事：
 * 1. 把后端 `PdrViews` 的枚举翻成界面措辞（状态标签统一走 `stores/dict.ts` 的 `pdrReview`/`pdrOpinion`，
 *    这里不复制第二份 label，避免「词典一份、页面一份」）；
 * 2. 补齐契约缺口：`ReviewDetail` 没有 `Tally`，进度与「谁还没评」在这里算；
 *    `StatsView` 没有 `needChangeCount`，需修改率在这里由 buckets 推；
 * 3. 把 409/403 的错误码翻成一句人话 + 可点的后续动作（转任务 → 任务链接、分类不合法 → 列出九类）。
 *
 * 组件里禁止再写这些字符串（与 `estimate.ts` 的约定一致：模板只拼常量）。
 */
import { ApiError } from '@/api/client'
import { PDR_CATEGORIES, PDR_CATEGORY_LIST_TEXT, pdrCategoryLabel } from '@/api/pdrCategories'
import type { PdrDecision, PdrOpinion, PdrReview, PdrReviewDetail, PdrStats } from '@/api/types.pdr'

/** 结论下拉的取值集合；文案取词典 `caseReviewConclusion`（PASS/NEED_CHANGE/REJECT） */
export const PDR_DECISION_CODES: PdrDecision[] = ['PASS', 'NEED_CHANGE', 'REJECT']

/** 结论下拉里「谁可以给哪一档」的提示 */
export const PDR_DECISION_HINTS: Record<PdrDecision, string> = {
  PASS: '本条无异议，可进入下一步',
  NEED_CHANGE: '指出要改的点，评审单会被判为需修改',
  REJECT: '方向性问题，整单驳回',
}

/** 评审模式：`MODE_ALL` 全员通过（默认）、`MODE_ANY` 任一通过 */
export const PDR_MODE_OPTIONS = [
  { value: 'MODE_ALL', label: '全员通过', hint: '默认：所有评审人都通过才算通过，任一「需修改/驳回」即整单驳回' },
  { value: 'MODE_ANY', label: '任一通过', hint: '或签：任意一位评审人通过即通过（兜底场景用，慎选）' },
] as const

export function pdrModeLabel(mode?: string | null): string {
  return PDR_MODE_OPTIONS.find((item) => item.value === mode)?.label ?? (mode || '全员通过')
}

/** 评审人在项目组里的角色（`ProjectViews.MemberView.roleInProject`），只翻中文，不判权限 */
export const PDR_PROJECT_ROLES: Record<string, string> = {
  PO: '产品负责人',
  PM: '项目经理',
  DEV: '研发',
  QA: '测试',
  SME: '领域专家',
}

export function pdrRoleLabel(role?: string | null): string {
  if (!role) return '成员'
  return PDR_PROJECT_ROLES[role] ?? role
}

/** 「研发已开始」的判定集合：PDR 状态机 `StateTables` 里 IN_DEV 之后（见 platform-contract StateTables） */
const PDR_DEV_STARTED = new Set(['IN_DEV', 'DELIVERED', 'ACCEPTED_CLOSED'])

/** 换厂商/改评估的锁定提示（文案与后端 409 VENDOR_LOCKED 一致，交互稿第 2 屏） */
export const VENDOR_LOCKED_HINT = '研发已开始，需求变更请走变更单'

/** 研发已开始后，厂商与评估都不该再改（按钮禁用而不是隐藏，让用户知道为什么） */
export function vendorEditLocked(requirementStatus?: string | null): boolean {
  return PDR_DEV_STARTED.has(String(requirementStatus ?? '').toUpperCase())
}

/** 换厂商后原评估会失效（STALE）—— 交互稿黄条文案，只在已有 APPROVED 评估时显示 */
export const VENDOR_SWAP_STALE_WARNING =
  '换厂商后原评估将失效（STALE），需新厂商重新评估；本轮换厂商会写入审计日志并通知新旧厂商。'

/** 评审单是否还接受意见（只有 IN_REVIEW 可写；关闭后列表保留、输入区隐藏） */
export function reviewWritable(review?: PdrReview | null): boolean {
  return (review?.status ?? '') === 'IN_REVIEW'
}

export interface PdrProgress {
  /** 评审人总数（`review.reviewerIds` 去重） */
  total: number
  /** 已提交意见的评审人数（按 reviewerId 去重） */
  submitted: number
  /** 还没提交的评审人 id（顺序同 reviewerIds） */
  pending: number[]
  /** 是否全员到齐（MODE_ALL 可以汇总的前置条件） */
  complete: boolean
}

/**
 * 进度 = 已评人数 / 评审人数。
 * 后端 `Tally` 存在但没有随 `ReviewDetail` 下发，所以在这里用 `reviewerIds` 与
 * `opinions[].reviewerId` 推——这只是「展示用」的统计，汇总仍由后端 conclude 判定。
 */
export function pdrProgress(detail?: PdrReviewDetail | null): PdrProgress {
  const ids = Array.from(new Set(detail?.review?.reviewerIds ?? []))
  const voted = new Set((detail?.opinions ?? []).map((item) => item.reviewerId))
  const pending = ids.filter((id) => !voted.has(id))
  return {
    total: ids.length,
    submitted: ids.length - pending.length,
    pending,
    complete: ids.length > 0 && pending.length === 0,
  }
}

/** 我的意见是否已提交（同一个人可以有多条；只要有一条就算已评） */
export function hasMyOpinion(detail: PdrReviewDetail | null | undefined, userId?: number | null): boolean {
  if (userId == null) return false
  return (detail?.opinions ?? []).some((item) => item.reviewerId === userId)
}

export interface PdrReviewerGroup {
  reviewerId: number
  reviewerName: string
  opinions: PdrOpinion[]
  /** 该评审人最「重」的结论（驳回 > 需修改 > 通过），用于分组头 pill */
  decision: PdrDecision | null
}

const DECISION_WEIGHT: Record<string, number> = { REJECT: 3, NEED_CHANGE: 2, PASS: 1 }

/** 按人分组（交互稿「按人分组，每条意见一行」）；组顺序按首次出现，不额外排序 */
export function groupOpinionsByReviewer(opinions: PdrOpinion[]): PdrReviewerGroup[] {
  const groups = new Map<number, PdrReviewerGroup>()
  for (const opinion of opinions) {
    const group = groups.get(opinion.reviewerId) ?? {
      reviewerId: opinion.reviewerId,
      reviewerName: opinion.reviewerName || `用户 #${opinion.reviewerId}`,
      opinions: [],
      decision: null,
    }
    group.opinions.push(opinion)
    const weight = DECISION_WEIGHT[opinion.decision] ?? 0
    if (group.decision === null || weight > (DECISION_WEIGHT[group.decision] ?? 0)) {
      group.decision = opinion.decision
    }
    groups.set(opinion.reviewerId, group)
  }
  return [...groups.values()]
}

/** 分类分布：统计图与列表都用它，保序（九类的后端顺序） */
export function pdrCategoryBuckets(stats?: PdrStats | null): { code: string; label: string; count: number }[] {
  // 后端只返回「有意见」的分类；界面要的是九类全貌（0 也要画出来，否则「哪类没人提」看不出来），
  // 所以以 `PDR_CATEGORIES` 为骨架，再用 buckets 覆盖计数。顺序沿用契约（PdrCategoryCatalog.ALL）。
  const byKey = new Map((stats?.buckets ?? []).map((bucket) => [bucket.key, bucket]))
  return PDR_CATEGORIES.map((code) => {
    const bucket = byKey.get(code)
    return {
      code,
      label: bucket?.label || pdrCategoryLabel(code),
      count: bucket?.count ?? 0,
    }
  })
}

/**
 * 需修改率：契约缺 `needChangeCount`，用 `groupBy=DECISION` 的 buckets 里
 * NEED_CHANGE + REJECT 除意见总数。分母为 0 时回 null（界面显示「—」，绝不 NaN）。
 */
export function pdrNeedChangeRate(stats?: PdrStats | null): number | null {
  if (!stats || !stats.opinionCount) return null
  const bad = (stats.buckets ?? [])
    .filter((bucket) => bucket.key === 'NEED_CHANGE' || bucket.key === 'REJECT')
    .reduce((sum, bucket) => sum + bucket.count, 0)
  return bad / stats.opinionCount
}

/** 按评审人的 Top N（`groupBy=REVIEWER` 的 buckets 已由后端排序，这里只截断） */
export function pdrReviewerTop(stats: PdrStats | null | undefined, limit = 5): PdrStats['buckets'] {
  return (stats?.buckets ?? []).slice(0, limit)
}

/** 平均评审时长：21.4h（后端给小时数；P90 放 hint） */
export function pdrHours(hours?: number | null): string {
  if (hours == null || !Number.isFinite(hours)) return '—'
  return `${hours.toFixed(1)}h`
}

/** 意见正文截断成任务标题（后端缺省取前 40 字，前端预填保持一致） */
export const PDR_TASK_TITLE_LIMIT = 40

export function pdrTaskTitle(comment: string): string {
  const clean = (comment || '').replace(/\s+/g, ' ').trim()
  if (clean.length <= PDR_TASK_TITLE_LIMIT) return clean
  return `${clean.slice(0, PDR_TASK_TITLE_LIMIT)}…`
}

/**
 * 转任务说明（交互稿：「自动带『来源：PDR 评审 · 分类：xxx · 原文』」）。
 * 与后端 `PdrViews.ToTask.description` 的缺省口径一致，前端预填只是为了让用户能改。
 */
export function pdrTaskDescription(reviewId: number, opinion: PdrOpinion): string {
  return [
    `来源：PDR 评审 #${reviewId} · 分类：${opinion.categoryLabel || pdrCategoryLabel(opinion.category)}`,
    '',
    '原文：',
    opinion.comment,
  ].join('\n')
}

/* ------------------------------------------------------------------ 错误码映射 */

export interface PdrErrorInfo {
  /** 给用户看的一句话 */
  text: string
  /** 错误码（`ApiError.code`），便于调试与断言 */
  code: string
  /** OPINION_ALREADY_TASKED 的 detail.taskId：给出任务链接 */
  taskId: number | null
  /** PDR_REVIEW_REQUIRED 的 detail.reviewId */
  reviewId: number | null
  /** INVALID_CATEGORY：界面要显式列出九类 */
  invalidCategory: boolean
}

const PDR_ERROR_TEXT: Record<string, string> = {
  NOT_REVIEWER: '只有评审人才能提交意见（评审人是发起时从项目组里选的）',
  REVIEW_CLOSED: '评审已结束，不能再提交意见；已提交的意见仍可转任务',
  OPINION_ALREADY_TASKED: '该意见已转过任务，无需重复创建',
  INVALID_CATEGORY: `评审分类不合法，只能是这九类之一：${PDR_CATEGORY_LIST_TEXT}`,
  PDR_REVIEW_REQUIRED: '排期前必须先完成 PDR 评审：发起评审并汇总通过',
  VENDOR_LOCKED: VENDOR_LOCKED_HINT,
  VENDOR_NOT_ASSIGNED: '请先指派承接厂商，再由厂商提交工时评估',
  ESTIMATE_NOT_APPROVED: '工时评估还没通过，不能排期',
  PDR_REVIEW_INCOMPLETE: '还有评审人没提交意见，全员通过模式不能汇总',
  ILLEGAL_TRANSITION: '当前状态不允许这个动作（可能已被他人推进）',
}

function detailNumber(detail: unknown, key: string): number | null {
  if (!detail || typeof detail !== 'object') return null
  const value = (detail as Record<string, unknown>)[key]
  return typeof value === 'number' ? value : null
}

/**
 * 统一的错误翻译：优先用后端 `code`；`409` 的 detail 里可能带 taskId/reviewId。
 * 页面拿到 `text` 后：行内常驻提示（409）或 toast（其它），见 `handlePdrError()`。
 */
export function pdrErrorInfo(error: unknown): PdrErrorInfo {
  const code = error instanceof ApiError ? error.code : ''
  const detail = error instanceof ApiError ? error.detail : null
  const known = PDR_ERROR_TEXT[code]
  const fallback = error instanceof Error && error.message ? error.message : String(error)
  return {
    text: known ?? fallback,
    code,
    taskId: detailNumber(detail, 'taskId'),
    reviewId: detailNumber(detail, 'reviewId'),
    invalidCategory: code === 'INVALID_CATEGORY',
  }
}

/** 错误是否值得「刷新整页数据」（状态已被别人推进 → 409，或权限/可见性变化 → 403） */
export function pdrErrorNeedsRefresh(error: unknown): boolean {
  return error instanceof ApiError && (error.isConflict || error.isForbidden)
}

/** 403：页内可选探测读不到时的一句降级提示（与 r45 的审批探测同构） */
export const PDR_FORBIDDEN_HINT = '无权查看评审单（需要「评审中心」页面权限，或不在本需求的项目组里）'
