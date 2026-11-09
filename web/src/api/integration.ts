/**
 * 集成域端点封装（M7 · TECHNICAL §4.1–§4.6 / §8.2）。
 *
 * 四条铁律，页面不得绕过：
 * 1. 前缀只在这里拼（`/api/req`）；页面传 id 与参数，不自己拼 URL，也不直连四个外部系统
 *    （Jenkins/GitLab/Sonar/Nexus 由服务端单一出站口代理，AC-2.1）；
 * 2. 四态、计数、是否阻断、能否提交一律用服务端下发（`AdmissionSnapshot.counts` 等）；
 *    前端不做二次统计、不内置状态机 —— 构建状态机在 Java，MR 单调推进在服务端；
 * 3. 凭据只写不读：`credential/secret` 只出现在 `IntegrationConfigCmd`（请求方向），
 *    响应里永远只有 `hasCredential/credentialLast4`，因此本文件不定义任何"读凭据"的类型；
 * 4. 409/400 原样抛 `ApiError`（`client.ts` 已约定不弹 toast），由页面渲染常驻冲突条。
 */
import { http } from './client'
import type { PageQuery, PageResponse } from './types'

const REQ = '/api/req'

/* ---------------- 配置（四槽：JENKINS / GITLAB / SONAR / NEXUS） ---------------- */

/** 四个被接入的系统；顺序即管理页卡片顺序（DESIGN §5.1） */
export type IntegrationSystem = 'JENKINS' | 'GITLAB' | 'SONAR' | 'NEXUS'

/**
 * 服务端 `auth_type` 的取值。**以落盘的 schema 为准**：
 * `V8__ci_integration.sql` 的 `auth_type ... COMMENT 'NONE/BASIC/TOKEN/PRIVATE_TOKEN'`
 * 与 `IntegrationConfigEntity:59` 一致；TECHNICAL §4.1 早期写的 `USERNAME_PASSWORD`
 * 已按实现收敛为 `BASIC`（用户名+口令），前端不另造第五个取值。
 */
export type IntegrationAuthType = 'NONE' | 'BASIC' | 'TOKEN' | 'PRIVATE_TOKEN'

/**
 * `extra` 的具名字段按系统写死（服务端校验）：
 * JENKINS `{jobName}`；GITLAB `{projectPath}`；SONAR `{projectKey?}`；NEXUS `{repository, allowedHosts?}`。
 * 用一张"全字段可选"的表而不是 `Record<string, unknown>`：页面按系统取字段，拼错名字 TS 就会报。
 */
export interface IntegrationExtra {
  jobName?: string
  projectPath?: string
  projectKey?: string
  repository?: string
  allowedHosts?: string[]
  [key: string]: unknown
}

/** 连通性测试结果：200 恒返回（失败也是一种结果），AC-1.4 */
export interface IntegrationTestResult {
  ok: boolean
  latencyMs?: number | null
  code?: string | null
  message?: string | null
  checkedAt?: string | null
}

/** 配置视图：凭据只露末四位，永不回显明文/密文（AC-1.2 / AC-10.1） */
export interface IntegrationConfigView {
  id: number
  system: IntegrationSystem
  name: string
  baseUrl: string
  authType: IntegrationAuthType
  username?: string | null
  hasCredential: boolean
  credentialLast4?: string | null
  hasSecret: boolean
  secretLast4?: string | null
  extra?: IntegrationExtra | null
  enabled: boolean
  timeoutMs: number
  lastTest?: IntegrationTestResult | null
}

/**
 * 新建/修改配置的请求体。
 * 语义（只有服务端一处实现）：`credential/secret` 缺省或 `''` = 保持原值；
 * `clearCredential: true` 必须同时给 `confirmName` 且等于 `name`，否则 400 `CONFIRM_NAME_MISMATCH`。
 */
export interface IntegrationConfigCmd {
  system: IntegrationSystem
  name: string
  baseUrl: string
  authType: IntegrationAuthType
  username?: string | null
  credential?: string
  secret?: string
  extra?: IntegrationExtra | null
  timeoutMs?: number
  clearCredential?: boolean
  confirmName?: string
}

export function listConfigs(system?: IntegrationSystem | string): Promise<IntegrationConfigView[]> {
  return http.get<IntegrationConfigView[]>(`${REQ}/integration/configs`, system ? { system } : undefined)
}

