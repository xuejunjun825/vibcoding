/**
 * 质量域端点封装（M6 · TECHNICAL §5.2 / §7.3）。
 *
 * 三条铁律，页面不得绕过：
 * 1. 前缀只在这里拼（`/api/deliver`），页面传 id / query，不写 URL；
 * 2. 比率、结论、合法动作一律用服务端下发的值（`MetricFormulas` / `StateTables` /
 *    `QualityAggregator` 的唯一实现），前端不做二次除法、不内置状态机；
 * 3. 409 **不弹 toast**（`client.ts` 已约定），错误明细由页面渲染常驻冲突卡 ——
 *    因此这里的方法全部原样抛 `ApiError`，不吞 catch。
 */
import { buildQuery, download, http } from './client'
import type {
  AssignmentReplacePayload,
  AssignmentView,
  CloseRoundPayload,
  EnvPayload,
  EnvRow,
  GateDetail,
  GateReplay,
  GateRow,
  GateRuleConfig,
  GateSnapshot,
  MyCaseRow,
  OpenRoundPayload,
  PageResponse,
  PlanActionPayload,
  PlanChangeLogRow,
  PlanDetail,
  PlanProgress,
  PlanSummary,
  PlanUpdatePayload,
  QualityMetrics,
  ReleaseQuality,
  ReportDetail,
  ReportDiff,
  ReportGeneratePayload,
  ReportRow,
  ReportSignoffPayload,
  ReportVersionDetail,
  ReportVersionRow,
  RoundDetail,
  RoundView,
  SignoffProgress,
  StrategyDetail,
  StrategyPayload,
  StrategyRow,
  WaiverApplyPayload,
  WaiverRow,
} from './types'

const DELIVER = '/api/deliver'

/** 分页查询的公共形状（服务端固定 page 从 1 起、size ≤100） */
export interface PageQuery extends Record<string, unknown> {
  page?: number
  size?: number
}

export interface StrategyQuery extends PageQuery {
  releaseId?: number | string
  status?: string
}

export interface PlanQuery extends PageQuery {
  releaseId?: number | string
  status?: string
  ownerId?: number | string
}

export interface EnvQuery extends PageQuery {
  status?: string
  keyword?: string
}

export interface ReportQuery extends PageQuery {
  releaseId?: number | string
  planId?: number | string
  status?: string
}

export interface GateQuery extends PageQuery {
  releaseId?: number | string
  status?: string
}

export interface WaiverQuery extends PageQuery {
  status?: string
  releaseId?: number | string
}

export interface QualityMetricQuery extends Record<string, unknown> {
  releaseId?: number | string
  domainId?: number | string
  vendorId?: number | string
  from?: string
  to?: string
}

export interface MyCaseQuery extends PageQuery {
  overdue?: boolean
}

/* ---------------- 测试策略（版本级，多版本、可激活） ---------------- */

export const strategyApi = {
  page(params: StrategyQuery = {}): Promise<PageResponse<StrategyRow>> {
    return http.get(`${DELIVER}/test-strategies`, params)
  },
  detail(id: number | string): Promise<StrategyDetail> {
    return http.get(`${DELIVER}/test-strategies/${id}`)
  },
  /** 同 Release 保存即生成新版本（旧版 RETIRED），版本号由服务端递增 */
  create(payload: StrategyPayload): Promise<StrategyDetail> {
    return http.post(`${DELIVER}/test-strategies`, payload)
  },
  /** ACTIVE 版只读：服务端 409 ILLEGAL_PLAN_TRANSITION，页面按错误码渲染 */
  update(id: number | string, payload: StrategyPayload): Promise<StrategyDetail> {
    return http.put(`${DELIVER}/test-strategies/${id}`, payload)
  },
  activate(id: number | string): Promise<StrategyDetail> {
    return http.post(`${DELIVER}/test-strategies/${id}/activate`)
  },
}

/* ---------------- 测试计划生命周期 ---------------- */

export const planApi = {
  /** 计划详情走 `/lifecycle`（M5 的 `/test-plans/{id}` 仍返回用例 + 汇总视图） */
  detail(id: number | string): Promise<PlanDetail> {
    return http.get(`${DELIVER}/test-plans/${id}/lifecycle`)
  },
  /** 已批准计划变更必须带 reason，服务端 +1 revision 并写 change_log */
  update(id: number | string, payload: PlanUpdatePayload): Promise<PlanDetail> {
    return http.patch(`${DELIVER}/test-plans/${id}`, payload)
  },
  /**
   * 动作按钮：submit-review / approve / reject / start / complete / close / archive。
   * 合法动作只来自 `allowedNext`，页面不判断“这个状态下能不能点”。
   */
  action(id: number | string, action: string, payload: PlanActionPayload = {}): Promise<PlanDetail> {
    return http.post(`${DELIVER}/test-plans/${id}/${action}`, payload)
  },
  allowedActions(id: number | string): Promise<string[]> {
    return http.get(`${DELIVER}/test-plans/${id}/allowed-actions`)
  },
  progress(id: number | string, roundId?: number | string | null): Promise<PlanProgress> {
    return http.get(`${DELIVER}/test-plans/${id}/progress`, {
      roundId: roundId ?? undefined,
    })
  },
  changeLogs(
    id: number | string,
    params: PageQuery = {},
  ): Promise<PageResponse<PlanChangeLogRow>> {
    return http.get(`${DELIVER}/test-plans/${id}/change-logs`, params)
  },
  /** M5 汇总（planned/executed/passRate…）；M6 的 PlanCounts 由 detail.summary 给 */
  summary(id: number | string): Promise<PlanSummary> {
    return http.get(`${DELIVER}/test-plans/${id}/summary`)
  },
}

