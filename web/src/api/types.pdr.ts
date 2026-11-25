/**
 * PDR 评审 + 项目组（厂商工时评估前置）的类型声明。
 *
 * 字段顺序/命名与后端 record 一一对应（Jackson 按 record 组件名序列化）：
 * - `req-service/src/main/java/com/example/req/pdr/PdrViews.java`
 * - `req-service/src/main/java/com/example/req/project/ProjectViews.java`
 *
 * 与 `types.ts` 的关系：需求主类型仍留在 `types.ts`，这里只放 PDR/项目组新增的部分，
 * 避免把 3000 行的 `types.ts` 继续撑大。`RequirementDetail.pdrReview` 在 `types.ts` 里
 * 以 import type 引用本文件的 `PdrReviewDetail`。
 */

/** `PdrReviewService.ST_*`：评审单状态（注意通过态是 APPROVED，不是 PASSED） */
export type PdrReviewStatus = 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

/** `PdrReviewService.MODE_ALL` 全员通过（默认）/ `MODE_ANY` 任一通过 */
export type PdrReviewMode = 'MODE_ALL' | 'MODE_ANY'

/** 单条意见的结论 */
export type PdrDecision = 'PASS' | 'NEED_CHANGE' | 'REJECT'

/** 意见状态：待处理 → 已转任务 → 已解决（转任务不改变评审单结论） */
export type PdrOpinionStatus = 'OPEN' | 'TASKED' | 'RESOLVED'

/** `PdrStatsService` 的分组维度 */
export type PdrStatsGroupBy = 'CATEGORY' | 'REVIEWER' | 'DECISION'

/** `ProjectViews.MemberView.roleInProject`：项目组角色 */
export type PdrProjectRole = 'PO' | 'PM' | 'DEV' | 'QA' | 'SME'

/**
 * 项目组（`ProjectViews.ProjectView`）。
 * `GET /projects` 只返列表，成员要另调 `GET /projects/{id}/members`。
 */
export interface PdrProject {
  id: number
  code: string
  name: string
  domainId: number | null
  ownerUserId: number | null
  status: string
  createdAt?: string | null
}

/** `ProjectViews.MemberView` —— 注意没有中文 name，要中文名得查人员字典 */
export interface PdrProjectMember {
  id: number
  projectId: number
  userId: number
  username: string
  roleInProject: PdrProjectRole | string
  active: boolean
  joinedAt?: string | null
}

/** `ProjectViews.ProjectDetail` */
export interface PdrProjectDetail {
  project: PdrProject
  members: PdrProjectMember[]
}

/** `PdrViews.ReviewView` —— 评审单头 */
export interface PdrReview {
  id: number
  requirementId: number
  projectId: number | null
  status: PdrReviewStatus
  mode: PdrReviewMode
  conclusion: string | null
  initiatorId: number
  /** 评审人来源：PROJECT_MEMBERS / FALLBACK 之类，仅用于提示文案 */
  reviewerSource: string | null
  /** true = 项目组为空，已回退为「提报人 + 域负责人」 */
  fallback: boolean
  reviewerIds: number[]
  note: string | null
  dueAt: string | null
  createdAt: string | null
  closedAt: string | null
}

/**
 * `PdrViews.OpinionView` —— 一条评审意见。
 * `reviewerName` / `categoryLabel` 服务端已给，页面不要再查人员表/分类字典。
 */
export interface PdrOpinion {
  id: number
  reviewId: number
  reviewerId: number
  reviewerName: string | null
  seq: number
  decision: PdrDecision
  category: string
  categoryLabel: string | null
  comment: string
  status: PdrOpinionStatus
  taskId: number | null
  createdAt: string | null
}

/**
 * `PdrViews.ReviewDetail`。
 *
 * 契约缺口：后端只下发 `review + opinions`，没有 `Tally`（reviewerCount/submittedCount/pendingReviewerIds），
 * 所以「已评 2/4」与「谁还没评」只能由 `review.reviewerIds` 去重 `opinions[].reviewerId` 计算
 * （见 `views/req/pdr.ts` 的 `pdrProgress()`）。
 */
export interface PdrReviewDetail {
  review: PdrReview | null
  opinions: PdrOpinion[]
}

/** `PdrViews.OpinionPage` */
export interface PdrOpinionPage {
  items: PdrOpinion[]
  total: number
  page: number
  size: number
}

/** `POST /requirements/{id}/pdr-review` 的 body（`PdrViews.StartReview`） */
export interface StartPdrReviewInput {
  /** 空数组 = 后端按项目组成员补齐，仍为空则回退「提报人 + 域负责人」 */
  reviewerIds?: number[]
  note?: string
  dueAt?: string
  mode?: PdrReviewMode
}

/** 单条意见输入（`PdrViews.OpinionInput`） */
export interface PdrOpinionInput {
  decision: PdrDecision
  category: string
  comment: string
}

/** `POST /pdr-reviews/{id}/opinions` 的 body（`PdrViews.SubmitOpinions`） */
export interface SubmitPdrOpinionsInput {
  opinions: PdrOpinionInput[]
  decision?: PdrDecision
  category?: string
  comment?: string
}

/** `POST .../opinions/{opinionId}/to-task` 的 body（`PdrViews.ToTask`，全字段可空） */
export interface PdrToTaskInput {
  assigneeId?: number
  dueDate?: string
  estimateHours?: number
  title?: string
  description?: string
}

/** `PdrViews.ToTaskResult` */
export interface PdrToTaskResult {
  taskId: number
  taskCode: string
  opinion: PdrOpinion
}

/** `PdrViews.Bucket` */
export interface PdrBucket {
  key: string
  label: string
  count: number
  ratio: number | null
}

/**
 * `PdrViews.StatsView`。
 * 契约缺口：没有 needChangeCount —— 需修改率要用 `groupBy=DECISION` 的 buckets
 * 里 NEED_CHANGE/REJECT 的 count 除以 opinionCount（见 `pdrNeedChangeRate()`）。
 */
export interface PdrStats {
  groupBy: string
  reviewCount: number
  decidedCount: number
  opinionCount: number
  taskedCount: number
  resolvedCount: number
  closureRate: number | null
  avgReviewHours: number | null
  p90ReviewHours: number | null
  buckets: PdrBucket[]
}

/** `PdrViews.DatasetInfo` */
export interface PdrDatasetInfo {
  code: string
  name: string
  dimensions: string[]
  measures: string[]
}

/** 统计查询参数（对应 `stats(groupBy, domainId, from, to)`） */
export interface PdrStatsQuery {
  groupBy?: PdrStatsGroupBy
  domainId?: number
  from?: string
  to?: string
}

/** 附件业务类型：PDR 评审单（`PdrReviewService.BIZ_TYPE`） */
export const PDR_ATTACHMENT_BIZ_TYPE = 'PDR_REVIEW'