export function createConfig(body: IntegrationConfigCmd): Promise<IntegrationConfigView> {
  return http.post<IntegrationConfigView>(`${REQ}/integration/configs`, body)
}

export function patchConfig(id: number, body: IntegrationConfigCmd): Promise<IntegrationConfigView> {
  return http.patch<IntegrationConfigView>(`${REQ}/integration/configs/${id}`, body)
}

export function deleteConfig(id: number): Promise<void> {
  return http.del<void>(`${REQ}/integration/configs/${id}`)
}

/** 连通性测试：不改动已保存配置；结果只用于就地提示与 `lastTest` 刷新 */
export function testConfig(id: number): Promise<IntegrationTestResult> {
  return http.post<IntegrationTestResult>(`${REQ}/integration/configs/${id}/test`)
}

/* ---------------- 同步日志（脱敏摘要的唯一出口在服务端） ---------------- */

export type IntegrationSyncResult = 'SUCCESS' | 'FAILED' | 'IGNORED' | 'RETRY_EXHAUSTED'

export interface IntegrationLogRow {
  id: number
  correlationId: string
  system: IntegrationSystem
  /** 语义动作：TRIGGER_BUILD / POLL_BUILD / MR_SYNC / SONAR_REFRESH / ARTIFACT_SEARCH… */
  operation: string
  method?: string | null
  /** 已脱敏的目标 URL（query 里的 token 已被服务端抹掉，AC-10.1） */
  targetUrl?: string | null
  requestSummary?: string | null
  responseSummary?: string | null
  httpStatus?: number | null
  result: IntegrationSyncResult
  errorCode?: string | null
  retryCount: number
  durationMs?: number | null
  createdAt?: string | null
}

/**
 * 日志筛选（`client.ts` 的 query 参数是 `Record<string, unknown>`，
 * 这里显式带索引签名，与 `api/quality.ts` 的 PageQuery 同一写法）
 */
export interface IntegrationLogQuery extends PageQuery, Record<string, unknown> {
  system?: string
  result?: string
  correlationId?: string
  /** ISO-8601 local date-time，闭区间；省略即不限 */
  from?: string
  to?: string
  /** 操作 / 目标 URL / 请求摘要 / 错误码的模糊匹配 */
  keyword?: string
}

export type IntegrationLogPage = PageResponse<IntegrationLogRow>

export function listLogs(query: IntegrationLogQuery = {}): Promise<IntegrationLogPage> {
  return http.get<IntegrationLogPage>(`${REQ}/integration/logs`, query)
}

/** 单条日志明细；不存在或跨租户 404 `INTEGRATION_SYNC_LOG_NOT_FOUND`。 */
export function getLog(id: number): Promise<IntegrationLogRow> {
  return http.get<IntegrationLogRow>(`${REQ}/integration/logs/${id}`)
}

/* ---------------- 准入项开关（①②③⑥ 由服务端常量锁死） ---------------- */

export interface AdmissionItemView {
  code: string
  name: string
  /** 1..9 固定顺序；顺序的唯一来源是 `AdmissionItemPolicy`，前端不排序 */
  order: number
  /** 锁定项拒绝关闭：409 `ITEM_LOCKED`（detail.itemCode） */
  locked: boolean
  enabled: boolean
  description?: string | null
}

export interface AdmissionToggle {
  code: string
  enabled: boolean
}

export function listAdmissionItems(): Promise<AdmissionItemView[]> {
  return http.get<AdmissionItemView[]>(`${REQ}/integration/admission-items`)
}

export function toggleAdmissionItems(items: AdmissionToggle[]): Promise<AdmissionItemView[]> {
  return http.put<AdmissionItemView[]>(`${REQ}/integration/admission-items`, { items })
}

/* ---------------- 构建（Jenkins） ---------------- */

export type BuildStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILURE' | 'ABORTED'

/** 活动构建 = QUEUED / RUNNING；其余为终态，轮询必须停 */
export const ACTIVE_BUILD_STATUSES: BuildStatus[] = ['QUEUED', 'RUNNING']

export function isActiveBuild(status: BuildStatus | string | null | undefined): boolean {
  return status === 'QUEUED' || status === 'RUNNING'
}

