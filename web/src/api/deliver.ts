/**
 * deliver-service 端点封装（TECHNICAL §4.4）。
 * 门禁、追溯、看板的口径都来自服务端，前端不做二次计算。
 */
import { download, http } from './client'
import type {
  BoardMetrics,
  DefectDetail,
  DefectRow,
  GateDecision,
  MetricDefinition,
  PageResponse,
  PlanCaseRow,
  PlanSummary,
  Severity,
  SprintBoard,
  TaskRow,
  TestCaseDetail,
  TestCaseRow,
  TestModuleRow,
  TestPassMetrics,
  TestPlanDetail,
  TraceRow,
} from './types'

const DELIVER = '/api/deliver'

/* ---------------- 缺陷 ---------------- */

export type DefectListQuery = {
  domainId?: number | string
  vendorId?: number | string
  releaseId?: number | string
  severity?: Severity | ''
  status?: string
  handlerId?: number | string
  requirementId?: number | string
  dateFrom?: string
  dateTo?: string
  page?: number
  size?: number
  [key: string]: unknown
}

export function listDefects(params: DefectListQuery): Promise<PageResponse<DefectRow>> {
  return http.get<PageResponse<DefectRow>>(`${DELIVER}/defects`, params)
}

export function getDefect(id: number | string): Promise<DefectDetail> {
  return http.get<DefectDetail>(`${DELIVER}/defects/${id}`)
}

export function createDefect(payload: {
  title: string
  description: string
  reproSteps?: string
  severity: Severity
  priority?: string
  requirementId?: number
  releaseId?: number
  handlerId?: number
  expectedFixDate?: string
  attachments?: number[]
}): Promise<DefectRow> {
  // 归属（域/厂商）由服务端派生，请求体里的值会被忽略（AC-13）
  return http.post<DefectRow>(`${DELIVER}/defects`, payload)
}

export function actOnDefect(
  id: number | string,
  payload: { action: string; comment?: string; assigneeId?: number },
): Promise<DefectDetail> {
  return http.post<DefectDetail>(`${DELIVER}/defects/${id}/actions`, payload)
}

export function defectFromExecution(executionId: number | string): Promise<DefectRow> {
  return http.post<DefectRow>(`${DELIVER}/defects/from-execution/${executionId}`)
}

export function defectBoard(params: DefectListQuery): Promise<BoardMetrics> {
  return http.get<BoardMetrics>(`${DELIVER}/defects/board`, params)
}

export function defectBoardItems(
  params: DefectListQuery & { metric?: string },
): Promise<PageResponse<DefectRow>> {
  return http.get<PageResponse<DefectRow>>(`${DELIVER}/defects/board/items`, params)
}

export function exportDefectsCsv(params: DefectListQuery): Promise<void> {
  return download(`${DELIVER}/defects/export.csv`, params, 'defects.csv')
}

/* ---------------- 任务 / 迭代 ---------------- */

export function listTasks(requirementId: number | string): Promise<TaskRow[]> {
  return http.get<TaskRow[]>(`${DELIVER}/requirements/${requirementId}/tasks`)
}

export function createTask(payload: {
  requirementId: number
  title: string
  assigneeId?: number
  estimateHours?: number
  startDate?: string
  dueDate?: string
}): Promise<TaskRow> {
  return http.post<TaskRow>(`${DELIVER}/tasks`, payload)
}

export function updateTask(id: number | string, payload: Partial<TaskRow>): Promise<TaskRow> {
  return http.patch<TaskRow>(`${DELIVER}/tasks/${id}`, payload)
}

export function moveTask(
  id: number | string,
  payload: { status: string; sortNo: number },
): Promise<TaskRow> {
  return http.post<TaskRow>(`${DELIVER}/tasks/${id}/move`, payload)
}

export function listSprints(releaseId: number | string): Promise<Array<{ id: number; name: string }>> {
  return http.get(`${DELIVER}/sprints`, { releaseId })
}

export function createSprint(payload: {
  releaseId: number
  name: string
  goal?: string
  startDate?: string
  endDate?: string
  capacityDays?: number
}): Promise<{ id: number; name: string }> {
  return http.post(`${DELIVER}/sprints`, payload)
}

export function sprintBoard(id: number | string): Promise<SprintBoard> {
  return http.get<SprintBoard>(`${DELIVER}/sprints/${id}/board`)
}

export function closeSprint(
  id: number | string,
  payload: { migrateToSprintId?: number },
): Promise<void> {
  return http.post<void>(`${DELIVER}/sprints/${id}/close`, payload)
}

/* ---------------- 用例 ---------------- */

export function listTestCases(params: {
  moduleId?: number | string
  keyword?: string
  priority?: string
  type?: string
  page?: number
  size?: number
}): Promise<PageResponse<TestCaseRow>> {
  return http.get<PageResponse<TestCaseRow>>(`${DELIVER}/testcases`, params)
}

export function getTestCase(
  id: number | string,
  version?: number,
): Promise<TestCaseDetail> {
  return http.get<TestCaseDetail>(`${DELIVER}/testcases/${id}`, { version })
}

export function listCaseVersions(
  id: number | string,
): Promise<Array<{ versionNo: number; createdAt: string; createdBy?: string; current?: boolean }>> {
  return http.get(`${DELIVER}/testcases/${id}/versions`)
}

export function createTestCase(payload: {
  moduleId?: number
  title: string
  requirementId?: number
  type?: string
  priority?: string
  precondition?: string
  steps: Array<{ action: string; expected: string }>
  tags?: string[]
  automated?: boolean
  scriptRef?: string
}): Promise<TestCaseDetail> {
  return http.post<TestCaseDetail>(`${DELIVER}/testcases`, payload)
}

