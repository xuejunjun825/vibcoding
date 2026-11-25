/**
 * r47 需求池运营端点：相似需求 / 跟单 / 合并。
 *
 * 页面只调用这里的方法，不直接拼 URL（同 `api/req.ts` 约定）。
 * 相似探测是**页内可选能力**：403 不得把人踹到全局 /403，也不得阻断
 * 提报或详情渲染，因此 `listSimilar*` 显式带 `silentForbidden`（L23 约定）。
 * 跟单/合并是主操作，403 交给调用方就地 toast，不走 silent 通道。
 */
import { http } from './client'
import type {
  RequirementFollowResult,
  RequirementFollowerPage,
  RequirementMergePayload,
  RequirementMergeResult,
  SimilarRequirementPage,
} from './types'

const REQ = '/api/req'

export interface SimilarQuery {
  title?: string
  description?: string
  domainId?: number
  type?: string
  limit?: number
}

/** 提报表单：按标题/域探测相似需求。标题为空时后端返回空页，不是错误。 */
export function listSimilarRequirements(query: SimilarQuery) {
  return http.get<SimilarRequirementPage>(
    `${REQ}/requirements/similar`,
    { ...query },
    { silentForbidden: true },
  )
}

/** 详情页/合并弹层：给定需求的相似需求 */
export function listSimilarForRequirement(id: number, limit = 5) {
  return http.get<SimilarRequirementPage>(
    `${REQ}/requirements/${id}/similar`,
    { limit },
    { silentForbidden: true },
  )
}

/** `我也需要`：201 created=true / 200 已跟单 / 409 REQUIREMENT_CLOSED */
export function followRequirement(id: number, note?: string) {
  return http.post<RequirementFollowResult>(
    `${REQ}/requirements/${id}/follow`,
    note && note.trim() ? { note: note.trim() } : {},
  )
}

/** 取消跟单：204；404 表示本来就没跟单 */
export function unfollowRequirement(id: number) {
  return http.del<void>(`${REQ}/requirements/${id}/follow`)
}

/** 跟单分页（前 5 条用 size=5，抽屉用 size=10） */
export function listRequirementFollowers(id: number, page = 1, size = 5) {
  // 跟单人名单是详情页的附加信息：读不到就留空，不能把人踹到全局 /403
  return http.get<RequirementFollowerPage>(
    `${REQ}/requirements/${id}/followers`,
    { page, size },
    { silentForbidden: true },
  )
}

/** 合并重复需求：源需求关闭（closeReason=MERGED），跟单与子需求迁入目标 */
export function mergeRequirement(id: number, payload: RequirementMergePayload) {
  return http.post<RequirementMergeResult>(`${REQ}/requirements/${id}/merge`, payload)
}