/* ---------------- 轮次（作用域固化，结论单调下调） ---------------- */

export const roundApi = {
  list(planId: number | string): Promise<RoundView[]> {
    return http.get(`${DELIVER}/test-plans/${planId}/rounds`)
  },
  /** 同计划仅一轮进行中：并发开轮由 `(plan_id, active)` 唯一键兜住 → 409 */
  open(planId: number | string, payload: OpenRoundPayload): Promise<RoundView> {
    return http.post(`${DELIVER}/test-plans/${planId}/rounds`, payload)
  },
  detail(planId: number | string, roundId: number | string): Promise<RoundDetail> {
    return http.get(`${DELIVER}/test-plans/${planId}/rounds/${roundId}`)
  },
  close(
    planId: number | string,
    roundId: number | string,
    payload: CloseRoundPayload,
  ): Promise<RoundDetail> {
    return http.post(`${DELIVER}/test-plans/${planId}/rounds/${roundId}/close`, payload)
  },
}

/* ---------------- 执行分配 ---------------- */

export const assignmentApi = {
  list(planId: number | string, roundId?: number | string | null): Promise<AssignmentView[]> {
    return http.get(`${DELIVER}/test-plans/${planId}/assignments`, {
      roundId: roundId ?? undefined,
    })
  },
  /** 整体替换：分配合计与用例数的差额由服务端算（`AssignmentSummary.diff`） */
  replace(
    planId: number | string,
    payload: AssignmentReplacePayload,
    roundId?: number | string | null,
  ): Promise<AssignmentView[]> {
    // http.put 的第三参只收 RequestOptions，query 必须自己拼
    const query = buildQuery({ roundId: roundId ?? payload.roundId ?? undefined })
    return http.put(`${DELIVER}/test-plans/${planId}/assignments${query}`, payload)
  },
}

/* ---------------- 环境台账 ---------------- */

export const envApi = {
  page(params: EnvQuery = {}): Promise<PageResponse<EnvRow>> {
    return http.get(`${DELIVER}/test-envs`, params)
  },
  create(payload: EnvPayload): Promise<EnvRow> {
    return http.post(`${DELIVER}/test-envs`, payload)
  },
  update(id: number | string, payload: EnvPayload): Promise<EnvRow> {
    return http.put(`${DELIVER}/test-envs/${id}`, payload)
  },
  /** 被进行中计划引用 → 409 ENV_IN_USE（detail.planIds） */
  remove(id: number | string): Promise<void> {
    return http.del(`${DELIVER}/test-envs/${id}`)
  },
}

/* ---------------- 测试报告（不可变版本 + 三级会签） ---------------- */

/** 版本内单条用例明细（证据抽屉；服务端固定 50/页） */
export interface ReportCaseDetailRow {
  planCaseId?: number
  caseId?: number
  caseCode?: string
  caseVersion?: number
  title?: string
  moduleName?: string
  executorName?: string
  result?: string
  executedAt?: string
}

/** 版本内单条缺陷明细（证据抽屉） */
export interface ReportDefectDetailRow {
  id?: number
  code?: string
  title?: string
  severity?: string
  status?: string
  source?: string
  escape?: boolean
  foundAt?: string
}

export const reportApi = {
  page(params: ReportQuery = {}): Promise<PageResponse<ReportRow>> {
    return http.get(`${DELIVER}/test-reports`, params)
  },
  /** 生成即得一个新版本快照；RELEASE 无已批准计划 → 409 REPORT_NO_PLAN */
  generate(payload: ReportGeneratePayload): Promise<ReportDetail> {
    return http.post(`${DELIVER}/test-reports`, payload)
  },
  detail(id: number | string): Promise<ReportDetail> {
    return http.get(`${DELIVER}/test-reports/${id}`)
  },
  versions(id: number | string): Promise<ReportVersionRow[]> {
    return http.get(`${DELIVER}/test-reports/${id}/versions`)
  },
  /** 旧版本只读 snapshot_json，不随新数据漂移 */
  version(id: number | string, versionNo: number | string): Promise<ReportVersionDetail> {
    return http.get(`${DELIVER}/test-reports/${id}/versions/${versionNo}`)
  },
  /** 严格顺序：越序 409 SIGNOFF_ORDER_VIOLATION；重复签幂等 */
  signoff(
    id: number | string,
    versionNo: number | string,
    payload: ReportSignoffPayload,
  ): Promise<SignoffProgress> {
    return http.post(`${DELIVER}/test-reports/${id}/versions/${versionNo}/signoff`, payload)
  },
  diff(id: number | string, from: number | string, to: number | string): Promise<ReportDiff> {
    return http.get(`${DELIVER}/test-reports/${id}/diff`, { from, to })
  },
  /** 未签完的版本也能导出（导出件带“未生效”水印），审计单独一条 */
  exportFile(
    id: number | string,
    versionNo: number | string,
    format: 'xlsx' | 'pdf' = 'xlsx',
  ): Promise<void> {
    return download(`${DELIVER}/test-reports/${id}/export`, { format, version: versionNo })
  },
  /** 证据抽屉：某版本的用例明细（页面不再直连路径） */
  cases(
    id: number | string,
    versionNo: number | string,
    params: { page?: number; size?: number; result?: string } = {},
  ): Promise<PageResponse<ReportCaseDetailRow>> {
    return http.get(`${DELIVER}/test-reports/${id}/versions/${versionNo}/cases`, params)
  },
  /** 证据抽屉：某版本的缺陷明细 */
  defects(
    id: number | string,
    versionNo: number | string,
    params: { page?: number; size?: number; severity?: string } = {},
  ): Promise<PageResponse<ReportDefectDetailRow>> {
    return http.get(`${DELIVER}/test-reports/${id}/versions/${versionNo}/defects`, params)
  },
}