export interface BuildStage {
  name: string
  status: string
  durationMs?: number | null
}

export interface BuildView {
  id: number
  deployOrderId: number
  status: BuildStatus
  jobName?: string | null
  buildNumber?: number | null
  queueId?: string | null
  queuedAt?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  durationMs?: number | null
  stages?: BuildStage[]
  /** console 外链：服务端已校验与 base_url 同源，不同源存 NULL（防钓鱼跳转） */
  consoleUrl?: string | null
  /** 2h 超时时刻；超时后 timedOut=true 并入准入第 7 项 FAIL */
  timeoutAt?: string | null
  retries: number
  timedOut: boolean
  /** 触发人（`build_record.triggered_by`）；服务端未下发时卡上显示 — */
  triggeredBy?: string | null
}

export interface BuildTriggerCmd {
  branch?: string
}

/** 202 结果：排队即返回，状态收敛靠轮询与 webhook */
export interface BuildTriggerResult {
  buildId: number
  status: BuildStatus
  correlationId: string
}

export function triggerBuild(
  deployOrderId: number,
  cmd: BuildTriggerCmd = {},
): Promise<BuildTriggerResult> {
  return http.post<BuildTriggerResult>(`${REQ}/deploy-orders/${deployOrderId}/builds`, cmd)
}

/** 最近 N 条（默认 5，DESIGN 构建卡）。服务端返回 `{items:[…]}` */
export async function listBuilds(deployOrderId: number, limit = 5): Promise<BuildView[]> {
  const page = await http.get<{ items?: BuildView[] }>(`${REQ}/deploy-orders/${deployOrderId}/builds`, {
    limit,
  })
  return page?.items ?? []
}

export function getBuild(buildId: number): Promise<BuildView> {
  return http.get<BuildView>(`${REQ}/builds/${buildId}`)
}

/** 交互读：单次尝试（失败降级），不参与后台重试链 */
export function refreshBuild(buildId: number): Promise<BuildView> {
  return http.post<BuildView>(`${REQ}/builds/${buildId}/refresh`)
}

/* ---------------- 代码（GitLab MR） ---------------- */

export type MrState = 'OPENED' | 'MERGED' | 'CLOSED' | 'LOCKED'

export interface MrCard {
  gitlabProject: string
  mrIid: number
  mrUrl: string
  title?: string | null
  state: MrState
  sourceBranch?: string | null
  targetBranch?: string | null
  approvalsCount?: number | null
  additions?: number | null
  deletions?: number | null
  lastEventAt?: string | null
  mergedAt?: string | null
  /** 兜底轮询超过阈值未更新：页面标「数据可能过期」，不让旧状态冒充当前 */
  stale?: boolean
}

export interface MrConfirmResult {
  requirementId: number
  status: string
  advanced: boolean
}

export function syncMr(requirementId: number, mrUrl: string): Promise<MrCard> {
  return http.post<MrCard>(`${REQ}/requirements/${requirementId}/mr-sync`, { mrUrl })
}

export function getMr(requirementId: number): Promise<MrCard | null> {
  return http.get<MrCard | null>(`${REQ}/requirements/${requirementId}/mr`)
}

/** 人工确认推进到「已合入」（`mrAutoAdvance=false` 的默认路径，只有 RD 参与） */
export function confirmMerge(requirementId: number, note?: string): Promise<MrConfirmResult> {
  return http.post<MrConfirmResult>(`${REQ}/requirements/${requirementId}/mr/confirm-merge`, { note })
}

/* ---------------- 质量门（Sonar） ---------------- */

export type SonarStatus = 'OK' | 'ERROR' | 'UNBOUND' | 'UNAVAILABLE'

export type SonarMetricKey =
  | 'coverage'
  | 'bugs'
  | 'vulnerabilities'
  | 'codeSmells'
  | 'duplicatedLinesDensity'

/** 五度量：任一缺失由服务端判 FAIL(METRIC_MISSING)，不用 0 冒充 */
export type SonarMetrics = Partial<Record<SonarMetricKey, number | null>>

/**
 * 达标阈值（`SonarThresholds` 的服务端判定结果回显）。
 * TECHNICAL §4.4 的 SonarCard 契约未列该字段：服务端不下发时页面只渲染值与结论，
 * 不会自己算一套阈值（口径只在一处）。
 */