export function saveTestCase(
  id: number | string,
  payload: Record<string, unknown>,
): Promise<TestCaseDetail> {
  return http.put<TestCaseDetail>(`${DELIVER}/testcases/${id}`, payload)
}

export function deleteTestCase(id: number | string): Promise<void> {
  return http.del<void>(`${DELIVER}/testcases/${id}`)
}

export function copyTestCase(id: number | string, title: string): Promise<TestCaseDetail> {
  return http.post<TestCaseDetail>(`${DELIVER}/testcases/${id}/copy`, { title })
}

export function importTestCases(
  form: FormData,
): Promise<{ ok: number; failed: number; errors: Array<{ row: number; message: string }> }> {
  return http.post(`${DELIVER}/testcases/import`, form)
}

export function exportTestCasesCsv(params: Record<string, unknown>): Promise<void> {
  return download(`${DELIVER}/testcases/export.csv`, params, 'testcases.csv')
}

/* ---------------- 用例模块（≤3 层，服务端校验层级） ---------------- */

export function listTestModules(params: {
  domainId?: number | string
  vendorId?: number | string
} = {}): Promise<TestModuleRow[]> {
  return http.get<TestModuleRow[]>(`${DELIVER}/test-modules`, params)
}

/**
 * 新建或改名模块：带 id 走 PUT /test-modules/{id}（只改 name/sortNo），
 * 不带 id 走 POST（parentId 决定层级，服务端拒绝第 4 层）。
 */
export function saveTestModule(payload: {
  id?: number
  parentId?: number | null
  name: string
  domainId?: number | null
  vendorId?: number | null
  sortNo?: number
}): Promise<TestModuleRow> {
  return payload.id
    ? http.put<TestModuleRow>(`${DELIVER}/test-modules/${payload.id}`, payload)
    : http.post<TestModuleRow>(`${DELIVER}/test-modules`, payload)
}

export function deleteTestModule(id: number | string): Promise<void> {
  return http.del<void>(`${DELIVER}/test-modules/${id}`)
}

/* ---------------- 测试计划 ---------------- */

export function listTestPlans(params: {
  releaseId?: number | string
  page?: number
  size?: number
}): Promise<PageResponse<{ id: number; code: string; name: string; status?: string; releaseName?: string }>> {
  return http.get(`${DELIVER}/test-plans`, params)
}

export function createTestPlan(payload: {
  releaseId: number
  name: string
  ownerId?: number
  windowStart?: string
  windowEnd?: string
  caseIds?: number[]
}): Promise<{ id: number; code: string }> {
  return http.post(`${DELIVER}/test-plans`, payload)
}

export function getTestPlan(id: number | string): Promise<TestPlanDetail> {
  return http.get<TestPlanDetail>(`${DELIVER}/test-plans/${id}`)
}

export function addCasesToPlan(id: number | string, caseIds: number[]): Promise<void> {
  return http.post<void>(`${DELIVER}/test-plans/${id}/cases`, { caseIds })
}

export function removeCaseFromPlan(id: number | string, caseId: number | string): Promise<void> {
  return http.del<void>(`${DELIVER}/test-plans/${id}/cases/${caseId}`)
}

export function executeCase(
  planId: number | string,
  payload: { caseId: number; result: string; note?: string; evidence?: string },
): Promise<{ executionId: number }> {
  return http.post(`${DELIVER}/test-plans/${planId}/execute`, payload)
}

export function planSummary(id: number | string): Promise<PlanSummary> {
  return http.get<PlanSummary>(`${DELIVER}/test-plans/${id}/summary`)
}

export type { PlanCaseRow }

/* ---------------- 门禁 ---------------- */

export function evaluateTestGate(requirementId: number | string): Promise<GateDecision> {
  // 这是门禁的唯一事实源：页面只渲染它的结果，绝不本地重算
  return http.get<GateDecision>(`${DELIVER}/test-gate`, { requirementId })
}

/* ---------------- 追溯 ---------------- */

export function listTraceability(params: {
  type?: string
  domainId?: number | string
  vendorId?: number | string
  gap?: boolean | string
  page?: number
  size?: number
}): Promise<PageResponse<TraceRow>> {
  return http.get<PageResponse<TraceRow>>(`${DELIVER}/traceability`, {
    ...params,
    gap: params.gap === true ? 1 : params.gap,
  })
}

export function traceDetail(requirementId: number | string): Promise<Record<string, unknown>> {
  return http.get(`${DELIVER}/traceability/${requirementId}`)
}

/* ---------------- 指标 ---------------- */

export function defectMetrics(params: DefectListQuery): Promise<BoardMetrics> {
  return http.get<BoardMetrics>(`${DELIVER}/metrics/defects`, params)
}

export function testPassMetrics(params: {
  releaseId?: number | string
  planId?: number | string
}): Promise<TestPassMetrics> {
  return http.get<TestPassMetrics>(`${DELIVER}/metrics/test-pass`, params)
}

/**
 * 指标字典。后端返回 `{items, allVersions}` 包装（MetricController.Dictionary），
 * 这里拆成数组，页面拿到的就是行；需要历史版本时传 allVersions=true。
 */
export async function listMetricDefinitions(allVersions = false): Promise<MetricDefinition[]> {
  const response = await http.get<{ items?: MetricDefinition[] }>(
    `${DELIVER}/metrics/definitions`,
    { allVersions },
  )
  return response?.items ?? []
}