/* ---------------- 质量门（唯一算法在服务端 GateEvaluator） ---------------- */

export const gateApi = {
  page(params: GateQuery = {}): Promise<PageResponse<GateRow>> {
    return http.get(`${DELIVER}/quality-gates`, params)
  },
  detail(releaseId: number | string): Promise<GateDetail> {
    return http.get(`${DELIVER}/quality-gates/${releaseId}`)
  },
  /** PRD 路径别名（同一方法），版本详情 tab 用它 */
  byRelease(releaseId: number | string): Promise<GateDetail> {
    return http.get(`${DELIVER}/releases/${releaseId}/quality-gate`)
  },
  /** QG-04 不可 disabled → 400 RULE_NOT_WAIVABLE */
  configure(releaseId: number | string, rules: GateRuleConfig[]): Promise<GateDetail> {
    return http.put(`${DELIVER}/quality-gates/${releaseId}`, { rules })
  },
  /** 预评估：不固化，`snapshotId=0`，只用于页面“先看一眼” */
  evaluate(
    releaseId: number | string,
    payload: { deployOrderId: number; requirementIds: number[] },
  ): Promise<GateSnapshot> {
    return http.post(`${DELIVER}/quality-gates/${releaseId}/evaluate`, payload)
  },
  snapshot(snapshotId: number | string): Promise<GateSnapshot> {
    return http.get(`${DELIVER}/quality-gates/snapshots/${snapshotId}`)
  },
  /** 回放只解析快照（AC-7），不重算、不写库 */
  replay(snapshotId: number | string): Promise<GateReplay> {
    return http.post(`${DELIVER}/quality-gates/snapshots/${snapshotId}/replay`)
  },
}

/* ---------------- 规则级豁免（1–30 天，QG-04 硬红线） ---------------- */

export const waiverApi = {
  page(params: WaiverQuery = {}): Promise<PageResponse<WaiverRow>> {
    return http.get(`${DELIVER}/quality-gates/waivers`, params)
  },
  /** 一条豁免只覆盖一条规则；QG-04 → 400 RULE_NOT_WAIVABLE */
  apply(payload: WaiverApplyPayload): Promise<WaiverRow> {
    return http.post(`${DELIVER}/quality-gates/waivers`, payload)
  },
  approve(id: number | string, comment?: string): Promise<WaiverRow> {
    return http.post(`${DELIVER}/quality-gates/waivers/${id}/approve`, { comment })
  },
  reject(id: number | string, comment: string): Promise<WaiverRow> {
    return http.post(`${DELIVER}/quality-gates/waivers/${id}/reject`, { comment })
  },
  /** 撤销不影响已发出的快照 */
  revoke(id: number | string, reason: string): Promise<WaiverRow> {
    return http.post(`${DELIVER}/quality-gates/waivers/${id}/revoke`, { reason })
  },
}

/* ---------------- 5 项质量指标 ---------------- */

export const metricApi = {
  /** 分母为 0 的指标 `value` 为 null，页面显示“—”（禁止 0%） */
  quality(params: QualityMetricQuery = {}): Promise<QualityMetrics> {
    return http.get(`${DELIVER}/metrics/quality`, params)
  },
}

/* ---------------- 工作台 ---------------- */

export const workbenchApi = {
  /** 分配给我的 + 我负责计划里未分配的用例（AC-9.5） */
  myCases(params: MyCaseQuery = {}): Promise<PageResponse<MyCaseRow>> {
    return http.get(`${DELIVER}/workbench/my-cases`, params)
  },
}

/* ---------------- 版本质量 ---------------- */

export const releaseQualityApi = {
  /** 与缺陷列表 / 发布单列表同 WHERE，AC-8.4 才能复算齐 */
  quality(releaseId: number | string): Promise<ReleaseQuality> {
    return http.get(`${DELIVER}/releases/${releaseId}/quality`)
  },
}