export type SonarThresholds = Partial<Record<SonarMetricKey, string | number>>

export interface SonarCard {
  projectKey?: string | null
  status: SonarStatus
  metrics?: SonarMetrics | null
  thresholds?: SonarThresholds | null
  /** 复用 `quality_gate_snapshot(source='SONAR')`，同值同 hash ⇒ 不新增行 */
  snapshotId?: number | null
  hash?: string | null
  fetchedAt?: string | null
  stale?: boolean
  /** 只有 SKIP 语义才带：`NOT_CONFIGURED` 等，页面按原因码取文案 */
  skipReason?: string | null
}

export function refreshSonar(requirementId: number): Promise<SonarCard> {
  return http.post<SonarCard>(`${REQ}/requirements/${requirementId}/sonar/refresh`)
}

export function getSonar(requirementId: number): Promise<SonarCard | null> {
  return http.get<SonarCard | null>(`${REQ}/requirements/${requirementId}/sonar`)
}

/** 需求详情第 8 tab「集成」的一次性载荷：代码卡 + Sonar 卡 + 最近 10 条同步记录 */
export interface RequirementIntegration {
  code: MrCard | null
  sonar: SonarCard | null
  sync: IntegrationLogRow[]
}

export function getRequirementIntegration(requirementId: number): Promise<RequirementIntegration> {
  return http.get<RequirementIntegration>(`${REQ}/requirements/${requirementId}/integration`)
}

/* ---------------- 版本级集成设置（mrAutoAdvance 默认关闭） ---------------- */

export interface ReleaseIntegrationSettings {
  releaseId: number
  mrAutoAdvance: boolean
  defaultSonarProjectKey?: string | null
}

export interface ReleaseIntegrationSettingsCmd {
  mrAutoAdvance?: boolean
  defaultSonarProjectKey?: string | null
}

export function getReleaseIntegration(releaseId: number): Promise<ReleaseIntegrationSettings> {
  return http.get<ReleaseIntegrationSettings>(`${REQ}/releases/${releaseId}/integration-settings`)
}

export function putReleaseIntegration(
  releaseId: number,
  body: ReleaseIntegrationSettingsCmd,
): Promise<ReleaseIntegrationSettings> {
  return http.put<ReleaseIntegrationSettings>(
    `${REQ}/releases/${releaseId}/integration-settings`,
    body,
  )
}

/* ---------------- 制品（Nexus） ---------------- */

export type ArtifactSource = 'NEXUS' | 'MANUAL'
export type ArtifactStatus = 'REGISTERED' | 'VERIFIED' | 'PROMOTED'

/**
 * 检索结果项：坐标 + sha256（三方一致校验的输入，不由前端判定）。
 *
 * <p>字段名与服务端 `ArtifactViews.ArtifactItem` 一一对应：`id` 是 **Nexus 资产 id（字符串）**，
 * 只用于展示与去重，绝不是本地 `artifact_record` 主键——拿它去当 `artifact_id` 绑定会命中
 * 另一条制品行或 404。`repo` 是 Nexus 仓库名；`repository` 是视图层沿用的同一值镜像，
 * 由 {@link searchArtifacts} 统一填充，避免每个页面各写一次 `repo ?? repository` 兜底。</p>
 */
export interface ArtifactItem {
  /** Nexus 资产 id（字符串，非本地主键） */
  id?: string | null
  repo?: string | null
  /** 与 {@link ArtifactItem.repo} 同值的镜像，供既有视图直接读取 */
  repository?: string | null
  groupId?: string | null
  artifactName: string
  version: string
  packaging?: string | null
  classifier?: string | null
  sha256?: string | null
  downloadUrl?: string | null
  sizeBytes?: number | null
}

export interface ArtifactSearchResult {
  items: ArtifactItem[]
  /** Nexus 不可用时的降级：返回空列表 + 原因，页面不白屏（AC-2.3） */
  degraded: boolean
  reason?: string | null
}

export interface ArtifactView {
  id: number
  repo: string
  groupId?: string | null
  artifactName: string
  version: string
  packaging?: string | null
  sha256: string
  source: ArtifactSource
  status: ArtifactStatus
  downloadUrl?: string | null
  sizeBytes?: number | null
  promotedFromId?: number | null
  promotedAt?: string | null
}

