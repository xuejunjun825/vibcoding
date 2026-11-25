/**
 * PDR 评审 + 项目组（厂商工时评估）API 客户端。
 *
 * 路径全部对照后端注解写（不要凭记忆）：
 * - `req-service/src/main/java/com/example/req/pdr/PdrController.java`
 * - `req-service/src/main/java/com/example/req/project/ProjectController.java`
 * 网关前缀 `/api/req`（`api/req.ts` 的 `REQ` 常量同一份）。
 *
 * 403 约定（r45 / access a8）：
 * - `getPdrReview()` 是**需求详情页内的可选探测**：一个只有 `req.pool` 的账号打开需求详情，
 *   `/requirements/{id}/pdr-review` 会 403；这里必须走 `silentForbidden`，让「评审」tab 降级成一条提示，
 *   而不是把整页甩到 `/403`。调用点默认就带，显式传 `{ silentForbidden: false }` 才升级为主数据。
 * - `listProjects()` / `getPdrStats()` / `listPdrDatasets()` 是「评审中心」页面的主数据，
 *   越权就该落全局 /403，所以默认**不带** silentForbidden。
 * - 附件复用 `api/req.ts` 的通用三件套（`listAttachments` / `uploadAttachment` / `downloadAttachment`），
 *   bizType 传 `PDR_ATTACHMENT_BIZ_TYPE`（`PDR_REVIEW`），不在这里另造一套上传协议。
 */
import { http } from './client'
import type { RequestOptions } from './client'
import { PDR_ATTACHMENT_BIZ_TYPE } from './types.pdr'
import type {
  PdrDatasetInfo,
  PdrOpinionPage,
  PdrProject,
  PdrProjectDetail,
  PdrProjectMember,
  PdrReview,
  PdrReviewDetail,
  PdrStats,
  PdrStatsQuery,
  PdrToTaskInput,
  PdrToTaskResult,
  StartPdrReviewInput,
  SubmitPdrOpinionsInput,
} from './types.pdr'

const REQ = '/api/req'

/** 只在「页内可选探测」时用；主数据保持默认（403 落全局） */
export interface PdrProbeOptions {
  silentForbidden?: boolean
}

/* ------------------------------------------------------------------ 项目组 */

/**
 * `GET /projects`（`ProjectController.list`）。
 * 参数只有 `status`；不传 = 全量（返回 `ProjectView[]`，无分页包装）。
 */
export function listProjects(
  params?: { status?: string },
  options: PdrProbeOptions = {},
): Promise<PdrProject[]> {
  return http.get<PdrProject[]>(`${REQ}/projects`, params, options)
}

/** `GET /projects/{id}` → `ProjectDetail{project, members}` */
export function getProject(id: number): Promise<PdrProjectDetail> {
  return http.get<PdrProjectDetail>(`${REQ}/projects/${id}`)
}

/** `GET /projects/{id}/members` → `MemberView[]`（评审人下拉的数据源） */
export function listProjectMembers(
  projectId: number,
  options: PdrProbeOptions = {},
): Promise<PdrProjectMember[]> {
  return http.get<PdrProjectMember[]>(`${REQ}/projects/${projectId}/members`, undefined, options)
}

/* ------------------------------------------------------------------ 评审单 */

/** `POST /requirements/{requirementId}/pdr-review`（201）→ `ReviewView` */
export function startPdrReview(
  requirementId: number,
  body: StartPdrReviewInput = {},
): Promise<PdrReview> {
  return http.post<PdrReview>(`${REQ}/requirements/${requirementId}/pdr-review`, body)
}

/**
 * `GET /requirements/{requirementId}/pdr-review` → `ReviewDetail{review, opinions}`。
 *
 * 从未发起时后端回 `{review: null, opinions: []}`（不是 404）。
 * 默认 `silentForbidden: true`：这是需求详情页里的可选探测（见文件头）。
 */
export function getPdrReview(
  requirementId: number,
  options: PdrProbeOptions = { silentForbidden: true },
): Promise<PdrReviewDetail> {
  return http.get<PdrReviewDetail>(`${REQ}/requirements/${requirementId}/pdr-review`, undefined, options)
}