/**
 * 制品坐标（登记/绑定的输入）。视图层沿用 `repo` 这个短名，服务端字段是 `repository`，
 * 两者在 {@link bindArtifact} 里翻译一次，不在每个调用点各写一遍。
 *
 * <p>`source='MANUAL'` 时服务端只记录你填的坐标与 sha256、不去 Nexus 核实，
 * 准入第 9 项因此 SKIP（跳过 ≠ 通过）；要让第 9 项 PASS，必须给 `source='NEXUS'`
 * 且带 `downloadUrl`，由服务端回查 Nexus 元数据做 sha256 三方一致。</p>
 */
export interface ArtifactManualRef {
  repo: string
  /** 与 {@link ArtifactManualRef.repo} 同义的显式拼写（服务端字段名） */
  repository?: string
  groupId: string
  artifactName: string
  version: string
  packaging?: string
  classifier?: string
  sha256: string
  downloadUrl?: string | null
  sizeBytes?: number | null
}

/**
 * 绑定：`artifactId`（绑定已存在的本地制品行）或 `manual`（按坐标登记/绑定）二选一。
 * 从 Nexus 检索结果里选中一条时走 `manual` + `source:'NEXUS'` + `downloadUrl`
 * ——检索结果是坐标，不是本地行，没有可绑的 `artifactId`。
 */
export interface ArtifactBindCmd {
  artifactId?: number
  source?: ArtifactSource
  manual?: ArtifactManualRef
}

export interface ArtifactPromoteResult {
  artifactId: number
  promotedFromId?: number | null
  status: 'PROMOTED'
}

export async function searchArtifacts(
  query: string,
  repository?: string,
  limit = 20,
  version?: string,
): Promise<ArtifactSearchResult> {
  const result = await http.get<ArtifactSearchResult>(`${REQ}/integration/artifacts/search`, {
    q: query,
    repository,
    version,
    limit,
  })
  // 服务端字段是 repo；补一份 repository 镜像，视图不必各自兜底
  return {
    ...result,
    items: (result.items ?? []).map((it) => ({
      ...it,
      repository: it.repository ?? it.repo ?? null,
    })),
  }
}

/**
 * 绑定制品到发布单。
 *
 * <p>服务端 `BindCmd` 的坐标字段是**顶层**的 `repository/groupId/…`（嵌套 `manual` 也认，
 * 但那是第二套字段名）。前端的 `ArtifactManualRef` 用 `repo` 短名，因此这里拍平成服务端
 * 形状——字段名只有一个翻译点，页面不参与。`source` 不传时按 MANUAL 处理（与「不确定来源
 * 就不去 Nexus 核实」的保守口径一致）。</p>
 */
export function bindArtifact(
  deployOrderId: number,
  body: ArtifactBindCmd,
): Promise<ArtifactView> {
  return http.post<ArtifactView>(
    `${REQ}/deploy-orders/${deployOrderId}/artifacts`,
    toBindPayload(body),
  )
}

function toBindPayload(body: ArtifactBindCmd): Record<string, unknown> {
  if (!body.manual) {
    return body.artifactId == null ? {} : { artifactId: body.artifactId }
  }
  const m = body.manual
  return {
    source: body.source ?? 'MANUAL',
    repository: (m.repository ?? m.repo ?? '').trim(),
    groupId: m.groupId,
    artifactName: m.artifactName,
    version: m.version,
    packaging: m.packaging,
    classifier: m.classifier,
    sha256: m.sha256,
    downloadUrl: m.downloadUrl ?? null,
    sizeBytes: m.sizeBytes ?? null,
  }
}

export function getBoundArtifact(deployOrderId: number): Promise<ArtifactView | null> {
  return http.get<ArtifactView | null>(`${REQ}/deploy-orders/${deployOrderId}/artifacts`)
}

/**
 * 晋级到目标仓库：目标仓库由服务端配置决定，body 只在确需覆盖时传。
 * 幂等——已晋级返回既有行 200；checksum 不一致 409 `CHECKSUM_MISMATCH`。
 */
export function promoteArtifact(
  artifactId: number,
  body: { targetRepo?: string } = {},
): Promise<ArtifactPromoteResult> {
  return http.post<ArtifactPromoteResult>(`${REQ}/artifacts/${artifactId}/promote`, body)
}