/** `GET /requirements/{requirementId}/pdr-review/opinions`（分页，按人/分类/状态筛） */
export function listPdrOpinions(
  requirementId: number,
  params: {
    reviewerId?: number
    category?: string
    status?: string
    page?: number
    size?: number
  } = {},
  options: PdrProbeOptions = {},
): Promise<PdrOpinionPage> {
  return http.get<PdrOpinionPage>(
    `${REQ}/requirements/${requirementId}/pdr-review/opinions`,
    params,
    options,
  )
}

/** `POST /pdr-reviews/{reviewId}/opinions`（201）→ 最新 `ReviewDetail`（一次可多条） */
export function submitPdrOpinions(
  reviewId: number,
  body: SubmitPdrOpinionsInput,
): Promise<PdrReviewDetail> {
  return http.post<PdrReviewDetail>(`${REQ}/pdr-reviews/${reviewId}/opinions`, body)
}

/** `POST /pdr-reviews/{reviewId}/conclude` → `ReviewDetail`（MODE_ALL 未评完 409） */
export function concludePdrReview(reviewId: number): Promise<PdrReviewDetail> {
  return http.post<PdrReviewDetail>(`${REQ}/pdr-reviews/${reviewId}/conclude`)
}

/** `POST /pdr-reviews/{reviewId}/close`（body 可空 `{reason}`）→ `ReviewDetail` */
export function closePdrReview(reviewId: number, reason?: string): Promise<PdrReviewDetail> {
  return http.post<PdrReviewDetail>(`${REQ}/pdr-reviews/${reviewId}/close`, { reason: reason ?? null })
}

/* ------------------------------------------------------------------ 意见转任务 */

/** `POST /pdr-reviews/{reviewId}/opinions/{opinionId}/to-task`（201）→ `ToTaskResult` */
export function pdrOpinionToTask(
  reviewId: number,
  opinionId: number,
  body: PdrToTaskInput = {},
): Promise<PdrToTaskResult> {
  return http.post<PdrToTaskResult>(
    `${REQ}/pdr-reviews/${reviewId}/opinions/${opinionId}/to-task`,
    body,
  )
}

/* ------------------------------------------------------------------ 统计 / 数据集 */

/** `GET /pdr-reviews/stats?groupBy&domainId&from&to` → `StatsView` */
export function getPdrStats(params: PdrStatsQuery = {}): Promise<PdrStats> {
  const query: Record<string, unknown> = {}
  if (params.groupBy) query.groupBy = params.groupBy
  if (params.domainId != null) query.domainId = params.domainId
  if (params.from) query.from = params.from
  if (params.to) query.to = params.to
  return http.get<PdrStats>(`${REQ}/pdr-reviews/stats`, query)
}

/**
 * `GET /pdr-reviews/datasets` → `DatasetInfo[]`。
 *
 * 契约缺口：这里只回维度/度量名（`pdr_review` / `pdr_review_opinion`），
 * **不含** 9 类分类的 code→label —— 分类码表见 `api/pdrCategories.ts`。
 * 页面把它当「与自助报表同源」的说明性数据，取不到不阻断（silentForbidden）。
 */
export function listPdrDatasets(options: PdrProbeOptions = { silentForbidden: true }): Promise<
  PdrDatasetInfo[]
> {
  return http.get<PdrDatasetInfo[]>(`${REQ}/pdr-reviews/datasets`, undefined, options)
}

/* ------------------------------------------------------------------ 附件的 bizType */

export { PDR_ATTACHMENT_BIZ_TYPE }

/**
 * 「评审中心」列表的聚合口径（后端没有 `GET /pdr-reviews` 列表端点，
 * 见 `PdrController` 只有 stats/datasets 与需求维度读路径）。
 *
 * 页面按 PDR 需求分页扫一遍、逐条补 `pdr-review` 详情后在前端归并；
 * 扫描窗口与并发在这里集中声明，页面不再自己写魔法数。
 */
export const PDR_LIST_SCAN = {
  /** 一次扫描多少条 PDR 需求（评审单只能顺着需求取，扫太多就是 N 次探测） */
  limit: 30,
  /** 并发上限：每页 6 个在飞，避免把 req-service 打成串行队列 */
  concurrency: 6,
} as const

/** 视图层用不到但测试/调试需要的一个保守兜底：`RequestOptions` 只剩 silentForbidden 时也能复用 */
export type PdrRequestExtra = Pick<RequestOptions, 'silentForbidden'>
